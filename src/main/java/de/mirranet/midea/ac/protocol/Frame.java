package de.mirranet.midea.ac.protocol;

import java.util.Arrays;

/**
 * One 0xAA appliance frame, the unit the device logic actually talks in.
 *
 * <pre>
 * AA | length | device type | 00 00 00 00 00 | protocol version | message type | body | checksum
 * </pre>
 *
 * {@code length} counts header and body but not the checksum. {@link #body()} includes the body
 * type as its first byte (0x41, 0xC0, 0xB1 ...) and excludes the checksum, so byte offsets match
 * the ones used in midea-local.
 *
 * @param deviceType 0xAC for air conditioners
 * @param protocolVersion message protocol version; requests must echo what the device reports
 * @param messageType 0x02 set, 0x03 query, 0x04/0x05 notifications, 0xA0 appliance info
 * @param body body type byte followed by the payload
 */
public record Frame(int deviceType, int protocolVersion, int messageType, byte[] body) {

    public static final int HEADER_LENGTH = 10;

    /** First body byte, or -1 for an empty body. */
    public int bodyType() {
        return body.length > 0 ? body[0] & 0xFF : -1;
    }

    /** Serializes a request frame and appends the checksum. */
    public static byte[] build(int deviceType, int protocolVersion, int messageType, byte[] body) {
        int length = HEADER_LENGTH + body.length;
        byte[] out = new byte[length + 1];
        out[0] = (byte) 0xAA;
        out[1] = (byte) length;
        out[2] = (byte) deviceType;
        out[8] = (byte) protocolVersion;
        out[9] = (byte) messageType;
        System.arraycopy(body, 0, out, HEADER_LENGTH, body.length);
        out[length] = (byte) checksum(out, 1, length);
        return out;
    }

    /** Two's complement of the byte sum, computed from the length byte onwards. */
    public static int checksum(byte[] data, int from, int to) {
        int sum = 0;
        for (int i = from; i < to; i++) {
            sum += data[i] & 0xFF;
        }
        return (~sum + 1) & 0xFF;
    }

    /**
     * Parses a decrypted frame. The checksum isn't verified, matching midea-local.
     *
     * @return the frame, or {@code null} if this isn't an 0xAA frame
     */
    public static Frame parse(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH + 1 || (data[0] & 0xFF) != 0xAA) {
            return null;
        }
        return new Frame(data[2] & 0xFF, data[8] & 0xFF, data[9] & 0xFF,
                Arrays.copyOfRange(data, HEADER_LENGTH, data.length - 1));
    }

    @Override
    public String toString() {
        return String.format("Frame[type=%02x, proto=%d, msg=%02x, body=%s]",
                deviceType, protocolVersion, messageType, Hex.encode(body));
    }
}
