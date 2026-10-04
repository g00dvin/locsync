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

import java.util.ArrayDeque;

/**
 * Works out how old a fix really is when it reaches us, including Wi-Fi delivery delay.
 *
 * <p>The server reports the fix's age at the moment it <em>sent</em> it, so a packet held back by
 * the Wi-Fi link (power save, retries) still claims to be fresh: on a recorded drive 14% of fixes
 * arrived &gt;200 ms late and 1% &gt;1 s late, often bunched with the next one, which made the
 * extrapolated icon jump back and forth by a second's worth of travel.
 *
 * <p>The phone's fix timestamps and our elapsed clock drift apart only by milliseconds per hour,
 * so {@code arrival - fixTimestamp} is a constant clock offset plus that fix's total latency. Its
 * minimum over a sliding window is the offset plus the best-case latency; the excess over it is
 * the extra delay of this particular fix. The best-case latency itself is taken as the smallest
 * phone-side age seen in the window (transit is near zero on an uncongested hotspot).
 *
 * <p>Pure Java (no Android APIs) so it is unit-testable.
 */
final class FixClock {
    static final long WINDOW_MS = 120_000;
    // A jump this large in the offset means the phone's clock was changed (NTP, manual); start over.
    private static final long CLOCK_JUMP_MS = 10_000;

    private final ArrayDeque<long[]> window = new ArrayDeque<>(); // {arrivalElapsedMs, offsetMs, ageMs}
    private long lastExtraDelayMs = 0;

    /**
     * @param arrivalElapsedMs our elapsedRealtime when the packet was received
     * @param fixTimestampMs   the fix's timestamp on the phone's wall clock
     * @param ageMs            the fix's age as reported by the phone when it sent the packet
     * @return the fix's total latency on arrival (ms, &ge; 0): phone-side age plus Wi-Fi delay
     */
    long latencyMs(long arrivalElapsedMs, long fixTimestampMs, long ageMs) {
        long offset = arrivalElapsedMs - fixTimestampMs;
        if (!window.isEmpty() && Math.abs(offset - minOffset()) > CLOCK_JUMP_MS) {
            window.clear();
        }
        while (!window.isEmpty() && window.peekFirst()[0] < arrivalElapsedMs - WINDOW_MS) {
            window.pollFirst();
        }
        window.addLast(new long[]{arrivalElapsedMs, offset, Math.max(0, ageMs)});

        long minOffset = minOffset();
        long minAge = Long.MAX_VALUE;
        for (long[] s : window) {
            minAge = Math.min(minAge, s[2]);
        }
        lastExtraDelayMs = offset - minOffset;
        // Never report less than the phone's own figure for this fix.
        return Math.max(Math.max(0, ageMs), lastExtraDelayMs + minAge);
    }

    /** Delivery delay of the last fix beyond the best case in the window (ms). */
    long lastExtraDelayMs() {
        return lastExtraDelayMs;
    }

    void reset() {
        window.clear();
        lastExtraDelayMs = 0;
    }

    private long minOffset() {
        long min = Long.MAX_VALUE;
        for (long[] s : window) {
            min = Math.min(min, s[1]);
        }
        return min;
    }
}
