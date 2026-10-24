@echo off
setlocal

:: Default script engine to graalvm.js if no argument provided
if "%1"=="" (
    set SCRIPT_ENGINE=graalvm.js
) else (
    set SCRIPT_ENGINE=%1
)

:: Get script directory and parent path
set BIN_PATH=%~dp0
set JS_PLUS_PATH_ORIGIN=%BIN_PATH%..\
SET "JS_PLUS_PATH=%JS_PLUS_PATH_ORIGIN:\=/%"


powershell -Command Start-Process -FilePath 'cmd.exe' -ArgumentList '/c %JS_PLUS_PATH%bin/StartH2.bat %SCRIPT_ENGINE%' -WindowStyle Hidden
