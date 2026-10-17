[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial, [string]$Adb = 'adb')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root = Split-Path $PSScriptRoot -Parent
Assert-Modem $Adb $Serial
$destination = Join-Path $root ('.local/backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Serial)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$null = Invoke-ModemAdb $Adb $Serial @('push', (Join-Path $root 'modem/backup-app.sh'), '/data/local/tmp/sms-backup-app.sh')
$rootAttempted = $false
try {
    $rootAttempted = $true
    Set-ModemRoot $Adb $Serial $true
    $result = Invoke-ModemShell $Adb $Serial 'sh /data/local/tmp/sms-backup-app.sh'
    $match = [regex]::Match($result, '(?m)^SMS_BACKUP_OK=(/data/local/sms-only/sms-app-[0-9-]+\.tar)\r?$')
    if (-not $match.Success) { throw "Backup marker missing: $result" }
    $remote = $match.Groups[1].Value
    $null = Invoke-ModemAdb $Adb $Serial @('pull', $remote, $destination)
    $local = Join-Path $destination (Split-Path $remote -Leaf)
    $hash = Invoke-ModemShell $Adb $Serial "busybox sha256sum $remote"
    if (($hash -split '\s+')[0] -ne (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash) { throw 'Backup SHA-256 mismatch' }
    $null = Invoke-ModemShell $Adb $Serial "rm -f $remote"
    Get-FileHash -LiteralPath $local -Algorithm SHA256 | Format-List
    Write-Output "Private backup saved to $destination"
} finally {
    if ($rootAttempted) { Set-ModemRoot $Adb $Serial $false }
}
