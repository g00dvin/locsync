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

import java.io.File;

import goodvin.locsync.shared.CsvLog;

/**
 * Phone-side half of a drive recording: every fix as the provider delivered it, before it goes to
 * the client. Together with the head unit's track-client.csv this separates delays in the
 * provider (fix time vs. delivery), on the phone (delivery vs. send) and over Wi-Fi.
 *
 * <p>{@code source}: {@code fused} / {@code gps} = the fix the server uses; {@code gps_ref} = raw
 * GPS recorded alongside Fused for comparison only (never sent). Flushed ~1/s by the caller,
 * rotated to {@code .old} above 30 MB. Main thread only.
 */
final class ServerTrackRecorder {
    private static final long MAX_BYTES = 30L * 1024 * 1024;
    static final String HEADER = "wall_ms,elapsed_ms,source,fix_time_ms,fix_elapsed_ms,lat,lon,alt,acc,"
            + "speed,bearing,spd_acc,brg_acc,vert_acc,sats,sent,client";

    private final CsvLog log;
    private final StringBuilder sb = new StringBuilder(200);

    ServerTrackRecorder(File dir) {
        log = new CsvLog(fileFor(dir), HEADER, MAX_BYTES);
    }

    static File fileFor(File dir) {
        return new File(dir, "track-server.csv");
    }

    void fix(long elapsedMs, String source, Location l, int sats, boolean sent, boolean clientPresent) {
        sb.setLength(0);
        sb.append(System.currentTimeMillis()).append(',').append(elapsedMs).append(',').append(source).append(',')
                .append(l.getTime()).append(',').append(l.getElapsedRealtimeNanos() / 1_000_000L).append(',');
        num(true, l.getLatitude(), 7);
        num(true, l.getLongitude(), 7);
        num(l.hasAltitude(), l.getAltitude(), 1);
        num(l.hasAccuracy(), l.getAccuracy(), 2);
        num(l.hasSpeed(), l.getSpeed(), 2);
        num(l.hasBearing(), l.getBearing(), 2);
        num(l.hasSpeedAccuracy(), l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : 0, 2);
        num(l.hasBearingAccuracy(), l.hasBearingAccuracy() ? l.getBearingAccuracyDegrees() : 0, 2);
        num(l.hasVerticalAccuracy(), l.hasVerticalAccuracy() ? l.getVerticalAccuracyMeters() : 0, 2);
        sb.append(sats).append(',').append(sent ? 1 : 0).append(',').append(clientPresent ? 1 : 0);
        log.writeLine(sb);
    }

    void flush() {
        log.flush();
    }

    void close() {
        log.close();
    }

    private void num(boolean has, double v, int decimals) {
        if (has) CsvLog.appendFixed(sb, v, decimals);
        sb.append(',');
    }
}
