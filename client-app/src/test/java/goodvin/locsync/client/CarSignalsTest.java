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
        CarSignals.Snapshot s = CarSignals.fromRaw(10f, 10.5f, 0x200220, 38f, 408f, 0.5f, 23275f, 1234L);
        assertEquals(36f, s.speedKmh, 1e-4f);
        assertEquals(37.8f, s.speedIpkKmh, 1e-4f);
        assertEquals("D", s.gear);
        assertEquals(38f, s.fuelLitres, 0f);
        assertEquals(408f, s.rangeKm, 0f);
        assertEquals(0.5f, s.outsideTempC, 0f);
        assertEquals(23275f, s.odometerKm, 0f);
        assertEquals(1234L, s.readElapsedMs);
        assertNull(CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, 0f, 1L).odometerKm);         // ignition-off zero
        assertEquals(0f, CarSignals.fromRaw(0f, 0f, -1, 0f, 0f, 0f, 0f, 1L).rangeKm, 0f); // empty tank is real
        assertNull(CarSignals.fromRaw(S, S, -1, S, S, S, S, 1L).speedIpkKmh);
        assertFalse(CarSignals.fromRaw(S, S, -1, S, S, S, S, 1L).hasAny());
        assertTrue(CarSignals.fromRaw(0f, S, -1, S, S, S, S, 1L).hasAny());               // standing still is data
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
}
