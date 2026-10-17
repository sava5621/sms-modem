[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb',[string]$OutputFile)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
$fingerprint=Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint'
if($fingerprint -ne 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.richal.20251104:user/test-keys'){throw 'UFI firmware mismatch'}
$checks=[ordered]@{}
foreach($item in @(@('MobileData','settings get global mobile_data'),@('Roaming','settings get global data_roaming'),
    @('Wifi','settings get global wifi_on'),@('WifiScan','settings get global wifi_scan_always_enabled'),
    @('TetherSupported','settings get global tether_supported'),@('Forward4','cat /proc/sys/net/ipv4/ip_forward'),
    @('Forward6','cat /proc/sys/net/ipv6/conf/all/forwarding'),@('SIM','getprop gsm.sim.state'),
    @('BootId','cat /proc/sys/kernel/random/boot_id'),@('Uptime','cat /proc/uptime'),@('AdbIdentity','id'))) {
    $checks[$item[0]]=Invoke-ModemShell $Adb $Serial $item[1]
}
$status=Invoke-RestMethod -Uri 'http://192.168.101.1:8088/api/status' -TimeoutSec 5
$http=Invoke-WebRequest -UseBasicParsing -Method Head -Uri 'http://192.168.101.1:8088/' -TimeoutSec 5
$sms=Invoke-RestMethod -Uri 'http://192.168.101.1:8088/api/sms?limit=1' -TimeoutSec 5
$checks.HttpStatus=[int]$http.StatusCode;$checks.MessageCount=[int]$sms.total
$checks.ProviderAvailable=[bool]$sms.providerAvailable
$checks.Network=$status.network;$checks.Indicator=$status.indicator;$checks.Policy=$status.policy
$routes=Invoke-ModemShell $Adb $Serial 'ip route'
$checks.NoDefaultRoute=$routes -notmatch '(?m)^default\s'
$ps=Invoke-ModemShell $Adb $Serial 'ps'
$checks.NoSharingProcesses=$ps -notmatch '(?m)\b(hostapd|wpa_supplicant|dnsmasq)\s*\r?$'
$checks.VendorDisabled=(Invoke-ModemShell $Adb $Serial 'pm list packages -d') -match '(?m)^package:com\.youdo\.server\r?$'
$checks.NetdFirewallEnabled=(Invoke-ModemShell $Adb $Serial 'dumpsys network_management') -match 'Firewall enabled: true'
$failures=@()
foreach($key in @('MobileData','Roaming','Wifi','WifiScan','TetherSupported','Forward4','Forward6')){if($checks[$key] -ne '0'){$failures+="$key=$($checks[$key])"}}
foreach($key in @('ProviderAvailable','NoDefaultRoute','NoSharingProcesses','VendorDisabled','NetdFirewallEnabled')){if(-not $checks[$key]){$failures+="$key=false"}}
if($checks.HttpStatus -ne 200 -or $status.policy.uid -ne 1000 -or -not $status.policy.firewallEnabled -or $status.policy.forwarding -or $status.policy.tethering -or $status.policy.error){$failures+='HTTP/policy failure'}
$report=[ordered]@{DateUtc=[DateTime]::UtcNow.ToString('o');ConfigurationPassed=($failures.Count -eq 0);RadioRegistered=[bool]$status.network.registered;CarrierSmsReceiptVerified=$false;Checks=$checks;Failures=$failures}
$json=$report | ConvertTo-Json -Depth 8
if($OutputFile){
    $path=[IO.Path]::GetFullPath($OutputFile)
    New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($path,$json+"`n",[Text.UTF8Encoding]::new($false))
}
Write-Output $json
if($failures.Count){throw ('UFI configuration failed: '+($failures -join ', '))}
if(-not $status.network.registered){Write-Warning 'Configuration passes; radio is not registered for SMS. Incoming carrier SMS is not confirmed.'}
