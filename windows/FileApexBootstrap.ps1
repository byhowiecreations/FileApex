# FileApex Automated Pre-Flight System Dependency Checker & Launcher
# Checks x64 architecture, path validity, and Visual C++ 2015-2022 Redistributable (x64).

param(
    [string[]]$AppArgs = @()
)

$ErrorActionPreference = "SilentlyContinue"

# Always enforce current working directory to application installation directory
$appDir = $PSScriptRoot
Set-Location -Path $appDir

# Ensure runtime\bin and runtime\bin\server are in PATH for JVM native DLL resolution
$runtimeBin = Join-Path $appDir "runtime\bin"
$runtimeServerBin = Join-Path $appDir "runtime\bin\server"
if (Test-Path $runtimeBin) {
    $env:PATH = "$runtimeBin;$runtimeServerBin;" + $env:PATH
}

# 1. Architecture Validation
$arch = $env:PROCESSOR_ARCHITECTURE
$arch64 = $env:PROCESSOR_ARCHITEW6432
$isX64 = ($arch -eq "AMD64") -or ($arch64 -eq "AMD64")

if (-not $isX64) {
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show(
            "FileApex requires a 64-bit x64 Windows operating system.`n`nDetected Architecture: $arch`n`nFileApex may fail to launch or run properly on 32-bit or unsupported ARM/Snapdragon hardware.",
            "FileApex System Compatibility Warning",
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Warning
        ) | Out-Null
    } catch {
        Write-Warning "FileApex requires 64-bit x64 Windows. Detected architecture: $arch"
    }
}

# 2. Visual C++ Redistributable x64 Registry & System32 DLL Check
$vcRegKey64 = "HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\X64"
$vcRegKey32 = "HKLM:\SOFTWARE\WOW6432Node\Microsoft\VisualStudio\14.0\VC\Runtimes\X64"
$sys32 = [System.IO.Path]::Combine($env:SystemRoot, "System32")
$vcDll1 = Join-Path $sys32 "vcruntime140.dll"
$vcDll2 = Join-Path $sys32 "vcruntime140_1.dll"
$vcDll3 = Join-Path $sys32 "msvcp140.dll"

$vcRegistryInstalled = $false
if (Test-Path $vcRegKey64) {
    $installedVal = (Get-ItemProperty -Path $vcRegKey64 -Name "Installed" -ErrorAction SilentlyContinue).Installed
    if ($installedVal -eq 1) { $vcRegistryInstalled = $true }
}
if (-not $vcRegistryInstalled -and (Test-Path $vcRegKey32)) {
    $installedVal = (Get-ItemProperty -Path $vcRegKey32 -Name "Installed" -ErrorAction SilentlyContinue).Installed
    if ($installedVal -eq 1) { $vcRegistryInstalled = $true }
}

$vcDllsPresent = (Test-Path $vcDll1) -and (Test-Path $vcDll2) -and (Test-Path $vcDll3)
$vcFullyInstalled = $vcRegistryInstalled -and $vcDllsPresent

if (-not $vcFullyInstalled) {
    $localVcRedist = Join-Path $appDir "vc_redist.x64.exe"
    $targetVcInstaller = ""

    if (Test-Path $localVcRedist) {
        $targetVcInstaller = $localVcRedist
    } else {
        $tempVcRedist = Join-Path $env:TEMP "vc_redist.x64.exe"
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri "https://aka.ms/vs/17/release/vc_redist.x64.exe" -OutFile $tempVcRedist -UseBasicParsing -TimeoutSec 60
            if (Test-Path $tempVcRedist) {
                $targetVcInstaller = $tempVcRedist
            }
        } catch {
            Write-Warning "Could not download vc_redist.x64.exe automatically."
        }
    }

    if ($targetVcInstaller -and (Test-Path $targetVcInstaller)) {
        try {
            $process = Start-Process -FilePath $targetVcInstaller -ArgumentList "/install", "/quiet", "/norestart" -Wait -PassThru -ErrorAction Stop
            Write-Host "Visual C++ Redistributable x64 installation finished with code $($process.ExitCode)"
        } catch {
            Write-Warning "Failed to launch silent VC++ Redistributable installer."
        }
    }
}

# 3. Launch Main JVM Executable with explicit WorkingDirectory
$exePath = Join-Path $appDir "FileApex.exe"
if (Test-Path $exePath) {
    if ($AppArgs -and $AppArgs.Count -gt 0) {
        Start-Process -FilePath $exePath -WorkingDirectory $appDir -ArgumentList $AppArgs
    } else {
        Start-Process -FilePath $exePath -WorkingDirectory $appDir
    }
} else {
    Write-Error "FileApex.exe not found in $appDir"
}
