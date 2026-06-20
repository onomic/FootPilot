# Foot Battery Monitor

An Android app that connects to an Össur Proprio Foot over Bluetooth LE, shows its
battery level, and **sends a notification when the battery drops below 25%**.
The official Össur Logic app is iOS-only; this fills the Android gap. Works with any
BLE device that exposes the standard Battery Service (0x180F).

## How the alert works

A foreground service holds the Bluetooth connection (even when the app is closed),
subscribes to battery-level updates, and posts a high-priority notification the first
time the level falls below the threshold. It re-arms once you charge back above it, so
you get one alert per discharge, not a stream of them. There's also a quiet ongoing
notification showing the current % with a Stop button.

To change the threshold, edit `LOW_BATTERY_THRESHOLD` in `BatteryService.kt`.

## Getting a runnable app

You can't get an APK by just opening these files — Android has to compile them.
Pick whichever path fits what you have.

### Option A — GitHub Actions (no dev tools needed)

1. Create a new GitHub repo and push this whole folder to it.
2. Open the **Actions** tab. The "Build APK" workflow runs automatically on push.
3. When it finishes, open the run and download the **foot-battery-apk** artifact.
   Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and install it (allow "install from unknown sources").

### Option B — Android Studio

1. Open this folder, let Gradle sync, plug in your phone with USB debugging on,
   press **Run** (▶).

## Using it

1. Close the **Össur Logic app on your iPhone** — the foot allows only one connection.
2. Open the app, tap **Scan**, grant the Bluetooth + Notifications permissions, Scan again.
3. Tap your foot (e.g. HF206250). This remembers it.
4. Tap **Start monitoring**. The ongoing notification appears and tracks battery.
   Closing the app is fine — the service keeps running.

## Make background alerts reliable

Android (especially Samsung/Xiaomi/OnePlus) may kill background Bluetooth to save power,
which would stop the alerts. To prevent that:
- Settings → Apps → Foot Battery → Battery → **Unrestricted** (or "Don't optimize").

The app also tries to resume monitoring after a reboot, though some OS versions block
that — if so, just open the app once.

## Safety

Read-only by design. Don't write to the foot's proprietary "Unknown Service" — that's
where settings affecting your gait live. This app only reads the standard battery value.

## Version notes

If the GitHub build complains about a missing SDK platform, the runner image usually has
android-34 already; the `setup-android` step accepts licenses.
Pinned: AGP 8.5.0, Gradle 8.7, Kotlin 1.9.24, compileSdk 34, Compose BOM 2024.06.00.
