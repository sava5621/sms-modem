[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildToolsDirectory,
    [Parameter(Mandatory)][string]$EcjJar,
    [Parameter(Mandatory)][string]$PlatformKey,
    [Parameter(Mandatory)][string]$PlatformCertificate,
    [string]$Java='java',
    [switch]$IncludeTests
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$out=Join-Path $root ('build/ufi/'+[Guid]::NewGuid().ToString('N'))
$sources=Join-Path $out 'src/local/modem/hub'
$classes=Join-Path $out 'classes'
New-Item -ItemType Directory -Path $sources,$classes -Force | Out-Null
$utf8=[Text.UTF8Encoding]::new($false)
function Replace-Required([string]$Value,[string]$Before,[string]$After) {
    if(-not $Value.Contains($Before)){throw "Shared source changed; review UFI adapter anchor: $Before"}
    return $Value.Replace($Before,$After)
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'app/src/local/modem/sms') -Filter '*.java') {
    $text=[IO.File]::ReadAllText($file.FullName).Replace('local.modem.sms','local.modem.hub').Replace('192.168.100.','192.168.101.')
    if($file.Name -eq 'LedController.java'){$text=$text.Replace('/4G_S/','/red/').Replace('/4G_L2/','/green/').Replace('/WIFI_S/','/blue/')}
    if($file.Name -eq 'SmsService.java') {
        $text=Replace-Required $text 'private LedController led;' 'private LedController led; private UfiPolicy policy;'
        $text=Replace-Required $text 'super.onCreate();' 'super.onCreate(); policy=new UfiPolicy(this); policy.start();'
        $text=Replace-Required $text 'if(led!=null)led.stop();' 'if(led!=null)led.stop(); if(policy!=null)policy.stop();'
        $text=Replace-Required $text '.put("indicator",led.snapshot())' '.put("indicator",led.snapshot()).put("policy",policy.snapshot())'
        # The first modem legacy reverse-proxy domain is not an allowed host on this modem.
        $text=Replace-Required $text ' || host.equals(PUBLIC_HOST) || host.equals(PUBLIC_HOST + ":443")' ''
    }
    [IO.File]::WriteAllText((Join-Path $sources $file.Name),$text,$utf8)
}
Copy-Item -Path (Join-Path $root 'devices/ufi-20251104/src/local/modem/hub/*.java') -Destination $sources
$allSources=@(Get-ChildItem -LiteralPath $sources -Filter '*.java' | ForEach-Object FullName)
& $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -bootclasspath $AndroidJar -d $classes @allSources
if($LASTEXITCODE -ne 0){throw 'UFI Java compilation failed'}
$dex=Join-Path $out 'classes.dex'
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/dx.jar') --dex --min-sdk-version=19 "--output=$dex" $classes
if($LASTEXITCODE -ne 0){throw 'UFI dex failed'}
$unsigned=Join-Path $out 'unsigned.apk'
& (Join-Path $BuildToolsDirectory 'aapt.exe') package -f -M (Join-Path $root 'devices/ufi-20251104/AndroidManifest.xml') -I $AndroidJar -A (Join-Path $root 'app/assets') -F $unsigned
if($LASTEXITCODE -ne 0){throw 'UFI packaging failed'}
Push-Location $out
try { & (Join-Path $BuildToolsDirectory 'aapt.exe') add unsigned.apk classes.dex; if($LASTEXITCODE -ne 0){throw 'UFI dex packaging failed'} } finally {Pop-Location}
$signed=Join-Path $root 'build/sms-modem-ufi-4.2.apk'
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') sign --key $PlatformKey --cert $PlatformCertificate --min-sdk-version 19 --out $signed $unsigned
if($LASTEXITCODE -ne 0){throw 'UFI signing failed'}
& $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') verify --print-certs $signed
if($LASTEXITCODE -ne 0){throw 'UFI signature verification failed'}
Get-FileHash -LiteralPath $signed -Algorithm SHA256
Write-Output "UFI APK: $signed"
if($IncludeTests) {
    $testRoot=Join-Path $out 'tests'
    $testSources=Join-Path $testRoot 'src/local/modem/hub/tests'
    $testClasses=Join-Path $testRoot 'classes'
    New-Item -ItemType Directory -Path $testSources,$testClasses -Force | Out-Null
    foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'app/tests/src/local/modem/sms/tests') -Filter '*.java') {
        $text=[IO.File]::ReadAllText($file.FullName).Replace('local.modem.sms','local.modem.hub').Replace('192.168.100.','192.168.101.')
        [IO.File]::WriteAllText((Join-Path $testSources $file.Name),$text,$utf8)
    }
    $manifest=[IO.File]::ReadAllText((Join-Path $root 'app/tests/AndroidManifest.xml')).Replace('local.modem.sms','local.modem.hub')
    $testManifest=Join-Path $testRoot 'AndroidManifest.xml'
    [IO.File]::WriteAllText($testManifest,$manifest,$utf8)
    $files=@(Get-ChildItem -LiteralPath $testSources -Filter '*.java' | ForEach-Object FullName)
    & $Java -jar $EcjJar -source 1.7 -target 1.7 -encoding UTF-8 -bootclasspath $AndroidJar -classpath $classes -d $testClasses @files
    if($LASTEXITCODE -ne 0){throw 'UFI tests compilation failed'}
    & $Java -jar (Join-Path $BuildToolsDirectory 'lib/dx.jar') --dex --min-sdk-version=19 "--output=$testRoot/classes.dex" $testClasses
    if($LASTEXITCODE -ne 0){throw 'UFI tests dex failed'}
    & (Join-Path $BuildToolsDirectory 'aapt.exe') package -f -M $testManifest -I $AndroidJar -F (Join-Path $testRoot 'unsigned.apk')
    if($LASTEXITCODE -ne 0){throw 'UFI tests packaging failed'}
    Push-Location $testRoot
    try { & (Join-Path $BuildToolsDirectory 'aapt.exe') add unsigned.apk classes.dex; if($LASTEXITCODE -ne 0){throw 'UFI tests dex packaging failed'} } finally {Pop-Location}
    & $Java -jar (Join-Path $BuildToolsDirectory 'lib/apksigner.jar') sign --key $PlatformKey --cert $PlatformCertificate --min-sdk-version 19 --out (Join-Path $root 'build/sms-ufi-tests.apk') (Join-Path $testRoot 'unsigned.apk')
    if($LASTEXITCODE -ne 0){throw 'UFI tests signing failed'}
}
