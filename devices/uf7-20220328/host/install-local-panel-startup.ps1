param([string]$Node='node',[switch]$ReplaceExisting)
$ErrorActionPreference='Stop'
$nodePath=(Get-Command $Node -ErrorAction Stop).Source
$link=Join-Path ([Environment]::GetFolderPath('Startup')) 'UF7 Local Panel.lnk'
$launcher=Join-Path $PSScriptRoot 'start-local-panel.ps1'
$bridge=Join-Path $PSScriptRoot 'local-panel.cjs'
$shellObject=New-Object -ComObject WScript.Shell
if(Test-Path -LiteralPath $link){
 $old=$shellObject.CreateShortcut($link)
 if($old.Arguments.IndexOf($launcher,[StringComparison]::OrdinalIgnoreCase) -lt 0 -and $old.Arguments.IndexOf($bridge,[StringComparison]::OrdinalIgnoreCase) -lt 0 -and -not $ReplaceExisting){throw 'Existing shortcut points to another folder. Review it and use -ReplaceExisting to migrate the UF7 launcher.'}
}
$shortcut=$shellObject.CreateShortcut($link)
$shortcut.TargetPath=Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
# Invoke only the known Node executable. This works with a Restricted .ps1
# policy without changing the execution policy globally or for the process.
$command="Start-Process -FilePath '"+$nodePath.Replace("'","''")+"' -ArgumentList ([string][char]34+'"+$bridge.Replace("'","''")+"'+[char]34) -WindowStyle Hidden"
$shortcut.Arguments='-NoProfile -NonInteractive -WindowStyle Hidden -Command "'+$command+'"'
$shortcut.WorkingDirectory=$PSScriptRoot
$shortcut.WindowStyle=7
$shortcut.Description='UF7 loopback panel for recording the greeting'
$shortcut.Save()
Write-Output ('Startup shortcut: '+$link)
