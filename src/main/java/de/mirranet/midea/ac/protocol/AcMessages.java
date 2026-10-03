package de.mirranet.midea.ac.protocol;

import java.util.Arrays;

/**
 * Builds every 0xAC request frame the library sends. Ported from
 * {@code midealocal.devices.ac.message}; the class names there are given in each method.
 *
 * <p>Most bodies end with a message id and a CRC-8 (see {@link Crc8}). The id is a rolling
 * counter supplied by the caller; answers are matched by body type, not by id.
 */
public final class AcMessages {

    public static final int DEVICE_TYPE = 0xAC;

    public static final int MSG_SET = 0x02;
    public static final int MSG_QUERY = 0x03;
    public static final int MSG_NOTIFY1 = 0x04;
    public static final int MSG_NOTIFY2 = 0x05;
    public static final int MSG_QUERY_APPLIANCE = 0xA0;

    // group ids for groupQuery(); the answer comes back as 0xC1 with 0x40 | group in byte 3
    public static final int GROUP_OPERATING_TIME = 0;
    public static final int GROUP_COMPRESSOR = 1;
    public static final int GROUP_INDOOR_FAN = 2;
    public static final int GROUP_OUTDOOR_FAN = 3;
    public static final int GROUP_ENERGY = 4;
    public static final int GROUP_HUMIDITY = 5;
    public static final int GROUP_COMPRESSOR_POWER = 7;

    private AcMessages() {
    }

    // body type + payload + message id + crc8
    static byte[] body(int bodyType, byte[] payload, int messageId) {
        byte[] out = new byte[payload.length + 3];
        out[0] = (byte) bodyType;
        System.arraycopy(payload, 0, out, 1, payload.length);
        out[payload.length + 1] = (byte) messageId;
        out[payload.length + 2] = (byte) Crc8.calculate(out, 0, payload.length + 2);
        return out;
    }

    /**
     * Appliance info query ({@code MessageQueryAppliance}). The answer's header carries the
     * message protocol version that all later requests should use.
     */
    public static byte[] applianceQuery() {
        return Frame.build(DEVICE_TYPE, 0, MSG_QUERY_APPLIANCE, new byte[19]);
    }

    /** Basic status query ({@code MessageQuery}, body 0x41). Answered with 0xC0. */
    public static byte[] statusQuery(int protocolVersion, int messageId) {
        byte[] p = new byte[19];
        p[0] = (byte) 0x81;
        p[2] = (byte) 0xFF;
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_QUERY, body(0x41, p, messageId));
    }

    /**
     * Capability query ({@code MessageCapabilitiesQuery}, body 0xB5). Units split their
     * capabilities over two answers; {@code additional = true} asks for the second one.
     */
    public static byte[] capabilitiesQuery(int protocolVersion, int messageId, boolean additional) {
        byte[] p = additional ? new byte[]{0x01, 0x01, 0x01} : new byte[]{0x01, 0x00};
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_QUERY, body(0xB5, p, messageId));
    }

    /**
     * Group data query ({@code MessageGroupDataQuery}), answered with 0xC1. Groups cover energy,
     * runtime and service data; many units only answer some of them. This body has no message id.
     *
     * @param group one of the {@code GROUP_*} constants
     */
    public static byte[] groupQuery(int protocolVersion, int group) {
        byte[] b = {0x41, 0x21, 0x01, (byte) (0x40 | group), 0x00, 0x01, 0};
        b[6] = (byte) Crc8.calculate(b, 0, 6);
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_QUERY, b);
    }

    /** New protocol query ({@code MessageNewProtocolQuery}, body 0xB1) for the given tags. */
    public static byte[] newProtocolQuery(int protocolVersion, int messageId, int... tags) {
        byte[] p = new byte[1 + tags.length * 2];
        p[0] = (byte) tags.length;
        for (int i = 0; i < tags.length; i++) {
            p[1 + i * 2] = (byte) tags[i];
            p[2 + i * 2] = (byte) (tags[i] >> 8);
        }
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_QUERY, body(0xB1, p, messageId));
    }

    /**
     * The standard new protocol status query. Rate select should only be asked for once the unit
     * advertised it in its capabilities; units without it don't answer at all otherwise.
     */
    public static byte[] newProtocolQuery(int protocolVersion, int messageId, boolean withRateSelect) {
        int[] tags = NewProtocolTags.DEFAULT_QUERY;
        if (withRateSelect) {
            tags = Arrays.copyOf(tags, tags.length + 1);
            tags[tags.length - 1] = NewProtocolTags.RATE_SELECT;
        }
        return newProtocolQuery(protocolVersion, messageId, tags);
    }

    /** {@code MessageGeneralSet}, body 0x40. Answered with 0xC0. */
    public static byte[] generalSet(int protocolVersion, int messageId, GeneralSetCommand cmd) {
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_SET, body(0x40, cmd.payload(), messageId));
    }

    /** {@code MessageNewProtocolSet}, body 0xB0. Answered with 0xB0 or 0xB1. */
    public static byte[] newProtocolSet(int protocolVersion, int messageId, NewProtocolSetCommand cmd) {
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_SET, body(0xB0, cmd.payload(), messageId));
    }

    /**
     * Toggles the LED display ({@code MessageToggleDisplay}). There is no "display on/off" command,
     * only this toggle, and it goes out as a query frame.
     */
    public static byte[] toggleDisplay(int protocolVersion, int messageId, boolean promptTone) {
        byte[] p = new byte[19];
        p[0] = (byte) (0x02 | (promptTone ? 0x40 : 0));
        p[2] = (byte) 0xFF;
        p[3] = 0x02;
        p[5] = 0x02;
        return Frame.build(DEVICE_TYPE, protocolVersion, MSG_QUERY, body(0x41, p, messageId));
    }
}
