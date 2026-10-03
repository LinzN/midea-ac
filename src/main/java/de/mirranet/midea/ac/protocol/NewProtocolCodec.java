package de.mirranet.midea.ac.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tag/length/value encoding used by the new protocol bodies.
 *
 * <p>Requests and 0xB5 answers use {@code tag_lo tag_hi len value}; 0xB0/0xB1 answers put an extra
 * zero byte before the length: {@code tag_lo tag_hi 00 len value}.
 */
public final class NewProtocolCodec {

    private NewProtocolCodec() {
    }

    static byte[] pack(int tag, byte[] value) {
        byte[] out = new byte[3 + value.length];
        out[0] = (byte) tag;
        out[1] = (byte) (tag >> 8);
        out[2] = (byte) value.length;
        System.arraycopy(value, 0, out, 3, value.length);
        return out;
    }

    /**
     * Parses an answer body. {@code body[0]} is the body type and decides the layout,
     * {@code body[1]} is the parameter count. Parameters with zero length are skipped, and parsing
     * stops quietly at the first truncated entry because some units send non-standard bodies.
     *
     * @return tag to value, in the order received
     */
    public static Map<Integer, byte[]> parse(byte[] body) {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        if (body.length < 2) {
            return result;
        }
        boolean shortPack = (body[0] & 0xFF) == 0xB5;
        int count = body[1] & 0xFF;
        int pos = 2;
        for (int i = 0; i < count; i++) {
            if (pos + 2 > body.length) {
                break;
            }
            int tag = (body[pos] & 0xFF) | ((body[pos + 1] & 0xFF) << 8);
            pos += 2;
            if (!shortPack) {
                pos++;
            }
            if (pos >= body.length) {
                break;
            }
            int length = body[pos++] & 0xFF;
            if (length > 0) {
                if (pos + length > body.length) {
                    break;
                }
                byte[] value = new byte[length];
                System.arraycopy(body, pos, value, 0, length);
                result.put(tag, value);
                pos += length;
            }
        }
        return result;
    }
}
