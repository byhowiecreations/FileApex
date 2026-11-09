# Starts scripts/build-fileapex.sh from PowerShell. All build logic is in that script; this file only finds
# Git for Windows' bash and passes the arguments and the exit code through.
$ErrorActionPreference = 'Stop'
$script = (Join-Path $PSScriptRoot 'build-fileapex.sh') -replace '\\', '/'
$env:CHERE_INVOKING = '1'
$candidates = @(
    (Join-Path $env:ProgramFiles 'Git\bin\bash.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Git\bin\bash.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Git\bin\bash.exe')
)
$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) { $candidates += (Join-Path (Split-Path (Split-Path $git.Source)) 'bin\bash.exe') }
$bash = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $bash) {
    Write-Error 'Git for Windows is required (it provides bash.exe). Install it from https://git-scm.com/download/win and run this again.'
    exit 1
}
& $bash -l $script @args
exit $LASTEXITCODE
