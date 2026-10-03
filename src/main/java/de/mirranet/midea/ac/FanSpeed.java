package de.mirranet.midea.ac;

/**
 * Fan speed presets. On the wire the fan speed is a number from 1 to 100, or 102 for auto.
 */
public enum FanSpeed {
    SILENT(20),
    LOW(40),
    MEDIUM(60),
    HIGH(80),
    FULL(100),
    AUTO(102);

    private final int value;

    FanSpeed(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /**
     * Maps a reported value to a preset. Units sometimes report values a little off the preset
     * (61 instead of 60), so anything above a preset counts as the next one up, like midea-local does.
     */
    public static FanSpeed fromRaw(int raw) {
        if (raw > 100) {
            return AUTO;
        }
        if (raw > 80) {
            return FULL;
        }
        if (raw > 60) {
            return HIGH;
        }
        if (raw > 40) {
            return MEDIUM;
        }
        if (raw > 20) {
            return LOW;
        }
        return SILENT;
    }
}
