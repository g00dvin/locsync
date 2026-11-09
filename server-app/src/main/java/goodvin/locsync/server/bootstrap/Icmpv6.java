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

/** Pure ICMPv6 helpers (no Android deps, unit-testable). */
public final class Icmpv6 {

    public static final int TYPE_ECHO_REQUEST = 128;
    public static final int TYPE_ECHO_REPLY = 129;

    private Icmpv6() {}

    /**
     * Builds an 8-byte ICMPv6 Echo Request header. The checksum is left 0: for a
     * {@code SOCK_DGRAM} ICMPv6 socket the kernel computes it. The kernel also
     * overwrites the identifier with the socket's own, so {@code id} is only a
     * placeholder.
     */
    public static byte[] buildEcho(int id, int seq) {
        return new byte[]{
                (byte) TYPE_ECHO_REQUEST, // type
                0,                        // code
                0, 0,                     // checksum (kernel fills)
                (byte) (id >> 8), (byte) id,
                (byte) (seq >> 8), (byte) seq,
        };
    }

    /** True if the first byte of a received ICMPv6 message is an Echo Reply. */
    public static boolean isEchoReply(byte[] buf, int len) {
        return len >= 1 && (buf[0] & 0xFF) == TYPE_ECHO_REPLY;
    }
}
