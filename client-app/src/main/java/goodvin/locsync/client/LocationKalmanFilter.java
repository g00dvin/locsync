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
 * Kalman filter for smoothing a moving GPS position. Operates in a local ENU (east/north metres)
 * frame anchored at the first fix. State: [e, n, ve, vn]. Full-state measurement (position +
 * GPS-derived velocity) applied as sequential scalar updates (the measurement noise is diagonal,
 * so no matrix inversion is needed). Pure Java — no Android APIs — so it is unit-testable.
 *
 * <p>Motion model: constant velocity, or — with the turn model enabled — a coordinated turn with
 * a known turn rate ω (the velocity vector rotates at ω while its magnitude stays constant). ω is
 * not part of the state: it is estimated from the heading change between fixes and smoothed,
 * which keeps the filter linear (a plain KF with a time-varying transition matrix).
 *
 * <p>The state refers to the time of the last fix; {@link #extrapolate(double)} projects it to the
 * output time without touching the filter, which is how the caller compensates fix latency.
 */
public class LocationKalmanFilter {

    private static final double M_PER_DEG_LAT = 111320.0;
    private static final double REANCHOR_M = 10_000.0;
    private static final double MIN_POS_SIGMA = 1.0;
    private static final double UNKNOWN_VEL_SIGMA = 50.0; // wide prior when speed is unknown (m/s)
    // Android reports accuracy as the 68% radius; for a circular 2D Gaussian that is
    // sqrt(-2 ln 0.32) ≈ 1.51 sigma (a 1-sigma circle holds only ~39%).
    private static final double RADIUS68_PER_SIGMA = 1.5096;
    private static final double MIN_SPEED_FOR_HEADING = 0.1; // m/s; below this heading is undefined

    // Turn-rate estimation. Below TURN_MIN_SPEED the heading is too noisy to differentiate, so ω
    // decays to zero. TURN_ALPHA smooths the per-fix estimate; MAX_TURN_RATE (~46°/s) bounds it to
    // what a car can do (a U-turn at walking pace) so a heading glitch can't spin the prediction.
    private static final double TURN_MIN_SPEED = 2.0;   // m/s
    private static final double TURN_ALPHA = 0.5;
    private static final double MAX_TURN_RATE = 0.8;    // rad/s
    private static final double MAX_TURN_DT = 3.0;      // s; older heading is too stale to difference

    private double sigmaA;                  // process acceleration noise (m/s^2)
    private final double defaultSpeedSigma; // fallback velocity measurement noise (m/s)
    private boolean turnModel = true;

    private boolean initialized = false;
    private double lat0, lon0, mPerDegLon;

    // state [e, n, ve, vn] and its covariance
    private final double[] x = new double[4];
    private final double[][] P = new double[4][4];
    // turn rate (rad/s, counter-clockwise positive in the E/N plane) and its estimation inputs
    private double omega = 0;
    private double prevHeading = Double.NaN; // rad, math angle atan2(vn, ve) at the previous fix
    private double sincePrevHeading = 0;     // s of prediction since prevHeading was taken

    // reused scratch (this runs at 10 Hz; avoid per-call allocation)
    private final double[][] F = new double[4][4];
    private final double[][] scratch = new double[4][4];
    private final double[] xOut = new double[4];
    private final double[][] pOut = new double[4][4];
    private final Estimate estimate = new Estimate();

    /** Filter output projected to some time; see {@link #extrapolate(double)}. Reused between calls. */
    public static final class Estimate {
        public double latitude, longitude, speed, bearingDeg;
        /** Legacy 1-sigma horizontal accuracy (m). */
        public double accuracy;
        /** 68% horizontal radius (m) — {@code Location.getAccuracy()} semantics. */
        public double accuracy68;
        /** 1-sigma speed accuracy (m/s) and bearing accuracy (deg, 180 when undefined). */
        public double speedAccuracy, bearingAccuracyDeg;
    }

    public LocationKalmanFilter(double sigmaA, double defaultSpeedSigma) {
        this.sigmaA = sigmaA;
        this.defaultSpeedSigma = defaultSpeedSigma;
    }

    /** Enables the coordinated-turn motion model; when off, the filter is constant-velocity. */
    public void setTurnModel(boolean enabled) {
        turnModel = enabled;
        if (!enabled) {
            omega = 0;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void reset() {
        initialized = false;
        omega = 0;
        prevHeading = Double.NaN;
        sincePrevHeading = 0;
    }

    public void update(double lat, double lon, double speed, double bearingDeg,
                       double accuracy, double speedAccuracy, double bearingAccuracyDeg) {
        update(lat, lon, speed, bearingDeg, accuracy, speedAccuracy, bearingAccuracyDeg, true, true);
    }

    public void update(double lat, double lon, double speed, double bearingDeg,
                       double accuracy, double speedAccuracy, double bearingAccuracyDeg,
                       boolean hasSpeed, boolean hasBearing) {
        boolean applyVel = hasSpeed && hasBearing;
        if (!initialized) {
            setAnchor(lat, lon);
            x[0] = 0;
            x[1] = 0;
            double sp = posSigma(accuracy);
            zero(P);
            P[0][0] = sp * sp;
            P[1][1] = sp * sp;
            if (applyVel) {
                double br = Math.toRadians(bearingDeg);
                x[2] = speed * Math.sin(br);
                x[3] = speed * Math.cos(br);
                double sv = velSigma(speed, speedAccuracy, bearingAccuracyDeg);
                P[2][2] = sv * sv;
                P[3][3] = sv * sv;
            } else {
                // Velocity unknown: start at zero but with a wide prior so subsequent position
                // motion — not a fake speed=0 — establishes it.
                x[2] = 0;
                x[3] = 0;
                P[2][2] = UNKNOWN_VEL_SIGMA * UNKNOWN_VEL_SIGMA;
                P[3][3] = UNKNOWN_VEL_SIGMA * UNKNOWN_VEL_SIGMA;
            }
            initialized = true;
            updateTurnRate(applyVel, speed, bearingDeg);
            return;
        }

        maybeReanchor();
        double em = (lon - lon0) * mPerDegLon;
        double nm = (lat - lat0) * M_PER_DEG_LAT;
        double sp = posSigma(accuracy);
        scalarUpdate(0, em, sp * sp);
        scalarUpdate(1, nm, sp * sp);

        if (applyVel) {
            double br = Math.toRadians(bearingDeg);
            double vem = speed * Math.sin(br);
            double vnm = speed * Math.cos(br);
            double sv = velSigma(speed, speedAccuracy, bearingAccuracyDeg);
            scalarUpdate(2, vem, sv * sv);
            scalarUpdate(3, vnm, sv * sv);
        }
        updateTurnRate(applyVel, speed, bearingDeg);
    }

    /** Advances the filter state by dt seconds (motion model + process noise). */
    public void predict(double dt) {
        if (!initialized || dt <= 0) {
            return;
        }
        propagate(x, P, dt, omega);
        sincePrevHeading += dt;
    }

    /**
     * Projects the current state {@code horizonS} seconds ahead (0 = the state itself) without
     * changing the filter. The returned object is reused by the next call.
     */
    public Estimate extrapolate(double horizonS) {
        System.arraycopy(x, 0, xOut, 0, 4);
        for (int i = 0; i < 4; i++) {
            System.arraycopy(P[i], 0, pOut[i], 0, 4);
        }
        if (horizonS > 0) {
            propagate(xOut, pOut, horizonS, omega);
        }
        Estimate est = estimate;
        est.latitude = lat0 + xOut[1] / M_PER_DEG_LAT;
        est.longitude = lon0 + xOut[0] / mPerDegLon;
        est.speed = Math.hypot(xOut[2], xOut[3]);
        est.bearingDeg = bearingDeg(xOut);
        est.accuracy = accuracy1Sigma(pOut);
        est.accuracy68 = Math.max(MIN_POS_SIGMA, RADIUS68_PER_SIGMA * posSigma1(pOut));
        est.speedAccuracy = speedAccuracy(xOut, pOut);
        est.bearingAccuracyDeg = bearingAccuracyDeg(xOut, pOut);
        return est;
    }

    /**
     * x = F x, P = F P F^T + Q. F is the coordinated-turn transition for turn rate w (reducing to
     * constant velocity as w → 0); Q is the discrete white-noise-acceleration model.
     */
    private void propagate(double[] s, double[][] cov, double dt, double w) {
        buildTransition(dt, w);
        double e = s[0], n = s[1], ve = s[2], vn = s[3];
        s[0] = e + F[0][2] * ve + F[0][3] * vn;
        s[1] = n + F[1][2] * ve + F[1][3] * vn;
        s[2] = F[2][2] * ve + F[2][3] * vn;
        s[3] = F[3][2] * ve + F[3][3] * vn;

        // scratch = F cov
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += F[i][k] * cov[k][j];
                }
                scratch[i][j] = sum;
            }
        }
        // cov = scratch F^T
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += scratch[i][k] * F[j][k];
                }
                cov[i][j] = sum;
            }
        }
        double s2 = sigmaA * sigmaA;
        double dt2 = dt * dt, dt3 = dt2 * dt, dt4 = dt3 * dt;
        double qpp = s2 * dt4 / 4.0, qpv = s2 * dt3 / 2.0, qvv = s2 * dt2;
        cov[0][0] += qpp; cov[0][2] += qpv; cov[2][0] += qpv; cov[2][2] += qvv;
        cov[1][1] += qpp; cov[1][3] += qpv; cov[3][1] += qpv; cov[3][3] += qvv;
    }

    private void buildTransition(double dt, double w) {
        zero(F);
        F[0][0] = 1;
        F[1][1] = 1;
        double wt = w * dt;
        double sinTerm, cosTerm, c, s; // sin(wt)/w, (1-cos(wt))/w, cos(wt), sin(wt)
        if (Math.abs(wt) < 1e-6) {
            sinTerm = dt;
            cosTerm = w * dt * dt / 2.0;
            c = 1;
            s = wt;
        } else {
            s = Math.sin(wt);
            c = Math.cos(wt);
            sinTerm = s / w;
            cosTerm = (1 - c) / w;
        }
        // position integrates the rotating velocity; velocity rotates counter-clockwise by wt
        F[0][2] = sinTerm;  F[0][3] = -cosTerm;
        F[1][2] = cosTerm;  F[1][3] = sinTerm;
        F[2][2] = c;        F[2][3] = -s;
        F[3][2] = s;        F[3][3] = c;
    }

    /**
     * Estimates the turn rate from the heading change since the previous fix. Uses the measured
     * GPS bearing when present (Doppler-derived, better than differencing noisy positions), else
     * the filter's own velocity heading.
     */
    private void updateTurnRate(boolean hasMeasuredVel, double speed, double bearingDeg) {
        if (!turnModel) {
            omega = 0;
            prevHeading = Double.NaN;
            return;
        }
        double heading;
        if (hasMeasuredVel && speed >= TURN_MIN_SPEED) {
            heading = Math.PI / 2 - Math.toRadians(bearingDeg);
        } else if (Math.hypot(x[2], x[3]) >= TURN_MIN_SPEED) {
            heading = Math.atan2(x[3], x[2]);
        } else {
            omega = 0; // too slow to tell; straight-line prediction is the safe default
            prevHeading = Double.NaN;
            sincePrevHeading = 0;
            return;
        }
        if (!Double.isNaN(prevHeading) && sincePrevHeading > 0 && sincePrevHeading <= MAX_TURN_DT) {
            double raw = wrapPi(heading - prevHeading) / sincePrevHeading;
            if (Math.abs(raw) > 2 * MAX_TURN_RATE) {
                // Physically implausible for a car (e.g. a heading flip from position noise with no
                // Doppler bearing): not a turn, so decay towards straight rather than chase it.
                omega *= 1 - TURN_ALPHA;
            } else {
                omega += TURN_ALPHA * (raw - omega);
                omega = Math.max(-MAX_TURN_RATE, Math.min(MAX_TURN_RATE, omega));
            }
        }
        prevHeading = heading;
        sincePrevHeading = 0;
    }

    private static double wrapPi(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // Sequential scalar Kalman update for measurement of state component `idx` (H row = unit vector).
    private void scalarUpdate(int idx, double z, double r) {
        double yInnov = z - x[idx];
        double s = P[idx][idx] + r;
        double[] k = new double[4];
        for (int i = 0; i < 4; i++) {
            k[i] = P[i][idx] / s;
        }
        for (int i = 0; i < 4; i++) {
            x[i] += k[i] * yInnov;
        }
        // P = (I - K H) P ; H = e_idx^T  =>  P -= K * P[idx, :]
        double[] row = new double[4];
        System.arraycopy(P[idx], 0, row, 0, 4);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                P[i][j] -= k[i] * row[j];
            }
        }
    }

    private double posSigma(double accuracy) {
        return accuracy > 0 ? accuracy : 10.0;
    }

    private double velSigma(double speed, double speedAccuracy, double bearingAccuracyDeg) {
        double base = speedAccuracy > 0 ? speedAccuracy : defaultSpeedSigma;
        double crossTrack = 0;
        if (bearingAccuracyDeg > 0) {
            crossTrack = Math.abs(speed) * Math.sin(Math.toRadians(bearingAccuracyDeg));
        }
        return Math.hypot(base, crossTrack);
    }

    private void setAnchor(double lat, double lon) {
        lat0 = lat;
        lon0 = lon;
        mPerDegLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));
        if (Math.abs(mPerDegLon) < 1.0) {
            mPerDegLon = 1.0; // guard near the poles
        }
    }

    private void maybeReanchor() {
        if (Math.abs(x[0]) > REANCHOR_M || Math.abs(x[1]) > REANCHOR_M) {
            // shift origin to current estimate's lat/lon, keep velocities
            double curLat = lat0 + x[1] / M_PER_DEG_LAT;
            double curLon = lon0 + x[0] / mPerDegLon;
            setAnchor(curLat, curLon);
            x[0] = 0;
            x[1] = 0;
        }
    }

    public double getLatitude() {
        return lat0 + x[1] / M_PER_DEG_LAT;
    }

    public double getLongitude() {
        return lon0 + x[0] / mPerDegLon;
    }

    public double getSpeed() {
        return Math.hypot(x[2], x[3]);
    }

    public double getBearingDeg() {
        return bearingDeg(x);
    }

    /** Current turn rate in degrees per second, clockwise positive (like a compass bearing). */
    public double getTurnRateDegPerSec() {
        return -Math.toDegrees(omega);
    }

    public double getAccuracy() {
        return accuracy1Sigma(P);
    }

    /** Horizontal accuracy as the 68% radius, matching {@code Location.getAccuracy()} semantics. */
    public double getAccuracy68() {
        return Math.max(MIN_POS_SIGMA, RADIUS68_PER_SIGMA * posSigma1(P));
    }

    /** 1-sigma (≈68%) speed accuracy: velocity variance projected onto the direction of travel. */
    public double getSpeedAccuracy() {
        return speedAccuracy(x, P);
    }

    /** 1-sigma (≈68%) bearing accuracy in degrees, from the cross-track velocity variance. */
    public double getBearingAccuracyDeg() {
        return bearingAccuracyDeg(x, P);
    }

    public double getVe() {
        return x[2];
    }

    public double getVn() {
        return x[3];
    }

    private static double bearingDeg(double[] s) {
        double b = Math.toDegrees(Math.atan2(s[2], s[3]));
        return (b % 360 + 360) % 360;
    }

    private static double posSigma1(double[][] cov) {
        return Math.sqrt((cov[0][0] + cov[1][1]) / 2.0);
    }

    private static double accuracy1Sigma(double[][] cov) {
        return Math.max(MIN_POS_SIGMA, posSigma1(cov));
    }

    private static double speedAccuracy(double[] s, double[][] cov) {
        double speed = Math.hypot(s[2], s[3]);
        if (speed < MIN_SPEED_FOR_HEADING) {
            return Math.sqrt((cov[2][2] + cov[3][3]) / 2.0);
        }
        return Math.sqrt(Math.max(0, projectVelVar(cov, s[2] / speed, s[3] / speed)));
    }

    private static double bearingAccuracyDeg(double[] s, double[][] cov) {
        double speed = Math.hypot(s[2], s[3]);
        if (speed < MIN_SPEED_FOR_HEADING) {
            return 180.0;
        }
        // unit vector perpendicular to travel
        double crossSigma = Math.sqrt(Math.max(0, projectVelVar(cov, s[3] / speed, -s[2] / speed)));
        return Math.min(180.0, Math.toDegrees(Math.atan2(crossSigma, speed)));
    }

    private static double projectVelVar(double[][] cov, double ue, double un) {
        return ue * ue * cov[2][2] + 2 * ue * un * cov[2][3] + un * un * cov[3][3];
    }

    private static void zero(double[][] a) {
        for (double[] row : a) {
            java.util.Arrays.fill(row, 0.0);
        }
    }
}
