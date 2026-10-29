# Yaris Doctor

An Android app that sits silently in the background and watches the diesel particulate filter (DPF) on a Toyota Yaris diesel through a cheap ELM327 Bluetooth OBD-II dongle.

When the car starts and the dongle powers up, the app connects by itself and logs soot load, DPF temperature and regeneration state. When the car starts a DPF regeneration, a live notification appears so you know to keep driving; it disappears when the regen finishes. Every regen is saved with its duration and the distance driven since the previous one.

It replaces DPFViewer, an earlier ESP32 + TFT dashboard that read the same values.

<p align="center">
  <img src="docs/screenshots/main-regen.png" width="200" alt="Overview during a regen: health warnings, the Keep driving callout and the soot load card, with the coral edge light">
  <img src="docs/screenshots/history.png" width="200" alt="Regen history: each regen with its duration, soot before and after, soot 5 min later, peak DPF temperature and distance since the previous one">
  <img src="docs/screenshots/settings.png" width="200" alt="Settings: dongle, logging, regen notification, Live Updates, edge light, units and model year">
</p>
<p align="center">
  <img src="docs/screenshots/notification.png" width="400" alt="Regen notification with temperature, soot and burn-off progress">
</p>
<p align="center"><sub>Screenshots in Debug mode (simulated data) on an Android 16 emulator sized like a Galaxy S25.</sub></p>

## Install

Scan with your phone to add it to [Obtainium](https://github.com/ImranR98/Obtainium), which installs it and keeps it updated from this repo's releases:

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/alexscemo/yaris-doctor">
    <img src="docs/obtainium-qr.svg" width="220" alt="QR code: add Yaris Doctor to Obtainium">
  </a>
</p>

On the phone itself, tap the QR code. Or, in Obtainium, choose **Add app** and paste `https://github.com/alexscemo/yaris-doctor`.

Without Obtainium, download the latest `yaris-doctor-vX.Y.Z.apk` from [Releases](https://github.com/alexscemo/yaris-doctor/releases) and open it.

Requires **Android 12 or newer**.

## Setup

1. **Pair the dongle** in Android's Bluetooth settings with the ignition on. The PIN is usually `1234` or `0000`.
2. **Open the app and allow everything it asks for:**
   - **Nearby devices (Bluetooth)**: needed to talk to the dongle.
   - **Location → "Allow all the time"**: only for the home hub. Android shows apps the Wi-Fi name only with this, and that's how the app knows it is home (to upload, and to stop looking for the dongle). The app never uses GPS; without it, everything but the hub works.
   - **Notifications**: for the regen alert.
   - **Unrestricted battery use**: otherwise Android kills the background service.

3. **Pick your dongle:** tap the settings icon (top right) → **Bluetooth dongle**, and choose it from your paired devices.

From then on it starts at boot. There is nothing to open before a drive.

## What you get

- **Regen alert:** a high-priority, ongoing notification while the DPF regenerates. It shows a running timer, DPF temperature and soot load, updated every second. On Android 16 it is a Live Update with a burn-off progress bar: Samsung (One UI 8) shows it in the Now Bar, other phones as a status-bar chip. Older Android versions get the normal notification.
- **Edge light (optional):** a coral glow around the screen edges during a regen, drawn over other apps such as Google Maps so it is noticed at a glance. Turn it on in Settings → Car; it asks for "Display over other apps". Touches pass straight through.
- **Regen history:** for every regen it records the start time, how long it took, soot before and after, and the distance driven since the previous regen ended.
- **Full log:** soot, temperature and regen state every 5 s while driving (1 s during a regen), stored in a SQLite database on the phone.
- **App screen:** current soot and distance since the last regen, DPF temperature, a **Next regen** estimate (about how far until the soot reaches the level this car's regens start at, or the build-up rate), a 14-day soot chart, a distance-between-regens chart (tap or drag to read values), and the regen history.
- **Maintenance** tab: the car's mileage (its own odometer, read from the dashboard), what is coming up, and a logbook of past and planned jobs. Each job can remind you again after a distance, a number of months or both; an overdue one shows as a warning on Overview.
- **Settings** (icon, top right): dongle, background logging on/off, regen notification on/off, Live Updates (Android 16), edge light, **Units** (imperial: mi, °F, psi, mph; or metric: km, °C, kPa, km/h, applied to every screen and notification), model year (2011-2014 or 2015+, which sets the soot and temperature limits the checks use). Also the home hub, **Back up database** / **Restore database** (one SQLite file you can move to a new phone), **Export database**, which saves the whole log to Downloads as one Markdown file for an AI to read, and **Delete trips**, which removes drives you no longer want here and on the hub. The database, the hub payload and that Markdown export stay metric so logs from different phones and unit choices compare directly.
- **Debug mode** (Settings → Developer): a simulated dongle feeds random but plausible data, with 14 days of fake history and a regen every few minutes, so you can try the screen and the notification without the car. Nothing is saved to your real log. Debug mode turns itself off when the phone restarts or Android closes the app.

A small, low-priority notification ("Waiting for dongle" / "Logging") is present while the app watches for the car. Android requires one for any app that runs in the background continuously. On your home Wi-Fi (Settings → Home hub → Home Wi-Fi name) the app stops looking for the dongle, so the notification goes away; it starts again within about a minute of leaving that Wi-Fi. This needs location "Allow all the time" and battery optimisation turned off for the app, which it asks for on first start.

## Home hub (optional)

Run **[Yaris Doctor Hub](https://github.com/alexscemo/yaris-doctor-hub)** on a home server (one Docker container) to
keep every phone's log in one place. If two people drive the car, their trips add up, and you get a dashboard in the
browser. In the app, **Settings → Home hub**: set the **Hub address** (e.g. `192.168.1.50`) and your **Home Wi-Fi name**.
Both are empty by default, and nothing leaves the phone until both are set. After that, new data uploads by itself
about a minute after you get home; **Sync now** forces it. Debug-mode data is never uploaded.

Sync works both ways. While at home, the app also downloads what the other phones logged, so your own screen shows the
whole car: the latest soot reading, regens and the distance since the last one, whoever was driving. This needs a hub
from October 2026 or later (it adds `/api/export`).

## How it works

- **Finding the car.** An ELM327 dongle never connects to the phone on its own, so the app checks about once a minute and tries to connect. It uses an alarm, so this still happens while the phone is asleep, and it checks at once when the phone is plugged into the car. If the dongle answers but the engine ECU doesn't (ignition off, dongle still powered), it disconnects within seconds so a parked car doesn't drain the phone.
- **Reading the ECU.** The app sets the dongle to talk to the engine ECU (`AT SH 7E0`, `AT CRA 7E8`, CAN 11-bit 500 kbps). It then polls three Toyota mode-21 PIDs:

  | PID | Value | Byte (from 0, incl. the echoed `61 xx`) | Formula |
  |---|---|---|---|
  | `21 DD` | Soot load (%) | 19 | `A*2` |
  | `21 F1` | DPF temperature (°C) | 8 | `(A*256+B)*0.625` |
  | `21 DF` | Regen active | 16 | `A&15` (regen when it gives 1) |

  These were found by trial and error on one car (see DPFViewer) and confirmed against live data. Other Yaris engines or model years may differ. Results are rounded down.
- **Distance.** The car's own odometer. The instrument cluster broadcasts it in whole miles on CAN id 0x611 (`21 00 00 04 00 01 5C 47` = 89,159 mi on the owner's car). The app listens for it for about 1.5 s at the start of each drive and every 5 minutes after, and adds up the ECU's vehicle speed in between, kept inside the mile the dash shows. Every reading, regen, engine frame and warning stores the odometer at its time. History logged before the first read (with GPS, by older versions) was moved onto the car's odometer and is marked as an estimate.
- **Engine off mid-regen.** The notification is cleared when the connection drops, but the regen stays open for 2 minutes: if the dongle reconnects in that time, it carries on as the same regen. Otherwise it is closed at the last reading.

## Limitations

- When the phone has been asleep a while, Android can delay the scheduled background check by a few minutes, so the first minutes of a drive may not be logged. Plugging the phone into the car wakes it at once.
- A regen that is interrupted by switching off and then finished on the next drive shows as one regen in the app (the soot continues), though the hub still receives the two raw rows.
- The cluster counts whole miles, so a reading is within a mile of the dash (usually much closer: vehicle speed fills in between reads). Rows from before the first cluster read are estimates from the old GPS distance and miss driving done without the phone.
- Some ELM327 clones refuse Android's standard Bluetooth serial connection. The app retries through the hidden channel those clones usually answer; if it still never connects, the dongle may be the cause.
- Android's cloud backup excludes the database (it outgrows the 25 MB quota), so use Settings → Your data → **Back up database** and keep the `.db` file somewhere safe. **Export database** is the readable Markdown copy for an AI, and is not restorable.

## Building

The project uses the Android framework plus AndroidX Activity and Lifecycle for the screens. It has two modules: `domain` (the car logic in plain Kotlin, tested on the JVM) and `app`.

- **Android Studio:** open the folder and run.
- **Command line:** with JDK 17 and the Android SDK, the committed wrapper pins Gradle 8.11.1:
  ```sh
  ./gradlew test lint assembleDebug   # APK in app/build/outputs/apk/debug/
  ```

The unit tests cover the ELM327 reply parser, the PID decoders, the poller, the health checks, the regen recorder, sync, the Debug-mode simulator, the next-regen forecast and the SQLite layer (schema, trips, thinning, backup/restore), and need no device. `lint` runs in CI.

## Releases

CI ([`build.yml`](.github/workflows/build.yml)) builds and tests every push to `main`. Pushing a version tag publishes a signed APK as a GitHub Release, which Obtainium picks up:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The signing key lives in the repo secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`. It must never change: an APK signed with a different key can't update the installed app, and reinstalling deletes the logged history.

## License

[MIT](LICENSE)
