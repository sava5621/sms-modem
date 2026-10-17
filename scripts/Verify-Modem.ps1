[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial, [string]$Adb = 'adb',
      [string]$OutputFile)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
Assert-Modem $Adb $Serial
$checks = [ordered]@{}
foreach ($entry in @(
    @('SIM', 'getprop gsm.sim.state'),
    @('MobileData', 'settings get global mobile_data'),
    @('Roaming', 'settings get global data_roaming'),
    @('Wifi', 'settings get global wifi_on'),
    @('WifiScan', 'settings get global wifi_scan_always_enabled'),
    @('TetherSupported', 'settings get global tether_supported'),
    @('UsbTethering', 'getprop sys.usb.tethering'),
    @('RebootWatchdog', 'getprop persist.ufi.nosignal.reboot'),
    @('Forward4', 'cat /proc/sys/net/ipv4/ip_forward'),
    @('Forward6', 'cat /proc/sys/net/ipv6/conf/all/forwarding'),
    @('AdbUid', 'busybox id -u'),
    @('BootId', 'cat /proc/sys/kernel/random/boot_id'),
    @('Uptime', 'cat /proc/uptime')
)) { $checks[$entry[0]] = Invoke-ModemShell $Adb $Serial $entry[1] }
$wifiState = Invoke-ModemShell $Adb $Serial 'dumpsys wifi'
$checks['WifiApDisabled'] = $wifiState -match 'curState=ApStaDisabledState'
$processes = Invoke-ModemShell $Adb $Serial 'ps'
$checks['NoSharingProcesses'] = $processes -notmatch '(?m)\b(hostapd|wpa_supplicant|dnsmasq)\s*\r?$'
$disabled = Invoke-ModemShell $Adb $Serial 'pm list packages -d'
$checks['MoSdkDisabled'] = $disabled -match '(?m)^package:com\.mo\.sdk2230\r?$'
$mounts = Invoke-ModemShell $Adb $Serial 'cat /proc/mounts'
$checks['ReadOnlySystem'] = $mounts -match '(?m)^\S+ /system \S+ ro[, ]'
$http = Invoke-WebRequest -UseBasicParsing -Uri 'http://192.168.100.1:8088/' -TimeoutSec 5
$checks['HttpStatus'] = [int]$http.StatusCode
# Do not output or save the API's message content or sender.
$api = Invoke-RestMethod -Uri 'http://192.168.100.1:8088/api/sms?limit=1' -TimeoutSec 5
$checks['MessageCount'] = [int]$api.total
$expected = @{SIM='READY'; MobileData='0'; Roaming='0'; Wifi='0'; WifiScan='0';
    TetherSupported='0'; UsbTethering='false'; RebootWatchdog='no'; Forward4='0'; Forward6='0'; AdbUid='2000'}
$failures = @()
foreach ($key in $expected.Keys) { if ($checks[$key] -ne $expected[$key]) { $failures += "$key=$($checks[$key])" } }
foreach ($key in @('WifiApDisabled','NoSharingProcesses','MoSdkDisabled','ReadOnlySystem')) {
    if (-not $checks[$key]) { $failures += "$key=false" }
}
if ($checks.HttpStatus -ne 200) { $failures += "HTTP=$($checks.HttpStatus)" }
$report = [ordered]@{DateUtc=[DateTime]::UtcNow.ToString('o'); Checks=$checks; Passed=($failures.Count -eq 0); Failures=$failures}
$json = $report | ConvertTo-Json -Depth 5
if ($OutputFile) { $json | Set-Content -LiteralPath $OutputFile -Encoding UTF8 }
Write-Output $json
if ($failures.Count) { throw ('Verification failed: ' + ($failures -join ', ')) }
Write-Output 'Basic checks passed. Complete firewall, port and real SMS checks in docs/06-verification.md.'
