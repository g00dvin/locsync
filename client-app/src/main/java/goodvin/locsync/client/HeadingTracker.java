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
 * The car's heading from the head unit's gyro ({@link GyroYaw}'s raw, cumulative yaw), with the gyro bias
 * learnt while the wheels stand still, and pulled gently towards the GPS bearing while driving. Between
 * GPS fixes it follows a turn the moment it starts; GPS only removes the slow drift.
 *
 * <p>Pure: times are ms on the SystemClock.elapsedRealtime() base, angles bearings in degrees. Not
 * thread-safe: the service calls it from its own thread only.
 */
final class HeadingTracker {
    /** GPS speed and bearing describe the car about this long before their measurement time (2026-11-09 drive). */
    static final long GPS_LAG_MS = 200;
    static final double GYRO_SCALE = 1.0;          // 0.98 measured over 29 turns: within noise of 1
    static final long GAP_INVALID_MS = 400;        // below GyroYaw.MAX_STEP_S, so no hole goes unnoticed
    private static final double STILL_MPS = 0.05;
    private static final long STILL_SETTLE_MS = 2000;
    private static final long BIAS_WINDOW_MS = 1000;
    private static final double BIAS_ALPHA = 0.1;
    private static final double MAX_BIAS_DPS = 1.0;
    private static final double GPS_MIN_SPEED = 3.0;
    private static final double INIT_MIN_SPEED = 4.0;
    private static final double GPS_GAIN = 0.3;
    private static final double MAX_GPS_ERROR_DEG = 45;
    private static final double REINIT_MIN_SPEED = 8.0;
    private static final int REINIT_AFTER = 3;
    // Fitted to the 2026-11-10 replay drift (see DeadReckoner.MAX_MS): 2° and 0.03 °/s claimed 60 m after 1 min at 90 km/h.
    private static final double BASE_SIGMA_DEG = 1.0;
    private static final double SIGMA_GROWTH_DPS = 0.003;   // bias uncertainty once GPS stops correcting
    private static final long RING_STEP_MS = 50;
    private static final int RING_SIZE = 100;              // ≥ 5 s of history

    private double bias;
    private boolean initialized;
    private double heading;                // unwrapped, degrees
    private double lastRaw = Double.NaN;
    private boolean hasYaw;
    private long lastYawT;
    private long lastCorrT;
    private int badFixes;
    private int biasUpdates;
    private long stillSince = -1, winT = -1;
    private double winRaw;
    private final long[] ringT = new long[RING_SIZE];
    private final double[] ringH = new double[RING_SIZE];
    private int ringCount, ringNewest = -1;

    HeadingTracker(double initialBiasDps) {
        bias = clampBias(initialBiasDps);
    }

    private static double clampBias(double b) {
        return Double.isNaN(b) ? 0 : Math.max(-MAX_BIAS_DPS, Math.min(MAX_BIAS_DPS, b));
    }

    void onYaw(long tMs, double rawYawDeg) {
        if (hasYaw && tMs <= lastYawT) {
            return;
        }
        if (hasYaw) {
            if (tMs - lastYawT > GAP_INVALID_MS) {
                initialized = false;   // a hole the gyro didn't integrate: the heading is lost until GPS
            } else {
                heading += GYRO_SCALE * (rawYawDeg - lastRaw) - bias * (tMs - lastYawT) / 1000.0;
            }
        }
        lastRaw = rawYawDeg;
        lastYawT = tMs;
        hasYaw = true;
        if (ringNewest < 0 || tMs - ringT[ringNewest] >= RING_STEP_MS) {
            ringNewest = (ringNewest + 1) % RING_SIZE;
            ringT[ringNewest] = tMs;
            ringH[ringNewest] = heading;
            ringCount = Math.min(ringCount + 1, RING_SIZE);
        }
    }

    /** Standstill bias learning: 1 s rate windows while the wheels have been still for 2 s. */
    void onWheel(long tMs, double mps) {
        if (Double.isNaN(mps) || Math.abs(mps) >= STILL_MPS) {
            stillSince = -1;
            return;
        }
        if (stillSince < 0) {
            stillSince = tMs;
            winT = -1;
        }
        if (tMs - stillSince < STILL_SETTLE_MS || !hasYaw) {
            return;
        }
        if (winT < 0 || lastYawT - winT > 2 * BIAS_WINDOW_MS) {   // start, or a hole in the gyro
            winT = lastYawT;
            winRaw = lastRaw;
            return;
        }
        if (lastYawT - winT >= BIAS_WINDOW_MS) {
            double rate = (lastRaw - winRaw) / ((lastYawT - winT) / 1000.0);
            bias = clampBias(bias + BIAS_ALPHA * (rate - bias));
            biasUpdates++;
            winT = lastYawT;
            winRaw = lastRaw;
        }
    }

    /**
     * A GPS bearing, at its measurement time minus {@link #GPS_LAG_MS}, already corrected for whole-degree
     * truncation. Ignored in reverse (it points backwards) and below 3 m/s (noise).
     */
    void onGpsBearing(long tMs, double speedMps, double bearingDeg, boolean reverse) {
        if (reverse || !hasYaw || Double.isNaN(bearingDeg) || speedMps < GPS_MIN_SPEED) {
            return;
        }
        double at = rawHeadingAt(tMs);
        if (Double.isNaN(at)) {
            at = heading;
        }
        double err = wrap180(bearingDeg - at);
        if (!initialized) {
            if (speedMps >= INIT_MIN_SPEED) {
                shift(err);
                initialized = true;
                badFixes = 0;
                lastCorrT = tMs;
            }
            return;
        }
        if (Math.abs(err) > MAX_GPS_ERROR_DEG) {
            // A glitch, or our own bad start: only a run of confident fixes overrules the gyro.
            if (speedMps >= REINIT_MIN_SPEED && ++badFixes >= REINIT_AFTER) {
                shift(err);
                badFixes = 0;
                lastCorrT = tMs;
            }
            return;
        }
        badFixes = 0;
        shift(GPS_GAIN * Math.min(1, (speedMps - GPS_MIN_SPEED) / 5) * err);
        lastCorrT = tMs;
    }

    private void shift(double deg) {
        heading += deg;
        for (int i = 0; i < ringCount; i++) {
            ringH[i] += deg;
        }
    }

    boolean valid(long nowMs) {
        return initialized && hasYaw && nowMs - lastYawT <= GAP_INVALID_MS;
    }

    double headingDeg() {
        return wrap360(heading);
    }

    /** Heading at {@code tMs}, wrapped to [0, 360); NaN outside the ~5 s history. */
    double headingAt(long tMs) {
        double h = rawHeadingAt(tMs);
        return Double.isNaN(h) ? h : wrap360(h);
    }

    /** Bias-corrected heading change from {@code fromMs} to {@code toMs}, radians, counter-clockwise positive. */
    double deltaRadCcw(long fromMs, long toMs) {
        return -Math.toRadians(rawHeadingAt(toMs) - rawHeadingAt(fromMs));
    }

    double sigmaDeg(long nowMs) {
        return Math.hypot(BASE_SIGMA_DEG, SIGMA_GROWTH_DPS * Math.max(0, nowMs - lastCorrT) / 1000.0);
    }

    double biasDps() {
        return bias;
    }

    /** Standstill bias updates so far (the service persists the bias every so many). */
    int biasUpdates() {
        return biasUpdates;
    }

    /** Unwrapped heading at t: interpolated in the ring, the current value up to the gap limit past the newest sample. */
    private double rawHeadingAt(long t) {
        if (!hasYaw) {
            return Double.NaN;
        }
        if (t >= lastYawT) {
            return t - lastYawT <= GAP_INVALID_MS ? heading : Double.NaN;
        }
        // Walk back from the newest entry; the current (lastYawT, heading) closes the ring.
        long tNext = lastYawT;
        double hNext = heading;
        for (int k = 0; k < ringCount; k++) {
            int i = ((ringNewest - k) % RING_SIZE + RING_SIZE) % RING_SIZE;
            if (ringT[i] <= t) {
                if (tNext == ringT[i]) {
                    return ringH[i];
                }
                return ringH[i] + (hNext - ringH[i]) * (t - ringT[i]) / (double) (tNext - ringT[i]);
            }
            tNext = ringT[i];
            hNext = ringH[i];
        }
        return Double.NaN;
    }

    private static double wrap180(double a) {
        return ((a % 360) + 540) % 360 - 180;
    }

    private static double wrap360(double a) {
        double w = a % 360;
        return w < 0 ? w + 360 : w;
    }
}
