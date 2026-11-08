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

/** Immutable sample of raw system counters. Sentinels: -1 (or Integer.MIN_VALUE for currentUa) = unknown. */
public final class SystemStats {
    public final long elapsedRealtimeMs;
    public final long cpuJiffies;   // process utime+stime, -1 unknown
    public final long gcCount;      // art.gc.gc-count, -1 unknown
    public final long gcPauseMs;    // art.gc.gc-time, -1 unknown
    public final long allocBytes;   // art.gc.bytes-allocated, -1 unknown
    public final int currentUa;     // BATTERY_PROPERTY_CURRENT_NOW, Integer.MIN_VALUE unknown

    public SystemStats(long elapsedRealtimeMs, long cpuJiffies, long gcCount, long gcPauseMs,
                       long allocBytes, int currentUa) {
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.cpuJiffies = cpuJiffies;
        this.gcCount = gcCount;
        this.gcPauseMs = gcPauseMs;
        this.allocBytes = allocBytes;
        this.currentUa = currentUa;
    }
}
