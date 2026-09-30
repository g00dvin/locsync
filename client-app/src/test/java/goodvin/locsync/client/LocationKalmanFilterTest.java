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

    // Latency compensation relies on extrapolate(): it must project the state forward exactly like
    // predict() would, but without changing the filter itself.
    @Test
    public void extrapolateProjectsWithoutMutating() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 90.0, 5.0, 1.0, 5.0); // east 10 m/s
        double lon0 = f.getLongitude();
        LocationKalmanFilter.Estimate est = f.extrapolate(1.0);
        double expDLon = 10.0 / (111320.0 * Math.cos(Math.toRadians(59.0)));
        assertEquals(lon0 + expDLon, est.longitude, 1e-7);
        assertEquals(lon0, f.getLongitude(), 1e-12);
        assertTrue(est.accuracy > f.getAccuracy());
        assertEquals(f.getLatitude(), f.extrapolate(0).latitude, 1e-12);
    }

    // Drive a right-hand circle (bearing +18°/s at 10 m/s) and check the turn model learns the rate
    // and predicts along the arc rather than off along the tangent.
    @Test
    public void turnModelFollowsArc() {
        double speed = 10.0, rateDeg = 18.0;
        LocationKalmanFilter turn = newFilter();
        LocationKalmanFilter straight = newFilter();
        straight.setTurnModel(false);
        double r = speed / Math.toRadians(rateDeg);
        double[] truth = new double[2];
        for (int i = 0; i <= 10; i++) {
            double brg = rateDeg * i;
            // circle centred east of the start; heading north initially, turning clockwise
            double th = Math.toRadians(brg);
            double e = r - r * Math.cos(th), n = r * Math.sin(th);
            double lat = 59.0 + n / M_PER_DEG_LAT;
            double lon = 30.0 + e / (M_PER_DEG_LAT * Math.cos(Math.toRadians(59.0)));
            for (LocationKalmanFilter f : new LocationKalmanFilter[]{turn, straight}) {
                if (i > 0) f.predict(1.0);
                f.update(lat, lon, speed, brg % 360, 3.0, 0.3, 2.0);
            }
            double thNext = Math.toRadians(brg + rateDeg);
            truth[0] = r - r * Math.cos(thNext);
            truth[1] = r * Math.sin(thNext);
        }
        assertEquals(rateDeg, turn.getTurnRateDegPerSec(), 3.0);
        assertEquals(0.0, straight.getTurnRateDegPerSec(), 1e-9);
        double errTurn = predErr(turn.extrapolate(1.0), truth);
        double errStraight = predErr(straight.extrapolate(1.0), truth);
        assertTrue("turn model should predict closer to the arc: " + errTurn + " vs " + errStraight,
                errTurn < errStraight * 0.6);
    }

    @Test
    public void turnRateDecaysWhenStopped() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 0.0, 3.0, 0.3, 2.0);
        f.predict(1.0);
        f.update(59.0 + 10 / M_PER_DEG_LAT, 30.0, 10.0, 20.0, 3.0, 0.3, 2.0);
        assertTrue(Math.abs(f.getTurnRateDegPerSec()) > 1.0);
        f.predict(1.0);
        f.update(59.0 + 10 / M_PER_DEG_LAT, 30.0, 0.0, 0.0, 3.0, 0.3, 2.0);
        f.predict(1.0);
        f.update(59.0 + 10 / M_PER_DEG_LAT, 30.0, 0.0, 0.0, 3.0, 0.3, 2.0);
        assertEquals(0.0, f.getTurnRateDegPerSec(), 1e-9);
    }

    // A 180° heading flip within a second is noise, not a turn: ω must not jump to the cap.
    @Test
    public void implausibleHeadingFlipIsNotATurn() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 10.0, 0.0, 3.0, 0.3, 2.0);
        f.predict(1.0);
        f.update(59.0 + 10 / M_PER_DEG_LAT, 30.0, 10.0, 180.0, 3.0, 0.3, 2.0);
        assertEquals(0.0, f.getTurnRateDegPerSec(), 1e-9);
    }

    private static double predErr(LocationKalmanFilter.Estimate est, double[] truthEn) {
        double n = (est.latitude - 59.0) * M_PER_DEG_LAT;
        double e = (est.longitude - 30.0) * M_PER_DEG_LAT * Math.cos(Math.toRadians(59.0));
        return Math.hypot(e - truthEn[0], n - truthEn[1]);
    }

    // A single multipath jump of ~100 m must be de-weighted, not followed.
    @Test
    public void gatingDeweightsSingleJump() {
        LocationKalmanFilter gated = newFilter();
        LocationKalmanFilter open = newFilter();
        open.setGating(false, 0);
        for (LocationKalmanFilter f : new LocationKalmanFilter[]{gated, open}) {
            f.setStandstill(false, 0); // isolate the gate from the standstill hold
            f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.3, 0.0);
            for (int i = 0; i < 5; i++) {
                f.predict(1.0);
                f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.3, 0.0);
            }
            f.predict(1.0);
            f.update(59.0 + 100 / M_PER_DEG_LAT, 30.0, 0.0, 0.0, 5.0, 0.3, 0.0);
        }
        double jumpGated = (gated.getLatitude() - 59.0) * M_PER_DEG_LAT;
        double jumpOpen = (open.getLatitude() - 59.0) * M_PER_DEG_LAT;
        assertTrue("gated filter should barely move: " + jumpGated + " vs " + jumpOpen,
                jumpGated < jumpOpen / 3);
        assertEquals(1, gated.getOutlierCount());
        assertTrue(gated.getLastNis() > 9.21);
    }

    // If the car really moved (fixes consistently elsewhere), the filter must re-anchor.
    @Test
    public void gatingReanchorsAfterConsecutiveOutliers() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 0.0, 0.0, 3.0, 0.3, 0.0);
        for (int i = 0; i < 5; i++) {
            f.predict(1.0);
            f.update(59.0, 30.0, 0.0, 0.0, 3.0, 0.3, 0.0);
        }
        double newLat = 59.0 + 500 / M_PER_DEG_LAT;
        for (int i = 0; i < 5; i++) {
            f.predict(1.0);
            f.update(newLat, 30.0, 0.0, 0.0, 3.0, 0.3, 0.0);
        }
        assertEquals(1, f.getReinitCount());
        assertEquals(newLat, f.getLatitude(), 1e-7);
    }

    // Parked car with slowly wandering (correlated) fixes: the hold must keep the estimate much
    // closer to where it stopped than a plain filter, and keep velocity ~0.
    @Test
    public void standstillHoldResistsWander() {
        LocationKalmanFilter held = newFilter();
        LocationKalmanFilter plain = newFilter();
        plain.setStandstill(false, 0);
        for (LocationKalmanFilter f : new LocationKalmanFilter[]{held, plain}) {
            f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.2, 0.0);
            for (int i = 1; i <= 20; i++) {
                f.predict(1.0);
                f.update(59.0 + i * 0.5 / M_PER_DEG_LAT, 30.0, 0.1, 0.0, 5.0, 0.2, 0.0); // drifts 0.5 m/s north
            }
        }
        assertTrue(held.isStationary());
        assertTrue(!plain.isStationary());
        double driftHeld = (held.getLatitude() - 59.0) * M_PER_DEG_LAT;
        double driftPlain = (plain.getLatitude() - 59.0) * M_PER_DEG_LAT;
        assertTrue("hold should resist wander: " + driftHeld + " vs " + driftPlain, driftHeld < driftPlain / 2);
        assertTrue(held.getSpeed() < 0.1);
    }

    // No Doppler speed (tunnel) must never count as "stopped".
    @Test
    public void unknownSpeedIsNotStandstill() {
        LocationKalmanFilter f = newFilter();
        f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.0, 0.0, false, false);
        f.predict(1.0);
        f.update(59.0, 30.0, 0.0, 0.0, 5.0, 0.0, 0.0, false, false);
        assertTrue(!f.isStationary());
    }

    @Test
    public void adaptiveNoiseRisesInTurnsOnly() {
        LocationKalmanFilter f = newFilter();
        f.setStandstill(false, 0);
        f.update(59.0, 30.0, 15.0, 0.0, 3.0, 0.3, 2.0);
        f.predict(1.0);
        f.update(59.0 + 15 / M_PER_DEG_LAT, 30.0, 15.0, 0.0, 3.0, 0.3, 2.0);
        assertEquals(2.0, f.getProcessNoise(), 0.2); // straight: stays near base
        f.predict(1.0);
        f.update(59.0 + 30 / M_PER_DEG_LAT, 30.0, 15.0, 20.0, 3.0, 0.3, 2.0); // 20°/s turn
        assertTrue("turn should raise σa: " + f.getProcessNoise(), f.getProcessNoise() > 2.5);

        LocationKalmanFilter fixed = newFilter();
        fixed.setProcessNoise(2.0, false);
        fixed.update(59.0, 30.0, 15.0, 0.0, 3.0, 0.3, 2.0);
        fixed.predict(1.0);
        fixed.update(59.0 + 15 / M_PER_DEG_LAT, 30.0, 15.0, 20.0, 3.0, 0.3, 2.0);
        assertEquals(2.0, fixed.getProcessNoise(), 1e-12);
    }

    // Fixes persistently disagreeing with the model (NIS well above 2) must loosen it, capped.
    @Test
    public void adaptiveNoiseFollowsInnovation() {
        LocationKalmanFilter f = new LocationKalmanFilter(0.5, 1.0);
        f.setStandstill(false, 0);
        f.setGating(false, 0);
        f.update(59.0, 30.0, 0.0, 0.0, 2.0, 0.0, 0.0, false, false);
        for (int i = 1; i <= 15; i++) {
            f.predict(1.0);
            double zig = (i % 2 == 0 ? 1 : -1) * 12.0; // ±12 m zig-zag vs σ=2 m fixes
            f.update(59.0 + zig / M_PER_DEG_LAT, 30.0, 0.0, 0.0, 2.0, 0.0, 0.0, false, false);
        }
        assertTrue("NIS mean should be high: " + f.getNisAverage(), f.getNisAverage() > 3.0);
        assertTrue(f.getProcessNoise() > 0.5);
        assertTrue(f.getProcessNoise() <= 0.5 * 3.0 + 1e-9);
    }
}
