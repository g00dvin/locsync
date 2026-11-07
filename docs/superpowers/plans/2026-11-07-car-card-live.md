# Car Card Live (Part 2b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the LocSync client's dormant car card show real head-unit data on the Geely G426 / eCarX unit: speed, gear, fuel (litres), range, outside temperature and odometer. When "Record track" is on, also write the car's speed into the track log next to every GPS fix, so GPS and car speed can be compared offline.

**Architecture:**
- **`CarSignals` (new, Android-free so JUnit can test it):** sensor IDs, sentinel handling, unit conversion, gear labels, the `Snapshot` value type and its assembly from raw readings, plus a "vendor SDK missing" classifier.
- **`VehicleData`:** keeps only AdaptAPI reflection, the polling thread, reconnect and lifecycle. It uses the correct read call per sensor kind:
  - continuous sensors: `ISensor.getSensorLatestValue(int)→float`
  - event sensors: `ISensor.getSensorEvent(int)→int`
- **`MainActivity.updateCarCard()`:** renders the new fields.
- **Track log:** `GNSSClientService` runs its own `VehicleData` while track recording is on (the activity's instance stops in `onPause`). `TrackRecorder` adds three columns to each `fix` row: wheel speed, cluster speed, and the signed time offset of the car reading from the fix arrival. `CsvLog` rotates a file whose header no longer matches, so old and new rows never mix.

**Tech Stack:** Java (Android), JUnit 4.13.2. AdaptAPI is reached by reflection on `com.ecarx.xui.adaptapi`, which sits on the head unit's boot classpath and is never a compile dependency.

**Spec:** this plan's "Signal facts" section, decoded from the car's `ecarx.adaptapi.jar` (recon of 2026-11-07; raw data kept outside git at `/home/goodvin/cityray/car-recon/`). Background: `docs/handoffs/2026-11-06-1259-car-signals-part2.md`. Reviewed by Sonnet 5.5 on 2026-11-07; this revision folds in its findings.

## Signal facts (the spec)

| Field | ISensor constant | ID | Read call | Decoding |
|---|---|---|---|---|
| speed | `SENSOR_TYPE_CAR_SPEED` | `0x100100` | `getSensorLatestValue` | m/s → km/h, ×3.6, absolute value |
| cluster speed (track log only, not on the card) | `SENSOR_TYPE_CAR_SPEED_FROM_IPK` | `0x101a00` | `getSensorLatestValue` | the speed the cluster displays, in m/s → km/h ×3.6, absolute value |
| gear | `SENSOR_TYPE_GEAR` | `0x200200` | `getSensorEvent` | `0x200230`=P, `0x200240`=R, `0x200210`=N, `0x200220`=D. `0x200201..0x20020a` = gears "1".."10". Anything else (incl. −1, `Integer.MIN_VALUE`) = no value. |
| fuel | `SENSOR_TYPE_FUEL_LEVEL` | `0x100600` | `getSensorLatestValue` | litres, as-is. Unit unverified; the user chose litres. |
| range | `SENSOR_TYPE_ENDURANCE_MILEAGE` | `0x100800` | `getSensorLatestValue` | km, as-is. 0 is a real value (empty tank). |
| outside temp | `SENSOR_TYPE_TEMPERATURE_AMBIENT` | `0x100b00` | `getSensorLatestValue` | °C, as-is. Unverified. |
| odometer | `SENSOR_TYPE_ODOMETER` | `0x100700` | `getSensorLatestValue` | km, as-is. `<= 0` = no value (a real car never reads 0). |

- **Unsupported reads:** `getSensorLatestValue` returns a float whose raw bits are exactly `0x1` (≈1.4E-45). Treat that, NaN and ±Infinity as "no value". `0f` is a real value. `getSensorEvent` defaults to −1.
- **Not readable on this unit:** RPM (`0x100900`) and coolant temperature (`0x100f00`). Remove both everywhere.
- **Hidden API:** AdaptAPI members are flagged "blacklist". A third-party app on this car calls them without a bypass, so enforcement is probably off on this ROM. If it is on, `getMethod` throws `NoSuchMethodException`.
- **Visibility and connection:** `CarImpl`, `Sensor` and `ISensor` are public. `com.ecarx.xui.adaptapi.binder.IConnectable` has `connect()` and `disconnect()`.

## Global Constraints

- No new dependencies. AdaptAPI stays reflection-only (`Class.forName`).
- Reflective binder calls stay off the main looper (existing `HandlerThread`). `stop()` runs only on the main thread.
- EN + RU strings for every user-visible text (`values/strings.xml`, `values-ru/strings.xml`).
- Commit messages are plain, with **no** `Co-Authored-By`, `Claude-Session` or "Generated with" trailers (user rule).
- Work on branch `feat/discovery-accuracy-car-card` (current).
- There is no Android SDK locally. Tests and the build run in CI (`./gradlew testDebugUnitTest`, `./gradlew assembleDebug`) or wherever JDK 21 and the Android SDK are installed.

## Review Focus

1. **Unit without AdaptAPI (non-eCarX unit, emulator).** `ClassNotFoundException`, `NoClassDefFoundError` or `ExceptionInInitializerError` must not crash the process. The card stays hidden, the reader shuts down for the session, and it logs one line. → Task 1 test `missingSdkIsDetected`; Task 2 steps 3–4.
2. **Car service restarted or not ready.** The reads keep returning the sentinel and never throw. After 5 consecutive empty polls the reader reconnects instead of staying blank forever. → Task 2 step 3.
3. **Sentinel or ignition-off zeros.** A sentinel never shows as "0", and odometer 0 never shows. → Task 1 tests `latestValueRejectsSentinel`, `snapshotFromRaw`.
4. **Fast pause/resume.** An old poll thread must not overwrite `latest` or the fields of the new connection. → Task 2 step 1 (local captures, `stopped` check before publishing).
5. **Hidden API enforced.** The first failure is visible in the log at info level, later ones at verbose. → Task 2 step 3.
6. **An existing `track-client.csv` with the old header** (users already record tracks): after the update, new rows must not be appended under the old header. → Task 3 test `headerChangeRotatesFile`.

**Speed comparison from recon (context for Task 3).** Above 40 km/h, the GPS speed of LocSync's mock fixes read about +2–3% above the car's wheel speed (`0x100100`); speed derived from fix positions read about +2%. The cluster reads about +4–5% above wheel speed. That was n=9 with ~2 s alignment error. Task 3 logs both car speeds at the GPS rate, with each reading's time offset, so the ratio can be measured properly.

---

### Task 1: `CarSignals` — pure IDs, decoders, snapshot

**Files:**
- Create: `client-app/src/main/java/goodvin/locsync/client/CarSignals.java`
- Test: `client-app/src/test/java/goodvin/locsync/client/CarSignalsTest.java`

**Interfaces** (`final class CarSignals`, package-private, no Android imports):
- `static final int SPEED = 0x100100, SPEED_IPK = 0x101a00, GEAR = 0x200200, FUEL = 0x100600, RANGE = 0x100800, OUTSIDE_TEMP = 0x100b00, ODOMETER = 0x100700;`
- `static Float latest(float raw)`: returns `null` for the bits-`0x1` sentinel (`Float.floatToRawIntBits(raw) == 1`), NaN or ±Inf; otherwise returns `raw`.
- `static Float speedKmh(Float mps)`: `null` → `null`, else `Math.abs(mps) * 3.6f`.
- `static String gearLabel(int code)`: per the Signal facts table; `null` when there is no value.
- `static boolean isMissingSdk(Throwable t)`: walks the cause chain and returns true if any link is a `ClassNotFoundException` or `NoClassDefFoundError`.
- `static final class Snapshot`, moved here from `VehicleData`:
  - Fields: `final Float speedKmh, speedIpkKmh, fuelLitres, rangeKm, outsideTempC, odometerKm; final String gear; final long readElapsedMs;`
  - `readElapsedMs` is the `SystemClock.elapsedRealtime()` when the values were read; it is 0 for `EMPTY`.
  - `boolean hasAny()` counts every value field, `speedIpkKmh` included.
  - `static final Snapshot EMPTY`
- `static Snapshot fromRaw(float speedMps, float speedIpkMps, int gearCode, float fuel, float range, float temp, float odo, long readElapsedMs)`: applies `latest` to each float, `speedKmh` to both speeds and `gearLabel` to the gear code. Odometer `<= 0` becomes `null`.

- [ ] **Step 1: Write the failing test** `CarSignalsTest` (JUnit 4, same style as `PhoneHealthTest`):

```java
static final float S = Float.intBitsToFloat(1);   // AdaptAPI "unsupported"

@Test public void latestValueRejectsSentinel() {
    assertNull(CarSignals.latest(S));
    assertNull(CarSignals.latest(Float.NaN));
    assertNull(CarSignals.latest(Float.POSITIVE_INFINITY));
    assertNull(CarSignals.latest(Float.NEGATIVE_INFINITY));
    assertEquals(0f, CarSignals.latest(0f), 0f);
    assertEquals(38.5f, CarSignals.latest(38.5f), 0f);
}
@Test public void speedIsAbsoluteKmh() {
    assertEquals(36f, CarSignals.speedKmh(10f), 1e-4f);
    assertEquals(36f, CarSignals.speedKmh(-10f), 1e-4f);   // reverse
    assertEquals(0f, CarSignals.speedKmh(0f), 0f);
    assertNull(CarSignals.speedKmh(null));
}
@Test public void gearLabels() {
    assertEquals("P", CarSignals.gearLabel(0x200230));
    assertEquals("R", CarSignals.gearLabel(0x200240));
    assertEquals("N", CarSignals.gearLabel(0x200210));
    assertEquals("D", CarSignals.gearLabel(0x200220));
    assertEquals("1", CarSignals.gearLabel(0x200201));
    assertEquals("2", CarSignals.gearLabel(0x200202));
    assertEquals("10", CarSignals.gearLabel(0x20020a));
    assertNull(CarSignals.gearLabel(0x20020b));           // past 10th
    assertNull(CarSignals.gearLabel(0x200200));           // base id, not a gear
    assertNull(CarSignals.gearLabel(0x200250));
    assertNull(CarSignals.gearLabel(-1));
    assertNull(CarSignals.gearLabel(Integer.MIN_VALUE));
}
@Test public void snapshotFromRaw() {
    CarSignals.Snapshot s = CarSignals.fromRaw(10f, 10.5f, 0x200220, 38f, 408f, 0.5f, 23275f, 1234L);
    assertEquals(36f, s.speedKmh, 1e-4f);
    assertEquals(37.8f, s.speedIpkKmh, 1e-4f);
    assertEquals("D", s.gear);
    assertEquals(38f, s.fuelLitres, 0f);
    assertEquals(408f, s.rangeKm, 0f);
    assertEquals(0.5f, s.outsideTempC, 0f);
    assertEquals(23275f, s.odometerKm, 0f);
    assertEquals(1234L, s.readElapsedMs);
    assertNull(CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, 0f, 1L).odometerKm);   // ignition-off zero
    assertEquals(0f, CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, 0f, 1L).rangeKm, 0f); // empty tank is real
    assertNull(CarSignals.fromRaw(S, S, -1, S, S, S, S, 1L).speedIpkKmh);
    assertFalse(CarSignals.fromRaw(S, S, -1, S, S, S, S, 1L).hasAny());
    assertTrue(CarSignals.fromRaw(0f, S, -1, S, S, S, S, 1L).hasAny());              // standing still is data
}
@Test public void missingSdkIsDetected() {
    assertTrue(CarSignals.isMissingSdk(new ClassNotFoundException("x")));
    assertTrue(CarSignals.isMissingSdk(new NoClassDefFoundError("x")));
    assertTrue(CarSignals.isMissingSdk(new ExceptionInInitializerError(new NoClassDefFoundError("x"))));
    assertTrue(CarSignals.isMissingSdk(new java.lang.reflect.InvocationTargetException(new ClassNotFoundException("x"))));
    assertFalse(CarSignals.isMissingSdk(new NoSuchMethodException("x")));    // hidden API: retry, not give up
    assertFalse(CarSignals.isMissingSdk(new IllegalStateException("x")));
}
```

- [ ] **Step 2: Run the test and expect FAIL** (compile error: `CarSignals` is missing).
Run: `./gradlew :client-app:testDebugUnitTest --tests '*CarSignalsTest'`

- [ ] **Step 3: Implement `CarSignals`** per the Interfaces block.
  - Use the GPL header from `VehicleData.java`.
  - Add a class Javadoc citing the source (`ecarx.adaptapi.jar` `Sensor.getSensorLatestValue`/`getGera`, recon 2026-11-07).

- [ ] **Step 4: Run the test and expect PASS.**
Run: `./gradlew :client-app:testDebugUnitTest --tests '*CarSignalsTest'`

- [ ] **Step 5: Commit**
```bash
git add client-app/src/main/java/goodvin/locsync/client/CarSignals.java client-app/src/test/java/goodvin/locsync/client/CarSignalsTest.java
git commit -m "client: decode eCarX AdaptAPI car signals (ids, sentinel, units, gear)"
```

### Task 2: Wire `VehicleData` and the car card to the real signals

**Files:**
- Modify: `client-app/src/main/java/goodvin/locsync/client/VehicleData.java` (rewrite: remove `Snapshot` and every `ID_*`, `UNSET`, `*_SCALE` and `TEMP_*` constant, `isConfigured()`, `decode()` and `rawInt()`)
- Modify: `client-app/src/main/java/goodvin/locsync/client/MainActivity.java:1005-1041` (`updateCarCard`), plus the `VehicleData.Snapshot` → `CarSignals.Snapshot` type
- Modify: `client-app/src/main/res/values/strings.xml:217-222`, `client-app/src/main/res/values-ru/strings.xml:214-219`

**Interfaces:**
- Consumes: everything in Task 1's Interfaces block.
- Produces: `CarSignals.Snapshot snapshot()`. The public surface (`VehicleData(Context, Handler, Runnable)`, `start()`, `stop()`) is unchanged.

- [ ] **Step 1: Connection and reads**
  - Fields:
    - `car`, `sensorManager`, `getLatest` and `getEvent` are `volatile`.
    - `latest` stays `volatile`.
    - Add `int emptyPolls`.
    - Add `boolean loggedRaw` and `boolean loggedFailure`, reset in `start()`.
  - `connect()`:
    - `Class.forName("com.ecarx.xui.adaptapi.car.Car").getMethod("create", Context.class)`.
    - Keep `car`, and keep the optional `IConnectable.connect()` as today.
    - Resolve `getLatest`/`getEvent` on `Class.forName("com.ecarx.xui.adaptapi.car.sensor.ISensor")`, not on `mgr.getClass()`. Invoke them on `mgr`.
  - `read()`:
    - Capture `final Object mgr = sensorManager` and the two `Method` refs locally.
    - Read the raw floats with `float rawLatest(Object mgr, int id)` and the gear with `int rawEvent(Object mgr, int id)`. On an invoke exception they return `Float.intBitsToFloat(1)` and `-1`, and log at `AppLog.v`.
    - Read `SPEED_IPK` like the other continuous sensors.
    - Build the result with `CarSignals.fromRaw(..., SystemClock.elapsedRealtime())`.
  - `poll()`: publishes `latest = snap` **only if `!stopped`**.

- [ ] **Step 2: Calibration log.**
  - On the first `read()` after each `connect()` (`!loggedRaw`), log one `AppLog.i(TAG, ...)` line with every raw value and its bits.
  - Format: `String.format(Locale.US, "raw speed=%s(0x%x) ipk=%s(0x%x) gear=0x%x fuel=%s(0x%x) range=%s(0x%x) temp=%s(0x%x) odo=%s(0x%x)", …)`, using `Float.floatToRawIntBits` for the hex.
  - Mark the line with `// ponytail: fuel litres / temp °C unverified; this line is the calibration knob`.

- [ ] **Step 3: Failure handling in `poll()`.** Catch `Exception | LinkageError e`.
  - **`CarSignals.isMissingSdk(e)`:**
    - log `AppLog.i(TAG, "AdaptAPI not present, car card disabled")`
    - set `stopped = true`, publish `Snapshot.EMPTY`, `notifyUi()`
    - `uiHandler.post(this::stop)` releases the thread on the main looper
    - do not reschedule
  - **Any other error:**
    - log at `AppLog.i` the first time (`!loggedFailure`, then set it), at `AppLog.v` after that
    - call `disconnect()` (step 4), set `sensorManager = null`, publish `EMPTY`, `notifyUi()`
    - reschedule after `RETRY_MS`
  - **Stale connection:** in the success path, if `!snap.hasAny()` increment `emptyPolls`; at `emptyPolls >= 5`, `disconnect()`, set `sensorManager = null` and reset the counter so the next poll reconnects. `hasAny()` resets it to 0.
    - `// ponytail: 5-poll heuristic; switch to IConnectWatcher if reconnects flap`

- [ ] **Step 4: `disconnect()` and `stop()`.**
  - `private void disconnect()`: if `car` is an `IConnectable` (same reflective check as `connect()`), invoke `disconnect()` inside try/catch (`Exception | LinkageError`), then set `car = null`.
  - `stop()`, main thread only: set `stopped`, remove callbacks, then post `disconnect()` to the worker before `quitSafely()`. Clear refs and set `latest = EMPTY` as today.
  - Remove the `isConfigured()` early return from `start()`.

- [ ] **Step 5: Strings**, in both locales:
  - Delete `car_coolant_fmt` and `car_gear_fmt`; the gear label is appended as-is.
  - `car_fuel_fmt` becomes EN `fuel %1$d L`, RU `топливо %1$d л`.
  - Add `car_odo_fmt`: EN `odo %1$,d km`, RU `пробег %1$,d км`. Grouping follows the resource locale: EN "23,275", RU "23 275".
  - Keep `car_title`, `car_range_fmt` and `car_outside_fmt`.

- [ ] **Step 6: `updateCarCard()`.**
  - Title: `car_title`, then speed (`%.0f` + `unit_kmh`, as today), then `s.gear`.
  - Detail, joined with " · " in this order: fuel (`Math.round`), range, outside temp, odometer (`Math.round` → `int`).
  - Change the Javadoc example to the EN form `"Car 54 km/h D" / "fuel 38 L · 408 km · out 1 °C · odo 23,275 km"`.
  - Remove the coolant branch.

- [ ] **Step 7: Build and run all unit tests.**
Run: `./gradlew :client-app:testDebugUnitTest :client-app:assembleDebug`
Expected: BUILD SUCCESSFUL.
Also run `grep -rn "coolant\|gearRaw\|fuelPercent\|isConfigured\|ID_SPEED\|car_gear_fmt\|VehicleData.Snapshot" client-app/src`. It must return no hits.

- [ ] **Step 8: Commit**
```bash
git add client-app/src/main/java/goodvin/locsync/client/VehicleData.java client-app/src/main/java/goodvin/locsync/client/MainActivity.java client-app/src/main/res/values/strings.xml client-app/src/main/res/values-ru/strings.xml
git commit -m "client: live car card from AdaptAPI (speed, gear, fuel L, range, temp, odometer)"
```

- [ ] **Step 9: On-car check** (the user does this: parked first, then driving with a passenger).
  - Install the debug APK and turn the ignition on. The card should appear.
  - Speed should match the cluster within ±2 km/h, and the gear should follow P/R/N/D.
  - Range (about 408) and odometer (about 23 275 km) should match the cluster.
  - `adb logcat -s VehicleData`: check the `raw …` line for the fuel unit (litres?) and temperature (°C?). Adjust only if they are wrong.
  - Toggle the screen and the app a few times to check for no crash and that the card comes back.

### Task 3: Car speed in the track log

**Files:**
- Modify: `shared/src/main/java/goodvin/locsync/shared/CsvLog.java` (`open()`, ~line 64)
- Test: `shared/src/test/java/goodvin/locsync/shared/CsvLogTest.java`
- Modify: `client-app/src/main/java/goodvin/locsync/client/TrackRecorder.java` (`HEADER`, `fix`, `out`)
- Test: `client-app/src/test/java/goodvin/locsync/client/TrackRecorderTest.java`
- Modify: `client-app/src/main/java/goodvin/locsync/client/GNSSClientService.java`: `updateTrackRecorder()` (~884), the recorder teardown (~326), and the `trackRecorder.fix(...)` call (~688)

**Interfaces:**
- Consumes: `CarSignals.Snapshot` (Task 1); `VehicleData(Context, Handler, Runnable)`, `start()`, `stop()`, `snapshot()` (Task 2).
- Produces:
  - `TrackRecorder.HEADER` ends with `…,turn_dps,sigma_a,car_speed,car_speed_ipk,car_dt_ms`.
  - `TrackRecorder.fix(...)` gains a trailing parameter `CarSignals.Snapshot car` (nullable).

- [ ] **Step 1: Write the failing tests.**
  - `CsvLogTest.headerChangeRotatesFile`:
    - Write one row with `new CsvLog(f, "a,b", 1<<20)`, then `close()`.
    - Open `new CsvLog(f, "a,b,c", 1<<20)`, write one row, `close()`.
    - Assert `f` line 0 is `"a,b,c"` and `f` has 2 lines.
    - Assert `f.getName()+".oldhdr"` exists and its line 0 is `"a,b"`.
    - Assert that a pre-existing `f.getName()+".old"` (create it with content `"keep"` before the second open) is **untouched**.
  - `TrackRecorderTest.rowsMatchHeaderColumns`:
    - Pass `null` as the last arg of the first `fix(...)` call.
    - Pass `CarSignals.fromRaw(10f, 10.5f, 0x200220, 38f, 408f, 0.5f, 23275f, 1500L)` to the second one (its `elapsedMs` is 2000).
    - The existing column-count loop stays.
    - Add: the second fix row ends with `",36.00,37.80,-500"` (the car was read 500 ms **before** the fix arrived).
    - Add: the first fix row's last three cells (`split(",", -1)`) are empty.
    - The out rows are covered by the existing column-count loop (28 columns).

- [ ] **Step 2: Run them and expect FAIL.**
Run: `./gradlew :shared:test --tests '*CsvLogTest' :client-app:testDebugUnitTest --tests '*TrackRecorderTest'`

- [ ] **Step 3: `CsvLog` header guard.**
  - In `open()`, when the file exists and is non-empty, read its first line.
  - If that line `!equals(header)`, rename the file to `<name>.oldhdr` (deleting any previous `.oldhdr`), then start a fresh file with the header.
  - Never touch `<name>.old`: it can hold up to 30 MB of size-rotated drive data.
  - One-line comment: a BOM or a partial header after a crash also counts as a mismatch, which is intended.
  - Read the first line with a `BufferedReader` in try-with-resources. On an `IOException`, treat it as a mismatch.

- [ ] **Step 4: `TrackRecorder` columns.**
  - Append `,car_speed,car_speed_ipk,car_dt_ms` to `HEADER`.
  - In `fix(...)`: after `sigma_a`, append `','`, then:
    - `car_speed` and `car_speed_ipk`: 2 decimals, or an empty cell when `car == null` or the field is null.
    - `car_dt_ms`: **signed** `car.readElapsedMs - elapsedMs`, i.e. when the car value was read relative to the fix's arrival (`arrivalElapsed`, same `elapsedRealtime` clock). Write it only when `car != null && car.hasAny()`; otherwise leave it empty. It is positive when the poll landed between the fix arrival and the main-thread write. Don't clamp: the sign is data.
    - The GPS value describes the moment `arrival - latency_ms`, so the analysis aligns on `car_dt_ms + latency_ms`.
    - No trailing comma on the last cell.
  - In `out(...)`: append `",,,"` after the existing empty cells.
  - Update the class Javadoc column note.

- [ ] **Step 5: Service owns a `VehicleData` while recording.**
  - New field `private VehicleData trackVehicle;`.
  - Add `private void closeTrackRecorder()`: closes and nulls `trackRecorder`, and stops and nulls `trackVehicle`. Use it in `updateTrackRecorder()` (switched off) and in `stopTransport()`, replacing the inline close at ~326; `onDestroy` reaches it through `stopTransport`.
  - Known, unchanged: `updateTrackRecorder()` runs only when a fix arrives. If recording is switched off while no fixes come, the recorder **and now the car connection** stay open until the next fix or until the service stops. Accepted.
  - In `updateTrackRecorder()`:
    - When the recorder is created: `trackVehicle = new VehicleData(this, mainHandler, null); trackVehicle.start();`
    - When it is closed: `trackVehicle.stop(); trackVehicle = null;`
  - Pass `trackVehicle == null ? null : trackVehicle.snapshot()` as the new last argument of `trackRecorder.fix(...)`.
  - Add `// ponytail: separate AdaptAPI connection from MainActivity's; share one if the head unit objects to two`.

- [ ] **Step 6: Run the tests and expect PASS; build.**
Run: `./gradlew :shared:test :client-app:testDebugUnitTest :client-app:assembleDebug`
Expected: BUILD SUCCESSFUL. `TrackReplayTest` must still pass: it looks columns up by name, and the new columns are only appended at the end.

- [ ] **Step 7: Commit**
```bash
git add shared/src/main/java/goodvin/locsync/shared/CsvLog.java shared/src/test/java/goodvin/locsync/shared/CsvLogTest.java client-app/src/main/java/goodvin/locsync/client/TrackRecorder.java client-app/src/test/java/goodvin/locsync/client/TrackRecorderTest.java client-app/src/main/java/goodvin/locsync/client/GNSSClientService.java
git commit -m "client: log car wheel and cluster speed next to each GPS fix in the track log"
```

- [ ] **Step 8: On-car data run** (user: a passenger, or the logs only).
  - Turn on "Record track".
  - Drive a few minutes at a steady ~60 km/h and ~90 km/h.
  - Share `track-client.csv` (the existing share action).
  - Compare `speed` (GPS, m/s ×3.6) with `car_speed` and `car_speed_ipk` on `fix` rows where `abs(car_dt_ms + latency_ms) < 1000` and speed is above 40 km/h. This gives the wheel-to-GPS and cluster-to-GPS ratios.
