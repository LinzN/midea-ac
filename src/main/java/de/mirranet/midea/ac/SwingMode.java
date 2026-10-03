package de.mirranet.midea.ac;

/**
 * Which louvers swing. Check {@link Capabilities.Feature#SWING_HORIZONTAL} before offering
 * horizontal swing; not every unit has movable side louvers.
 */
public enum SwingMode {
    OFF(false, false),
    VERTICAL(true, false),
    HORIZONTAL(false, true),
    BOTH(true, true);

    private final boolean vertical;
    private final boolean horizontal;

    SwingMode(boolean vertical, boolean horizontal) {
        this.vertical = vertical;
        this.horizontal = horizontal;
    }

    public boolean vertical() {
        return vertical;
    }

    public boolean horizontal() {
        return horizontal;
    }

    public static SwingMode of(boolean vertical, boolean horizontal) {
        for (SwingMode m : values()) {
            if (m.vertical == vertical && m.horizontal == horizontal) {
                return m;
            }
        }
        return OFF;
    }
}
