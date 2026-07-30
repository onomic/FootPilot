# Foot Battery — Project Notes & Handoff

**Purpose of this document:** complete context for the FootBattery Android app so work can
continue in a fresh session without losing any decisions or state. Last updated at the point
where the app is fully working except for one unavoidable Android UI behavior (notification
shade collapse).

---

## 1. What this app is

A native **Android** app that monitors the **battery level of an Össur microprocessor
prosthetic foot** over Bluetooth Low Energy (BLE). The official Össur Logic app is
**iOS-only**, so this fills the Android gap. The user is an amputee — it is their own leg —
and has full authority over design decisions, including ones that touch the device's bond state.

**Primary goals (all met):**
- Show current battery % with a clean dark UI.
- Notify when battery drops below a configurable threshold (default 25%).
- Optional background polling on a schedule.
- On-demand "Check now" (from the app and from the notification).

---

## 2. The device (critical facts)

- **Display name:** `HF206250`
- **MAC address:** `CA:AD:73:A4:52:80`
- **Battery data:** exposes the **standard BLE Battery Service `0x180F`** with **Battery Level
  characteristic `0x2A19`**, properties **NOTIFY + READ**. This was the best-case outcome — no
  reverse-engineering of any proprietary protocol was needed to read battery.
- **Full GATT table observed (via nRF Connect early on):**
  - Generic Access `0x1800`
  - Generic Attribute `0x1801`
  - Battery Service `0x180F` → Battery Level `0x2A19` (NOTIFY, READ) + CCCD `0x2902`
  - Device Information `0x180A`
  - **Unknown / proprietary service** `1610aa00-0111-0899-2503-732…4219` — **DO NOT WRITE TO
    THIS.** This is where settings that could affect the foot's behavior/gait likely live. The
    app is strictly **read-only** by design.
- **Pairing:** the foot uses a **6-digit numeric PIN** to bond. (Early on we mistakenly thought
  it was "Just Works"/no-PIN because it happened to already be bonded; it actually prompts for a
  PIN on a fresh bond.)
- **Behavior:** the foot **"peeps" (audible chirp) when the BLE link is fully dropped.** This is
  the user's ground-truth signal that a disconnect actually worked.
- **Single connection:** the foot allows **only one BLE connection at a time.** Anything else
  holding the link (e.g. another app) will block ours and prevent the peep.
- Initially reported **NOT BONDED** in nRF Connect.

---

## 3. User environment

- **OS:** Windows. Project lives at `C:\Users\justi\Downloads\FootBattery\` (has been re-downloaded
  into nested/renamed folders over time, e.g. `FootBattery_2\FootBattery\`).
- **IDE:** Android Studio.
- **Phone:** Samsung (One UI) — relevant because One UI applies **circular masking to notification
  icons** and supports **themed (monochrome) icons**. Also aggressive background-process management.
- Also owns a **Garmin EPIX PRO 42mm** watch (appears in the BT menu; unrelated).
- **No longer uses the iPhone Össur Logic app** — so changes to bond state are acceptable to the
  user (this unblocked the `removeBond()` approach to disconnect).
- **Does not have nRF Connect installed anymore** (ruled out as the thing holding the connection).
- Self-hosts web content over HTTPS (relevant only to the abandoned Web Bluetooth option below).

---

## 4. Tech stack & build config

- **Language/UI:** Kotlin + Jetpack Compose + Material 3
- **Package:** `com.example.footbattery`
- **SDKs:** `compileSdk 34`, `targetSdk 34`, `minSdk 26`
- **Plugins/versions:** AGP **8.5.0**, Kotlin **1.9.24**, Gradle **8.7**, Compose BOM **2024.06.00**,
  Compose compiler ext **1.5.14**
- **Key deps:** `androidx.work:work-runtime-ktx:2.9.0` (background polling),
  `kotlinx-coroutines-android:1.8.1`, `androidx.core:core-ktx:1.13.1`,
  `androidx.activity:activity-compose:1.9.0`, lifecycle-runtime-ktx, compose ui/foundation/material3.

### How to build (two paths)
1. **GitHub Actions (no local tooling):** push the folder to a GitHub repo. `.github/workflows/build.yml`
   runs on push, produces a downloadable `foot-battery-apk` artifact (`app-debug.apk`) in the Actions tab.
2. **Android Studio:** Open the folder, let Gradle sync, Run.

### Build/install ritual (IMPORTANT — avoids recurring pitfalls)
- When swapping in new files, **close Android Studio first**, replace the folder on disk, then reopen.
  Editing on disk while Studio holds a file open caused a **stale editor buffer** that silently
  reverted changes (this bit us once and wasted a cycle).
- For **icon changes:** **Build → Clean Project**, then **uninstall the app from the phone**, then
  reinstall. Android + the launcher cache the old icon otherwise.

---

## 5. Current file inventory

All Kotlin under `app/src/main/java/com/example/footbattery/`:

| File | Role | Notes |
|---|---|---|
| `MainActivity.kt` | Compose UI: main gauge screen + settings screen; triggers start/stop/check | Holds settings state, permission requests |
| `LiveConnection.kt` | In-process **live** BLE connection for continuous monitoring | Replaced an earlier crashing foreground-service approach |
| `BleReader.kt` | **One-shot** connect → read → disconnect (+unbond when monitoring off) | Used by Check now (app + notification) and background poll |
| `CheckNowService.kt` | **Foreground service** that runs the notification "Check now" | Replaced a glitchy BroadcastReceiver approach |
| `BatteryReadWorker.kt` | WorkManager periodic background poll (calls `BleReader`) | Skips if live monitoring is active or polling off |
| `PollScheduler.kt` | Enables/disables/updates the periodic work | Enforces WorkManager's 15-min minimum |
| `Alerts.kt` | All notifications: channels, ongoing/poll-status/low-battery; `recordReading`; time formatters | Central notification logic |
| `Prefs.kt` | Typed SharedPreferences wrapper | threshold, polling, intervalMin, monitoring, armed, pairingCode, lastChecked |
| `BondHelper.kt` | Reflective `removeBond()` force-unbond | Hidden API; how disconnect truly drops this foot |
| `PairingReceiver.kt` | Auto-submits saved PIN on `ACTION_PAIRING_REQUEST` | Also auto-confirms passkey variant |
| `BleRegistry.kt` | Tracks open GATT clients: `add/remove/disconnectAll/closeAll/count` | Ensures clients get closed, not leaked |
| `Uuids.kt` | Centralized BLE UUIDs (`SERVICE`, `LEVEL`, `CCCD`) | Created to avoid a name-collision bug (see §7.1) |
| `BatteryService.kt` | **DEAD CODE** — old foreground-service monitor, no longer started anywhere | Kept in tree but unused; safe to delete later. **Still holds the `TARGET_ADDRESS`, `TARGET_NAME`, and `LOW_BATTERY_THRESHOLD` constants that other files reference**, so don't delete without moving those. |
| `BootReceiver.kt` | Currently a **no-op** | Live monitoring is in-process (ends with app); WorkManager restores polling itself |

**Resources** under `app/src/main/res/`:
- `drawable/ic_battery.xml` — **white foot silhouette**, used as the status-bar (small) notification icon.
  Kept as a silhouette on purpose; Android tints small icons, so a full-color PNG would render as a
  white blob.
- `drawable/ic_launcher_background.xml` — from the icon pack (black background).
- `drawable-xxxhdpi/ic_launcher_monochrome.png` — Android 13 themed/monochrome layer.
- `mipmap-*/ic_launcher*.png` — the **v4 "notification-safe" icon pack** (glowing green prosthetic
  foot on black), all densities, foreground + round foreground.
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` — adaptive icon defs incl. `<monochrome>`.
- `values/strings.xml` (`app_name = "Foot Battery"`), `values/themes.xml` (dark `AppTheme`).

**Manifest** highlights:
- Permissions: `BLUETOOTH_SCAN` (neverForLocation), `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`
  (maxSdk 30), `BLUETOOTH`/`BLUETOOTH_ADMIN` (maxSdk 30), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `RECEIVE_BOOT_COMPLETED`, `BLUETOOTH_PRIVILEGED`.
- `<uses-feature ... bluetooth_le required="true">`.
- Components: `MainActivity`; `BatteryService` (FGS type `connectedDevice`); `CheckNowService`
  (FGS type `connectedDevice`); `BootReceiver` (BOOT_COMPLETED); `PairingReceiver`
  (`PAIRING_REQUEST`, intent-filter priority 999).

**Build files:** root `build.gradle.kts`, `app/build.gradle.kts`, `settings.gradle.kts`,
`gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.7), `.github/workflows/build.yml`.

### Key constants (where to change behavior)
- `BatteryService.TARGET_ADDRESS = "CA:AD:73:A4:52:80"` — the only device the app talks to.
- `BatteryService.TARGET_NAME = "HF206250"`.
- `BatteryService.LOW_BATTERY_THRESHOLD = 25` — **default** only; the live, user-adjustable value is
  stored in `Prefs` and edited in Settings.

---

## 6. How the app currently behaves (functional spec as built)

### Main screen
- Dark UI with a **circular battery gauge** that fills with the level and changes color:
  teal (>35%), amber (≤35%), red (≤15%); shows "—" when unknown.
- Device name `HF206250` and "Alerts below X%" shown under the gauge.
- **LIVE/IDLE** status pill (pulsing dot when live).
- Buttons: **Check now** (outlined, accent), **Start** (monitor), **Disconnect**.
- ⚙ gear (top-right) → Settings.

### Settings screen (scrollable)
- **Alert threshold** — stepper, **5–50%**, steps of 5.
- **Pairing code** — numeric field, digits only, capped at 8 (covers 4/6-digit). Saved persistently.
- **Background polling** — on/off toggle.
- **Check every** — presets **15m / 30m / 1h / 2h**, default **1h**, greyed until polling is on.
- Tip text about setting battery usage to Unrestricted.

### Connection model
- **Live monitoring (Start):** runs **in the app process** via `LiveConnection` (NOT a foreground
  service — see §7.2). Holds the link, subscribes to battery notifications, shows an ongoing
  notification, ends when the app is closed.
- **Disconnect:** disconnect all tracked clients → close all → **`BondHelper.forceUnbond()`**. This
  is what makes the foot actually drop **and peep**. Confirmed working across repeated cycles.
- **Check now (monitoring OFF):** `BleReader` does a one-shot connect → read → **immediately
  unbond** (no artificial delay). Fast and seamless; foot drops/peeps after. Works from both the
  in-app button and the notification.
- **Check now (monitoring ON):** just re-reads the existing live link; does NOT unbond/disconnect.
- **Background poll:** WorkManager wakes ~per interval, connects, reads, unbonds (since monitoring is
  off during a poll), disconnects. "Model 2" wake/check/sleep — stays disconnected between checks.

### Notifications (all titles are number-first so the % is never truncated when collapsed)
- **Ongoing (while monitoring):** title `Battery X%`, body `Connected`.
- **Poll-status (while background polling is on, monitoring off):** title `Battery X%` (or `Battery —`
  before first check), body `Last checked: <clock time>` (e.g. "10:08 PM"; adds date if not today),
  with a **Check now** action button and tap-to-open. Appears the moment polling is toggled on;
  disappears when toggled off.
- **Low battery alert:** title `Low battery: X%`, body `Foot is low — time to charge.` Fires once when
  crossing below threshold; **re-arms** after charging back above it (so it doesn't nag). High
  priority, vibrates.
- **"Last checked" uses absolute clock time, not relative** — because a notification can't re-run code
  to age a "5 min ago" string; it only redraws when the app touches it. Clock time is correct forever.

### Pairing PIN auto-submit
- `PairingReceiver` listens for the system pairing request, and if a code is saved and the request is
  for our foot, it submits the PIN via `setPin(code.toByteArray(UTF-8))` and `abortBroadcast()` to
  suppress the dialog. For passkey/confirm variants it calls `setPairingConfirmation(true)` via
  reflection. **Confirmed working** — entering the 6-digit PIN once in Settings stops the repeated
  prompts that otherwise appear because we unbond on disconnect/check.

---

## 7. Major bugs fixed along the way (so we don't repeat them)

### 7.1 "Type mismatch: inferred type is String but UUID! was expected"
- **Cause:** a constant named `BATTERY_SERVICE` was declared inside a class extending `Service`
  (→ `Context`). `Context` already defines `BATTERY_SERVICE = "batterymanager"` (a String), and the
  inherited member **shadowed** the file-level `UUID`. So `getService(BATTERY_SERVICE)` saw the String.
- **Tell:** the `UUID` constant showed as "never used"; Ctrl+B on the usage jumped to
  `android.content.Context`.
- **Fix:** renamed constants and ultimately centralized all UUIDs in `Uuids.kt` (`Uuids.SERVICE`, etc.).

### 7.2 Start crashed / app closed immediately
- **Cause:** foreground-service `startForeground()` path was throwing (Android 12+ timing/restrictions),
  killing the app before the notification posted ("no notification at all" was the tell). The crash also
  left a BLE connection open, which looked like a separate disconnect bug.
- **Fix:** moved live monitoring **into the app process** (`LiveConnection`), matching the Check path
  that already worked reliably.

### 7.3 Disconnect "worked once, then never"
- **Cause:** code was **closing and recreating** the GATT client every cycle; the reused client never
  re-registered cleanly.
- **Fix path:** first tried a single-client-toggle; ultimately the reliable drop on this foot required
  **`removeBond()` via reflection** (`BondHelper`). User explicitly requested this and accepted the
  medical-device tradeoff. Disconnect now = disconnect → close all → forceUnbond.

### 7.4 `convertPinToBytes` unresolved
- **Cause:** that method is a hidden internal helper, not public API.
- **Fix:** `code.toByteArray(Charsets.UTF_8)` (correct for a numeric/text PIN like 6 digits).

### 7.5 Platform declaration clash (`setThreshold`/`setPolling`)
- **Cause:** methods named `setX` collided with Kotlin's auto-generated property setters for `var x`.
- **Fix:** renamed to `applyThreshold` / `applyPolling` / `applyInterval`.

### 7.6 Phantom 0% reading
- **Cause:** `onCharacteristicRead` took `value[0]` even when `status != GATT_SUCCESS`; a failed read
  often carries a `0`, so the gauge/notification showed 0% when the foot was actually 100%.
- **Fix:** only accept the value when `status == BluetoothGatt.GATT_SUCCESS`; otherwise return null and
  never overwrite the last good reading. No code path writes 0 on failure anymore.

### 7.7 Notification "Check now" stuck on "Checking…", slow, never updated
- **Cause:** it ran in a **BroadcastReceiver + goAsync()**, which runs at throttled priority; BLE
  callbacks were starved and the final `notify()` often didn't land. The same `BleReader` code is
  instant from the in-app (foreground Activity) context.
- **Fix:** moved the notification check into a brief **foreground service** (`CheckNowService`), which
  runs at normal priority. Also added `BleReader.isBusy()` guard to prevent overlapping/stacked checks.

### 7.8 Unbond accidentally removed from checks, then restored
- At one point I removed `forceUnbond` from the check path entirely. User clarified the **in-app check
  should unbond when monitoring is off**. Restored: `forceUnbond` in `BleReader.finishAndClose()` gated
  on `!BatteryRepo.running.value`, and **removed the old 1.5s pre-close delay** so there is no lag.

---

## 8. Current status (as of last interaction)

**User quote:** *"Other than the notification shade going up, it all works perfectly."*

Working and confirmed by the user:
- Start → connect → Disconnect drops the foot and it **peeps**, reliably across repeated cycles.
- In-app **Check now** is fast/seamless and **unbonds after** when monitoring is off.
- Notification **Check now** updates reliably and quickly (via the foreground service) and unbonds after.
- **Phantom 0%** is gone.
- **PIN auto-submit** works (no more repeated pairing prompts).
- **Icon** (v4 prosthetic-foot pack) looks correct; status-bar icon is the white foot silhouette.
- Notifications show number-first battery and **clock-time** "Last checked".
- Background polling configured (15m/30m/1h/2h).

---

## 9. The one open issue

**Tapping the notification's "Check now" button collapses the notification shade.**
- This is **standard, unavoidable Android behavior** for any notification **action button** — an app
  cannot keep the shade open when an action is tapped. (Confirmed this is framework behavior, not a bug
  in our code.)
- The check itself works fine; only the shade animation is the annoyance.

**Options presented (user has not chosen; said they'd live with it for now):**
1. **Keep as-is** — background button via `CheckNowService`; shade closes but app never opens and the
   notification updates in place. *(Current behavior.)*
2. **Make the whole notification tap-to-open** — tapping anywhere opens the app and runs the fast
   in-app check. Shade still closes, but it reads as intentional (the app comes forward). Trade-off:
   it opens the app and removes the standalone button.

There is **no arrangement that both keeps the shade open and checks in place** — that combination isn't
available in Android's notification framework.

---

## 10. Next steps / backlog

- **Decide the notification-tap behavior** (option 1 vs 2 in §9). Default recommendation: live with
  option 1 unless the collapse proves annoying in daily use.
- **Optional cleanup:** delete dead `BatteryService.kt` — but first move `TARGET_ADDRESS`, `TARGET_NAME`,
  and `LOW_BATTERY_THRESHOLD` somewhere live (e.g. into `Prefs`/a `Config` object/`Uuids`-style file),
  since other files reference those constants.
- **Reliability tip for the user:** set the app's battery usage to **Unrestricted**
  (Settings → Apps → Foot Battery → Battery) so Samsung doesn't kill background polling.
- **Known platform constraints to keep in mind:**
  - WorkManager minimum periodic interval is **15 minutes**; "1 hour" is approximate under Doze.
  - `removeBond()` is a **hidden API** — works on this user's Samsung; could be a no-op on other OEM ROMs
    (not a concern for single-user use).
  - Foreground service started from a notification action is allowed because it's a user interaction.

---

## 11. Abandoned / not in use

- **Web Bluetooth version (`index.html`)** — an early no-install option that ran in Chrome on Android.
  Superseded by the native app. Not part of the shipping app.
- **`BatteryService.kt`** — superseded by `LiveConnection` + `CheckNowService`; remains only as dead code
  holding shared constants (see §10).

---

## 12. Guardrails / philosophy to preserve

- **Read-only, always.** The app only ever reads the standard Battery Level characteristic. It must
  **never write to the proprietary "Unknown Service"** — that is where gait/behavior settings likely
  live, and this is a medical device on the user's body.
- The **peep on disconnect** is the user's trusted signal that the link truly dropped; preserve it on the
  explicit Disconnect action.
- The **in-app check is the gold standard** for speed/seamlessness; any background/notification path
  should match its behavior (fast connect → read → unbond-when-monitoring-off).
