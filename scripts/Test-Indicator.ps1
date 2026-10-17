[CmdletBinding()]
param([Parameter(Mandatory)][string]$EcjJar,[string]$Java='java')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $root ('build/indicator-tests/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -d $classes `
    (Join-Path $root 'app/src/local/modem/sms/LedPolicy.java') `
    (Join-Path $root 'tests/java/local/modem/sms/LedPolicyTest.java')
if($LASTEXITCODE -ne 0){throw 'Indicator test compilation failed'}
& $Java -cp $classes local.modem.sms.LedPolicyTest
if($LASTEXITCODE -ne 0){throw 'Indicator policy regression failed'}
