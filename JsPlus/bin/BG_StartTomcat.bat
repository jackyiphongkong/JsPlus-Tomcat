@echo off
setlocal

if "%1"=="" (
    set SCRIPT_ENGINE=graalvm.js
) else (
    set SCRIPT_ENGINE=%1
)

set BIN_PATH=%~dp0
set JS_PLUS_PATH_ORIGIN=%BIN_PATH%..\
SET "JS_PLUS_PATH=%JS_PLUS_PATH_ORIGIN:\=/%"

powershell -Command Start-Process -FilePath 'cmd.exe' -ArgumentList '/c %JS_PLUS_PATH%/bin/StartTomcat.bat %SCRIPT_ENGINE%' -WindowStyle Hidden
