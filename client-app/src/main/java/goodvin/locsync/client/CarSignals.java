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
 * eCarX AdaptAPI car-signal IDs and their decoding, free of Android types so it can be unit-tested.
 *
 * <p>Decoded from the head unit's {@code ecarx.adaptapi.jar}
 * ({@code com.ecarx.xui.adaptapi.car.sensor.Sensor#getSensorLatestValue} / {@code getGera}),
 * Geely G426 recon of 2026-11-07. Continuous sensors are read with {@code getSensorLatestValue(int)},
 * which returns a float and, for an unsupported id, a float whose raw bits are exactly {@code 0x1};
 * event sensors (gear) are read with {@code getSensorEvent(int)}, which defaults to -1.
 */
final class CarSignals {

    // ISensor.SENSOR_TYPE_* — continuous (getSensorLatestValue)
    static final int SPEED = 0x100100;          // CAR_SPEED, m/s
    static final int SPEED_IPK = 0x101a00;      // CAR_SPEED_FROM_IPK: what the cluster shows, m/s
    static final int FUEL = 0x100600;           // FUEL_LEVEL: integer mL in the float's bits on G426 (see fuelLitres)
    static final int RANGE = 0x100800;          // ENDURANCE_MILEAGE, km
    static final int OUTSIDE_TEMP = 0x100b00;   // TEMPERATURE_AMBIENT, °C (unverified)
    static final int ODOMETER = 0x100700;       // ODOMETER, km
    static final int STEERING_ANGLE = 0x101000; // STEERING_WHEEL_ANGLE: raw bits to the track log, readability unknown
    // event (getSensorEvent)
    static final int GEAR = 0x200200;

    private static final int GEAR_PARK = 0x200230;
    private static final int GEAR_REVERSE = 0x200240;
    private static final int GEAR_NEUTRAL = 0x200210;
    private static final int GEAR_DRIVE = 0x200220;
    private static final int GEAR_FIRST = 0x200201;
    private static final int GEAR_TENTH = 0x20020a;

    private CarSignals() {
    }

    /** Immutable readout. Any value field may be null when that signal isn't reported. */
    static final class Snapshot {
        final Float speedKmh, speedIpkKmh, fuelLitres, rangeKm, outsideTempC, odometerKm;
        final String gear;
        // Steering angle as the float's raw bits: the encoding is unknown (fuel turned out to be an
        // integer in the bits), so it is only recorded. Not counted by hasAny(): it reads 0 parked.
        final Integer steerBits;
        final long readElapsedMs;   // SystemClock.elapsedRealtime() of the read; 0 for EMPTY

        Snapshot(Float speedKmh, Float speedIpkKmh, String gear, Float fuelLitres, Float rangeKm,
                 Float outsideTempC, Float odometerKm, Integer steerBits, long readElapsedMs) {
            this.speedKmh = speedKmh;
            this.speedIpkKmh = speedIpkKmh;
            this.gear = gear;
            this.fuelLitres = fuelLitres;
            this.rangeKm = rangeKm;
            this.outsideTempC = outsideTempC;
            this.odometerKm = odometerKm;
            this.steerBits = steerBits;
            this.readElapsedMs = readElapsedMs;
        }

        boolean hasAny() {
            return speedKmh != null || speedIpkKmh != null || gear != null || fuelLitres != null
                    || rangeKm != null || outsideTempC != null || odometerKm != null;
        }

        static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null, null, 0);
    }

    /** Raw AdaptAPI readings → a Snapshot in display units. */
    static Snapshot fromRaw(float speedMps, float speedIpkMps, int gearCode, float fuel, float range,
                            float temp, float odo, float steer, long readElapsedMs) {
        Float odometer = latest(odo);
        // A real car never reads 0 km: that's the wake-up placeholder, and while it shows, fuel and
        // range zeros are placeholders too. With a real odometer a zero range (empty tank) is kept.
        boolean waking = odometer != null && odometer <= 0;
        return new Snapshot(speedKmh(latest(speedMps)), speedKmh(latest(speedIpkMps)), gearLabel(gearCode),
                waking ? positive(fuelLitres(fuel)) : fuelLitres(fuel), waking ? positive(latest(range)) : latest(range),
                latest(temp), waking ? null : odometer,
                Float.floatToRawIntBits(steer) == 1 ? null : Float.floatToRawIntBits(steer), readElapsedMs);
    }

    /**
     * Fuel in litres. The G426 returns an integer number of millilitres in the float's raw bits
     * (drive of 2026-11-08: bits 0x7467 = 29799 → 29.8 L, falling with range), which reads as a tiny
     * subnormal float. A positive subnormal is decoded that way; a normal float passes through as
     * litres, for units that report it properly.
     */
    static Float fuelLitres(float raw) {
        Float v = latest(raw);
        int bits = Float.floatToRawIntBits(raw);
        if (v != null && bits > 1 && bits < 0x00800000) {
            return bits / 1000f;
        }
        return v;
    }

    private static Float positive(Float v) {
        return v != null && v > 0 ? v : null;
    }

    /** A getSensorLatestValue result, or null for the "unsupported" sentinel and non-finite values. */
    static Float latest(float raw) {
        if (Float.floatToRawIntBits(raw) == 1 || Float.isNaN(raw) || Float.isInfinite(raw)) {
            return null;
        }
        return raw;
    }

    /** m/s → km/h; reverse may report negative speed, shown as positive. */
    static Float speedKmh(Float mps) {
        return mps == null ? null : Math.abs(mps) * 3.6f;
    }

    /** ISensorEvent.GEAR_* code → "P"/"R"/"N"/"D"/"1".."10", or null when there's no gear. */
    static String gearLabel(int code) {
        switch (code) {
            case GEAR_PARK: return "P";
            case GEAR_REVERSE: return "R";
            case GEAR_NEUTRAL: return "N";
            case GEAR_DRIVE: return "D";
            default:
                return code >= GEAR_FIRST && code <= GEAR_TENTH
                        ? Integer.toString(code - GEAR_FIRST + 1) : null;
        }
    }

    /**
     * Recent wheel speeds (km/h) by read time, to sample the car's speed at a GPS fix's measurement
     * time. Written by the poll thread, read by the owner's thread.
     */
    static final class SpeedHistory {
        static final long MAX_GAP_MS = 600;     // don't interpolate across a longer hole
        static final long MAX_AHEAD_MS = 300;   // past the newest sample, hold it this long
        private static final int SIZE = 32;
        private final long[] times = new long[SIZE];
        private final float[] speeds = new float[SIZE];
        private int count, next;

        /** A null speed (not readable) clears the history, so nothing is interpolated across it. */
        synchronized void add(long elapsedMs, Float kmh) {
            if (kmh == null) {
                count = 0;
                return;
            }
            if (count > 0 && elapsedMs <= times[(next + SIZE - 1) % SIZE]) {
                return;   // out of order or duplicate
            }
            times[next] = elapsedMs;
            speeds[next] = kmh;
            next = (next + 1) % SIZE;
            count = Math.min(count + 1, SIZE);
        }

        /** Speed at {@code t}, interpolated between the samples around it; null when not covered. */
        synchronized Float at(long t) {
            if (count == 0) {
                return null;
            }
            int newest = (next + SIZE - 1) % SIZE;
            if (t >= times[newest]) {
                return t - times[newest] <= MAX_AHEAD_MS ? speeds[newest] : null;
            }
            for (int k = 1; k < count; k++) {
                int i = (newest - k + SIZE) % SIZE;   // walk back from the newest
                if (times[i] <= t) {
                    int j = (i + 1) % SIZE;
                    long gap = times[j] - times[i];
                    if (gap > MAX_GAP_MS) {
                        return null;
                    }
                    return speeds[i] + (speeds[j] - speeds[i]) * (t - times[i]) / (float) gap;
                }
            }
            return null;   // older than everything kept
        }
    }

    /**
     * GPS speed / wheel speed. The wheel signal reads ~2.8% under GPS on the G426 (drive of
     * 2026-11-08), and tyres differ, so the factor is learned on steady, clean highway fixes and
     * persisted. GPS lags the wheels by ~400 ms, hence the steadiness condition.
     */
    static final class WheelCalibration {
        static final double MIN = 0.9, MAX = 1.1;
        private static final double ALPHA = 0.05;
        private static final int WARMUP = 20;              // accepted fixes before the tight window
        private static final double WINDOW = 0.04, WARMUP_WINDOW = 0.15;
        private static final double MIN_KMH = 40;
        private static final double MAX_SPEED_ACC_MPS = 0.5;
        private static final double MAX_CHANGE_KMH = 0.5;  // wheel change over the last second

        private double factor;
        private int accepted;

        /** @param learned the persisted factor, or null to start from 1 with a wide window */
        WheelCalibration(Float learned) {
            factor = learned == null ? 1.0 : Math.max(MIN, Math.min(MAX, learned));
            accepted = learned == null ? 0 : WARMUP;
        }

        double factor() {
            return factor;
        }

        int accepted() {
            return accepted;
        }

        /**
         * One fix. {@code wheelSecondBefore} is the wheel speed one second before the fix's
         * measurement time. Returns true when the fix was used.
         */
        boolean update(double gpsKmh, double gpsSpeedAccMps, double wheelKmh, double wheelSecondBefore) {
            if (gpsKmh < MIN_KMH || wheelKmh < MIN_KMH
                    || !(gpsSpeedAccMps > 0 && gpsSpeedAccMps <= MAX_SPEED_ACC_MPS)
                    || Math.abs(wheelKmh - wheelSecondBefore) > MAX_CHANGE_KMH) {
                return false;
            }
            double ratio = gpsKmh / wheelKmh;
            if (Math.abs(ratio - factor) > (accepted < WARMUP ? WARMUP_WINDOW : WINDOW)) {
                return false;   // a GPS speed glitch, not the tyres
            }
            factor = Math.max(MIN, Math.min(MAX, factor + ALPHA * (ratio - factor)));
            accepted++;
            return true;
        }
    }

    /**
     * One AdaptAPI W4m group value ({@code ISensor.getSensorGroupLatestValue(0x800400)}): the car's yaw rate,
     * lateral/longitudinal acceleration and the four wheel speeds, as the ESC sees them. Recorded raw: units
     * and whether the G426 fills it at all are what the next drive is for.
     */
    static final class W4m {
        static final int GROUP = 0x800400;
        final float yawRate, latAcc, lonAcc, vFL, vFR, vRL, vRR, steer;
        final int gear, interval;
        final long tick;

        private W4m(float yawRate, float latAcc, float lonAcc, float vFL, float vFR, float vRL, float vRR,
                    float steer, int gear, long tick, int interval) {
            this.yawRate = yawRate; this.latAcc = latAcc; this.lonAcc = lonAcc;
            this.vFL = vFL; this.vFR = vFR; this.vRL = vRL; this.vRR = vRR; this.steer = steer;
            this.gear = gear; this.tick = tick; this.interval = interval;
        }

        /** Null when every float is NaN (the getters failed): nothing worth a row. */
        static W4m of(float yawRate, float latAcc, float lonAcc, float vFL, float vFR, float vRL, float vRR,
                      float steer, int gear, long tick, int interval) {
            float[] all = {yawRate, latAcc, lonAcc, vFL, vFR, vRL, vRR, steer};
            for (float v : all) {
                if (!Float.isNaN(v)) {
                    return new W4m(yawRate, latAcc, lonAcc, vFL, vFR, vRL, vRR, steer, gear, tick, interval);
                }
            }
            return null;
        }
    }

    /**
     * The census probe's CSV cell: {@code hexid=value} pairs joined by ';' (never a comma) for the values that
     * changed since the last row, and every value once a minute so a drive can be reconstructed from any point.
     */
    static final class ProbeDiff {
        static final long FULL_EVERY_MS = 60_000;
        private final int[] ids;
        private final double[] last;
        private long lastFullMs = Long.MIN_VALUE;

        ProbeDiff(int[] ids) {
            this.ids = ids.clone();
            last = new double[ids.length];
        }

        String row(long nowMs, double[] values) {
            boolean full = lastFullMs == Long.MIN_VALUE || nowMs - lastFullMs >= FULL_EVERY_MS;
            if (full) {
                lastFullMs = nowMs;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ids.length; i++) {
                double v = values[i];
                boolean same = Double.compare(v, last[i]) == 0;
                last[i] = v;
                if (!full && same) {
                    continue;
                }
                if (sb.length() > 0) sb.append(';');
                sb.append(Integer.toHexString(ids[i])).append('=');
                if (Double.isNaN(v)) {
                    sb.append("nan");
                } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                    sb.append((long) v);
                } else {
                    sb.append((float) v);
                }
            }
            return sb.toString();
        }
    }

    /** True when a failure means the vendor SDK isn't on this unit at all (stop trying), not a transient error. */
    static boolean isMissingSdk(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ClassNotFoundException || c instanceof NoClassDefFoundError
                    || c instanceof ExceptionInInitializerError) {
                return true;
            }
        }
        return false;
    }
}
