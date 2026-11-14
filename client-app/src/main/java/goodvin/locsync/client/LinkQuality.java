/*
 * Copyright © 2026 LocSync
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.client;

/**
 * How evenly the phone's fixes have been arriving: the longest wait between two of them over the last
 * minute. On the 2026-11-14 drive the phone went quiet for 3–15 s seven times; the icon kept moving on
 * dead reckoning and nothing on screen said why it drifted. Times are SystemClock.elapsedRealtime() ms.
 */
final class LinkQuality {
    static final long WINDOW_MS = 60_000;
    private static final int SIZE = 256;   // a minute of fixes at up to 4 Hz
    private final long[] times = new long[SIZE];
    private int count, next;

    synchronized void onFix(long tMs) {
        times[next] = tMs;
        next = (next + 1) % SIZE;
        count = Math.min(count + 1, SIZE);
    }

    synchronized void reset() {
        count = 0;
    }

    /** The longest wait in the window ending at {@code nowMs}, the one still running included; 0 with no fix in it. */
    synchronized long maxGapMs(long nowMs) {
        long max = 0, prev = -1;
        for (int k = count; k >= 1; k--) {   // oldest first
            long t = times[(next - k + SIZE) % SIZE];
            if (t < nowMs - WINDOW_MS) {
                continue;
            }
            if (prev >= 0) {
                max = Math.max(max, t - prev);
            }
            prev = t;
        }
        return prev < 0 ? 0 : Math.max(max, nowMs - prev);
    }
}
