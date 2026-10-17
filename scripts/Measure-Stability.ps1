[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial, [string]$Adb = 'adb',
      [ValidateRange(1,1440)][int]$Minutes = 10,
      [ValidateRange(5,300)][int]$IntervalSeconds = 20)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
Assert-Modem $Adb $Serial
$root = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $root ('.local/measurements/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$rows = [Collections.Generic.List[object]]::new()
$start = [DateTime]::UtcNow
$until = $start.AddMinutes($Minutes)
do {
    $row = [ordered]@{DateUtc=[DateTime]::UtcNow.ToString('o'); Http=0; BootId='';
        Uptime=''; SIM=''; MobileData=''; Forwarding=''; RebootWatchdog=''; CpuRaw=''; Error=''}
    try {
        $http = Invoke-WebRequest -UseBasicParsing -Uri 'http://192.168.100.1:8088/' -TimeoutSec 4
        $row.Http = [int]$http.StatusCode
    } catch { $row.Error = 'HTTP unavailable' }
    try {
        $command = 'cat /proc/sys/kernel/random/boot_id; cat /proc/uptime; getprop gsm.sim.state; settings get global mobile_data; cat /proc/sys/net/ipv4/ip_forward; getprop persist.ufi.nosignal.reboot; cat /sys/class/thermal/thermal_zone0/temp'
        $values = (Invoke-ModemShell $Adb $Serial $command) -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
        if ($values.Count -ne 7) { throw 'Incomplete state sample' }
        $row.BootId=$values[0]; $row.Uptime=$values[1]; $row.SIM=$values[2]
        $row.MobileData=$values[3]; $row.Forwarding=$values[4]; $row.RebootWatchdog=$values[5]; $row.CpuRaw=$values[6]
    } catch { $row.Error += ' ADB state unavailable' }
    $rows.Add([pscustomobject]$row)
    $rows | Export-Csv -LiteralPath (Join-Path $destination 'samples.csv') -NoTypeInformation -Encoding UTF8
    Write-Output "$($row.DateUtc) HTTP=$($row.Http) SIM=$($row.SIM) boot=$($row.BootId) $($row.Error)"
    $remaining = ($until - [DateTime]::UtcNow).TotalSeconds
    if ($remaining -gt 0) { Start-Sleep -Seconds ([int][Math]::Min($IntervalSeconds, [Math]::Ceiling($remaining))) }
} while ([DateTime]::UtcNow -lt $until)
$bootIds = @($rows | Where-Object BootId | Select-Object -ExpandProperty BootId -Unique)
$bad = @($rows | Where-Object { $_.Http -ne 200 -or $_.Error -or $_.SIM -ne 'READY' -or
    $_.MobileData -ne '0' -or $_.Forwarding -ne '0' -or $_.RebootWatchdog -ne 'no' })
$report = [ordered]@{DurationSeconds=[Math]::Round(([DateTime]::UtcNow-$start).TotalSeconds,1);
    Samples=$rows.Count; BadSamples=$bad.Count; UniqueBootIds=$bootIds.Count;
    Passed=($bad.Count -eq 0 -and $bootIds.Count -eq 1); RecordedSmsBodies=$false}
$json = $report | ConvertTo-Json
$json | Set-Content -LiteralPath (Join-Path $destination 'summary.json') -Encoding UTF8
Write-Output $json
Write-Output "Measurements: $destination"
if (-not $report.Passed) { throw 'Stability check failed. See docs/07-troubleshooting.md.' }
