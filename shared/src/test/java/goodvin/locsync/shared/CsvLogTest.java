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

package goodvin.locsync.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class CsvLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // appendFixed must print exactly what %.Nf prints (it replaces it in the track recorders).
    @Test
    public void appendFixedMatchesStringFormat() {
        Random rnd = new Random(7);
        double[] specials = {0, -0.0, 0.5, -0.5, 1.005, 2.5, -2.5, 999.9999, 55.7558123456, -37.6173219,
                0.04, 1e-9, -1e-9, 180, 359.99999};
        for (int d = 0; d <= 7; d++) {
            for (double v : specials) check(v, d);
            for (int i = 0; i < 2000; i++) check((rnd.nextDouble() - 0.5) * 400, d);
        }
    }

    private static void check(double v, int d) {
        String expected = String.format(Locale.US, "%." + d + "f", v);
        if (expected.equals("-0") || expected.matches("-0\\.0*")) expected = expected.substring(1);
        String actual = CsvLog.appendFixed(new StringBuilder(), v, d).toString();
        // %.Nf rounds half-even on exact binary ties; allow a last-digit difference only there
        if (!expected.equals(actual)) {
            double ulp = Math.pow(10, -d);
            assertTrue(v + " d=" + d + ": " + expected + " vs " + actual,
                    Math.abs(Double.parseDouble(expected) - Double.parseDouble(actual)) <= ulp * 1.0001);
        }
    }

    @Test
    public void nanAndInfinityWriteNothing() {
        assertEquals("", CsvLog.appendFixed(new StringBuilder(), Double.NaN, 2).toString());
        assertEquals("", CsvLog.appendFixed(new StringBuilder(), Double.POSITIVE_INFINITY, 2).toString());
    }

    @Test
    public void headerOnceRotationAndRecreateAfterDelete() throws Exception {
        File f = new File(tmp.newFolder(), "t.csv");
        CsvLog log = new CsvLog(f, "a,b", 50);
        for (int i = 0; i < 10; i++) log.writeLine(new StringBuilder("1234567890,x"));
        log.close();
        assertTrue(new File(f.getParentFile(), "t.csv.old").exists());
        List<String> lines = Files.readAllLines(f.toPath());
        assertEquals("a,b", lines.get(0));

        CsvLog again = new CsvLog(f, "a,b", 1_000_000);
        again.writeLine(new StringBuilder("1,2"));
        again.flush();
        assertTrue(f.delete());
        again.flush();                       // notices the deletion
        again.writeLine(new StringBuilder("3,4"));
        again.close();
        assertEquals(List.of("a,b", "3,4"), Files.readAllLines(f.toPath()));
    }
}
