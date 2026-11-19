# WebOS Launcher

A home-screen launcher that is nothing but a full-screen Firefox engine (GeckoView) showing your web OS.
No status bar, no nav bar, no keyboard, no native pickers. Hardware keys, the power button, sensors,
files and root are exposed to your page through `window.Native`.

## Build
Push this folder to a **private** GitHub repo (keep `.github`). Actions → *Build Android APK* → run.
Download `WebOSLauncher-apk`. (The signing key is in the repo so every build installs as an update.)
The build resolves GeckoView (Firefox 139 by default) from Mozilla's Maven, like your old project.

## First run
1. Install the APK, open it. It asks you to enter your URL (it opens the panel by itself the first time).
2. A wizard asks for every permission. With root it grants them silently.
3. Make it the default home app when asked.

## Getting out: three-finger double tap
Double tap with 3 fingers anywhere. A native panel opens (this is the only place a keyboard exists):
URL box + Go, Reload, **Android settings**, default home app, grant permissions, pause lock-down,
**Release phone** (removes Device Owner and every restriction).
Pressing Home inside Android Settings returns to the launcher and re-locks.

## Full lock-down (Device Owner)
Without Device Owner it never pins the screen: it is just an immersive home launcher (bars hide, but an edge swipe can still reveal them briefly).
For the real lock-down, make the app Device Owner. Needs a phone with **no accounts signed in**:

    adb shell dpm set-device-owner com.webos.launcher/.AdminReceiver

With root the app tries this by itself during the permission wizard. As Device Owner you get:
no status bar, no notification shade, no home/recents, no power menu, no lock screen, permanent default home,
safe-mode blocked, screen kept on while charging, all runtime permissions auto-granted.

## JavaScript API (`window.Native`)
Only pages on the **same origin as the saved URL** are answered. All calls return Promises.
Events: `Native.on("power", fn)` or `window.addEventListener("native:power", e => e.detail)`.

    Native.on("key", k => console.log(k));       // {keyCode, key:"VOLUME_UP", action:"down"|"up", repeat}
    Native.on("power", p => console.log(p));     // {state:"screen_off"|"screen_on"}
    Native.on("sensor", s => console.log(s));    // {sensor, values:[...], accuracy, timestampNs}
    Native.on("battery", b => ...);  Native.on("headset", h => ...);  Native.on("notification", n => ...);

    Native.keys.setConsume(true)          // volume/back/menu/media keys go to you instead of Android (default on)
    Native.power.setMode("intercept")     // default: power press => event + screen stays on. "normal" = Android behaviour
    Native.power.sleep()  .wake()  .reboot()  .shutdown()       // sleep needs Device Owner or root
    Native.audio.setVolume("music", 0.5)  .getVolume("music")   // streams: music ring alarm notification voice system
    Native.display.setBrightness(0.8)  .keepAwake(true)  .setOrientation("portrait"|"landscape"|"auto")
    Native.sensors.list()  .start("step_counter", 1000)  .stop("step_counter")
        // accelerometer gyroscope magnetometer light pressure proximity gravity linear_acceleration rotation_vector
        // humidity ambient_temperature step_counter step_detector heart_rate
    Native.fs.roots() .list(path) .stat(path) .read(path, offset, length) .write(path, base64, append) .mkdir(path) .delete(path)
    Native.apps.list()  .icon(pkg, 96)  .launch(pkg)
    Native.app.info()  .setUrl(url)  .reload()  .openSettings()  .pauseKiosk(true)
    Native.perms.status()  .requestAll()
    Native.root.available()  .exec("any shell command")          // needs root
    Native.clipboard.read() .write(text)   Native.device.vibrate(30 | [0,50,30,80])   Native.battery.get()
    Native.call("namespace.method", ...args)                      // generic form

## What cannot be done (Android limits, not this app)
- **Power button**: no app can swallow it, Android handles it before apps. The launcher hears the screen turn off,
  tells your page, and wakes the screen again (brief flicker). Long-press power menu is blocked in Device Owner mode.
- **Volume keys** are consumed only while the launcher is in front (always, in kiosk).
- **Keyboard**: the web view never connects to the on-screen keyboard. Build your own in the page. Hardware keyboards still type.
- Health Connect / Google Fit data is not bridged (step counting uses the hardware step sensor above).

## Using it as the only launcher (your root plan)
Remove or disable the stock launcher, install this, make it Device Owner (or let the wizard do it with root).
Moving the APK to `/system/priv-app/` is possible later for even more permissions but needs a privapp-permissions allowlist,
so do it only once everything else works.
