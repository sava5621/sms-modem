[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb',[switch]$AllowTemporaryRoot)
$ErrorActionPreference='Stop'
if(-not $AllowTemporaryRoot){throw 'Updating boot networking requires explicitly authorized temporary root: -AllowTemporaryRoot'}
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root=Split-Path $PSScriptRoot -Parent
Assert-Modem $Adb $Serial
Test-ArtifactHashes $root
if((Invoke-ModemShell $Adb $Serial 'dumpsys telephony.registry') -notmatch '(?m)mCallState=0\s*$'){throw 'Wait until the call has ended'}
$private=Join-Path $root ('.local/dt/network-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $private | Out-Null
$apk=Invoke-ModemShell $Adb $Serial 'pm path local.modem.hub'
if($apk -notmatch '^package:(/data/app/local\.modem\.hub-[0-9]+\.apk)$'){throw 'Expected the existing DT Hub'}
$null=Invoke-ModemAdb $Adb $Serial @('pull',$Matches[1],(Join-Path $private 'before.apk'))
$stage='/data/local/tmp/dt-network-update'
$null=Invoke-ModemShell $Adb $Serial "mkdir -p $stage"
foreach($name in @('apply-network.sh','modem-usb-lan.sh','sms-only-policy.sh')){
 $null=Invoke-ModemAdb $Adb $Serial @('push',(Join-Path $root ('devices/dt-20221021/system/'+$name)),($stage+'/'+$name))
 $null=Invoke-ModemShell $Adb $Serial "sh -n $stage/$name"
}
try{
 Set-ModemRoot $Adb $Serial $true
 $result=Invoke-ModemShell $Adb $Serial "sh $stage/apply-network.sh"
 $result | Set-Content -LiteralPath (Join-Path $private 'result.txt') -Encoding UTF8
 if($result -notmatch 'DT_NETWORK_COMPLETE backup=(/data/local/sms-only/backups/dt-network-[0-9]+-[0-9]+)'){throw 'Missing DT network completion marker'}
}finally{
 try{$null=Invoke-ModemAdb $Adb $Serial @('pull','/data/local/sms-only/backups',$private)}catch{Write-Warning 'Private backups remain on DT; pulling them failed'}
 Set-ModemRoot $Adb $Serial $false
}
$result=Invoke-ModemAdb $Adb $Serial @('install','-r',(Join-Path $root 'artifacts/sms-modem-dt-1.8.1.apk'))
if($result -notmatch '(?m)^Success\r?$'){throw "DT update failed; restore the private before.apk if necessary: $result"}
$null=Invoke-ModemShell $Adb $Serial 'am start -n local.modem.hub/.MainActivity --ez apply_configuration true --ez apply_security true'
Write-Output 'DT boot networking and port 80 updated. Panel: http://192.168.100.2/ . ADB is back to UID 2000.'
