# Record every usable head-unit source (v3.11.1, record only)

**Goal:** On the next drive, record every source that could improve position and reaction, so we can decide what to feed the
filter: the car's W4m motion group, every other AdaptAPI sensor that reports, the raw accelerometer, the gyro temperature, and
the head unit's own GNSS receiver. **The filter and output are unchanged.**

## Where the candidates come from (recon 2026-11-07 + `ecarx.adaptapi.jar` decode, engram #708)
- **W4m:** `ISensor.getSensorGroupLatestValue(0x800400)` → `IW4mValue`. It has `getYawRate`, `getLatAcc`, `getLonAcc`,
  `getVFL/VFR/VRL/VRR`, `getSteerAngle`, `getGearState`, `getTickTime`, `getInterval`.
  - It is backed by the HAL props `0x21607b53`/`b47`/`b49` and `0x2160729a`/`9d`/`a0`/`a2`. They are 0.0 parked; whether they
    move while driving is unknown.
- **AdaptAPI sensor census:** there are 102 `ISensor.SENSOR_TYPE_*` constants. Which of them report on the G426 is unknown,
  apart from speed, gear, fuel, range, temperature and odometer.
  - `isSensorSupported(type)` returns `FunctionStatus` (`active`/`notactive`/`notavailable`/`error`).
- **Android sensors:** the raw SMI130 accelerometer (today only the gravity estimate is kept), and the SMI230 gyro temperature
  (`TYPE_AMBIENT_TEMPERATURE`, on-change). The temperature tests whether the gyro bias (0.175 → 0.096 °/s over a drive) follows
  temperature.
- **Head-unit GNSS:** a real HAL is running (`vendor.autolink.gnss_service`, kernel "GNSS driver registered"), but it is hidden
  while our mock owns `gps`.
  - Record the non-mock fixes, NMEA (it may still flow while mocking, and may carry proprietary DR sentences) and satellite
    status.
  - Real fixes appear at least while our mock is off (before connect, and after Wi-Fi loss).

## Design
1. **`VehicleData` (poll thread):**
   - **On connect, a census.** Reflect over the `ISensor` `SENSOR_TYPE_*` fields. For each, log one line with the name, the hex
     id, `isSensorSupported`, the latest float and the event int.
     - Types that are not `notavailable`/`error` form the **probe set**.
     - Log the W4m group's first value and `getMountAngle()`.
   - **Each speed poll (5 Hz):** also read W4m into a pure `CarSignals.W4m` (9 floats, plus tick and interval). It is null when
     the call fails or returns null.
   - **Each full read (1 Hz):** read the probe set into `CarSignals.ProbeDiff`. That writes `hex=value` pairs for values that
     changed since the last row, and every value every 60 s.
   - `SpeedListener.onSpeed(long t, Float kmh, CarSignals.W4m w4m, String probe)`. `probe` is null on speed-only polls.
2. **`TrackRecorder.car(...)`** gains columns appended to the header:
   `w4m_tick,yaw_rate,lat_acc,lon_acc,v_fl,v_fr,v_rl,v_rr,w4m_steer,w4m_gear,acc_x,acc_y,acc_z,gyro_temp,probe`.
   - Other row kinds leave them empty, so the column count matches on every row.
   - The probe string uses `;` between pairs, so it never contains a comma.
3. **The service** registers `TYPE_AMBIENT_TEMPERATURE` with the IMU listener and keeps the latest raw accel x/y/z and
   temperature. The `car` row writes them.
4. **New `GnssRecorder`** writes `gnss-client.csv` through `CsvLog`, capped at 10 MB. It is only active while "Record track" is on.
   - `fix` rows: Android `Location` fields, with `mock=0/1`. Fixes from our own mock are skipped.
   - `status` rows: at most 1 Hz. Satellites seen and used, mean C/N0 of the top 4 used, constellations.
   - `nmea` rows: the raw sentence as the last column.
   - The service requests `gps` updates at 1 s, plus an NMEA listener and a `GnssStatus.Callback`, on its handler while recording.
   - It logs `getAllProviders()` once.
5. **No filter or output change.** The motion-fusion switch and everything else stay as they are.

## Tests (TDD)
- `CarSignalsTest`:
  - `probeDiffWritesChangesAndFullEveryMinute`;
  - `w4mNullWhenAllNaN`.
- `TrackRecorderTest`:
  - `carRowWritesW4mImuAndProbe`, with the column count checked;
  - the existing alignment test still passes.
- `GnssRecorderTest`:
  - `rowsMatchHeader`;
  - `nmeaKeepsCommas` (the sentence is the last column, verbatim);
  - `statusAtMostOncePerSecond`.

## Check on the car (with "Record track" on)
- The log has the census lines and the W4m first value.
- In `track-client.csv`, `car` rows have W4m values that move while driving (yaw rate in turns, `v_fl`…`v_rr` near the wheel
  speed), plus `gyro_temp` and `probe`.
- `gnss-client.csv` exists. Does it have NMEA or status while LocSync is mocking? Does it have `fix mock=0` rows?
