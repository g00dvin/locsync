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

    /** Unique token echoed back so we can tell a real shell from a login prompt. */
    private static final String MARK = "LSPROBE";

    private TelnetUnlock() {}

    /** Result of a non-destructive probe of the head unit's telnet port. */
    public static final class Probe {
        /** TCP {@code :23} accepted the connection. */
        public final boolean reachable;
        /** The daemon handed us a shell that ran {@code id} and reported uid 0. */
        public final boolean rootShell;
        /** Short human-readable detail for the status row. */
        public final String detail;

        Probe(boolean reachable, boolean rootShell, String detail) {
            this.reachable = reachable;
            this.rootShell = rootShell;
            this.detail = detail;
        }
    }

    /**
     * Connects, runs {@code id}, and reports what the telnet daemon exposes —
     * without changing anything on the unit. This is what the "Test connection"
     * button calls to answer "is this really an unauth root shell?".
     */
    public static Probe probe(String host, Consumer<String> log) {
        log.accept("telnet probe → [" + host + "]:" + PORT);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            StringBuilder banner = new StringBuilder();
            drainInto(in, banner, log);
            String cmd = "echo " + MARK + " $(id -u)";
            log.accept("$ " + cmd);
            out.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(300);
            StringBuilder reply = new StringBuilder();
            drainInto(in, reply, log);

            String all = banner.toString() + "\n" + reply.toString();
            if (all.contains(MARK + " 0")) {
                return new Probe(true, true, "root shell (uid 0), no auth");
            }
            if (all.contains(MARK + " ")) {
                return new Probe(true, false, "shell, but not root");
            }
            String lower = all.toLowerCase();
            if (lower.contains("login") || lower.contains("password")) {
                return new Probe(true, false, "login required — not an open shell");
            }
            return new Probe(true, false, "open, but no shell response");
        } catch (Exception e) {
            return new Probe(false, false, "port 23 not reachable: " + e.getMessage());
        }
    }

    /**
     * @param host     head unit address, e.g. {@code fe80::1234%wlan0} or {@code 192.168.1.1}
     * @param adbKey   this phone's ADB public key, in adb_keys line format
     *                 ({@code QAAAA…= user@host})
     * @param log      line sink (called off the UI thread)
     * @throws Exception on connect/IO failure, or if the daemon is not a root shell
     */
    public static void enableAdb(String host, String adbKey, Consumer<String> log) throws Exception {
        Probe p = probe(host, log);
        if (!p.reachable) throw new IllegalStateException(p.detail);
        if (!p.rootShell) throw new IllegalStateException(p.detail);
        // The key is interpolated into single-quoted shell strings below. A single
        // quote in the comment field (OEM-set hostname/account) would break out of
        // the quotes on the root shell — strip them; they are never valid in a key.
        String key = adbKey.trim().replace("'", "");
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
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("cancelled");
                }
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
        drainInto(in, null, log);
    }

    /**
     * Reads buffered bytes within the read timeout, logging each chunk and, if
     * {@code sink} is non-null, accumulating the raw text for later inspection.
     * Never throws on timeout.
     */
    private static void drainInto(InputStream in, StringBuilder sink, Consumer<String> log) {
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                if (sink != null) sink.append(s);
                String t = s.trim();
                if (!t.isEmpty()) log.accept(t);
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
