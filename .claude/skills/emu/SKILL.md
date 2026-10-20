---
name: emu
description: Build the debug APK and run it on the local S25 Android emulator (install, screenshot, logcat). Use to check an app change on a device.
---

Local toolchain lives in `C:\Users\alex\Android` (JDK 17, Gradle 8.11.1, SDK). Run from Git Bash:

```bash
export JAVA_HOME="C:/Users/alex/Android/jdk-17.0.20.1+1" SDK=/c/Users/alex/Android/sdk MSYS_NO_PATHCONV=1
```

- AVDs: `S25` (API 36.0, 1080x2340, 420 dpi) and `S25_QPR` (API 36.1). Live Updates / Now Bar promotion only happens on `S25_QPR`; 36.0 never promotes.
- Start in the background: `$SDK/emulator/emulator -avd S25 -no-snapshot-save > /c/Users/alex/Android/emu.log 2>&1`, then `$SDK/platform-tools/adb wait-for-device`.
- Build: `/c/Users/alex/Android/gradle-8.11.1/bin/gradle -q assembleDebug`, then `gradle --stop` (the emulator needs the memory).
- Install needs a Windows path: `adb install -r 'C:\Users\alex\Desktop\GitHub\yaris-doctor\app\build\outputs\apk\debug\app-debug.apk'`.
- Device paths need `MSYS_NO_PATHCONV=1` (already exported above), e.g. `adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`.
- No car: turn on Debug mode in Settings for simulated data. Logs: `adb logcat -s DpfService Sync`.
- The Settings app hides other apps' overlays: check the edge light over the home screen or Maps.
- Memory is tight: if the emulator or a build is killed, tell the user instead of restarting it. Only touch an emulator, never `installDebug` to a real phone without asking.
