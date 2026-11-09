$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$res = 'D:\Projects\AndroidStudioProjects\MyEmbyHub\tvprobe\src\main\res'
$dens = [ordered]@{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }

$bg     = [System.Drawing.Color]::FromArgb(18, 20, 26)
$accent = [System.Drawing.Color]::FromArgb(0, 200, 120)

foreach ($k in $dens.Keys) {
    $s = $dens[$k]
    $dir = Join-Path $res "mipmap-$k"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $bmp = New-Object System.Drawing.Bitmap($s, $s)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear($bg)

    $pad = [int]($s * 0.14)
    $w = [float]($s - 2 * $pad)
    $rect = New-Object System.Drawing.RectangleF([float]$pad, [float]$pad, $w, $w)
    $brush = New-Object System.Drawing.SolidBrush($accent)
    $g.FillRectangle($brush, $rect)

    $font = New-Object System.Drawing.Font('Arial', [float]($s * 0.42), [System.Drawing.FontStyle]::Bold)
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = 'Center'
    $fmt.LineAlignment = 'Center'
    $g.DrawString('TV', $font, [System.Drawing.Brushes]::Black, $rect, $fmt)

    $bmp.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose(); $font.Dispose(); $brush.Dispose()
}

# TV launcher banner 320x180
$dir = Join-Path $res 'drawable-xhdpi'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$bmp = New-Object System.Drawing.Bitmap(320, 180)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.Clear($bg)
$brush = New-Object System.Drawing.SolidBrush($accent)
$g.FillRectangle($brush, 20, 40, 280, 100)
$font = New-Object System.Drawing.Font('Arial', 48, [System.Drawing.FontStyle]::Bold)
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = 'Center'
$fmt.LineAlignment = 'Center'
$g.DrawString('TV Probe', $font, [System.Drawing.Brushes]::Black, (New-Object System.Drawing.RectangleF(20, 40, 280, 100)), $fmt)
$bmp.Save((Join-Path $dir 'ic_banner.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose(); $font.Dispose(); $brush.Dispose()

Get-ChildItem $res -Recurse -Filter '*.png' | Select-Object FullName, Length | Format-Table -AutoSize
