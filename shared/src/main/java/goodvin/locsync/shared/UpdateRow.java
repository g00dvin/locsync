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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import goodvin.locsync.logexporter.R;

/**
 * Settings → About → Updates, for both apps: checks GitHub once a day (and on demand), shows when a
 * newer release exists, and downloads and opens its APK in the system installer.
 */
public final class UpdateRow {
    private static final String TAG = "UpdateRow";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Activity activity;
    private final View row;
    private final String apkPrefix;
    private final String installed;
    private final Runnable onAvailableChanged;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UpdateChecker.Release available;
    private String status;          // subtitle while checking/downloading; null = idle text
    private boolean busy;

    /**
     * @param apkPrefix asset name prefix of this app's APK, e.g. "locsync-client-"
     * @param onAvailableChanged called when an update appears (e.g. to show a banner); may be null
     */
    public UpdateRow(Activity activity, View row, String apkPrefix, String installedVersion,
                     Runnable onAvailableChanged) {
        this.activity = activity;
        this.row = row;
        this.apkPrefix = apkPrefix;
        this.installed = installedVersion;
        this.onAvailableChanged = onAvailableChanged;
        UpdateChecker.Release cached = UpdateChecker.cached(activity);
        if (cached != null && UpdateChecker.isNewer(cached.tag(), installed)) available = cached;
        render();
    }

    /** The newer release, or null. */
    public UpdateChecker.Release available() {
        return available;
    }

    /** Once a day, for release builds (branch/debug builds have no comparable version). */
    public void autoCheck() {
        if (UpdateChecker.parseVersion(installed) == null) return;
        if (UpdateChecker.autoCheckDue(activity, AUTO_CHECK_INTERVAL_MS)) check(false);
    }

    private void check(boolean manual) {
        if (busy) return;
        busy = true;
        status = activity.getString(R.string.update_checking);
        render();
        EXECUTOR.execute(() -> {
            UpdateChecker.Release latest = null;
            Exception error = null;
            try {
                latest = UpdateChecker.fetchLatest(activity, apkPrefix);
            } catch (Exception e) {
                error = e;
            }
            UpdateChecker.Release result = latest;
            Exception err = error;
            main.post(() -> {
                busy = false;
                status = null;
                if (err != null) {
                    AppLog.w(TAG, "Update check failed: " + err.getMessage());
                    if (manual && alive()) {
                        Toast.makeText(activity, activity.getString(R.string.update_error, err.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    boolean had = available != null;
                    available = result != null && UpdateChecker.isNewer(result.tag(), installed) ? result : null;
                    if (available != null) AppLog.i(TAG, "Update available: " + available.tag());
                    if (manual && available == null && alive()) {
                        Toast.makeText(activity, R.string.update_latest_toast, Toast.LENGTH_SHORT).show();
                    }
                    if (had != (available != null) && onAvailableChanged != null && alive()) {
                        onAvailableChanged.run();
                    }
                }
                render();
            });
        });
    }

    /** Downloads the newer APK and opens the system installer (asks to allow installs first). */
    public void install() {
        UpdateChecker.Release release = available;
        if (release == null || busy) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.update_allow_install, Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "No unknown-sources settings screen", e);
            }
            return;
        }
        busy = true;
        status = activity.getString(R.string.update_downloading, 0);
        render();
        EXECUTOR.execute(() -> {
            File apk = null;
            Exception error = null;
            try {
                apk = UpdateChecker.download(activity, release, pct -> main.post(() -> {
                    status = activity.getString(R.string.update_downloading, pct);
                    render();
                }));
            } catch (Exception e) {
                error = e;
            }
            File file = apk;
            Exception err = error;
            main.post(() -> {
                busy = false;
                status = null;
                render();
                if (!alive()) return;
                if (err != null) {
                    AppLog.w(TAG, "Update download failed: " + err.getMessage());
                    Toast.makeText(activity, activity.getString(R.string.update_error, err.getMessage()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                openInstaller(file);
            });
        });
    }

    private void openInstaller(File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.update_error, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean alive() {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private void render() {
        if (!alive()) return;
        String sub;
        String button;
        Runnable click;
        if (status != null) {
            sub = status;
            button = null;
            click = null;
        } else if (available != null) {
            sub = activity.getString(R.string.update_available, available.tag());
            button = activity.getString(R.string.update_install);
            click = this::install;
        } else {
            sub = activity.getString(R.string.update_sub_idle, installed);
            button = activity.getString(R.string.update_check);
            click = () -> check(true);
        }
        SettingsRows.bindActionButton(row, activity.getString(R.string.update_title), sub,
                button != null ? button : "", click != null ? click : () -> { });
        row.findViewById(R.id.row_button).setVisibility(button != null ? View.VISIBLE : View.GONE);
    }
}
