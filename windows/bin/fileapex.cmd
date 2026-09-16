@echo off
setlocal
REM Console-subsystem launcher must live next to FileApex.exe (app\ + runtime\ siblings).
REM Cannot be named fileapex.exe beside FileApex.exe — Windows FS is case-insensitive.
"%~dp0..\FileApexCli.exe" %*
exit /b %ERRORLEVEL%
