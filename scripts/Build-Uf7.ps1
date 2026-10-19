[CmdletBinding()]
param(
 [Parameter(Mandatory)][string]$AndroidJar,
 [Parameter(Mandatory)][string]$BuildToolsDirectory,
 [Parameter(Mandatory)][string]$EcjJar,
 [Parameter(Mandatory)][string]$PlatformKey,
 [Parameter(Mandatory)][string]$PlatformCertificate,
 [string]$Java='java', [switch]$IncludeTests
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$profile=Join-Path $root 'devices/uf7-20220328'
$out=Join-Path $root ('build/uf7/'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $out 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources=@(Get-ChildItem -LiteralPath (Join-Path $profile 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -bootclasspath $AndroidJar -d $classes @sources
if($LASTEXITCODE){throw 'UF7 Java compilation failed'}
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/dx.jar') --dex --min-sdk-version=19 "--output=$out/classes.dex" $classes
if($LASTEXITCODE){throw 'UF7 DEX failed'}
& (Join-Path $BuildToolsDirectory 'aapt.exe') package -f -M (Join-Path $profile 'AndroidManifest.xml') -I $AndroidJar -A (Join-Path $profile 'assets') -F (Join-Path $out 'unsigned.apk')
if($LASTEXITCODE){throw 'UF7 packaging failed'}
Push-Location $out
try{& (Join-Path $BuildToolsDirectory 'aapt.exe') add unsigned.apk classes.dex;if($LASTEXITCODE){throw 'UF7 DEX packaging failed'}}finally{Pop-Location}
$signed=Join-Path $root 'build/sms-modem-uf7-1.8.apk'
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') sign --key $PlatformKey --cert $PlatformCertificate --min-sdk-version 19 --out $signed (Join-Path $out 'unsigned.apk')
if($LASTEXITCODE){throw 'UF7 signing failed'}
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') verify --print-certs $signed
if($LASTEXITCODE){throw 'UF7 signature verification failed'}
Get-FileHash -LiteralPath $signed -Algorithm SHA256
if($IncludeTests){
 $test=Join-Path $out 'tests';New-Item -ItemType Directory -Path $test -Force | Out-Null
 & $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -bootclasspath $AndroidJar -classpath $classes -d (Join-Path $test 'classes') (Join-Path $profile 'tests/UpgradeTest.java')
 if($LASTEXITCODE){throw 'UF7 test compile failed'}
 & $Java -jar (Join-Path $BuildToolsDirectory 'lib/dx.jar') --dex --min-sdk-version=19 "--output=$test/classes.dex" (Join-Path $test 'classes') $classes
 if($LASTEXITCODE){throw 'UF7 test DEX failed'}
 & (Join-Path $BuildToolsDirectory 'aapt.exe') package -f -M (Join-Path $profile 'tests/AndroidManifest.xml') -I $AndroidJar -F (Join-Path $test 'unsigned.apk')
 Push-Location $test
 try{& (Join-Path $BuildToolsDirectory 'aapt.exe') add unsigned.apk classes.dex}finally{Pop-Location}
 & $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') sign --key $PlatformKey --cert $PlatformCertificate --min-sdk-version 19 --out (Join-Path $root 'build/uf7-tests.apk') (Join-Path $test 'unsigned.apk')
 if($LASTEXITCODE){throw 'UF7 test signing failed'}
}
