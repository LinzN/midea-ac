package de.mirranet.midea.ac;

/** Operating mode. The numbers are what the unit sends and expects. */
public enum OperatingMode {
    AUTO(1),
    COOL(2),
    DRY(3),
    HEAT(4),
    FAN_ONLY(5);

    private final int value;

    OperatingMode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /**
     * @return the mode, or {@code null} for values outside 1..5 (some units report 0 while off)
     */
    public static OperatingMode of(int value) {
        for (OperatingMode m : values()) {
            if (m.value == value) {
                return m;
            }
        }
        return null;
    }
}
