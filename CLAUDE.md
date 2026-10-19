# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Android app for a Toyota Yaris diesel. It replaces the ESP32 dashboard in `../DPFViewer`. It runs as an always-on background service that connects to an ELM327 Bluetooth dongle and logs DPF soot, DPF temperature and the regen flag to SQLite. It records each regeneration with its duration and the GPS distance since the previous one, and shows an ongoing notification while a regen is running.

## Commands

This machine has no Android SDK, Gradle or modern JDK (only Java 8), so nothing builds or runs locally. Build in Android Studio, or set up CI. There is no `gradlew`: only `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.11.1) exists. Android Studio or `gradle wrapper` generates the scripts and jar.

- Build debug APK: `./gradlew assembleDebug`
- Unit tests (plain JVM, no device): `./gradlew test`
- Single test: `./gradlew :app:testDebugUnitTest --tests "com.yarisdoctor.ElmTest.decodersUseDpfViewerOffsets"`
- Install: `./gradlew installDebug` (writes to the connected phone; ask first)
- Logs: `adb logcat -s DpfService`

CI (`.github/workflows/build.yml`) runs `gradle test assembleDebug` on every push and PR. It pins Gradle via `setup-gradle` because there is no wrapper jar.

**Release:** `git tag vX.Y.Z && git push origin vX.Y.Z` builds a signed APK and publishes it as a GitHub Release, which Obtainium on the phone installs. `versionName` comes from the tag, `versionCode` from the run number. The signing key (`~/.android-keys/yaris-doctor.p12` + `.password.txt`, alias `yaris`) lives outside the repo. It is stored in the repo secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`. Never regenerate it: a different key forces an uninstall, which wipes the phone's database.

The app has never been compiled. Treat the first build as a check of everything, and verify behaviour on the car.

## Architecture

- **No AndroidX or third-party dependencies.** It uses only framework APIs: `android.app.Activity`, `Notification.Builder`, `SQLiteOpenHelper`, `LocationManager`, and plain `Thread`. The UI is a single code-built `TextView` with no layout or resource files, and icons are `android.R.drawable`. JUnit is the only dependency, for tests. minSdk 31, so Android 12+ Bluetooth permissions only.
- **`DpfService`** is a foreground service (`connectedDevice|location`), started by `MainActivity` after permissions are granted and by `BootReceiver` on boot or app update. One worker thread loops:
  - **Idle:** sets an `AlarmManager` `ELAPSED_REALTIME_WAKEUP` alarm every `PROBE_MS`, wakes on a `Semaphore`, and tries an RFCOMM SPP connect to the hardcoded `DONGLE_MAC`. The phone has to start the connection because the dongle never connects on its own. The alarm exists because CPU sleep freezes `Thread.sleep`.
  - **Connected:** holds a non-reference-counted wakelock, starts GPS and polls `21 DD` / `21 F1` / `21 DF` every 5 s, or every 1 s during regen.
  - **Ignition off:** the dongle may stay powered, so ECU silence counts as ignition off. The session ends after 1 dead cycle if no data was ever seen (a quick probe), or after 3 dead cycles mid-drive.
  - **Regen interrupted by disconnect:** the `finally` block closes the regen row and cancels the notification.
- **`Elm.kt`** ports ELMduino's response handling so the DPFViewer char offsets still apply. `parse()` keeps only `[A-Za-z0-9:.\r]`, returns null on `NODATA`/`UNABLETOCONNECT`/`STOPPED`/`ERROR`, and joins multi-frame (`N:`) replies, trimmed to the byte count on the first line. Decoders index the hex string by **character** (2 per byte). `init()` mirrors `myELM.begin(..., '6')` plus `AT SH 7E0` / `AT CRA 7E8`.
- **Distance / "odometer":** the car's odometer PID is unknown, so distance comes from GPS fixes, counted only while a session is connected. Fixes with accuracy over 25 m or speed under 1 m/s are dropped. `odoM` is written only by the GPS callback on the main thread and read by the worker (`@Volatile`). It persists as `sample.odo_m`, and the service restores it at start from `MAX(odo_m)`, so there is no separate prefs store.
- **`Db`** is a process-wide singleton (`Db.get`), shared by the service and the activity. Tables `sample` and `regen`. Miles between regens is computed at read time in `regens()`: this regen's `odo_start` minus the previous regen's `odo_end`. Schema version is 1 and `onUpgrade` is empty, so a schema change needs a real migration or it will wipe users' history.

## Facts from the car (do not change without on-car confirmation)

- Payload char offsets and scales: soot `[38,40)` ×2 (%), regen flag char `[33]` == 1, DPF temp `[16,20)` ×0.625 (°C). They were found by trial and error in DPFViewer `BT.h` and are covered by `ElmTest`.
- Dongle MAC `66:1E:32:7A:2E:72`, PIN `1234`. It must be paired in Android settings first.

## Known gaps

- A regen interrupted by switching off and then resumed on the next drive is stored as two rows.
- Some ELM327 clones need the `createRfcommSocket(1)` reflection fallback; it is not implemented.
- Android 16 promoted "Live Update" notifications are not used (needs compileSdk 36).
