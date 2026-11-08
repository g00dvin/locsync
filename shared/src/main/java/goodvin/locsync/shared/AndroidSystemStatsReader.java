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

import android.content.Context;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;

/** Reads a snapshot of system-level performance counters. */
public final class AndroidSystemStatsReader {
    private final BatteryManager batteryManager;

    public AndroidSystemStatsReader(Context ctx) {
        this.batteryManager = (BatteryManager) ctx.getApplicationContext().getSystemService(Context.BATTERY_SERVICE);
    }

    public SystemStats read() {
        return new SystemStats(
                SystemClock.elapsedRealtime(),
                readCpuJiffies(),
                runtimeStat("art.gc.gc-count"),
                runtimeStat("art.gc.gc-time"),
                runtimeStat("art.gc.bytes-allocated"),
                currentUa());
    }

    private static long readCpuJiffies() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"))) {
            return parseProcSelfStatCpuJiffies(r.readLine());
        } catch (Exception e) {
            return -1;
        }
    }

    /** utime (field 14) + stime (field 15), 1-based, after the parenthesised comm. -1 if unparseable. */
    public static long parseProcSelfStatCpuJiffies(String statLine) {
        if (statLine == null) return -1;
        int close = statLine.lastIndexOf(')');
        if (close < 0 || close + 2 > statLine.length()) return -1;
        String[] f = statLine.substring(close + 2).trim().split("\\s+");
        // After comm, index 0 = field 3 (state). utime = field 14 -> index 11; stime = 15 -> index 12.
        if (f.length < 13) return -1;
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long runtimeStat(String key) {
        try {
            String v = Debug.getRuntimeStat(key);
            return v == null ? -1 : Long.parseLong(v);
        } catch (Exception e) {
            return -1;
        }
    }

    private int currentUa() {
        try {
            if (batteryManager == null) return Integer.MIN_VALUE;
            int v = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            return v == Integer.MIN_VALUE ? Integer.MIN_VALUE : v;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }
}
