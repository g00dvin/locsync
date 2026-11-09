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

public class DeadReckonerTest {
    private static final double LAT = 55.6, LON = 37.6, M = 111320.0;

    /** A tracker initialised at {@code heading0} at t=0, turning at {@code rateDps}, with yaw samples up to tEnd. */
    private static HeadingTracker tracker(double heading0, double rateDps, long tEnd) {
        HeadingTracker h = new HeadingTracker(0);
        h.onYaw(0, 0);
        h.onGpsBearing(0, 10, heading0, false);
        for (long t = 20; t <= tEnd; t += 20) {
            h.onYaw(t, rateDps * t / 1000.0);
        }
        return h;
    }

    private static double east(LocationKalmanFilter.Estimate e) {
        return (e.longitude - LON) * M * Math.cos(Math.toRadians(LAT));
    }

    private static double north(LocationKalmanFilter.Estimate e) {
        return (e.latitude - LAT) * M;
    }

    /** Feeds yaw live (as the service does) while stepping every 100 ms up to tEnd. */
    private static LocationKalmanFilter.Estimate run(double heading0, double rateDps, DeadReckoner.WheelSpeed w, long tEnd) {
        HeadingTracker h = tracker(heading0, rateDps, 0);
        DeadReckoner dr = new DeadReckoner(h, w);
        assertTrue(dr.anchor(LAT, LON, 3, 0, 0));
        for (long t = 100; t <= tEnd; t += 100) {
            for (long y = t - 80; y <= t; y += 20) {
                h.onYaw(y, rateDps * y / 1000.0);
            }
            assertTrue(dr.step(t));
        }
        LocationKalmanFilter.Estimate e = new LocationKalmanFilter.Estimate();
        dr.fill(e, tEnd);
        return e;
    }

    @Test
    public void straightLine() {
        LocationKalmanFilter.Estimate e = run(90, 0, t -> 10, 10_000);
        assertEquals(100, east(e), 0.5);
        assertEquals(0, north(e), 0.5);
        assertEquals(10, e.speed, 1e-9);
        assertEquals(90, e.bearingDeg, 1e-6);
    }

    @Test
    public void anchorRewalksToNow() {
        DeadReckoner dr = new DeadReckoner(tracker(0, 0, 1500), t -> 10);
        assertTrue(dr.anchor(LAT, LON, 3, 0, 1500));
        LocationKalmanFilter.Estimate e = new LocationKalmanFilter.Estimate();
        dr.fill(e, 1500);
        assertEquals(15, north(e), 0.1);
        assertEquals(0, dr.anchoredAtMs());
    }

    @Test
    public void quarterCircle() {
        LocationKalmanFilter.Estimate e = run(0, 10, t -> 10, 9000);
        double r = 10 / Math.toRadians(10);   // v / ω
        assertEquals(r, east(e), 1);
        assertEquals(r, north(e), 1);
    }

    @Test
    public void reverseMovesBackwards() {
        LocationKalmanFilter.Estimate e = run(0, 0, t -> -1, 5000);
        assertEquals(-5, north(e), 0.1);
        assertEquals(1, e.speed, 1e-9);
    }

    @Test
    public void accuracyGrowsWithDistance() {
        LocationKalmanFilter.Estimate e = run(0, 0, t -> 1000, 1000);
        double sigma = Math.toRadians(Math.hypot(2.0, 0.03));
        assertEquals(Math.sqrt(9 + 100 + Math.pow(1000 * Math.sin(sigma), 2)), e.accuracy, 0.5);
        assertEquals(e.accuracy, e.accuracy68, 1e-9);
    }

    @Test
    public void missingWheelSpeedStops() {
        DeadReckoner dr = new DeadReckoner(tracker(0, 0, 1000), t -> t < 500 ? 10 : Double.NaN);
        assertTrue(dr.anchor(LAT, LON, 3, 0, 0));
        assertTrue(dr.step(300));
        assertTrue(dr.step(700));    // held: a stalled poll isn't the end
        LocationKalmanFilter.Estimate e = new LocationKalmanFilter.Estimate();
        dr.fill(e, 700);
        assertEquals(10, e.speed, 1e-9);   // reports the speed it keeps moving at
        assertFalse(dr.step(2400));  // missing for over MAX_HOLD_MS: stop, the caller falls back
        assertFalse(dr.active());
    }

    @Test
    public void slideDecays() {
        DeadReckoner.Slide s = new DeadReckoner.Slide();
        s.start(3, 0, 1000);
        LocationKalmanFilter.Estimate e = new LocationKalmanFilter.Estimate();
        e.latitude = LAT; e.longitude = LON;
        s.apply(e, 1500);
        assertEquals(1.5, east(e), 1e-6);
        e.latitude = LAT; e.longitude = LON;
        s.apply(e, 2000);
        assertEquals(0, east(e), 1e-9);
    }

    @Test
    public void slideSkipsLargeOffsets() {
        DeadReckoner.Slide s = new DeadReckoner.Slide();
        s.start(60, 0, 1000);
        LocationKalmanFilter.Estimate e = new LocationKalmanFilter.Estimate();
        e.latitude = LAT; e.longitude = LON;
        s.apply(e, 1000);
        assertEquals(0, east(e), 1e-9);
    }

    // One stalled wheel poll (SpeedHistory goes null 300 ms after its newest sample) must not end dead
    // reckoning for the rest of a tunnel: hold, then walk the missed interval when the speed is back.
    @Test
    public void wheelHoleThenRecovers() {
        HeadingTracker h = tracker(0, 0, 0);
        DeadReckoner dr = new DeadReckoner(h, t -> t >= 500 && t < 800 ? Double.NaN : 10);
        assertTrue(dr.anchor(LAT, LON, 3, 0, 0));
        for (long t = 100; t <= 2000; t += 100) {
            for (long y = t - 80; y <= t; y += 20) h.onYaw(y, 0);
            assertTrue("t=" + t, dr.step(t));
        }
        LocationKalmanFilter.Estimate e = new LocationKalmanFilter.Estimate();
        dr.fill(e, 2000);
        assertEquals(20, north(e), 1.0);
    }

    @Test
    public void longWheelHoleStops() {
        HeadingTracker h = tracker(0, 0, 0);
        DeadReckoner dr = new DeadReckoner(h, t -> t >= 500 ? Double.NaN : 10);
        assertTrue(dr.anchor(LAT, LON, 3, 0, 0));
        boolean stopped = false;
        for (long t = 100; t <= 3000 && !stopped; t += 100) {
            for (long y = t - 80; y <= t; y += 20) h.onYaw(y, 0);
            stopped = !dr.step(t);
        }
        assertTrue(stopped);
        assertFalse(dr.active());
    }

    // Switched off and on again (or a stalled thread): never walk one huge stale segment.
    @Test
    public void staleStepStops() {
        HeadingTracker h = tracker(0, 0, 5000);
        DeadReckoner dr = new DeadReckoner(h, t -> 10);
        assertTrue(dr.anchor(LAT, LON, 3, 4800, 4800));
        assertTrue(dr.step(4900));
        h = null;
        DeadReckoner stale = new DeadReckoner(tracker(0, 0, 5000), t -> 10);
        assertTrue(stale.anchor(LAT, LON, 3, 0, 0));
        assertFalse(stale.step(5000));
        assertFalse(stale.active());
    }
}
