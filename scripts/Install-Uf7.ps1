[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root=Split-Path $PSScriptRoot -Parent
if((Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint') -ne 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.libinglin.20220328:userdebug/test-keys'){throw 'UF7 firmware mismatch'}
if((Invoke-ModemShell $Adb $Serial 'getprop persist.adb.tcp.port') -ne '-1' -or (Invoke-ModemShell $Adb $Serial 'getprop persist.ufi.nosignal.reboot') -ne 'no'){throw 'Initialize the two persistent properties explicitly first: Initialize-Uf7Properties.ps1 -AllowTemporaryRoot'}
Test-ArtifactHashes $root
$current=Invoke-RestMethod 'http://192.168.100.1:8088/api/status' -TimeoutSec 10
if($current.callState -ne 0){throw 'Wait until the call has ended'}
$result=Invoke-ModemAdb $Adb $Serial @('install','-r',(Join-Path $root 'artifacts/sms-modem-uf7-1.8.apk'))
if($result -notmatch '(?m)^Success\r?$'){throw "UF7 update failed: $result"}
$result=Invoke-ModemShell $Adb $Serial 'am start -n local.modem.hub/.MainActivity --ez apply_configuration true --ez apply_security true'
if($result -match '(?i)error|exception'){throw $result}
$null=Invoke-ModemAdb $Adb $Serial @('usb')
Write-Output 'UF7 updated in place: http://192.168.100.1/. Mobile data stays enabled for registration; netd isolates non-USB IP traffic.'
