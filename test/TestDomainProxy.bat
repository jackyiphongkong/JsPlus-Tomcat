@echo off
setlocal
call "%~dp0_RunOne.bat" TestDomainProxy %*
exit /b %ERRORLEVEL%