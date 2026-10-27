param([string]$Branch)
$ErrorActionPreference = 'Stop'
$env:Path = "C:\msys64\ucrt64\bin;" + $env:Path
Set-Location C:\Users\cliff\AndroidStudioProjects\FileApex
git fetch origin
git checkout -B $Branch "origin/$Branch"
git log --oneline -1
.\gradlew.bat copyWindowsBuilds --console=plain
exit $LASTEXITCODE
