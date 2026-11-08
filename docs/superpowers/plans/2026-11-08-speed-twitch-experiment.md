# v3.9.2 — fuel fix + speed-twitch experiment switches

## Why
Drive of 2026-11-08 (v3.9.1, client/server data ZIPs):
- **Fuel shows 0 L.** `FUEL` (`0x100600`) returns an integer number of millilitres in the float's raw
  bits: raw `0x7467` = 29799 → 29.8 L, falling 29.8 → 29.4 → 28.9 L as range falls 364 → 336 km.
  Read as a float it's 4.2E-41, which rounds to 0.
- **Yandex Navigator on the head unit twitches its speed number** now and then. On the phone it's steady.
  The user thinks it started in some 3.7 release.
  - Raw phone GPS speed vs the car's wheel speed ×1.028: p1 −3.5, p99 +2.3 km/h. Good.
  - Injected speed vs the car: median 0.3 km/h, p95 2.4 km/h. About 60 spikes over 4 km/h in 35 min.
  - Typical spike: GPS speed wobbles ±2 km/h. The acceleration ramp from 3.7.4 (`06f8fb4`) then extends
    it over a full second and jumps back at the next fix (641 s: 30.9 → 27.1, then +7.8).
  - `setMockLocation()` takes 2–40 ms per call (median 17 ms), so delivery jitters on the 100 ms tick.
    If Yandex works out speed from position changes using the time it receives each update, that jitter
    shows up as speed noise.
- We can't see which signal Yandex uses, so the next drive is an A/B/C test.

## Changes
1. `CarSignals.fromRaw`: fuel decoding. If the raw bits are a positive subnormal (`1 < bits < 0x00800000`),
   read them as integer millilitres and convert with `bits / 1000f` L. Otherwise keep the float as is.
   That covers other units and the existing tests. The wake-up zero-hiding still applies after decoding.
   Unit test with `Float.intBitsToFloat(29799)` → 29.799 L.
2. Experiment switch **"Speed ramp between fixes"**:
   - Pref `filterSpeedRamp`, default on.
   - `FilterConfig.speedRamp` is applied on every fix with `kalman.setAccelerationExtrapolation(...)`.
     The filter already has this setter; nothing calls it today.
   - Added to Advanced smoothing, under adaptive smoothing, and to "Reset to recommended".
3. Experiment switch **"Output once per second"**:
   - Pref `filterOutput1Hz`, default off.
   - When on, `smoothingTick` uses a 1000 ms interval instead of 100 ms. Tick-jitter metrics use the
     current interval.
   - The fixed-grid resync already handles an interval change.
   - Added to Advanced smoothing and to "Reset to recommended".
4. Experiment switch **"Send speed to apps"**:
   - Pref `filterSendSpeed`, default on.
   - When off, the injected location carries position and bearing but no speed and no speed accuracy.
     If Yandex still shows a speed, it works the speed out itself from positions.
   - `MockLocationManager.isStatic` now also requires that the location has no bearing. Without that, a
     moving location without a speed would get the parked-car jitter.
   - The track `out` row still records the filter's own speed.
5. A log line (`Output switches: …`) each time a switch change takes effect, on the next fix. This tells
   the runs apart in the export.
6. EN and RU strings for all three switches.

## Not doing
- No change to the default output behaviour. The fix depends on the result of the test drive.
- No new track columns. The `out` rows already show the rate and the speed.

## Test drive (user)
Use one drive with track recording on. Switch every 2–3 minutes and repeat the cycle (ABAB), so traffic
and sky conditions affect every setting alike:
- A: defaults.
- B: speed ramp off.
- C: output once per second.
- D: send speed off. Does Yandex still show a speed? Does it twitch?

Note roughly when Yandex twitched, then export the data ZIP.

Plan reviewed by Sonnet: approve-with-fixes. Changes taken: the D arm, the switch log line, ABAB ordering,
the tick-jitter metric using the current interval, and reset restoring the defaults.

## Verify
- Local junit stub: `CarSignalsTest` (fuel), plus the existing tests.
- CI green, release `v3.9.2` with both APKs.
