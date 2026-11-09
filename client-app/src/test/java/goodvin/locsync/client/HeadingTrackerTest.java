/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class HeadingTrackerTest {
    private static final long STEP = 20;   // ms, the gyro's ~50 Hz

    /** Feeds yaw samples from t0 to t1 with raw yaw = raw0 + rateDps * (t - t0) / 1000. Returns the last raw. */
    private static double yaw(HeadingTracker h, long t0, long t1, double raw0, double rateDps) {
        double raw = raw0;
        for (long t = t0; t <= t1; t += STEP) {
            raw = raw0 + rateDps * (t - t0) / 1000.0;
            h.onYaw(t, raw);
        }
        return raw;
    }

    private static double angleDiff(double a, double b) {
        return ((a - b) % 360 + 540) % 360 - 180;
    }

    @Test
    public void straightWithBiasStaysOnCourse() {
        HeadingTracker h = new HeadingTracker(0.15);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 90, false);
        yaw(h, 0, 60_000, 0, 0.15);
        assertTrue(h.valid(60_000));
        assertEquals(0, angleDiff(h.headingDeg(), 90), 0.5);
    }

    @Test
    public void learnsBiasAtStandstill() {
        HeadingTracker h = new HeadingTracker(0);
        Random rnd = new Random(1);
        for (long t = 0; t <= 30_000; t += STEP) {
            h.onYaw(t, 0.12 * t / 1000.0 + rnd.nextGaussian() * 0.03);
            if (t % 100 == 0) h.onWheel(t, 0);
        }
        assertEquals(0.12, h.biasDps(), 0.02);
    }

    @Test
    public void turnFollowsGyroBeforeGps() {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 0, false);
        yaw(h, 0, 3000, 0, -30);
        assertEquals(0, angleDiff(h.headingDeg(), 270), 1);
    }

    @Test
    public void wrapsAcrossNorth() {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 350, false);
        yaw(h, 0, 1000, 0, 20);
        assertEquals(0, angleDiff(h.headingAt(1000), 10), 0.2);
        assertEquals(0, angleDiff(h.headingAt(500), 0), 0.2);
        assertTrue(h.headingAt(500) >= 0 && h.headingAt(500) < 360);
        assertEquals(-Math.toRadians(20), h.deltaRadCcw(0, 1000), 1e-3);
    }

    @Test
    public void deltaRadCcwSign() {
        HeadingTracker h = new HeadingTracker(0);
        yaw(h, 0, 1000, 0, 10);
        assertEquals(-0.1745, h.deltaRadCcw(0, 1000), 1e-3);
    }

    @Test
    public void gpsIgnoredInReverse() {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 0, false);
        yaw(h, 20, 1000, 0, 0);
        h.onGpsBearing(1000, 3, 180, true);
        assertEquals(0, angleDiff(h.headingDeg(), 0), 1e-6);
    }

    @Test
    public void gyroGapInvalidates() {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 0, false);
        assertTrue(h.valid(100));
        assertFalse(h.valid(450));
    }

    @Test
    public void biasClamped() {
        assertEquals(1.0, new HeadingTracker(50).biasDps(), 0);
        assertEquals(-1.0, new HeadingTracker(-50).biasDps(), 0);
        assertEquals(0.0, new HeadingTracker(Double.NaN).biasDps(), 0);
    }

    @Test
    public void gpsPullsSlowly() {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 0, false);
        long t = 0;
        t += 1000;
        yaw(h, 20, t, 0, 0);
        h.onGpsBearing(t, 10, 10, false);
        assertEquals(3, h.headingDeg(), 0.01);
        for (int i = 0; i < 6; i++) {
            yaw(h, t + 20, t + 1000, 0, 0);
            t += 1000;
            h.onGpsBearing(t, 10, 10, false);
        }
        assertEquals(0, angleDiff(h.headingDeg(), 10), 1);
    }

    @Test
    public void badInitRecovers() {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, 0, false);
        long t = 0;
        for (int i = 0; i < 3; i++) {
            yaw(h, t + 20, t + 1000, 0, 0);
            t += 1000;
            h.onGpsBearing(t, 10, 120, false);
        }
        assertEquals(0, angleDiff(h.headingDeg(), 120), 1e-6);
    }
}
