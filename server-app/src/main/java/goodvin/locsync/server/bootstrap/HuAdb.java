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

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Thin wrapper over the embedded ADB client ({@code dev.mobile:dadb}).
 *
 * <p>dadb owns an RSA key pair stored in the app's files dir; its public key is
 * what {@link TelnetUnlock} injects into the head unit's {@code adb_keys}, so
 * {@code Dadb.create} connects pre-authorised with no on-screen dialog.
 *
 * <p>All methods block and run off the UI thread.
 */
public final class HuAdb {

    private static final int ADB_PORT = 5555;

    private final AdbKeyPair keyPair;
    private final String publicKeyLine;

    public HuAdb(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adb_key");
        File pub = new File(ctx.getFilesDir(), "adb_key.pub");
        if (!priv.exists() || !pub.exists()) {
            // Writes both files; the .pub is the adb_keys line format (QAAAA…= user@host).
            AdbKeyPair.generate(priv, pub);
        }
        keyPair = AdbKeyPair.read(priv, pub);
        publicKeyLine = new String(Files.readAllBytes(pub.toPath()), StandardCharsets.UTF_8).trim();
    }

    /** The adb_keys line to inject via telnet so this phone is trusted. */
    public String publicKeyLine() {
        return publicKeyLine;
    }

    /**
     * Pushes and installs an APK, then launches it.
     *
     * @param host   head unit address (ADB over TCP 5555)
     * @param apk    local APK file
     * @param pkg    package name (parsed from the APK by the caller)
     * @param appops appops to grant after install (e.g. {@code android:mock_location});
     *               each applied as {@code appops set <pkg> <op> allow}
     * @param log    line sink (off the UI thread)
     * @throws Exception on connect/install failure
     */
    public void installAndLaunch(String host, File apk, String pkg, List<String> appops,
                                 Consumer<String> log) throws Exception {
        try (Dadb dadb = Dadb.create(host, ADB_PORT, keyPair)) {
            String remote = "/data/local/tmp/ls_" + System.currentTimeMillis() + ".apk";
            log.accept("push " + apk.getName() + " → " + remote);
            dadb.push(apk, remote);

            log.accept("pm install -r -g " + remote);
            AdbShellResponse res = dadb.shell("pm install -r -g " + remote);
            dadb.shell("rm -f " + remote);
            String out = res.getOutput();
            if (res.getExitCode() != 0 || out == null || !out.contains("Success")) {
                throw new IllegalStateException(out == null ? "no output" : out.trim());
            }

            if (appops != null) {
                for (String op : appops) {
                    log.accept("appops set " + pkg + " " + op + " allow");
                    dadb.shell("appops set " + pkg + " " + op + " allow");
                }
            }

            log.accept("launch " + pkg);
            dadb.shell("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        }
    }
}
