/*
 * Copyright © 2026 LocSync
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.shared;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The recorded track CSVs (client or server) as a GPX file any map app opens: the phone's fixes in order,
 * one track segment per file and per pause. Reads the columns by name ({@code lat}, {@code lon}, {@code alt},
 * the fix time as {@code fix_time_ms} or {@code fix_ts}; with a {@code type} column only its {@code fix} rows),
 * so both apps' files and older headers work. Android-free.
 */
public final class Gpx {
    static final long SEGMENT_GAP_MS = 60_000;

    private Gpx() {
    }

    /** Writes {@code out} from the CSVs (oldest first) and returns the number of points; unreadable files are skipped. */
    public static int write(File[] csvs, File out) throws IOException {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        int points = 0;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"LocSync\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>LocSync</name>\n");
            for (File csv : csvs) {
                boolean open = false;
                long last = 0;
                try (BufferedReader r = new BufferedReader(new FileReader(csv))) {
                    String header = r.readLine();
                    if (header == null) {
                        continue;
                    }
                    List<String> cols = Arrays.asList(header.split(",", -1));
                    int lat = cols.indexOf("lat"), lon = cols.indexOf("lon"), alt = cols.indexOf("alt");
                    int time = Math.max(cols.indexOf("fix_time_ms"), cols.indexOf("fix_ts")), type = cols.indexOf("type");
                    if (lat < 0 || lon < 0 || time < 0) {
                        continue;
                    }
                    for (String line; (line = r.readLine()) != null; ) {
                        String[] c = line.split(",", -1);
                        if (c.length <= Math.max(Math.max(lat, lon), time) || c[lat].isEmpty() || c[lon].isEmpty()
                                || c[time].isEmpty() || (type >= 0 && !"fix".equals(c[type]))) {
                            continue;
                        }
                        long t;
                        try {
                            t = Long.parseLong(c[time]);
                            Double.parseDouble(c[lat]);
                            Double.parseDouble(c[lon]);
                        } catch (NumberFormatException e) {
                            continue;   // a row cut short by a crash
                        }
                        if (open && Math.abs(t - last) > SEGMENT_GAP_MS) {
                            w.write("</trkseg>\n");
                            open = false;
                        }
                        if (!open) {
                            w.write("<trkseg>\n");
                            open = true;
                        }
                        last = t;
                        w.write("<trkpt lat=\"" + c[lat] + "\" lon=\"" + c[lon] + "\">");
                        if (alt >= 0 && alt < c.length && !c[alt].isEmpty()) {
                            w.write("<ele>" + c[alt] + "</ele>");
                        }
                        w.write("<time>" + iso.format(new Date(t)) + "</time></trkpt>\n");
                        points++;
                    }
                } catch (IOException e) {
                    // this file can't be read: the others still make a track
                }
                if (open) {
                    w.write("</trkseg>\n");
                }
            }
            w.write("</trk>\n</gpx>\n");
        }
        return points;
    }
}
