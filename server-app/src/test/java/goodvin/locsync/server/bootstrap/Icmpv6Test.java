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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Icmpv6Test {

    @Test
    public void echoHeaderIsWellFormed() {
        byte[] e = Icmpv6.buildEcho(0x1234, 7);
        assertEquals("length", 8, e.length);
        assertEquals("type = Echo Request (128)", 128, e[0] & 0xFF);
        assertEquals("code", 0, e[1] & 0xFF);
        // Checksum left zero: the kernel fills it for a SOCK_DGRAM ICMPv6 socket.
        assertEquals("checksum hi", 0, e[2] & 0xFF);
        assertEquals("checksum lo", 0, e[3] & 0xFF);
        assertEquals("seq lo", 7, e[7] & 0xFF);
    }

    @Test
    public void echoReplyDetection() {
        assertTrue(Icmpv6.isEchoReply(new byte[]{(byte) 129, 0, 0, 0}, 4));
        assertFalse("echo request is not a reply",
                Icmpv6.isEchoReply(new byte[]{(byte) 128, 0, 0, 0}, 4));
        assertFalse("empty is not a reply", Icmpv6.isEchoReply(new byte[0], 0));
    }
}
