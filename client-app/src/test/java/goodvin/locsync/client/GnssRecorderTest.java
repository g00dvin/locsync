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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class GnssRecorderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rowsMatchHeader() throws Exception {
        File dir = tmp.newFolder();
        GnssRecorder r = new GnssRecorder(dir);
        r.fix(1000, 55.75, 37.6, 150.5, 4.5f, 10.2f, 90.5f, 0.3f, 2.5f, 990, false, "gps");
        r.status(1100, 30, 18, 38.24, "GPS:10,GLO:8");
        r.close();
        List<String> lines = Files.readAllLines(GnssRecorder.fileFor(dir).toPath());
        assertEquals(GnssRecorder.HEADER, lines.get(0));
        int cols = GnssRecorder.HEADER.split(",", -1).length;
        assertEquals(cols, lines.get(1).split(",", -1).length);
        assertEquals(cols, lines.get(2).split(",", -1).length);
        assertTrue(lines.get(1), lines.get(1).contains(",fix,55.7500000,37.6000000,150.5,4.50,10.20,90.5,0.30,2.5,990,0,gps,"));
        assertTrue(lines.get(2), lines.get(2).contains(",status,") && lines.get(2).contains(",30,18,38.2,GPS:10;GLO:8,"));
    }

    // NMEA is the last column, verbatim (commas and all), so a parser takes the rest of the line.
    @Test
    public void nmeaKeepsCommas() throws Exception {
        File dir = tmp.newFolder();
        GnssRecorder r = new GnssRecorder(dir);
        String s = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
        r.nmea(2000, s + "\r\n");
        r.close();
        List<String> lines = Files.readAllLines(GnssRecorder.fileFor(dir).toPath());
        assertTrue(lines.get(1), lines.get(1).endsWith("," + s));
        String head = lines.get(1).substring(0, lines.get(1).length() - s.length() - 1);
        assertEquals(GnssRecorder.HEADER.split(",", -1).length - 1, head.split(",", -1).length);
    }

    @Test
    public void statusAtMostOncePerSecond() throws Exception {
        GnssRecorder r = new GnssRecorder(tmp.newFolder());
        assertTrue(r.status(1000, 1, 1, 30, "GPS:1"));
        assertFalse(r.status(1500, 1, 1, 30, "GPS:1"));
        assertTrue(r.status(2000, 1, 1, 30, "GPS:1"));
        r.close();
    }
}
