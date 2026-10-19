param([Parameter(Mandatory)][string]$Serial,[string]$Uf7Serial,[switch]$Restore,[string]$BackupFile)
$ErrorActionPreference='Stop'
if($Uf7Serial){
 if($Restore){throw 'Restore the saved dual-adapter snapshot manually; do not apply the single-adapter DHCP restore'}
 & (Join-Path $PSScriptRoot '../../../scripts/Configure-DualModemWindows.ps1') -Uf7Serial $Uf7Serial -DtSerial $Serial -BackupFile $BackupFile
 return
}
if($Serial -notmatch '^[A-Za-z0-9_-]+$'){throw 'Use a USB serial'}
$expected='USB\VID_05C6&PID_90B4\'+$Serial
$matches=@(Get-CimInstance Win32_NetworkAdapter | Where-Object PNPDeviceID | Where-Object {try{(Get-PnpDeviceProperty -InstanceId $_.PNPDeviceID -KeyName DEVPKEY_Device_Parent -ErrorAction Stop).Data -eq $expected}catch{$false}})
if($matches.Count -ne 1){throw 'Could not identify exactly one DT RNDIS adapter'}
$adapter=Get-NetAdapter -InterfaceIndex $matches[0].InterfaceIndex
if(-not $BackupFile){$BackupFile=Join-Path $PSScriptRoot ('../../../.local/dt/windows-'+$Serial+'.json')}
if($Restore){
 $before=Get-Content -LiteralPath $BackupFile -Raw | ConvertFrom-Json
 if($before.Guid -ne $adapter.InterfaceGuid.ToString() -or $before.UsbParent -ne $expected -or $before.Dhcp -ne 'Enabled'){throw 'Backup identity or original DHCP mode mismatch; restore manually'}
 foreach($store in @('PersistentStore','ActiveStore')){Get-NetRoute -PolicyStore $store -DestinationPrefix '192.168.100.2/32' -InterfaceIndex $adapter.ifIndex -ErrorAction SilentlyContinue | Remove-NetRoute -Confirm:$false}
 & netsh.exe interface ipv4 set address "name=$($adapter.Name)" source=dhcp
 if($LASTEXITCODE){throw 'DHCP restore failed'}
 & netsh.exe interface ipv4 set dnsservers "name=$($adapter.Name)" source=dhcp
 if($LASTEXITCODE){throw 'DNS restore failed'}
 if($before.IPv6Binding){Enable-NetAdapterBinding -Name $adapter.Name -ComponentID ms_tcpip6 | Out-Null}
 return
}
if(@(Get-NetIPAddress -AddressFamily IPv4 -IPAddress '192.168.100.2' -ErrorAction SilentlyContinue).Count){throw '192.168.100.2 is assigned to this computer. Configure both modems with -Uf7Serial first.'}
if(@(Get-NetRoute -DestinationPrefix '192.168.100.2/32' -ErrorAction SilentlyContinue | Where-Object InterfaceIndex -ne $adapter.ifIndex).Count){throw 'Another adapter owns the DT route; configure both modems together.'}
if(-not (Test-Path -LiteralPath $BackupFile)){
 New-Item -ItemType Directory -Path (Split-Path ([IO.Path]::GetFullPath($BackupFile)) -Parent) -Force | Out-Null
 [ordered]@{Guid=$adapter.InterfaceGuid.ToString();UsbParent=$expected;Dhcp=(Get-NetIPInterface -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4).Dhcp.ToString();Addresses=@(Get-NetIPAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4 | Select-Object IPAddress,PrefixLength);Dns=@((Get-DnsClientServerAddress -InterfaceIndex $adapter.ifIndex -AddressFamily IPv4).ServerAddresses);IPv6Binding=(Get-NetAdapterBinding -Name $adapter.Name -ComponentID ms_tcpip6).Enabled} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $BackupFile -Encoding UTF8
}
$unexpected=@(Get-NetRoute -InterfaceIndex $adapter.ifIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Where-Object NextHop -ne '192.168.100.2')
if($unexpected.Count){throw 'Unexpected gateway; review this adapter before changing it'}
& netsh.exe interface ipv4 set address "name=$($adapter.Name)" source=static address=192.168.100.253 mask=255.255.255.0 gateway=none store=persistent
if($LASTEXITCODE){throw 'Static USB address failed'}
& netsh.exe interface ipv4 set dnsservers "name=$($adapter.Name)" source=static address=none validate=no
if($LASTEXITCODE){throw 'USB DNS configuration failed'}
Disable-NetAdapterBinding -Name $adapter.Name -ComponentID ms_tcpip6 | Out-Null
if(-not @(Get-NetRoute -PolicyStore PersistentStore -DestinationPrefix '192.168.100.2/32' -InterfaceIndex $adapter.ifIndex -ErrorAction SilentlyContinue).Count){
 & netsh.exe interface ipv4 add route prefix=192.168.100.2/32 "interface=$($adapter.Name)" nexthop=0.0.0.0 metric=1 store=persistent
 if($LASTEXITCODE){throw 'DT host route failed'}
}
Write-Output 'DT adapter configured without gateway or DNS. Other adapters unchanged.'
