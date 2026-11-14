package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarSignalsTest {
    static final float S = Float.intBitsToFloat(1);   // AdaptAPI "unsupported"

    @Test
    public void latestValueRejectsSentinel() {
        assertNull(CarSignals.latest(S));
        assertNull(CarSignals.latest(Float.NaN));
        assertNull(CarSignals.latest(Float.POSITIVE_INFINITY));
        assertNull(CarSignals.latest(Float.NEGATIVE_INFINITY));
        assertEquals(0f, CarSignals.latest(0f), 0f);
        assertEquals(38.5f, CarSignals.latest(38.5f), 0f);
    }

    @Test
    public void speedIsAbsoluteKmh() {
        assertEquals(36f, CarSignals.speedKmh(10f), 1e-4f);
        assertEquals(36f, CarSignals.speedKmh(-10f), 1e-4f);   // reverse
        assertEquals(0f, CarSignals.speedKmh(0f), 0f);
        assertNull(CarSignals.speedKmh(null));
    }

    @Test
    public void gearLabels() {
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

    @Test
    public void snapshotFromRaw() {
        CarSignals.Snapshot s = CarSignals.fromRaw(10f, 10.5f, 0x200220, 38f, 408f, 0.5f, 23275f, S, 1234L);
        assertEquals(36f, s.speedKmh, 1e-4f);
        assertEquals(37.8f, s.speedIpkKmh, 1e-4f);
        assertEquals("D", s.gear);
        assertEquals(38f, s.fuelLitres, 0f);
        assertEquals(408f, s.rangeKm, 0f);
        assertEquals(0.5f, s.outsideTempC, 0f);
        assertEquals(23275f, s.odometerKm, 0f);
        assertEquals(1234L, s.readElapsedMs);
        CarSignals.Snapshot waking = CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, 0f, S, 1L);   // odometer 0: placeholders
        assertNull(waking.odometerKm);
        assertNull(waking.fuelLitres);
        assertNull(waking.rangeKm);
        assertEquals(0f, waking.outsideTempC, 0f);                                          // 0 °C is real
        assertEquals(5f, CarSignals.fromRaw(0f, 0f, -1, 5f, 0f, 0f, 0f, S, 1L).fuelLitres, 0f);
        CarSignals.Snapshot empty = CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, 23275f, S, 1L);
        assertEquals(0f, empty.rangeKm, 0f);                                               // empty tank is real
        assertEquals(0f, empty.fuelLitres, 0f);
        assertEquals(0f, CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, S, S, 1L).rangeKm, 0f);  // no odometer: keep zeros
        assertNull(CarSignals.fromRaw(S, S, -1, S, S, S, S, S, 1L).speedIpkKmh);
        assertFalse(CarSignals.fromRaw(S, S, -1, S, S, S, S, S, 1L).hasAny());
        assertTrue(CarSignals.fromRaw(0f, S, -1, S, S, S, S, S, 1L).hasAny());               // standing still is data
    }

    // Steering is kept as the raw float bits: its encoding is unknown (fuel turned out to be an
    // integer in the bits). It reads 0 parked, like the dead odometer, so alone it isn't data.
    @Test
    public void steeringKeptRaw() {
        assertEquals(Integer.valueOf(95), CarSignals.fromRaw(S, S, -1, S, S, S, S, Float.intBitsToFloat(95), 1L).steerBits);
        assertEquals(Integer.valueOf(Float.floatToRawIntBits(-12.5f)),
                CarSignals.fromRaw(S, S, -1, S, S, S, S, -12.5f, 1L).steerBits);
        assertNull(CarSignals.fromRaw(S, S, -1, S, S, S, S, S, 1L).steerBits);
        assertNull(CarSignals.Snapshot.EMPTY.steerBits);
        assertFalse(CarSignals.fromRaw(S, S, -1, S, S, S, S, 0f, 1L).hasAny());
    }

    // G426 packs integer millilitres into the float's bits; a normal float is litres already.
    @Test
    public void fuelDecoding() {
        assertEquals(29.799f, CarSignals.fuelLitres(Float.intBitsToFloat(29799)), 1e-4f);
        assertEquals(38f, CarSignals.fuelLitres(38f), 0f);
        assertEquals(0f, CarSignals.fuelLitres(0f), 0f);
        assertNull(CarSignals.fuelLitres(S));                       // unsupported sentinel (bits 0x1)
        assertEquals(-1f, CarSignals.fuelLitres(-1f), 0f);           // negatives aren't subnormal-decoded
        assertEquals(29.799f, CarSignals.fromRaw(0f, 0f, -1, Float.intBitsToFloat(29799), 364f, 2.6f, 0f, S, 1L)
                .fuelLitres, 1e-4f);                                 // the logged case, odometer 0
    }

    @Test
    public void speedHistoryInterpolatesAndExpires() {
        CarSignals.SpeedHistory h = new CarSignals.SpeedHistory();
        assertNull(h.at(1000));
        h.add(1000, 50f);
        h.add(1200, 52f);
        assertEquals(51f, h.at(1100), 1e-4f);
        assertEquals(52f, h.at(1500), 0f);         // newest held for 300 ms
        assertNull(h.at(1501));
        assertNull(h.at(999));                     // older than kept
        h.add(2000, 60f);                          // 800 ms hole: not interpolated across
        assertNull(h.at(1600));
        assertEquals(60f, h.at(2000), 0f);
        h.add(1900, 1f);                           // out of order: ignored
        assertEquals(60f, h.at(2000), 0f);
        h.add(2100, null);                         // unreadable: history cleared
        assertNull(h.at(2000));
        for (int i = 0; i < 100; i++) h.add(10_000 + i * 200L, (float) i);   // ring wraps
        assertEquals(98.5f, h.at(10_000 + 98 * 200L + 100), 1e-4f);
        assertNull(h.at(10_000 + 50 * 200L));      // overwritten
    }

    @Test
    public void wheelCalibrationLearnsAndRejectsGlitches() {
        CarSignals.WheelCalibration c = new CarSignals.WheelCalibration(null);
        assertEquals(1.0, c.factor(), 0);
        for (int i = 0; i < 200; i++) assertTrue(c.update(102.8, 0.1, 100, 100));
        assertEquals(1.028, c.factor(), 1e-3);
        assertFalse(c.update(90, 0.1, 100, 100));          // GPS glitch: outside ±0.04
        assertFalse(c.update(102.8, 0.1, 100, 99));        // accelerating: lag would bias it
        assertFalse(c.update(30.8, 0.1, 30, 30));          // too slow
        assertFalse(c.update(102.8, 0.0, 100, 100));       // no speed accuracy
        assertFalse(c.update(102.8, 0.8, 100, 100));       // inaccurate GPS speed
        assertEquals(1.028, c.factor(), 1e-3);
        CarSignals.WheelCalibration learned = new CarSignals.WheelCalibration(1.5f);
        assertEquals(1.1, learned.factor(), 0);            // clamped
        assertFalse(learned.update(150, 0.1, 100, 100));   // learned: tight window from the start
    }

    @Test
    public void missingSdkIsDetected() {
        assertTrue(CarSignals.isMissingSdk(new ClassNotFoundException("x")));
        assertTrue(CarSignals.isMissingSdk(new NoClassDefFoundError("x")));
        assertTrue(CarSignals.isMissingSdk(new ExceptionInInitializerError(new NoClassDefFoundError("x"))));
        // A vendor class whose static init throws: not usable on this unit either, stop retrying.
        assertTrue(CarSignals.isMissingSdk(new ExceptionInInitializerError(new IllegalStateException("x"))));
        assertTrue(CarSignals.isMissingSdk(
                new java.lang.reflect.InvocationTargetException(new ClassNotFoundException("x"))));
        assertFalse(CarSignals.isMissingSdk(new NoSuchMethodException("x")));   // hidden API: retry, not give up
        assertFalse(CarSignals.isMissingSdk(new IllegalStateException("x")));
    }

    // The census probe: only what changed goes in a row, plus everything once a minute, so a drive's
    // worth of ~100 AdaptAPI sensors stays small but every value can still be reconstructed.
    @Test
    public void probeDiffWritesChangesAndFullEveryMinute() {
        CarSignals.ProbeDiff d = new CarSignals.ProbeDiff(new int[]{0x100100, 0x200200});
        assertEquals("100100=1.5;200200=3", d.row(0, new double[]{1.5, 3}));
        assertEquals("", d.row(1000, new double[]{1.5, 3}));
        assertEquals("200200=4", d.row(2000, new double[]{1.5, 4}));
        assertEquals("100100=nan", d.row(3000, new double[]{Double.NaN, 4}));
        assertEquals("", d.row(4000, new double[]{Double.NaN, 4}));
        assertEquals("100100=nan;200200=4", d.row(60_000, new double[]{Double.NaN, 4}));
    }

    @Test
    public void w4mNullWhenAllNaN() {
        float n = Float.NaN;
        assertEquals(null, CarSignals.W4m.of(n, n, n, n, n, n, n, n, 0, 0, 0));
        CarSignals.W4m w = CarSignals.W4m.of(0.1f, n, n, n, n, n, n, n, 3, 5, 20);
        assertEquals(0.1f, w.yawRate, 0);
        assertEquals(3, w.gear);
        assertEquals(5, w.tick);
    }
}
