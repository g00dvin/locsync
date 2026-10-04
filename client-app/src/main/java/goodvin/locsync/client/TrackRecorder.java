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

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Records a drive for offline analysis: every raw fix from the server (with the filter state right
 * after it) and every location handed to the mock provider. Lets a real trip be replayed through
 * the filter to tune it. Rows are buffered and flushed ~1/s; the file rotates to {@code .old} when
 * it outgrows {@link #MAX_BYTES} (~4 MB per hour of driving, so that is many hours).
 *
 * <p>One CSV for both row kinds ({@code type} = fix | out); columns that don't apply are empty.
 * Called from the service's main thread only.
 */
final class TrackRecorder {
    private static final String TAG = "TrackRecorder";
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    static final String HEADER = "wall_ms,elapsed_ms,type,fix_ts,provider,sats,lat,lon,alt,acc,"
            + "speed,bearing,spd_acc,brg_acc,age_s,latency_ms,horizon_ms,"
            + "f_lat,f_lon,f_speed,f_bearing,nis,stationary,turn_dps,sigma_a";

    private final File file;
    private BufferedWriter writer;
    private long bytes;

    TrackRecorder(File dir) {
        this.file = fileFor(dir);
    }

    static File fileFor(File dir) {
        return new File(dir, "track-client.csv");
    }

    /** A raw fix as received, plus the filter state after it was applied. */
    void fix(long elapsedMs, long fixTs, String provider, int sats, double lat, double lon, double alt, float acc,
             boolean hasSpeed, float speed, boolean hasBearing, float bearing, float spdAcc, float brgAcc,
             float ageS, long latencyMs, LocationKalmanFilter f) {
        StringBuilder sb = start(elapsedMs, "fix");
        sb.append(fixTs).append(',').append(provider == null ? "" : provider).append(',').append(sats).append(',');
        deg(sb, lat); deg(sb, lon); num(sb, alt, 1); num(sb, acc, 2);
        if (hasSpeed) num(sb, speed, 2); else sb.append(',');
        if (hasBearing) num(sb, bearing, 1); else sb.append(',');
        num(sb, spdAcc, 2); num(sb, brgAcc, 1); num(sb, ageS, 3);
        sb.append(latencyMs).append(",,");
        deg(sb, f.getLatitude()); deg(sb, f.getLongitude());
        num(sb, f.getSpeed(), 2); num(sb, f.getBearingDeg(), 1);
        num(sb, f.getLastNis(), 2);
        sb.append(f.isStationary() ? 1 : 0).append(',');
        num(sb, f.getTurnRateDegPerSec(), 2);
        sb.append(String.format(Locale.US, "%.3f", f.getProcessNoise()));
        write(sb);
    }

    /** A location handed to the mock provider. NaN accuracies are written as empty cells. */
    void out(long elapsedMs, double lat, double lon, boolean moving, double speed, double bearing,
             double acc, double spdAcc, double brgAcc, double horizonMs) {
        StringBuilder sb = start(elapsedMs, "out");
        sb.append(",,,");                 // fix_ts, provider, sats
        deg(sb, lat); deg(sb, lon); sb.append(',');  // alt
        num(sb, acc, 2);
        if (moving) { num(sb, speed, 2); num(sb, bearing, 1); } else sb.append(",,");
        num(sb, spdAcc, 2); num(sb, brgAcc, 1);
        sb.append(",,");                  // age_s, latency_ms
        sb.append(String.format(Locale.US, "%.0f", horizonMs)).append(",,,,,,,,");
        write(sb);
    }

    void flush() {
        if (writer == null) return;
        if (!file.exists()) {
            // Deleted by "Clear logs": drop the stale handle; the next row starts a fresh file.
            closeQuietly();
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            Log.w(TAG, "flush failed", e);
            closeQuietly();
        }
    }

    void close() {
        flush();
        closeQuietly();
    }

    private StringBuilder start(long elapsedMs, String type) {
        StringBuilder sb = new StringBuilder(220);
        sb.append(System.currentTimeMillis()).append(',')
                .append(elapsedMs).append(',')
                .append(type).append(',');
        return sb;
    }

    private static void deg(StringBuilder sb, double v) {
        sb.append(String.format(Locale.US, "%.7f", v)).append(',');
    }

    private static void num(StringBuilder sb, double v, int decimals) {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) {
            sb.append(String.format(Locale.US, "%." + decimals + "f", v));
        }
        sb.append(',');
    }

    private void write(StringBuilder line) {
        try {
            if (writer == null) open();
            if (bytes > MAX_BYTES) {
                closeQuietly();
                File old = new File(file.getParentFile(), file.getName() + ".old");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
                open();
            }
            line.append('\n');
            writer.append(line);
            bytes += line.length();
        } catch (IOException e) {
            Log.w(TAG, "write failed", e);
            closeQuietly();
        }
    }

    private void open() throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        boolean needHeader = !file.exists() || file.length() == 0;
        writer = new BufferedWriter(new FileWriter(file, true), 16 * 1024);
        bytes = file.length();
        if (needHeader) {
            writer.append(HEADER).append('\n');
            bytes += HEADER.length() + 1;
        }
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            writer = null;
        }
    }
}
