@echo off
setlocal enabledelayedexpansion

REM ==================================================================
REM JsPlus-Core release helper - release.bat
REM Scans every sibling project folder under the scan root (default
REM D:/Working). Any project that contains a LibHome/jsplus/default
REM folder is a release target: the freshly built jsplus-core-<version>
REM .jar is copied into its LibHome/jsplus/default. The previous
REM jsplus-core-*.jar there is renamed to *.jar.bak (not deleted);
REM other jars are left untouched.
REM The source project itself (JsPlus-Core) is skipped.
REM Scan root   : default D:/Working, override via JS_PLUS_RELEASE_ROOT
REM Exit code   : 0 = success, 1 = failure
REM ==================================================================

set CORE_ROOT=%~dp0
if "%CORE_ROOT:~-1%"=="\" set CORE_ROOT=%CORE_ROOT:~0,-1%

REM ---- locate the latest built project jar ----
set JAR_DIR=%CORE_ROOT%\LibHome\jsplus\default
if not exist "%JAR_DIR%" (
    echo [release] ERROR: build output dir not found: %JAR_DIR%
    exit /b 1
)
set "LATEST_JAR="
for /f "delims=" %%f in ('dir /b /o-d "%JAR_DIR%\jsplus-core-*.jar" 2^>nul') do (
    if not defined LATEST_JAR set "LATEST_JAR=%%f"
)
if not defined LATEST_JAR (
    echo [release] ERROR: no jsplus-core-*.jar under %JAR_DIR% - run 'ant build' first.
    exit /b 1
)
set SRC_JAR=%JAR_DIR%\%LATEST_JAR%
echo [release] Source : %SRC_JAR%

REM ---- scan root ----
set SCAN_ROOT=%JS_PLUS_RELEASE_ROOT%
if not defined SCAN_ROOT set "SCAN_ROOT=D:/Working"
set CORE_NORM=%CORE_ROOT:\=/%

REM ---- iterate every sibling project ----
set COPIED=0
for /d %%d in ("%SCAN_ROOT%\*") do (
    set PROJ=%%d
    set PROJ_NORM=!PROJ:\=/!
    set TGT_DIR=!PROJ!\LibHome\jsplus\default
    if /i not "!PROJ_NORM!"=="%CORE_NORM%" (
        if exist "!TGT_DIR!" (
            echo [release] Target : !TGT_DIR!
            for /f "delims=" %%f in ('dir /b "!TGT_DIR!\jsplus-core-*.jar" 2^>nul') do (
                if exist "!TGT_DIR!\%%f.bak" del /q "!TGT_DIR!\%%f.bak" >nul 2>&1
                ren "!TGT_DIR!\%%f" "%%f.bak" >nul 2>&1
            )
            copy /y "%SRC_JAR%" "!TGT_DIR!\%LATEST_JAR%" >nul
            if errorlevel 1 (
                echo [release] ERROR: copy to !TGT_DIR! failed
                exit /b 1
            )
            echo [release]   Copied : !TGT_DIR!\%LATEST_JAR%
            set /a COPIED+=1
        )
    )
)
if "%COPIED%"=="0" echo [release] No target project found under %SCAN_ROOT%
echo [release] Done.
exit /b 0
