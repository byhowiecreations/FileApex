@echo off
setlocal
cd /d "%~dp0"
set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%SCRIPT_DIR%FileApexBootstrap.ps1" %*
endlocal
