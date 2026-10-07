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
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Looks up the latest GitHub release and downloads this app's APK from it. Both apps are installed
 * outside a store, so nothing else tells the user about a new version. Network calls block: run
 * them off the main thread.
 */
public final class UpdateChecker {
    static final String LATEST_RELEASE_URL = "https://github.com/g00dvin/locsync/releases/latest";
    static final String RELEASE_DOWNLOAD_URL = "https://github.com/g00dvin/locsync/releases/download/";
    private static final String PREFS = "locsync_updates";
    private static final String KEY_CHECKED_AT = "checkedAt";
    private static final String KEY_TAG = "tag";
    private static final String KEY_APK_URL = "apkUrl";
    private static final int TIMEOUT_MS = 15_000;
    private static final long MAX_APK_BYTES = 100L * 1024 * 1024;

    /** A release newer than the installed version, with this app's APK in it. */
    public record Release(String tag, String apkUrl) {}

    private UpdateChecker() {}

    /** Asks GitHub for the latest release; null when it has no APK for this app ({@code apkPrefix}). */
    public static Release fetchLatest(Context context, String apkPrefix) throws IOException {
        // Count the attempt, not just a success: offline checks must not retry on every screen start.
        prefs(context).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();
        String tag = latestTag();
        Release release = tag == null ? null : apkExists(apkUrl(tag, apkPrefix)) ? new Release(tag, apkUrl(tag, apkPrefix)) : null;
        remember(context, release);
        return release;
    }

    /**
     * The latest release's tag, from where github.com/.../releases/latest redirects to. The REST API
     * allows only 60 anonymous requests an hour per IP, which mobile carriers share between many
     * subscribers (HTTP 403); the website has no such limit.
     */
    private static String latestTag() throws IOException {
        HttpURLConnection conn = open(LATEST_RELEASE_URL);
        conn.setInstanceFollowRedirects(false);
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;   // no release yet
            if (code / 100 != 3) throw new IOException("HTTP " + code);
            return tagFromLocation(conn.getHeaderField("Location"));
        } finally {
            conn.disconnect();
        }
    }

    /** ".../releases/tag/v3.7.2" → "v3.7.2"; null when it isn't a plain version tag. */
    static String tagFromLocation(String location) {
        if (location == null) return null;
        int i = location.lastIndexOf("/releases/tag/");
        if (i < 0) return null;
        String tag = location.substring(i + "/releases/tag/".length());
        // Only plain versions: the tag becomes part of a URL, and a pre-release like v4.0-rc1 isn't offered.
        return tag.matches("v\\d+\\.\\d+(\\.\\d+)?") ? tag : null;
    }

    /** Release assets are named locsync-client-v3.7.2.apk / locsync-server-v3.7.2.apk. */
    static String apkUrl(String tag, String apkPrefix) {
        return RELEASE_DOWNLOAD_URL + tag + "/" + apkPrefix + tag + ".apk";
    }

    /** The download URL redirects to GitHub's file host when the asset exists, else 404. */
    private static boolean apkExists(String url) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("HEAD");
        try {
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return false;
            if (code / 100 == 3 || code == HttpURLConnection.HTTP_OK) return true;
            throw new IOException("HTTP " + code);
        } finally {
            conn.disconnect();
        }
    }

    /** True when {@code latest} (e.g. "v3.7.0") is a higher version than {@code installed}. */
    public static boolean isNewer(String latest, String installed) {
        int[] a = parseVersion(latest), b = parseVersion(installed);
        if (a == null || b == null) return false;
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    /** "v3.6.2" / "3.6" → {3, 6, 2} / {3, 6, 0}; null when not a plain version (e.g. a branch build). */
    static int[] parseVersion(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] parts = s.split("\\.");
        if (parts.length < 2 || parts.length > 3) return null;
        int[] out = new int[3];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** The last release seen (from any check), or null. */
    public static Release cached(Context context) {
        SharedPreferences p = prefs(context);
        String tag = p.getString(KEY_TAG, null), url = p.getString(KEY_APK_URL, null);
        return tag != null && url != null ? new Release(tag, url) : null;
    }

    /** Automatic checks run at most once a day. */
    public static boolean autoCheckDue(Context context, long intervalMs) {
        long last = prefs(context).getLong(KEY_CHECKED_AT, 0);
        long now = System.currentTimeMillis();
        return now - last >= intervalMs || now < last;
    }

    private static void remember(Context context, Release release) {
        prefs(context).edit()
                .putString(KEY_TAG, release != null ? release.tag() : null)
                .putString(KEY_APK_URL, release != null ? release.apkUrl() : null)
                .apply();
    }

    public interface Progress {
        void onProgress(int percent);
    }

    /** Downloads the APK into cache/updates (shared through the app's FileProvider). */
    public static File download(Context context, Release release, Progress progress) throws IOException {
        clearDownloads(context);   // one APK at a time
        File dir = downloadsDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, "update.apk");   // fixed name: the tag comes from the network
        File part = new File(dir, "update.apk.part");
        HttpURLConnection conn = open(release.apkUrl());   // follows GitHub's redirect to its CDN
        boolean ok = false;
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            long total = conn.getContentLengthLong();
            long done = 0;
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int lastPct = -1, n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (done > MAX_APK_BYTES) throw new IOException("APK too large");
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) progress.onProgress(lastPct = pct);
                    }
                }
            }
            if (total > 0 && done != total) throw new IOException("Download interrupted");
            if (!part.renameTo(out)) throw new IOException("Cannot save " + out);
            ok = true;
            return out;
        } finally {
            conn.disconnect();
            if (!ok) part.delete();
        }
    }

    /** Removes downloaded APKs (after an update is installed, or before a new download). */
    public static void clearDownloads(Context context) {
        File[] old = downloadsDir(context).listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    private static File downloadsDir(Context context) {
        return new File(context.getCacheDir(), "updates");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "LocSync");
        return conn;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
