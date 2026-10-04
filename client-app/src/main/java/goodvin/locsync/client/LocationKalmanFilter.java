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
 * <p>Outliers (multipath jumps in urban canyons) are handled with a Mahalanobis gate on the
 * position innovation: a fix whose normalised innovation squared exceeds the χ² threshold is not
 * dropped but de-weighted (its noise inflated until it sits on the gate), so a genuine jump is
 * still followed — and after several consecutive outliers the filter re-anchors on the fixes.
 *
 * <p>Standstill: GNSS errors at rest are strongly time-correlated, so a parked car's position
 * "wanders" and navigation apps re-route at traffic lights. When the Doppler speed says we are
 * stopped, the filter applies a zero-velocity update and heavily de-weights the position (ZUPT).
 *
 * <p>Adaptive process noise: one fixed σa is a compromise — too stiff in turns, too jittery on
 * straights. With adaptation on, σa grows with the lateral acceleration of the current turn
 * (v·|ω|) and with a running average of the innovation (fixes persistently disagreeing with the
 * model means the model is too confident); it never drops below the configured base.
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

    // After this many consecutive gated fixes the filter, not the fixes, is presumed wrong.
    private static final int REINIT_AFTER_OUTLIERS = 5;

    // Standstill (ZUPT): velocity is pinned to zero with this sigma and position noise is scaled up
    // by the factor below (σ ×5), so correlated at-rest wander barely moves the estimate.
    private static final double ZUPT_SIGMA = 0.05;             // m/s
    private static final double STANDSTILL_POS_VAR_SCALE = 25.0;
    private static final double STANDSTILL_MAX_SPEED_ACC = 1.0; // m/s; distrust "stopped" if worse

    // Adaptive process noise. NIS of a consistent 2D update averages 2; its running mean above that
    // scales σa by sqrt(mean/2), capped. Lateral acceleration adds in quadrature.
    private static final double NIS_EXPECTED = 2.0;
    private static final double NIS_EMA_ALPHA = 0.2;
    private static final double MAX_NIS_SCALE = 3.0;
    private static final double TURN_ACCEL_GAIN = 1.0;
    private static final double MAX_LATERAL_ACCEL = 8.0; // m/s², ~0.8 g: beyond this it isn't a car

    // Adaptive position noise (see adaptPositionNoise).
    private static final double POS_NIS_ALPHA = 0.1;
    private static final double POS_R_RATE = 0.1;
    private static final double MIN_POS_R_SCALE = 0.25;   // trust positions at most 4x more than claimed
    private boolean adaptivePosition = true;
    private double posRScale = 1.0;
    private double posNisEma = 2.0;

    private double sigmaA;                  // base process acceleration noise (m/s^2)
    private boolean adaptiveNoise = true;
    private double sigmaAEff;               // σa actually used by the motion model
    private double nisEma = NIS_EXPECTED;
    private final double defaultSpeedSigma; // fallback velocity measurement noise (m/s)
    private boolean turnModel = true;
    // Bearing quantization (whole degrees) detection and compensation, plus a floor on the bearing
    // accuracy a source may claim. Detection: running share of whole-degree bearings.
    private static final double BEARING_QUANT_ALPHA = 0.05;
    private static final double BEARING_QUANT_DETECT = 0.9;
    private boolean bearingCompensation = true;
    private double minBearingAccuracyDeg = 2.0;
    private double integerBearingShare = 0;
    private boolean gating = true;
    private double gateThreshold = 9.21;    // χ² with 2 dof at 99%
    private boolean standstillHold = true;
    private double standstillSpeed = 0.5;   // m/s
    private boolean stationary = false;

    // Innovation diagnostics (counters survive reset(): they describe the whole session).
    private double lastNis = Double.NaN;
    private int consecutiveOutliers = 0;
    private long outlierCount = 0, reinitCount = 0;

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
        this.sigmaAEff = sigmaA;
        this.defaultSpeedSigma = defaultSpeedSigma;
    }

    /** Base process noise σa (m/s²) and whether it adapts to turns and innovation. */
    public void setProcessNoise(double base, boolean adaptive) {
        if (base > 0) {
            sigmaA = base;
        }
        adaptiveNoise = adaptive;
        updateProcessNoise();
    }

    /** Enables the coordinated-turn motion model; when off, the filter is constant-velocity. */
    public void setTurnModel(boolean enabled) {
        turnModel = enabled;
        if (!enabled) {
            omega = 0;
        }
    }

    /**
     * Compensates whole-degree bearing truncation (+0.5° once detected) and never trusts a bearing
     * more than {@code minAccuracyDeg}.
     */
    public void setBearingHandling(boolean compensateQuantization, double minAccuracyDeg) {
        bearingCompensation = compensateQuantization;
        minBearingAccuracyDeg = Math.max(0, minAccuracyDeg);
    }

    /** Adapt how much reported position accuracy is trusted from the innovation statistics. */
    public void setAdaptivePosition(boolean enabled) {
        adaptivePosition = enabled;
        if (!enabled) {
            posRScale = 1.0;
        }
    }

    /** Factor applied to the reported position variance (1 = as reported, 0.25 = 2x tighter σ). */
    public double getPositionNoiseScale() {
        return posRScale;
    }

    /** Whether incoming bearings look truncated to whole degrees (and are being compensated). */
    public boolean isBearingQuantized() {
        return integerBearingShare > BEARING_QUANT_DETECT;
    }

    /** Mahalanobis gating of position fixes; {@code threshold} is χ² with 2 degrees of freedom. */
    public void setGating(boolean enabled, double threshold) {
        gating = enabled;
        if (threshold > 0) {
            gateThreshold = threshold;
        }
    }

    /** Zero-velocity update + position de-weighting while the measured speed is below {@code speed}. */
    public void setStandstill(boolean enabled, double speed) {
        standstillHold = enabled;
        if (speed > 0) {
            standstillSpeed = speed;
        }
        if (!enabled) {
            stationary = false;
        }
    }

    /** Whether the last fix was treated as standstill. */
    public boolean isStationary() {
        return stationary;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void reset() {
        initialized = false;
        nisEma = NIS_EXPECTED;
        sigmaAEff = sigmaA;
        stationary = false;
        consecutiveOutliers = 0;
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
        if (hasBearing) {
            // Track whether the source quantizes bearing to whole degrees.
            boolean whole = Math.abs(bearingDeg - Math.rint(bearingDeg)) < 1e-6;
            integerBearingShare += BEARING_QUANT_ALPHA * ((whole ? 1 : 0) - integerBearingShare);
            if (bearingCompensation && integerBearingShare > BEARING_QUANT_DETECT) {
                // Fused truncates bearing to whole degrees: on average it reads 0.5° left of the
                // true course. Trusted at ±0.7°, that bias steered the estimate ~1.4 m to the left.
                bearingDeg += 0.5;
            }
            bearingAccuracyDeg = Math.max(bearingAccuracyDeg, minBearingAccuracyDeg);
        }
        updateInternal(lat, lon, speed, bearingDeg, accuracy, speedAccuracy, bearingAccuracyDeg, hasSpeed, hasBearing);
    }

    private void updateInternal(double lat, double lon, double speed, double bearingDeg,
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
        double r = sp * sp * posRScale;
        // Only a real Doppler speed can say "stopped" (no speed, e.g. in a tunnel, is not zero).
        stationary = standstillHold && hasSpeed && speed < standstillSpeed
                && (speedAccuracy <= 0 || speedAccuracy <= STANDSTILL_MAX_SPEED_ACC);
        if (stationary) {
            r *= STANDSTILL_POS_VAR_SCALE;
        }

        // Normalised innovation squared of the 2D position: d² = νᵀ S⁻¹ ν, S = P_pos + R.
        double ne = em - x[0], nn = nm - x[1];
        double s00 = P[0][0] + r, s11 = P[1][1] + r, s01 = P[0][1];
        double det = s00 * s11 - s01 * s01;
        lastNis = det > 0 ? (ne * ne * s11 - 2 * ne * nn * s01 + nn * nn * s00) / det : 0;
        if (gating && lastNis > gateThreshold) {
            outlierCount++;
            if (++consecutiveOutliers >= REINIT_AFTER_OUTLIERS) {
                // The fixes agree with each other but not with us: we diverged (or the car really
                // jumped, e.g. after a ferry). Start over from this fix.
                reinitCount++;
                reset();
                updateInternal(lat, lon, speed, bearingDeg, accuracy, speedAccuracy, bearingAccuracyDeg, hasSpeed, hasBearing);
                return;
            }
            // Inflating R by d²/τ puts the fix exactly on the gate: it still pulls, but gently.
            r *= lastNis / gateThreshold;
        } else {
            consecutiveOutliers = 0;
        }
        // Outliers are the gate's job; don't let them also loosen the motion model.
        double nisSample = gating ? Math.min(lastNis, gateThreshold) : lastNis;
        nisEma += NIS_EMA_ALPHA * (nisSample - nisEma);
        if (!stationary && consecutiveOutliers == 0) {
            adaptPositionNoise();
        }
        scalarUpdate(0, em, r);
        scalarUpdate(1, nm, r);

        if (stationary) {
            scalarUpdate(2, 0, ZUPT_SIGMA * ZUPT_SIGMA);
            scalarUpdate(3, 0, ZUPT_SIGMA * ZUPT_SIGMA);
            omega = 0;
            prevHeading = Double.NaN;
            sincePrevHeading = 0;
            updateProcessNoise();
            return;
        }
        if (applyVel) {
            double br = Math.toRadians(bearingDeg);
            double vem = speed * Math.sin(br);
            double vnm = speed * Math.cos(br);
            double sv = velSigma(speed, speedAccuracy, bearingAccuracyDeg);
            scalarUpdate(2, vem, sv * sv);
            scalarUpdate(3, vnm, sv * sv);
        }
        updateTurnRate(applyVel, speed, bearingDeg);
        updateProcessNoise();
    }

    /**
     * Fused claims ±4–10 m but its track is far smoother than that (position NIS median 0.05 on a
     * real drive, where 2 is expected), so the filter leaned on velocity and lagged the fixes. Scale
     * the position noise down, slowly and within [MIN_POS_R_SCALE, 1], until the NIS mean of moving,
     * non-outlier fixes approaches its expected value. Learned once per source, kept across resets.
     */
    private void adaptPositionNoise() {
        posNisEma += POS_NIS_ALPHA * (lastNis - posNisEma);
        if (adaptivePosition) {
            posRScale *= Math.pow(Math.max(1e-3, posNisEma) / NIS_EXPECTED, POS_R_RATE);
            posRScale = Math.max(MIN_POS_R_SCALE, Math.min(1.0, posRScale));
        } else {
            posRScale = 1.0;
        }
    }

    private void updateProcessNoise() {
        if (!adaptiveNoise || stationary) {
            sigmaAEff = sigmaA;
            return;
        }
        double lateral = TURN_ACCEL_GAIN * Math.min(MAX_LATERAL_ACCEL, Math.hypot(x[2], x[3]) * Math.abs(omega));
        double scale = Math.max(1.0, Math.min(MAX_NIS_SCALE, Math.sqrt(nisEma / NIS_EXPECTED)));
        sigmaAEff = Math.hypot(sigmaA, lateral) * scale;
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
        double s2 = sigmaAEff * sigmaAEff;
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

    /** Normalised innovation squared of the last position fix (χ², 2 dof; ~2 on average). */
    public double getLastNis() {
        return lastNis;
    }

    /** Process noise σa (m/s²) currently used by the motion model. */
    public double getProcessNoise() {
        return sigmaAEff;
    }

    /** Running mean of the position NIS (≈2 when the model and the fixes agree). */
    public double getNisAverage() {
        return nisEma;
    }

    /** Fixes de-weighted by the gate this session. */
    public long getOutlierCount() {
        return outlierCount;
    }

    /** Times the filter re-anchored after consecutive outliers this session. */
    public long getReinitCount() {
        return reinitCount;
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
