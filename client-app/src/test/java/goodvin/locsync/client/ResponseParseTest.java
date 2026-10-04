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

package goodvin.locsync.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import goodvin.locsync.proto.LocationProto;
import goodvin.locsync.shared.Protocol;

public class ResponseParseTest {
    // The client parses RESPONSE payloads in place from its reused receive buffer: the parse must
    // honour the header offset/length and the result must not alias the buffer.
    @Test
    public void parsesInPlaceFromReceiveBufferWithoutAliasing() throws Exception {
        LocationProto.ServerResponse sent = LocationProto.ServerResponse.newBuilder()
                .setStatus(LocationProto.Status.TRANSMITTING_LOCATION)
                .setSatellites(17)
                .setLocationUpdate(LocationProto.LocationUpdate.newBuilder()
                        .setTimestamp(1_700_000_000_123L).setLatitude(55.7558).setLongitude(37.6173)
                        .setAccuracy(4.2f).setSpeed(12.5f).setBearing(91f).setProvider("fused")
                        .setLocationAge(0.04f))
                .build();
        byte[] packet = Protocol.buildPacket(Protocol.TYPE_RESPONSE, sent.toByteArray());
        byte[] buffer = new byte[Protocol.MAX_PACKET_BYTES];
        Arrays.fill(buffer, (byte) 0x7f);                       // garbage around the datagram
        System.arraycopy(packet, 0, buffer, 0, packet.length);

        Protocol.Header h = Protocol.parse(buffer, packet.length);
        LocationProto.ServerResponse got = LocationProto.ServerResponse.parseFrom(
                ByteBuffer.wrap(buffer, h.payloadOffset, h.payloadLength));

        Arrays.fill(buffer, (byte) 0);                          // next datagram overwrites it
        assertEquals(sent, got);
        assertEquals("fused", got.getLocationUpdate().getProvider());
        assertTrue(got.getLocationUpdate().hasSpeed());
    }
}
