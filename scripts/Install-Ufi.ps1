[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb')
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$root=Split-Path $PSScriptRoot -Parent
$expected='qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.richal.20251104:user/test-keys'
if((Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint') -ne $expected){throw 'UFI firmware mismatch; use the matching device instructions'}
$package=Invoke-ModemShell $Adb $Serial 'pm path local.modem.hub'
if($package -notmatch '^package:'){throw 'Expected existing Modem Hub package; preserve its platform signature and data'}
Test-ArtifactHashes $root
$result=Invoke-ModemAdb $Adb $Serial @('install','-r',(Join-Path $root 'artifacts/sms-modem-ufi-4.2.apk'))
if($result -notmatch '(?m)^Success\r?$'){throw "UFI update failed: $result"}
$result=Invoke-ModemShell $Adb $Serial 'am start -n local.modem.hub/.MainActivity'
if($result -match '(?i)error|exception'){throw $result}
Write-Output 'Second modem installed in place. USB LAN: 192.168.101.1:8088. No root, flashing or data clearing used.'
Write-Output 'Verify radio registration and a real incoming SMS separately: docs/14-second-modem.md.'
