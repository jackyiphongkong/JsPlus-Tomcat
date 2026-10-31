@echo off
setlocal
call "%~dp0_RunOne.bat" TestSecurityFilter %*
exit /b %ERRORLEVEL%