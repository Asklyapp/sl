package com.webos.launcher;

import android.app.admin.DeviceAdminReceiver;

/** Needed so the app can become Device Owner (adb / root), which unlocks real kiosk mode. */
public class AdminReceiver extends DeviceAdminReceiver {
}
