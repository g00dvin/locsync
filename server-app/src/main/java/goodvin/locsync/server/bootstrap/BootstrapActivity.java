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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import goodvin.locsync.server.R;

/**
 * Hidden head-unit bootstrap screen (reached by tapping the version row 7×).
 * Enables ADB on the connected head unit over its root telnet, then installs
 * and launches the LocSync client or any user-picked APK over ADB.
 */
public class BootstrapActivity extends AppCompatActivity {

    private static final String CLIENT_ASSET = "locsync-client.apk";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EditText addr;
    private TextView logView;
    private ScrollView logScroll;
    private HuAdb huAdb;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onApkPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootstrap);
        addr = findViewById(R.id.bootstrapAddr);
        logView = findViewById(R.id.bootstrapLog);
        logScroll = findViewById(R.id.bootstrapLogScroll);

        ((Button) findViewById(R.id.bootstrapScan)).setOnClickListener(v -> scan());
        ((Button) findViewById(R.id.bootstrapEnableAdb)).setOnClickListener(v -> enableAdb());
        ((Button) findViewById(R.id.bootstrapInstallClient)).setOnClickListener(v -> installClient());
        ((Button) findViewById(R.id.bootstrapInstallApk)).setOnClickListener(v -> picker.launch(new String[]{"*/*"}));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // --- actions ---------------------------------------------------------

    private void enableAdb() {
        String host = host();
        if (host == null) return;
        io.execute(() -> {
            try {
                HuAdb adb = adb();
                TelnetUnlock.enableAdb(host, adb.publicKeyLine(), this::log);
                toast(getString(R.string.bootstrap_adb_ok));
            } catch (Exception e) {
                log("! " + e);
                toast(getString(R.string.bootstrap_adb_fail, String.valueOf(e.getMessage())));
            }
        });
    }

    private void installClient() {
        String host = host();
        if (host == null) return;
        List<String> appops = collectAppops();
        io.execute(() -> {
            try {
                File apk = copyAsset();
                if (apk == null) {
                    toast(getString(R.string.bootstrap_no_client));
                    return;
                }
                installApk(host, apk, appops);
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void onApkPicked(@Nullable Uri uri) {
        if (uri == null) return;
        String host = host();
        if (host == null) return;
        List<String> appops = collectAppops();
        io.execute(() -> {
            try {
                File apk = copyUri(uri);
                installApk(host, apk, appops);
            } catch (Exception e) {
                fail(e);
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
    private void installApk(String host, File apk, List<String> appops) throws Exception {
        String pkg = packageOf(apk);
        if (pkg == null) {
            fail(new IllegalStateException("cannot read package name from APK"));
            return;
        }
        adb().installAndLaunch(host, apk, pkg, appops, this::log);
        toast(getString(R.string.bootstrap_install_ok, pkg));
    }

    /** Auto-discovers the head unit (IPv6 link-local + IPv4) and fills the field. */
    private void scan() {
        io.execute(() -> {
            boolean[] filled = {false};
            new HuDiscovery(this, this::log).find(host -> {
                if (!filled[0]) {
                    filled[0] = true;
                    runOnUiThread(() -> addr.setText(host));
                }
            });
        });
    }

    // --- helpers ---------------------------------------------------------

    private HuAdb adb() throws Exception {
        if (huAdb == null) huAdb = new HuAdb(this);
        return huAdb;
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

    private void fail(Exception e) {
        log("! " + e);
        toast(getString(R.string.bootstrap_install_fail, String.valueOf(e.getMessage())));
    }

    private void log(String line) {
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
