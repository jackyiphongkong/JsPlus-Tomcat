@echo off
title Kill Java Processes by Startup Bat Name
setlocal enabledelayedexpansion

set "ScriptDir=%~dp0"
set "ScriptDir=!ScriptDir:~0,-1!"

REM --- project root: only command lines holding a path under it are eligible ---
for %%i in ("%~dp0..\..") do set "ProjRoot=%%~fi"

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

REM --- derive the keyword from the bat file name: StartH2.bat -> StartH2 ---
set "Base=%~n1"

if "!Base!"=="" (
    echo [ERROR] Cannot derive keyword from "%~n1".
    pause & exit /b 1
)

echo ==============================================
echo TargetBat   : !TargetBat!
echo Keyword     : !Base!
echo ProjectRoot : !ProjRoot!
echo ==============================================
echo Searching and terminating Java processes...
echo (only processes whose command line holds a path under ProjectRoot are eligible,
echo  so a launcher in another project that happens to share the script or jar name is skipped)

powershell -NoProfile -Command ^
    "$root='!ProjRoot!'; $kw='!Base!';" ^
    "$rootPath=($root.Replace('/','\') + '\').ToLower(); $kw=$kw.ToLower();" ^
    "$procs=Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | Where-Object { $_.CommandLine -ne $null -and $_.CommandLine.Replace('/','\').ToLower().Contains($rootPath) -and $_.CommandLine.ToLower().Contains($kw) };" ^
    "if ($procs) {" ^
    "  $procs | ForEach-Object {" ^
    "    Write-Host ('Terminating PID: {0}' -f $_.ProcessId) -ForegroundColor Yellow;" ^
    "    Write-Host ('  CMD: {0}' -f $_.CommandLine) -ForegroundColor DarkGray;" ^
    "    Stop-Process -Id $_.ProcessId -Force" ^
    "  };" ^
    "  Write-Host ('Terminated {0} process(es).' -f @($procs).Count) -ForegroundColor Green" ^
    "} else {" ^
    "  Write-Host 'No matching Java processes found.' -ForegroundColor Cyan" ^
    "}"

echo.
endlocal