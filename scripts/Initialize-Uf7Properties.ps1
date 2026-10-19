[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb',[switch]$AllowTemporaryRoot)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
if(-not $AllowTemporaryRoot){throw 'This operation requires explicit -AllowTemporaryRoot. It temporarily restarts adbd as root to write exactly two persistent properties.'}
$fp=Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint'
if($fp -ne 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.libinglin.20220328:userdebug/test-keys'){throw 'UF7 firmware mismatch'}
$status=Invoke-RestMethod 'http://192.168.100.1:8088/api/status' -TimeoutSec 10
if($status.callState -ne 0){throw 'Wait until the call has ended'}
$backup=Join-Path (Split-Path $PSScriptRoot -Parent) ('.local/uf7/properties-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'.json')
New-Item -ItemType Directory -Path (Split-Path $backup -Parent) -Force | Out-Null
[ordered]@{AdbTcp=(Invoke-ModemShell $Adb $Serial 'getprop persist.adb.tcp.port');RebootWatchdog=(Invoke-ModemShell $Adb $Serial 'getprop persist.ufi.nosignal.reboot')} | ConvertTo-Json | Set-Content -LiteralPath $backup -Encoding UTF8
try{
 Set-ModemRoot $Adb $Serial $true
 $null=Invoke-ModemShell $Adb $Serial 'setprop persist.adb.tcp.port -1'
 $null=Invoke-ModemShell $Adb $Serial 'setprop persist.ufi.nosignal.reboot no'
 if((Invoke-ModemShell $Adb $Serial 'getprop persist.adb.tcp.port') -ne '-1' -or (Invoke-ModemShell $Adb $Serial 'getprop persist.ufi.nosignal.reboot') -ne 'no'){throw 'Persistent property validation failed'}
}finally{Set-ModemRoot $Adb $Serial $false}
Write-Output 'UF7 persistent properties saved; ADB returned to uid 2000. Radio settings unchanged.'
