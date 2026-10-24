@echo off
setlocal

SET BIN_PATH_ORIGIN=%~dp0

SET "BIN_PATH=%BIN_PATH_ORIGIN:\=/%"

if defined JAVA_HOME set "JAVA_HOME_ORIG=%JAVA_HOME%"
if defined ANT_HOME set "ANT_HOME_ORIG=%ANT_HOME%"

SET JAVA_HOME=%BIN_PATH%/Java/default/

SET ANT_HOME=%BIN_PATH%/ant/default/

if not exist "%JAVA_HOME%" (
    echo WARN: Bundled Java not found at %JAVA_HOME%, falling back to system JAVA_HOME...
    if defined JAVA_HOME_ORIG (
        SET "JAVA_HOME=%JAVA_HOME_ORIG%"
    ) else (
        set "JAVA_HOME="
    )
)

if not exist "%ANT_HOME%" (
    echo WARN: Bundled Ant not found at %ANT_HOME%, falling back to system ANT_HOME...
    if defined ANT_HOME_ORIG (
        SET "ANT_HOME=%ANT_HOME_ORIG%"
    ) else (
        set "ANT_HOME="
    )
)

if defined ANT_HOME (
    %ANT_HOME%/bin/ant.bat
) else (
    ant
)

endlocal