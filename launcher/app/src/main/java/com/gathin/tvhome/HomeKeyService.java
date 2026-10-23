package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps this launcher on the Home button when the stock home cannot be switched off.
 *
 * Remotes that emit KEYCODE_ALL_APPS for Home (Xiaomi sticks and boxes): Android TV 9 routes that key
 * only to the system-image launcher, so once the stock launcher is disabled the button does nothing.
 *
 * Google TV sets where the stock launcher must stay enabled (Acer/MOKA: disabling it crash-loops Home):
 * Android always resolves Home to the highest-priority HOME activity before looking at the user's
 * choice, and the shell may not disable single components, so Home keeps opening the stock launcher.
 * This service takes KEYCODE_HOME too, and whenever another app's HOME activity comes to the front
 * (after boot, or when an app exits back to Home) it opens this launcher instead.
 * Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Class names of the other apps' HOME activities, e.g. launcherx's .home.HomeActivity. */
    private final Set<String> otherHomes = new HashSet<>();
    private long lastRedirect;

    @Override
    protected void onServiceConnected() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL)) {
            if (!getPackageName().equals(ri.activityInfo.packageName)) otherHomes.add(ri.activityInfo.name);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_ALL_APPS && code != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = event.getClassName();
        if (cls == null || !otherHomes.contains(cls.toString())) return;
        // The stock home reached the front (boot, or Back out of an app): cover it with this one.
        long now = SystemClock.uptimeMillis();
        if (now - lastRedirect < 1500) return; // never ping-pong with a home that insists
        lastRedirect = now;
        goHome();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(getPackageName(), MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
    }

    @Override
    public void onInterrupt() {
    }
}
