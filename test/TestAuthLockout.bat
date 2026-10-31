@echo off
setlocal
call "%~dp0_RunOne.bat" TestAuthLockout %*
exit /b %ERRORLEVEL%