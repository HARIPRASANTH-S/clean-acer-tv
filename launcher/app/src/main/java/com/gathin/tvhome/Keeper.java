package com.gathin.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Re-applies the debloat after every boot, for firmwares that switch disabled apps back on at power-on
 * (the Acer/MOKA Google TVs do). Off unless config.xml lists something under keeper_*.
 *
 * Each pass connects to the TV's own adbd ({@link AdbLocal}) and: disables any keeper_disable package
 * that is enabled again, blocks background running for keeper_no_background, and, with keeper_keep_home,
 * makes this launcher Home again if the firmware handed Home back to the stock one. Passes run a short
 * while after boot and twice more later, because the firmware's own re-enabling runs after boot too.
 * The last pass is logged to Android/data/com.gathin.tvhome/files/keeper.log.
 */
public final class Keeper extends JobService {
    private static final String TAG = "TvHome.Keeper";
    private static final int[] JOB_IDS = {7101, 7102, 7103};
    private static final long[] DELAYS_MS = {20_000, 120_000, 360_000};
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    /** Schedules the passes; with allPasses false only the first, and only if nothing is pending. */
    static void schedule(Context ctx, boolean allPasses) {
        if (!enabled(ctx.getResources())) return;
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!allPasses && js.getPendingJob(JOB_IDS[0]) != null) return;
        for (int i = 0; i < (allPasses ? JOB_IDS.length : 1); i++) {
            js.schedule(new JobInfo.Builder(JOB_IDS[i], new ComponentName(ctx, Keeper.class))
                    .setMinimumLatency(DELAYS_MS[i])
                    .setOverrideDeadline(DELAYS_MS[i] + 30_000)
                    .build());
        }
    }

    private static boolean enabled(Resources r) {
        return r.getStringArray(R.array.keeper_disable).length > 0
                || r.getStringArray(R.array.keeper_no_background).length > 0
                || r.getBoolean(R.bool.keeper_keep_home);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            String result = runPass(getApplicationContext());
            Log.i(TAG, result);
            writeLog(result);
            jobFinished(params, false);
        }, "keeper").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    static String runPass(Context ctx) {
        Resources r = ctx.getResources();
        StringBuilder log = new StringBuilder();
        // Two minutes to accept the "Allow USB debugging?" prompt the first time.
        try (AdbLocal adb = AdbLocal.connect(ctx, r.getInteger(R.integer.keeper_adb_port), 120_000)) {
            Set<String> disabled = new HashSet<>();
            for (String line : adb.shell("pm list packages -d").split("\n")) {
                if (line.startsWith("package:")) disabled.add(line.substring(8).trim());
            }
            for (String pkg : r.getStringArray(R.array.keeper_disable)) {
                if (!PACKAGE.matcher(pkg).matches() || disabled.contains(pkg)) continue;
                log.append(adb.shell("pm disable-user --user 0 " + pkg).trim()).append('\n');
            }
            for (String pkg : r.getStringArray(R.array.keeper_no_background)) {
                if (!PACKAGE.matcher(pkg).matches()) continue;
                adb.shell("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore");
            }
            if (r.getBoolean(R.bool.keeper_keep_home)) keepHome(ctx, adb, log);
            return log.length() == 0 ? "all in place" : log.toString().trim();
        } catch (Exception e) {
            return "failed: " + e; // USB debugging off, or the prompt was declined
        }
    }

    /**
     * Keeps Home on this launcher. Where the stock launcher stays enabled it out-ranks this one in Android's
     * Home resolution whatever the preference says, so HomeKeyService does the real work; this keeps the
     * preference and that service switched on, and covers the stock home if it is on screen.
     */
    private static void keepHome(Context ctx, AdbLocal adb, StringBuilder log) throws java.io.IOException {
        String me = ctx.getPackageName() + "/.MainActivity";
        adb.shell("cmd package set-home-activity --user 0 " + me);
        String svc = ctx.getPackageName() + "/.HomeKeyService";
        String enabled = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (!enabled.contains(svc)) {
            String value = enabled.isEmpty() || enabled.equals("null") ? svc : enabled + ":" + svc;
            adb.shell("settings put secure enabled_accessibility_services '" + value + "'");
            adb.shell("settings put secure accessibility_enabled 1");
            log.append("HomeKeyService switched back on\n");
        }
        String top = adb.shell("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'");
        String homes = adb.shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : homes.split("\n")) {
            String c = line.trim();
            if (!c.contains("/") || c.startsWith(ctx.getPackageName() + "/")) continue;
            if (top.contains(c)) {
                adb.shell("am start -n " + me);
                log.append("covered ").append(c).append('\n');
                break;
            }
        }
    }

    private void writeLog(String result) {
        File dir = Config.dir(this);
        if (dir == null) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        try (FileWriter w = new FileWriter(new File(dir, "keeper.log"))) {
            w.write(stamp + "\n" + result + "\n");
        } catch (Exception e) {
            Log.w(TAG, "could not write keeper.log", e);
        }
    }

    /** Starts the passes after every boot. */
    public static final class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) schedule(ctx, true);
        }
    }
}
