# Battery Pass

Working name. An Android app that offers a **timed** battery-optimization exemption for another app the
moment that app opens: a notification with three duration buttons (30 min / 1 h / 4 h by default), one tap,
and a reminder to set the app back when the time is up. The motivating case is a trails app that should be
Unrestricted while it records a hike and Optimized the rest of the time.

## The experiment this scaffold exists to run

Android gives no third-party app an API to change another app's battery setting. What it does have is the
system dialog apps use to ask for their *own* exemption:

> Allow *App* to ignore battery optimizations? **Deny / Allow**

In current AOSP that dialog (`Settings/.../fuelgauge/RequestIgnoreBatteryOptimizations.java`) checks only
the **target** package named in the intent: it exists, it is not already exempt, and it declares
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Nothing compares the caller to the target. So this app fires that
dialog *for the trails app*, and one tap on Allow puts the trails app on the Doze allowlist. No Shizuku,
no root, no developer options.

Open questions this build answers on a real phone:

1. Does the dialog actually appear for another package on **your** phone's Settings app? Vendor Settings
   apps replace this screen. An old Google issue reports a Nexus not showing it while a Samsung did.
2. Is the readback reliable? The dialog returns no result (removed in 2021), so the app checks
   `PowerManager.isIgnoringBatteryOptimizations(target)` when the dialog closes.
3. Does the trails app you care about declare the permission at all? Without it the dialog finishes
   silently. The Apps list shows this per app.

What is **not** possible, and is handled as a reminder instead: reverting. No app can take another off the
allowlist, and the "Restricted" state is out of reach entirely. When a pass ends you get a notification
that deep-links to the app's settings; the monitor notices when you have flipped it back and clears up.

## How it works

| Piece | File | Role |
| --- | --- | --- |
| Monitor | `MonitorService.kt` | Foreground service. Polls `UsageStatsManager` events every 1.5 s while the screen is on; when a watched app comes to the foreground and is not exempt, posts the offer. Pauses on screen off. |
| Offer | `Notifications.kt` | Heads-up notification with up to three duration buttons. Each button is a `PendingIntent` to `GrantActivity`. |
| Grant | `GrantActivity.kt` | Invisible. Fires the system dialog for the target with `startActivityForResult`, then reads back the exemption state and starts the pass or reports the miss (with how many ms the dialog was up, which tells Deny apart from "never shown"). |
| Pass | `Passes.kt`, `Prefs.kt` | Stores the pass, schedules the expiry alarm (exact when allowed), shows the ongoing "exempt until" notification with End now / Extend. |
| Expiry | `PassReceiver.kt` | Alarm fires: if still exempt, shows the reminder with Open settings / Extend / Done. |
| Boot | `BootReceiver.kt` | Rebuilds alarms and notifications after reboot or app update; restarts the monitor if it was on. |
| State reads | `BatteryState.kt` | All public API: exempt or not, declares the permission or not, Restricted or not (best effort), usage access. |
| Logging | `Logs.kt` | Tiered: Off / Light / Normal / Verbose. Light is on by default and records every state change **with its source** (user, alarm, boot, monitor). Viewable and copyable from the main screen. |

Permissions: usage access (special, granted in Settings), notifications, foreground service (specialUse),
`QUERY_ALL_PACKAGES` (to list apps and read their declared permissions), exact alarms (optional), boot
completed, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the app's own exemption so reminders fire on time.

## Build and install

Requires Android Studio (its bundled JDK is enough) and SDK platform 36 with build-tools 36.1.0, both
present on the machine this was scaffolded on. `local.properties` is git-ignored; Studio regenerates it.

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run with the phone connected. The APK is debug-signed;
"Install unknown apps" has to be allowed for whatever you sideload it with.

## Phone test plan

Set the log level to **Normal** before starting. Copy the log at the end (Log > Copy).

1. **Setup.** Open the app. Tap Allow for notifications, then Settings for usage access and grant it.
   Optionally Request the app's own exemption and allow exact alarms.
2. **Find the trails app** in the Apps list. Note whether its row says "Declares the permission, dialog
   possible" or "No permission, dialog impossible". If the latter, the experiment cannot work for that app;
   pick any app that does declare it for the rest of the plan and note the trails app's package name.
3. **The experiment.** Tap **Test** on an app that declares the permission and is Optimized.
   Expected: the system dialog "Allow *App* to ignore battery optimizations?" appears. Tap **Allow**.
   Expected: toast "*App* exempt for 30 min", an ongoing "exempt until" notification, and in
   Settings > Apps > *App* > Battery the setting now reads Unrestricted. Log line: `grant: EXEMPT after dialog`.
   If instead a notification says "still optimized" and the log shows `NOT exempt ... elapsedMs=` under
   about 500 ms, the Settings app refused to show the dialog for another package. That is the key finding.
4. **Deny path.** Test a second app, tap Deny. Expected: "still optimized" notification, log `NOT exempt`
   with a larger elapsed time.
5. **Monitor path.** Check the trails app's box, turn on "Offer a pass when a watched app opens", go Home,
   open the trails app. Expected within about 2 s: heads-up offer with three buttons. Tap one.
   Expected: the system dialog, then the same results as step 3. Re-opening the app within 10 minutes
   does not re-offer (cooldown); opening it while exempt never offers.
6. **Expiry.** Set durations to `1,2,3`, Save, and run step 3 again with the 1-minute button.
   Expected at the minute: "Pass ended" reminder with Open settings / +1 min / Done. Tap Open settings,
   set Battery to Optimized, go back. Expected within about 30 s with the monitor on: reminder disappears,
   log `pass closed ... source=monitor/periodic`. Without the monitor, tap Done.
7. **End now and Extend** on the ongoing notification: End now brings the reminder forward immediately;
   Extend pushes the time out and the notification updates.
8. **Reboot** during an active pass. Expected after boot: the "exempt until" notification is back, the
   alarm still fires on time, the monitor notification is back if it was on. Log: `boot: restoring`.
9. **Copy the log** and keep it with the phone model and Android version.

## Known limits

- Reverting is a reminder, not an action. Nothing short of Shizuku or root can set an app back.
- The Restricted battery state cannot be changed either way, and the readout of it is best effort.
- OEM Settings apps (Samsung, Xiaomi, OnePlus and others) may not show the dialog for another package.
  Only the phone test tells.
- Google Play: `QUERY_ALL_PACKAGES` needs a declared use case, Google says apps may request the
  exemption only when their core function needs it, and it has said it monitors apps holding that
  permission. A utility whose purpose is requesting it for other apps is a review question, not settled here.
- Detection depends on usage access and the monitor's foreground service. An accessibility service would
  be faster but is a Play policy risk, so it is not used.

## Later, if the experiment works

- Shizuku as an optional advanced mode that makes the revert real (`cmd deviceidle whitelist -pkg`).
- A Quick Settings tile as a trigger-free alternative to the monitor.
- Adaptive launcher icon, strings in resources, a release signing config.
