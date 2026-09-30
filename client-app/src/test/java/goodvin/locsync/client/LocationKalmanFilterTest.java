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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationKalmanFilterTest {

    private static LocationKalmanFilter newFilter() {
        return new LocationKalmanFilter(2.0, 1.0);
    }

    private static final double M_PER_DEG_LAT = 111320.0;

    // Tunnel case: the provider reports NO speed/bearing (hasSpeed=false). A moving car must still
    // have its velocity inferred from position motion — not pinned to zero by a fake speed=0
    // measurement. This is the root cause of the "icon frozen while GPS looks OK" tunnel bug.
    @Test
    public void unknownSpeedDoesNotPinVelocityToZero() {
        LocationKalmanFilter f = newFilter();
        double lat = 59.0;
        final double dLat = 10.0 / M_PER_DEG_LAT; // ~10 m/s north
        f.update(lat, 30.0, 0.0, 0.0, 5.0, 0.0, 0.0, false, false);
        for (int i = 0; i < 20; i++) {
            f.predict(1.0);
            lat += dLat;
            f.update(lat, 30.0, 0.0, 0.0, 5.0, 0.0, 0.0, false, false);
        }
        assertTrue("speed inferred from motion, got " + f.getSpeed(), f.getSpeed() > 7.0);
        assertTrue("moving north, got vn=" + f.getVn(), f.getVn() > 7.0);
    }

    // Skipping the velocity measurement must not invent motion when the car is genuinely stationary:
    // position not moving => inferred velocity stays ~0.
    @Test
    public void unknownSpeedWithNoMotionStaysStationary() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.0, 0.0, false, false);
        for (int i = 0; i < 20; i++) {
            f.predict(1.0);
            f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.0, 0.0, false, false);
        }
        assertTrue("no motion => ~0 speed, got " + f.getSpeed(), f.getSpeed() < 2.0);
    }

    @Test
    public void firstUpdateInitializes() {
        LocationKalmanFilter f = newFilter();
        assertTrue(!f.isInitialized());
        f.update(59.0, 30.0, 10.0, 90.0, 5.0, 1.0, 5.0); // heading east at 10 m/s
        assertTrue(f.isInitialized());
        assertEquals(59.0, f.getLatitude(), 1e-6);
        assertEquals(30.0, f.getLongitude(), 1e-6);
        // bearing 90 => east velocity positive, north ~0
        assertTrue(f.getVe() > 8.0);
        assertEquals(0.0, f.getVn(), 0.5);
    }

    @Test
    public void resetForgetsState() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 90.0, 5.0, 1.0, 5.0);
        assertTrue(f.isInitialized());
        f.reset();
        assertTrue(!f.isInitialized());
        // after reset, the next update re-anchors at the new position
        f.update(60.0, 31.0, 0.0, 0.0, 5.0, 1.0, 30.0);
        assertEquals(60.0, f.getLatitude(), 1e-6);
        assertEquals(31.0, f.getLongitude(), 1e-6);
    }

    // Regression lock for the covariance-propagation refactor: predict must advance position by
    // velocity*dt exactly, leave velocity untouched, and inflate positional uncertainty (process noise).
    @Test
    public void predictAdvancesPositionAndInflatesUncertainty() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 90.0, 5.0, 1.0, 5.0); // heading east at 10 m/s
        double acc0 = f.getAccuracy();
        double lat0 = f.getLatitude();
        double lon0 = f.getLongitude();
        f.predict(2.0);
        double expDLon = 20.0 / (111320.0 * Math.cos(Math.toRadians(59.0))); // 10 m/s * 2s east
        assertEquals(lon0 + expDLon, f.getLongitude(), 1e-6);
        assertEquals(lat0, f.getLatitude(), 1e-6);
        assertEquals(10.0, f.getSpeed(), 1e-6);
        assertTrue("uncertainty grows " + acc0 + " -> " + f.getAccuracy(), f.getAccuracy() > acc0);
    }

    @Test
    public void predictAdvancesAlongVelocity() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 90.0, 5.0, 1.0, 5.0); // east 10 m/s
        double lon0 = f.getLongitude();
        f.predict(1.0); // 1 second
        // ~10 m east; 1 deg lon ~ 111320*cos(59) ~ 57330 m => 10m ~ 1.74e-4 deg
        double dLon = f.getLongitude() - lon0;
        assertTrue("expected eastward advance", dLon > 1.0e-4 && dLon < 2.5e-4);
        assertEquals(59.0, f.getLatitude(), 1e-5);
    }

    @Test
    public void noiseIsReduced() {
        LocationKalmanFilter f = newFilter();
        // Stationary truth at (59,30); feed noisy position measurements.
        f.update(59.0, 30.0, 0.0, 0.0, 8.0, 1.0, 30.0);
        double[] noise = {+1e-4, -1e-4, +8e-5, -9e-5, +1e-4, -1e-4};
        double maxErrM = 0;
        for (double dn : noise) {
            f.predict(0.2);
            f.update(59.0 + dn, 30.0 + dn, 0.0, 0.0, 8.0, 1.0, 30.0);
            double errLat = Math.abs(f.getLatitude() - 59.0) * 111320.0;
            maxErrM = Math.max(maxErrM, errLat);
        }
        // input noise ~ 1e-4 deg ~ 11 m; filtered error should be well under half that.
        assertTrue("filtered error too high: " + maxErrM, maxErrM < 6.0);
    }

    @Test
    public void enuRoundTrips() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 0.0, 0.0, 5.0, 1.0, 30.0);
        // Update to a point ~100 m north-east, filter should track close to it.
        for (int i = 0; i < 30; i++) {
            f.predict(0.2);
            f.update(59.0009, 30.0016, 0.0, 0.0, 2.0, 1.0, 30.0);
        }
        assertEquals(59.0009, f.getLatitude(), 5e-5);
        assertEquals(30.0016, f.getLongitude(), 5e-5);
    }

    @Test
    public void bearingDerivedFromVelocity() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 0.0, 3.0, 0.5, 2.0); // heading north
        for (int i = 0; i < 10; i++) { f.predict(0.1); f.update(59.0 + i * 9e-5, 30.0, 10.0, 0.0, 3.0, 0.5, 2.0); }
        assertEquals(0.0, f.getBearingDeg(), 5.0); // ~north
        assertTrue(f.getSpeed() > 8.0 && f.getSpeed() < 12.0);
    }

    // Android's Location.getAccuracy() is a 68% radius; for a circular 2D Gaussian that is ~1.51σ,
    // so the reported radius must be wider than the legacy 1σ value by that factor.
    @Test
    public void accuracy68IsWiderThanOneSigma() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 90.0, 5.0, 1.0, 5.0);
        assertEquals(1.5096 * f.getAccuracy(), f.getAccuracy68(), 1e-6);
    }

    // Speed accuracy follows the velocity variance along the travel direction; bearing accuracy
    // shrinks as speed grows (same cross-track velocity error is a smaller angle when fast).
    @Test
    public void speedAndBearingAccuracyFromVelocityCovariance() {
        LocationKalmanFilter slow = newFilter();
        slow.update(59.0, 30.0, 2.0, 90.0, 5.0, 0.5, 0.0);
        LocationKalmanFilter fast = newFilter();
        fast.update(59.0, 30.0, 20.0, 90.0, 5.0, 0.5, 0.0);
        assertEquals(0.5, fast.getSpeedAccuracy(), 1e-6);
        assertTrue("bearing acc should drop with speed: " + slow.getBearingAccuracyDeg()
                        + " vs " + fast.getBearingAccuracyDeg(),
                fast.getBearingAccuracyDeg() < slow.getBearingAccuracyDeg());
        assertEquals(Math.toDegrees(Math.atan2(0.5, 20.0)), fast.getBearingAccuracyDeg(), 1e-6);
    }

    @Test
    public void bearingAccuracyUndefinedWhenStopped() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.5, 0.0);
        assertEquals(180.0, f.getBearingAccuracyDeg(), 1e-9);
    }
}
