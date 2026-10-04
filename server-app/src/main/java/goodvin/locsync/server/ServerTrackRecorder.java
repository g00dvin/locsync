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

package goodvin.locsync.server;

import android.location.Location;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Phone-side half of a drive recording: every fix as the provider delivered it, before it goes to
 * the client. Together with the head unit's track-client.csv this separates delays in the
 * provider (fix time vs. delivery), on the phone (delivery vs. send) and over Wi-Fi.
 *
 * <p>{@code source}: {@code fused} / {@code gps} = the fix the server uses; {@code gps_ref} = raw
 * GPS recorded alongside Fused for comparison only (never sent). Buffered, flushed ~1/s, rotated
 * to {@code .old} above 30 MB. Main thread only.
 */
final class ServerTrackRecorder {
    private static final String TAG = "ServerTrackRecorder";
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    static final String HEADER = "wall_ms,elapsed_ms,source,fix_time_ms,fix_elapsed_ms,lat,lon,alt,acc,"
            + "speed,bearing,spd_acc,brg_acc,vert_acc,sats,sent,client";

    private final File file;
    private BufferedWriter writer;
    private long bytes;

    ServerTrackRecorder(File dir) {
        this.file = fileFor(dir);
    }

    static File fileFor(File dir) {
        return new File(dir, "track-server.csv");
    }

    void fix(long elapsedMs, String source, Location l, int sats, boolean sent, boolean clientPresent) {
        StringBuilder sb = new StringBuilder(200);
        sb.append(System.currentTimeMillis()).append(',').append(elapsedMs).append(',').append(source).append(',')
                .append(l.getTime()).append(',').append(l.getElapsedRealtimeNanos() / 1_000_000L).append(',');
        sb.append(String.format(Locale.US, "%.7f,%.7f,", l.getLatitude(), l.getLongitude()));
        num(sb, l.hasAltitude(), l.getAltitude(), 1);
        num(sb, l.hasAccuracy(), l.getAccuracy(), 2);
        num(sb, l.hasSpeed(), l.getSpeed(), 2);
        num(sb, l.hasBearing(), l.getBearing(), 2);
        num(sb, l.hasSpeedAccuracy(), l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : 0, 2);
        num(sb, l.hasBearingAccuracy(), l.hasBearingAccuracy() ? l.getBearingAccuracyDegrees() : 0, 2);
        num(sb, l.hasVerticalAccuracy(), l.hasVerticalAccuracy() ? l.getVerticalAccuracyMeters() : 0, 2);
        sb.append(sats).append(',').append(sent ? 1 : 0).append(',').append(clientPresent ? 1 : 0);
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

    private static void num(StringBuilder sb, boolean has, double v, int decimals) {
        if (has) sb.append(String.format(Locale.US, "%." + decimals + "f", v));
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
