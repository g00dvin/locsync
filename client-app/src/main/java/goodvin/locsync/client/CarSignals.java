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
    static final int FUEL = 0x100600;           // FUEL_LEVEL, litres (unverified)
    static final int RANGE = 0x100800;          // ENDURANCE_MILEAGE, km
    static final int OUTSIDE_TEMP = 0x100b00;   // TEMPERATURE_AMBIENT, °C (unverified)
    static final int ODOMETER = 0x100700;       // ODOMETER, km
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
        final long readElapsedMs;   // SystemClock.elapsedRealtime() of the read; 0 for EMPTY

        Snapshot(Float speedKmh, Float speedIpkKmh, String gear, Float fuelLitres, Float rangeKm,
                 Float outsideTempC, Float odometerKm, long readElapsedMs) {
            this.speedKmh = speedKmh;
            this.speedIpkKmh = speedIpkKmh;
            this.gear = gear;
            this.fuelLitres = fuelLitres;
            this.rangeKm = rangeKm;
            this.outsideTempC = outsideTempC;
            this.odometerKm = odometerKm;
            this.readElapsedMs = readElapsedMs;
        }

        boolean hasAny() {
            return speedKmh != null || speedIpkKmh != null || gear != null || fuelLitres != null
                    || rangeKm != null || outsideTempC != null || odometerKm != null;
        }

        static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null, null, 0);
    }

    /** Raw AdaptAPI readings → a Snapshot in display units. */
    static Snapshot fromRaw(float speedMps, float speedIpkMps, int gearCode, float fuel, float range,
                            float temp, float odo, long readElapsedMs) {
        Float odometer = latest(odo);
        if (odometer != null && odometer <= 0) {
            odometer = null;   // a real car never reads 0 km; ignition-off placeholder
        }
        return new Snapshot(speedKmh(latest(speedMps)), speedKmh(latest(speedIpkMps)), gearLabel(gearCode),
                latest(fuel), latest(range), latest(temp), odometer, readElapsedMs);
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
