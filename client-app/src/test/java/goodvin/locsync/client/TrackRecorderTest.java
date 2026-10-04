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
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class TrackRecorderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // Both row kinds must line up with the header, or the CSV can't be replayed/analysed.
    @Test
    public void rowsMatchHeaderColumns() throws Exception {
        File dir = tmp.newFolder();
        TrackRecorder r = new TrackRecorder(dir);
        LocationKalmanFilter f = new LocationKalmanFilter(2.0, 1.0);
        f.update(55.75, 37.6, 10.0, 90.0, 4.0, 0.3, 5.0);
        r.fix(1000, 1_700_000_000_000L, "fused", 17, 55.75, 37.6, 150.0, 4.0f,
                true, 10.0f, true, 90.0f, 0.3f, 5.0f, 0.04f, 40, f);
        r.fix(2000, 1_700_000_001_000L, "gps", 0, 55.75, 37.6, 150.0, 4.0f,
                false, 0f, false, 0f, 0f, 0f, 0.05f, 50, f);
        r.out(2100, 55.75, 37.6, true, 10.0, 90.0, 6.0, 0.3, Double.NaN, 100);
        r.out(2200, 55.75, 37.6, false, 0.1, 0, 6.0, Double.NaN, Double.NaN, 200);
        r.close();

        List<String> lines = Files.readAllLines(TrackRecorder.fileFor(dir).toPath());
        assertEquals(TrackRecorder.HEADER, lines.get(0));
        int cols = TrackRecorder.HEADER.split(",", -1).length;
        assertEquals(5, lines.size());
        for (String line : lines.subList(1, lines.size())) {
            assertEquals(line, cols, line.split(",", -1).length);
        }
        assertTrue(lines.get(1).contains(",fix,"));
        assertTrue(lines.get(3).contains(",out,"));
    }

    // Recording is appended across service restarts; the header is written only once.
    @Test
    public void appendsWithoutRepeatingHeader() throws Exception {
        File dir = tmp.newFolder();
        LocationKalmanFilter f = new LocationKalmanFilter(2.0, 1.0);
        f.update(55.75, 37.6, 0, 0, 4.0, 0.3, 5.0);
        for (int i = 0; i < 2; i++) {
            TrackRecorder r = new TrackRecorder(dir);
            r.out(i, 55.75, 37.6, false, 0, 0, 5, Double.NaN, Double.NaN, 0);
            r.close();
        }
        List<String> lines = Files.readAllLines(TrackRecorder.fileFor(dir).toPath());
        assertEquals(3, lines.size());
    }
}
