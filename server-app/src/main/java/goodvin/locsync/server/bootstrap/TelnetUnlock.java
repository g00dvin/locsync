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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * One-time ADB unlock over the head unit's root telnet daemon (port 23, no auth
 * on the target units — a bare {@code #} root shell).
 *
 * <p>Sends, as root:
 * <ul>
 *   <li>{@code setprop persist.service.adb.button.visible ON} — reveals the ADB
 *       toggle in engineering mode;</li>
 *   <li>appends this phone's ADB public key to {@code /data/misc/adb/adb_keys}
 *       so {@code adbd} trusts it with no on-screen RSA dialog
 *       (the unit has {@code ro.secure=1});</li>
 *   <li>{@code setprop service.adb.tcp.port 5555; stop adbd; start adbd} —
 *       brings {@code adbd} up listening on TCP 5555.</li>
 * </ul>
 *
 * <p>Output is logged, not parsed. This is deliberately only the unlock step;
 * all file transfer and installs go over ADB afterwards.
 */
public final class TelnetUnlock {

    private static final int PORT = 23;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 1500;

    private TelnetUnlock() {}

    /**
     * @param host     head unit address, e.g. {@code fe80::1234%wlan0} or {@code 192.168.1.1}
     * @param adbKey   this phone's ADB public key, in adb_keys line format
     *                 ({@code QAAAA…= user@host})
     * @param log      line sink (called off the UI thread)
     * @throws Exception on connect/IO failure
     */
    public static void enableAdb(String host, String adbKey, Consumer<String> log) throws Exception {
        // Single-quote-safe: the adb key is base64 + " user@host", no quotes inside.
        String key = adbKey.trim();
        String[] commands = {
                "setprop persist.service.adb.button.visible ON",
                "mkdir -p /data/misc/adb",
                "touch /data/misc/adb/adb_keys",
                // Avoid duplicate lines on repeated runs.
                "grep -qF '" + key + "' /data/misc/adb/adb_keys || echo '" + key
                        + "' >> /data/misc/adb/adb_keys",
                "chmod 640 /data/misc/adb/adb_keys",
                "chown system:shell /data/misc/adb/adb_keys",
                "setprop service.adb.tcp.port 5555",
                "stop adbd",
                "start adbd",
        };

        try (Socket socket = new Socket()) {
            log.accept("telnet → [" + host + "]:" + PORT);
            socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            drain(in, log);                 // login banner / prompt
            for (String cmd : commands) {
                log.accept("$ " + redact(cmd));
                out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                sleep(250);
                drain(in, log);
            }
            log.accept("telnet done");
        }
    }

    /** Reads whatever is buffered within the read timeout; never throws on timeout. */
    private static void drain(InputStream in, Consumer<String> log) {
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                String s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) log.accept(s);
                if (n < buf.length) break;
            }
        } catch (Exception ignored) {
            // SocketTimeoutException is expected: nothing more to read right now.
        }
    }

    /** Keep the full public key out of the on-screen log. */
    private static String redact(String cmd) {
        int q = cmd.indexOf("QAAAA");
        if (q < 0) return cmd;
        return cmd.substring(0, q) + "<adb-key>…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
