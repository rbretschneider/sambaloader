<#
.SYNOPSIS
Regenerates the checked-in test media corpus under testdata/ and its
manifest.json of known SHA-256 hashes.

.DESCRIPTION
Produces small JPEGs with real EXIF DateTimeOriginal tags (the app reads
capture time from EXIF/MediaStore) plus one unicode-named file. Run only when
the corpus needs to change — every regeneration changes the hashes, and
TestDataManifestTest pins them.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot
)

$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) {
    # $PSScriptRoot is unreliable inside param() defaults on Windows PowerShell 5.1.
    $RepoRoot = Split-Path (Split-Path $PSCommandPath -Parent) -Parent
}
Add-Type -AssemblyName System.Drawing

$outDir = Join-Path $RepoRoot 'testdata'
New-Item -ItemType Directory -Force $outDir | Out-Null

function New-ExifProperty([int]$Id, [string]$Value) {
    # PropertyItem has no public constructor; this is the standard workaround.
    $item = [System.Runtime.Serialization.FormatterServices]::GetUninitializedObject([System.Drawing.Imaging.PropertyItem])
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($Value + [char]0)
    $item.Id = $Id
    $item.Type = 2  # ASCII
    $item.Len = $bytes.Length
    $item.Value = $bytes
    return $item
}

function New-TestJpeg {
    param(
        [string]$Path,
        [string]$Label,
        [System.Drawing.Color]$Color,
        [datetime]$CapturedAt,
        [int]$Width,
        [int]$Height
    )
    $bitmap = New-Object System.Drawing.Bitmap($Width, $Height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear($Color)
        $font = New-Object System.Drawing.Font('Arial', 20)
        $brush = [System.Drawing.Brushes]::White
        $graphics.DrawString($Label, $font, $brush, 20, 20)
        $graphics.DrawString($CapturedAt.ToString('yyyy-MM-dd HH:mm:ss'), $font, $brush, 20, 60)

        # 0x9003 DateTimeOriginal, 0x0132 DateTime — EXIF format "yyyy:MM:dd HH:mm:ss"
        $exifDate = $CapturedAt.ToString('yyyy:MM:dd HH:mm:ss')
        $bitmap.SetPropertyItem((New-ExifProperty 0x9003 $exifDate))
        $bitmap.SetPropertyItem((New-ExifProperty 0x0132 $exifDate))

        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

$assets = @(
    @{ Name = 'red_landscape.jpg';        Color = [System.Drawing.Color]::DarkRed;    CapturedAt = [datetime]'2024-06-15T14:23:17'; Width = 640;  Height = 480 }
    @{ Name = 'green_portrait.jpg';       Color = [System.Drawing.Color]::DarkGreen;  CapturedAt = [datetime]'2024-06-15T14:23:17'; Width = 480;  Height = 640 }
    @{ Name = 'blue_large.jpg';           Color = [System.Drawing.Color]::DarkBlue;   CapturedAt = [datetime]'2025-01-01T00:00:01'; Width = 1920; Height = 1080 }
    @{ Name = 'gray_tiny.jpg';            Color = [System.Drawing.Color]::DimGray;    CapturedAt = [datetime]'2023-12-31T23:59:59'; Width = 64;   Height = 64 }
    @{ Name = ([string]([char]0x66 + 'am' + [char]0xED + 'lia_praia_' + [char]0x65E5 + [char]0x672C + '.jpg')); Color = [System.Drawing.Color]::DarkOrange; CapturedAt = [datetime]'2024-08-30T09:15:00'; Width = 640; Height = 480 }
)

$manifest = @()
foreach ($asset in $assets) {
    $path = Join-Path $outDir $asset.Name
    New-TestJpeg -Path $path -Label 'Sambaloader test asset' -Color $asset.Color -CapturedAt $asset.CapturedAt -Width $asset.Width -Height $asset.Height
    $hash = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    $file = Get-Item -LiteralPath $path
    $manifest += [ordered]@{
        name       = $asset.Name
        sha256     = $hash
        sizeBytes  = $file.Length
        mimeType   = 'image/jpeg'
        capturedAt = $asset.CapturedAt.ToString('yyyy-MM-ddTHH:mm:ss')
    }
    Write-Host "  $($asset.Name)  $($file.Length) bytes  $hash"
}

$manifestPath = Join-Path $outDir 'manifest.json'
$json = ConvertTo-Json @($manifest) -Depth 3
[System.IO.File]::WriteAllText($manifestPath, $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Wrote $manifestPath with $($manifest.Count) entries."
