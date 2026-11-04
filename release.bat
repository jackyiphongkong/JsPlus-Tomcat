@echo off
setlocal enabledelayedexpansion

REM ==================================================================
REM JsPlus-Tomcat release helper - release.bat
REM Scans every sibling project folder under the scan root (default
REM parent folder of this project) and distributes the freshly built
REM jsplus-tomcat-<version>.jar into each project's
REM LibHome/jsplus/default. The previous jsplus-tomcat-*.jar there
REM is renamed to *.jar.bak (not deleted); other jars are left
REM untouched.
REM The source project itself (JsPlus-Tomcat) is skipped.
REM A project counts as a target only when it already holds a
REM jsplus-tomcat-*.jar: a project that has never consumed this
REM artifact is skipped, so scanning a shared root cannot drop the jar
REM into an unrelated project.
REM
REM Nothing is built or tested here: run 'ant build' (and
REM 'test\TestAll.bat') first, then this script copies the jar that
REM already exists. Same split as KillCoding-Core / JsPlus-Core /
REM JsPlus-LogReplaced release.bat.
REM
REM Source dir : <this project>/LibHome/jsplus/default  (ant package output)
REM Target dir : <sibling>/LibHome/jsplus/default           (runtime classpath)
REM Scan root  : default parent folder of this project, override via JS_PLUS_RELEASE_ROOT
REM Exit code  : 0 = success, 1 = failure
REM ==================================================================

set "TOMCAT_ROOT=%~dp0"
if "%TOMCAT_ROOT:~-1%"=="\" set "TOMCAT_ROOT=%TOMCAT_ROOT:~0,-1%"

REM ---- locate the latest built project jar ----
set "JAR_DIR=%TOMCAT_ROOT%\LibHome\jsplus\default"
if not exist "%JAR_DIR%" (
    echo [release] ERROR: build output dir not found: %JAR_DIR%
    exit /b 1
)
set "LATEST_JAR="
for /f "delims=" %%f in ('dir /b /o-d "%JAR_DIR%\jsplus-tomcat-*.jar" 2^>nul') do (
    if not defined LATEST_JAR set "LATEST_JAR=%%f"
)
if not defined LATEST_JAR (
    echo [release] ERROR: no jsplus-tomcat-*.jar under %JAR_DIR% - run 'ant build' first.
    exit /b 1
)
set "SRC_JAR=%JAR_DIR%\%LATEST_JAR%"
echo [release] Source : %SRC_JAR%

REM ---- scan root (defaults to the parent folder of this project) ----
set "SCAN_ROOT=%JS_PLUS_RELEASE_ROOT%"
if not defined JS_PLUS_RELEASE_ROOT (
    for %%i in ("%~dp0..") do set "SCAN_ROOT=%%~fi"
)
echo [release] Scan root : %SCAN_ROOT%
set "TOMCAT_NORM=%TOMCAT_ROOT:\=/%"

REM ---- iterate every sibling project ----
REM A project is touched only when jsplus-tomcat-*.jar already exists in
REM its LibHome/jsplus/default; a directory without that jar is reported as
REM Skip and left exactly as it is.
set /a COPIED=0
for /d %%d in ("%SCAN_ROOT%\*") do (
    set "PROJ=%%d"
    set "PROJ_NORM=!PROJ:\=/!"
    set "TGT_DIR=!PROJ!\LibHome\jsplus\default"
    if /i not "!PROJ_NORM!"=="%TOMCAT_NORM%" (
        if exist "!TGT_DIR!" (
            set "HAS_JAR="
            for /f "delims=" %%f in ('dir /b "!TGT_DIR!\jsplus-tomcat-*.jar" 2^>nul') do set "HAS_JAR=1"
            if not defined HAS_JAR (
                echo [release]   Skip  : !TGT_DIR! ^(no existing jsplus-tomcat-*.jar^)
            ) else (
                echo [release] Target : !TGT_DIR!
                for /f "delims=" %%f in ('dir /b "!TGT_DIR!\jsplus-tomcat-*.jar" 2^>nul') do (
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
)
if "%COPIED%"=="0" echo [release] No project holding an existing jsplus-tomcat-*.jar under %SCAN_ROOT%
echo [release] Done.
exit /b 0
