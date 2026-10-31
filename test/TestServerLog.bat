@echo off
setlocal
call "%~dp0_RunOne.bat" TestServerLog %*
exit /b %ERRORLEVEL%