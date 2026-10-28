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

- **Ash record** (`Poller.ASH_RECORD`, `A9 18`, in `SLOW` but read every `ASH_MS` = 1 min, so its counters can be tied to what the car did between reads): the ECU's ash estimate, which the repair manual calls the "DPF Deterioration Record". The reply is `E9 18` + 13 bytes, the last being the XOR of the other 12 (`ashRecordOk()`). The format is undocumented, so it's stored raw as frame lid `A918`. The Overview's DPF card shows the newest read as three counters. The app never writes it (write format below).
  - **Decoding** (byte layout confirmed 24 Oct 2026 by a write + read-back; the counter meanings are inferred): the 12 bytes are three big-endian 32-bit counters A, B, C, all **time counters**, none an ash mass. With the ignition on and the engine off (owner's 9 reads, 21 Oct 17:39-17:59) A rose ~3.8 per second while B and C stood still; while driving C rose ~1 per second. So A = time with the ECU awake (~0.26 s ticks), C = engine running seconds, B = exactly 20 × C (50 ms ticks of the same). If the ECU derives its ash estimate from these, a zeroed C means "no engine hours on this DPF". The Mileage Write zeroed **B and C** only: its A = 648 is just the ~170 s the ECU stayed awake before the read-back, so the written mileage left no trace in this record. Last read before the write: `0025 5D02 00C0 0724 0009 99F5 FC` (17:59:14).
  - **Owner's history:** the DPF pressure sensor was replaced and the record cleared on 19 Mar 2026, at ~82-83k miles; ~88k miles in Oct 2026. That fits C as engine seconds: 175 h over ~5,500 miles (~8,850 km) is ~51 km/h on average, ~49 min of driving a day.
  - **First real write, 24 Oct 2026:** `3B 18 00` + 12 bytes is stored as given for **B and C**; **A comes back re-based** (wrote 2,448,642, read back 2,449,690 one second later, then +3.75/s), so the ECU adds its own awake ticks to the stored A and A can never be set exactly. Do not judge a write by A.
  - **`01 DD` and `01 EF` do not answer with the engine off** (`01 05` and `01 10` do), so any before/after PM-ratio test has to be done at a warm idle, not at ignition-on.
- **Calibration ID** (`Poller.CAL_ID`, OBD mode 09 PID 04): no longer asked (removed 28 Oct 2026). Older builds read it once ever and stored it raw as frame lid `0904` (`49 04 <count>` dropped to `<count>` + 16 ASCII bytes per ID; the hub skips it like `A918`). Those rows stay: `Db.frames()` still maps the lid and the AI export still prints the newest one.

## Write format (from the vehicle's local-ID writes, 23-24 Oct; never sent by the app)

`3B 18 <mode> <payload> <XOR of payload>`, positive reply `7B 18`:
- mode 00: payload = the 12 record bytes, as `A9 18` reads them. Record Clear is this write with 12 zero bytes (16-byte request).
- mode 01: payload = mileage / 10 km (2 bytes). The vehicle's mileage write sends it; on this car it zeroed B and C (A stayed the live awake counter).
- 16 bytes is a multi-frame CAN request: an ELM327 can't send it in normal mode, so a restore needs a CAN pass-through cable.

## Restore plan (step 1 done, then undone by the Mileage Write)

The laptop tool is a small C# console app (built with the .NET Framework `csc`; `--simulate` fakes the ECU). It backs up, writes one step, reads back and logs everything to a text file beside it. Opening it writes the next step after a 10 s countdown; `--read` only reads.

**Status 24 Oct 2026:** step 1 (`3B 18 00 00 25 5D 02 00 C0 07 24 00 09 99 F5 FC`) was written at 18:00 and accepted (`7B 18`, B and C verbatim, read back at 18:02:52). Four minutes later a mileage write to 100001-150000 km (`3B 18 01 3A 98 A2`) **zeroed it again**: mode 01 sets the distance-based estimate *and* clears B and C. The car now carries the 150,000 km estimate and a record at 0 h, so the next run writes step 1 again.

**Order matters: Mileage Write first, record restore last.** Never run the Mileage Write after a restore. No session or security frame precedes `3B 18 01` in the captured log, so the local-ID writes go through unlocked on a cold channel, which is why the tool works with other diagnostic software closed.

1. Write back the last pre-write record: `3B 18 00 00 25 5D 02 00 C0 07 24 00 09 99 F5 FC` (C = 629,237 s = 175 h since 19 Mar), with a backup read first and a read-back after.
2. Compare the next 2-3 regens with 21 Oct (~40 km apart, soot back to ~40 % right after): if they improve, the record feeds the ash estimate. For a fast check use `--read` at warm idle before and after an IG cycle — `01 DD` does not answer with the engine off.
3. Only then raise C towards the full-life value in steps (+500 h at a time, B = 20 × C, A ≈ 3.9 × C). Full life at ~51 km/h over 88k miles (~141,600 km) is ~2,800 h (C ≈ 10.1 M). Overshooting is the dangerous side: too much assumed ash means late regens on a heavy soot load. The measured DPF pressure is low (~60 % of Toyota's limit), so stay under the full-life value.
