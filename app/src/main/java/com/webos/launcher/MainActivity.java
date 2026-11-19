package com.webos.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebNotification;
import org.mozilla.geckoview.WebNotificationDelegate;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements AdminPanel.Listener {

    private static final String TAG = "WebOS";
    private static final String EXTENSION_URI = "resource://android/assets/bridge/";
    private static final String EXTENSION_ID = "bridge@webos.app";
    private static final int REQ_RUNTIME = 6001;
    private static final int REQ_SPECIAL = 6002;
    private static final String NOTIF_CHANNEL = "webos_web";

    private static GeckoRuntime sRuntime;

    private SharedPreferences prefs;
    private FrameLayout root;
    private LockedGeckoView geckoView;
    private GeckoSession session;
    private AdminPanel panel;
    private Bridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();

    private WebExtension.Port port;
    private GeckoSession.PermissionDelegate.Callback pendingPermCallback;

    // lock-down state
    private boolean kioskEnabled = true;
    private boolean suspended = false;      // Settings / another app is on top on purpose
    private boolean wizardActive = false;
    private boolean wizardPending = false;  // first launch: run the permission wizard before locking
    private boolean consumeKeys = true;
    private String powerMode = "intercept"; // "intercept" = wake the screen again and tell the web app
    private long allowSleepUntil = 0;

    // wizard
    private int wizardStage = 0;
    private int specialIndex = 0;
    private List<Step> specialSteps;

    // three-finger double tap
    private int maxPtr = 0;
    private long gestureDown = 0;
    private long lastTap = 0;
    private boolean gestureMoved = false;
    private float cx0, cy0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("webos", MODE_PRIVATE);
        kioskEnabled = prefs.getBoolean("kiosk", true);
        consumeKeys = prefs.getBoolean("consumeKeys", true);
        powerMode = prefs.getString("powerMode", "intercept");

        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        geckoView = new LockedGeckoView(this);
        root.addView(geckoView, new FrameLayout.LayoutParams(-1, -1));
        panel = new AdminPanel(this, this);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        Kiosk.hideSystemUi(this);
        installReHideListener();

        bridge = new Bridge(this, new Bridge.Emitter() {
            public void emit(String event, JSONObject data) { MainActivity.this.emit(event, data); }
        });

        if (sRuntime == null) {
            sRuntime = GeckoRuntime.create(getApplicationContext(), buildRuntimeSettings());
            sRuntime.setWebNotificationDelegate(notificationDelegate);
        }
        openSession();
        installBridgeExtension();
        registerReceivers();

        String url = prefs.getString("url", "");
        if (url.isEmpty()) {
            main.postDelayed(this::openPanel, 400);
        } else {
            session.loadUri(url);
        }
        wizardPending = !prefs.getBoolean("wizardDone", false);
        if (wizardPending) main.postDelayed(() -> runWizard(), 800);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Kiosk.hideSystemUi(this);
        if (kioskEnabled && !suspended && !wizardActive && !wizardPending) Kiosk.engage(this);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        suspended = false; // came back from Settings / another app
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i); // Home pressed -> we are already the home screen
        Kiosk.hideSystemUi(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Kiosk.hideSystemUi(this);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        try { bridge.shutdown(); } catch (Exception ignored) { }
        try {
            if (geckoView != null) geckoView.releaseSession();
            if (session != null) session.close();
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (panel.isOpen()) { closePanel(); return; }
        emitKey(KeyEvent.KEYCODE_BACK, "down", 0);
        emitKey(KeyEvent.KEYCODE_BACK, "up", 0);
    }

    @SuppressWarnings("deprecation")
    private void installReHideListener() {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                if (insets.isVisible(WindowInsets.Type.systemBars())) main.postDelayed(() -> Kiosk.hideSystemUi(this), 120);
                return insets;
            });
        } else {
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(vis -> {
                if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) main.postDelayed(() -> Kiosk.hideSystemUi(this), 120);
            });
        }
    }

    // ------------------------------------------------------------------ GeckoView

    private GeckoRuntimeSettings buildRuntimeSettings() {
        return new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .webManifest(true)
                .aboutConfigEnabled(true)
                .remoteDebuggingEnabled(true)   // about:debugging from desktop Firefox over USB
                .consoleOutput(true)
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
                .contentBlocking(new ContentBlocking.Settings.Builder()
                        .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                        .build())
                .build();
    }

    private void openSession() {
        session = new GeckoSession();

        // No @Override on purpose: if a newer GeckoView renames something the build still succeeds.
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                if (uri != null) session.loadUri(uri); // window.open / target=_blank stay in the same view
                return GeckoResult.<GeckoSession>fromValue(null);
            }
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest request) {
                String u = request.uri == null ? "" : request.uri.toLowerCase();
                if (u.startsWith("mailto:") || u.startsWith("tel:") || u.startsWith("sms:")
                        || u.startsWith("geo:") || u.startsWith("market:") || u.startsWith("intent:")) {
                    openExternalUri(request.uri);
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            public void onCrash(GeckoSession s) { recoverSession(); }
            public void onKill(GeckoSession s) { recoverSession(); }
            public void onExternalResponse(GeckoSession s, WebResponse response) { saveDownload(response); }
        });

        // Everything a page asks for is granted: this is your own OS, not the open web.
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            public void onMediaPermissionRequest(GeckoSession s, String uri, MediaSource[] video,
                                                 MediaSource[] audio, MediaCallback callback) {
                callback.grant(
                        (video != null && video.length > 0) ? video[0] : null,
                        (audio != null && audio.length > 0) ? audio[0] : null);
            }
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
                return GeckoResult.fromValue(Integer.valueOf(ContentPermission.VALUE_ALLOW));
            }
            public void onAndroidPermissionsRequest(GeckoSession s, String[] permissions, Callback callback) {
                if (permissions == null || permissions.length == 0) { callback.grant(); return; }
                List<String> need = new ArrayList<>();
                for (String p : permissions) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
                if (need.isEmpty()) { callback.grant(); return; }
                pendingPermCallback = callback;
                requestPermissions(need.toArray(new String[0]), REQ_RUNTIME + 100);
            }
        });

        // No native pickers or dialogs of any kind: every prompt is dismissed.
        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onColorPrompt(GeckoSession s, ColorPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onDateTimePrompt(GeckoSession s, DateTimePrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onAuthPrompt(GeckoSession s, AuthPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onSharePrompt(GeckoSession s, SharePrompt p) { return GeckoResult.fromValue(p.dismiss()); }
            public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession s, RepostConfirmPrompt p) { return GeckoResult.fromValue(p.dismiss()); }
        });

        // Empty delegate = no text-selection toolbar / copy-paste popups.
        session.setSelectionActionDelegate(new GeckoSession.SelectionActionDelegate() { });

        session.open(sRuntime);
        geckoView.setSession(session);
    }

    private void recoverSession() {
        runOnUiThread(() -> {
            try { geckoView.releaseSession(); session.close(); } catch (Exception ignored) { }
            openSession();
            String url = prefs.getString("url", "");
            if (!url.isEmpty()) session.loadUri(url);
        });
    }

    private void openExternalUri(String uri) {
        try {
            Intent i = uri.toLowerCase().startsWith("intent:")
                    ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            launchExternal(i);
        } catch (Exception e) {
            Log.w(TAG, "No app for " + uri);
        }
    }

    /** Leaves the lock-down on purpose (Settings, another app). Pressing Home brings us back and re-locks. */
    private void launchExternal(Intent i) {
        suspended = true;
        Kiosk.disengage(this);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            suspended = false;
            if (kioskEnabled && !wizardActive) Kiosk.engage(this);
            toast("Can't open that");
        }
    }

    // ------------------------------------------------------------------ JS bridge plumbing

    private final WebExtension.MessageDelegate messageDelegate = new WebExtension.MessageDelegate() {
        public void onConnect(WebExtension.Port p) {
            port = p;
            p.setDelegate(new WebExtension.PortDelegate() {
                public void onPortMessage(Object message, WebExtension.Port pp) { onBridgeMessage(message); }
                public void onPortMessage(JSONObject message, WebExtension.Port pp) { onBridgeMessage(message); }
                public void onDisconnect(WebExtension.Port pp) { if (port == pp) port = null; }
            });
        }
    };

    private void installBridgeExtension() {
        sRuntime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
                .accept(
                        ext -> ext.setMessageDelegate(messageDelegate, "browser"),
                        err -> Log.e(TAG, "Bridge extension failed to install: " + err));
    }

    void emit(final String event, final JSONObject data) {
        runOnUiThread(() -> {
            if (port == null) return;
            try {
                JSONObject m = new JSONObject();
                m.put("event", event);
                m.put("data", data == null ? JSONObject.NULL : data);
                port.postMessage(m);
            } catch (Exception e) { Log.w(TAG, "emit failed " + e); }
        });
    }

    private void reply(final int id, final boolean ok, final Object value) {
        runOnUiThread(() -> {
            if (port == null) return;
            try {
                JSONObject m = new JSONObject();
                m.put("id", id);
                m.put("ok", ok);
                m.put(ok ? "result" : "error", value == null ? JSONObject.NULL : value);
                port.postMessage(m);
            } catch (Exception e) { Log.w(TAG, "reply failed " + e); }
        });
    }

    private static String originOf(String url) {
        try {
            Uri u = Uri.parse(url);
            if (u.getScheme() == null || u.getHost() == null) return "";
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception e) { return ""; }
    }

    private void onBridgeMessage(Object message) {
        try {
            final JSONObject m = (message instanceof JSONObject) ? (JSONObject) message : new JSONObject(String.valueOf(message));
            final int id = m.optInt("id", -1);
            String trusted = originOf(prefs.getString("url", ""));
            if (trusted.isEmpty() || !trusted.equals(m.optString("origin"))) {
                if (id > 0) reply(id, false, "this origin is not trusted by the launcher");
                return;
            }
            final String method = m.optString("method", "");
            if (method.equals("hello")) { emitInfoReady(); return; }
            final JSONArray args = m.optJSONArray("args") == null ? new JSONArray() : m.optJSONArray("args");
            bg.execute(() -> {
                try {
                    Object r = handle(method, args);
                    if (id > 0) reply(id, true, r);
                } catch (Throwable t) {
                    if (id > 0) reply(id, false, String.valueOf(t.getMessage() == null ? t : t.getMessage()));
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Bad bridge message: " + e);
        }
    }

    private void emitInfoReady() {
        try {
            JSONObject d = new JSONObject();
            d.put("deviceOwner", Kiosk.isOwner(this));
            emit("ready", d);
        } catch (Exception ignored) { }
    }

    private <T> T onUi(final Callable<T> c) throws Exception {
        final FutureTask<T> f = new FutureTask<>(c);
        runOnUiThread(f);
        return f.get();
    }

    private Object handle(final String method, final JSONArray a) throws Exception {
        switch (method) {
            case "app.info": return info();
            case "app.setUrl": { final String u = a.getString(0); onUi(() -> { loadUrl(u); return null; }); return true; }
            case "app.reload": onUi(() -> { session.reload(); return null; }); return true;
            case "app.openSettings": {
                final String action = a.optString(0, Settings.ACTION_SETTINGS);
                onUi(() -> { launchExternal(new Intent(action)); return null; });
                return true;
            }
            case "app.openUrl": { final String u = a.getString(0); onUi(() -> { openExternalUri(u); return null; }); return true; }
            case "app.pauseKiosk": {
                final boolean pause = a.optBoolean(0, true);
                onUi(() -> { setKiosk(!pause); return null; });
                return true;
            }
            case "apps.launch": {
                final String pkg = a.getString(0);
                final Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) throw new Exception("no launch intent for " + pkg);
                onUi(() -> { launchExternal(i); return null; });
                return true;
            }

            case "display.setBrightness": {
                final float v = (float) a.optDouble(0, -1);
                onUi(() -> {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.min(1f, v);
                    getWindow().setAttributes(lp);
                    return null;
                });
                return true;
            }
            case "display.keepAwake": {
                final boolean on = a.optBoolean(0, true);
                onUi(() -> {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    return null;
                });
                return true;
            }
            case "display.setOrientation": {
                final String o = a.optString(0, "auto");
                onUi(() -> {
                    setRequestedOrientation(o.equals("portrait") ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            : o.equals("landscape") ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    return null;
                });
                return true;
            }

            case "audio.getVolume": {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int s = streamOf(a.optString(0, "music"));
                int max = am.getStreamMaxVolume(s);
                return max == 0 ? 0 : am.getStreamVolume(s) / (double) max;
            }
            case "audio.setVolume": {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int s = streamOf(a.optString(0, "music"));
                double v = Math.max(0, Math.min(1, a.optDouble(1, 0.5)));
                am.setStreamVolume(s, (int) Math.round(v * am.getStreamMaxVolume(s)), 0);
                return true;
            }

            case "power.setMode": {
                powerMode = a.optString(0, "intercept").equals("normal") ? "normal" : "intercept";
                prefs.edit().putString("powerMode", powerMode).apply();
                return powerMode;
            }
            case "power.sleep": {
                allowSleepUntil = System.currentTimeMillis() + 5000;
                if (Kiosk.isOwner(this)) Kiosk.dpm(this).lockNow();
                else if (Bridge.rootAvailable()) Bridge.rootExec("input keyevent 223", 5000);
                else throw new Exception("needs Device Owner or root to turn the screen off");
                return true;
            }
            case "power.wake": wakeScreen(); return true;
            case "power.reboot": {
                if (Kiosk.isOwner(this)) { Kiosk.dpm(this).reboot(Kiosk.admin(this)); return true; }
                Bridge.rootExec("reboot", 5000); return true;
            }
            case "power.shutdown": Bridge.rootExec("reboot -p", 5000); return true;

            case "keys.setConsume": {
                consumeKeys = a.optBoolean(0, true);
                prefs.edit().putBoolean("consumeKeys", consumeKeys).apply();
                return consumeKeys;
            }

            case "perms.status": return permStatus();
            case "perms.requestAll": onUi(() -> { runWizard(); return null; }); return true;

            case "clipboard.read": return onUi(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cm.getPrimaryClip();
                return (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) ? d.getItemAt(0).getText().toString() : "";
            });
            case "clipboard.write": {
                final String t = a.optString(0, "");
                onUi(() -> {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("webos", t));
                    return null;
                });
                return true;
            }

            case "device.vibrate": vibrate(a.opt(0)); return true;

            default: return bridge.call(method, a);
        }
    }

    private static int streamOf(String s) {
        switch (s) {
            case "ring": return AudioManager.STREAM_RING;
            case "alarm": return AudioManager.STREAM_ALARM;
            case "notification": return AudioManager.STREAM_NOTIFICATION;
            case "voice": return AudioManager.STREAM_VOICE_CALL;
            case "system": return AudioManager.STREAM_SYSTEM;
            default: return AudioManager.STREAM_MUSIC;
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(Object arg) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null) return;
        if (arg instanceof JSONArray) {
            JSONArray arr = (JSONArray) arg;
            long[] t = new long[arr.length()];
            for (int k = 0; k < t.length; k++) t[k] = arr.optLong(k);
            v.vibrate(VibrationEffect.createWaveform(t, -1));
        } else {
            long ms = arg instanceof Number ? ((Number) arg).longValue() : 30;
            v.vibrate(VibrationEffect.createOneShot(Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        o.put("package", getPackageName());
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("model", Build.MODEL);
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("deviceOwner", Kiosk.isOwner(this));
        o.put("kiosk", kioskEnabled);
        o.put("url", prefs.getString("url", ""));
        o.put("powerMode", powerMode);
        o.put("defaultHome", isDefaultHome());
        return o;
    }

    private JSONObject permStatus() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray granted = new JSONArray();
        JSONArray denied = new JSONArray();
        for (String p : Perms.runtime(this)) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted.put(p); else denied.put(p);
        }
        o.put("granted", granted);
        o.put("denied", denied);
        if (Build.VERSION.SDK_INT >= 30) o.put("allFilesAccess", Environment.isExternalStorageManager());
        o.put("overlay", Settings.canDrawOverlays(this));
        o.put("writeSettings", Settings.System.canWrite(this));
        o.put("batteryUnrestricted", ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
        return o;
    }

    // ------------------------------------------------------------------ URL

    private void loadUrl(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return;
        if (!u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            boolean local = u.startsWith("localhost") || u.startsWith("127.") || u.startsWith("192.168.")
                    || u.startsWith("10.") || u.endsWith(".local") || u.matches("^[0-9.]+(:[0-9]+)?(/.*)?$");
            u = (local ? "http://" : "https://") + u;
        }
        prefs.edit().putString("url", u).apply();
        session.loadUri(u);
    }

    // ------------------------------------------------------------------ touch + keys

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            maxPtr = 1; gestureDown = e.getEventTime(); gestureMoved = false;
        } else if (act == MotionEvent.ACTION_POINTER_DOWN) {
            maxPtr = Math.max(maxPtr, e.getPointerCount());
            if (e.getPointerCount() == 3) { cx0 = centroidX(e); cy0 = centroidY(e); }
        } else if (act == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 3) {
            float slop = 90 * getResources().getDisplayMetrics().density / 2f;
            if (Math.abs(centroidX(e) - cx0) > slop || Math.abs(centroidY(e) - cy0) > slop) gestureMoved = true;
        } else if (act == MotionEvent.ACTION_UP) {
            long now = e.getEventTime();
            if (maxPtr == 3 && !gestureMoved && now - gestureDown < 450) {
                if (now - lastTap < 650) { lastTap = 0; togglePanel(); }
                else lastTap = now;
            }
            maxPtr = 0;
        } else if (act == MotionEvent.ACTION_CANCEL) {
            maxPtr = 0;
        }
        return super.dispatchTouchEvent(e);
    }

    private static float centroidX(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i); return s / e.getPointerCount(); }
    private static float centroidY(MotionEvent e) { float s = 0; for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i); return s / e.getPointerCount(); }

    private static boolean isSystemKey(int c) {
        switch (c) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_CAMERA: case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_SEARCH: case KeyEvent.KEYCODE_ASSIST: case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_BRIGHTNESS_UP: case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
            default: return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel.isOpen()) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) closePanel();
            return super.dispatchKeyEvent(e);
        }
        if (consumeKeys && isSystemKey(e.getKeyCode())) {
            emitKey(e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up", e.getRepeatCount());
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void emitKey(int code, String action, int repeat) {
        try {
            JSONObject d = new JSONObject();
            d.put("keyCode", code);
            d.put("key", KeyEvent.keyCodeToString(code).replace("KEYCODE_", ""));
            d.put("action", action);
            d.put("repeat", repeat);
            emit("key", d);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ power button

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String act = i.getAction();
            try {
                JSONObject d = new JSONObject();
                if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                    d.put("state", "screen_off");
                    emit("power", d);
                    if (powerMode.equals("intercept") && System.currentTimeMillis() > allowSleepUntil) wakeScreen();
                } else if (Intent.ACTION_SCREEN_ON.equals(act)) {
                    d.put("state", "screen_on");
                    emit("power", d);
                } else if (Intent.ACTION_BATTERY_CHANGED.equals(act)) {
                    emit("battery", bridge.battery());
                } else if (Intent.ACTION_HEADSET_PLUG.equals(act)) {
                    d.put("plugged", i.getIntExtra("state", 0) == 1);
                    emit("headset", d);
                }
            } catch (Exception ignored) { }
        }
    };

    private void registerReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(Intent.ACTION_HEADSET_PLUG);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
    }

    /**
     * Android handles the power key inside the system, so no app can swallow it. What we CAN do:
     * hear the screen switch off, tell the web app, and switch the screen straight back on.
     */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "webos:wake");
            wl.acquire(2000);
        } catch (Exception e) { Log.w(TAG, "wake failed " + e); }
    }

    // ------------------------------------------------------------------ panel

    private void togglePanel() { if (panel.isOpen()) closePanel(); else openPanel(); }

    private void openPanel() {
        String s = "Device owner: " + (Kiosk.isOwner(this) ? "yes (full lock-down)" : "no (immersive launcher only)")
                + "\nDefault home app: " + (isDefaultHome() ? "yes" : "no")
                + "\nLock-down: " + (kioskEnabled ? "on" : "paused");
        geckoView.clearFocus();
        panel.open(prefs.getString("url", ""), s, kioskEnabled);
    }

    private void closePanel() {
        panel.close();
        Kiosk.hideSystemUi(this);
    }

    private void setKiosk(boolean on) {
        kioskEnabled = on;
        prefs.edit().putBoolean("kiosk", on).apply();
        if (on) Kiosk.engage(this); else Kiosk.disengage(this);
    }

    @Override public void onGo(String url) { loadUrl(url); closePanel(); }
    @Override public void onReload() { session.reload(); closePanel(); }
    @Override public void onAndroidSettings() { closePanel(); launchExternal(new Intent(Settings.ACTION_SETTINGS)); }
    @Override public void onHomeSettings() { closePanel(); launchExternal(new Intent(Settings.ACTION_HOME_SETTINGS)); }
    @Override public void onPermissions() { closePanel(); runWizard(); }
    @Override public void onClose() { closePanel(); }

    @Override
    public void onToggleKiosk() {
        setKiosk(!kioskEnabled);
        openPanel();
    }

    @Override
    public void onRelease() {
        new AlertDialog.Builder(this)
                .setTitle("Release phone?")
                .setMessage("This removes the lock-down and Device Owner so Android behaves normally. You can set it up again later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Release", (d, w) -> {
                    kioskEnabled = false;
                    prefs.edit().putBoolean("kiosk", false).apply();
                    Kiosk.release(this, true);
                    closePanel();
                    launchExternal(new Intent(Settings.ACTION_HOME_SETTINGS));
                })
                .show();
    }

    private boolean isDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) return ((RoleManager) getSystemService(ROLE_SERVICE)).isRoleHeld(RoleManager.ROLE_HOME);
            Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ permission wizard

    private abstract static class Step {
        abstract boolean done();
        abstract Intent intent();
    }

    private Uri pkgUri() { return Uri.parse("package:" + getPackageName()); }

    private List<Step> buildSpecialSteps() {
        List<Step> s = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) s.add(new Step() {
            boolean done() { return Environment.isExternalStorageManager(); }
            Intent intent() { return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri()); }
        });
        s.add(new Step() {
            boolean done() { return Settings.canDrawOverlays(MainActivity.this); }
            Intent intent() { return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri()); }
        });
        s.add(new Step() {
            boolean done() { return Settings.System.canWrite(MainActivity.this); }
            Intent intent() { return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri()); }
        });
        s.add(new Step() {
            @SuppressWarnings("deprecation")
            boolean done() {
                AppOpsManager ao = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
                return ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
            }
            Intent intent() { return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS); }
        });
        s.add(new Step() {
            boolean done() { return ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()); }
            Intent intent() { return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri()); }
        });
        if (Build.VERSION.SDK_INT >= 31) s.add(new Step() {
            boolean done() { return ((AlarmManager) getSystemService(ALARM_SERVICE)).canScheduleExactAlarms(); }
            Intent intent() { return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri()); }
        });
        s.add(new Step() {
            boolean done() { return getPackageManager().canRequestPackageInstalls(); }
            Intent intent() { return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri()); }
        });
        s.add(new Step() {
            boolean done() { return isDefaultHome(); }
            Intent intent() {
                if (Build.VERSION.SDK_INT >= 29) return ((RoleManager) getSystemService(ROLE_SERVICE)).createRequestRoleIntent(RoleManager.ROLE_HOME);
                return new Intent(Settings.ACTION_HOME_SETTINGS);
            }
        });
        return s;
    }

    private void runWizard() {
        if (wizardActive) return;
        wizardActive = true;
        wizardPending = false;
        wizardStage = 0;
        specialIndex = 0;
        Kiosk.disengage(this);
        bg.execute(() -> {
            if (Kiosk.isOwner(this)) Perms.ownerGrantAll(this);
            else if (Bridge.rootAvailable()) {
                Perms.rootGrantAll(this);              // also tries to become Device Owner
                if (Kiosk.isOwner(this)) Perms.ownerGrantAll(this);
            }
            runOnUiThread(this::wizardRuntimeStage);
        });
    }

    private void wizardRuntimeStage() {
        while (wizardStage < 2) {
            List<String> need = Perms.missing(this, wizardStage == 1);
            if (need.isEmpty()) { wizardStage++; continue; }
            requestPermissions(need.toArray(new String[0]), REQ_RUNTIME);
            return;
        }
        specialSteps = buildSpecialSteps();
        specialIndex = 0;
        wizardSpecialStage();
    }

    private void wizardSpecialStage() {
        while (specialIndex < specialSteps.size()) {
            Step st = specialSteps.get(specialIndex);
            if (st.done()) { specialIndex++; continue; }
            try {
                startActivityForResult(st.intent(), REQ_SPECIAL);
                return;
            } catch (Exception e) {
                Log.w(TAG, "wizard step failed " + e);
                specialIndex++;
            }
        }
        wizardActive = false;
        prefs.edit().putBoolean("wizardDone", true).apply();
        if (kioskEnabled) Kiosk.engage(this);
        toast("Permissions done");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUNTIME) {
            wizardStage++;
            wizardRuntimeStage();
        } else if (requestCode == REQ_RUNTIME + 100 && pendingPermCallback != null) {
            boolean all = grantResults.length > 0;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) all = false;
            GeckoSession.PermissionDelegate.Callback cb = pendingPermCallback;
            pendingPermCallback = null;
            if (all) cb.grant(); else cb.reject();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPECIAL && wizardActive) {
            specialIndex++;
            main.postDelayed(this::wizardSpecialStage, 300);
        }
    }

    // ------------------------------------------------------------------ web notifications

    private final WebNotificationDelegate notificationDelegate = new WebNotificationDelegate() {
        public void onShowNotification(WebNotification n) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel(NOTIF_CHANNEL, "WebOS", NotificationManager.IMPORTANCE_DEFAULT));
                Intent open = new Intent(MainActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b = new Notification.Builder(MainActivity.this, NOTIF_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle(n.title)
                        .setContentText(n.text)
                        .setContentIntent(pi)
                        .setAutoCancel(true);
                String key = n.tag != null && !n.tag.isEmpty() ? n.tag : String.valueOf(System.nanoTime());
                nm.notify(key, 1, b.build());
                JSONObject d = new JSONObject();
                d.put("title", n.title);
                d.put("text", n.text);
                emit("notification", d);
            } catch (Exception e) { Log.w(TAG, "Notification failed: " + e); }
        }

        public void onCloseNotification(WebNotification n) {
            try {
                if (n.tag != null && !n.tag.isEmpty()) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(n.tag, 1);
            } catch (Exception ignored) { }
        }
    };

    // ------------------------------------------------------------------ downloads

    private void saveDownload(final WebResponse response) {
        final InputStream body = response.body;
        if (body == null) { toast("Download failed"); return; }
        final String uri = response.uri;
        final Map<String, String> headers = response.headers;
        new Thread(() -> {
            try {
                String mime = headerValue(headers, "content-type");
                if (mime != null && mime.contains(";")) mime = mime.substring(0, mime.indexOf(';')).trim();
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
                String name = downloadName(headers, uri, mime);
                writeToDownloads(name, mime, body);
                toast("Saved to Downloads: " + name);
            } catch (Exception e) {
                Log.w(TAG, "Download failed", e);
                toast("Download failed");
            } finally {
                try { body.close(); } catch (Exception ignored) { }
            }
        }, "webos-download").start();
    }

    private static String headerValue(Map<String, String> headers, String key) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static String downloadName(Map<String, String> headers, String uri, String mime) {
        String name = null;
        String cd = headerValue(headers, "content-disposition");
        if (cd != null) {
            Matcher m = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) name = Uri.decode(m.group(1).trim());
        }
        if (name == null || name.isEmpty()) {
            String last = uri == null ? "" : Uri.parse(uri).getLastPathSegment();
            name = (last == null || last.isEmpty()) ? "download" : last;
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!name.contains(".")) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (ext != null) name = name + "." + ext;
        }
        return name;
    }

    private void writeToDownloads(String name, String mime, InputStream in) throws Exception {
        OutputStream out;
        Uri pending = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            pending = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (pending == null) throw new Exception("could not create download");
            out = getContentResolver().openOutputStream(pending);
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            out = new FileOutputStream(new File(dir, name));
        }
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        } finally {
            out.close();
        }
        if (pending != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(pending, done, null, null);
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }
}
