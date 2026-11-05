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

**Result, 2026-11-05, Moto G54:** the dialog appears for another package and Allow grants the exemption.
The concept holds on at least one near-stock device. Still open: other vendors' Settings apps.

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
| Updates | `Updater.kt`, `InstallReceiver.kt` | Self-update for sideloaded builds: fetches `latest.json`, offers a newer build on the main screen, downloads it, checks the SHA-256, installs it through PackageInstaller. See "Updates" below. |

Permissions: usage access (special, granted in Settings), notifications, foreground service (specialUse),
`QUERY_ALL_PACKAGES` (to list apps and read their declared permissions), exact alarms (optional), boot
completed, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the app's own exemption so reminders fire on time,
and for self-update `INTERNET`, `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

## Build and install

Requires Android Studio (its bundled JDK is enough) and SDK platform 36 with build-tools 36.1.0, both
present on the machine this was scaffolded on. `local.properties` is git-ignored; Studio regenerates it.
`keystore.properties` (git-ignored, template in `keystore.properties.example`) points at the permanent
signing key; without it debug builds fall back to the debug key and cannot be updated over the air.

```bash
scripts/release.sh --notes "what changed"
```

That builds the signed release APK into `dist/battery-pass-<version>.apk` with `dist/latest.json` beside it.
Install it from the phone's file manager, or:

```bash
adb install -r dist/battery-pass-*.apk
```

`./gradlew assembleDebug` still works for quick iterations and is signed with the same key, so a debug
build and a release build can replace each other on the phone. Builds signed with different keys cannot:
a phone holding a build signed with the old debug key must uninstall it before the first permanently
signed build goes on (one time, 2026-11-05).

## Updates

Every build from 0.2.0 on can update itself. The rule is a standing one for all of Daniel's Android apps:
OTA from the first APK anyone installs, because the first build's signing key decides whether any later
build can replace it.

**On the phone.** The card at the top of the main screen shows the installed version and the last check.
The app checks on open, at most every 6 hours (15 minutes after a failed check), and on Check now. When
`latest.json` names a higher versionCode the card offers it with its notes: Install update downloads the
APK, verifies the SHA-256 from the manifest, and commits a PackageInstaller session. Android 12+ may install
a self-update without a prompt; otherwise the system confirm dialog opens (or, if the app is no longer in
front, a notification whose tap opens it). The first time, Android also needs "Install unknown apps" for
Battery Pass; the card shows an Allow installs button until it is on. Later hides an offer until the next
successful check. Every check, download and install verdict is in the log with its source.

**The manifest.** `BuildConfig.UPDATE_MANIFEST_URL` is
`https://github.com/SteadfastCode/battery-pass/releases/latest/download/latest.json`, which GitHub
redirects to the `latest.json` asset of the newest release. An empty URL disables the updater. Fields:

```json
{
  "versionCode": 3,
  "versionName": "0.2.0",
  "apkUrl": "https://github.com/SteadfastCode/battery-pass/releases/download/v0.2.0/battery-pass-0.2.0.apk",
  "sha256": "<hex of the APK>",
  "notes": "What changed",
  "publishedAt": "2026-11-05T21:00:00Z"
}
```

**Publishing a release.** Bump `versionCode` (always) and `versionName` in `app/build.gradle.kts`, commit, then:

```bash
scripts/release.sh --notes "what changed" --publish
```

It builds, writes `dist/`, tags `v<versionName>`, pushes, and runs `gh release create` with the APK and the
manifest attached. Phones pick the new build up on their next check. `RELEASE_REPO` and `RELEASE_APK_URL`
override the repository and the APK location when the files are hosted elsewhere; the manifest format is
the same on any static host.

**The signing key** lives outside the repo at `C:\Users\danie\.android\keystores\battery-pass.jks`
(PKCS12, alias `battery-pass`, RSA 4096, valid 10,000 days), referenced from `keystore.properties`. Back
up the file and its password in the password manager: losing the key ends over-the-air updates for every
installed copy, since no differently signed build can replace them. If the app ever goes to Google Play,
upload this key as the app signing key so Play builds and sideloaded builds stay interchangeable.

## Phone test plan

Set the log level to **Normal** before starting. Copy the log at the end (Log > Copy).

1. **Setup.** Open the app. Tap Allow for notifications, then follow the usage access card. On a copy
   installed from a file, Android 13+ greys the Usage access switch out as a "restricted setting" until you
   open this app's App info, tap the three-dot menu at the top right, and choose **Allow restricted
   settings**; then the switch works. Store and adb installs skip that. Optionally Request the app's own
   exemption and allow exact alarms.
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
9. **Update.** With a newer release published (see "Updates"), open the app: the top card should name the
   new version within a few seconds. Tap Install update. Expected: progress, then either a silent install or
   the system confirm dialog, then the app restarts on the new version. Log: `update check done ... newer=true`,
   `update install committed`, and on the next start `app updated versionCode N -> M`.
10. **Copy the log** and keep it with the phone model and Android version.

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
- Usage access is a "restricted setting" for sideloaded installs on Android 13+ (the Allow restricted
  settings step above). The in-app help card spells it out; a store install never sees it.
- Google Play does not allow `REQUEST_INSTALL_PACKAGES` outside app stores and file managers, so a Play
  build has to drop the updater and that permission (a product flavor, when the time comes).

## Later, if the experiment works

- Shizuku as an optional advanced mode that makes the revert real (`cmd deviceidle whitelist -pkg`).
- A Quick Settings tile as a trigger-free alternative to the monitor.
- Adaptive launcher icon, strings in resources, a `play` flavor without the updater.
