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

import org.junit.Test;

public class MetricsTest {

    private static SystemStats stats(long elapsed, long cpuJiffies, long gcCount,
                                     long gcPauseMs, long allocBytes, int currentUa) {
        return new SystemStats(elapsed, cpuJiffies, gcCount, gcPauseMs, allocBytes, currentUa);
    }

    @Test
    public void firstSnapshotIsBaselineZeros() {
        Metrics m = new Metrics();
        MetricsSnapshot s = m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        assertEquals(0.0, s.windowMs, 0.0001);
        assertEquals(0.0, s.pktSentPerSec, 0.0001);
    }

    @Test
    public void computesRatesOverWindow() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000)); // prime prev
        m.recordPacketSent(200);
        m.recordPacketSent(200);
        m.recordFix();
        MetricsSnapshot s = m.snapshot(stats(2000, 100, 10, 5, 0, -50000)); // +1000ms
        assertEquals(1000.0, s.windowMs, 0.0001);
        assertEquals(2.0, s.pktSentPerSec, 0.0001);
        assertEquals(400.0, s.bytesSentPerSec, 0.0001);
        assertEquals(1.0, s.fixesPerSec, 0.0001);
    }

    @Test
    public void computesCpuPercentFromJiffies() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        // +50 jiffies over 1s at 100 Hz = 0.5s CPU = 50%
        MetricsSnapshot s = m.snapshot(stats(2000, 150, 10, 5, 0, -50000));
        assertEquals(50.0, s.cpuPct, 0.001);
    }

    @Test
    public void computesGcDeltasAndCurrent() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        MetricsSnapshot s = m.snapshot(stats(2000, 100, 13, 25, 1024 * 1024, -50000));
        assertEquals(3.0, s.gcCountDelta, 0.0001);
        assertEquals(20.0, s.gcPauseMsDelta, 0.0001);
        assertEquals(1024.0, s.allocKbPerSec, 0.001);
        assertEquals(-50000.0, s.currentUa, 0.0001);
    }

    @Test
    public void latencyAndJitterPercentiles() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        for (double v : new double[]{10, 20, 30, 40, 100}) m.recordFixAgeMs(v);
        for (double v : new double[]{1, 2, 3, 4, 50}) m.recordTickJitterMs(v);
        MetricsSnapshot s = m.snapshot(stats(2000, 100, 10, 5, 0, -50000));
        assertEquals(40.0, s.ageMeanMs, 0.0001);   // (10+20+30+40+100)/5
        assertEquals(100.0, s.ageP95Ms, 0.0001);   // ceil(0.95*5)=5 -> index 4
        assertEquals(50.0, s.jitterMaxMs, 0.0001);
        assertEquals(12.0, s.jitterMeanMs, 0.0001);
    }

    @Test
    public void samplesClearAfterSnapshot() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        m.recordFixAgeMs(10);
        m.snapshot(stats(2000, 100, 10, 5, 0, -50000));
        MetricsSnapshot s = m.snapshot(stats(3000, 100, 10, 5, 0, -50000));
        assertTrue(Double.isNaN(s.ageMeanMs)); // no samples in this window
    }

    @Test
    public void maxGapFromRecvTimestamps() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        m.recordPacketRecv(50, 1000);
        m.recordPacketRecv(50, 1200); // gap 200
        m.recordPacketRecv(50, 1900); // gap 700
        MetricsSnapshot s = m.snapshot(stats(2000, 100, 10, 5, 0, -50000));
        assertEquals(700.0, s.maxGapMs, 0.0001);
        assertEquals(3.0, s.pktRecvPerSec, 0.0001);
    }

    @Test
    public void csvHeaderAndRowColumnCountMatch() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000));
        MetricsSnapshot s = m.snapshot(stats(2000, 100, 10, 5, 0, -50000));
        int headerCols = MetricsSnapshot.csvHeader().split(",", -1).length;
        int rowCols = s.toCsvRow(new StringBuilder(), "2026-08-13T00:00:00", 1).toString().split(",", -1).length;
        assertEquals(headerCols, rowCols);
    }

    @Test
    public void sampleListsAreBoundedWithoutSnapshot() {
        Metrics m = new Metrics();
        // no snapshot() call — simulates metrics toggle OFF while record* is still called at ~10 Hz
        for (int i = 0; i < 20000; i++) {
            m.recordFixAgeMs(5.0);
            m.recordTickJitterMs(5.0);
        }
        assertTrue(m.sampleBacklog() <= 2 * 4096);
    }

    @Test
    public void cappedListsStillProduceCorrectMeanAfterSnapshot() {
        Metrics m = new Metrics();
        m.snapshot(stats(1000, 100, 10, 5, 0, -50000)); // prime prev
        m.recordFixAgeMs(10.0);
        m.recordFixAgeMs(20.0);
        m.recordFixAgeMs(30.0);
        MetricsSnapshot s = m.snapshot(stats(2000, 100, 10, 5, 0, -50000));
        assertEquals(20.0, s.ageMeanMs, 0.0001);
    }
}
