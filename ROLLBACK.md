# Rollback plan: Acer H PRO 55" Google TV (AR55GR2851UDPRO)

How to put this TV back exactly the way it came from the factory, or undo just one change.
Nothing was uninstalled from the TV's system and nothing was rooted, so every change here can be undone
except the removed user profile (see [What cannot be undone](#what-cannot-be-undone)).

TV: Acer H PRO Series 55" 4K, Indkal / MOKA platform (model R4G, device R4_GTV), Google TV 14,
firmware UKR1.260308. Factory state recorded 19 Oct 2026, before any change.

## What was changed

| # | Change | Where it lives | Undo |
|---|---|---|---|
| 1 | New home screen (CleanYourTV launcher, `com.gathin.tvhome`), including the **Close apps** button in the top bar and **hold Home** to close the apps you opened (both force-stop apps over the launcher's adb key) | Installed app | Uninstall the app. To keep the launcher but drop the feature, set `cleaner_enabled` to false in `config.xml` and rebuild |
| 2 | **Debloat keeper** inside that launcher: after every boot it reconnects to the TV's own adb (127.0.0.1:5555) and re-disables the bloat | Same app, plus its own adb key trusted by the TV | Uninstall the app, then revoke debugging keys |
| 3 | Two accessibility services from the launcher: Home button and Input button | Settings → secure `enabled_accessibility_services` | Clear the setting (factory: none, `accessibility_enabled=0`) |
| 4 | 16 apps disabled (ads, demo overlays, TV Manager, meeting mode, mirroring apps, Assistant, TalkBack, Ambient mode, Play Games, Feedback, Calendar sync) | `pm disable-user` | `pm enable` each one; list in [`debloat.txt`](examples/acer-h-pro-ar55gr2851udpro/debloat.txt) (factory: nothing disabled) |
| 5 | 9 streaming apps blocked from running in the background (YouTube, YouTube Music, Netflix, Prime Video, SonyLIV, JioHotstar, ZEE5, Sun NXT, Google TV) | App-ops `RUN_ANY_IN_BACKGROUND ignore` | Set back to `allow` |
| 6 | Animations sped up to 0.5x | Settings → global `*_animation_scale` | Set to 1.0 (factory) |
| 7 | Screensaver set to the launcher's **Clock** screensaver (`com.gathin.tvhome/.ClockDream`), after 10 minutes idle. Ambient mode was disabled with the debloat, and Android had fallen back to "Colors" | Settings → secure `screensaver_components` | `com.google.android.apps.tv.dreamx/.service.Backdrop` (factory) |
| 8 | Second user profile (`new_user`, id 10) removed | Android users | **Cannot be undone**, re-create it |
| 9 | USB debugging switched on, the computer's and the launcher's adb keys trusted | Developer options | Revoke authorizations, switch debugging off |

The Google TV home (`com.google.android.apps.tv.launcherx`) was **never disabled**. On this firmware disabling
it makes Home crash-loop, so it stays installed and the new launcher just covers it.

## Full rollback (recommended order)

Run from the repo folder on a computer on the same Wi-Fi as the TV, with the TV switched on.

1. **Preview** what will run (changes nothing):

   ```powershell
   powershell -ExecutionPolicy Bypass -File examples\acer-h-pro-ar55gr2851udpro\rollback.ps1 -DryRun
   ```

2. **Run it**:

   ```powershell
   powershell -ExecutionPolicy Bypass -File examples\acer-h-pro-ar55gr2851udpro\rollback.ps1 -TvHost 192.168.0.100
   ```

   macOS / Linux: `TV_HOST=192.168.0.100 examples/acer-h-pro-ar55gr2851udpro/rollback.sh`

   The script does, in this order:
   1. Switches off the launcher's accessibility services and **uninstalls the launcher first**. It carries the
      keeper, which would otherwise re-disable everything at the next power-on.
   2. Makes the Google TV home the default Home again.
   3. Re-enables every package in `debloat.txt`.
   4. Lets the streaming apps run in the background again.
   5. Restores animation speed 1.0 and the Ambient mode screensaver.
   6. Opens the Google TV home and prints how many packages are still disabled (should be 0) and which app
      owns Home (should be `com.google.android.apps.tv.launcherx/.home.HomeActivity`).

3. **Manual steps on the TV** (the script cannot do these):
   1. Settings → System → Developer options → **Revoke USB debugging authorizations**. This removes the
      launcher's key and this computer's key.
   2. In the same menu switch **USB debugging** off (and Developer options off if you like).
   3. If you turned on **Apps only mode** (Settings → Accounts & sign-in → your account), switch it off to get
      the Google TV recommendations back.
   4. Re-create the second profile if you want it (Settings → Accounts & sign-in → Add a child / Add account).
   5. Turn the TV off and on once, and check that the Google TV home comes up.

4. **Check**: after the restart everything should look like the stock Google TV. This firmware itself
   re-enables most apps at every power-on anyway, so leftovers are unlikely.

## Partial rollbacks

Use `tools/tv.py` with `TV_HOST` set (PowerShell: `$env:TV_HOST='192.168.0.100'`), one line each:

| I want to… | Command |
|---|---|
| Keep the debloat but get the Google TV home back | `tools/venv/Scripts/python.exe -I tools/tv.py "settings delete secure enabled_accessibility_services; pm uninstall com.gathin.tvhome"` |
| Keep the new home but stop the automatic re-apply | Rebuild the launcher with the three `keeper_*` entries in `launcher/app/src/main/res/values/config.xml` emptied/false, reinstall, then revoke debugging keys and re-trust only the computer |
| Get one app back (e.g. Google Assistant) | `tools/venv/Scripts/python.exe -I tools/tv.py "pm enable com.google.android.katniss"`, and remove it from `keeper_disable` in `config.xml` and rebuild, or the keeper turns it off again at the next boot |
| Let one streaming app run in the background again | `tools/venv/Scripts/python.exe -I tools/tv.py "cmd appops set com.netflix.ninja RUN_ANY_IN_BACKGROUND allow"` (also remove it from `keeper_no_background`) |
| Keep the new home but use Android's "Colors" screensaver | `tools/venv/Scripts/python.exe -I tools/tv.py "settings put secure screensaver_components com.android.dreams.basic/com.android.dreams.basic.Colors"` |
| Normal animation speed | `tools/venv/Scripts/python.exe -I tools/tv.py "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0"` |

## If something goes wrong

| Symptom | Fix |
|---|---|
| Home shows a blank screen or crashes in a loop, the TV drops off Wi-Fi | `pm enable --user 0 com.google.android.apps.tv.launcherx` over adb (with the TV on a cable or as soon as it is reachable), or hold Power on the remote to restart. This is what happened once when launcherx was disabled; the current setup never disables it. |
| Home button opens Google TV instead of the new launcher | Settings → Accessibility → turn on "Home button" (CleanYourTV), or wait for the next boot: the keeper switches it back on |
| Bloat is back after a power-on | Debugging was switched off or the keeper's key was revoked. Run `examples\acer-h-pro-ar55gr2851udpro\reapply.ps1 -TvHost <ip>` from the computer |
| The computer cannot reach the TV | Check the TV's IP in Settings → Network; USB debugging must be on; accept "Allow USB debugging?" on the TV |
| Nothing else works | Settings → System → About → Factory reset. Every change here lives in user data, so a factory reset returns the TV to stock (and erases accounts and apps) |

## What cannot be undone

- **The removed second profile** (`new_user`, id 10). Android deletes a removed user's data. Re-create the
  profile on the TV; apps and sign-ins on it have to be set up again.

## Files

- [`examples/acer-h-pro-ar55gr2851udpro/rollback.ps1`](examples/acer-h-pro-ar55gr2851udpro/rollback.ps1) / [`rollback.sh`](examples/acer-h-pro-ar55gr2851udpro/rollback.sh): full rollback
- [`examples/acer-h-pro-ar55gr2851udpro/debloat.txt`](examples/acer-h-pro-ar55gr2851udpro/debloat.txt): what was disabled and why
- [`examples/acer-h-pro-ar55gr2851udpro/reapply.ps1`](examples/acer-h-pro-ar55gr2851udpro/reapply.ps1): re-apply the debloat by hand
- [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml): keeper lists (`keeper_*`)
- The factory backup (package lists, settings, getprop) is in `backup/` on the computer that made the changes.
  It holds the TV's serial number and device IDs, so it is kept out of git on purpose.
