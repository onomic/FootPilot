# Foot Battery Monitor

An Android app for one configured Össur Proprio Foot. It reads the standard BLE battery
level, reports low battery, and can query and change the foot's confirmed standby state.
Battery and standby checks are saved as one verified snapshot. Live battery values can be newer
than that snapshot; they update the gauge and live-monitoring notification without changing the
snapshot's `Last checked` time.

## How the alert works

A live in-process connection subscribes to battery updates while the app is running.
Optional WorkManager polling briefly connects, obtains battery plus confirmed standby,
then disconnects and removes the bond. Low-battery alerts re-arm after charging above the
configured threshold, so they fire once per discharge rather than repeatedly. Every valid battery
reading drives this safety behavior even if the accompanying standby check fails; an incomplete
read does not replace the last complete snapshot.

The alert threshold, pairing PIN, polling interval, and polling toggle are available in Settings.

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

1. Close any other app connected to the foot — it allows only one connection.
2. Open the app and grant Bluetooth + Notifications permissions.
3. Save the pairing PIN in Settings if the foot requires one.
4. Tap **Check now** for a complete battery/standby snapshot, or **Start** for live monitoring.
5. Once standby has been checked, use the standby switch in the app or notification.

If a standby change is confirmed but its follow-up battery read fails, the confirmed standby state
is retained across app restarts. The app marks the battery as not yet verified after that change and
continues to label the preserved timestamp as the last complete check until a full check succeeds.

## Make background alerts reliable

Android (especially Samsung/Xiaomi/OnePlus) may kill background Bluetooth to save power,
which would stop the alerts. To prevent that:
- Settings → Apps → Foot Battery → Battery → **Unrestricted** (or "Don't optimize").

Scheduled polling is restored by WorkManager. Live monitoring ends with the app process.

## Safety

The only proprietary writes are the three confirmed Össur AA01 standby packets: query,
standby on, and standby off. They use write-with-response and require notification
confirmation plus a final query. No commands are sent to AA02 or to any other proprietary
characteristic.

## Version notes

If the GitHub build complains about a missing SDK platform, the runner image usually has
android-34 already; the `setup-android` step accepts licenses.
Pinned: AGP 8.5.0, Gradle 8.7, Kotlin 1.9.24, compileSdk 34, Compose BOM 2024.06.00.
