[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$AndroidJar,
 [Parameter(Mandatory)][string]$BuildToolsDirectory,
 [Parameter(Mandatory)][string]$EcjJar,
 [Parameter(Mandatory)][string]$PlatformKey,
 [Parameter(Mandatory)][string]$PlatformCertificate,
 [string]$Java='java'
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$profile=Join-Path $root 'devices/dt-20221021'
$out=Join-Path $root ('build/dt/'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $out 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $profile 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -bootclasspath $AndroidJar -d $classes @sources
if($LASTEXITCODE){throw 'DT Java compilation failed'}
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/dx.jar') --dex --min-sdk-version=19 "--output=$out/classes.dex" $classes
if($LASTEXITCODE){throw 'DT DEX failed'}
& (Join-Path $BuildToolsDirectory 'aapt.exe') package -f -M (Join-Path $profile 'AndroidManifest.xml') -I $AndroidJar -A (Join-Path $profile 'assets') -F (Join-Path $out 'unsigned.apk')
if($LASTEXITCODE){throw 'DT packaging failed'}
Push-Location $out
try{& (Join-Path $BuildToolsDirectory 'aapt.exe') add unsigned.apk classes.dex;if($LASTEXITCODE){throw 'DT DEX packaging failed'}}finally{Pop-Location}
$signed=Join-Path $root 'build/sms-modem-dt-1.8.apk'
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') sign --key $PlatformKey --cert $PlatformCertificate --min-sdk-version 19 --out $signed (Join-Path $out 'unsigned.apk')
if($LASTEXITCODE){throw 'DT signing failed'}
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') verify --print-certs $signed
if($LASTEXITCODE){throw 'DT signature verification failed'}
Get-FileHash -LiteralPath $signed -Algorithm SHA256
