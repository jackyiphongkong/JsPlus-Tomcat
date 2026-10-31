@echo off
setlocal enabledelayedexpansion

REM ------------------------------------------------------------------
# _RunOne.bat ? internal shared launcher for a single test suite.
# Usage: _RunOne.bat <TestName> [scriptEngine]
#   <TestName>    e.g. TestDomainProxy, TestSecurityFilter, ...
#   [scriptEngine] default graalvm.js
# Runs pre-cleanup -> StopAll -> H2 init/start/wait -> run one JS -> StopAll.
# Does NOT run LoadTest, does NOT archive reports (that is TestAll.bat's job).
REM ------------------------------------------------------------------

if "%~1"=="" (
    echo Usage: %~nx0 ^<TestName^> [scriptEngine]
    exit /b 1
)
set TEST_NAME=%~1

if "%2"=="" (
    set SCRIPT_ENGINE=graalvm.js
) else (
    set SCRIPT_ENGINE=%2
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
    if defined JAVA_HOME (
        set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA_BIN=java"
    )
)

SET "JvmParamsName=JsPlus_JvmParams_%AppName%"
call set "JvmParams=%%%JvmParamsName%%%"
if "%JvmParams%"=="" set "JvmParams=-Xms256m -Xmx1024m -Xss256k"

if not defined JsPlus_H2_PORT set "JsPlus_H2_PORT=3307"
set "H2_PORT=%JsPlus_H2_PORT%"

REM Step -1: pre-cleanup (align with TestAll.bat)
echo [%TEST_NAME] Cleaning JsPlus\.tmp\* and logs\* ...
if exist "%JS_PLUS_PATH%.tmp" del /q /s "%JS_PLUS_PATH%.tmp\*" >nul 2>&1
if exist "%JS_PLUS_PATH%..\logs" del /q /s "%JS_PLUS_PATH%..\logs\*" >nul 2>&1

REM Step 0: Stop existing services
echo [%TEST_NAME] Stopping existing services...
call "%JS_PLUS_PATH%bin\StopAll.bat"

REM Step 1: H2 init + start
netstat -ano | findstr ":%H2_PORT%" | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 goto :h2_already

echo [%TEST_NAME] Initializing database...
call "%JS_PLUS_PATH%bin\StartH2Console.bat" init tomcatdb
if errorlevel 1 (
    echo [%TEST_NAME] ERROR: Database initialization failed.
    exit /b 1
)
echo [%TEST_NAME] Starting H2 server (port %H2_PORT%)...
call "%JS_PLUS_PATH%bin\BG_StartH2.bat" %SCRIPT_ENGINE%

:h2_already
echo [%TEST_NAME] Waiting for H2 :%H2_PORT%...
set "H2_READY="
for /l %%i in (1,1,45) do (
    netstat -ano | findstr ":%H2_PORT%" | findstr "LISTENING" >nul 2>&1
    if not errorlevel 1 set "H2_READY=1"
    if defined H2_READY goto :h2_ready
    ping -n 2 127.0.0.1 >nul 2>&1
)
:h2_ready
if not defined H2_READY (
    echo [%TEST_NAME] ERROR: H2 did not start on port %H2_PORT%.
    exit /b 1
)
echo [%TEST_NAME] H2 ready.

REM Step 2: run the single suite via _RunOne.js (which bootstraps Tomcat)
set "TESTALL_TASK=1"
set "RUN_SUITE=%TEST_NAME%"
echo [%TEST_NAME] Running %TEST_NAME%.js (via _RunOne.js) ...
%JAVA_BIN% %JvmParams% -cp "%DEPEND_LIBS%" com.killcoding.jsplus.Main -ScriptEngine "%SCRIPT_ENGINE%" -Env script -WriteHistory false -Task "%JS_PLUS_PATH%../test/_RunOne.js" < NUL
set "RC=%ERRORLEVEL%"
echo [%TEST_NAME] exit code: %RC%

REM Step 3: cleanup services
echo [%TEST_NAME] Stopping all remaining services...
call "%JS_PLUS_PATH%bin\StopAll.bat"

exit /b %RC%
