# Foot Battery Monitor

An Android app for one configured Össur Proprio Foot. It reads the standard BLE battery
level, reports low battery, queries and changes the foot's confirmed standby state, and provides
foot-authoritative ankle alignment controls. Battery and standby checks are saved as one verified
snapshot. Ankle position is persisted separately in exact protocol millidegrees with its own
certainty and verification time. Live battery values can be newer than the complete snapshot; they
update the gauge and live-monitoring notification without changing the snapshot's `Last checked`
time.

## How the alert works

A live in-process connection subscribes to battery updates while the app is running.
Optional WorkManager polling briefly connects, obtains battery, confirmed standby, and ankle state,
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
4. Tap **Check now** for battery, standby, and ankle truth from the foot, or **Start** for live
   monitoring.
5. Once standby has been checked, use the standby switch in the app or notification.
6. With freshly confirmed standby OFF, fine-adjust by exactly `-0.1°` or `+0.1°`, save the confirmed
   angle into one of the four fixed footwear presets, recall a configured preset, or start
   **Auto Alignment**.

If a standby change is confirmed but its follow-up battery read fails, the confirmed standby state
is retained across app restarts. The app marks the battery as not yet verified after that change and
continues to label the preserved timestamp as the last complete check until a full check succeeds.

After Android accepts a standby write, a missing SET confirmation is resolved with a final typed
QUERY response; delayed SET responses cannot satisfy that query. If the final state still cannot be
verified, the app persists and displays an ambiguous standby state instead of retaining a potentially
wrong ON/OFF value. The standby switch and notification standby action stay disabled until **Check
now** restores a complete confirmed snapshot. Transient operation results remain visible for their
full display period even when live battery notifications refresh the battery line.

## Ankle alignment

The protocol's canonical unit is one millidegree (`0.001°`). The supported app range is
`-2.0°` through `+14.0°`. Fine adjustment computes the next target from the latest confirmed
foot value by exactly `±100` millidegrees; a step that would cross a bound is disabled rather than
clamped. There is no slider, arbitrary angle field, or direct picker.

Barefoot, Running, Dress, and Boots are a fixed set in that order. Presets begin unconfigured and
store only a user-saved, foot-confirmed exact angle. Selecting an unconfigured preset does not move
the foot. Auto Alignment is event-driven and always finishes with an ankle query; its observed
status bytes (`00`, `1E`, and `3C`) remain opaque, and Auto never overwrites a preset.

Every movement path—including notification actions—rechecks Bluetooth/session readiness and
freshly queries standby at execution time. Movement proceeds only when the foot reports standby
OFF. A successful Android write is not optimistic confirmation: the final ankle query is
authoritative. If movement may have occurred but that verification fails, the app records
`UNKNOWN_AFTER_COMMAND`, displays `Unknown`, and retains any older value only as explicitly labelled
`Last verified` history until a query restores certainty. Reconnects query device truth and never
replay cached angles or presets. After a process restart, a persisted angle is likewise historical
until a fresh typed query confirms it. A final foot-confirmed value within one millidegree of the request is
accepted as the device result; the exact queried value—not the request—is what is stored and shown.

Quick Adjust is intentionally hidden until verified shoe-height-to-angle calibration is
configured. No inch conversion is guessed, and notification controls expose no inch adjustment.

The collapsed notification is status-only. In a normal safe state, the expanded notification shows
the four fixed presets and native actions in the order **Check / Standby / Auto**. Actions are
revalidated when tapped, so an old rendered notification cannot bypass current standby or connection
state. Custom notification content is transparent inside Android's native notification surface and
uses notification-aware text appearances for readability in light and dark system themes.

The physical-validation build identifies itself as `1.2.0-beta1` (`versionCode 2`); the version is
shown unobtrusively at the bottom of Settings.

## Make background alerts reliable

Android (especially Samsung/Xiaomi/OnePlus) may kill background Bluetooth to save power,
which would stop the alerts. To prevent that:
- Settings → Apps → Foot Battery → Battery → **Unrestricted** (or "Don't optimize").

Scheduled polling is restored by WorkManager. Live monitoring ends with the app process.

## Safety

All proprietary writes go only to AA01, after AA01 and AA02 notification setup, through the existing
single `FootGattSession` and process-wide operation coordinator. The complete allowlist is:

- query standby, set standby ON, and set standby OFF;
- query ankle;
- set an absolute ankle target encoded as signed little-endian `Int32` millidegrees; and
- start Auto Alignment with `B2 B0 04 00`.

No commands are written to AA02 or any other proprietary characteristic. Arbitrary proprietary
writes and out-of-range firmware experiments are unsupported. `B2 B0 04 02` is deliberately not
sent because its semantics are unknown.

Software tests, lint, builds, and emulator review do not validate physical movement. Initial device
testing must be seated, safely supported, and follow the hardware checklist in `PROJECT_NOTES.md`;
never test movement while walking or driving.

## Version notes

If the GitHub build complains about a missing SDK platform, the runner image usually has
android-34 already; the `setup-android` step accepts licenses.
Pinned: AGP 8.5.0, Gradle 8.7, Kotlin 1.9.24, compileSdk 34, Compose BOM 2024.06.00.
