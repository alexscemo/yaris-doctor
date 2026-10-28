---
paths:
  - "**/Sync.kt"
  - "**/SyncTest.kt"
  - "**/HubDefs.kt"
  - "**/Db.kt"
  - "**/Regens.kt"
---

# Home hub sync

- The server is a separate public repo, [yaris-doctor-hub](https://github.com/alexscemo/yaris-doctor-hub) (image `ghcr.io/alexscemo/yaris-doctor-hub`). Its `/api/sync` payload shape must stay in step with `Sync.kt` + `Db.samplesAfter()` / `Db.regenRows()`.
- `Sync.maybe()` is called once a minute from the service's idle loop. It uploads only when `Prefs.server` and `Prefs.wifi` are both set (both empty by default) and the current SSID matches. SSID comes from `Sync.watch()`'s NetworkCallback with `FLAG_INCLUDE_LOCATION_INFO`, which needs fine + background location. It uploads when there are new samples (by SQLite `rowid`) or the regen signature changed, and waits 15 min after a failure. It always reads `container.real`, so Debug data never leaves the phone. `usesCleartextTraffic` is on because hubs are plain-http LAN servers.
- **Two-way:** after uploading, `Sync.run()` pages through the hub's `GET /api/export?device=<own id>&after=<cursor>` (cursor `Prefs.PULLED_ROWID`, the hub's sample rowid). It stores the other phones' rows in `peer_sample` / `peer_regen` (schema v3) through `Db.putPeers()`, ignoring re-sent samples and replacing the whole peer regen list. `Sync.maybe()` also pulls every 10 min on the home Wi-Fi when there is nothing to send. Changing the hub address resets both cursors.
- **Deleted trips:** `Sync.queueDeletes()` stores their windows in `Prefs.PENDING_DELETES`, only when a hub is set. The first sync request carries them as `deleted: [[from, to]…]`. The hub removes that phone's samples, frames, alerts and regens in them before storing the rest of the request, which resends the phone's open alerts. Its reply's `deleted` key clears the queue. The hub keeps the windows (table `deleted`) and `/api/export` sends every other phone's as `deleted: [[device, from, to]…]`; `Db.putPeers()` drops the `peer_sample` rows in them. A hub without that key keeps them queued (`HUB_DELETES`, like `HUB_FRAMES`).
  - **Rowids:** `sample` and `frame` have no AUTOINCREMENT, so deleting the newest trip makes SQLite reuse its rowids. `queueDeletes()` therefore winds `SYNCED_ROWID` / `SYNCED_FRAME_ROWID` back to the highest rowids left (`deleteTrips()` returns them). The hub keeps its own sample rowids above any ever used (`meta.sample_top`), because the other phones' `PULLED_ROWID` pages by them. A hub from before deletes ignores the re-sent rowids: it has to be updated.
- **The main screen shows the whole car:**
  - `lastSample`, `sootSeries`, `regens` and `metresSinceRegen` read own and peer rows together.
  - Car distance between two instants is `driven()`: the sum of each phone's odometer progress, as in the hub's `distance_m`.
  - `mergeRegens()` (tested in `SyncTest`) counts a hand-over burn once, but never merges open regens. `mergeSameBurn()` then joins this car's own regens that an ignition cycle split (within 30 min and with no soot reset), so the screen and gap math see one burn; `regenRows()` still sends the raw two rows to the hub.
  - `odometer()` stays own-only, because the service restores its GPS odometer from it.
- **Regens and DPF condition:** the first sync request carries `regens_all: true` with the phone's whole regen list, and the hub deletes that phone's regens missing from it (engine-stop blips the phone dropped). It also carries `dpf` (`Db.dpfRows()`), which the hub stores whole per phone and charts. The hub's status card shows open alerts only, like the Overview.
- **Frames, alerts and the PID table:** the first sync request also carries `alerts` (`Db.alertRows()`, open plus the last 30 days, resent whole when `alertSignature()` changes) and `defs` (`pidDefs()`, which the hub decodes frames with, so the formulas live only in `PidTable.kt`). Frames follow in pages of `BATCH`, at most `MAX_PAGES` per run, with cursor `Prefs.SYNCED_FRAME_ROWID`. The hub only accepts 2-character lids, so `framesAfter()` and `maxFrameRowid()` both skip the `A918` ash record rows. With only one of the two filtered, a sync would be rejected or would look pending forever.
  - **Hubs from before frames:** they drop these fields silently. They are detected by the missing `frames` key in the sync reply and stored in `Prefs.HUB_FRAMES`. The frame cursor then doesn't move, and frames/alerts don't count as pending, so the phone doesn't sync every minute; the 10-minute pull rechecks.
  - **Hub side:** the hub keeps every frame and never thins them: it is the full-detail backup. So with a hub set, `Db.thinFrames()` stops at the oldest frame after `SYNCED_FRAME_ROWID` (same lid filter as `framesAfter()`); unsent frames stay at full rate until they reach the hub. The hub upgrades its schema with numbered `MIGRATIONS` (`PRAGMA user_version`). It serves `/api/live` and `/api/series`, and its dashboard has a Live data view and the same status card as the Overview.
  - **Rule descriptions:** the first request also carries `rules` (`ruleDefs()`: key, title, level, description and value names for every `Checker.RULES` and `TREND_RULES`). The hub's warning detail shows them, with a chart of each value around the warning.
  - **Units stay metric on the wire:** `pidDefs()` unit strings, `ruleDefs()` descriptions, DPF-condition values and alert text are canonical metric. Each phone converts at its own display edge with `Prefs.units()`, so two phones with different unit choices can share one hub without corrupting stored data or each other's screens.
  - **All warnings:** a hub that never got this phone's warnings (`SYNCED_ALERTS` unset: first sync, or a new address) gets all of them. After that, the last 30 days.
  - **"All synced" notification:** the automatic sync (`Sync.maybe` → `run(c, notify = true)`) posts one low-importance "All synced with the hub" notification when it uploaded readings, frames or changed warnings. "Sync now" in Settings shows a toast instead.
  - **Phone side:** phones don't download other phones' frames or alerts.
- `hubUrl()` normalises what the user types ("192.168.1.50" → `http://192.168.1.50:8765`); it is unit-tested in `SyncTest`.
