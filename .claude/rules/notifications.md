---
paths:
  - "**/DpfService.kt"
  - "**/EdgeLight.kt"
  - "**/SettingsActivity.kt"
  - "**/AndroidManifest.xml"
  - "**/build.gradle*"
---

# Regen notification = Android 16 Live Update (Now Bar on Samsung, status chip elsewhere)

- On API 36+, `DpfService.liveUpdate()` sets the `android.requestPromotedOngoing` extra (what `setRequestPromotedOngoing()` does; that method only exists from API 36.1, so it can't be called with compileSdk 36), a coral `ProgressStyle` (share of the starting soot burned off, from `burnPercent()`) and `setShortCriticalText("<soot>%")` for the chip. One UI 8 shows promoted notifications in the Now Bar. Keep the rules or promotion is silently dropped: ongoing, a content title, a non-MIN channel, never `setColorized(true)`, no custom views. The `POST_PROMOTED_NOTIFICATIONS` permission is in the manifest. Settings shows `canPostPromotedNotifications()` and links to the system toggle, falling back to the app's notification settings when a manufacturer lacks that screen. Nothing here is Samsung-only: no Samsung SDK, just the Android 16 API, and the brand is only used for wording.
- That needs compileSdk 36 (AGP 8.10.1). targetSdk is still 35 on purpose: raising it changes Android 16 behaviour (edge-to-edge, predictive back) and needs on-phone checks.
- The regen notification's DPF temperature and the health-warning body follow `Prefs.units()` at display time (`temperatureNoSpace()`, `Units.text()`); no converted value or unit is stored. A visible notification updates on the next regen sample, so a unit change needs no service restart.

# Edge light

`EdgeLight` adds a full-screen `TYPE_APPLICATION_OVERLAY` view (a blurred coral stroke along the screen outline, its corners taken from `Display.getRoundedCorner()`, square where the display reports none) while a regen runs and `Prefs.edge` is on. It needs `SYSTEM_ALERT_WINDOW`, which is opt-in from Settings. The window is `FLAG_NOT_TOUCHABLE` with `alpha = 0.8f`: Android 12+ blocks touches through untrusted overlays above 0.8 opacity, so raising it would freeze Google Maps. Window calls are posted to the main thread because `publish()` runs on the worker.
