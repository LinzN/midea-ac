package de.mirranet.midea.ac.protocol;

import java.io.ByteArrayOutputStream;

/**
 * Payload of the 0xB0 new protocol set command. Only fields that are not null are sent, so one
 * frame can change a single feature without touching anything else.
 *
 * <p>Fields are written in the same order as {@code MessageNewProtocolSet._body}.
 */
public final class NewProtocolSetCommand {

    public Boolean breezeless;
    public Boolean indirectWind;
    public Boolean promptTone;
    public Boolean screenDisplayAlternate;
    /** {power, speed} for units that report tag 0x0233. */
    public int[] freshAir1;
    /** {power, speed} for units that report tag 0x004B. */
    public int[] freshAir2;
    /** Louver position: 0 off, 1, 25, 50, 75, 100. */
    public Integer windLrAngle;
    public Integer windUdAngle;
    public Boolean outSilent;
    public Boolean sound;
    public Boolean selfClean;
    public Integer rateSelect;

    byte[] payload() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = 0;
        out.write(0); // parameter count, patched at the end
        if (breezeless != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.BREEZELESS, b(breezeless ? 1 : 0)));
            count++;
        }
        if (indirectWind != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.INDIRECT_WIND, b(indirectWind ? 2 : 1)));
            count++;
        }
        if (promptTone != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.PROMPT_TONE, b(promptTone ? 1 : 0)));
            count++;
        }
        if (screenDisplayAlternate != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.SCREEN_DISPLAY,
                    b(screenDisplayAlternate ? 0x64 : 0)));
            count++;
        }
        if (freshAir1 != null && freshAir1.length == 2) {
            byte[] v = new byte[10];
            v[0] = (byte) (freshAir1[0] > 0 ? 2 : 1);
            v[1] = (byte) freshAir1[1];
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.FRESH_AIR_1, v));
            count++;
        }
        if (freshAir2 != null && freshAir2.length == 2) {
            byte[] v = {(byte) (freshAir2[0] > 0 ? 1 : 0), (byte) freshAir2[1], (byte) 0xFF};
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.FRESH_AIR_2, v));
            count++;
        }
        if (windLrAngle != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.WIND_LR_ANGLE, b(windLrAngle)));
            count++;
        }
        if (windUdAngle != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.WIND_UD_ANGLE, b(windUdAngle)));
            count++;
        }
        if (outSilent != null) {
            // 0x03 switches it on; the firmware rejects 0x01
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.OUT_SILENT, b(outSilent ? 3 : 0)));
            count++;
        }
        if (sound != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.BUZZER_ALL, b(sound ? 1 : 0)));
            count++;
        }
        if (selfClean != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.SELF_CLEAN, b(selfClean ? 1 : 0)));
            count++;
        }
        if (rateSelect != null) {
            out.writeBytes(NewProtocolCodec.pack(NewProtocolTags.RATE_SELECT, b(rateSelect)));
            count++;
        }
        byte[] result = out.toByteArray();
        result[0] = (byte) count;
        return result;
    }

    private static byte[] b(int v) {
        return new byte[]{(byte) v};
    }
}
