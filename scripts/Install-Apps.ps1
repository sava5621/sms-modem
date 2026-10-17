[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial, [string]$Adb = 'adb')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root = Split-Path $PSScriptRoot -Parent
Assert-Modem $Adb $Serial
Test-ArtifactHashes $root
foreach ($file in @('sms-modem-4.0.apk', 'wifi-off-1.0.apk')) {
    $result = Invoke-ModemAdb $Adb $Serial @('install', '-r', (Join-Path $root "artifacts/$file"))
    if ($result -notmatch '(?m)^Success\r?$') { throw "APK installation failed: $result" }
    Write-Output "$file installed"
}
$result = Invoke-ModemShell $Adb $Serial 'am start -n local.modem.sms/.MainActivity'
if ($result -match '(?im)error|exception') { throw $result }
$result = Invoke-ModemShell $Adb $Serial 'am start -n local.modem.wifioff/.EnableActivity'
if ($result -match '(?im)error|exception') { throw $result }
Write-Output 'Apps installed without uninstalling or clearing application data. Continue with docs/05-sms-only.md.'
