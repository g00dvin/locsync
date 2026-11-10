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

package goodvin.locsync.server.bootstrap;

import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import goodvin.locsync.server.R;
import goodvin.locsync.shared.AppLog;

/**
 * Hidden head-unit bootstrap screen (reached by tapping the version row 7×).
 * Enables ADB on the connected head unit over its root telnet, then installs
 * and launches the LocSync client or any user-picked APK over ADB.
 *
 * <p>The screen is a live 3-step flow — Connection, ADB, Install — each with a
 * state icon (idle / running / ok / fail) and a one-line detail, so a tap never
 * looks dead. Only one action runs at a time (buttons disable while busy) and
 * every log line is mirrored to logcat so {@code debugLogging} captures it.
 */
public class BootstrapActivity extends AppCompatActivity {

    private static final String TAG = "BootstrapActivity";
    private static final String CLIENT_ASSET = "locsync-client.apk";
    private static final int ADB_PORT = 5555;
    private static final int ADB_PROBE_TIMEOUT_MS = 2500;

    /** State of one step row; carries the glyph and colour it shows. */
    private enum Step {
        IDLE("○", R.color.ls_text_dim),
        RUN("…", R.color.ls_accent),
        OK("✓", R.color.ls_success),
        FAIL("✗", R.color.ls_error);

        final String glyph;
        @ColorRes final int color;

        Step(String glyph, @ColorRes int color) {
            this.glyph = glyph;
            this.color = color;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private EditText addr;
    private TextView logView;
    private ScrollView logScroll;
    private ProgressBar progress;
    private List<View> controls;
    private HuAdb huAdb;
    private volatile HuDiscovery discovery;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onApkPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);
        addr = findViewById(R.id.bootstrapAddr);
        logView = findViewById(R.id.bootstrapLog);
        logScroll = findViewById(R.id.bootstrapLogScroll);
        progress = findViewById(R.id.bootstrapProgress);

        Button scan = findViewById(R.id.bootstrapScan);
        Button test = findViewById(R.id.bootstrapTest);
        Button enable = findViewById(R.id.bootstrapEnableAdb);
        Button installClient = findViewById(R.id.bootstrapInstallClient);
        Button installApk = findViewById(R.id.bootstrapInstallApk);
        controls = Arrays.asList(scan, test, enable, installClient, installApk);

        scan.setOnClickListener(v -> scan());
        test.setOnClickListener(v -> testConnection());
        enable.setOnClickListener(v -> enableAdb());
        installClient.setOnClickListener(v -> installClient());
        installApk.setOnClickListener(v -> picker.launch(new String[]{"*/*"}));
    }

    @Override
    protected void onDestroy() {
        HuDiscovery d = discovery;
        if (d != null) d.cancel();   // stop the IPv4 pool and suppress late callbacks
        io.shutdownNow();
        super.onDestroy();
    }

    // --- actions ---------------------------------------------------------

    /** Auto-discovers the head unit and fills the field. */
    private void scan() {
        run(() -> {
            setStep(R.id.stepConnIcon, R.id.stepConnDetail, Step.RUN,
                    getString(R.string.bootstrap_conn_scanning));
            boolean[] found = {false};
            HuDiscovery d = new HuDiscovery(this, this::log);
            discovery = d;
            d.find(host -> {
                found[0] = true;   // find() delivers at most once
                runOnUiThread(() -> addr.setText(host));
                setStep(R.id.stepConnIcon, R.id.stepConnDetail, Step.OK,
                        getString(R.string.bootstrap_conn_found, host));
            });
            if (!found[0]) {
                setStep(R.id.stepConnIcon, R.id.stepConnDetail, Step.FAIL,
                        getString(R.string.bootstrap_conn_none));
            }
        });
    }

    /** Non-destructive probe: reachable? root shell? is ADB already up? */
    private void testConnection() {
        String host = host();
        if (host == null) return;
        run(() -> {
            setStep(R.id.stepConnIcon, R.id.stepConnDetail, Step.RUN,
                    getString(R.string.bootstrap_conn_testing));
            TelnetUnlock.Probe p = TelnetUnlock.probe(host, this::log);
            // Connection row = reachability. Root-vs-login lives in the detail text.
            setStep(R.id.stepConnIcon, R.id.stepConnDetail,
                    p.reachable ? Step.OK : Step.FAIL, p.detail);
            if (adbReachable(host)) {
                setStep(R.id.stepAdbIcon, R.id.stepAdbDetail, Step.OK,
                        getString(R.string.bootstrap_adb_already));
            }
        });
    }

    private void enableAdb() {
        String host = host();
        if (host == null) return;
        run(() -> {
            setStep(R.id.stepAdbIcon, R.id.stepAdbDetail, Step.RUN,
                    getString(R.string.bootstrap_adb_running));
            try {
                HuAdb adb = adb();
                TelnetUnlock.enableAdb(host, adb.publicKeyLine(), this::log);
                boolean up = adbReachable(host);
                setStep(R.id.stepAdbIcon, R.id.stepAdbDetail, up ? Step.OK : Step.FAIL,
                        up ? getString(R.string.bootstrap_adb_up) : "no adbd on 5555 yet — retry");
                toast(getString(R.string.bootstrap_adb_ok));
            } catch (Exception e) {
                log("! " + e);
                setStep(R.id.stepAdbIcon, R.id.stepAdbDetail, Step.FAIL, String.valueOf(e.getMessage()));
                toast(getString(R.string.bootstrap_adb_fail, String.valueOf(e.getMessage())));
            }
        });
    }

    private void installClient() {
        String host = host();
        if (host == null) return;
        List<String> appops = collectAppops();
        run(() -> {
            setStep(R.id.stepInstallIcon, R.id.stepInstallDetail, Step.RUN,
                    getString(R.string.bootstrap_install_running));
            File apk = copyAsset();
            if (apk == null) {
                setStep(R.id.stepInstallIcon, R.id.stepInstallDetail, Step.FAIL,
                        getString(R.string.bootstrap_no_client));
                toast(getString(R.string.bootstrap_no_client));
                return;
            }
            installApk(host, apk, appops);
        });
    }

    private void onApkPicked(@Nullable Uri uri) {
        if (uri == null) return;
        String host = host();
        if (host == null) return;
        List<String> appops = collectAppops();
        run(() -> {
            setStep(R.id.stepInstallIcon, R.id.stepInstallDetail, Step.RUN,
                    getString(R.string.bootstrap_install_running));
            try {
                installApk(host, copyUri(uri), appops);
            } catch (Exception e) {
                failInstall(e);
            }
        });
    }

    /** Reads the grant checkboxes (UI thread) into appops op names. */
    private List<String> collectAppops() {
        List<String> ops = new ArrayList<>();
        if (((CheckBox) findViewById(R.id.grantMockLocation)).isChecked()) {
            ops.add("android:mock_location");
        }
        if (((CheckBox) findViewById(R.id.grantInstall)).isChecked()) {
            ops.add("android:request_install_packages");
        }
        if (((CheckBox) findViewById(R.id.grantVpn)).isChecked()) {
            ops.add("ACTIVATE_VPN");
        }
        if (((CheckBox) findViewById(R.id.grantWriteSettings)).isChecked()) {
            ops.add("android:write_settings");
        }
        return ops;
    }

    /** Runs on the io thread. */
    private void installApk(String host, File apk, List<String> appops) {
        try {
            String pkg = packageOf(apk);
            if (pkg == null) {
                failInstall(new IllegalStateException("cannot read package name from APK"));
                return;
            }
            adb().installAndLaunch(host, apk, pkg, appops, this::log);
            setStep(R.id.stepInstallIcon, R.id.stepInstallDetail, Step.OK, pkg);
            toast(getString(R.string.bootstrap_install_ok, pkg));
        } catch (Exception e) {
            failInstall(e);
        }
    }

    // --- busy / step plumbing -------------------------------------------

    /**
     * Runs {@code task} on the single io thread, but only if no other action is
     * in flight; shows the header spinner and disables every button meanwhile.
     * This is what stops a tap from silently queueing behind a slow scan.
     */
    private void run(Runnable task) {
        if (!busy.compareAndSet(false, true)) {
            toast(getString(R.string.bootstrap_busy));
            return;
        }
        setControlsEnabled(false);
        runOnUiThread(() -> progress.setVisibility(View.VISIBLE));
        io.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log("! " + e);
            } finally {
                busy.set(false);
                runOnUiThread(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    setControlsEnabled(true);
                });
            }
        });
    }

    private void setControlsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            for (View v : controls) {
                v.setEnabled(enabled);
                v.setAlpha(enabled ? 1f : 0.5f);
            }
        });
    }

    private void setStep(int iconId, int detailId, Step s, @Nullable String detail) {
        runOnUiThread(() -> {
            TextView icon = findViewById(iconId);
            icon.setText(s.glyph);
            icon.setTextColor(getColor(s.color));
            TextView d = findViewById(detailId);
            if (TextUtils.isEmpty(detail)) {
                d.setVisibility(View.GONE);
            } else {
                d.setText(detail);
                d.setVisibility(View.VISIBLE);
            }
        });
    }

    // --- helpers ---------------------------------------------------------

    private HuAdb adb() throws Exception {
        if (huAdb == null) huAdb = new HuAdb(this);
        return huAdb;
    }

    /** True if ADB over TCP {@value #ADB_PORT} accepts a connection. */
    private boolean adbReachable(String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, ADB_PORT), ADB_PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private String host() {
        String h = addr.getText().toString().trim();
        if (TextUtils.isEmpty(h)) {
            toast(getString(R.string.bootstrap_need_addr));
            return null;
        }
        return h;
    }

    @Nullable
    private String packageOf(File apk) {
        PackageInfo pi = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return pi == null ? null : pi.packageName;
    }

    @Nullable
    private File copyAsset() {
        try (InputStream in = getAssets().open(CLIENT_ASSET)) {
            File out = new File(getCacheDir(), CLIENT_ASSET);
            copy(in, out);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private File copyUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("cannot open picked file");
            File out = new File(getCacheDir(), "picked.apk");
            copy(in, out);
            return out;
        }
    }

    private static void copy(InputStream in, File out) throws Exception {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private void failInstall(Exception e) {
        log("! " + e);
        setStep(R.id.stepInstallIcon, R.id.stepInstallDetail, Step.FAIL, String.valueOf(e.getMessage()));
        toast(getString(R.string.bootstrap_install_fail, String.valueOf(e.getMessage())));
    }

    private void log(String line) {
        AppLog.i(TAG, line);
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
