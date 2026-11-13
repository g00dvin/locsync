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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

public class ProtocolTest {

    @Test
    public void buildPacket_writesVersionTypeAndPayload() {
        byte[] payload = {10, 20, 30};
        byte[] packet = Protocol.buildPacket(Protocol.TYPE_RESPONSE, payload);

        assertEquals(Protocol.HEADER_BYTES + payload.length, packet.length);
        assertEquals(Protocol.VERSION, packet[0] & 0xFF);
        assertEquals(Protocol.TYPE_RESPONSE, packet[1]);
        assertArrayEquals(payload, Arrays.copyOfRange(packet, Protocol.HEADER_BYTES, packet.length));
    }

    @Test
    public void buildPacket_nullPayload_isHeaderOnly() {
        byte[] packet = Protocol.buildPacket(Protocol.TYPE_HELLO, null);
        assertEquals(Protocol.HEADER_BYTES, packet.length);
        assertEquals(Protocol.TYPE_HELLO, packet[1]);
    }

    @Test
    public void parse_roundTripsBuildPacket() {
        byte[] payload = {1, 2, 3, 4};
        byte[] packet = Protocol.buildPacket(Protocol.TYPE_RESPONSE, payload);

        Protocol.Header header = Protocol.parse(packet, packet.length);
        assertEquals(Protocol.VERSION, header.version);
        assertEquals(Protocol.TYPE_RESPONSE, header.type);
        assertEquals(Protocol.HEADER_BYTES, header.payloadOffset);
        assertEquals(payload.length, header.payloadLength);
        assertArrayEquals(payload,
                Arrays.copyOfRange(packet, header.payloadOffset, header.payloadOffset + header.payloadLength));
    }

    @Test
    public void parse_tooSmall_throws() {
        try {
            Protocol.parse(new byte[]{0x02}, 1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void isSupportedVersion_onlyCurrent() {
        assertEquals(true, Protocol.isSupportedVersion(Protocol.VERSION));
        assertEquals(false, Protocol.isSupportedVersion(Protocol.VERSION + 1));
        assertEquals(false, Protocol.isSupportedVersion(1));
    }

    @Test
    public void authHello_verifiesOnlyWithTheKeyAndTheServersNonce() {
        byte[] key = Protocol.random(Protocol.KEY_BYTES);
        byte[] serverNonce = Protocol.random(Protocol.NONCE_BYTES), clientNonce = Protocol.random(Protocol.NONCE_BYTES);
        byte[] packet = Protocol.buildAuthHello(key, serverNonce, clientNonce);
        Protocol.Header h = Protocol.parse(packet, packet.length);

        assertArrayEquals(clientNonce, Protocol.verifyAuthHello(key, serverNonce, packet, h));
        assertEquals(null, Protocol.verifyAuthHello(Protocol.random(Protocol.KEY_BYTES), serverNonce, packet, h));
        assertEquals(null, Protocol.verifyAuthHello(key, Protocol.random(Protocol.NONCE_BYTES), packet, h));   // replay
        assertEquals(null, Protocol.verifyAuthHello(null, serverNonce, packet, h));                             // unpaired
        byte[] plain = Protocol.buildPacket(Protocol.TYPE_HELLO, null);
        assertEquals(null, Protocol.verifyAuthHello(key, serverNonce, plain, Protocol.parse(plain, plain.length)));
    }

    @Test
    public void response_verifiesOnlyUntamperedForThisClientNonce() {
        byte[] key = Protocol.random(Protocol.KEY_BYTES), clientNonce = Protocol.random(Protocol.NONCE_BYTES);
        byte[] body = {1, 2, 3, 4, 5};
        byte[] packet = Protocol.buildResponse(key, clientNonce, body);
        Protocol.Header h = Protocol.parse(packet, packet.length);

        assertEquals(body.length, Protocol.verifyResponse(key, clientNonce, packet, h));
        assertEquals(-1, Protocol.verifyResponse(key, Protocol.random(Protocol.NONCE_BYTES), packet, h));   // other session
        assertEquals(-1, Protocol.verifyResponse(Protocol.random(Protocol.KEY_BYTES), clientNonce, packet, h));
        packet[Protocol.HEADER_BYTES] ^= 1;
        assertEquals(-1, Protocol.verifyResponse(key, clientNonce, packet, h));
        byte[] unsigned = Protocol.buildPacket(Protocol.TYPE_RESPONSE, new byte[]{1, 2});
        assertEquals(-1, Protocol.verifyResponse(key, clientNonce, unsigned, Protocol.parse(unsigned, unsigned.length)));
    }
}
