@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check_package_name.ps1"
exit /b %ERRORLEVEL%

