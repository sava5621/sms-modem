[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root=Split-Path $PSScriptRoot -Parent
if((Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint') -ne 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys'){throw 'DT firmware mismatch'}
if((Invoke-ModemShell $Adb $Serial 'getprop persist.adb.tcp.port') -ne '-1' -or (Invoke-ModemShell $Adb $Serial 'getprop persist.ufi.nosignal.reboot') -ne 'no'){throw 'Use Migrate-Dt.ps1 -AllowTemporaryRoot for the first migration. The persistent properties are not initialized.'}
Test-ArtifactHashes $root
$current=Invoke-RestMethod 'http://192.168.100.2/api/status' -TimeoutSec 10
if($current.callState -ne 0){throw 'Wait until the call has ended'}
$result=Invoke-ModemAdb $Adb $Serial @('install','-r',(Join-Path $root 'artifacts/sms-modem-dt-1.8.1.apk'))
if($result -notmatch '(?m)^Success\r?$'){throw "DT update failed: $result"}
$result=Invoke-ModemShell $Adb $Serial 'am start -n local.modem.hub/.MainActivity --ez apply_configuration true --ez apply_security true'
if($result -match '(?i)error|exception'){throw $result}
$null=Invoke-ModemAdb $Adb $Serial @('usb')
Write-Output 'DT updated in place: http://192.168.100.2/. Mobile data stays disabled; both firewall layers isolate non-USB IP traffic.'
