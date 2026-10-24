@echo off
setlocal enabledelayedexpansion

REM ------------------------------------------------------------------
REM JsPlus-Tomcat H2 database console / initializer
REM Usage:
REM   StartH2Console.bat                 Interactive shell (default DB: appdb)
REM   StartH2Console.bat <DB_NAME>       Interactive shell on the given DB
REM   StartH2Console.bat init [DB_NAME]  Reset + rebuild schema:
REM                                      fallback.sql -> change.sql
REM                                      (default DB for init: tomcatdb,
REM                                       matching DataSource.properties)
REM ------------------------------------------------------------------

set "INIT_MODE=0"
if /i "%1"=="init" (
    set "INIT_MODE=1"
    if "%2"=="" (
        set "DB_NAME=tomcatdb"
    ) else (
        set "DB_NAME=%2"
    )
) else (
    if "%1"=="" (
        set "DB_NAME=appdb"
    ) else (
        set "DB_NAME=%1"
    )
)

:: Get script directory and parent path
set BIN_PATH=%~dp0
set JS_PLUS_PATH_ORIGIN=%BIN_PATH%..\
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
    SET "JvmParams=-Xms128m -Xmx128m -Xss256k"
    echo WARN: Missing %JvmParamsName% then using default params
) else (
    echo Using System Env JVM Params
)

echo Using "%JvmParams%"

if "%INIT_MODE%"=="1" (
    echo [StartH2Console] Initializing database "%DB_NAME%"...
    echo [StartH2Console] Executing dbscript/fallback.sql ...
    "%JAVA_BIN%" %JvmParams% -cp "%DEPEND_LIBS%" org.h2.tools.RunScript -url "jdbc:h2:%JS_PLUS_PATH%../Database/%DB_NAME%" -user sa -script "%JS_PLUS_PATH%../dbscript/fallback.sql" -checkResults
    if errorlevel 1 (
        echo [StartH2Console] ERROR: fallback.sql failed
        exit /b 1
    )
    echo [StartH2Console] Executing dbscript/change.sql ...
    "%JAVA_BIN%" %JvmParams% -cp "%DEPEND_LIBS%" org.h2.tools.RunScript -url "jdbc:h2:%JS_PLUS_PATH%../Database/%DB_NAME%" -user sa -script "%JS_PLUS_PATH%../dbscript/change.sql" -checkResults
    if errorlevel 1 (
        echo [StartH2Console] ERROR: change.sql failed
        exit /b 1
    )
    echo [StartH2Console] Database "%DB_NAME%" initialized: 9 tables + sample_user.
) else (
    "%JAVA_BIN%" %JvmParams% -cp "%DEPEND_LIBS%" org.h2.tools.Shell -url "jdbc:h2:%JS_PLUS_PATH%../Database/%DB_NAME%" -user sa
)

endlocal
