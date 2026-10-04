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
 * <p>One CSV for both row kinds ({@code type} = fix | out); columns that don't apply are empty.
 * Called from the service's main thread only.
 */
final class TrackRecorder {
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    static final String HEADER = "wall_ms,elapsed_ms,type,fix_ts,provider,sats,lat,lon,alt,acc,"
            + "speed,bearing,spd_acc,brg_acc,age_s,latency_ms,horizon_ms,"
            + "f_lat,f_lon,f_speed,f_bearing,nis,stationary,turn_dps,sigma_a";

    private final CsvLog log;
    private final StringBuilder sb = new StringBuilder(256);   // reused: one row at a time

    TrackRecorder(File dir) {
        log = new CsvLog(fileFor(dir), HEADER, MAX_BYTES);
    }

    static File fileFor(File dir) {
        return new File(dir, "track-client.csv");
    }

    /** A raw fix as received, plus the filter state after it was applied. */
    void fix(long elapsedMs, long fixTs, String provider, int sats, double lat, double lon, double alt, float acc,
             boolean hasSpeed, float speed, boolean hasBearing, float bearing, float spdAcc, float brgAcc,
             float ageS, long latencyMs, LocationKalmanFilter f) {
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
        CsvLog.appendFixed(sb, f.getProcessNoise(), 3);
        log.writeLine(sb);
    }

    /** A location handed to the mock provider. NaN accuracies are written as empty cells. */
    void out(long elapsedMs, double lat, double lon, boolean moving, double speed, double bearing,
             double acc, double spdAcc, double brgAcc, double horizonMs) {
        start(elapsedMs, "out");
        sb.append(",,,");                 // fix_ts, provider, sats
        num(lat, 7); num(lon, 7); sb.append(',');  // alt
        num(acc, 2);
        if (moving) { num(speed, 2); num(bearing, 1); } else sb.append(",,");
        num(spdAcc, 2); num(brgAcc, 1);
        sb.append(",,");                  // age_s, latency_ms
        CsvLog.appendFixed(sb, horizonMs, 0).append(",,,,,,,,");
        log.writeLine(sb);
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

    /** Number followed by a comma; NaN leaves the cell empty. */
    private void num(double v, int decimals) {
        CsvLog.appendFixed(sb, v, decimals).append(',');
    }
}
