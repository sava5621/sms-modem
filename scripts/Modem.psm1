Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:ExpectedFingerprint = 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.rel.20221021:userdebug/test-keys'

function Invoke-ModemAdb {
    param([Parameter(Mandatory)][string]$Adb, [Parameter(Mandatory)][string]$Serial,
          [Parameter(Mandatory)][string[]]$Arguments)
    if ($Serial -notmatch '^[A-Za-z0-9_-]+$') { throw 'Use the USB serial from adb devices, not a TCP address.' }
    $null = Get-Command $Adb -ErrorAction Stop
    $savedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 can wrap successful native stderr as ErrorRecord.
        $ErrorActionPreference = 'Continue'
        $result = & $Adb -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $text = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($exitCode -ne 0) { throw "ADB failed ($exitCode): $text" }
    return $text.Trim()
}

function Invoke-ModemShell {
    param([string]$Adb, [string]$Serial, [Parameter(Mandatory)][string]$Command)
    # Old Android adbd does not reliably propagate the remote exit status.
    $marker = 'SMS_RC_' + [Guid]::NewGuid().ToString('N')
    $wrapped = $Command + '; sms_rc=$?; echo ' + $marker + ':$sms_rc'
    $text = Invoke-ModemAdb -Adb $Adb -Serial $Serial -Arguments @('shell', $wrapped)
    $match = [regex]::Match($text, '(?m)^' + $marker + ':(\d+)\r?$')
    if (-not $match.Success -or $match.Groups[1].Value -ne '0') { throw "Remote command failed: $text" }
    return ($text -replace ('(?m)^' + $marker + ':\d+\r?$'), '').Trim()
}

function Assert-Modem {
    param([string]$Adb, [string]$Serial)
    $fingerprint = Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint'
    if ($fingerprint -ne $script:ExpectedFingerprint) {
        throw "Unverified firmware: $fingerprint. Read docs/01-hardware.md before adapting the scripts."
    }
}

function Wait-Modem {
    param([string]$Adb, [string]$Serial, [int]$Seconds = 30)
    $until = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        try {
            $state = Invoke-ModemAdb $Adb $Serial @('get-state')
            if ($state -eq 'device') { return }
        } catch { }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $until)
    throw "USB ADB did not return within $Seconds seconds. Check the USB connection."
}

function Set-ModemRoot {
    param([string]$Adb, [string]$Serial, [bool]$Enabled)
    $desired = if ($Enabled) { '1' } else { '0' }
    $expectedUid = if ($Enabled) { '0' } else { '2000' }
    $null = Invoke-ModemShell $Adb $Serial "setprop service.adb.root $desired"
    # Restarting only the device's adbd intentionally interrupts this command.
    try { $null = Invoke-ModemAdb $Adb $Serial @('shell', 'busybox killall adbd') } catch { }
    Start-Sleep -Seconds 2
    Wait-Modem $Adb $Serial
    $uid = Invoke-ModemShell $Adb $Serial 'busybox id -u'
    $property = Invoke-ModemShell $Adb $Serial 'getprop service.adb.root'
    if ($uid -ne $expectedUid -or $property -ne $desired) { throw "Unexpected ADB identity: uid=$uid, service.adb.root=$property" }
}

function Test-ArtifactHashes {
    param([Parameter(Mandatory)][string]$Root)
    $manifest = Get-Content -Raw -LiteralPath (Join-Path $Root 'artifacts/manifest.json') | ConvertFrom-Json
    foreach ($item in $manifest.files) {
        $path = Join-Path $Root $item.path
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $item.sha256) {
            throw "Hash mismatch: $($item.path)"
        }
    }
}

Export-ModuleMember -Function Invoke-ModemAdb, Invoke-ModemShell, Assert-Modem, Wait-Modem, Set-ModemRoot, Test-ArtifactHashes
