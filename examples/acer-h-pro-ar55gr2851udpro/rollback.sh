#!/usr/bin/env bash
# Full rollback for the Acer H PRO AR55GR2851UDPRO (macOS/Linux version of rollback.ps1).
# Puts the TV back the way it came from the factory. See ROLLBACK.md for the plan and the manual steps.
#
# Usage: TV_HOST=192.168.0.100 examples/acer-h-pro-ar55gr2851udpro/rollback.sh [--dry-run]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
py="$repo/tools/venv/bin/python"

streaming="com.google.android.youtube.tv com.google.android.youtube.tvmusic com.netflix.ninja
com.amazon.amazonvideo.livingroom com.sonyliv in.startv.hotstar com.graymatrix.did com.suntv.sunnxt
com.google.android.videos"

cmds() {
    # 1. The launcher first: it carries the keeper, which would re-disable everything at the next boot.
    echo 'settings delete secure enabled_accessibility_services'
    echo 'settings put secure accessibility_enabled 0'
    echo 'pm uninstall com.gathin.tvhome'
    # 2. Google TV home back as Home.
    echo 'pm enable com.google.android.apps.tv.launcherx'
    echo 'pm enable com.google.android.tungsten.setupwraith'
    echo 'cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity'
    # 3. Every disabled package back on.
    sed -e 's/#.*//' -e 's/[[:space:]]//g' "$here/debloat.txt" | grep -v '^$' | sed 's/^/pm enable /'
    # 4. Streaming apps may run in the background again.
    for p in $streaming; do echo "cmd appops set $p RUN_ANY_IN_BACKGROUND allow"; done
    # 5. Factory settings: animation speed and the Ambient mode screensaver.
    echo 'settings put global window_animation_scale 1.0'
    echo 'settings put global transition_animation_scale 1.0'
    echo 'settings put global animator_duration_scale 1.0'
    echo 'settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop'
    echo 'settings put secure screensaver_enabled 1'
    # 6. Show the stock home and report what is left.
    echo 'am start -a android.intent.action.MAIN -c android.intent.category.HOME'
    echo 'echo disabled-now: $(pm list packages -d | wc -l)  (factory: 0)'
    echo 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'
}

if [ "${1:-}" = "--dry-run" ]; then cmds; exit 0; fi
: "${TV_HOST:?set TV_HOST to the TV's IP address}"
[ -x "$py" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
cmds | "$py" -I "$repo/tools/tv.py" --batch
echo 'Done. Finish with the manual steps in ROLLBACK.md (revoke debugging keys, turn USB debugging off).'
