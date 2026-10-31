@echo off
setlocal
call "%~dp0_RunOne.bat" TestTomcatServer %*
exit /b %ERRORLEVEL%