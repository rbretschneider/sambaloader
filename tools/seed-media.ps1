<#
.SYNOPSIS
Seeds a connected Android device/emulator's camera roll with test media so
sync detection can be exercised by hand.

.DESCRIPTION
Pushes copies of the checked-in testdata/ JPEGs into /sdcard/DCIM/Camera with
unique names, then asks MediaProvider to rescan the volume so they appear in
MediaStore (and the gallery) immediately. Optionally records a short real MP4
on the device itself (no local video corpus is needed).

.EXAMPLE
./tools/seed-media.ps1 -Count 10
./tools/seed-media.ps1 -Count 3 -IncludeVideo -Serial emulator-5554
#>
[CmdletBinding()]
param(
    [int]$Count = 5,
    [switch]$IncludeVideo,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSCommandPath -Parent) -Parent

function Find-Adb {
    $found = Get-Command adb -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    foreach ($base in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\sdk'))) {
        if (-not $base) { continue }
        $candidate = Join-Path $base 'platform-tools\adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    throw 'adb not found. Install Android platform-tools or set ANDROID_HOME.'
}

$adb = Find-Adb
$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" }
if (-not $devices) { throw 'No connected device or running emulator found (adb devices).' }
if ($devices.Count -gt 1 -and -not $Serial) {
    throw "Multiple devices connected. Pass -Serial. Found:`n$($devices -join "`n")"
}

$sources = Get-ChildItem (Join-Path $repoRoot 'testdata') -Filter *.jpg
if (-not $sources) { throw 'testdata/ is empty — run tools/generate-testdata.ps1 first.' }

$remoteDir = '/sdcard/DCIM/Camera'
$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
& $adb @adbArgs shell mkdir -p $remoteDir | Out-Null

Write-Host "Seeding $Count photo(s) to $remoteDir ..."
for ($i = 1; $i -le $Count; $i++) {
    $source = $sources[($i - 1) % $sources.Count]
    $remoteName = "seed_${stamp}_{0:d4}.jpg" -f $i
    & $adb @adbArgs push $source.FullName "$remoteDir/$remoteName" | Out-Null
    Write-Host "  $remoteName  (from $($source.Name))"
}

if ($IncludeVideo) {
    Write-Host 'Recording a 2-second MP4 on the device ...'
    $remoteVideo = "$remoteDir/seed_${stamp}_video.mp4"
    # screenrecord produces a genuine device-encoded MP4 — no local codec needed.
    & $adb @adbArgs shell screenrecord --time-limit 2 --size 640x360 $remoteVideo
    Write-Host "  seed_${stamp}_video.mp4"
}

Write-Host 'Requesting MediaProvider volume rescan ...'
$scanResult = & $adb @adbArgs shell content call --uri content://media --method scan_volume --arg external_primary 2>&1
if ($LASTEXITCODE -ne 0 -or "$scanResult" -match 'Error') {
    Write-Host '  scan_volume unavailable; falling back to per-file broadcast.'
    & $adb @adbArgs shell "for f in $remoteDir/seed_${stamp}_*; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://`$f > /dev/null; done"
}

$imageCount = & $adb @adbArgs shell content query --uri content://media/external/images/media --projection _id |
    Where-Object { $_ -match 'Row' } | Measure-Object | Select-Object -ExpandProperty Count
Write-Host "Done. MediaStore now reports $imageCount image row(s). Check the gallery app."
