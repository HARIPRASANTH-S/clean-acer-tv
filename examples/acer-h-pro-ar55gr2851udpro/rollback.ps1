# Full rollback for the Acer H PRO AR55GR2851UDPRO: puts the TV back the way it came from the factory
# (state saved on 19 Oct 2026, before any change). See ROLLBACK.md for the plan and the manual steps.
#
# Usage: examples\acer-h-pro-ar55gr2851udpro\rollback.ps1 -TvHost 192.168.0.100 [-DryRun]
#        -DryRun prints the commands and changes nothing.
param(
    [string]$TvHost = $env:TV_HOST,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'

# Every package the debloat list disables (comment lines and inline comments ignored).
$pkgs = Get-Content (Join-Path $PSScriptRoot 'debloat.txt') |
    ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
$streaming = 'com.google.android.youtube.tv', 'com.google.android.youtube.tvmusic', 'com.netflix.ninja',
    'com.amazon.amazonvideo.livingroom', 'com.sonyliv', 'in.startv.hotstar', 'com.graymatrix.did',
    'com.suntv.sunnxt', 'com.google.android.videos'

$cmds = @(
    # 1. The launcher first: it carries the keeper, which would re-disable everything at the next boot.
    'settings delete secure enabled_accessibility_services',
    'settings put secure accessibility_enabled 0',
    'pm uninstall com.gathin.tvhome',
    # 2. Google TV home back as Home.
    'pm enable com.google.android.apps.tv.launcherx',
    'pm enable com.google.android.tungsten.setupwraith',
    'cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity'
)
# 3. Every disabled package back on.
$cmds += $pkgs | ForEach-Object { "pm enable $_" }
# 4. Streaming apps may run in the background again.
$cmds += $streaming | ForEach-Object { "cmd appops set $_ RUN_ANY_IN_BACKGROUND allow" }
$cmds += @(
    # 5. Factory settings: animation speed and the Ambient mode screensaver.
    'settings put global window_animation_scale 1.0',
    'settings put global transition_animation_scale 1.0',
    'settings put global animator_duration_scale 1.0',
    'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop',
    'settings put secure screensaver_enabled 1',
    # 6. Show the stock home and report what is left.
    'am start -a android.intent.action.MAIN -c android.intent.category.HOME',
    'echo disabled-now: $(pm list packages -d | wc -l)  (factory: 0)',
    'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
)

if ($DryRun) { $cmds; return }
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
$env:TV_HOST = $TvHost
$cmds | & $py -I (Join-Path $repo 'tools\tv.py') --batch
Write-Host 'Done. Finish with the manual steps in ROLLBACK.md (revoke debugging keys, turn USB debugging off).'
