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

import org.junit.Test;

public class FixClockTest {
    // Phone clock is 5 s ahead of nothing in particular: only differences matter.
    private static final long SKEW = 1_700_000_000_000L - 100_000L;

    @Test
    public void onTimeFixesReportPhoneAge() {
        FixClock c = new FixClock();
        for (int i = 0; i < 10; i++) {
            long fixTs = SKEW + 100_000 + i * 1000L;
            long arrival = 100_000 + i * 1000L + 40; // 40 ms after the fix
            assertEquals(40, c.latencyMs(arrival, fixTs, 40));
        }
        assertEquals(0, c.lastExtraDelayMs());
    }

    // The bug seen on a real drive: a packet held ~1 s by Wi-Fi still says "age 40 ms".
    @Test
    public void wifiDelayIsAddedToReportedAge() {
        FixClock c = new FixClock();
        for (int i = 0; i < 10; i++) {
            c.latencyMs(100_000 + i * 1000L + 40, SKEW + 100_000 + i * 1000L, 40);
        }
        long fixTs = SKEW + 110_000;
        long lateArrival = 110_000 + 40 + 950;       // delayed in transit
        assertEquals(990, c.latencyMs(lateArrival, fixTs, 40));
        assertEquals(950, c.lastExtraDelayMs());
        // the next one arrives on time right behind it
        assertEquals(40, c.latencyMs(111_000 + 40, SKEW + 111_000, 40));
    }

    @Test
    public void phoneClockJumpRestartsTheWindow() {
        FixClock c = new FixClock();
        for (int i = 0; i < 5; i++) {
            c.latencyMs(100_000 + i * 1000L + 40, SKEW + 100_000 + i * 1000L, 40);
        }
        // phone clock set back by a minute: offset jumps; must not read as 60 s of latency
        assertEquals(40, c.latencyMs(105_040, SKEW + 105_000 - 60_000, 40));
    }
}
