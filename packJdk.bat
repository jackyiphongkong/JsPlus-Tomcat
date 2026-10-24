@echo off
setlocal enabledelayedexpansion

:: ===========================================================================
:: packJdk.bat - Build a trimmed custom JRE for JsPlus (jdeps + jlink)
::
:: Usage:   packJdk.bat
::
:: Prereq:  A JDK with jdeps/jlink must be available. The bundled GraalVM
::          (Java\default) is used by default; system JAVA_HOME is the fallback.
:: Output:  %BIN_PATH%/Java/runtime  (jlink-trimmed runtime, previous output is replaced)
:: ===========================================================================

:: ---------------------------------------------------------------------------
:: 1. Base paths
:: ---------------------------------------------------------------------------
set "BIN_PATH=%~dp0"
set "LIB_HOME=%BIN_PATH%LibHome"
set "RUNTIME_PACK_OUTPUT=%BIN_PATH%/Java/runtime"

:: ---------------------------------------------------------------------------
:: 2. Build classpath from every JAR under LibHome
:: ---------------------------------------------------------------------------
set "DEPEND_LIBS="
for /r "%LIB_HOME%" %%j in (*.jar) do (
    set "DEPEND_LIBS=!DEPEND_LIBS!;%%j"
)
set "DEPEND_LIBS=!DEPEND_LIBS:~1!"
if not defined DEPEND_LIBS (
    echo Error: No JAR files found under %LIB_HOME%
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: 3. Locate Java - bundled GraalVM preferred, system JAVA_HOME as fallback
:: ---------------------------------------------------------------------------
set "JAVA_HOME_ORIG=%JAVA_HOME%"
set "JAVA_HOME=%BIN_PATH%Java\default"
set "JAVA_BIN=%JAVA_HOME%\bin"

if not exist "%JAVA_BIN%\jdeps.exe" (
    echo WARN: Bundled Java not found at %JAVA_HOME%, falling back to system JAVA_HOME...
    if defined JAVA_HOME_ORIG (
        set "JAVA_HOME=%JAVA_HOME_ORIG%"
        set "JAVA_BIN=%JAVA_HOME_ORIG%\bin"
    ) else (
        echo Error: Java JDK not found. Set JAVA_HOME to a JDK to use packJdk.
        exit /b 1
    )
)

:: ---------------------------------------------------------------------------
:: 4. Resolve module dependencies with jdeps
:: ---------------------------------------------------------------------------
set "TMPFILE=%TEMP%\jdeps_modules_%RANDOM%.txt"

"%JAVA_BIN%\jdeps.exe" --multi-release 25 --ignore-missing-deps -cp "%DEPEND_LIBS%" --print-module-deps %LIB_HOME%\jsplus\default\*.jar > "%TMPFILE%" 2>nul
if errorlevel 1 (
    echo Error: jdeps failed. Check the classpath or JDK version.
    if exist "%TMPFILE%" del "%TMPFILE%"
    exit /b 1
)

:: Keep the last non-warning line (jdeps prints "Warning: split package ..."
:: before the module list); the final line is the comma-separated module list.
for /f "delims=" %%m in ('findstr /v /c:"Warning:" "%TMPFILE%"') do set "modules=%%m"
if exist "%TMPFILE%" del "%TMPFILE%"

if "%modules%"=="" (
    echo Error: jdeps returned no module dependencies.
    exit /b 1
)

echo Depend modules: %modules%

:: ---------------------------------------------------------------------------
:: 5. Build the trimmed runtime with jlink
:: ---------------------------------------------------------------------------
if exist "%RUNTIME_PACK_OUTPUT%\" (
    echo Removing previous runtime at %RUNTIME_PACK_OUTPUT%
    rmdir /s /q "%RUNTIME_PACK_OUTPUT%"
)

"%JAVA_BIN%\jlink.exe" --module-path "%JAVA_HOME%\jmods" --add-modules %modules% --output "%RUNTIME_PACK_OUTPUT%"
if errorlevel 1 (
    echo Error: jlink failed. See messages above.
    exit /b 1
)

echo Custom JRE created at %RUNTIME_PACK_OUTPUT%
endlocal
exit /b 0
