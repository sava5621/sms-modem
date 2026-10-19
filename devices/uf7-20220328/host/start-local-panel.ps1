param([string]$Node='node')
$ErrorActionPreference='Stop'
$scriptPath=Join-Path $PSScriptRoot 'local-panel.cjs'
$existing=Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue
if($existing){
 foreach($connection in $existing){$owner=Get-CimInstance Win32_Process -Filter ('ProcessId='+$connection.OwningProcess);if($owner.CommandLine -and $owner.CommandLine.IndexOf($scriptPath,[StringComparison]::OrdinalIgnoreCase) -ge 0){Write-Output 'Local panel is already running: http://localhost:18080/';return}}
 throw 'Port 18080 is occupied by another service. No changes made.'
}
$nodePath=(Get-Command $Node -ErrorAction Stop).Source
Start-Process -FilePath $nodePath -ArgumentList @('"'+$scriptPath+'"') -WindowStyle Hidden
Write-Output 'Local panel: http://localhost:18080/'
