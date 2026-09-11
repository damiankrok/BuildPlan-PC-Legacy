param(
    [Parameter(Mandatory=$true)][string]$Snapshot,
    [Parameter(Mandatory=$true)][string]$Destination,
    [ValidateSet('After','Before','Reference')][string]$Mode='After'
)
$ErrorActionPreference='Stop'
$taskAdb='C:\Users\damia\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$taskSnapshot=(Resolve-Path -LiteralPath $Snapshot).Path
$taskDestination=[System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force $taskDestination | Out-Null
function Invoke-BuildPlanAdb {
    & $taskAdb -s emulator-5570 @args
    if($LASTEXITCODE -ne 0){ throw "ADB command failed on emulator-5570" }
}
Invoke-BuildPlanAdb push $taskSnapshot /data/local/tmp/buildplan-evidence.json
Invoke-BuildPlanAdb shell run-as com.buildplan.app cp /data/local/tmp/buildplan-evidence.json files/evidence-snapshot.json
Invoke-BuildPlanAdb shell am force-stop com.buildplan.app
$taskExtra=@()
if($Mode -eq 'Before'){$taskExtra=@('--ez','baselineAdapter','true')}
if($Mode -eq 'Reference'){$taskExtra=@('--ez','reference','true')}
Invoke-BuildPlanAdb shell am start -n com.buildplan.app/.analyzer.lab.ReconstructionEvidenceActivity --ez captureAll true --ez fixedCamera true @taskExtra
Start-Sleep -Seconds 2
$taskProcess=(Invoke-BuildPlanAdb shell pidof com.buildplan.app).Trim()
if($taskProcess -notmatch '^\d+$'){throw 'Expected one BuildPlan process'}
$taskLog=''
for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++){
    $taskLog=(Invoke-BuildPlanAdb logcat -d "--pid=$taskProcess" -s ReconstructionEvidence AndroidRuntime) -join "`n"
    if($taskLog.Contains('FATAL EXCEPTION')){throw $taskLog}
    if($taskLog.Contains('COMPLETE')){break}
    Start-Sleep -Seconds 5
}
if(!$taskLog.Contains('COMPLETE') -or ([regex]::Matches($taskLog,'capture=0')).Count -ne 8){throw 'Eight fresh successful captures were not completed'}
Set-Content -LiteralPath "$taskDestination\capture-log.txt" -Value $taskLog -Encoding utf8
$taskPrefix=if($Mode -eq 'Reference'){'reference'}else{'candidate'}
for($taskIndex=0;$taskIndex -lt 8;$taskIndex++){
    # PowerShell 7 preserves native binary stdout when redirected directly to a file.
    & $taskAdb -s emulator-5570 exec-out run-as com.buildplan.app cat "files/$taskPrefix-$taskIndex.png" > "$taskDestination\view-$taskIndex.png"
    if($LASTEXITCODE -ne 0){throw 'Capture pull failed'}
    Invoke-BuildPlanAdb exec-out run-as com.buildplan.app cat "files/$taskPrefix-$taskIndex-camera.txt" | Set-Content -LiteralPath "$taskDestination\view-$taskIndex-camera.txt" -Encoding utf8
}
Get-FileHash -LiteralPath $taskSnapshot -Algorithm SHA256 | Format-List | Out-String | Set-Content -LiteralPath "$taskDestination\snapshot-sha256.txt" -Encoding utf8
Write-Output "COMPLETE: $Mode -> $taskDestination"
