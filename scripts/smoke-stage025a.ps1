param(
    [Parameter(Mandatory=$true)][string]$Url,
    [Parameter(Mandatory=$true)][string]$Destination
)
$ErrorActionPreference='Stop'
$taskAdb='C:\Users\damia\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$taskDestination=[System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force $taskDestination | Out-Null
function Invoke-BuildPlanAdb {
    & $taskAdb -s emulator-5570 @args
    if($LASTEXITCODE -ne 0){throw 'ADB command failed on emulator-5570'}
}
Invoke-BuildPlanAdb shell am force-stop com.buildplan.app
Invoke-BuildPlanAdb shell am start -n com.buildplan.app/.analyzer.lab.AnalyzerProductSmokeActivity --es url $Url
Start-Sleep -Seconds 2
$taskProcess=(Invoke-BuildPlanAdb shell pidof com.buildplan.app).Trim()
if($taskProcess -notmatch '^\d+$'){throw 'Expected one BuildPlan process'}
$taskLog=''
for($taskAttempt=0;$taskAttempt -lt 60;$taskAttempt++){
    $taskLog=(Invoke-BuildPlanAdb logcat -d "--pid=$taskProcess" -s ProductSmoke AndroidRuntime) -join "`n"
    if($taskLog.Contains('FATAL EXCEPTION')){throw $taskLog}
    if($taskLog.Contains('COMPLETE')){break}
    Start-Sleep -Seconds 5
}
if(!$taskLog.Contains('COMPLETE') -or !$taskLog.Contains('outcome=Success')){throw "Product smoke did not succeed: $taskLog"}
Start-Sleep -Seconds 5
Set-Content -LiteralPath "$taskDestination\log.txt" -Value $taskLog -Encoding utf8
Invoke-BuildPlanAdb exec-out run-as com.buildplan.app cat files/product-smoke.txt | Set-Content -LiteralPath "$taskDestination\metrics.txt" -Encoding utf8
Invoke-BuildPlanAdb exec-out run-as com.buildplan.app cat files/product-snapshot.json | Set-Content -LiteralPath "$taskDestination\snapshot.json" -Encoding utf8
& $taskAdb -s emulator-5570 exec-out screencap -p > "$taskDestination\screen.png"
if($LASTEXITCODE -ne 0){throw 'Screenshot pull failed'}
Write-Output "COMPLETE: product URL -> $taskDestination"
