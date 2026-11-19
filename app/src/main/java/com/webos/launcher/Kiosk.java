package com.webos.launcher;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * Lock-down logic.
 *  - Always: immersive fullscreen (status + navigation bar hidden).
 *  - Device Owner (set with adb or root): real lock-task kiosk. No status bar, no notification
 *    shade, no home/recents, no power menu, no lock screen, permanent default launcher.
 *  - Not Device Owner: falls back to Android "screen pinning".
 */
final class Kiosk {
    private static final String TAG = "WebOSKiosk";

    private Kiosk() { }

    static ComponentName admin(Context c) { return new ComponentName(c, AdminReceiver.class); }

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isOwner(Context c) {
        try { return dpm(c).isDeviceOwnerApp(c.getPackageName()); } catch (Throwable t) { return false; }
    }

    /** Applies all Device Owner policies. Safe to call repeatedly; no-op when not owner. */
    static void applyPolicies(Context c) {
        if (!isOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        try { d.setLockTaskPackages(a, new String[]{c.getPackageName()}); } catch (Throwable t) { Log.w(TAG, "lockTaskPackages " + t); }
        try { if (Build.VERSION.SDK_INT >= 28) d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE); } catch (Throwable t) { Log.w(TAG, "lockTaskFeatures " + t); }
        try { if (Build.VERSION.SDK_INT >= 28) d.setKeyguardDisabled(a, true); } catch (Throwable t) { Log.w(TAG, "keyguard " + t); }
        try { d.setStatusBarDisabled(a, true); } catch (Throwable t) { Log.w(TAG, "statusbar " + t); }
        try { d.setGlobalSetting(a, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7"); } catch (Throwable t) { Log.w(TAG, "stayon " + t); }
        try { d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT); } catch (Throwable t) { Log.w(TAG, "safeboot " + t); }
        try { d.addUserRestriction(a, UserManager.DISALLOW_ADD_USER); } catch (Throwable t) { Log.w(TAG, "adduser " + t); }
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            d.addPersistentPreferredActivity(a, f, new ComponentName(c, MainActivity.class));
        } catch (Throwable t) { Log.w(TAG, "preferredHome " + t); }
    }

    /** Hides status + navigation bars (swipe from an edge only shows them briefly, then they re-hide). */
    @SuppressWarnings("deprecation")
    static void hideSystemUi(Activity a) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    static void engage(Activity a) {
        hideSystemUi(a);
        applyPolicies(a);
        try { a.startLockTask(); } catch (Throwable t) { Log.w(TAG, "startLockTask " + t); }
    }

    static void disengage(Activity a) {
        try { a.stopLockTask(); } catch (Throwable t) { Log.w(TAG, "stopLockTask " + t); }
    }

    /** Undo every policy and (optionally) give up Device Owner so the phone is normal again. */
    static void release(Activity a, boolean removeOwner) {
        disengage(a);
        if (!isOwner(a)) return;
        DevicePolicyManager d = dpm(a);
        ComponentName n = admin(a);
        try { d.setStatusBarDisabled(n, false); } catch (Throwable ignored) { }
        try { if (Build.VERSION.SDK_INT >= 28) d.setKeyguardDisabled(n, false); } catch (Throwable ignored) { }
        try { d.clearUserRestriction(n, UserManager.DISALLOW_SAFE_BOOT); } catch (Throwable ignored) { }
        try { d.clearUserRestriction(n, UserManager.DISALLOW_ADD_USER); } catch (Throwable ignored) { }
        try { d.setGlobalSetting(n, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0"); } catch (Throwable ignored) { }
        try { d.clearPackagePersistentPreferredActivities(n, a.getPackageName()); } catch (Throwable ignored) { }
        if (removeOwner) {
            try { d.clearDeviceOwnerApp(a.getPackageName()); } catch (Throwable t) { Log.w(TAG, "clearOwner " + t); }
        }
    }
}
