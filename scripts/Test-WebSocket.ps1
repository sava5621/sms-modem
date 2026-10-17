[CmdletBinding()]
param([Parameter(Mandatory)][string]$EcjJar,[string]$Java='java')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root ('build/protocol-tests/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$source=Join-Path $root 'app/src/local/modem/sms'
& $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -d $classes `
    (Join-Path $source 'WebSocketProtocol.java') (Join-Path $source 'WebSocketHub.java') `
    (Join-Path $root 'tests/java/local/modem/sms/WebSocketTest.java')
if($LASTEXITCODE -ne 0){throw 'Protocol test compilation failed'}
& $Java -cp $classes local.modem.sms.WebSocketTest
if($LASTEXITCODE -ne 0){throw 'Protocol regression failed'}
