@echo off
setlocal
call "%~dp0_RunOne.bat" TestFreed %*
exit /b %ERRORLEVEL%