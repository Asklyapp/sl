package com.webos.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bridge methods that don't need the activity: sensors, files, apps, root, battery. */
final class Bridge {

    interface Emitter { void emit(String event, JSONObject data); }

    private static final Map<String, Integer> SENSOR_TYPES = new HashMap<>();
    static {
        SENSOR_TYPES.put("accelerometer", Sensor.TYPE_ACCELEROMETER);
        SENSOR_TYPES.put("magnetometer", Sensor.TYPE_MAGNETIC_FIELD);
        SENSOR_TYPES.put("gyroscope", Sensor.TYPE_GYROSCOPE);
        SENSOR_TYPES.put("light", Sensor.TYPE_LIGHT);
        SENSOR_TYPES.put("pressure", Sensor.TYPE_PRESSURE);
        SENSOR_TYPES.put("proximity", Sensor.TYPE_PROXIMITY);
        SENSOR_TYPES.put("gravity", Sensor.TYPE_GRAVITY);
        SENSOR_TYPES.put("linear_acceleration", Sensor.TYPE_LINEAR_ACCELERATION);
        SENSOR_TYPES.put("rotation_vector", Sensor.TYPE_ROTATION_VECTOR);
        SENSOR_TYPES.put("humidity", Sensor.TYPE_RELATIVE_HUMIDITY);
        SENSOR_TYPES.put("ambient_temperature", Sensor.TYPE_AMBIENT_TEMPERATURE);
        SENSOR_TYPES.put("step_counter", Sensor.TYPE_STEP_COUNTER);   // total steps since boot
        SENSOR_TYPES.put("step_detector", Sensor.TYPE_STEP_DETECTOR); // one event per step
        SENSOR_TYPES.put("heart_rate", Sensor.TYPE_HEART_RATE);       // needs BODY_SENSORS
    }

    private final Context ctx;
    private final Emitter emitter;
    private final Map<String, SensorEventListener> listeners = new HashMap<>();
    private static Boolean rootCache = null;

    Bridge(Context c, Emitter e) { ctx = c.getApplicationContext(); emitter = e; }

    // ---------------------------------------------------------------- root

    static synchronized boolean rootAvailable() {
        if (rootCache != null) return rootCache;
        try {
            JSONObject r = rootExec("id -u", 8000);
            rootCache = r.optInt("code", -1) == 0 && r.optString("out").trim().equals("0");
        } catch (Exception e) {
            rootCache = false;
        }
        return rootCache;
    }

    static JSONObject rootExec(String cmd, long timeoutMs) throws Exception {
        Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 1_000_000) out.append(line).append('\n');
            }
        }
        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) p.destroyForcibly();
        JSONObject o = new JSONObject();
        o.put("code", done ? p.exitValue() : -1);
        o.put("out", out.toString());
        return o;
    }

    // ---------------------------------------------------------------- dispatch

    Object call(String method, JSONArray a) throws Exception {
        switch (method) {
            case "root.available": return rootAvailable();
            case "root.exec": return rootExec(a.getString(0), a.optLong(1, 30000));

            case "battery.get": return battery();

            case "sensors.list": return sensorList();
            case "sensors.start": return startSensor(a.getString(0), a.optInt(1, 200));
            case "sensors.stop": stopSensor(a.getString(0)); return true;

            case "apps.list": return appList();
            case "apps.icon": return appIcon(a.getString(0), a.optInt(1, 96));

            case "fs.roots": return fsRoots();
            case "fs.list": return fsList(a.getString(0));
            case "fs.stat": return fsStat(a.getString(0));
            case "fs.read": return fsRead(a.getString(0), a.optLong(1, 0), a.optLong(2, -1));
            case "fs.write": return fsWrite(a.getString(0), a.getString(1), a.optBoolean(2, false));
            case "fs.mkdir": return new File(a.getString(0)).mkdirs();
            case "fs.delete": return deleteRec(new File(a.getString(0)));

            default: throw new IllegalArgumentException("unknown method " + method);
        }
    }

    // ---------------------------------------------------------------- battery

    JSONObject battery() throws Exception {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject o = new JSONObject();
        if (i == null) return o;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        o.put("level", scale > 0 ? level / (double) scale : -1);
        o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        o.put("plugged", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
        o.put("temperatureC", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
        o.put("voltageMv", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
        return o;
    }

    // ---------------------------------------------------------------- sensors

    private SensorManager sm() { return (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE); }

    private JSONArray sensorList() throws Exception {
        JSONArray arr = new JSONArray();
        SensorManager m = sm();
        for (Map.Entry<String, Integer> e : SENSOR_TYPES.entrySet()) {
            Sensor s = m.getDefaultSensor(e.getValue());
            JSONObject o = new JSONObject();
            o.put("name", e.getKey());
            o.put("available", s != null);
            if (s != null) o.put("hardware", s.getName());
            arr.put(o);
        }
        return arr;
    }

    private synchronized boolean startSensor(final String name, int periodMs) {
        Integer type = SENSOR_TYPES.get(name);
        if (type == null) return false;
        SensorManager m = sm();
        Sensor s = m.getDefaultSensor(type);
        if (s == null) return false;
        stopSensor(name);
        SensorEventListener l = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("sensor", name);
                    JSONArray v = new JSONArray();
                    for (float f : e.values) v.put((double) f);
                    d.put("values", v);
                    d.put("accuracy", e.accuracy);
                    d.put("timestampNs", e.timestamp);
                    emitter.emit("sensor", d);
                } catch (Exception ignored) { }
            }
            public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        listeners.put(name, l);
        return m.registerListener(l, s, Math.max(0, periodMs) * 1000);
    }

    private synchronized void stopSensor(String name) {
        SensorEventListener l = listeners.remove(name);
        if (l != null) sm().unregisterListener(l);
    }

    // ---------------------------------------------------------------- apps

    private JSONArray appList() throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        JSONArray arr = new JSONArray();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            JSONObject o = new JSONObject();
            o.put("package", ri.activityInfo.packageName);
            o.put("label", String.valueOf(ri.loadLabel(pm)));
            arr.put(o);
        }
        return arr;
    }

    private String appIcon(String pkg, int size) throws Exception {
        Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    // ---------------------------------------------------------------- files

    private JSONObject fsRoots() throws Exception {
        JSONObject o = new JSONObject();
        o.put("shared", Environment.getExternalStorageDirectory().getAbsolutePath());
        o.put("downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        o.put("dcim", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath());
        o.put("appData", ctx.getFilesDir().getAbsolutePath());
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) o.put("appExternal", ext.getAbsolutePath());
        return o;
    }

    private JSONObject describe(File f) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", f.getName());
        o.put("path", f.getAbsolutePath());
        o.put("isDir", f.isDirectory());
        o.put("size", f.length());
        o.put("modified", f.lastModified());
        return o;
    }

    private JSONArray fsList(String path) throws Exception {
        File[] files = new File(path).listFiles();
        JSONArray arr = new JSONArray();
        if (files == null) return arr;
        for (File f : files) arr.put(describe(f));
        return arr;
    }

    private Object fsStat(String path) throws Exception {
        File f = new File(path);
        return f.exists() ? describe(f) : JSONObject.NULL;
    }

    /** Returns base64. Pass offset/length for big files. */
    private String fsRead(String path, long offset, long length) throws Exception {
        File f = new File(path);
        long remaining = length < 0 ? f.length() - offset : length;
        if (remaining > 64L * 1024 * 1024) throw new Exception("read at most 64 MB at a time");
        try (FileInputStream in = new FileInputStream(f)) {
            if (offset > 0) in.skip(offset);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                bo.write(buf, 0, n);
                remaining -= n;
            }
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        }
    }

    private boolean fsWrite(String path, String base64, boolean append) throws Exception {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f, append)) {
            out.write(Base64.decode(base64, Base64.DEFAULT));
        }
        return true;
    }

    private boolean deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        return f.delete();
    }

    void shutdown() {
        for (String n : new java.util.ArrayList<>(listeners.keySet())) stopSensor(n);
    }
}
