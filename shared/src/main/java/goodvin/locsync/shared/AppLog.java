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

    // Set by install(); guarded by RING.
    private static java.io.File dir;
    private static String app = "app", version = "?";
    private static boolean crashHandlerInstalled;
    // applog-<app>.csv: every captured line, flushed at once, while debug logging is on. The ring is lost with the
    // process, and logcat can only be exported for the current one, so nothing survived a crash or a restart.
    private static CsvLog fileLog;
    private static final StringBuilder fileLine = new StringBuilder(200);
    private static final long FILE_MAX_BYTES = 2_000_000;   // rotated to .old above this
    private static final long CRASH_MAX_BYTES = 1_000_000;
    private static final int CRASH_LOG_TAIL = 150;

    /**
     * Call first thing in every entry point (services, activities). Tells the log where its files go (the logs
     * directory the diagnostics export zips) and, once per process, installs a handler that appends an uncaught
     * exception's stack trace and the last log lines to crash-<app>.txt before the app dies as usual.
     */
    public static void install(android.content.Context context, String appName) {
        synchronized (RING) {
            dir = new java.io.File(context.getCacheDir(), "logs");
            app = appName;
            version = VersionGetter.getAppVersionName(context);
            if (crashHandlerInstalled) {
                return;
            }
            crashHandlerInstalled = true;
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                writeCrash(thread, error);
            } catch (Throwable ignored) {
                // nothing may get in the way of the crash itself
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static void writeCrash(Thread thread, Throwable error) throws java.io.IOException {
        java.io.File d;
        String a, v;
        List<Entry> tail;
        synchronized (RING) {
            d = dir;
            a = app;
            v = version;
            tail = new ArrayList<>(RING);
            if (fileLog != null) {
                fileLog.flush();
            }
        }
        if (d == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        java.io.File f = new java.io.File(d, "crash-" + a + ".txt");
        if (f.length() > CRASH_MAX_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US);
        try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true))) {
            w.println("=== crash " + fmt.format(new java.util.Date()) + " " + a + " " + v + ", thread " + thread.getName()
                    + ", Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT
                    + "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            error.printStackTrace(w);
            w.println("--- last log lines (oldest first)");
            for (int i = Math.max(0, tail.size() - CRASH_LOG_TAIL); i < tail.size(); i++) {
                Entry e = tail.get(i);
                w.println(fmt.format(new java.util.Date(e.timeMillis)) + " " + e.level + " " + e.tag + ": " + e.message);
            }
            w.println();
        }
    }

    public static void setDebug(boolean enabled) {
        debug = enabled;
        if (!enabled) {
            synchronized (RING) {
                if (fileLog != null) {
                    fileLog.close();
                    fileLog = null;
                }
            }
        }
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
            if (debug && dir != null) {
                if (fileLog == null) {
                    fileLog = new CsvLog(new java.io.File(dir, "applog-" + app + ".csv"), "wall_ms,level,tag,message",
                            FILE_MAX_BYTES);
                }
                fileLine.setLength(0);
                fileLine.append(e.timeMillis).append(',').append(level).append(',').append(tag).append(',')
                        .append(msg == null ? "" : msg.replace('\n', ' ').replace('\r', ' '));   // last column: commas stay
                fileLog.writeLine(fileLine);
                fileLog.flush();   // a few lines a minute; survives a kill
            }
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
