/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Log gate: debug/info/verbose are emitted to logcat only when debug logging is enabled (default
 * off, so normal runs produce warnings + errors only). Warnings and errors always go through
 * android.util.Log directly at their call sites.
 *
 * <p>In addition, every call is captured into a small in-memory ring buffer regardless of the gate,
 * so the Monitor screen's log console can render recent activity in-process. The ring is bounded and
 * thread-safe.
 */
public final class AppLog {
    private static volatile boolean debug = false;

    private static final int RING_CAPACITY = 1000;   // also exported with the logs
    private static final Deque<Entry> RING = new ArrayDeque<>(RING_CAPACITY);

    /** One captured log line. {@code level} is 'I' | 'W' | 'D' | 'V'. */
    public static final class Entry {
        public final long timeMillis;
        public final char level;
        public final String tag;
        public final String message;

        Entry(long timeMillis, char level, String tag, String message) {
            this.timeMillis = timeMillis;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }
    }

    private AppLog() {}

    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    public static boolean isDebug() {
        return debug;
    }

    private static void capture(char level, String tag, String msg) {
        Entry e = new Entry(System.currentTimeMillis(), level, tag, msg);
        synchronized (RING) {
            if (RING.size() >= RING_CAPACITY) {
                RING.removeFirst();
            }
            RING.addLast(e);
        }
    }

    /** Recent log entries, newest first. */
    public static List<Entry> snapshot() {
        List<Entry> out;
        synchronized (RING) {
            out = new ArrayList<>(RING);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public static void clearRing() {
        synchronized (RING) {
            RING.clear();
        }
    }

    public static void d(String tag, String msg) {
        capture('D', tag, msg);
        if (debug) android.util.Log.d(tag, msg);
    }

    public static void i(String tag, String msg) {
        capture('I', tag, msg);
        if (debug) android.util.Log.i(tag, msg);
    }

    public static void v(String tag, String msg) {
        capture('V', tag, msg);
        if (debug) android.util.Log.v(tag, msg);
    }

    /** Optional explicit warning capture for the console (still logged at the call site as needed). */
    public static void w(String tag, String msg) {
        capture('W', tag, msg);
    }
}
