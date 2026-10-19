[CmdletBinding()]
param([Parameter(Mandatory)][string]$Serial,[string]$Adb='adb',[string]$OutputFile)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Modem.psm1') -Force
if((Invoke-ModemShell $Adb $Serial 'getprop ro.build.fingerprint') -ne 'qcom/msm8916_32_512/msm8916_32_512:4.4.4/KTU84P/eng.libinglin.20220328:userdebug/test-keys'){throw 'UF7 firmware mismatch'}
$s=Invoke-RestMethod 'http://192.168.100.1:8088/api/status' -TimeoutSec 10
$p=$s.device.policy
$client=New-Object Net.Sockets.TcpClient
try{$connect=$client.ConnectAsync('192.168.100.1',10242);$done=$connect.Wait(1500);$adbTcp=$done -and $client.Connected}catch{$adbTcp=$false}finally{$client.Dispose()}
$checks=[ordered]@{Version=$s.version;SimReady=($s.simState -eq 5);VoiceRegistered=($s.network.voiceRegistration -eq 0);DataRegistered=($s.network.dataRegistration -eq 0);MobileDataPreserved=($s.device.mobile_data -eq '1');WifiOff=($s.device.wifi_on -eq '0' -and $s.device.wifiApState -eq 11);ForwardingOff=(-not $s.device.forwarding);FirewallEnabled=[bool]$p.firewallEnabled;RoamingOff=($p.dataRoaming -eq 0);TetheringDisabled=($p.tetherSupported -eq 0);WatchdogDisabled=($p.rebootWatchdog -eq 'no');AdbTcpDisabled=($p.persistentAdbTcpPort -eq '-1' -and -not $adbTcp);AdbNormalUser=((Invoke-ModemShell $Adb $Serial 'id') -match 'uid=2000\(shell\)');AutoAnswerEnabled=[bool]$s.autoAnswer;GreetingReady=[bool]$s.greeting.ready;AnswerDelaySeconds=$s.greeting.answerDelaySeconds;MessageSeconds=$s.greeting.messageSeconds;SmsCount=$s.counts.smsTotal;CallCount=$s.counts.callsTotal;ServiceError=$s.error}
$failures=@();foreach($key in @('SimReady','VoiceRegistered','DataRegistered','MobileDataPreserved','WifiOff','ForwardingOff','FirewallEnabled','RoamingOff','TetheringDisabled','WatchdogDisabled','AdbTcpDisabled','AdbNormalUser','AutoAnswerEnabled','GreetingReady')){if(-not $checks[$key]){$failures+=$key}}
if($s.error -or $checks.AnswerDelaySeconds -ne 5 -or $checks.MessageSeconds -ne 20){$failures+='Service/timing'}
$result=[ordered]@{CheckedAtUtc=[DateTime]::UtcNow.ToString('o');Passed=($failures.Count -eq 0);Checks=$checks;Failures=$failures}
$json=$result | ConvertTo-Json -Depth 6
if($OutputFile){$full=[IO.Path]::GetFullPath($OutputFile);New-Item -ItemType Directory -Path (Split-Path $full -Parent) -Force | Out-Null;[IO.File]::WriteAllText($full,$json,[Text.UTF8Encoding]::new($false))}
Write-Output $json
if($failures.Count){throw ('UF7 check failed: '+($failures -join ', '))}
Write-Output 'Configuration verified. Verify a real incoming SMS and caller-heard greeting after a cold boot separately.'
