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

import goodvin.locsync.shared.CsvLog;

import java.io.File;

/**
 * The head unit's own GNSS receiver, recorded next to the track while "Record track" is on: its fixes (ours,
 * the mock, are skipped by the caller), satellite status at most once a second, and raw NMEA (the last
 * column, verbatim). Tells whether it is good enough to be a second, Wi-Fi-independent position source.
 * Called from the service thread only.
 */
final class GnssRecorder {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long STATUS_EVERY_MS = 1000;
    static final String HEADER = "wall_ms,elapsed_ms,type,lat,lon,alt,acc,speed,bearing,spd_acc,brg_acc,"
            + "fix_elapsed_ms,mock,provider,svs_seen,svs_used,cn0_top4,constellations,nmea";

    private final CsvLog log;
    private final StringBuilder sb = new StringBuilder(160);
    private long lastStatusMs = Long.MIN_VALUE;

    GnssRecorder(File dir) {
        log = new CsvLog(fileFor(dir), HEADER, MAX_BYTES);
    }

    static File fileFor(File dir) {
        return new File(dir, "gnss-client.csv");
    }

    void fix(long elapsedMs, double lat, double lon, double alt, float acc, float speed, float bearing,
             float spdAcc, float brgAcc, long fixElapsedMs, boolean mock, String provider) {
        start(elapsedMs, "fix");
        num(lat, 7); num(lon, 7); num(alt, 1); num(acc, 2); num(speed, 2); num(bearing, 1);
        num(spdAcc, 2); num(brgAcc, 1);
        sb.append(fixElapsedMs).append(',').append(mock ? 1 : 0).append(',')
                .append(provider == null ? "" : provider.replace(',', ';')).append(",,,,,");
        log.writeLine(sb);
    }

    /** False (nothing written) within a second of the previous status row. */
    boolean status(long elapsedMs, int seen, int used, double cn0Top4, String constellations) {
        if (lastStatusMs != Long.MIN_VALUE && elapsedMs - lastStatusMs < STATUS_EVERY_MS) {
            return false;
        }
        lastStatusMs = elapsedMs;
        start(elapsedMs, "status");
        sb.append(",,,,,,,,,,,");   // lat .. provider
        sb.append(seen).append(',').append(used).append(',');
        num(cn0Top4, 1);
        sb.append(constellations == null ? "" : constellations.replace(',', ';')).append(',');
        log.writeLine(sb);
        return true;
    }

    void nmea(long elapsedMs, String sentence) {
        start(elapsedMs, "nmea");
        sb.append(",,,,,,,,,,,,,,,");   // lat .. constellations
        sb.append(sentence.trim());
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
        if (!Double.isNaN(v)) CsvLog.appendFixed(sb, v, decimals);
        sb.append(',');
    }
}
