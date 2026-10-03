package de.mirranet.midea.ac.protocol;

/**
 * CRC-8/MAXIM (Dallas 1-Wire, reflected polynomial 0x8C).
 *
 * <p>Every 0xAC message body ends with this checksum over the body type, payload and message id.
 * midea-local ships it as a precomputed table ({@code crc8_854_table}); generating the table here
 * gives the same values, which the tests check.
 */
public final class Crc8 {

    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8C : crc >>> 1;
            }
            TABLE[i] = crc;
        }
    }

    private Crc8() {
    }

    public static int calculate(byte[] data) {
        return calculate(data, 0, data.length);
    }

    /** CRC over {@code data[from]} up to, but not including, {@code data[to]}. */
    public static int calculate(byte[] data, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            crc = TABLE[(crc ^ data[i]) & 0xFF];
        }
        return crc;
    }

    static int[] table() {
        return TABLE.clone();
    }
}
