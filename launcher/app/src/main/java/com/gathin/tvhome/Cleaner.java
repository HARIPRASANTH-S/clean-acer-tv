package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * "Close apps": finds the apps you opened that are still running in the background and force-stops them,
 * to give a 2 GB TV its memory back. Used by the top-bar pill and by a long press on Home (HomeKeyService).
 *
 * An app counts when it has a tile in the launcher (a TV or phone launcher entry) and has a running
 * process. This launcher, the Live TV app and everything in cleaner_keep (config.xml) are never closed.
 * Android 14 does not let one app stop another, so this goes through the TV's own adb ({@link AdbLocal}),
 * like {@link Keeper}; it needs USB debugging on and this app's key accepted.
 */
final class Cleaner {

    /** What is open: app labels in order, and their resident memory added up. */
    static final class Result {
        final List<String> labels = new ArrayList<>();
        final List<String> packages = new ArrayList<>();
        long kb;
        /** Null when the scan or close worked, otherwise why not. */
        String error;

        String summary(Context ctx) {
            return ctx.getResources().getQuantityString(R.plurals.cleaner_pill, labels.size(),
                    labels.size(), Math.round(kb / 1024.0));
        }
    }

    private Cleaner() {
    }

    /** Lists the open apps without closing anything. Blocking: call off the main thread. */
    static Result scan(Context ctx) {
        return run(ctx, false);
    }

    /** Closes every open app and reports what it closed. Blocking: call off the main thread. */
    static Result closeAll(Context ctx) {
        return run(ctx, true);
    }

    /** closeAll on a background thread, then a toast and the callback (may be null) on the main thread. */
    static void closeAllAsync(Context ctx, Consumer<Result> done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Result r = closeAll(app);
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(app, message(app, r), Toast.LENGTH_LONG).show();
                if (done != null) done.accept(r);
            });
        }, "cleaner").start();
    }

    static String message(Context ctx, Result r) {
        if (r.error != null) return ctx.getString(R.string.cleaner_failed);
        if (r.labels.isEmpty()) return ctx.getString(R.string.cleaner_nothing);
        return ctx.getString(R.string.cleaner_done, String.join(", ", r.labels), Math.round(r.kb / 1024.0));
    }

    private static Result run(Context ctx, boolean close) {
        Result r = new Result();
        Map<String, String> candidates = candidates(ctx);
        try (AdbLocal adb = AdbLocal.connect(ctx, ctx.getResources().getInteger(R.integer.keeper_adb_port), 30_000)) {
            // Resident memory per process; app processes are named after their package, sub-processes "pkg:name".
            Map<String, Long> kbByPackage = new LinkedHashMap<>();
            for (String line : adb.shell("ps -A -o RSS,ARGS").split("\n")) {
                String[] cols = line.trim().split("\\s+", 2);
                if (cols.length < 2 || !cols[0].matches("\\d+")) continue;
                String proc = cols[1].trim();
                int colon = proc.indexOf(':');
                String pkg = colon > 0 ? proc.substring(0, colon) : proc;
                if (!candidates.containsKey(pkg)) continue;
                Long sum = kbByPackage.get(pkg);
                kbByPackage.put(pkg, (sum == null ? 0 : sum) + Long.parseLong(cols[0]));
            }
            for (Map.Entry<String, Long> e : kbByPackage.entrySet()) {
                if (close) adb.shell("am force-stop " + e.getKey());
                r.packages.add(e.getKey());
                r.labels.add(candidates.get(e.getKey()));
                r.kb += e.getValue();
            }
        } catch (Exception e) {
            r.error = e.toString();
        }
        return r;
    }

    /** Package → label for every app with a launcher entry that may be closed. */
    private static Map<String, String> candidates(Context ctx) {
        Resources res = ctx.getResources();
        Set<String> keep = new HashSet<>(Arrays.asList(res.getStringArray(R.array.cleaner_keep)));
        keep.add(ctx.getPackageName());
        String liveTv = res.getString(R.string.live_tv_package);
        if (!liveTv.isEmpty()) keep.add(liveTv);

        PackageManager pm = ctx.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (keep.contains(pkg) || out.containsKey(pkg)) continue;
                out.put(pkg, ri.loadLabel(pm).toString());
            }
        }
        return out;
    }
}
