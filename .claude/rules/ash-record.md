---
paths:
  - "**/Poller.kt"
  - "**/PollerTest.kt"
  - "**/DriveSession.kt"
  - "**/DriveSessionTest.kt"
  - "**/Db.kt"
  - "**/OverviewViewModel.kt"
---

# DPF deterioration (ash) record and calibration ID

Full notes on the Techstream side (decompiled routines, J2534 logs): `../techstream/RM_NOTES.md`, "DPF Deterioration Record".

- **Ash record** (`Poller.ASH_RECORD`, `A9 18`, in `SLOW` but read every `ASH_MS` = 1 min, so its counters can be tied to what the car did between reads): what Techstream calls "DPF Deterioration Record", which the repair manual describes as the ECU's ash estimate. It is the same read as Techstream's Learning Values Confirmation (J2534 log, 21 Oct). The reply is `E9 18` + 13 bytes, the last being the XOR of the other 12 (`ashRecordOk()`). The format is undocumented, so it's stored raw as frame lid `A918`. The Overview's DPF card shows the newest read, grouped like Techstream. The app never writes it (write format below).
  - **Decoding so far** (not confirmed): the 12 bytes are three big-endian 32-bit counters A, B, C, all **time counters**, none an ash mass. With the ignition on and the engine off (owner's 9 reads, 21 Oct 17:39-17:59) A rose ~3.8 per second while B and C stood still; while driving C rose ~1 per second. So A = time with the ECU awake (~0.26 s ticks), C = engine running seconds, B = exactly 20 × C (50 ms ticks of the same). If the ECU derives its ash estimate from these, a zeroed C means "no engine hours on this DPF". The Mileage Write zeroed all three: its A = 648 is just the ~170 s the ECU stayed awake before the Confirmation read, so the written mileage left no trace in this record. Last read before the write: `0025 5D02 00C0 0724 0009 99F5 FC` (17:59:14).
  - **Owner's history:** the DPF pressure sensor was replaced and the record cleared on 19 Mar 2026, at ~82-83k miles; ~88k miles in Oct 2026. That fits C as engine seconds: 175 h over ~5,500 miles (~8,850 km) is ~51 km/h on average, ~49 min of driving a day.
- **Calibration ID** (`Poller.CAL_ID`, OBD mode 09 PID 04): `DriveSession` adds it once a drive, to the cycle after the first live one (a miss before anything answered would end a probe), until `Db.hasFrame("0904")` finds one stored, so it's read once ever. Stored raw as frame lid `0904` (`49 04 <count>` dropped to `<count>` + 16 ASCII bytes per ID; the hub skips it like `A918`). It's there to look up which tools can read this ECU's flash; nothing displays it.

## Write format (decompiled from Techstream `UtilityExNK2.dll`, 23 Oct; never sent by the app)

`3B 18 <mode> <payload> <XOR of payload>`, positive reply `7B 18`:
- mode 00: payload = the 12 record bytes, as `A9 18` reads them. Record Clear is this write with 12 zero bytes (16-byte request).
- mode 01: payload = mileage / 10 km (2 bytes). Vehicle Mileage Write sends it; on this car it zeroed all three counters.
- 16 bytes is a multi-frame CAN request: an ELM327 can't send it in normal mode, so a restore needs the J2534 cable.

## Restore plan (not done)

The laptop tool is `../techstream/ashstep/` (`AshStep.exe`, C# built with the .NET Framework `csc`; `--simulate` fakes the ECU). It backs up, writes one step, reads back and logs everything to `ashstep_<date>_<time>.txt`.

1. Write back the last pre-write record: `3B 18 00 00 25 5D 02 00 C0 07 24 00 09 99 F5 FC` (C = 629,237 s = 175 h since 19 Mar), with a backup read first and a read-back after.
2. Compare the next 2-3 regens with 21 Oct (~40 km apart, soot back to ~40 % right after): if they improve, the record feeds the ash estimate.
3. Only then raise C towards the full-life value in steps (+500 h at a time, B = 20 × C, A ≈ 3.9 × C). Full life at ~51 km/h over 88k miles (~141,600 km) is ~2,800 h (C ≈ 10.1 M). Overshooting is the dangerous side: too much assumed ash means late regens on a heavy soot load. The measured DPF pressure is low (~60 % of Toyota's limit), so stay under the full-life value.
