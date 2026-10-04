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
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Date;
import java.util.Locale;

public class LogExporter {
    private static final String TAG = "LogExporter";
    private static final String LOG_FILE_MIDDLE = "-logs--";
    private static final String LOG_FILE_EXT = ".txt";
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd--HH-mm-ss", Locale.US);

    /**
     * Export logs to a file in the app's cache directory
     *
     * @param context Application context
     * @return File object pointing to the exported logs, or null if failed
     */
    public static File exportLogs(Context context, String appName) {
        File logFile = createLogFile(context, appName);
        if (logFile == null) {
            return null;
        }

        try {
            Process process = startLogcat(context, true);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            );
            FileOutputStream output = new FileOutputStream(logFile);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));

            String line;
            while ((line = reader.readLine()) != null) {
                writer.append(line).append("\n");
            }

            writer.close();
            output.close();
            reader.close();

            if (process.waitFor() != 0) {
                Log.e(TAG, "Failed to export logs, logcat command exited with result = " + process.exitValue());
                if (clearedAtMs(context) > 0) {
                    // This logcat may not understand -T; export everything rather than nothing.
                    return exportUnfiltered(context, logFile);
                }
                return null;
            }

            AppLog.d(TAG, "Logs exported to: " + logFile.getAbsolutePath());
            return logFile;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "Error exporting logs", e);
            return null;
        }
    }

    private static File exportUnfiltered(Context context, File logFile) throws IOException, InterruptedException {
        Process process = startLogcat(context, false);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.append(line).append("\n");
            }
        }
        return process.waitFor() == 0 ? logFile : null;
    }

    /** This process's logcat; after {@link #clearAll} only lines newer than the clear (logcat -T). */
    private static Process startLogcat(Context context, boolean sinceClear) throws IOException {
        List<String> cmd = new ArrayList<>(Arrays.asList("logcat", "-d"));
        long since = sinceClear ? clearedAtMs(context) : 0;
        if (since > 0) {
            cmd.add("-T");
            cmd.add(String.format(Locale.US, "%d.%03d", since / 1000, since % 1000)); // epoch sssss.mmm
        }
        cmd.add("*:V");
        cmd.add("--pid=" + android.os.Process.myPid());
        return Runtime.getRuntime().exec(cmd.toArray(new String[0]));
    }

    private static final String PREFS = "locsync_log_exporter";
    private static final String PREF_CLEARED_AT = "clearedAtMs";

    private static long clearedAtMs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(PREF_CLEARED_AT, 0);
    }

    /**
     * Clears this app's diagnostics without touching its settings: deletes every file in the logs
     * directory (exported logs, metrics and track CSVs), empties the in-app log console and makes
     * later log exports start from now (logcat itself is system-wide and can't be cleared by an app).
     *
     * @return bytes freed
     */
    public static long clearAll(Context context) {
        long freed = 0;
        File[] files = getLogDir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                long len = f.length();
                if (f.isFile() && f.delete()) {
                    freed += len;
                }
            }
        }
        AppLog.clearRing();
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(PREF_CLEARED_AT, System.currentTimeMillis()).apply();
        AppLog.i(TAG, "Logs cleared (" + freed + " bytes freed)");
        return freed;
    }

    /**
     * Create a log file with timestamp in the app's cache directory
     */
    private static File createLogFile(Context context, String appName) {
        try {
            String timeStamp = DATE_FORMAT.format(new Date());
            String fileName = appName + LOG_FILE_MIDDLE + timeStamp + LOG_FILE_EXT;
            File logDir = getLogDir(context);

            if (logDir.mkdirs()) {
                AppLog.d(TAG, "Created new log directory: " + logDir.getAbsolutePath());
            }

            File logFile = new File(logDir, fileName);
            if (logFile.createNewFile()) {
                AppLog.d(TAG, "Created log file: " + logFile.getAbsolutePath());
            }
            return logFile;
        } catch (IOException e) {
            Log.e(TAG, "Error creating log file", e);
            return null;
        }
    }

    /**
     * Clean up old log files (keep last 5)
     */
    public static void cleanupOldLogs(Context context, String appName) {
        try {
            File logDir = getLogDir(context);
            if (!logDir.exists() || !logDir.isDirectory()) {
                return;
            }

            File[] logFiles = logDir.listFiles((dir, name) ->
                    name.startsWith(appName + LOG_FILE_MIDDLE) && name.endsWith(LOG_FILE_EXT)
            );

            if (logFiles == null || logFiles.length <= 5) {
                return;
            }

            // Sort by last modified (newest first)
            Arrays.sort(logFiles, (f1, f2) ->
                    Long.compare(f2.lastModified(), f1.lastModified())
            );

            // Keep only the 5 most recent log files
            for (int i = 5; i < logFiles.length; i++) {
                File logFile = logFiles[i];
                String path = logFile.getAbsolutePath();
                if (logFile.delete()) {
                    AppLog.d(TAG, "Deleted old log file: " + path);
                } else {
                    Log.w(TAG, "Failed to delete old log file: " + path);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old logs", e);
        }
    }

    private static File getLogDir(Context context) {
        return new File(context.getCacheDir(), "logs");
    }
}
