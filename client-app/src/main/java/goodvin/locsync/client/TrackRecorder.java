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

import java.io.File;

import goodvin.locsync.shared.CsvLog;

/**
 * Records a drive for offline analysis: every raw fix from the server (with the filter state right
 * after it) and every location handed to the mock provider. Lets a real trip be replayed through
 * the filter to tune it. Buffered and flushed ~1/s by the caller; rotated to {@code .old} above
 * {@link #MAX_BYTES} (~4 MB per hour of driving, so that is many hours).
 *
 * <p>One CSV for all row kinds ({@code type} = fix | out | car); columns that don't apply are empty.
 * {@code car} rows are the 5 Hz wheel-speed polls with the gear ({@code car_gear}), so a drive replays
 * the way the service saw it; out rows say whether dead reckoning or the filter made them ({@code out_src}).
 * Fix rows also carry the head unit's wheel and cluster speed and raw steering ({@code car_*}, empty
 * off eCarX units) so GPS speed can be compared with the car's. Both row kinds carry the gyro heading
 * ({@code gyro_*}) to compare with the GPS turn rate.
 * Called from the service thread only.
 */
final class TrackRecorder {
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    static final String HEADER = "wall_ms,elapsed_ms,type,fix_ts,provider,sats,lat,lon,alt,acc,"
            + "speed,bearing,spd_acc,brg_acc,age_s,latency_ms,horizon_ms,"
            + "f_lat,f_lon,f_speed,f_bearing,nis,stationary,turn_dps,sigma_a,"
            + "car_speed,car_speed_ipk,car_dt_ms,car_steer_bits,gyro_yaw_deg,gyro_dt_ms,car_gear,out_src,"
            + "w4m_tick,yaw_rate,lat_acc,lon_acc,v_fl,v_fr,v_rl,v_rr,w4m_steer,w4m_gear,acc_x,acc_y,acc_z,gyro_temp,probe";
    private static final String NO_CAR_EXTRA = ",,,,,,,,,,,,,,,";   // the 15 car-row-only columns

    final GyroYaw gyro;   // the service's, fed by its sensor listener
    private final CsvLog log;
    private final StringBuilder sb = new StringBuilder(256);   // reused: one row at a time

    TrackRecorder(File dir, GyroYaw gyro) {
        this.gyro = gyro;
        log = new CsvLog(fileFor(dir), HEADER, MAX_BYTES);
    }

    static File fileFor(File dir) {
        return new File(dir, "track-client.csv");
    }

    /**
     * A raw fix as received, plus the filter state after it was applied, plus the head unit's speed
     * ({@code car} may be null). {@code car_dt_ms} (only when a car speed is present) is when the car value was read relative to the fix's
     * arrival ({@code elapsedMs}); signed, not clamped. The GPS value itself describes the moment
     * {@code arrival - latency_ms}.
     */
    void fix(long elapsedMs, long fixTs, String provider, int sats, double lat, double lon, double alt, float acc,
             boolean hasSpeed, float speed, boolean hasBearing, float bearing, float spdAcc, float brgAcc,
             float ageS, long latencyMs, LocationKalmanFilter f, CarSignals.Snapshot car) {
        start(elapsedMs, "fix");
        sb.append(fixTs).append(',').append(provider == null ? "" : provider).append(',').append(sats).append(',');
        num(lat, 7); num(lon, 7); num(alt, 1); num(acc, 2);
        if (hasSpeed) num(speed, 2); else sb.append(',');
        if (hasBearing) num(bearing, 1); else sb.append(',');
        num(spdAcc, 2); num(brgAcc, 1); num(ageS, 3);
        sb.append(latencyMs).append(",,");
        num(f.getLatitude(), 7); num(f.getLongitude(), 7);
        num(f.getSpeed(), 2); num(f.getBearingDeg(), 1);
        num(f.getLastNis(), 2);
        sb.append(f.isStationary() ? 1 : 0).append(',');
        num(f.getTurnRateDegPerSec(), 2);
        CsvLog.appendFixed(sb, f.getProcessNoise(), 3).append(',');
        if (car != null && (car.speedKmh != null || car.speedIpkKmh != null)) {
            optional(car.speedKmh, 2); optional(car.speedIpkKmh, 2);
            sb.append(car.readElapsedMs - elapsedMs);
        } else {
            sb.append(",,");
        }
        tail(elapsedMs, car == null ? null : car.steerBits);
        end(car == null ? null : car.gear, null);
    }

    /**
     * One wheel-speed poll (km/h, null = not readable) and the newest gear, with what else the car and the
     * head unit said at that moment: the W4m group, the raw accelerometer (m/s²), the gyro temperature (°C)
     * and the AdaptAPI census probe (once a second). Any of them may be null/NaN.
     */
    void car(long elapsedMs, Float speedKmh, String gear, CarSignals.W4m w4m, float[] acc, float gyroTempC,
             String probe) {
        start(elapsedMs, "car");
        sb.append(",,,,,,,,,,,,,,,,,,,,,,");   // fix_ts .. sigma_a
        optional(speedKmh, 2);
        sb.append(",");                           // car_speed_ipk; car_dt_ms empty (tail adds its comma)
        tail(elapsedMs, null);
        endWith(gear, null);
        if (w4m != null) {
            sb.append(',').append(w4m.tick);
            fixed(w4m.yawRate, 4); fixed(w4m.latAcc, 3); fixed(w4m.lonAcc, 3);
            fixed(w4m.vFL, 3); fixed(w4m.vFR, 3); fixed(w4m.vRL, 3); fixed(w4m.vRR, 3); fixed(w4m.steer, 2);
            sb.append(',').append(w4m.gear);
        } else {
            sb.append(",,,,,,,,,,");
        }
        if (acc != null) {
            fixed(acc[0], 3); fixed(acc[1], 3); fixed(acc[2], 3);
        } else {
            sb.append(",,,");
        }
        fixed(gyroTempC, 1);
        sb.append(',');
        if (probe != null) sb.append(probe);
        log.writeLine(sb);
    }

    /** ',' then the value; NaN leaves the cell empty. */
    private void fixed(double v, int decimals) {
        sb.append(',');
        if (!Double.isNaN(v)) CsvLog.appendFixed(sb, v, decimals);
    }

    /** A location handed to the mock provider. NaN accuracies are written as empty cells. */
    void out(long elapsedMs, double lat, double lon, boolean moving, double speed, double bearing,
             double acc, double spdAcc, double brgAcc, double horizonMs, String src) {
        start(elapsedMs, "out");
        sb.append(",,,");                 // fix_ts, provider, sats
        num(lat, 7); num(lon, 7); sb.append(',');  // alt
        num(acc, 2);
        if (moving) { num(speed, 2); num(bearing, 1); } else sb.append(",,");
        num(spdAcc, 2); num(brgAcc, 1);
        sb.append(",,");                  // age_s, latency_ms
        CsvLog.appendFixed(sb, horizonMs, 0).append(",,,,,,,,,,,");   // filter + car columns
        tail(elapsedMs, null);
        end(null, src);
    }

    private void end(String gear, String src) {
        endWith(gear, src);
        sb.append(NO_CAR_EXTRA);
        log.writeLine(sb);
    }

    private void endWith(String gear, String src) {
        sb.append(',');
        if (gear != null) sb.append(gear);
        sb.append(',');
        if (src != null) sb.append(src);
    }

    void flush() {
        log.flush();
    }

    void close() {
        log.close();
    }

    private void start(long elapsedMs, String type) {
        sb.setLength(0);
        sb.append(System.currentTimeMillis()).append(',').append(elapsedMs).append(',').append(type).append(',');
    }

    /**
     * The cells after car_dt_ms: steering bits, then the gyro heading (integrated, raw: see
     * {@link GyroYaw}) and when its newest sample was taken relative to the row, like car_dt_ms.
     */
    private void tail(long elapsedMs, Integer steerBits) {
        sb.append(',');
        if (steerBits != null) sb.append(steerBits);
        sb.append(',');
        if (gyro.ready()) {
            CsvLog.appendFixed(sb, gyro.yawDeg(), 3).append(',').append(gyro.lastNs() / 1_000_000 - elapsedMs);
        } else {
            sb.append(',');
        }
    }

    /** Like {@link #num} for a value that may be absent. */
    private void optional(Float v, int decimals) {
        if (v == null) sb.append(','); else num(v, decimals);
    }

    /** Number followed by a comma; NaN leaves the cell empty. */
    private void num(double v, int decimals) {
        CsvLog.appendFixed(sb, v, decimals).append(',');
    }
}
