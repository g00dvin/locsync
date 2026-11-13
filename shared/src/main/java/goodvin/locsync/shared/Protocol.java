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

public final class Protocol {

    private Protocol() {}

    public static final int VERSION = 4;   // 4: paired peers, signed packets, monotonic fix time
    public static final int PORT = 8887;
    public static final int HEADER_BYTES = 2; // [version][type]
    public static final int MAX_PACKET_BYTES = 2048;

    public static final byte TYPE_HELLO = 0x01;
    public static final byte TYPE_RESPONSE = 0x02;
    public static final byte TYPE_VERSION_MISMATCH = 0x03;
    /** Server → client: [nonce]. The answer to a HELLO from anyone but the current, authenticated client. */
    public static final byte TYPE_CHALLENGE = 0x04;
    /** Client → server: [key], in the clear. Sent and accepted only while "Pair" is open on both sides. */
    public static final byte TYPE_PAIR = 0x05;

    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 16;
    public static final int MAC_BYTES = 16;
    /** An authenticating HELLO's payload: [server nonce][client nonce][mac]. A plain HELLO has none. */
    public static final int HELLO_AUTH_BYTES = 2 * NONCE_BYTES + MAC_BYTES;
    private static final byte TAG_HELLO = 'H', TAG_RESPONSE = 'R';

    // ponytail: packets are authenticated, not encrypted: someone on the car's own Wi-Fi can still read the
    // fixes (and relay a challenge). AES-GCM with the same key if that network ever stops being trusted.

    /** HMAC-SHA256 of the tag and the parts, truncated to {@link #MAC_BYTES}. */
    static byte[] mac(byte[] key, byte tag, byte[]... parts) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            m.update(tag);
            for (byte[] p : parts) {
                m.update(p);
            }
            return java.util.Arrays.copyOf(m.doFinal(), MAC_BYTES);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);   // HmacSHA256 is on every Android and JVM
        }
    }

    /** The HELLO that answers a CHALLENGE: proves the key and names the nonce the responses must be signed with. */
    public static byte[] buildAuthHello(byte[] key, byte[] serverNonce, byte[] clientNonce) {
        byte[] payload = new byte[HELLO_AUTH_BYTES];
        System.arraycopy(serverNonce, 0, payload, 0, NONCE_BYTES);
        System.arraycopy(clientNonce, 0, payload, NONCE_BYTES, NONCE_BYTES);
        System.arraycopy(mac(key, TAG_HELLO, serverNonce, clientNonce), 0, payload, 2 * NONCE_BYTES, MAC_BYTES);
        return buildPacket(TYPE_HELLO, payload);
    }

    /**
     * Checks an authenticating HELLO against the nonce this server handed out.
     *
     * @return the client's nonce, or null when the packet isn't one or doesn't verify
     */
    public static byte[] verifyAuthHello(byte[] key, byte[] serverNonce, byte[] buffer, Header h) {
        if (key == null || h.type != TYPE_HELLO || h.payloadLength != HELLO_AUTH_BYTES) {
            return null;
        }
        int o = h.payloadOffset;
        byte[] gotNonce = java.util.Arrays.copyOfRange(buffer, o, o + NONCE_BYTES);
        byte[] clientNonce = java.util.Arrays.copyOfRange(buffer, o + NONCE_BYTES, o + 2 * NONCE_BYTES);
        byte[] gotMac = java.util.Arrays.copyOfRange(buffer, o + 2 * NONCE_BYTES, o + HELLO_AUTH_BYTES);
        boolean ok = java.security.MessageDigest.isEqual(gotNonce, serverNonce)
                & java.security.MessageDigest.isEqual(gotMac, mac(key, TAG_HELLO, serverNonce, clientNonce));
        return ok ? clientNonce : null;
    }

    /** A RESPONSE: [body][mac over the client's nonce and the body]. */
    public static byte[] buildResponse(byte[] key, byte[] clientNonce, byte[] body) {
        byte[] payload = java.util.Arrays.copyOf(body, body.length + MAC_BYTES);
        System.arraycopy(mac(key, TAG_RESPONSE, clientNonce, body), 0, payload, body.length, MAC_BYTES);
        return buildPacket(TYPE_RESPONSE, payload);
    }

    /**
     * Length of a RESPONSE's body (it starts at {@code h.payloadOffset}), or -1 when the packet wasn't signed
     * with this key for this client nonce.
     */
    public static int verifyResponse(byte[] key, byte[] clientNonce, byte[] buffer, Header h) {
        int bodyLen = h.payloadLength - MAC_BYTES;
        if (key == null || clientNonce == null || h.type != TYPE_RESPONSE || bodyLen < 0) {
            return -1;
        }
        int o = h.payloadOffset;
        byte[] body = java.util.Arrays.copyOfRange(buffer, o, o + bodyLen);
        byte[] gotMac = java.util.Arrays.copyOfRange(buffer, o + bodyLen, o + h.payloadLength);
        return java.security.MessageDigest.isEqual(gotMac, mac(key, TAG_RESPONSE, clientNonce, body)) ? bodyLen : -1;
    }

    /** Random bytes for keys and nonces. */
    public static byte[] random(int n) {
        byte[] b = new byte[n];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    /** Build a packet: [version][type][payload...]. payload may be null. */
    public static byte[] buildPacket(byte type, byte[] payload) {
        int payloadLen = payload == null ? 0 : payload.length;
        byte[] packet = new byte[HEADER_BYTES + payloadLen];
        packet[0] = (byte) VERSION;
        packet[1] = type;
        if (payloadLen > 0) {
            System.arraycopy(payload, 0, packet, HEADER_BYTES, payloadLen);
        }
        return packet;
    }

    /** Parsed header view over a received datagram. */
    public static final class Header {
        public final int version;
        public final byte type;
        public final int payloadOffset;
        public final int payloadLength;

        Header(int version, byte type, int payloadOffset, int payloadLength) {
            this.version = version;
            this.type = type;
            this.payloadOffset = payloadOffset;
            this.payloadLength = payloadLength;
        }
    }

    /**
     * Parse the header from a received buffer holding {@code length} valid bytes.
     *
     * @throws IllegalArgumentException if the buffer is too small to contain a header.
     */
    public static Header parse(byte[] buffer, int length) {
        if (buffer == null || length < HEADER_BYTES) {
            throw new IllegalArgumentException("Packet too small: " + length);
        }
        int version = buffer[0] & 0xFF;
        byte type = buffer[1];
        return new Header(version, type, HEADER_BYTES, length - HEADER_BYTES);
    }

    public static boolean isSupportedVersion(int version) {
        return version == VERSION;
    }
}
