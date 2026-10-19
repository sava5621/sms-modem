param([switch]$RemoveStartup)
$ErrorActionPreference='Stop'
$scriptPath=Join-Path $PSScriptRoot 'local-panel.cjs'
$connections=Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue
foreach($connection in $connections){
 $owner=Get-CimInstance Win32_Process -Filter ('ProcessId='+$connection.OwningProcess)
 if($owner.CommandLine -and $owner.CommandLine.IndexOf($scriptPath,[StringComparison]::OrdinalIgnoreCase) -ge 0){Stop-Process -Id $connection.OwningProcess}
}
if($RemoveStartup){
 $link=Join-Path ([Environment]::GetFolderPath('Startup')) 'DT Local Panel.lnk'
 if(Test-Path -LiteralPath $link){$ws=New-Object -ComObject WScript.Shell;$entry=$ws.CreateShortcut($link);$launcher=Join-Path $PSScriptRoot 'start-local-panel.ps1';if($entry.Arguments.IndexOf($launcher,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $entry.Arguments.IndexOf($scriptPath,[StringComparison]::OrdinalIgnoreCase) -ge 0){Remove-Item -LiteralPath $link}else{throw 'The startup shortcut points elsewhere. It was not changed.'}}
}
Write-Output 'Local panel stopped. The modem service is unaffected.'
