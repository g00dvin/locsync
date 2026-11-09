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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Finds the head unit on the current Wi-Fi link, non-rooted:
 * <ul>
 *   <li>IPv6 link-local neighbours via an unprivileged ICMPv6 Echo sweep to
 *       {@code ff02::1} (how the root telnet host is reached), then a TCP probe
 *       of {@code :23};</li>
 *   <li>an IPv4 {@code /24} TCP probe of {@code :23} as a fallback.</li>
 * </ul>
 *
 * <p>All sockets are bound to the Wi-Fi {@link Network} so nothing routes over
 * cellular/VPN. mDNS ({@code _adb-tls-connect._tcp}) is a planned addition and
 * is intentionally not here yet.
 *
 * <p>Blocking; call from a background thread. Found addresses (already including
 * the {@code %iface} zone where relevant) are delivered to {@code onHost}.
 */
public final class HuDiscovery {

    private static final int TELNET_PORT = 23;
    private static final int PROBE_TIMEOUT_MS = 500;
    private static final int ECHO_COUNT = 3;
    private static final long ECHO_SPACING_MS = 300;
    private static final long SWEEP_DEADLINE_MS = 2000;

    private final Context ctx;
    private final Consumer<String> log;

    public HuDiscovery(Context ctx, Consumer<String> log) {
        this.ctx = ctx.getApplicationContext();
        this.log = log;
    }

    /** Runs both sweeps; each confirmed telnet host is delivered to {@code onHost}. */
    public void find(Consumer<String> onHost) {
        Network wifi = awaitWifi(4000);
        if (wifi == null) {
            log.accept("no Wi-Fi network — connect the phone to the head unit first");
            return;
        }
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        LinkProperties lp = cm.getLinkProperties(wifi);
        String iface = lp == null ? null : lp.getInterfaceName();
        if (iface == null) {
            log.accept("no Wi-Fi interface name");
            return;
        }
        try {
            icmpSweep(wifi, lp, iface, onHost);
        } catch (Exception e) {
            log.accept("ICMPv6 sweep failed: " + e);
        }
        try {
            ipv4Sweep(wifi, onHost);
        } catch (Exception e) {
            log.accept("IPv4 sweep failed: " + e);
        }
    }

    // --- IPv6 link-local ICMPv6 sweep -----------------------------------

    private void icmpSweep(Network wifi, LinkProperties lp, String iface, Consumer<String> onHost)
            throws Exception {
        int ifIndex = Os.if_nametoindex(iface);
        if (ifIndex == 0) {
            log.accept("if_nametoindex failed for " + iface);
            return;
        }
        Set<String> own = new LinkedHashSet<>();
        for (LinkAddress la : lp.getLinkAddresses()) {
            own.add(la.getAddress().getHostAddress());
        }

        FileDescriptor fd = Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_DGRAM,
                OsConstants.IPPROTO_ICMPV6);
        try {
            wifi.bindSocket(fd);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(PROBE_TIMEOUT_MS));

            byte[] allNodes = InetAddress.getByName("ff02::1").getAddress();
            InetAddress dest = Inet6Address.getByAddress(null, allNodes, ifIndex);
            log.accept("ICMPv6 echo → ff02::1%" + iface);
            for (int i = 0; i < ECHO_COUNT; i++) {
                byte[] echo = Icmpv6.buildEcho(0, i);
                Os.sendto(fd, echo, 0, echo.length, 0, dest, 0);
                Thread.sleep(ECHO_SPACING_MS);
            }

            Set<String> neighbours = new LinkedHashSet<>();
            byte[] buf = new byte[1500];
            long deadline = System.nanoTime() + SWEEP_DEADLINE_MS * 1_000_000L;
            while (System.nanoTime() < deadline) {
                InetSocketAddress src = new InetSocketAddress(0);
                int n;
                try {
                    n = Os.recvfrom(fd, buf, 0, buf.length, 0, src);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN) continue; // timeout slice
                    break;
                }
                InetAddress from = src.getAddress();
                if (Icmpv6.isEchoReply(buf, n) && from instanceof Inet6Address
                        && from.isLinkLocalAddress()
                        && !own.contains(from.getHostAddress())) {
                    // Re-attach the scope so a later connect targets the right link.
                    InetAddress scoped = Inet6Address.getByAddress(null, from.getAddress(), ifIndex);
                    neighbours.add(scoped.getHostAddress());
                }
            }

            for (String addr : neighbours) {
                if (tcpProbe(wifi, addr, TELNET_PORT)) {
                    log.accept("telnet host: " + addr);
                    onHost.accept(addr);
                }
            }
        } finally {
            try {
                Os.close(fd);
            } catch (ErrnoException ignored) {
            }
        }
    }

    // --- IPv4 /24 fallback ----------------------------------------------

    private void ipv4Sweep(Network wifi, Consumer<String> onHost) {
        LinkProperties lp = ctx.getSystemService(ConnectivityManager.class).getLinkProperties(wifi);
        String prefix = null;
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a.getAddress().length == 4 && !a.isLoopbackAddress()) {
                    String ip = a.getHostAddress();
                    int dot = ip.lastIndexOf('.');
                    if (dot > 0) prefix = ip.substring(0, dot + 1);
                }
            }
        }
        if (prefix == null) return;
        log.accept("scanning " + prefix + "0/24 :23 …");
        for (int i = 1; i < 255; i++) {
            String ip = prefix + i;
            if (tcpProbe(wifi, ip, TELNET_PORT)) {
                log.accept("telnet host: " + ip);
                onHost.accept(ip);
            }
        }
    }

    // --- helpers ---------------------------------------------------------

    private boolean tcpProbe(Network wifi, String host, int port) {
        try (Socket s = wifi.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Network awaitWifi(long timeoutMs) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        final Network[] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                out[0] = network;
                latch.countDown();
            }
        };
        cm.registerNetworkCallback(req, cb);
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                cm.unregisterNetworkCallback(cb);
            } catch (Exception ignored) {
            }
        }
        return out[0];
    }
}
