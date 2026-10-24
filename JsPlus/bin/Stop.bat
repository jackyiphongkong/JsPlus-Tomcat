@echo off
title Kill Java Processes by Startup Bat Name
setlocal enabledelayedexpansion

set "ScriptDir=%~dp0"
set "ScriptDir=!ScriptDir:~0,-1!"

if "%~1"=="" (
    echo Usage: %~nx0 ^<StartXxx.bat^>
    echo   e.g. %~nx0 StartH2.bat
    pause & exit /b 1
)

set "Arg=%~1"
echo !Arg! | findstr /r /c:"^[A-Za-z]:" >nul
if not errorlevel 1 (
    set "TargetBat=!Arg!"
) else (
    set "TargetBat=!ScriptDir!\!Arg!"
)
for %%i in ("!TargetBat!") do set "TargetBat=%%~fi"

REM --- 從檔名推導關鍵字：StartH2 -> H2 ---
set "Base=%~n1"

if "!Base!"=="" (
    echo [ERROR] Cannot derive keyword from "%~n1".
    pause & exit /b 1
)

echo ==============================================
echo TargetBat : !TargetBat!
echo Keyword   : !Base!
echo ==============================================
echo Searching and terminating Java processes...

powershell -NoProfile -Command ^
    "$kw='!Base!';" ^
    "$procs=Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | Where-Object { $_.CommandLine -like \"*$kw*\" };" ^
    "if ($procs) {" ^
    "  $procs | ForEach-Object {" ^
    "    Write-Host ('Terminating PID: {0}' -f $_.ProcessId) -ForegroundColor Yellow;" ^
    "    Write-Host ('  CMD: {0}' -f $_.CommandLine) -ForegroundColor DarkGray;" ^
    "    Stop-Process -Id $_.ProcessId -Force" ^
    "  };" ^
    "  Write-Host ('Terminated {0} process(es).' -f $procs.Count) -ForegroundColor Green" ^
    "} else {" ^
    "  Write-Host 'No matching Java processes found.' -ForegroundColor Cyan" ^
    "}"

echo.
endlocal