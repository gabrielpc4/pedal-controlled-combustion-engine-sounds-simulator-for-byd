@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-fh6.ps1" %*
exit /b %ERRORLEVEL%
