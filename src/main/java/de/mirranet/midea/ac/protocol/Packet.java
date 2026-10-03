package de.mirranet.midea.ac.protocol;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The 0x5A5A transport packet that carries an encrypted 0xAA frame. Sending side ported from
 * {@code midealocal.packet_builder}, receiving side from {@code MideaDevice.parse_message}.
 *
 * <pre>
 *  0-1    5A 5A
 *  2-3    type: 01 11 data, 01 10 heartbeat
 *  4-5    total length, little endian
 *  6-7    20 00 (7B 00 for a heartbeat)
 *  8-11   message id, always 0 from our side
 * 12-19   timestamp, see {@link #timestamp}
 * 20-27   device id, little endian
 * 28-39   reserved
 * 40-     AES-ECB encrypted 0xAA frame
 * last 16 MD5 over everything before it, see {@link LocalSecurity#encode32}
 * </pre>
 */
public final class Packet {

    public static final int HEADER_LENGTH = 40;
    private static final int MIN_DATA_PACKET_LENGTH = 56;

    private Packet() {
    }

    /** Wraps a serialized 0xAA frame for sending. */
    public static byte[] build(long deviceId, byte[] frame) {
        byte[] header = header(deviceId);
        byte[] packet = LocalSecurity.concat(header, LocalSecurity.encryptPayload(frame));
        return finish(packet);
    }

    /** Keep-alive packet without payload, sent while the connection is otherwise idle. */
    public static byte[] heartbeat(long deviceId) {
        byte[] packet = header(deviceId);
        packet[3] = 0x10;
        packet[6] = 0x7B;
        return finish(packet);
    }

    private static byte[] finish(byte[] packet) {
        int length = packet.length + 16;
        packet[4] = (byte) length;
        packet[5] = (byte) (length >> 8);
        return LocalSecurity.concat(packet, LocalSecurity.encode32(packet));
    }

    private static byte[] header(long deviceId) {
        byte[] p = new byte[HEADER_LENGTH];
        p[0] = 0x5A;
        p[1] = 0x5A;
        p[2] = 0x01;
        p[3] = 0x11;
        p[6] = 0x20;
        System.arraycopy(timestamp(ZonedDateTime.now(ZoneOffset.UTC)), 0, p, 12, 8);
        for (int i = 0; i < 8; i++) {
            p[20 + i] = (byte) (deviceId >>> (8 * i));
        }
        return p;
    }

    /**
     * UTC time as eight decimal pairs, least significant first: hundredths of a second, seconds,
     * minutes, hours, day, month, year % 100, century. midea-local builds this from
     * {@code strftime("%Y%m%d%H%M%S%f")[:16]}.
     */
    static byte[] timestamp(ZonedDateTime t) {
        int[] pairs = {
                t.getYear() / 100, t.getYear() % 100, t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond(), t.getNano() / 10_000_000};
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) pairs[7 - i];
        }
        return out;
    }

    /** Complete packets found in a buffer and the incomplete rest. */
    public record Split(List<byte[]> packets, byte[] remainder) {
    }

    /**
     * Cuts a V2 byte stream into packets using the length field. Also used on the payloads that
     * come out of a V3 envelope, which are plain 0x5A5A packets as well.
     */
    public static Split splitV2(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        byte[] rest = data;
        while (rest.length >= 6) {
            int alleged = (rest[4] & 0xFF) | ((rest[5] & 0xFF) << 8);
            if (alleged <= 0) {
                // A zero length would never advance. midea-local loops forever here; we drop the buffer.
                return new Split(result, new byte[0]);
            }
            if (rest.length < alleged) {
                break;
            }
            result.add(Arrays.copyOfRange(rest, 0, alleged));
            rest = Arrays.copyOfRange(rest, alleged, rest.length);
        }
        return new Split(result, rest);
    }

    /**
     * Decrypts the 0xAA frame inside a received packet.
     *
     * @return the frame bytes, or {@code null} for heartbeat answers (types 0x0001/0x1001) and
     *         anything too short or not decryptable
     */
    public static byte[] extractFrame(byte[] packet) {
        if (packet.length < 6) {
            return null;
        }
        int payloadType = (packet[2] & 0xFF) | ((packet[3] & 0xFF) << 8);
        if (payloadType == 0x1001 || payloadType == 0x0001) {
            return null;
        }
        if (packet.length <= MIN_DATA_PACKET_LENGTH) {
            return null;
        }
        int payloadLength = ((packet[4] & 0xFF) | ((packet[5] & 0xFF) << 8)) - MIN_DATA_PACKET_LENGTH;
        if (payloadLength % 16 != 0) {
            return null;
        }
        byte[] decrypted = LocalSecurity.decryptPayload(
                Arrays.copyOfRange(packet, HEADER_LENGTH, packet.length - 16));
        return decrypted.length > 0 ? decrypted : null;
    }
}
