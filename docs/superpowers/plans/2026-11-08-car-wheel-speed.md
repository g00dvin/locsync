# v3.10.0 — car wheel speed as the filter's speed measurement

## Why
- **Test drive with v3.9.2.** With "send speed" off, Yandex works out its own speed and it twitches by
  ±5–10 km/h. With defaults it doesn't. So Yandex **displays the Location speed field we inject**.
- **Replay of the 2026-11-08 08:00 drive** (35 min, city and highway). The simulation reproduces the
  injected speed within 0.03 m/s at p99.
  - The 3.7.4 speed ramp is the best variant tried. Removing it doubles the spikes over 4 km/h (421 → 859).
  - The remaining spikes come from the phone's raw GPS speed. Example at 914–919 s: GPS read 28–37 km/h
    while the wheels read 38–42, with GPS claiming speed accuracy of 0.3 m/s.
  - Phone Yandex likely uses the phone's fused provider. We relay the raw `gps` provider.
- **Wheel speed** (`CarSignals.SPEED`) has no multipath glitches.
  - On average GPS reads 1.028 × wheel. The ratio is steady across speed bands.
  - Cross-correlation: GPS speed lags wheel speed by about 400 ms.
- **User decision:** use wheel speed × an auto-calibrated factor as the speed measurement, with GPS speed
  as the fallback. Keep the three experiment switches.

## Design
1. **`VehicleData`** gains a poll interval (constructor argument) and a small speed history.
   - The service polls at **200 ms**. MainActivity stays at 1000 ms.
   - `Float speedKmhAt(long elapsedMs)` interpolates linearly between the two samples around `t`.
     It returns null when the gap between them is over 600 ms, or when `t` is more than 300 ms past the
     newest sample.
   - Pure logic lives in a new `CarSignals.SpeedHistory`: a ring of 32 entries, unit-tested. Writes come
     from the poll thread and reads from the main thread, so access is synchronized.
2. **The service runs one `VehicleData`** (`vehicle`) from start to stop.
   - It is a no-op on units without AdaptAPI (static `sdkMissing`).
   - The track recorder uses the same instance. The separate `trackVehicle` start/stop goes away.
   - The Activity's reader keeps its own connection. Two connections already ran fine on the 08:52 drive,
     with recording on.
3. **Calibration:** new `CarSignals.WheelCalibration`, pure and unit-tested.
   - The factor is GPS ÷ wheel. It starts from the persisted value (`carSpeedFactor`, default 1.0) and is
     clamped to [0.9, 1.1].
   - It updates on each fix only when all of these hold. The windows were tightened after review; see
     "After plan review" below.
     - GPS speed is over 40 km/h.
     - GPS speed accuracy is between 0 and 0.5 m/s.
     - The wheel speed changes by less than 1.5 km/h between 0.5 s before and 0.5 s after the measurement
       time. Steady driving means the 400 ms lag doesn't bias the ratio.
     - The ratio is within ±0.08 of the current factor (rejects GPS glitches). The first 20 updates use a
       wider window of ±0.15.
   - EMA with α = 0.05.
   - Persisted every 60 accepted updates and on service stop. One log line each time it's persisted.
4. **Fusion in the fix handler.** When the "Use car speed" switch is on (`filterCarSpeed`, default on) and
   `vehicle.speedKmhAt(measElapsed)` is non-null:
   - speed = wheel × factor / 3.6 m/s, with speed accuracy **0.15 m/s** (a calibration knob, as a constant).
   - The bearing still comes from GPS. The filter applies velocity only when there is a bearing, as today.
   - Standstill: a wheel speed of 0 is below the standstill threshold, so it holds as today.
   - Calibration uses the GPS speed before it is replaced.
   - Otherwise the GPS speed is used unchanged.
5. The track `fix` row keeps the raw GPS `speed` and `car_speed`. `f_speed` shows the fused result.
   No new columns.
6. Advanced smoothing gets the **"Use car speed"** switch, under adaptive smoothing. It's included in
   "Reset to recommended", with EN and RU strings. The `Output switches` log line includes it.

## After plan review (Sonnet: approve with 4 fixes, plus one addition)
- **Polling:** the poller reads only `SPEED` between full reads, and does a full read every second. The
  stale-read reconnect counts full reads only, so it still triggers after 5 s.
- **Calibration windows tightened:**
  - Wheel steady within 0.5 km/h over the last second. The 1 s lookback replaces ±0.5 s, because future
    samples don't exist yet when a fix arrives.
  - Ratio within ±0.04 (±0.15 for the first 20 updates).
  - Wheel speed of at least 40 km/h.
- **Output:** `injectSmoothed` takes its speed from the live wheel speed × factor when the newest sample is
  at most 300 ms old. Position still comes from the filter. This removes the per-fix steps and the
  400 ms GPS lag from what Yandex shows.
- **Source switch** between wheel and GPS: accepted as a one-fix blip in the ramp. Marked with a
  `ponytail:` note.
- **Lifecycle:** the service's `VehicleData` is created and stopped on the main thread. Calibration is
  saved on stop.
- **Steering angle** (`0x101000`, user question): added to the raw log line only, to learn whether it's
  readable.
- **Branch review** (Sonnet: approve after one fix). All three findings fixed:
  - The "moving" show threshold also requires the filter to be moving, so pulling away can't inject a
    north bearing.
  - The speed-only sample is added under the `VehicleData` lock, so `stop()` can't be overtaken.
  - Stale comment corrected.
- **Replay** of drive 1 (Java, the real filter, wheel speed interpolated at 1 Hz):

  | | p99 | Spikes over 4 km/h | Max |
  |---|---|---|---|
  | Before | 5.95 km/h | 418 | 13.3 km/h |
  | After | 2.65 km/h | 54 | 9.2 km/h |

  The result is flat for speed accuracy between 0.1 and 0.5 m/s.

## Not doing
- Dead reckoning in tunnels with wheel speed (follow-up).
- Calibrating the lag. Sampling at the measurement time is right, and GPS is the one that lags.
- Showing the speed source in the Monitor.

## Verify
- Unit tests:
  - `SpeedHistory`: interpolation, gap and stale cutoffs, ring wrap.
  - `WheelCalibration`: convergence to 1.028 from 1.0, glitch rejection, clamp.
- Replay of the drive-1 track in Python, with wheel speed fed into the filter. Expect the p99 error and the
  count of spikes over 4 km/h to drop.
- CI green, release v3.10.0.
