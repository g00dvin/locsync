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

            appendInAppLog(writer);
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

    /**
     * Appends the app's own log ring. Some head-unit ROMs drop app log lines below error level from
     * logcat entirely, so without this an export contained nothing from the app itself.
     */
    private static void appendInAppLog(BufferedWriter writer) throws IOException {
        java.util.List<AppLog.Entry> entries = AppLog.snapshot();
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        writer.append("\n--------- in-app log (oldest first, ").append(String.valueOf(entries.size()))
                .append(" entries) ---------\n");
        for (int i = entries.size() - 1; i >= 0; i--) {
            AppLog.Entry e = entries.get(i);
            writer.append(fmt.format(new Date(e.timeMillis))).append(' ').append(e.level).append(' ')
                    .append(e.tag).append(": ").append(e.message).append('\n');
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
            appendInAppLog(writer);
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

    /**
     * Everything needed to analyse a drive, in one ZIP for sharing: a fresh log export (logcat +
     * in-app log), every CSV in the logs directory (metrics, recorded track and its rotated part),
     * and info.txt with app/device details and the given settings. The previous archive is deleted.
     *
     * @return the archive, or null if it could not be written
     */
    public static File exportAll(Context context, String appName, String appVersion,
                                 java.util.Map<String, ?> settings) {
        File dir = getLogDir(context);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File log = exportLogs(context, appName);
        cleanupOldLogs(context, appName);
        File[] old = dir.listFiles((d, name) -> name.endsWith(".zip"));
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        String ts = DATE_FORMAT.format(new Date());
        File zip = new File(dir, appName + "-data--" + ts + ".zip");
        try (java.util.zip.ZipOutputStream out =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("info.txt"));
            out.write(info(appName, appVersion, settings).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            if (log != null) {
                addFile(out, log);
            }
            File[] data = dir.listFiles((d, name) -> name.endsWith(".csv") || name.endsWith(".csv.old"));
            if (data != null) {
                Arrays.sort(data);
                for (File f : data) {
                    addFile(out, f);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing data archive", e);
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            return null;
        }
        AppLog.i(TAG, "Data exported to " + zip.getName() + " (" + zip.length() + " bytes)");
        return zip;
    }

    private static void addFile(java.util.zip.ZipOutputStream out, File f) throws IOException {
        out.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        out.closeEntry();
    }

    private static String info(String appName, String appVersion, java.util.Map<String, ?> settings) {
        StringBuilder sb = new StringBuilder();
        sb.append("app: ").append(appName).append(' ').append(appVersion).append('\n');
        sb.append("exported: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())).append('\n');
        sb.append("device: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(", Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("\nsettings:\n");
        if (settings != null) {
            java.util.List<String> keys = new ArrayList<>(settings.keySet());
            java.util.Collections.sort(keys);
            for (String k : keys) {
                sb.append("  ").append(k).append(" = ").append(settings.get(k)).append('\n');
            }
        }
        return sb.toString();
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
