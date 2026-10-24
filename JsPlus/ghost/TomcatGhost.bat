@echo off
setlocal enabledelayedexpansion

if "%1"=="" (
    set SCRIPT_ENGINE=graalvm.js
) else (
    set SCRIPT_ENGINE=%1
)

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
    SET "JvmParams=-Xms64m -Xmx64m -Xss256k"
    echo WARN: Missing %JvmParamsName% then using default params
) else (
    echo Using System Env JVM Params
)

echo Using "%JvmParams%"

%JAVA_BIN% %JvmParams% -cp "%DEPEND_LIBS%" com.killcoding.jsplus.Main -ScriptEngine "%SCRIPT_ENGINE%" -Env ghost -WriteHistory false -GhostName TomcatGhost

endlocal