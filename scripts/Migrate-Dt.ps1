[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb',[switch]$AllowTemporaryRoot)
$ErrorActionPreference='Stop'
if(-not $AllowTemporaryRoot){throw 'Migration requires explicitly authorized temporary root: -AllowTemporaryRoot'}
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root=Split-Path $PSScriptRoot -Parent
Assert-Modem $Adb $Serial
Test-ArtifactHashes $root
if((Invoke-ModemShell $Adb $Serial 'pm list packages local.modem.hub') -match '(?m)^package:local\.modem\.hub\r?$'){throw 'Hub is already installed. Use Install-Dt.ps1 to update, or inspect a partial migration before continuing.'}
if((Invoke-ModemShell $Adb $Serial 'dumpsys telephony.registry') -notmatch '(?m)mCallState=0\s*$'){throw 'Wait until telephony is idle'}
$private=Join-Path $root ('.local/dt/migration-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $private | Out-Null
$oldPath=Invoke-ModemShell $Adb $Serial 'pm path local.modem.sms'
if($oldPath -notmatch '^package:(/data/app/local\.modem\.sms-[0-9]+\.apk)$'){throw 'Expected the existing DT SMS application'}
$null=Invoke-ModemAdb $Adb $Serial @('pull',$Matches[1],(Join-Path $private 'old-sms.apk'))
$result=Invoke-ModemAdb $Adb $Serial @('install',(Join-Path $root 'artifacts/sms-modem-dt-1.8.1.apk'))
if($result -notmatch '(?m)^Success\r?$'){throw "DT installation failed: $result"}
$stage='/data/local/tmp/dt-ui-migration'
$null=Invoke-ModemShell $Adb $Serial "mkdir -p $stage"
foreach($name in @('migrate.sh','modem-usb-lan.sh','sms-only-policy.sh')){
 $null=Invoke-ModemAdb $Adb $Serial @('push',(Join-Path $root ('devices/dt-20221021/system/'+$name)),($stage+'/'+$name))
}
$migrationSucceeded=$false
try{
 Set-ModemRoot $Adb $Serial $true
 $result=Invoke-ModemShell $Adb $Serial "sh $stage/migrate.sh"
 $result | Set-Content -LiteralPath (Join-Path $private 'migration.txt') -Encoding UTF8
 if($result -notmatch 'DT_MIGRATION_COMPLETE backup=(/data/local/sms-only/backups/dt-ui-[0-9]+-[0-9]+)'){throw 'Missing migration completion marker'}
 $null=Invoke-ModemAdb $Adb $Serial @('pull',$Matches[1],$private)
 $migrationSucceeded=$true
}finally{
 # Even a partially completed migration must leave a private backup and shell ADB.
 try{$null=Invoke-ModemAdb $Adb $Serial @('pull','/data/local/sms-only/backups',$private)}catch{Write-Warning 'Backup pull failed; device backups remain under /data/local/sms-only/backups'}
 Set-ModemRoot $Adb $Serial $false
}
if(-not $migrationSucceeded){throw 'Migration incomplete. Inspect the private backup; do not overwrite the new inbox.'}
$null=Invoke-ModemShell $Adb $Serial 'am start -n local.modem.hub/.MainActivity --ez apply_configuration true --ez apply_security true'
Write-Output "DT migrated; private backup: $private"
Write-Output 'Configure only this USB adapter with devices/dt-20221021/host/configure-windows.ps1 as administrator. Panel: http://192.168.100.2/'
Write-Output 'Save a greeting in the panel, then verify a real SMS, caller-heard greeting, recorded speech and automatic hangup.'
