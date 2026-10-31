@echo off
setlocal
call "%~dp0_RunOne.bat" TestIpFilter %*
exit /b %ERRORLEVEL%