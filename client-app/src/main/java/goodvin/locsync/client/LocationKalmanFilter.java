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
 * Constant-velocity Kalman filter for smoothing a moving GPS position. Operates in a local ENU
 * (east/north metres) frame anchored at the first fix. State: [e, n, ve, vn]. Full-state
 * measurement (position + GPS-derived velocity) applied as sequential scalar updates (the
 * measurement noise is diagonal, so no matrix inversion is needed). Pure Java — no Android APIs —
 * so it is unit-testable.
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

    private final double sigmaA;            // process acceleration noise (m/s^2)
    private final double defaultSpeedSigma; // fallback velocity measurement noise (m/s)

    private boolean initialized = false;
    private double lat0, lon0, mPerDegLon;

    // state
    private double e, n, ve, vn;
    // covariance (4x4)
    private final double[][] P = new double[4][4];
    // reused scratch for the predict step's F*P product (avoids per-call allocation at 10 Hz)
    private final double[][] scratchFP = new double[4][4];

    public LocationKalmanFilter(double sigmaA, double defaultSpeedSigma) {
        this.sigmaA = sigmaA;
        this.defaultSpeedSigma = defaultSpeedSigma;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Forget all state so the next update() re-anchors fresh (e.g. after a reconnect). */
    public void reset() {
        initialized = false;
    }

    /** Full-measurement update. Treats speed and bearing as always present (backward-compatible). */
    public void update(double lat, double lon, double speed, double bearingDeg,
                       double accuracy, double speedAccuracy, double bearingAccuracyDeg) {
        update(lat, lon, speed, bearingDeg, accuracy, speedAccuracy, bearingAccuracyDeg, true, true);
    }

    /**
     * @param hasSpeed   whether {@code speed} is a real measurement. When the provider reports no
     *                   speed (e.g. GPS lost in a tunnel) this must be false — otherwise a spurious
     *                   speed=0 would be applied as a confident zero-velocity measurement, pinning
     *                   the estimate and freezing the smoothed position. When speed or bearing is
     *                   absent the velocity measurement is skipped entirely and velocity is inferred
     *                   from position motion instead.
     * @param hasBearing whether {@code bearingDeg} is a real measurement.
     */
    public void update(double lat, double lon, double speed, double bearingDeg,
                       double accuracy, double speedAccuracy, double bearingAccuracyDeg,
                       boolean hasSpeed, boolean hasBearing) {
        boolean applyVel = hasSpeed && hasBearing;
        if (!initialized) {
            setAnchor(lat, lon);
            e = 0;
            n = 0;
            double sp = posSigma(accuracy);
            zero(P);
            P[0][0] = sp * sp;
            P[1][1] = sp * sp;
            if (applyVel) {
                double br = Math.toRadians(bearingDeg);
                ve = speed * Math.sin(br);
                vn = speed * Math.cos(br);
                double sv = velSigma(speed, speedAccuracy, bearingAccuracyDeg);
                P[2][2] = sv * sv;
                P[3][3] = sv * sv;
            } else {
                // Velocity unknown: start at zero but with a wide prior so subsequent position
                // motion — not a fake speed=0 — establishes it.
                ve = 0;
                vn = 0;
                P[2][2] = UNKNOWN_VEL_SIGMA * UNKNOWN_VEL_SIGMA;
                P[3][3] = UNKNOWN_VEL_SIGMA * UNKNOWN_VEL_SIGMA;
            }
            initialized = true;
            return;
        }

        maybeReanchor(lat, lon);
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
    }

    public void predict(double dt) {
        if (!initialized || dt <= 0) {
            return;
        }
        // x = F x  (constant-velocity: position advances by velocity)
        e += ve * dt;
        n += vn * dt;
        // P = F P F^T + Q with F = [[1,0,dt,0],[0,1,0,dt],[0,0,1,0],[0,0,0,1]], computed in place with a
        // single reused scratch buffer — no per-call matrix allocation (this runs at 10 Hz).
        double[][] S = scratchFP;
        // S = F P: rows 0,1 gain dt * their velocity row; rows 2,3 unchanged.
        for (int j = 0; j < 4; j++) {
            S[0][j] = P[0][j] + dt * P[2][j];
            S[1][j] = P[1][j] + dt * P[3][j];
            S[2][j] = P[2][j];
            S[3][j] = P[3][j];
        }
        // P = S F^T: columns 0,1 gain dt * their velocity column; columns 2,3 unchanged.
        for (int i = 0; i < 4; i++) {
            P[i][0] = S[i][0] + dt * S[i][2];
            P[i][1] = S[i][1] + dt * S[i][3];
            P[i][2] = S[i][2];
            P[i][3] = S[i][3];
        }
        // process noise Q
        double s2 = sigmaA * sigmaA;
        double dt2 = dt * dt, dt3 = dt2 * dt, dt4 = dt3 * dt;
        double q_pp = dt4 / 4.0, q_pv = dt3 / 2.0, q_vv = dt2;
        P[0][0] += s2 * q_pp; P[0][2] += s2 * q_pv;
        P[2][0] += s2 * q_pv; P[2][2] += s2 * q_vv;
        P[1][1] += s2 * q_pp; P[1][3] += s2 * q_pv;
        P[3][1] += s2 * q_pv; P[3][3] += s2 * q_vv;
    }

    // Sequential scalar Kalman update for measurement of state component `idx` (H row = unit vector).
    private void scalarUpdate(int idx, double z, double r) {
        double[] x = {e, n, ve, vn};
        double yInnov = z - x[idx];
        double s = P[idx][idx] + r;
        double[] k = new double[4];
        for (int i = 0; i < 4; i++) {
            k[i] = P[i][idx] / s;
        }
        e += k[0] * yInnov;
        n += k[1] * yInnov;
        ve += k[2] * yInnov;
        vn += k[3] * yInnov;
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

    private void maybeReanchor(double lat, double lon) {
        if (Math.abs(e) > REANCHOR_M || Math.abs(n) > REANCHOR_M) {
            // shift origin to current estimate's lat/lon, keep velocities
            double curLat = lat0 + n / M_PER_DEG_LAT;
            double curLon = lon0 + e / mPerDegLon;
            setAnchor(curLat, curLon);
            e = 0;
            n = 0;
        }
    }

    public double getLatitude() {
        return lat0 + n / M_PER_DEG_LAT;
    }

    public double getLongitude() {
        return lon0 + e / mPerDegLon;
    }

    public double getSpeed() {
        return Math.hypot(ve, vn);
    }

    public double getBearingDeg() {
        double b = Math.toDegrees(Math.atan2(ve, vn));
        return (b % 360 + 360) % 360;
    }

    public double getAccuracy() {
        return Math.max(MIN_POS_SIGMA, Math.sqrt((P[0][0] + P[1][1]) / 2.0));
    }

    /** Horizontal accuracy as the 68% radius, matching {@code Location.getAccuracy()} semantics. */
    public double getAccuracy68() {
        return Math.max(MIN_POS_SIGMA, RADIUS68_PER_SIGMA * Math.sqrt((P[0][0] + P[1][1]) / 2.0));
    }

    /** 1-sigma (≈68%) speed accuracy: velocity variance projected onto the direction of travel. */
    public double getSpeedAccuracy() {
        double speed = getSpeed();
        if (speed < MIN_SPEED_FOR_HEADING) {
            return Math.sqrt((P[2][2] + P[3][3]) / 2.0);
        }
        double ue = ve / speed, un = vn / speed;
        return Math.sqrt(Math.max(0, projectVelVar(ue, un)));
    }

    /** 1-sigma (≈68%) bearing accuracy in degrees, from the cross-track velocity variance. */
    public double getBearingAccuracyDeg() {
        double speed = getSpeed();
        if (speed < MIN_SPEED_FOR_HEADING) {
            return 180.0;
        }
        double we = vn / speed, wn = -ve / speed; // unit vector perpendicular to travel
        double crossSigma = Math.sqrt(Math.max(0, projectVelVar(we, wn)));
        return Math.min(180.0, Math.toDegrees(Math.atan2(crossSigma, speed)));
    }

    private double projectVelVar(double ue, double un) {
        return ue * ue * P[2][2] + 2 * ue * un * P[2][3] + un * un * P[3][3];
    }

    public double getVe() {
        return ve;
    }

    public double getVn() {
        return vn;
    }

    private static void zero(double[][] a) {
        for (double[] row : a) {
            java.util.Arrays.fill(row, 0.0);
        }
    }
}
