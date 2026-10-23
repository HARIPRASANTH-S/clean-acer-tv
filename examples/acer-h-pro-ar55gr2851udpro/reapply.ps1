# Run after each TV power-on: this Acer/MOKA firmware re-enables most of the debloat list at boot.
# Disables the list again and stops streaming apps from idling in the background (they still open normally).
# Usage: examples\acer-h-pro-ar55gr2851udpro\reapply.ps1 -TvHost 192.168.0.100
param([string]$TvHost = $env:TV_HOST)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
& (Join-Path $repo 'tools\disable.ps1') -TvHost $TvHost (Join-Path $PSScriptRoot 'debloat.txt')

$streaming = 'com.google.android.youtube.tv', 'com.google.android.youtube.tvmusic', 'com.netflix.ninja',
    'com.amazon.amazonvideo.livingroom', 'com.sonyliv', 'in.startv.hotstar', 'com.graymatrix.did',
    'com.suntv.sunnxt', 'com.google.android.videos'
$cmds = foreach ($p in $streaming) { "cmd appops set $p RUN_ANY_IN_BACKGROUND ignore"; "am force-stop $p" }
$cmds += 'grep MemAvailable /proc/meminfo'
$cmds | & (Join-Path $repo 'tools\venv\Scripts\python.exe') -I (Join-Path $repo 'tools\tv.py') --batch
