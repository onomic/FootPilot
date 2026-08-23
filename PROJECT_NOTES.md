# FootPilot — Project Notes & Handoff

## Captured Chair Exit and Relax mode protocol

The following AA01 packets were isolated in dedicated Össur Logic captures and are the complete
authorized protocol for these two Settings controls:

```text
Relax
Query   B0 B0 33 13 81 20
ON      B1 B0 33 13 81 00 01
OFF     B1 B0 33 13 81 00 00
Response prefix B0/B1 B0 33 13 81 A0 STATE

Chair Exit
Query   B0 B0 34 13 81 20
ON      B1 B0 34 13 81 00 01
OFF     B1 B0 34 13 81 00 00
Response prefix B0/B1 B0 34 13 81 A0 STATE

STATE 00 = OFF
STATE 01 = ON
```

The mutation authority model is fresh QUERY, no SET when already at the absolute target, otherwise
absolute SET followed by a final QUERY. The final query is authoritative. A completed Android write
with a missing SET response proceeds to final verification; if that also fails, UI truth is
ambiguous rather than optimistic. Response bytes after `STATE` remain opaque. Both modes persist on
the foot, but FootPilot never treats local state as foot truth.

Opening Settings is the primary synchronization point. One coordinator-owned live READY session is
reused when available; otherwise one safe temporary session queries Chair Exit and Relax
sequentially and releases through `BleTargetReleaseBarrier`. Mode queries are not added to Check
now, notification Check, or scheduled polling. Explicit absolute mutations have one generation-safe
automatic retry after the shared 15-second BLE delay; the retry begins with a fresh query and is
cancelled by newer intent or selected-foot change. Refresh failure never auto-retries.

`BleRetryPolicy` is now the single code-level source for the deliberate automatic retry delay,
currently `15_000 ms` (15 seconds). Stay connected retries persist while requested; Chair Exit,
Relax, and Standby controls get at most one safe retry. Standby retry is generation-safe and keeps
the resolved absolute ON/OFF target, so a notification toggle is never blindly toggled a second
time after a SET may have reached the foot. A verified Standby result with only a trailing battery
read failure is complete for control purposes and does not retry. App Check now, notification Check,
scheduled polling, and Foot Modes Settings refresh remain one-shot. Fine Adjust, preset movement,
and Auto never blindly resend or restart.

Foot Modes refresh admission now reserves its single job slot before calling `beginRefresh()`.
Rapid Settings re-entry is therefore a complete no-op while the existing two-query refresh is
running and cannot reset an already-completed Chair or Relax row to `Checking...`.
`FootModeStateStore.beginRefresh()` also rejects `CHECKING` as defense in depth.

Battery accents use one fixed semantic classifier: `UNKNOWN`, `NORMAL` (36–100), `WARNING`
(16–35), and `CRITICAL` (0–15), independent of the configurable alert threshold. The collapsed,
expanded, and Auto notification battery numbers use the same resolved value and semantic band.
Normal retains the notification green; warning is `#F5B94A`, critical is `#F0604D`, and unknown is
neutral. The `Battery` label is not recolored.

> **Live retry and historical-angle correction:** The first user-requested **Stay connected**
> attempt remains immediate. A genuine persistent connection failure or unexpected loss after
> READY now enters one fixed 15-second, cancellation-safe retry wait before the same owner coroutine
> starts another attempt. The main screen observes dedicated process state and shows
> `Retrying in 15s...` through `Retrying in 1s...` in its single bottom status slot, below active BLE
> operation priority and above verification, standby, and general messages. Turning **Stay
> connected** off cancels the wait immediately; READY, IDLE, a new attempt, target reset, generation
> change, and coroutine cancellation all clear the countdown. The ongoing notification remains
> stable during the wait and is not updated once per second.
>
> A valid historical ankle value now remains visible as the large Fine Adjust degree value in muted
> styling when certainty is `UNKNOWN` or `UNKNOWN_AFTER_COMMAND`; `Unknown` is reserved for a state
> with no valid `lastVerifiedMd`. Concise `Last verified` copy and accessibility semantics identify
> the value as historical. This is presentation only: `confirmedMd` is unchanged, historical values
> never enable Fine Adjust, presets, Save, or Auto, and never become movement or active-preset truth.

> **BLE session handoff correction:** Temporary checks and controls now retain the existing
> process-wide BLE coordinator through an explicit, target-specific release barrier before a new
> **Stay connected** session may start. The established force-unbond policy remains in temporary
> cleanup, but disconnect and bond release are observed from Android lifecycle events and current
> state with bounded uncertain outcomes instead of being assumed complete when API calls return.
> The live connect coroutine independently checks the latest release generation immediately before
> opening its GATT. An uncertain generation receives at most one target-specific hard recovery, so
> rapid temporary-to-live handoff can recover without manually cycling **Stay connected**, while
> ordinary later live disconnects retain the existing lightweight retry loop. The older 600 ms delay
> in the explicit live Stop path is unchanged and is not used as handoff synchronization.
>
> The main-screen card rhythm also now has a dedicated adaptive **FOOT CONTROLS** to **ANKLE
> ALIGNMENT** gap (12/14/16 dp); the existing ankle-card to status-slot gap is unchanged.

> **Standby-safe connection correction:** **Stay connected** is valid with confirmed Standby ON or
> OFF. Standby ON blocks movement, not the BLE connection, and is never turned off automatically to
> connect. A full snapshot queries Standby first and sends an ankle query only after fresh Standby
> OFF; confirmed ON or unavailable Standby truth skips ankle traffic and still proceeds to the
> standard battery read while the session is usable. Battery/Standby snapshot completeness remains
> independent from ankle certainty. An intentional ankle skip demotes any older confirmed angle to
> labelled `Last verified` history and is not reported as an ankle or connection failure.

> **FootPilot controls UX update:** **Check now** is the one full-width primary main-screen action.
> The former Start/Disconnect bottom action is now a state-derived **Stay connected** switch in a
> compact **FOOT CONTROLS** card beside the existing confirmation-driven Standby switch. Turning it
> on reuses `LiveConnection.start()`; turning it off still uses the guarded activity stop path and
> its standby warning before `LiveConnection.stop()`. The switch stays available as an escape from
> retry or Bluetooth loss when no protected operation is active, and shows off/disabled while the
> existing connection state is `DISCONNECTING`.
>
> The passive header now reports `CONNECTED`, `CONNECTING`, `DISCONNECTING`, `POLLING`, or `IDLE`;
> connected/connecting retain the established pulse. Snapshot-derived checked-time wording moved
> below the alert threshold, including incomplete and never-checked states. The later captured-mode
> update replaces the original **FOOT MODES** placeholders with foot-authoritative Chair Exit and
> Relax switches. Background polling and notification actions remain separate and unchanged.

> **FootPilot Android identity update:** The repository is `onomic/FootPilot`, the visible product
> name is FootPilot, and the application ID, namespace, Kotlin packages, and notification action
> prefix are now `com.onomic.footpilot`. This is intentionally a new Android application identity,
> not an in-place upgrade of builds using the former application ID. New installs start with clean
> app-private state. The build remains `1.3.0-beta1` (`versionCode 5`).
> Settings begins with one compact Foot Setup card containing the existing pairing code and an
> exact-name **Find foot** action. Fresh installs and beta2 upgrades have no selected target; there
> is no hard-coded or automatic migration of an old device name/address and no Bluetooth results
> list. A matching advertisement is saved only after the existing `FootGattSession` discovers the
> Battery Service/Level plus Össur service/AA01/AA02 profile. Verification performs no movement or
> proprietary command and participates in the process-wide BLE coordinator.
>
> Each normal session captures one immutable persisted `SelectedFoot`. Live reconnects retain that
> target for their generation, temporary operations resolve the current target before connecting,
> explicit disconnect unbonds only that target, and pairing auto-entry accepts only the selected
> address or one expiring verification candidate. Find/Remove are unavailable during live,
> coordinated BLE, or ankle work. A failed search or verification leaves the current selection and
> its data untouched. Successful replacement/removal atomically clears battery/standby completeness,
> timestamps, ankle certainty/history, four preset targets, monitoring, polling, and low-alert state
> while retaining threshold, interval, and pairing code. Polling is disabled and foot notifications
> are cancelled; removal does not unbond.
>
> Main and notification controls fail closed with no selected foot. API 31+ dynamic active/confirmed
> notification accents now use resource-deferred `RemoteViews.setColor`; API 26–30 resolves the same
> resource during notification construction. The Ankle Alignment info icon opens one accessible,
> source-faithful help dialog. All ankle/standby command bytes and safety transactions below remain
> unchanged.

> **Ankle Alignment v1 update:** This addendum supersedes the standby-only command boundary in the
> older handoff below without rewriting that historical record. The proprietary AA01 allowlist is
> now standby query/on/off, exact Chair Exit and Relax query/on/off, ankle query, absolute ankle set
> using signed little-endian `Int32` millidegrees, and Auto start `B2 B0 04 00`. `FootGattSession`
> remains the sole GATT owner; its one typed AA01 router and transaction mutex serve standby, mode,
> ankle, and Auto traffic, while the existing
> process-wide coordinator serializes live, manual, notification, worker, and disconnect work. AA01
> and AA02 are subscribed before commands. `B2 B0 04 02` remains unknown and is never sent.
>
> Ankle truth is persisted separately from the battery/standby snapshot as an exact millidegree
> value, verification time, and certainty (`UNKNOWN`, `CONFIRMED`, or
> `UNKNOWN_AFTER_COMMAND`). Every fine, preset, and Auto movement performs a fresh standby query and
> requires OFF; the app never silently turns standby off. A set-write response is optional after an
> accepted write, but the final query is authoritative. Possible movement followed by failed
> verification becomes unknown-after-command, and a cached value may then appear only as labelled
> `Last verified` history—not as current state. Reconnect queries the foot and never replays local
> angle or preset state; a process restart treats the persisted angle as historical until a fresh
> typed query succeeds. The app enforces `-2000..14000` md; invalid `±100` md fine steps are disabled
> instead of clamped. A final queried result within one millidegree of the request is accepted while
> preserving the foot's exact value; larger mismatches remain verified truth but report request
> failure. Unsupported firmware out-of-range behavior is not explored.
>
> Barefoot, Running, Dress, and Boots are fixed, initially unconfigured presets. Saving records only
> the selected preset's current foot-confirmed exact angle; recalling a preset runs the same safe
> absolute-set transaction. Fine adjustment clears physical active matching when the confirmed
> value no longer equals a saved target. Auto is event-driven, treats observed `00`/`1E`/`3C`
> activity values as opaque, requires completion plus a final query for success, and never updates
> preset storage. Beta2 uses the owner's replacement line-art footwear family in the app and expanded
> notification. Artwork is aspect-preserving Fit/fitCenter at both phone and wide-screen widths; the
> full Barefoot toes, Running speed lines, Dress heel/toe, and Boots shaft/toe remain in-frame.
>
> The collapsed notification is status-only. In a normal safe state the expanded notification shows
> the four presets and exactly Check / Standby / Auto native actions. Custom `RemoteViews` content is
> transparent inside the SystemUI-owned notification surface and uses notification-aware text
> appearances in light and dark themes. Brand color tokens are centralized in Android resources: the
> dark app and dark notifications use `#16D13A`, while light notifications use the higher-contrast
> `#0B7A1D`. Taps revalidate current permission, Bluetooth, session,
> configured-preset, bounds, and fresh standby truth at execution time. Quick Adjust is intentionally
> hidden until verified calibration is configured: no approved inch-to-angle calibration exists, so
> no inch movement or guessed conversion is available. The physical-validation build is
> `1.2.0-beta2` (`versionCode 3`). API 34 emulator checks passed at approximately 360dp and 785dp
> app widths and for light/dark collapsed and expanded notifications. Physical Samsung/One UI and
> owner hardware validation remain pending until run on the target device.
>
> **Physical validation remains pending.** Software tests cannot establish movement safety. The
> owner should perform the seated and supported checklist in the “Ankle Alignment v1 hardware
> validation” section below before relying on movement controls.

> **Standby v1 update:** The historical notes below describe the pre-standby implementation.
> The current app now routes all live, manual, notification, and scheduled BLE work through
> one process-wide coordinator and `FootGattSession`. A complete check queries confirmed
> standby over Össur AA01 and then reads battery; both values and one timestamp are persisted
> atomically. AA01 and AA02 notifications are initialized in callback order before use. The
> only proprietary commands are the confirmed standby query/on/off packets. The dormant
> `BatteryService` and battery-only `BleReader` paths were removed. A valid battery read still
> updates the live display and low-battery arming/alert logic when standby verification fails,
> without advancing the complete snapshot timestamp. Live monitoring notifications use that live
> battery value while standby and time remain snapshot-backed. A confirmed standby change followed
> by battery failure is persisted as battery-pending and remains visibly incomplete after restart.
> An accepted standby write with a missing SET confirmation proceeds to a typed final QUERY; if that
> query cannot establish the actual state, a distinct ambiguous state survives restart, suppresses
> standby actions, and is recoverable with Check now. Live battery refreshes preserve transient
> operation results until their eight-second expiration.

**Purpose of this document:** complete context for the FootPilot Android app so work can
continue in a fresh session without losing decisions or state. Historical sections remain intact;
the addenda at the top describe the current implementation.

## Ankle Alignment v1 hardware validation

Perform movement checks only while seated and appropriately supported—never while walking or
driving. These items are pending until the owner validates them on the real foot:

1. Connect and confirm AA01/AA02 subscriptions finish before controls enable.
2. Confirm initial battery, standby, and ankle values come from the foot.
3. Reconnect while standby is active and verify persisted device truth is shown.
4. Toggle standby ON/OFF and verify UI changes only after device confirmation.
5. Verify the explicit Disconnect warning while standby is active.
6. With standby OFF and the foot safely positioned, test one `+0.1°` and one `-0.1°`.
7. Confirm decrement is disabled at `-2.0°` and increment at `+14.0°`.
8. Save and recall Barefoot, Running, Dress, and Boots independently.
9. Fine-adjust away from a saved preset and confirm its active indication clears until re-saved.
10. Restart/reconnect and verify device truth replaces local cache rather than replaying it.
11. Verify collapsed and expanded notifications in light and dark system themes, including a preset,
    Check, and Standby action.
12. Run Auto from the app and expanded notification; confirm its result updates the angle but never
    overwrites presets.
13. Confirm standby ON blocks movement both in-app and from an already-rendered notification.
14. Confirm background polling cannot collide with live, manual, preset, standby, or Auto work.
15. Exercise temporary-session preset and Auto paths if the current product flow exposes them, and
    confirm each final query completes before disconnect/unbond.
16. Verify the next connection rediscovers the current physical ankle angle.
17. Verify the exact supplied footwear artwork in the app and expanded notification on the target
    Samsung/One UI device.
18. Only if explicitly chosen and safe, interrupt one movement by disconnecting and verify
    unknown/re-query recovery; otherwise leave this item pending.
19. Regression-check battery reads, polling, low-battery alert/re-arm behavior, pairing PIN,
    transient notifications, explicit disconnect, unbond, and the trusted disconnect peep.
20. Confirm Quick Adjust remains hidden until verified calibration is configured.

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
  - **Össur proprietary service** `1610aa00-0111-0899-2503-732…4219` — Standby v1 uses only the
    confirmed AA01 query/on/off protocol and subscribes to AA01 plus AA02. No other proprietary
    command is permitted.
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
- **Package:** `com.onomic.footpilot`
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

All Kotlin under `app/src/main/java/com/onomic/footpilot/`:

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
- `values/strings.xml` (`app_name = "FootPilot"`), `values/themes.xml` (dark `AppTheme`).

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
- **Foot Modes** — foot-authoritative Chair Exit Mode and Relax Mode switches, refreshed together
  when Settings opens.
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
  (Settings → Apps → FootPilot → Battery) so Samsung doesn't kill background polling.
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

- **Constrained proprietary access.** The app may write only the exact allowlisted AA01 standby,
  Chair Exit, Relax, ankle, and Auto packets documented above. It must never wildcard a packet
  family, infer opaque bytes, or write proprietary packets to AA02.
- The **peep on disconnect** is the user's trusted signal that the link truly dropped; preserve it on the
  explicit Disconnect action.
- The **in-app check is the gold standard** for speed/seamlessness; any background/notification path
  should match its behavior (fast connect → read → unbond-when-monitoring-off).
