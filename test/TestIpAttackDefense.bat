@echo off
setlocal
call "%~dp0_RunOne.bat" TestIpAttackDefense %*
exit /b %ERRORLEVEL%