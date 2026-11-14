/*
 * Copyright © 2026 LocSync
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. See <https://www.gnu.org/licenses/>.
 */

package goodvin.locsync.server;

/**
 * Chooses between Fused and the plain GPS provider running beside it as a backup. Fused is the first
 * choice; on the 2026-11-14 drive it went quiet for 3–15 s six times and once answered only with ±104 m
 * network positions, while the client waited. GPS fixes fill in whenever Fused has had no good fix for
 * {@link #FRESH_MS}; a coarse Fused fix is passed on only when GPS has nothing either.
 * Times are SystemClock.elapsedRealtime() ms. Main thread only.
 */
final class FixSource {
    static final long FRESH_MS = 3000;
    static final float GOOD_ACCURACY_M = 50;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private long lastGoodFused = NEVER, lastGps = NEVER;

    /** Location updates (re)started: Fused gets {@link #FRESH_MS} to deliver before the backup is called in. */
    void reset(long nowMs) {
        lastGoodFused = nowMs;
        lastGps = NEVER;
    }

    /** True when this fix should go to the client. */
    boolean accept(boolean fused, boolean goodAccuracy, long nowMs) {
        if (!fused) {
            lastGps = nowMs;
            return nowMs - lastGoodFused > FRESH_MS;
        }
        if (goodAccuracy) {
            lastGoodFused = nowMs;
            return true;
        }
        return nowMs - lastGps > FRESH_MS;
    }

    /** True when Fused has gone quiet or coarse: time to start the GPS backup. */
    boolean fusedStalled(long nowMs) {
        return nowMs - lastGoodFused > FRESH_MS;
    }
}
