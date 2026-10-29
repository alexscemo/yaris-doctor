# Changelog

All notable changes to Yaris Doctor. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
versions follow [Semantic Versioning](https://semver.org/). Each release's GitHub notes are its section below, so a tag
without a section here fails the release build.

## [Unreleased]

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
