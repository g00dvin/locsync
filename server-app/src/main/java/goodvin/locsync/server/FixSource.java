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
 * Decides which of the phone's fixes go to the car. Fused is the first choice, with the plain GPS provider
 * running beside it as a backup: its fixes fill in whenever Fused has had no good fix for {@link #FRESH_MS}.
 *
 * <p>A fix worse than {@link #GOOD_ACCURACY_M} is never sent, whatever its source. Those are network
 * positions and cold-start guesses: leaving an underground car park on 2026-11-15 they hopped between
 * points 300 m apart with claimed accuracies up to 1.8 km, and each one reset the car's filter. Holding the
 * last good position (and letting the car dead-reckon from it) is better than any of them.
 * Times are SystemClock.elapsedRealtime() ms. Main thread only.
 */
final class FixSource {
    static final long FRESH_MS = 3000;
    static final float GOOD_ACCURACY_M = 50;
    private static final long NEVER = Long.MIN_VALUE / 2;
    private long lastGoodFused = NEVER;

    /** Location updates (re)started: Fused gets {@link #FRESH_MS} to deliver before the backup is called in. */
    void reset(long nowMs) {
        lastGoodFused = nowMs;
    }

    /** True when this fix should go to the client. */
    boolean accept(boolean fused, boolean goodAccuracy, long nowMs) {
        if (!goodAccuracy) {
            return false;
        }
        if (fused) {
            lastGoodFused = nowMs;
            return true;
        }
        return nowMs - lastGoodFused > FRESH_MS;
    }

    /** True when Fused has gone quiet or coarse: time to start the GPS backup. */
    boolean fusedStalled(long nowMs) {
        return nowMs - lastGoodFused > FRESH_MS;
    }
}
