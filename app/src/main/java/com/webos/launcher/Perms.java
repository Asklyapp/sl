package com.webos.launcher;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Everything about getting as many permissions as Android allows. */
final class Perms {
    private static final String TAG = "WebOSPerms";

    private Perms() { }

    static String[] requested(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS);
            return pi.requestedPermissions == null ? new String[0] : pi.requestedPermissions;
        } catch (Exception e) {
            return new String[0];
        }
    }

    /** Runtime ("dangerous") permissions that exist on this Android version. */
    static List<String> runtime(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String p : requested(c)) {
            try {
                PermissionInfo info = pm.getPermissionInfo(p, 0);
                int base = Build.VERSION.SDK_INT >= 28 ? info.getProtection() : (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
                if (base == PermissionInfo.PROTECTION_DANGEROUS) out.add(p);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static boolean isBackground(String p) {
        return p.equals("android.permission.ACCESS_BACKGROUND_LOCATION")
                || p.equals("android.permission.BODY_SENSORS_BACKGROUND");
    }

    static List<String> missing(Context c, boolean background) {
        List<String> out = new ArrayList<>();
        for (String p : runtime(c)) {
            if (isBackground(p) != background) continue;
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out;
    }

    /** As Device Owner the app can grant itself every runtime permission with no prompt. */
    static void ownerGrantAll(Context c) {
        if (!Kiosk.isOwner(c)) return;
        DevicePolicyManager d = Kiosk.dpm(c);
        try { d.setPermissionPolicy(Kiosk.admin(c), DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT); } catch (Throwable t) { Log.w(TAG, "policy " + t); }
        for (String p : runtime(c)) {
            try {
                d.setPermissionGrantState(Kiosk.admin(c), c.getPackageName(), p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Throwable t) { Log.w(TAG, "grant " + p + " " + t); }
        }
    }

    /** With root: grant runtime permissions + special access, and try to become Device Owner. */
    static void rootGrantAll(Context c) {
        if (!Bridge.rootAvailable()) return;
        String pkg = c.getPackageName();
        StringBuilder sb = new StringBuilder();
        for (String p : runtime(c)) sb.append("pm grant ").append(pkg).append(' ').append(p).append(" 2>/dev/null; ");
        String[] ops = {"MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "GET_USAGE_STATS",
                "REQUEST_INSTALL_PACKAGES", "SCHEDULE_EXACT_ALARM", "ACCESS_RESTRICTED_SETTINGS"};
        for (String op : ops) sb.append("appops set ").append(pkg).append(' ').append(op).append(" allow 2>/dev/null; ");
        sb.append("dumpsys deviceidle whitelist +").append(pkg).append(" 2>/dev/null; ");
        sb.append("dpm set-device-owner ").append(pkg).append("/.AdminReceiver 2>&1");
        try { Bridge.rootExec(sb.toString(), 60000); } catch (Exception e) { Log.w(TAG, "root grant " + e); }
    }
}
