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
import android.content.Context;
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

    // Process-wide: a check or download outlives the activity when Settings recreate it.
    private static boolean busy;
    private static String status;   // subtitle while checking/downloading; null = idle text
    // The row on screen, to repaint when background work ends (weak: don't keep a closed screen).
    private static java.lang.ref.WeakReference<UpdateRow> current = new java.lang.ref.WeakReference<>(null);

    private final Activity activity;
    private final View row;
    private final String apkPrefix;
    private final String installed;
    private final boolean releaseBuild;
    private final Runnable onAvailableChanged;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private UpdateChecker.Release available;

    /**
     * @param apkPrefix asset name prefix of this app's APK, e.g. "locsync-client-"
     * @param releaseBuild false for branch/CI builds: their signature differs from releases, so
     *                     they can't be updated in place and offer nothing
     * @param onAvailableChanged called when an update appears (e.g. to show a banner); may be null
     */
    public UpdateRow(Activity activity, View row, String apkPrefix, String installedVersion,
                     boolean releaseBuild, Runnable onAvailableChanged) {
        this.activity = activity;
        this.row = row;
        this.apkPrefix = apkPrefix;
        this.installed = installedVersion;
        this.releaseBuild = releaseBuild && UpdateChecker.parseVersion(installedVersion) != null;
        this.onAvailableChanged = onAvailableChanged;
        this.app = activity.getApplicationContext();
        current = new java.lang.ref.WeakReference<>(this);
        UpdateChecker.Release cached = this.releaseBuild ? UpdateChecker.cached(app) : null;
        if (cached != null && UpdateChecker.isNewer(cached.tag(), installed)) {
            available = cached;
        } else if (!busy) {
            EXECUTOR.execute(() -> UpdateChecker.clearDownloads(app));   // the update is installed
        }
        render();
    }

    /** The newer release, or null. */
    public UpdateChecker.Release available() {
        return available;
    }

    /** Once a day, for release builds. */
    public void autoCheck() {
        if (releaseBuild && UpdateChecker.autoCheckDue(app, AUTO_CHECK_INTERVAL_MS)) check(false);
    }

    private void check(boolean manual) {
        if (busy) return;
        busy = true;
        status = app.getString(R.string.update_checking);
        render();
        EXECUTOR.execute(() -> {
            UpdateChecker.Release latest = null;
            Exception error = null;
            try {
                latest = UpdateChecker.fetchLatest(app, apkPrefix);
            } catch (Exception e) {
                error = e;
            }
            UpdateChecker.Release result = latest;
            Exception err = error;
            main.post(() -> {
                busy = false;
                status = null;
                if (err != null) {
                    AppLog.w(TAG, "Update check failed: " + err);
                    if (manual) toast(app.getString(R.string.update_error, describe(err)));
                } else {
                    UpdateChecker.Release newer =
                            result != null && UpdateChecker.isNewer(result.tag(), installed) ? result : null;
                    if (newer != null) AppLog.i(TAG, "Update available: " + newer.tag());
                    if (manual && newer == null) toast(app.getString(R.string.update_latest_toast));
                    UpdateRow row = current.get();   // the screen may have been recreated meanwhile
                    if (row != null && row.alive()) {
                        boolean had = row.available != null;
                        row.available = newer;
                        if (had != (newer != null) && row.onAvailableChanged != null) row.onAvailableChanged.run();
                    }
                }
                repaint();
            });
        });
    }

    /** Downloads the newer APK and opens the system installer (asks to allow installs first). */
    public void install() {
        UpdateChecker.Release release = available;
        if (release == null) return;
        if (busy) {
            toast(app.getString(R.string.update_busy));
            return;
        }
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
        status = app.getString(R.string.update_downloading, 0);
        render();
        Context app = this.app;
        EXECUTOR.execute(() -> {
            File apk = null;
            Exception error = null;
            try {
                apk = UpdateChecker.download(app, release, pct -> main.post(() -> {
                    status = app.getString(R.string.update_downloading, pct);
                    repaint();
                }));
            } catch (Exception e) {
                error = e;
            }
            File file = apk;
            Exception err = error;
            main.post(() -> {
                busy = false;
                status = null;
                repaint();
                if (err != null) {
                    AppLog.w(TAG, "Update download failed: " + err);
                    toast(app.getString(R.string.update_error, describe(err)));
                    return;
                }
                openInstaller(app, file);   // app context: still works if the screen was recreated
            });
        });
    }

    private static void openInstaller(Context app, File apk) {
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(app, app.getString(R.string.update_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** No network vs. anything else: the raw exception text means nothing to a driver. */
    private String describe(Exception e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException) {
            return app.getString(R.string.update_no_network);
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void toast(String text) {
        Toast.makeText(app, text, Toast.LENGTH_LONG).show();
    }

    /** Repaints the row on screen now (this one, or the one of a recreated activity). */
    private static void repaint() {
        UpdateRow row = current.get();
        if (row != null) row.render();
    }

    private boolean alive() {
        return current.get() == this && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void render() {
        if (!alive()) return;
        String sub;
        String button;
        Runnable click;
        if (!releaseBuild) {
            sub = activity.getString(R.string.update_test_build);
            button = null;
            click = null;
        } else if (status != null) {
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
