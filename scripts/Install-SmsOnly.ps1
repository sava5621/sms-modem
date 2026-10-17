[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial, [string]$Adb = 'adb')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root = Split-Path $PSScriptRoot -Parent
Assert-Modem $Adb $Serial
Test-ArtifactHashes $root
$remote = '/data/local/tmp/sms-only-install'
$null = Invoke-ModemShell $Adb $Serial "mkdir -p $remote"
foreach ($file in @('modem/modem-usb-lan.sh','modem/sms-only-policy.sh','modem/install-sms-only.sh','artifacts/sms-only-control.jar')) {
    $null = Invoke-ModemAdb $Adb $Serial @('push', (Join-Path $root $file), "$remote/")
}
$rootAttempted = $false
try {
    $rootAttempted = $true
    Set-ModemRoot $Adb $Serial $true
    $result = Invoke-ModemShell $Adb $Serial "sh $remote/install-sms-only.sh"
    if ($result -notmatch '(?m)^SMS_ONLY_INSTALLED backup=') { throw "Installation marker missing: $result" }
    Write-Output $result
} finally {
    if ($rootAttempted) { Set-ModemRoot $Adb $Serial $false }
}
Write-Output 'Policy saved; ADB is back to uid=2000. Reboot and verify as described in docs/06-verification.md.'
