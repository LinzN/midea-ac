package de.mirranet.midea.ac;

/**
 * Fixed louver positions, for units that support angle control. Check
 * {@link Capabilities.Feature#SWING_VERTICAL_ANGLE} and {@code SWING_HORIZONTAL_ANGLE} first.
 */
public final class Vane {

    private Vane() {
    }

    /** Left/right louver, new protocol tag 0x000A. */
    public enum Horizontal {
        OFF(0), LEFT(1), LEFT_MID(25), MIDDLE(50), RIGHT_MID(75), RIGHT(100);

        private final int raw;

        Horizontal(int raw) {
            this.raw = raw;
        }

        public int raw() {
            return raw;
        }

        /** @return the position, or {@code null} if the unit reported something in between */
        public static Horizontal of(int raw) {
            for (Horizontal h : values()) {
                if (h.raw == raw) {
                    return h;
                }
            }
            return null;
        }
    }

    /** Up/down louver, new protocol tag 0x0009. */
    public enum Vertical {
        OFF(0), UP(1), UP_MID(25), MIDDLE(50), DOWN_MID(75), DOWN(100);

        private final int raw;

        Vertical(int raw) {
            this.raw = raw;
        }

        public int raw() {
            return raw;
        }

        /** @return the position, or {@code null} if the unit reported something in between */
        public static Vertical of(int raw) {
            for (Vertical v : values()) {
                if (v.raw == raw) {
                    return v;
                }
            }
            return null;
        }
    }
}
