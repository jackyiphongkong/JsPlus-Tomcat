@echo off
setlocal enabledelayedexpansion

REM ------------------------------------------------------------------
REM JsPlus-Tomcat automated test suite — TestAll.bat
REM Flow:
REM   1) Database init + H2 server (port %H2_PORT%) — skipped when H2 is
REM      already running (the DB file is locked by the running server,
REM      so embedded fallback.sql/change.sql cannot apply).
REM   2) Wait for H2 :%H2_PORT% to LISTEN.
REM   3) Run TestAll.js against the running server.
REM      TestAll.js bootstraps the embedded Tomcat in the SAME JVM
REM      (mirror of script/StartTomcat.js) so the global `tomcat` object
REM      and TomcatScriptConsole.MasterConsole exist — that is the only
REM      mode in which the suites can run.
REM      Stdin is redirected to NUL so com.killcoding.jsplus.Main exits
REM      right after the -Task completes (otherwise it blocks on the
REM      interactive scanner, see Main.java scanner loop).
REM   4) LoadTest.js (embedded Tomcat, LOADTEST_* env tunables).
REM   5) Archive reports as docs/reports/<version>.jar following the
REM      JsPlus-Core docs layout (docs/latest/report/ is the browsable
REM      newest-reports site; test_reports/ keeps a legacy copy).
REM ------------------------------------------------------------------

if "%1"=="" (
    set SCRIPT_ENGINE=graalvm.js
) else (
    set SCRIPT_ENGINE=%1
)

set BIN_PATH=%~dp0
set JS_PLUS_PATH_ORIGIN=%BIN_PATH%..\JsPlus\
SET "JS_PLUS_PATH=%JS_PLUS_PATH_ORIGIN:\=/%"
set LIB_HOME=%JS_PLUS_PATH%../LibHome/

set "DEPEND_LIBS="
for /r "%JS_PLUS_PATH_ORIGIN%..\LibHome" %%j in (*.jar) do set "DEPEND_LIBS=!DEPEND_LIBS!;%%j"
set "DEPEND_LIBS=!DEPEND_LIBS:~1!"
if not defined DEPEND_LIBS (
    echo Error: No JARs found under LibHome
    exit /b 1
)

FOR %%I IN ("%JS_PLUS_PATH%..") DO SET "AppName=%%~nxI"

SET "JavaDirEnvName=JsPlus_JavaDir_%AppName%"
call set "JAVA_DIR=%%%JavaDirEnvName%%%"
if not defined JAVA_DIR set "JAVA_DIR=default"
set "JAVA_BIN=%JS_PLUS_PATH%../Java/%JAVA_DIR%/bin/java.exe"

if not exist "%JAVA_BIN%" (
    echo WARN: Bundled Java not found at %JAVA_BIN%, falling back to system Java...
    if defined JAVA_HOME (
        set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_BIN=java"
    )
)

SET "JvmParamsName=JsPlus_JvmParams_%AppName%"
call set "JvmParams=%%%JvmParamsName%%%"

echo "%JvmParamsName%"

if "%JvmParams%"=="" (
    SET "JvmParams=-Xms256m -Xmx1024m -Xss256k"
    echo WARN: Missing %JvmParamsName% then using default params
) else (
    echo Using System Env JVM Params
)

echo Using "%JvmParams%"

REM H2 TCP port: read from the JsPlus config (env JsPlus_H2_PORT, default 3307).
if not defined JsPlus_H2_PORT set "JsPlus_H2_PORT=3307"
set "H2_PORT=%JsPlus_H2_PORT%"
echo [TestAll] H2 port: %H2_PORT%

REM ------------------------------------------------------------------
REM Step 0: Stop any existing services (H2 / Tomcat / consoles) so the
REM database file is unlocked and ports %H2_PORT%/8080 are free.
REM ------------------------------------------------------------------
echo [TestAll] Stopping existing services...
call "%JS_PLUS_PATH%bin\StopAll.bat"

REM ------------------------------------------------------------------
REM Step 1: Database init + H2 server (port %H2_PORT%)
REM Skipped when H2 is already running: the DB file is locked by the
REM running server, so embedded fallback.sql/change.sql cannot apply.
REM ------------------------------------------------------------------
netstat -ano | findstr ":%H2_PORT%" | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 goto :h2_already

echo [TestAll] Initializing database (fallback.sql -^> change.sql)...
call "%JS_PLUS_PATH%bin\StartH2Console.bat" init tomcatdb
if errorlevel 1 (
    echo [TestAll] ERROR: Database initialization failed.
    exit /b 1
)
echo [TestAll] Starting H2 server (port %H2_PORT%)...
call "%JS_PLUS_PATH%bin\BG_StartH2.bat" %SCRIPT_ENGINE%

:h2_already
echo [TestAll] Waiting for H2 :%H2_PORT%...
set "H2_READY="
for /l %%i in (1,1,45) do (
    netstat -ano | findstr ":%H2_PORT%" | findstr "LISTENING" >nul 2>&1
    if not errorlevel 1 set "H2_READY=1"
    if defined H2_READY goto :h2_ready
    ping -n 2 127.0.0.1 >nul 2>&1
)
:h2_ready
if not defined H2_READY (
    echo [TestAll] ERROR: H2 did not start on port %H2_PORT%.
    exit /b 1
)
echo [TestAll] H2 ready.

REM ------------------------------------------------------------------
REM Step 2: Run TestAll.js against the running server.
REM TestAll.js bootstraps the embedded Tomcat in this JVM (mirror of
REM script/StartTomcat.js), waits for :8080, runs the 4 suites and
REM stops the server before finishing.
REM "< NUL" closes stdin: the JsPlus Main scanner gets EOF right after
REM the -Task finishes and the JVM exits cleanly (Main.java).
REM TESTALL_TASK tells TestAll.js it is running as the CI/batch task (not an
REM interactive console eval), so it hard-halts its own JVM (Runtime.halt(0))
REM after the suites — otherwise the non-daemon TomcatServer await thread
REM would keep this JVM alive forever and Step 3 would never start.
set "TESTALL_TASK=1"
%JAVA_BIN% %JvmParams% -cp "%DEPEND_LIBS%" com.killcoding.jsplus.Main -ScriptEngine "%SCRIPT_ENGINE%" -Env script -WriteHistory false -Task "%JS_PLUS_PATH%../test/TestAll.js" < NUL
set "RC=%ERRORLEVEL%"
echo TestAll exit code: %RC%

REM ------------------------------------------------------------------
REM Step 3: Simple load test — TestAll.js stops shared services when it
REM finishes, so make sure a WORKING H2 is reachable on :%H2_PORT% first
REM (netstat may still show a half-dead H2 holding the port, so we do a
REM real TCP connect test); then start a standalone Tomcat, run LoadTest
REM against it, and stop Tomcat again.
REM Outputs: ../test_reports/loadtest-result.json + loadtest-dashboard.html
REM Override via env: LOADTEST_URL, LOADTEST_CONCURRENCY,
REM                    LOADTEST_TOTAL, LOADTEST_TIMEOUT
REM ------------------------------------------------------------------
set "H2_OK="
powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',%H2_PORT%);$c.Close();exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 set "H2_OK=1"
if not defined H2_OK (
    echo [TestAll] H2 not reachable on :%H2_PORT%, restarting H2...
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%H2_PORT%" ^| findstr "LISTENING"') do (
        taskkill /F /PID %%p >nul 2>&1
    )
    call "%JS_PLUS_PATH%bin\BG_StartH2.bat" %SCRIPT_ENGINE%
    set "H2_UP="
    for /l %%i in (1,1,45) do (
        powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',%H2_PORT%);$c.Close();exit 0}catch{exit 1}" >nul 2>&1
        if not errorlevel 1 set "H2_UP=1"
        if defined H2_UP goto :h2_up
        ping -n 2 127.0.0.1 >nul 2>&1
    )
    :h2_up
    if not defined H2_UP (
        echo [TestAll] WARN: H2 did not start; skipping load test.
        goto :skip_loadtest
    )
    echo [TestAll] H2 restarted.
) else (
    echo [TestAll] H2 reachable on :%H2_PORT%.
)

echo [TestAll] Starting Tomcat for load test...
call "%JS_PLUS_PATH%bin\BG_StartTomcat.bat" %SCRIPT_ENGINE%

echo [TestAll] Waiting for Tomcat :8080/:8443...
set "WEB_READY="
for /l %%i in (1,1,60) do (
    netstat -ano | findstr ":8080" | findstr "LISTENING" >nul 2>&1
    if not errorlevel 1 (
        netstat -ano | findstr ":8443" | findstr "LISTENING" >nul 2>&1
        if not errorlevel 1 set "WEB_READY=1"
    )
    if defined WEB_READY goto :web_ready
    ping -n 2 127.0.0.1 >nul 2>&1
)
:web_ready
if not defined WEB_READY (
    echo [TestAll] WARN: Tomcat did not start; skipping load test.
    goto :skip_loadtest
)
echo [TestAll] Tomcat ready. Running load test...

REM LoadTest.js — pure-JS load test through the JsPlus console (replaces the
REM old compiled LoadTest.java step; connection reuse makes ~1000 requests
REM finish in seconds). Env vars LOADTEST_URL/CONCURRENCY/TOTAL/TIMEOUT are
REM read by the script itself.
if not defined LOADTEST_URL set "LOADTEST_URL=https://localhost:8443/default/"
if not defined LOADTEST_CONCURRENCY set "LOADTEST_CONCURRENCY=10"
if not defined LOADTEST_TOTAL set "LOADTEST_TOTAL=10000"
if not defined LOADTEST_TIMEOUT set "LOADTEST_TIMEOUT=60"

pushd "%JS_PLUS_PATH%..\test"
%JAVA_BIN% %JvmParams% -cp "%DEPEND_LIBS%" com.killcoding.jsplus.Main -ScriptEngine %SCRIPT_ENGINE% -Env script -WriteHistory false -Task "%JS_PLUS_PATH%..\test\LoadTest.js" < NUL
set "LT_RC=%ERRORLEVEL%"
popd

echo [TestAll] Stopping Tomcat after load test...
call "%JS_PLUS_PATH%bin\StopTomcat.bat"

:skip_loadtest
REM TestAll.js now exits via System.exit(0) (NOT ScriptConsole.exit()), so
REM the shared exit.tmp signal file is never written and the H2 ghost started
REM by BG_StartH2.bat keeps running. Stop ALL remaining services (H2) here so
REM TestAll leaves nothing behind. RC was captured right after TestAll.js.
if not defined RC set "RC=1"
echo [TestAll] Stopping all remaining services...
call "%JS_PLUS_PATH%bin\StopAll.bat"

REM ------------------------------------------------------------------
REM Step 4b: Archive the test reports under the same docs layout as the
REM javadoc site (JsPlus-Core standard): docs/reports/<version>.jar (one
REM jar per run) plus docs/latest/report/ as the browsable newest reports.
REM Version is taken from the jsplus-tomcat jar name. A failure to archive
REM is only a WARN and never changes the suite exit code (RC captured above).
REM ------------------------------------------------------------------
set "TOMCAT_JAR="
for %%j in ("%JS_PLUS_PATH_ORIGIN%..\LibHome\jsplus\default\jsplus-tomcat-*.jar") do set "TOMCAT_JAR=%%j"
if not defined TOMCAT_JAR (
    echo WARN: jsplus-tomcat-*.jar not found, skipping report archive.
) else (
    for %%f in ("!TOMCAT_JAR!") do set "TOMCAT_FNAME=%%~nxf"
    set "TOMCAT_VERSION=!TOMCAT_FNAME:jsplus-tomcat-=!"
    set "TOMCAT_VERSION=!TOMCAT_VERSION:.jar=!"
    if not defined TOMCAT_VERSION (
        echo WARN: Cannot extract version from !TOMCAT_JAR!, skipping report archive.
    ) else (
        echo [TestAll] Archiving test reports as docs/reports/!TOMCAT_VERSION!.jar ^(docs/latest/report^)...
        if not exist "%JS_PLUS_PATH_ORIGIN%..\docs\reports" mkdir "%JS_PLUS_PATH_ORIGIN%..\docs\reports"
        if not exist "%JS_PLUS_PATH_ORIGIN%..\docs\latest\report" mkdir "%JS_PLUS_PATH_ORIGIN%..\docs\latest\report"
        if exist "%JS_PLUS_PATH_ORIGIN%..\docs\reports\!TOMCAT_VERSION!.jar" del "%JS_PLUS_PATH_ORIGIN%..\docs\reports\!TOMCAT_VERSION!.jar"
        for %%b in ("!JAVA_BIN!") do set "JAR_BIN=%%~dpbjar.exe"
        if exist "!JAR_BIN!" (
            "!JAR_BIN!" cf "%JS_PLUS_PATH_ORIGIN%..\docs\reports\!TOMCAT_VERSION!.jar" -C "%JS_PLUS_PATH_ORIGIN%..\docs\latest\report" .
            if errorlevel 1 (
                echo WARN: Failed to pack test report jar, continuing.
            ) else (
                echo [TestAll] Packed docs/reports/!TOMCAT_VERSION!.jar
            )
        ) else (
            echo WARN: jar.exe not found next to "!JAVA_BIN!", continuing.
        )
    )
)

echo TestAll exit code: %RC%
exit /b %RC%

endlocal
