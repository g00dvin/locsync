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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import goodvin.locsync.logexporter.R;

/** Screen helpers both apps' MainActivity share: log console, data export, "Clear logs" row, formatting. */
public final class Ui {
    private static final String TAG = "Ui";

    private Ui() {}

    /** The in-app log console: the newest 120 entries, time dimmed, level coloured. */
    public static void renderLog(TextView view) {
        Context c = view.getContext();
        // Per call: a formatter keeps the time zone it was built with.
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        List<AppLog.Entry> entries = AppLog.snapshot();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int max = Math.min(entries.size(), 120);
        for (int i = 0; i < max; i++) {
            AppLog.Entry e = entries.get(i);
            int start = sb.length();
            sb.append(time.format(e.timeMillis)).append(' ');
            span(sb, start, sb.length(), c.getColor(R.color.ls_text_dim));
            int ls = sb.length();
            sb.append(e.level).append(' ');
            span(sb, ls, sb.length(), levelColor(c, e.level));
            sb.append(e.message).append('\n');
        }
        view.setText(sb);
    }

    private static int levelColor(Context c, char level) {
        return switch (level) {
            case 'I' -> c.getColor(R.color.ls_accent_400);
            case 'W' -> c.getColor(R.color.ls_error);
            default -> c.getColor(R.color.ls_log_debug);
        };
    }

    private static void span(SpannableStringBuilder sb, int start, int end, int color) {
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Settings → Diagnostics / Monitor: one archive with logs, metrics, track and settings, then the share sheet. */
    public static void exportAll(Activity a, String appName, String appVersion, Supplier<Map<String, ?>> settings) {
        Toast.makeText(a, R.string.export_all_in_progress, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File zip = null;
            try {
                zip = LogExporter.exportAll(a, appName, appVersion, settings.get());
            } catch (Exception e) {
                Log.e(TAG, "Error exporting data", e);
            }
            final File result = zip;
            a.runOnUiThread(() -> {
                if (result != null) {
                    shareFile(a, result, "application/zip", a.getString(R.string.export_all));
                } else {
                    Toast.makeText(a, R.string.export_all_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static void shareFile(Activity a, File file, String mime, String chooserTitle) {
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(share, chooserTitle));
        } catch (Exception e) {
            Log.w(TAG, "Failed to share file", e);
        }
    }

    /**
     * First tap on "Clear logs" arms an inline confirmation (a "Clear" button in the row) instead of
     * an AlertDialog: some head-unit ROMs restyle system dialogs so their buttons are invisible.
     * {@code disarm} is the activity's own Runnable (so it can be cancelled) and runs by itself
     * after a few seconds; {@code afterClear} refreshes whatever shows the logs.
     */
    public static void armClearLogs(View row, Handler handler, Runnable disarm, Runnable afterClear) {
        Context c = row.getContext();
        setText(row, R.id.row_sub, c.getString(R.string.clear_logs_confirm));
        TextView button = row.findViewById(R.id.row_button);
        button.setText(R.string.clear_logs_action);
        button.setVisibility(View.VISIBLE);
        row.findViewById(R.id.row_chevron).setVisibility(View.GONE);
        button.setOnClickListener(v -> {
            disarm.run();
            long freed = LogExporter.clearAll(c);
            afterClear.run();
            Toast.makeText(c, c.getString(R.string.clear_logs_done, Formatter.formatShortFileSize(c, freed)),
                    Toast.LENGTH_SHORT).show();
        });
        handler.removeCallbacks(disarm);
        handler.postDelayed(disarm, 5000);
    }

    /** Puts the "Clear logs" row back to its idle look. */
    public static void disarmClearLogs(View row, Handler handler, Runnable disarm) {
        handler.removeCallbacks(disarm);
        setText(row, R.id.row_sub, row.getContext().getString(R.string.clear_logs_sub));
        row.findViewById(R.id.row_button).setVisibility(View.GONE);
        row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
    }

    /**
     * Home-screen power button: a filled "Start" while stopped, an outlined "Stop" while running.
     * {@code label} carries the text and icon; it may be the button itself.
     */
    public static void stylePower(View button, TextView label, boolean running) {
        button.setBackgroundResource(running ? R.drawable.bg_btn_outline : R.drawable.bg_btn_primary);
        label.setText(running ? R.string.power_stop : R.string.power_start);
        int color = button.getContext().getColor(running ? R.color.ls_text : R.color.ls_bg);
        label.setTextColor(color);
        label.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    /** Human name of a runtime permission for the "missing permissions" line. */
    public static String permissionName(Context c, String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION -> c.getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION -> c.getString(R.string.permission_coarse_location);
            case Manifest.permission.ACCESS_BACKGROUND_LOCATION -> c.getString(R.string.permission_background_location);
            default -> permission.substring(permission.lastIndexOf('.') + 1);
        };
    }

    /** Connected time since {@code sinceElapsed} (elapsedRealtime; 0 = not connected). */
    public static String uptime(Context c, long sinceElapsed) {
        long s = sinceElapsed == 0 ? 0 : (SystemClock.elapsedRealtime() - sinceElapsed) / 1000;
        return c.getString(R.string.uptime_format, s / 60, s % 60);
    }

    public static String fmt0(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.0f", v);
    }

    public static String fmt1(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.1f", v);
    }

    private static void setText(View root, int childId, CharSequence text) {
        TextView tv = root.findViewById(childId);
        if (tv != null) tv.setText(text);
    }
}
