[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildToolsDirectory,
    [Parameter(Mandatory)][string]$EcjJar,
    [Parameter(Mandatory)][string]$KeyStore,
    [string]$KeyAlias = 'modem',
    [string]$Java = 'java',
    [switch]$IncludeTests
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root 'build'
foreach ($path in @($AndroidJar,$EcjJar,$KeyStore,(Join-Path $BuildToolsDirectory 'aapt.exe'),
    (Join-Path $BuildToolsDirectory 'lib/dx.jar'), (Join-Path $BuildToolsDirectory 'lib/apksigner.jar'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file missing: $path" }
}
if (-not $env:SMS_MODEM_STORE_PASSWORD -or -not $env:SMS_MODEM_KEY_PASSWORD) {
    throw 'Set SMS_MODEM_STORE_PASSWORD and SMS_MODEM_KEY_PASSWORD in this process before signing. See docs/09-build.md.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
function Invoke-JavaTool {
    param([string[]]$ToolArguments)
    & $Java @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "Java tool failed (exit $LASTEXITCODE)" }
}
function Compile-Dex {
    param([string]$Source, [string]$Destination, [string]$ExtraClasspath)
    # Keep each compilation in a fresh subdirectory; never use stale class files.
    $run = Join-Path $Destination ([Guid]::NewGuid().ToString('N'))
    $classes = Join-Path $run 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath $Source -Recurse -Filter '*.java' | ForEach-Object FullName)
    if (-not $sources.Count) { throw "No Java sources: $Source" }
    $compilerArgs = @('-jar',$EcjJar,'-source','1.7','-target','1.7','-encoding','UTF-8','-bootclasspath',$AndroidJar,'-d',$classes)
    if ($ExtraClasspath) { $compilerArgs += @('-classpath',$ExtraClasspath) }
    Invoke-JavaTool ($compilerArgs + $sources)
    $dex = Join-Path $run 'classes.dex'
    Invoke-JavaTool @('-jar',(Join-Path $BuildToolsDirectory 'lib/dx.jar'),'--dex','--min-sdk-version=19',"--output=$dex",$classes)
    return $dex
}
function Build-App {
    param([string]$Name, [string]$Source, [string]$ExtraClasspath)
    $destination = Join-Path $out $Name
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    # Suppress compiler stdout on the success pipeline used to return the path.
    $dex = Compile-Dex (Join-Path $Source 'src') $destination $ExtraClasspath | Select-Object -Last 1
    if ($Name -eq 'sms-modem') { $script:SmsClasses = Join-Path (Split-Path $dex -Parent) 'classes' }
    $unsigned = Join-Path $destination 'unsigned.apk'
    $aaptArgs = @('package','-f','-M',(Join-Path $Source 'AndroidManifest.xml'),'-I',$AndroidJar,'-F',$unsigned)
    if (Test-Path -LiteralPath (Join-Path $Source 'assets')) { $aaptArgs += @('-A',(Join-Path $Source 'assets')) }
    & (Join-Path $BuildToolsDirectory 'aapt.exe') @aaptArgs
    if ($LASTEXITCODE -ne 0) { throw "aapt failed for $Name" }
    $zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try { $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $dex, 'classes.dex') } finally { $zip.Dispose() }
    $signed = Join-Path $out "$Name.apk"
    Invoke-JavaTool @('-jar',(Join-Path $BuildToolsDirectory 'lib/apksigner.jar'),'sign','--ks',$KeyStore,'--ks-key-alias',$KeyAlias,
        '--ks-pass','env:SMS_MODEM_STORE_PASSWORD','--key-pass','env:SMS_MODEM_KEY_PASSWORD','--min-sdk-version','19','--out',$signed,$unsigned)
    Invoke-JavaTool @('-jar',(Join-Path $BuildToolsDirectory 'lib/apksigner.jar'),'verify','--verbose',$signed)
    Get-FileHash -LiteralPath $signed -Algorithm SHA256
}
Build-App 'sms-modem' (Join-Path $root 'app')
Build-App 'wifi-off' (Join-Path $root 'wifi-off')
if ($IncludeTests) { Build-App 'sms-tests' (Join-Path $root 'app/tests') $script:SmsClasses }
$controlDex = Compile-Dex (Join-Path $root 'modem/src') (Join-Path $out 'control') | Select-Object -Last 1
$jar = Join-Path $out 'sms-only-control.jar'
# Create from scratch, without assuming Java's jar.exe is installed.
$stream = [IO.File]::Open($jar, [IO.FileMode]::Create)
$zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
try { $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$controlDex,'classes.dex') } finally { $zip.Dispose(); $stream.Dispose() }
Get-FileHash -LiteralPath $jar -Algorithm SHA256
Write-Output 'Build complete. Output is in build/. No device was changed and artifacts/ was not overwritten.'
