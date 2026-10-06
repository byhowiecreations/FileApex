@echo off
setlocal
REM Build libFileApexTsnet.dll — in-process userspace Tailscale for Windows.
REM Must compile from native/tsnet. The repo root is not a Go module.
set "ROOT=%~dp0..\.."
set "OUT=%ROOT%\windows\build\Tsnet"
if not exist "%OUT%" mkdir "%OUT%"
where go >nul 2>&1
if errorlevel 1 (
  echo go not found — Windows tsnet DLL was not built.
  exit /b 1
)
set "GOTOOLCHAIN=go1.27.1"
set "CGO_ENABLED=1"
set "GOOS=windows"
set "GOARCH=amd64"
pushd "%ROOT%\native\tsnet"
if errorlevel 1 exit /b 1
python prepare_tsnet.py go > "%TEMP%\fileapex-tsnet-ldflags.txt"
if errorlevel 1 (
  type "%TEMP%\fileapex-tsnet-ldflags.txt"
  popd
  exit /b 1
)
set /p LDFLAGS=<"%TEMP%\fileapex-tsnet-ldflags.txt"
if not defined LDFLAGS (
  echo prepare_tsnet.py printed no linker flags.
  popd
  exit /b 1
)
go build -buildmode=c-shared -trimpath -ldflags "%LDFLAGS%" -o "%OUT%\libFileApexTsnet.dll" .\cmd\libtsnet
set "CODE=%ERRORLEVEL%"
popd
if not "%CODE%"=="0" exit /b %CODE%
echo Built %OUT%\libFileApexTsnet.dll
exit /b 0
