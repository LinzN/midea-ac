package de.mirranet.midea.ac.protocol;

/**
 * Hex string helpers. Lower case on output, either case on input.
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = DIGITS[(data[i] >> 4) & 0x0F];
            out[i * 2 + 1] = DIGITS[data[i] & 0x0F];
        }
        return new String(out);
    }

    /**
     * Decodes a hex string. An odd number of digits is padded with a leading zero, which is what
     * {@code BigInteger.toString(16)} needs when the top nibble is zero.
     *
     * @throws IllegalArgumentException on non-hex characters
     */
    public static byte[] decode(String hex) {
        String s = hex.trim();
        if ((s.length() & 1) == 1) {
            s = "0" + s;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex string: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
