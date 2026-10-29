# Changelog

All notable changes to Yaris Doctor. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
versions follow [Semantic Versioning](https://semver.org/). Each release's GitHub notes are its section below, so a tag
without a section here fails the release build.

## [1.2.0] - 2026-10-29

The car's own odometer replaces GPS: every reading, regen, engine frame and warning now carries the mileage it happened
at.

It also brings 1.1.1's fill-up fix to the phone: that release was published without its APK.

### Added

- **The car's odometer, read from the dashboard.** The instrument cluster broadcasts it in whole miles; the app listens
  for it for about 1.5 s at the start of each drive and every 5 minutes after, and adds up the engine's vehicle speed in
  between, so the figure stays within the mile the dash shows. Confirmed on the car: 89,159 mi on 29 Oct.
- **Mileage on everything:** readings, regens, engine frames and warnings store the odometer at their time, so the log is
  a timeline by mileage as well as by time. The AI export shows it on every trip, regen, warning and reading (in miles and
  km), says whether each figure is the car's or an estimate, and lists every odometer read.

### Changed

- **No more GPS.** Distance between regens, the trip summary, the Maintenance tab's mileage and reminders, and fill-ups
  all use the car's odometer. The Maintenance tab shows it as read from the dashboard, and "Set mileage" goes away once it
  has been read. The location permission is kept only for the home hub's Wi-Fi check: Android shows apps the Wi-Fi name
  only with it.
- **Your history moved onto the car's odometer.** The odometer check you ran on 29 Oct read 89,159 mi, so every older
  row was shifted to match: the log now runs from about 88,812 mi (20 Oct). Those older figures are estimates (the
  phone's GPS distance, counted back from that reading) and are marked as such in the export.
- Two phones on the same drive no longer count its distance twice: the car has one odometer.

### Fixed

- **Releases from a tag typed with a capital V** (`V1.2.0`) now build: they used to be skipped, so the release had no APK.

### Upgrade notes

- The database moves to version 9. History is kept and moved onto the car's odometer; older engine frames get their
  mileage in the background after the next drive.
- **Update the home hub first** (`yaris-doctor-hub` with the odometer migration): this version sends the odometer with
  frames and warnings, which an older hub refuses. The phone then sends its whole history again, now with the car's
  mileage.

## [1.1.1] - 2026-10-29

### Fixed

- **Fill-up mileage:** a fill-up logged after driving on was stored at the mileage of the moment you saved it, not where
  you filled up. It now takes the mileage at the stop where the tank's level jumped, read from the instrument cluster.
  To correct one logged before this, open it in the Maintenance tab and tap Save.

### Changed

- The fuel level read from the cluster is confirmed on the car: byte × 0.5 L matched a real fill-up (32.5 L seen for
  34.7 L put in; a full tank reads about 39 L).

## [1.1.0] - 2026-10-28

Fuel: what's in the tank, what the engine burns, and what you spend on it.

### Added

- **Fuel tile** on Overview: the fuel left in the tank, read from the instrument cluster about once a minute while
  driving. Tap it to log a fill-up. Not yet confirmed on the car: if the cluster doesn't answer, the tile stays blank.
- **Fuel use tile** on Overview: L/100 km, or US mpg in imperial units, over the last 5 minutes of driving, and L/h
  while idling. After a drive it keeps showing the end of the last drive. It is an estimate from the engine ECU's
  injection volume and is not yet checked against real fill-ups.
- **Fill-ups** in the Maintenance tab: log litres and the amount paid (`,` or `.` both work). Shows what you spent this
  month, this year and in total, the average price per litre, and the last 10 fill-ups. Kept on the phone only: not
  synced to the hub and not in the AI export.
- **Odometer probe** (Settings → Developer): on the next drive, the app asks the engine and the cluster for the
  odometer and listens to the car's network for about 30 s, then turns itself off. The answers go in Export database.
  It is there to find where this car keeps its odometer.

### Upgrade notes

- The database moves to version 8 (a new `fuel` table). Existing history is kept.

## [1.0.0] - 2026-10-28

The first stable release: a maintenance logbook with reminders and the car's mileage, and a new bottom navigation.
The code is the same as 1.0.0-rc.1; these notes cover everything since 0.19.3.

### Added

- **Maintenance tab:** a logbook of the car's maintenance, past and planned, with reminders.
  - Log a job (oil and filter, the filters, brake fluid, coolant, brakes, tyres, battery, DPF, inspection, or your own)
    with its date, odometer reading and a note. Past jobs can be backdated.
  - Each job can remind you again after a distance, a number of months or both, whichever comes first. The next job of
    the same kind reuses the interval. Oil defaults to Toyota's 15,000 km.
  - Plan a job for a date and/or an odometer reading; mark it done when it is.
  - **Coming up** lists every reminder, overdue first. An overdue one shows as a warning on Overview and as a
    notification, like the other health checks.
- **Mileage:** enter the dashboard odometer once and the app adds the GPS distance of every logged drive. A job logged
  today with an odometer reading corrects it.

### Changed

- **Navigation:** the tabs moved from the chip row at the top to a bottom navigation bar with icons, ordered Overview,
  Data, Maintenance, Rules. Each tab keeps its scroll position, tapping the open tab scrolls it to the top, and Back
  returns to Overview before leaving the app.
- "All data" is now called **Data**.
- The oil change moved from Settings into the Maintenance tab. The **Oil change due** warning is now **Maintenance
  due**, and covers every reminder.
- Restore database also replaces the maintenance logbook.
- The **Maintenance due** warning lists as many jobs as fit the home hub's 300-character alert limit and counts the
  rest, so a long list never stops the sync.

### Upgrade notes

- The database is upgraded in place (schema 6 to 7). An oil change recorded in Settings becomes the logbook's first
  entry, with the 15,000 km reminder still counting from the same point.
- Backups made by 0.19.x still restore. They have no logbook, so restoring one keeps the logbook already on the phone.

### Known limitations

- The logbook stays on the phone: it is not synced to the home hub and not part of the AI export.
- Reminders are checked when logging starts, when a regen or a drive ends, and right after an edit made while not
  driving. A reminder with only a date shows up at the next drive.

## [1.0.0-rc.1] - 2026-10-28

Release candidate for 1.0: the first release with a maintenance logbook and the new navigation. It becomes 1.0.0
once it has run on the car without problems.

### Added

- **Maintenance tab:** a logbook of the car's maintenance, past and planned, with reminders.
  - Log a job (oil and filter, the filters, brake fluid, coolant, brakes, tyres, battery, DPF, inspection, or your own)
    with its date, odometer reading and a note. Past jobs can be backdated.
  - Each job can remind you again after a distance, a number of months or both, whichever comes first. The next job of
    the same kind reuses the interval. Oil defaults to Toyota's 15,000 km.
  - Plan a job for a date and/or an odometer reading; mark it done when it is.
  - **Coming up** lists every reminder, overdue first. An overdue one shows as a warning on Overview and as a
    notification, like the other health checks.
- **Mileage:** enter the dashboard odometer once and the app adds the GPS distance of every logged drive. A job logged
  today with an odometer reading corrects it.

### Changed

- **Navigation:** the tabs moved from the chip row at the top to a bottom navigation bar with icons, ordered Overview,
  Data, Maintenance, Rules. Each tab keeps its scroll position, tapping the open tab scrolls it to the top, and Back
  returns to Overview before leaving the app.
- "All data" is now called **Data**.
- The oil change moved from Settings into the Maintenance tab. The **Oil change due** warning is now **Maintenance
  due**, and covers every reminder.
- Restore database also replaces the maintenance logbook.
- The **Maintenance due** warning lists as many jobs as fit the home hub's 300-character alert limit and counts the
  rest, so a long list never stops the sync.

### Upgrade notes

- The database is upgraded in place (schema 6 to 7). An oil change recorded in Settings becomes the logbook's first
  entry, with the 15,000 km reminder still counting from the same point.
- Backups made by 0.19.x still restore. They have no logbook, so restoring one keeps the logbook already on the phone.
- This is a pre-release: in Obtainium, turn on "Include prereleases" for Yaris Doctor to receive it.

### Known limitations

- The logbook stays on the phone: it is not synced to the home hub and not part of the AI export.
- Reminders are checked when logging starts, when a regen or a drive ends, and right after an edit made while not
  driving. A reminder with only a date shows up at the next drive.

## Earlier versions

Releases up to [0.19.3](https://github.com/alexscemo/yaris-doctor/releases/tag/v0.19.3) have their notes on GitHub.

[1.0.0]: https://github.com/alexscemo/yaris-doctor/compare/v0.19.3...v1.0.0
[1.0.0-rc.1]: https://github.com/alexscemo/yaris-doctor/compare/v0.19.3...v1.0.0-rc.1
