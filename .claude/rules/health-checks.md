---
paths:
  - "**/Checks.kt"
  - "**/ChecksTest.kt"
  - "**/RulesTab.kt"
  - "**/Normal.kt"
  - "**/DpfService.kt"
  - "**/DriveSession.kt"
  - "**/Db.kt"
  - "**/HubDefs.kt"
---

# Health checks

**Health checks** (`Checks.kt`, tested in `ChecksTest`) run on the phone; the hub will only display them.

- **Live checks:** `Checker` is one per drive. The service feeds it each cycle's replies, and it returns `Finding`s.
  - Every rule has a hold time, so one odd reading never fires.
  - Values older than 6 min are ignored.
  - The coolant-rise rule keeps 2 min of history. A "soot rising fast" rule existed and was removed: on the owner's car it fired every drive; `regen_frequent` says the same once per regen.
  - **`regen_flag_check`:** Toyota's own regen sign is After Injection Period (`21AD`, MEDIUM) above 0. The rule warns when it and the app's regen flag disagree for 5 min with the engine running. After injection counts as on for 90 s after its last non-zero read, because mid-regen it drops to 0 for up to 55 s on the owner's car (overrun, idle) and `21AD` is read every ~18 s. On the 21 Oct log the two never disagreed; a build with the wrong regen byte (11) would have fired 5 min into the 09:33 regen. Short NOx purges also after-inject but last seconds. `Sim.frames()` sets it during simulated regens.
- **Model year:** `Prefs.model2015` (Settings → Car → Model year, "2011-2014" default, the owner's car). `Checker(model2015)` takes the soot levels from `sootLimits()` (needed / P2463: 100 / 150 %, 2015+: 118 / 174 %), `trends()` the B1S2 regen maximum from `regenTempMax()` (700 / 750 °C) and the All data tab the `TOYOTA_NORMAL_2015` lines through `toyotaNormal()`. The service reads it once at start, so a change restarts it. Rule descriptions name both years, so the hub's copy (`ruleDefs()`) stays static.
- **History checks:** `DpfService.history()` = `trends()` on the regen history (interval under 150 km, last 3 regens under 10 min) plus `sensorZero()`: the DPF pressure with the engine stopped and ignition on (`Db.engineOffDp()`) must be within -0.5..1 kPa (the owner's reads 0.3-0.4), plus `oilDue()`. Recomputed at start, when a regen closes and when a drive ends.
  - **Regen warning signs:** `Db.withSigns()` adds each regen's `floor` (first soot sample 5-10 min after it ended, own or peer; null when no phone was connected) and `peakTemp` (max sample `temp`, i.e. B1S2, during it), computed at read time with no schema change. It costs a few indexed queries per regen, so callers pass only the regens they use (the last 10 for `history()`, the last 50 for the Overview list). `trends()` judges the newest finished regen that has each value, so a drive that ended before the floor could be read doesn't clear the warning.
  - **`soot_floor`:** floor of 30 % or more (estimate). A burn ends near 10 %; Toyota's 2011-2014 DPF test passes a filter at 40 % or less after two forced regens. The owner's car read 38-40 % at +5 min (and 10 % at the end of the burn) while its ash record was cleared, with regens every ~40 km.
  - **`regen_hot`:** peak over 700 °C (2015+: 750), the top of Toyota's B1S2 regen range. The owner's regens peak at 627-638 °C.
  - **`short_trips`** (estimate): `forecast()` says the next regen is due within 30 km and each of this phone's last 3 trips (`DriveStore.trips()`) took under 15 min. It warns before the regen starts, so the driver can plan a longer drive. Settings' oil row also counts the regens since the change that ran under 10 min ("cut short"), the same cut-off as `regen_interrupted`.
  - **Oil:** Settings → Car → Oil changed stores `Prefs.OIL_AT` (time) and `OIL_ODO` (the real log's GPS odometer, metres) and restarts the service. `Prefs.oilMetres()` = odometer now minus `OIL_ODO`, this phone's GPS only. The row shows distance and regens since. `oil_change_due` warns from 15000 km (Toyota's interval, checked in the P252F procedure); never when no change was recorded.
- **DPF condition:** `restriction()` = DPF pressure per 100 l/s of exhaust (MAF turned into volume at the after-DPF temperature, ~1 atm; null under 1200 rpm or 25 l/s). `Db.dpfConditions()` (the queries; the per-regen logic is `dpfConditions()` in `Checks.kt`, tested in `ChecksTest`) gives, per finished regen, the median restriction in the 5 min after it (`base`, what burning can't remove: ash) and its rise per km until the next regen (`perKm`). The window is 5 min because the owner's filter already rises a lot within 15. Shown on the Overview ("DPF condition" card) and sent to the hub. On 21 Oct: base 6.7, +0.14 per km. Under it, the ECU ash record (`A918`) with `Db.ashHistory()`: its readings where the value changed (`LAG` over the `A918` frames), newest first, last 10.
- **Trip summary:** when a drive ends (ECU silent for `REGEN_GAP_MS`), `tripDone()` posts one low-importance "Drive finished" notification from `Db.tripStats()`: distance, soot first → last, regens and warnings started.
- **Rules tab:** every `Checker.Rule` carries a `title`, an `about` (condition, threshold, source) and `uses` (the `PIDS` names it reads). The **Rules** tab (`RulesTab`, third chip on the main screen) lists them with their hold time and each input's poll rate from `Poller.pollRate()`; `TREND_RULES` describes the `trends()` checks. A new rule must fill all three, and `ChecksTest` fails if a `uses` name isn't in the table.
- **Display units:** rule text, warning text and thresholds stay metric in `Checks.kt`; they are converted only when shown. `Prefs.units()` (`domain.model.Units`) drives `Pid.format(v, units)` in All data, the numeric conversions on the Overview, and `Units.text(about)` in `RulesTab` (which is rebuilt on each visit). Stored alert text stays metric, so the hub payload and old alerts stay comparable; `MainActivity.alertRow()` and `DpfService.alertOpened()` convert it at display/notification time.
- **Thresholds:** each rule's `about` names its source. "Estimate" marks the ones taken from other Toyota diesels or generic OBD logic, because no 1ND-TV figure was found. Tighten them once real drives show the normal range.
- **Toyota repair manual:** where the 1ND-TV manual (Yaris RM, read 21 Oct) gives a figure, the rule uses it and its `about` says so. Before a threshold was tightened, the owner's 21 Oct logs were checked against it so it doesn't fire on a healthy car.
  - The page versions used are the 2011-2014 ones, matching the owner's Nov 2013 car. Soot: regen may start above 77 %, needed at 100 %, P2463 over 149 %. 2015+ cars use 105/118/173 %.
  - The EGR closed learned value's 0.7 V lower limit is left out: the owner's car reads 0.48 and Toyota's own test car 0.52.
  - `TOYOTA_NORMAL` (`Normal.kt`) holds the manual's normal value per `PIDS` name. All data shows it under each value; `ChecksTest` checks that every name exists.
- **Alerts:** `Db.syncAlerts()` (table `alert`, schema v5) keeps one open row per active key. It closes the row when the key goes away and returns newly opened keys. The service notifies each of those on the low-importance "Health warnings" channel. When the value recovers, the row is closed (kept as history and synced) and its notification cancelled. Each key notifies at most once per drive (`notified`), so a value flapping around its limit doesn't spam; `showing` tracks which notifications are still up.
- **`diffAlerts()`:** the open/close/update decision is a pure function in `Checks.kt`, tested in `ChecksTest`. `Db.syncAlerts()` only applies it.
- **When alerts close:** a drive outlives a Bluetooth drop, like a regen does. `checker`, `notified` and the open live alerts live on the `DriveSession`. `idle()` resets them, and closes the live alerts, once the ECU has been silent (`lastSeen`) for more than `REGEN_GAP_MS`. So a flaky dongle neither re-notifies a warning nor splits its row. Regen-history alerts stay open while they apply.
- **Overview:** the status card at the top shows "All good", "Warning" or "Check the car now", with the open alerts only. Recovered ones leave the dashboard.
