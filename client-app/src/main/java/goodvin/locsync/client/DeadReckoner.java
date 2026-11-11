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

/**
 * Dead reckoning: from an anchor (the Kalman state at a fix), walks the wheel distance along the
 * {@link HeadingTracker}'s heading. Incremental: each output tick walks only from the previous tick, so a
 * GPS gap needs no history beyond the last few seconds. Each fix re-anchors it and re-walks from the
 * fix's measurement time to now.
 *
 * <p>Pure: times are ms on the SystemClock.elapsedRealtime() base. Service thread only.
 */
final class DeadReckoner {
    /** Calibrated wheel speed in m/s at a time, negative in reverse; NaN when unknown. */
    interface WheelSpeed {
        double mpsAt(long tMs);
    }

    private static final double M_PER_DEG_LAT = 111320.0;
    private static final long ANCHOR_STEP_MS = 100;
    private static final double DISTANCE_ERROR = 0.01;   // wheel factor error left after calibration
    private static final double SPEED_ACC_MPS = 0.15;
    // Through a wheel-speed hole up to this long (a stalled 200 ms poll) keep the last speed; a longer
    // hole, or a step this long after the last (switch toggled, thread stalled), stops.
    static final long MAX_HOLD_MS = 2000;
    // The service dead-reckons through a GPS loss at most this long, or until it's this unsure; then it freezes.
    // A wheel+gyro replay of the 2026-11-10 drive drifted 42 m (p90) after 2 min and 144 m (p90) after 5 min.
    static final long MAX_MS = 300_000;
    static final double MAX_ACC_M = 200;

    private final HeadingTracker heading;
    private final WheelSpeed wheel;
    private boolean active;
    private double lat, lon, accM, distM;
    private long lastMs, anchoredAtMs;
    private double lastV = Double.NaN;
    private long lastVMs;

    DeadReckoner(HeadingTracker heading, WheelSpeed wheel) {
        this.heading = heading;
        this.wheel = wheel;
    }

    /** Puts the position at (lat, lon) at {@code atMs} and walks it to {@code nowMs}. False (inactive) on missing data. */
    boolean anchor(double lat, double lon, double accM, long atMs, long nowMs) {
        this.lat = lat;
        this.lon = lon;
        this.accM = accM;
        distM = 0;
        lastMs = atMs;
        anchoredAtMs = atMs;
        active = true;
        for (long t = atMs + ANCHOR_STEP_MS; t < nowMs; t += ANCHOR_STEP_MS) {
            if (!segment(t)) {
                return false;
            }
        }
        return nowMs <= lastMs || segment(nowMs);
    }

    /**
     * Walks from the previous time to {@code nowMs}. False (and inactive) once the heading is missing, the wheel
     * speed has been missing for {@link #MAX_HOLD_MS}, or the previous step is that old.
     */
    boolean step(long nowMs) {
        if (!active) {
            return false;
        }
        if (nowMs - lastMs > MAX_HOLD_MS) {
            active = false;
            return false;
        }
        return nowMs <= lastMs || segment(nowMs);
    }

    private boolean segment(long t1) {
        long mid = (lastMs + t1) / 2;
        double h = heading.valid(t1) ? heading.headingAt(mid) : Double.NaN;
        double v = wheel.mpsAt(mid);
        if (Double.isNaN(v)) {
            v = wheel.mpsAt(t1);
        }
        if (!Double.isNaN(v)) {
            lastV = v;
            lastVMs = t1;
        } else if (!Double.isNaN(lastV) && t1 - lastVMs <= MAX_HOLD_MS) {
            v = lastV;   // a stalled poll: keep going at the last speed rather than freeze and jump
        }
        if (Double.isNaN(h) || Double.isNaN(v)) {
            active = false;
            return false;
        }
        double d = v * (t1 - lastMs) / 1000.0;
        double br = Math.toRadians(h);
        lat += d * Math.cos(br) / M_PER_DEG_LAT;
        lon += d * Math.sin(br) / (M_PER_DEG_LAT * Math.cos(Math.toRadians(lat)));
        distM += Math.abs(d);
        lastMs = t1;
        return true;
    }

    boolean active() {
        return active;
    }

    long anchoredAtMs() {
        return anchoredAtMs;
    }

    /** Fills {@code out} with the current position; accuracy grows with the distance walked and the heading's sigma. */
    void fill(LocationKalmanFilter.Estimate out, long nowMs) {
        double sigma = heading.sigmaDeg(nowMs);
        double v = wheel.mpsAt(nowMs);
        if (Double.isNaN(v) && nowMs - lastVMs <= MAX_HOLD_MS) {
            v = lastV;   // the speed it's moving at through a stalled poll
        }
        out.latitude = lat;
        out.longitude = lon;
        out.speed = Double.isNaN(v) ? 0 : Math.abs(v);
        out.bearingDeg = heading.headingDeg();
        double lateral = distM * Math.sin(Math.toRadians(sigma));
        out.accuracy = Math.sqrt(accM * accM + Math.pow(DISTANCE_ERROR * distM, 2) + lateral * lateral);
        out.accuracy68 = out.accuracy;
        out.speedAccuracy = SPEED_ACC_MPS;
        out.bearingAccuracyDeg = sigma;
    }

    /** Hides a re-anchor jump: the old-minus-new offset, fading to zero over {@link #SLIDE_MS}; big jumps snap. */
    static final class Slide {
        static final double MAX_SLIDE_M = 50;
        static final long SLIDE_MS = 1000;
        private double dE, dN;
        private long startMs;

        void start(double dEastM, double dNorthM, long tMs) {
            boolean small = Math.hypot(dEastM, dNorthM) <= MAX_SLIDE_M;
            dE = small ? dEastM : 0;
            dN = small ? dNorthM : 0;
            startMs = tMs;
        }

        void apply(LocationKalmanFilter.Estimate e, long tMs) {
            double k = 1 - Math.min(1, Math.max(0, tMs - startMs) / (double) SLIDE_MS);
            if (k <= 0 || (dE == 0 && dN == 0)) {
                return;
            }
            e.latitude += k * dN / M_PER_DEG_LAT;
            e.longitude += k * dE / (M_PER_DEG_LAT * Math.cos(Math.toRadians(e.latitude)));
        }
    }
}
