@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-fileapex.ps1" %*
exit /b %ERRORLEVEL%
