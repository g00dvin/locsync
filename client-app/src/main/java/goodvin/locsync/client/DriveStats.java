/*
 * Copyright © 2026 LocSync
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.client;

import java.util.Locale;

/**
 * One day's driving, kept across the head unit's naps as a short string in the preferences:
 * <ul>
 * <li>smoothness — hard brakings, hard accelerations and sharp turns per distance, from the wheel speed and
 * the gyro heading (no accelerometer: its mounting is unknown);</li>
 * <li>GPS pauses bridged by dead reckoning — how many, how long, and how far off the icon was when GPS
 * came back, which is the honest measure of the motion fusion.</li>
 * </ul>
 * Pure: times are SystemClock.elapsedRealtime() ms. Service thread only.
 */
final class DriveStats {
    // Thresholds over a one-second window, m/s². On the 2026-11-14 drive (159 km) they gave 1 event per
    // 10 km on the motorway leg and 6 per 10 km in town. Calibration knobs: a car and a driver differ.
    static final double BRAKE = 3.0, ACCEL = 2.5, TURN = 3.5;
    static final double REARM = 0.6;              // an event ends below this share of its threshold
    static final long WINDOW_MS = 1000, MAX_HOLE_MS = 3000;
    static final double POINTS_PER_EVENT = 10;    // off the score, per event per 10 km
    static final double MIN_SCORE_M = 1000;       // no score before the first kilometre
    private static final double SAVE_EVERY_M = 500;

    final int day;                                // yyyymmdd
    double distM;
    int brakes, accels, turns;
    int gaps;
    long gapMsTotal, gapMsMax;
    double gapErrMax, gapErrSum;

    private long refT = Long.MIN_VALUE, lastT;
    private double refV, refH;
    private boolean inBrake, inAccel, inTurn;
    private double savedAtM;

    DriveStats(int day) {
        this.day = day;
    }

    /**
     * One motion sample: wheel speed (m/s, negative in reverse, NaN = unreadable) and the gyro heading in
     * degrees (NaN = unknown: no turns are judged). Returns true when the stats are worth saving now.
     */
    boolean onMotion(long tMs, double mps, double headingDeg) {
        if (Double.isNaN(mps)) {
            refT = Long.MIN_VALUE;
            return false;
        }
        double v = Math.abs(mps);
        if (refT == Long.MIN_VALUE || tMs - lastT > MAX_HOLE_MS || tMs < lastT) {
            refT = tMs;
            refV = v;
            refH = headingDeg;
            lastT = tMs;
            return false;
        }
        distM += v * (tMs - lastT) / 1000.0;
        lastT = tMs;
        boolean event = false;
        if (tMs - refT >= WINDOW_MS) {
            double dt = (tMs - refT) / 1000.0;
            double lon = (v - refV) / dt;
            double turn = Math.abs((v + refV) / 2 * Math.toRadians(wrap180(headingDeg - refH)) / dt);   // NaN-safe below
            if (-lon > BRAKE && !inBrake) { brakes++; inBrake = true; event = true; } else if (-lon < REARM * BRAKE) inBrake = false;
            if (lon > ACCEL && !inAccel) { accels++; inAccel = true; event = true; } else if (lon < REARM * ACCEL) inAccel = false;
            if (turn > TURN && !inTurn) { turns++; inTurn = true; event = true; } else if (!(turn >= REARM * TURN)) inTurn = false;
            refT = tMs;
            refV = v;
            refH = headingDeg;
        }
        if (event || distM - savedAtM >= SAVE_EVERY_M) {
            savedAtM = distM;
            return true;
        }
        return false;
    }

    /** GPS came back after {@code durationMs} on dead reckoning, which had drifted {@code errorM} from the new fix. */
    void onGap(long durationMs, double errorM) {
        gaps++;
        gapMsTotal += durationMs;
        gapMsMax = Math.max(gapMsMax, durationMs);
        gapErrMax = Math.max(gapErrMax, errorM);
        gapErrSum += errorM;
    }

    /** 0–100, or -1 before {@link #MIN_SCORE_M}. */
    int score() {
        if (distM < MIN_SCORE_M) {
            return -1;
        }
        double per10km = (brakes + accels + turns) / (distM / 10_000);
        return (int) Math.round(Math.max(0, 100 - POINTS_PER_EVENT * per10km));
    }

    String encode() {
        return String.format(Locale.US, "%d,%.0f,%d,%d,%d,%d,%d,%d,%.1f,%.1f",
                day, distM, brakes, accels, turns, gaps, gapMsTotal, gapMsMax, gapErrMax, gapErrSum);
    }

    /** The stored day when it is {@code today}'s; a fresh one otherwise (another day, nothing stored, garbage). */
    static DriveStats decode(String s, int today) {
        DriveStats d = new DriveStats(today);
        try {
            String[] p = s == null ? new String[0] : s.split(",");
            if (p.length == 10 && Integer.parseInt(p[0]) == today) {
                d.distM = Double.parseDouble(p[1]);
                d.brakes = Integer.parseInt(p[2]);
                d.accels = Integer.parseInt(p[3]);
                d.turns = Integer.parseInt(p[4]);
                d.gaps = Integer.parseInt(p[5]);
                d.gapMsTotal = Long.parseLong(p[6]);
                d.gapMsMax = Long.parseLong(p[7]);
                d.gapErrMax = Double.parseDouble(p[8]);
                d.gapErrSum = Double.parseDouble(p[9]);
                d.savedAtM = d.distM;
            }
        } catch (NumberFormatException e) {
            return new DriveStats(today);
        }
        return d;
    }

    /** Today as yyyymmdd on the unit's clock. */
    static int today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    private static double wrap180(double a) {
        return ((a % 360) + 540) % 360 - 180;
    }
}
