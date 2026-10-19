# Yaris Doctor

An Android app that sits silently in the background and watches the diesel particulate filter (DPF) on a Toyota Yaris diesel through a cheap ELM327 Bluetooth OBD-II dongle.

When the car starts and the dongle powers up, the app connects by itself and logs soot load, DPF temperature and regeneration state. When the car starts a DPF regeneration, a live notification appears so you know to keep driving; it disappears when the regen finishes. Every regen is saved with its duration and the miles driven since the previous one.

It replaces DPFViewer, an earlier ESP32 + TFT dashboard that read the same values.

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
   - **Location → "Allow all the time"**: GPS is how distance between regens is measured. Without it, logging still works but miles stay at 0.
   - **Notifications**: for the regen alert.
   - **Unrestricted battery use**: otherwise Android kills the background service.

3. **Pick your dongle:** tap the settings icon (top right) → **Bluetooth dongle**, and choose it from your paired devices.

From then on it starts at boot. There is nothing to open before a drive.

## What you get

- **Regen alert:** a high-priority, ongoing notification while the DPF regenerates. It shows a running timer, DPF temperature and soot load, updated every second.
- **Regen history:** for every regen it records the start time, how long it took, soot before and after, and the miles driven since the previous regen ended.
- **Full log:** soot, temperature and regen state every 5 s while driving (1 s during a regen), stored in a SQLite database on the phone.
- **App screen:** current soot and miles since the last regen, a 14-day soot chart, a miles-between-regens chart (tap or drag to read values), and the regen history.
- **Settings** (icon, top right): dongle, background logging on/off, regen notification on/off, miles or kilometres.
- **Debug mode** (Settings → Developer): a simulated dongle feeds random but plausible data, with 14 days of fake history and a regen every few minutes, so you can try the screen and the notification without the car. Nothing is saved to your real log. Debug mode turns itself off when the phone restarts or Android closes the app.

A small, low-priority notification ("Waiting for dongle" / "Logging") is always present. Android requires one for any app that runs in the background continuously.

## How it works

- **Finding the car.** An ELM327 dongle never connects to the phone on its own, so the app checks about once a minute and tries to connect. It uses an alarm, so this still happens while the phone is asleep. If the dongle answers but the engine ECU doesn't (ignition off, dongle still powered), it disconnects within seconds so a parked car doesn't drain the phone.
- **Reading the ECU.** The app sets the dongle to talk to the engine ECU (`AT SH 7E0`, `AT CRA 7E8`, CAN 11-bit 500 kbps). It then polls three Toyota mode-21 PIDs:

  | PID | Value | Decoding (hex payload, character offsets) |
  |---|---|---|
  | `21 DD` | Soot load (%) | chars 38–39 × 2 |
  | `21 F1` | DPF temperature (°C) | chars 16–19 × 0.625 |
  | `21 DF` | Regen active | char 33 == `1` |

  These offsets were found by trial and error on one car (see DPFViewer). Other Yaris engines or model years may differ.
- **Distance.** The car's odometer PID isn't known, so distance comes from the phone's GPS, counted only while the dongle is connected. Fixes that are inaccurate or stationary are ignored to avoid GPS drift.
- **Engine off mid-regen.** The regen is closed at the moment the connection drops, and the notification is cleared.

## Limitations

- When the phone has been asleep a while, Android can delay the background check by a few minutes, so the first minutes of a drive may not be logged.
- A regen that is interrupted by switching off and then finished on the next drive is stored as two regens, the second with a tiny "miles since last".
- GPS distance is an estimate and misses any driving done without the phone.
- Some ELM327 clones refuse Android's standard Bluetooth serial connection. If the app never connects, the dongle may be the cause.
- There is no data export yet. The database is included in Android's normal app backup.

## Building

The project uses only the Android framework (no AndroidX or third-party libraries) and has a single module.

- **Android Studio:** open the folder and run. It generates the Gradle wrapper on first sync.
- **Command line:** with JDK 17, the Android SDK and Gradle 8.11.1:
  ```sh
  gradle test assembleDebug   # APK in app/build/outputs/apk/debug/
  ```

The unit tests cover the ELM327 reply parser and the PID decoders, and need no device.

## Releases

CI ([`build.yml`](.github/workflows/build.yml)) builds and tests every push to `main`. Pushing a version tag publishes a signed APK as a GitHub Release, which Obtainium picks up:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

The signing key lives in the repo secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`. It must never change: an APK signed with a different key can't update the installed app, and reinstalling deletes the logged history.

## License

[MIT](LICENSE)
