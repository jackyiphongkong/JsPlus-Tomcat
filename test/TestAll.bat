@echo off
setlocal enabledelayedexpansion

REM ------------------------------------------------------------------
REM JsPlus-Tomcat automated test suite — TestAll.bat
REM Flow:
REM   1) Database init + H2 server (port 3306) — skipped when H2 is
REM      already running (the DB file is locked by the running server,
REM      so embedded fallback.sql/change.sql cannot apply).
REM   2) Wait for H2 :3306 to LISTEN.
REM   3) Run TestAll.js against the running server.
REM      TestAll.js bootstraps the embedded Tomcat in the SAME JVM
REM      (mirror of script/StartTomcat.js) so the global `tomcat` object
REM      and TomcatScriptConsole.MasterConsole exist — that is the only
REM      mode in which the suites can run.
REM      Stdin is redirected to NUL so com.killcoding.jsplus.Main exits
REM      right after the -Task completes (otherwise it blocks on the
REM      interactive scanner, see Main.java scanner loop).
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

REM ------------------------------------------------------------------
REM Step 0: Stop any existing services (H2 / Tomcat / consoles) so the
REM database file is unlocked and ports 3306/8080 are free.
REM ------------------------------------------------------------------
echo [TestAll] Stopping existing services...
call "%JS_PLUS_PATH%bin\StopAll.bat"

REM ------------------------------------------------------------------
REM Step 1: Database init + H2 server (port 3306)
REM Skipped when H2 is already running: the DB file is locked by the
REM running server, so embedded fallback.sql/change.sql cannot apply.
REM ------------------------------------------------------------------
netstat -ano | findstr ":3306" | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 goto :h2_already

echo [TestAll] Initializing database (fallback.sql -^> change.sql)...
call "%JS_PLUS_PATH%bin\StartH2Console.bat" init tomcatdb
if errorlevel 1 (
    echo [TestAll] ERROR: Database initialization failed.
    exit /b 1
)
echo [TestAll] Starting H2 server (port 3306)...
call "%JS_PLUS_PATH%bin\BG_StartH2.bat" %SCRIPT_ENGINE%

:h2_already
echo [TestAll] Waiting for H2 :3306...
set "H2_READY="
for /l %%i in (1,1,45) do (
    netstat -ano | findstr ":3306" | findstr "LISTENING" >nul 2>&1
    if not errorlevel 1 set "H2_READY=1"
    if defined H2_READY goto :h2_ready
    timeout /t 1 /nobreak >nul
)
:h2_ready
if not defined H2_READY (
    echo [TestAll] ERROR: H2 did not start on port 3306.
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
REM ------------------------------------------------------------------
%JAVA_BIN% %JvmParams% -cp "%DEPEND_LIBS%" com.killcoding.jsplus.Main -ScriptEngine "%SCRIPT_ENGINE%" -Env script -WriteHistory false -Task "%JS_PLUS_PATH%../test/TestAll.js" < NUL
echo TestAll exit code: %ERRORLEVEL%
exit /b %ERRORLEVEL%

endlocal
