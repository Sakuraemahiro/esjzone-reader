# Installs the APK on a running emulator and proves it actually starts.
#
# The adb daemon does not survive between separate shells here, so the whole sequence -
# connect, install, launch, inspect, screenshot - has to run in one invocation.
[CmdletBinding()]
param(
    [string]$Adb = 'F:\AndroidWatch_ADB_ToolBox\adb\adb.exe',
    [string]$Device = '127.0.0.1:16384',
    [string]$Package = 'com.esjzone.reader',
    [string]$Apk
)

$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path (Split-Path $Root -Parent) 'ESJZone-Reader-1.0.apk' }
$Apk = (Resolve-Path $Apk).Path
$Shot = Join-Path $Root '..\build\shot.png'
$ShotMain = Join-Path $Root '..\build\shot-main.png'

function AdbRun([string[]]$arguments) {
    & $Adb -s $Device @arguments 2>&1
}

Write-Host "==> adb server" -ForegroundColor Cyan
& $Adb kill-server *> $null
Start-Sleep -Milliseconds 800
& $Adb start-server *> $null
Start-Sleep -Milliseconds 800

Write-Host "==> connect $Device" -ForegroundColor Cyan
& $Adb connect $Device
Start-Sleep -Seconds 3
$devices = & $Adb devices
if (-not ($devices -match [regex]::Escape($Device) + '\s+device')) {
    Write-Host "device $Device is not online:" -ForegroundColor Red
    $devices
    exit 1
}

Write-Host "==> install $Apk" -ForegroundColor Cyan
$install = AdbRun @('install', '-r', $Apk)
$install
if (-not ($install -match 'Success')) {
    Write-Host "install failed" -ForegroundColor Red
    exit 1
}

Write-Host "==> launch" -ForegroundColor Cyan
AdbRun @('shell', 'am', 'force-stop', $Package) | Out-Null
AdbRun @('logcat', '-c') | Out-Null
AdbRun @('shell', 'am', 'start', '-n', "$Package/.MainActivity") | Out-Null
Start-Sleep -Seconds 9

$log = AdbRun @('logcat', '-d', '-v', 'brief')
$crash = $log | Select-String -Pattern 'FATAL EXCEPTION|InflateException|Unable to start activity|ClassNotFoundException'
if ($crash) {
    Write-Host "==> CRASH" -ForegroundColor Red
    ($log | Select-String -Pattern 'AndroidRuntime' | Select-Object -First 20)
    exit 1
}
Write-Host "    no crash in the launch log" -ForegroundColor Green

$top = (AdbRun @('shell', 'dumpsys', 'activity', 'activities')) |
    Select-String -Pattern 'topResumedActivity' | Select-Object -First 1
Write-Host "    $($top.ToString().Trim())"

AdbRun @('shell', 'screencap', '-p', '/sdcard/shot.png') | Out-Null
AdbRun @('pull', '/sdcard/shot.png', $Shot) | Out-Null

# back out of the login screen so the main list is on top as well
Write-Host "==> back to the main list" -ForegroundColor Cyan
AdbRun @('shell', 'input', 'keyevent', '4') | Out-Null
Start-Sleep -Seconds 3
$log = AdbRun @('logcat', '-d', '-v', 'brief')
$crash = $log | Select-String -Pattern 'FATAL EXCEPTION'
if ($crash) {
    Write-Host "==> CRASH after back" -ForegroundColor Red
    ($log | Select-String -Pattern 'AndroidRuntime' | Select-Object -First 20)
    exit 1
}
$top = (AdbRun @('shell', 'dumpsys', 'activity', 'activities')) |
    Select-String -Pattern 'topResumedActivity' | Select-Object -First 1
Write-Host "    $($top.ToString().Trim())"
AdbRun @('shell', 'screencap', '-p', '/sdcard/shot2.png') | Out-Null
AdbRun @('pull', '/sdcard/shot2.png', $ShotMain) | Out-Null

Write-Host ''
Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
Write-Host "  login screen: $Shot"
Write-Host "  main list:    $ShotMain"
