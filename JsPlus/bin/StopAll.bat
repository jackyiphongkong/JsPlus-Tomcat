@echo off
title Kill Java Processes by Path
setlocal enabledelayedexpansion

REM Get the parent directory of the script location (application root)
set "ScriptDir=%~dp0"
set "ScriptDir=%ScriptDir:~0,-1%"
for %%i in ("%ScriptDir%\..") do set "AppRootDir=%%~fi"

REM Convert backslash to forward slash to match the path format in command line
set "Pattern=!AppRootDir:\=/!"

echo ==============================================
echo Application Root: !AppRootDir!
echo Pattern: !Pattern!
echo ==============================================
echo Searching and terminating Java processes...

REM Execute PowerShell command (already verified working)
powershell -NoProfile -Command ^
    "$pattern='!Pattern!';" ^
    "$procs=Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | Where-Object { $_.CommandLine -like \"*$pattern*\" };" ^
    "if ($procs) { $procs | ForEach-Object { Write-Host \"Terminating PID: $($_.ProcessId)\" -ForegroundColor Yellow; Stop-Process -Id $_.ProcessId -Force }; Write-Host \"Terminated $($procs.Count) process(es).\" -ForegroundColor Green } else { Write-Host \"No matching Java processes found.\" -ForegroundColor Cyan }"

echo.
echo Operation completed.
