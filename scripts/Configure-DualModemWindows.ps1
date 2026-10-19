[CmdletBinding()]
param([Parameter(Mandatory)][string]$Uf7Serial,[Parameter(Mandatory)][string]$DtSerial,[string]$BackupFile)
$ErrorActionPreference='Stop'
if($Uf7Serial -eq $DtSerial -or $Uf7Serial -notmatch '^[A-Za-z0-9_-]+$' -or $DtSerial -notmatch '^[A-Za-z0-9_-]+$'){throw 'Specify two different USB serials'}
function Find-UsbAdapter([string]$Serial){
 $parent='USB\VID_05C6&PID_90B4\'+$Serial
 $found=@(Get-CimInstance Win32_NetworkAdapter | Where-Object PNPDeviceID | Where-Object {try{(Get-PnpDeviceProperty -InstanceId $_.PNPDeviceID -KeyName DEVPKEY_Device_Parent -ErrorAction Stop).Data -eq $parent}catch{$false}})
 if($found.Count -ne 1){throw 'Could not uniquely identify modem USB adapter'}
 $adapter=Get-NetAdapter -InterfaceIndex $found[0].InterfaceIndex
 [pscustomobject]@{Adapter=$adapter;Parent=$parent}
}
$uf=Find-UsbAdapter $Uf7Serial
$dt=Find-UsbAdapter $DtSerial
if($uf.Adapter.ifIndex -eq $dt.Adapter.ifIndex){throw 'USB adapters must differ'}
$targets=@(@{Item=$uf;HostAddress='192.168.100.254';ModemAddress='192.168.100.1'},@{Item=$dt;HostAddress='192.168.100.253';ModemAddress='192.168.100.2'})
$indices=@($uf.Adapter.ifIndex,$dt.Adapter.ifIndex)
foreach($target in $targets){
 $adapter=$target.Item.Adapter
 if(@(Get-NetRoute -InterfaceIndex $adapter.ifIndex -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue).Count){throw 'Unexpected default gateway on a modem adapter'}
 $conflict=@(Get-NetIPAddress -AddressFamily IPv4 -IPAddress $target.HostAddress -ErrorAction SilentlyContinue | Where-Object InterfaceIndex -ne $adapter.ifIndex)
 if($conflict.Count){throw 'Host address is already assigned to another interface'}
 $foreignRoute=@(Get-NetRoute -AddressFamily IPv4 -DestinationPrefix ($target.ModemAddress+'/32') -ErrorAction SilentlyContinue | Where-Object InterfaceIndex -notin $indices)
 if($foreignRoute.Count){throw 'An unrelated interface already owns a modem host route'}
}
if(-not $BackupFile){$BackupFile=Join-Path $PSScriptRoot ('../.local/network/dual-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.json')}
if(-not (Test-Path -LiteralPath $BackupFile)){
 New-Item -ItemType Directory -Force (Split-Path ([IO.Path]::GetFullPath($BackupFile)) -Parent) | Out-Null
 $before=@(foreach($target in $targets){$a=$target.Item.Adapter;[ordered]@{Guid=$a.InterfaceGuid.ToString();UsbParent=$target.Item.Parent;Dhcp=(Get-NetIPInterface -InterfaceIndex $a.ifIndex -AddressFamily IPv4).Dhcp.ToString();Addresses=@(Get-NetIPAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4 | Select-Object IPAddress,PrefixLength);Dns=@((Get-DnsClientServerAddress -InterfaceIndex $a.ifIndex -AddressFamily IPv4).ServerAddresses);Routes=@(Get-NetRoute -InterfaceIndex $a.ifIndex -AddressFamily IPv4 | Select-Object DestinationPrefix,NextHop,RouteMetric,Protocol);IPv6Binding=(Get-NetAdapterBinding -Name $a.Name -ComponentID ms_tcpip6).Enabled}})
 $before | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $BackupFile -Encoding UTF8
}
foreach($target in $targets){
 $adapter=$target.Item.Adapter
 & netsh.exe interface ipv4 set address "name=$($adapter.Name)" source=static "address=$($target.HostAddress)" mask=255.255.255.0 gateway=none store=persistent
 if($LASTEXITCODE){throw 'Static modem USB address failed'}
 & netsh.exe interface ipv4 set dnsservers "name=$($adapter.Name)" source=static address=none validate=no
 if($LASTEXITCODE){throw 'Modem USB DNS configuration failed'}
 Disable-NetAdapterBinding -Name $adapter.Name -ComponentID ms_tcpip6 | Out-Null
}
foreach($target in $targets){
 $prefix=$target.ModemAddress+'/32'
 $adapter=$target.Item.Adapter
 # Specific routes disambiguate two physically separate USB links sharing /24.
 foreach($store in @('PersistentStore','ActiveStore')){
  $wrong=@(Get-NetRoute -PolicyStore $store -DestinationPrefix $prefix -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceIndex -in $indices -and ($_.InterfaceIndex -ne $adapter.ifIndex -or $_.NextHop -ne '0.0.0.0') })
  foreach($route in $wrong){Remove-NetRoute -InputObject $route -Confirm:$false}
 }
 $existing=@(Get-NetRoute -PolicyStore PersistentStore -DestinationPrefix $prefix -InterfaceIndex $adapter.ifIndex -ErrorAction SilentlyContinue | Where-Object NextHop -eq '0.0.0.0')
 if(-not $existing.Count){
  & netsh.exe interface ipv4 add route "prefix=$prefix" "interface=$($adapter.Name)" nexthop=0.0.0.0 metric=1 store=persistent
  if($LASTEXITCODE){throw 'Persistent modem host route failed'}
 }
}
Write-Output 'Windows USB links: UF7 host .254 -> modem .1; DT host .253 -> modem .2. No gateway or DNS. Modem firmware settings are unaffected.'
