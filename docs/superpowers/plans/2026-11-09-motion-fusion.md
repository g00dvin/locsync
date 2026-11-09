# Motion fusion (gyro + wheels + GPS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Between GPS fixes and through GPS loss, move the injected location with the head unit's gyro heading and the car's wheel
distance (signed by the gear). GPS corrects the drift, so the icon reacts to turns and speed changes at once and keeps moving in
tunnels.

**Architecture:** Two new pure-Java classes, run on the service thread only.
- `HeadingTracker` integrates the gyro yaw into a heading. It learns the bias at standstill and is pulled towards the GPS bearing.
- `DeadReckoner` holds an **incremental** position. Each output tick it walks from its last time to now, using the current heading
  and the wheel speed. Each fix re-anchors it at the Kalman state for the fix time and re-walks only measT → now (about 1.5 s, within
  the 6.4 s wheel history).

The Kalman filter still fuses GPS. It keeps the raw GPS bearing as its measurement (no double counting) and takes the gyro heading
change for its predict step only. A short linear slide hides each re-anchor jump.

**Tech Stack:** Java (AGP default language level; no `compileOptions` set), Android minSdk 28 (head unit API 30), JUnit 4 via the
local junitstub runner, no new dependencies.

**Spec:** the "Data" and "Decisions" sections below, plus the 2026-11-09 analysis (engram obs #703 and #704). The analysis scripts
are in `/tmp/claude-1000/-home-goodvin-cityray/e5257877-b5cd-47ad-83dd-96afa44150be/scratchpad/scripts/{an,turns,dr2}.py`.

## Data (2026-11-09 19:07 drive, v3.10.7: the only drive with gyro so far)
- **Gyro clock:** `gyro_dt_ms` median -22 ms, on the elapsedRealtime base.
- **Bias:** +0.10..+0.17 °/s, changing during the drive (0.175 → 0.127 → 0.096 at three stops). Standstill noise is 0.06 °/s
  (1 s windows).
- **Scale and sign:** gyro/GPS heading change is 0.98 over 29 turns larger than 25° (within noise of 1.0). The sign matches the
  bearing.
- **Lag:** gyro and wheel both lead the GPS bearing and speed by about 200 ms.
- **Wheel speed:** vs GPS rms 0.27 m/s, distance ±0.5%. The CSV holds only the 1 Hz snapshot; the service polls at 5 Hz.
- **Steering:** `car_steer_bits` is always 0. It is unusable.
- **GPS-cut simulation:**
  - Setup: dead reckoning on wheels plus gyro, bias 0.15, start heading the gyro-aligned speed²-weighted mean of 20 s of GPS
    bearings.
  - Error p50/p90/max: 10 s 1.4/3.5/12 m, 30 s 3.6/10/28 m, 60 s 6.4/25/55 m, 120 s 17/48/81 m.
  - The error is almost all start heading. A 0.05 °/s bias error doubles the 60 s error.
- **Replay baseline** (current filter, `+ acceleration ramp` row):
  - 19:07 drive: next-fix p50/p90 1.22/3.09 m, turns p50 1.73 m, heading in turns p50/p90 6.1°/28.9°, snap p90 0.52 m,
    jumps 138.
  - 10:22 drive (no gyro): 0.96/3.25 m, heading in turns 9.3°/23.1°.

## Decisions (after the Sonnet review, 2026-11-09)
- **Dead reckoning is incremental, not re-walked from the last fix.** The wheel history (`CarSignals.SpeedHistory`) holds 32
  samples, which is 6.4 s.
- **Everything runs on the service thread.** It pulls the wheel speed with `vehicle.speedKmhAt(t)`; the vehicle poll thread pushes
  nothing.
- **The Kalman filter keeps the raw GPS bearing.** The gyro feeds only `predict` (the heading change over that exact dt).
  `extrapolate` never uses the gyro, so its fallback path stays today's.
- **Reverse:** gear `"R"` (`CarSignals.Snapshot.gear`) negates the wheel speed for dead reckoning, and the tracker ignores GPS
  bearings while in R.
- **Cut for v1:**
  - moving-bias learning;
  - the standstill-threshold override;
  - the heading/sigma/bias track columns.
- **Added:** 5 Hz `car` rows (wheel speed and gear) in the track CSV, so the next drive replays faithfully.
- **Gyro scale** is a constant `GYRO_SCALE = 1.0` (calibration knob).
- **Unwrapped heading:** the tracker stores an unwrapped heading, so 359→0 doesn't break interpolation.
- **Dead reckoning needs both switches.** It requires "Use car speed" and "Use car motion" both on.
- **Default:** "Use car motion" ships **off** in v3.11.0. The user turns it on for test drives. The default flips to on in a
  follow-up, once a second gyro drive passes the gates. (Open question for the user; see the end of the plan.)

## Global Constraints
- **Pure classes stay Android-free.** `HeadingTracker`, `DeadReckoner`, `LocationKalmanFilter` and `GyroYaw` take times in ms on the
  `SystemClock.elapsedRealtime()` base, and angles as bearings in degrees (clockwise from north) unless the name says `Rad`/`Ccw`.
- **Today's path stays byte-for-byte.** With no gyro sensor, no wheel speed, "Use car speed" off, or "Use car motion" off, the output
  must be today's: `kalman.extrapolate` plus the 2.5 s freeze. That includes the 1 Hz output mode.
- **New switch:** "Use car motion" (`filterCarMotion`), default **off**, placed after "Use car speed" in Advanced smoothing. It is
  included in "Reset to recommended" (to its default) and in the `Output switches` log line, with EN and RU strings.
- **Commits** carry no `Co-Authored-By`, `Claude-Session` or "Generated with" lines. PR bodies have no attribution lines.
- **Local test runner:** the junitstub runner lives in an old session's scratchpad.
  - At the start, copy it: `cp -r /tmp/claude-1000/-home-goodvin-cityray/4db3641e-3d52-4c04-9bd8-d78f402c690b/scratchpad/junitstub
    $S/` and `cp -r /tmp/claude-1000/-home-goodvin-cityray/3107817b-c6be-4a43-8d93-8a75be8d7421/scratchpad/assume $S/`. `$S` is the
    current scratchpad.
  - Run with `$S/junitstub/run.sh <outdir> <test classes> -- <source files>`. JDK: `/opt/jdk-21.0.12.1+1/bin/java`.
  - `C=client-app/src/main/java/goodvin/locsync/client`, `T=client-app/src/test/java/goodvin/locsync/client`, run from the repo
    root.
- **Replay drives:**
  - `G=/tmp/claude-1000/-home-goodvin-cityray/e5257877-b5cd-47ad-83dd-96afa44150be/scratchpad/cli/track-client.csv` (gyro).
  - `/tmp/claude-1000/-home-goodvin-cityray/3107817b-c6be-4a43-8d93-8a75be8d7421/scratchpad/cli/track-client.csv` (no gyro).
  - Copy both into `$S` at the start. Never commit tracks.
- **Line numbers** in this plan are as of `main` at `8696dce`. Re-grep before editing.

## Review Focus
1. **Reversing out of a space.** Gear R: dead reckoning moves backwards and the tracker ignores the GPS bearing. Tests:
   `DeadReckonerTest.reverseMovesBackwards` and `HeadingTrackerTest.gpsIgnoredInReverse`. Replay can't test it: old CSVs have no gear.
2. **Gyro stream stops** (sensor paused, thread stalled). After `GAP_INVALID_MS = 400` without a yaw sample the tracker is invalid.
   This is below `GyroYaw.MAX_STEP_S` = 0.5 s, so no unintegrated hole goes unnoticed. The output falls back to `kalman.extrapolate`
   until GPS re-initialises the tracker. Test: `HeadingTrackerTest.gyroGapInvalidates`.
3. **Wheel speed goes missing mid-drive** (AdaptAPI reconnect, `speedKmhAt` null). `DeadReckoner.step` returns false and the caller
   falls back. Test: `DeadReckonerTest.missingWheelSpeedStops`.
4. **Bad persisted bias** (NaN, ±50). It is clamped to [-1, 1] °/s, and NaN gives 0. Test: `HeadingTrackerTest.biasClamped`.
5. **GPS returns far from the dead-reckoned spot** after a tunnel. `setState` skips the χ² gate for one update, so the far fix isn't
   rejected 5 times. The slide snaps instead of sliding when the jump is over 50 m. Tests:
   `LocationKalmanFilterTest.setStateThenFarFixIsAccepted` and `DeadReckonerTest.slideSkipsLargeOffsets`.

---

### Task 1: Replay harness reads gyro and wheels; GPS-gap metric; split halves

**Files:**
- Modify: `client-app/src/test/java/goodvin/locsync/client/TrackReplayTest.java`

**Interfaces:**
- Produces:
  - `Fix` gains `long measT` (filled in `replay`).
  - `static final class Motion { long[] yawT; double[] yaw; long[] wheelT; double[] wheelMps; String[] gear; }` comes from
    `static Motion loadMotion(Path p)`. It returns **null when the file has no `gyro_yaw_deg` values**: "no gyro" means
    `Motion == null`.
    - Yaw: `fix` and `out` rows, at elapsed_ms + gyro_dt_ms, sorted, duplicates dropped.
    - Wheel: `car` rows (Task 6 format) when present, else the `fix` rows' `car_speed` at elapsed_ms + car_dt_ms. Wheel is in m/s
      (× 1.0249 / 3.6).
  - `static double wheelAt(Motion m, long t)` mimics `SpeedHistory`: linear between samples ≤ 1500 ms apart (600 ms with `car`
    rows), held 300 ms past the newest, else NaN.
  - `Result` gains `List<Double> gap30, gap60`: the distance from the last output to the first fix after an artificial gap.
  - `replay(List<Fix>, Motion, boolean useWheels, int gapS, long fromT, long toT, Consumer<LocationKalmanFilter>)`.
    - `fromT`/`toT` limit the fixes scored, for the first-half/second-half split.
    - The old `replay(...)` delegates with `null, false, 0, MIN, MAX`, and networkSync stays as is.
- **Gap scenario (`gapS > 0`):**
  - Gap starts slide through the drive: every 60 s, at the first fix with speed ≥ 4 m/s and acc ≤ 10.
  - Each gap runs **as its own replay**, so gaps don't interact. The fixes with arrival in `(t0, t0 + gapS*1000]` are dropped.
  - Output ticks keep running through the gap (today's pipeline freezes after 2500 ms).
  - Score the first fix after the gap, then stop that replay.
  - Report p50 and max.

- [ ] **Step 1:** Implement the above. With `useWheels`, when `wheelAt(m, measT)` is not NaN, it replaces the fix speed in
  `kf.update` with speed accuracy 0.15, as `GNSSClientService:766-771` does.
  - New rows: `"+ wheel speed (as shipped)"`, plus that row's gap30 and gap60 figures, all for full / first half / second half.
- [ ] **Step 2:** Compile and run on both drives.
  - Expected: the existing rows equal the "Data" baseline.
  - New rows print. Put the numbers in the PR description; they are the bar for Task 5.
- [ ] **Step 3: Commit** `test: replay reads gyro and wheel speed, adds GPS-gap metric and half splits`

### Task 2: `HeadingTracker`: gyro heading with standstill bias, pulled to GPS

**Files:**
- Create: `client-app/src/main/java/goodvin/locsync/client/HeadingTracker.java`
- Test: `client-app/src/test/java/goodvin/locsync/client/HeadingTrackerTest.java`

**Interfaces:**
- Produces:
  - `HeadingTracker(double initialBiasDps)`. The bias is clamped to [-1, 1]; NaN gives 0.
  - `void onYaw(long tMs, double rawYawDeg)`: `heading += GYRO_SCALE * (raw - prevRaw) - bias * dt`. The heading is kept unwrapped.
  - `void onWheel(long tMs, double mps)`: standstill bias learning. While |mps| < `STILL_MPS = 0.05` for at least 2 s, 1 s rate
    windows feed an EMA with α 0.1.
  - `void onGpsBearing(long tMs, double speedMps, double bearingDeg, boolean reverse)`.
    - `tMs` is the GPS measurement time minus `GPS_LAG_MS`. `bearingDeg` already has the +0.5° quantisation fix when the filter
      reports `isBearingQuantized()`.
    - Ignored in reverse and below 3 m/s.
    - Init (when invalid): heading = bearing at speed ≥ 4 m/s.
    - Correction: if |error| ≤ 45°, heading and its short history shift by `GPS_GAIN * min(1, (speed-3)/5)`, with
      `GPS_GAIN = 0.3`.
    - 3 consecutive fixes at ≥ 8 m/s with |error| > 45° force a re-init (guards a bad init).
  - `boolean valid(long nowMs)`: initialised, and the last yaw within `GAP_INVALID_MS = 400`.
  - `double headingDeg()`: the current heading, wrapped to [0, 360).
  - `double headingAt(long tMs)`: from a 5 s ring at a 50 ms step, wrapped, NaN outside.
  - `double deltaRadCcw(long fromMs, long toMs)`: the bias-corrected heading change, CCW-positive; NaN outside the ring.
  - `double sigmaDeg(long nowMs)`: `hypot(2.0, 0.03 * secondsSinceLastCorrection)`.
  - `double biasDps()`.
- Constants: `GPS_LAG_MS = 200` (Task 5 may change it), `GYRO_SCALE = 1.0`, and the values above.

- [ ] **Step 1: Write the failing tests:**
  - `straightWithBiasStaysOnCourse`: bias 0.15, yaw drifting +0.15 °/s, init at 90 at 10 m/s; after 60 s, `headingDeg` within 0.5°
    of 90.
  - `learnsBiasAtStandstill`: start bias 0, wheel 0 for 30 s, yaw +0.12 °/s ± 0.06 noise (seed 1); `biasDps` within 0.02 of 0.12.
  - `turnFollowsGyroBeforeGps`: init at 0, yaw -90 over 3 s, no GPS; heading within 1° of 270.
  - `wrapsAcrossNorth`: from 350, a +20° yaw gives `headingAt` 10 at the end and 0 at the midpoint; `deltaRadCcw` is -0.349.
  - `deltaRadCcwSign`: yaw +10° in 1 s gives -0.1745.
  - `gpsIgnoredInReverse`: heading 0, GPS 180 at 3 m/s with `reverse=true`; heading unchanged.
  - `gyroGapInvalidates`: no `onYaw` for 450 ms gives `valid` false.
  - `biasClamped`: `new HeadingTracker(50).biasDps() == 1.0`; NaN gives 0.
  - `gpsPullsSlowly`: heading 0 vs GPS 10 at 10 m/s; one fix moves it 3°, and it's within 1° after 7 fixes.
  - `badInitRecovers`: init at 0, then 3 fixes of 120 at 10 m/s; heading 120.
- [ ] **Step 2: Run, expect FAIL.**
  `$S/junitstub/run.sh $S/t2 goodvin.locsync.client.HeadingTrackerTest -- $T/HeadingTrackerTest.java $C/HeadingTracker.java`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `client: HeadingTracker, gyro heading with standstill bias and GPS correction`

### Task 3: `DeadReckoner`: incremental position, anchor at fixes, slide

**Files:**
- Create: `client-app/src/main/java/goodvin/locsync/client/DeadReckoner.java`
- Test: `client-app/src/test/java/goodvin/locsync/client/DeadReckonerTest.java`

**Interfaces:**
- Consumes: `HeadingTracker.headingDeg`, `headingAt`, `sigmaDeg`.
- Produces:
  - `interface WheelSpeed { double mpsAt(long tMs); }`. NaN = unknown. The value is signed: negative in reverse, already
    calibrated.
  - `DeadReckoner(HeadingTracker h, WheelSpeed w)`.
  - `boolean anchor(double lat, double lon, double accM, long atMs, long nowMs)`.
    - It sets the position at `atMs` and walks to `nowMs` in 100 ms steps, using `headingAt` and `mpsAt` at each step's midpoint.
    - It returns false (and becomes inactive) if a step has no heading or speed.
    - It resets the distance since anchor to 0 and keeps `accM`.
  - `boolean step(long nowMs)`.
    - It walks from the last time to `nowMs` in one segment (ticks are 100 ms; up to 1 s in 1 Hz mode), with the current
      `headingDeg` and `mpsAt(nowMs)`.
    - It returns false and becomes inactive on NaN.
  - `boolean active()`.
  - `long anchoredAtMs()`.
  - `void fill(LocationKalmanFilter.Estimate out, long nowMs)`. It fills a reused `Estimate`:
    - `speed = |wheel|`, `bearingDeg = headingDeg`;
    - `accuracy = accuracy68 = hypot(accM, 0.01*d, d*sin(toRadians(sigmaDeg(nowMs))))`, where d = distance since anchor;
    - `speedAccuracy = 0.15`, `bearingAccuracyDeg = sigmaDeg`.
  - `static final class Slide`:
    - `void start(double dEastM, double dNorthM, long tMs)`, where the offset = old output minus new output at the same tick.
    - It is dropped (snap) above `MAX_SLIDE_M = 50`.
    - `void apply(LocationKalmanFilter.Estimate e, long tMs)` adds the offset, decaying linearly to 0 over `SLIDE_MS = 1000`.
- Distance uses flat-earth 111320 m/deg, with cos(lat) for longitude, as `LocationKalmanFilter` does.

- [ ] **Step 1: Write the failing tests:**
  - `straightLine`: heading 90, 10 m/s, 100 steps of 100 ms → 100 m east ±0.5.
  - `anchorRewalksToNow`: anchor 1500 ms back at 10 m/s north → 15 m north of the anchor.
  - `quarterCircle`: heading 0→90 over 9 s at 10 m/s → the closed-form arc end within 1 m.
  - `reverseMovesBackwards`: heading 0, wheel -1 m/s, 5 s → 5 m south.
  - `accuracyGrowsWithDistance`: acc 3, 1000 m, sigma 2° → `hypot(3, 10, 34.9)` ±0.5.
  - `missingWheelSpeedStops`: NaN speed → `step` false, `active()` false.
  - `slideDecays`: (3, 0) → (1.5, 0) at 500 ms, 0 at 1000 ms.
  - `slideSkipsLargeOffsets`: 60 m → 0 at once.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `client: DeadReckoner walks wheel distance along the gyro heading`

### Task 4: `LocationKalmanFilter`: gyro heading change in predict, and re-anchor

**Files:**
- Modify: `client-app/src/main/java/goodvin/locsync/client/LocationKalmanFilter.java` (`predict` ~470, new `setState`, the gate in
  `updateInternal`)
- Test: `client-app/src/test/java/goodvin/locsync/client/LocationKalmanFilterTest.java`

**Interfaces:**
- Produces:
  - `void predict(double dt, double dThetaRadCcw)`.
    - NaN means `predict(dt)`, today's behaviour.
    - Otherwise it propagates with `w = dThetaRadCcw / dt`.
    - The old `predict(dt)` is unchanged, and `extrapolate` is not touched.
    - `updateTurnRate` keeps running, so `omega` is warm for the fallback.
  - `void setState(double lat, double lon, double speed, double bearingDeg, double posSigmaM, double velSigmaMps)`.
    - Implemented as `reset()` followed by the init branch of `updateInternal` with those values.
    - It sets `skipGateOnce`, so the next update isn't gated or counted as an outlier.

- [ ] **Step 1: Write the failing tests:**
  - `predictWithHeadingChange`: north at 10 m/s, `predict(1, -0.5)` → bearing 28.6° ±0.5.
  - `predictNaNIsTodaysBehaviour`: equal to `predict(1)` to 1e-12, for state and `extrapolate(1)`.
  - `setStateThenFarFixIsAccepted`: `setState` 120 m from a fix with posSigma 40; after 3 updates, `getOutlierCount()` +0 and the
    position within 5 m of the fix.
- [ ] **Step 2: Run, expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.** All existing `LocationKalmanFilterTest` tests still pass.
- [ ] **Step 5: Commit** `client: Kalman predict takes the gyro heading change; setState re-anchors`

### Task 5: Fusion in the replay, tune on the first half, validate on the second

**Files:**
- Modify: `client-app/src/test/java/goodvin/locsync/client/TrackReplayTest.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `replayFused(List<Fix>, Motion, int gapS, long fromT, long toT, int gpsLagMs)`, the reference glue Task 6 ports step for
  step.
  1. **Feed:** before each fix and each tick, feed the tracker `onYaw` with all yaw samples up to that time. Feed `onWheel(t,
     wheelAt(t))` at each tick.
  2. **Predict:** call `kf.predict(dt, tracker.valid(measT) ? tracker.deltaRadCcw(stateTime, stateTime + dt*1000) : NaN)`. Use the
     handler's own dt (`fixTs` difference, else the meas difference, capped at 5 s). NaN on the first fix after a reset.
  3. **Tracker:** `tracker.onGpsBearing(measT - gpsLagMs, f.speed, f.bearing + (kf.isBearingQuantized() ? 0.5 : 0), reverse)`
     when the fix has a bearing.
  4. **Update:** `kf.update` exactly as today (raw GPS bearing, wheel speed per Task 1).
  5. **Gap logic:**
     - `drActive` = `dr.active()` and `t - lastFix ≤ 120_000` and the DR accuracy ≤ `DR_MAX_ACC_M = 60`.
     - `resumingAfterGap` = `!drActive && gap > 2500`, which resets as today.
     - If `drActive` and the gap is over 2500: before the update, back the DR position off from now to measT along the heading
       (`v·Δt`). Then `kf.setState(that, speed |wheel|, heading, posSigma = DR accuracy, velSigma 0.5)`.
  6. **Re-anchor:** after each update, `est0 = kf.extrapolate(0)`; `dr.anchor(est0.lat, est0.lon, est0.accuracy, measT, t)`.
     - If DR was active, `slide.start(lastOut - drNow)` at this tick.
     - If the tracker is invalid or the wheels are missing, the DR is inactive.
  7. **Output tick:**
     - If `dr.step(t)` and `drActive`, the output is `dr.fill` plus `slide.apply`.
     - Else, if `t - lastFix ≤ 2500`, today's `kf.extrapolate(h)`.
     - Else freeze.

- [ ] **Step 1:** Add rows `"+ motion fusion lag 0/200/400"`, full / first half / second half, gaps 0, 30 and 60.
- [ ] **Step 2: Tune on the first half only.**
  - Pick the lag; keep the other constants at defaults unless a gate fails.
  - If one fails, try GPS_GAIN 0.2 or 0.5, then stop.
  - Record every tried value and its result in the PR description.
- [ ] **Step 3: Validate on the second half and the full drive.** Gates, against `+ wheel speed (as shipped)` in the same run:
  - heading in turns p90 at least 30% lower;
  - turns next-fix p50 and straight next-fix p50/p90 at most +5%;
  - snap p90 at most +20%;
  - jumps not higher;
  - gap30 p50 ≤ 5 m and max ≤ 30 m;
  - gap60 p50 ≤ 10 m and max ≤ 60 m.
  If the second half fails a gate the first half passed, report to the user and stop. Don't retune on the second half.
- [ ] **Step 4:** On the no-gyro drive (`Motion == null`), the fusion rows must equal `+ wheel speed (as shipped)` exactly.
- [ ] **Step 5:** Set `GPS_LAG_MS` to the chosen value. **Commit** `test: replay with motion fusion, tuned on the 2026-11-09 drive`
  - The PR description states that the replay is optimistic: 1 Hz wheel and 10 Hz yaw, vs 5 Hz and 50 Hz live.

### Task 6: Service wiring, switch, 5 Hz car rows

**Files:**
- Modify:
  - `GNSSClientService.java`:
    - `imuListener` 134-148: it returns early without a recorder, so rewrite it.
    - Sensor registration 1040-1055: it currently sits inside `updateTrackRecorder`. Move it to transport start/stop.
    - The fix handler ~676-790, `smoothingTick` ~803-850, and stop/save ~427 and ~1029.
  - `TrackRecorder.java`: `TrackRecorder(File dir, GyroYaw gyro)` with a shared gyro; new `car` row; `out_src` column.
  - `VehicleData.java`: a hook that reports each speed poll for the recorder. It is called on the poll thread and only writes to the
    recorder's queue, the same way `TrackRecorder` is already written from the service thread. Check how `TrackRecorder` is
    synchronized before choosing; if it is not thread-safe, post to the service handler.
  - `FilterConfig.java`, `Preferences.java` (`filterCarMotion` default false, `gyroBiasDps` default 0.15).
  - `MainActivity.java` (toggle after `rowCarSpeed` at ~581), `res/layout/view_settings.xml`, `res/values{,-ru}/strings.xml`.
- Test: `client-app/src/test/java/goodvin/locsync/client/TrackRecorderTest.java`

**Interfaces:**
- Consumes: the Task 5 `replayFused` glue.
- Produces:
  - CSV `car` rows: `wall_ms,elapsed_ms,car,…` with `car_speed` and a new `car_gear` column at the end.
  - Column `out_src` (`dr`/`kf`) on out rows.
  - Task 1's `loadMotion` reads both.

- [ ] **Step 1:** Add `TrackRecorderTest.headerHasNewColumns`, `carRowWritesSpeedAndGear` and `outRowWritesSource`. Run, expect
  FAIL. Implement. Run, expect PASS. `TrackReplayTest.loadMotion` parses a 3-line sample with `car` rows; add that as a test too.
- [ ] **Step 2:** Make `GyroYaw` a service field, registered at transport start when `filterCarMotion` or recording is on.
  - The listener feeds `GyroYaw`; on gyro events it then calls `tracker.onYaw(e.timestamp / 1_000_000, gyro.yawDeg())`.
  - Log once per start the difference between `SystemClock.elapsedRealtimeNanos()` and the first event timestamp. If it exceeds
    1 s, log a warning and disable the tracker for the session, because the clock base is wrong.
- [ ] **Step 3:** Port the `replayFused` steps 1–7.
  - `WheelSpeed` = `vehicle.speedKmhAt(t) * wheelCalibration.factor() / 3.6`, negated when `vehicle.snapshot().gear` is `"R"`; NaN on
    null.
  - Dead reckoning runs only when both `filterConfig.carSpeed` and `filterConfig.carMotion` are on.
  - `gpsLossCapMs()` keeps its meaning for the old path. The new `drActive` decides the dead-reckoning path.
  - `injectSmoothed(est)` is unchanged. Check by hand that `outputMoving` uses the DR `est.speed` (wheel) and that a stop hides the
    speed and bearing as today.
- [ ] **Step 4:** Persist the bias: load `gyroBiasDps` at start, save on stop and every 60 standstill updates. Log
  `Gyro bias %.4f °/s saved`.
- [ ] **Step 5:** Add the switch.
  - EN: "Use car motion" / "Between GPS fixes, follow turns and speed with the head unit's gyroscope and the wheels, and keep
    moving for a short while (about a minute) when GPS is lost. Needs \"Use car speed\"."
  - RU: "Движение машины" / "Между точками GPS — повороты и скорость по гироскопу ГУ и колёсам; при потере GPS — движение ещё
    около минуты. Нужна «Скорость машины»."
  - Add it to Reset to recommended and the `Output switches` line.
- [ ] **Step 6:** Run the full local suite (the s1–s3 commands in `docs/handoffs/2026-11-09-1143-v3107-gyro-steer-recording.md`,
  with paths moved to `$S`, plus the new test classes). Expect all to pass.
- [ ] **Step 7: Commit** `client: fuse gyro heading and wheel distance into the output, keep moving through GPS loss`

### Task 7: Review, PR, release (standing process)
- [ ] Sonnet review (Agent, `model: sonnet`, read-only) of the branch diff against this plan. Fix its findings.
- [ ] Push `feat/motion-fusion` and open the PR in the #43–#45 format, with the Task 1/5 numbers. Then `gh pr checks N --watch`.
- [ ] Ask the user. Then merge, tag v3.11.0, verify the release, `mem_save`, handoff, stop.
- [ ] Car checks for the user, with the switch **on** and "Record track" on:
  - junction turns: the icon turns with the car;
  - the parking end-of-ride case;
  - reversing out of a space;
  - one tunnel or underground entry and exit;
  - switch off: same as v3.10.7.
  - Then send the zips. The next drive re-runs the Task 5 gates with 5 Hz wheels, before the default flips to on.

## Open question for the user
- Should "Use car motion" ship **off** (safer; the user enables it for test drives) or **on** in v3.11.0? The plan assumes off,
  following the review.
