package de.mirranet.midea.ac;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a unit says it can do, read from its 0xB5 answers on the first refresh.
 *
 * <p>The raw values are not simple on/off flags; each capability has its own encoding. The decoding
 * comes from midea-local, which took it from the msmart project. Features the unit didn't report
 * are simply missing, so {@link #supports(Feature, boolean)} asks you for a default.
 */
public final class Capabilities {

    /** Capability names, matching the keys midea-local uses. */
    public enum Feature {
        HEAT_MODE, COOL_MODE, DRY_MODE, AUTO_MODE,
        SWING_HORIZONTAL, SWING_VERTICAL, SWING_HORIZONTAL_ANGLE, SWING_VERTICAL_ANGLE,
        FAN_SILENT, FAN_LOW, FAN_MEDIUM, FAN_HIGH, FAN_AUTO, FAN_CUSTOM,
        ECO, ANION, TURBO_COOL, TURBO_HEAT, DISPLAY_CONTROL,
        ENERGY_STATS, ENERGY_SETTING, ENERGY_BCD,
        RATE_SELECT, RATE_SELECT_2_LEVEL, RATE_SELECT_5_LEVEL,
        SOUND, HUMIDITY
    }

    private final Map<Feature, Boolean> flags;
    private final Map<OperatingMode, double[]> temperatureLimits;
    private final boolean received;

    Capabilities(Map<Feature, Boolean> flags, Map<OperatingMode, double[]> limits, boolean received) {
        this.flags = flags.isEmpty() ? new EnumMap<>(Feature.class) : new EnumMap<>(flags);
        EnumMap<OperatingMode, double[]> l = new EnumMap<>(OperatingMode.class);
        limits.forEach((k, v) -> l.put(k, v.clone()));
        this.temperatureLimits = l;
        this.received = received;
    }

    static Capabilities empty() {
        return new Capabilities(Map.of(), Map.of(), false);
    }

    /** False if the unit never answered the capability query; then everything else here is empty. */
    public boolean isReceived() {
        return received;
    }

    public boolean supports(Feature feature, boolean defaultValue) {
        return flags.getOrDefault(feature, defaultValue);
    }

    /** @return the flag, or {@code null} if the unit didn't report it */
    public Boolean get(Feature feature) {
        return flags.get(feature);
    }

    public Map<Feature, Boolean> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }

    /**
     * Modes the unit supports. A mode counts as supported unless the unit explicitly says
     * otherwise, which is also how Home Assistant decides. FAN_ONLY is always included.
     */
    public Set<OperatingMode> supportedModes() {
        Set<OperatingMode> modes = EnumSet.noneOf(OperatingMode.class);
        if (supports(Feature.AUTO_MODE, true)) {
            modes.add(OperatingMode.AUTO);
        }
        if (supports(Feature.COOL_MODE, true)) {
            modes.add(OperatingMode.COOL);
        }
        if (supports(Feature.DRY_MODE, true)) {
            modes.add(OperatingMode.DRY);
        }
        if (supports(Feature.HEAT_MODE, true)) {
            modes.add(OperatingMode.HEAT);
        }
        modes.add(OperatingMode.FAN_ONLY);
        return modes;
    }

    /**
     * Allowed set point range for a mode, as {@code {min, max}} in Celsius. The unit only reports
     * ranges for cool, auto and heat; dry and fan only use the cool range.
     *
     * @param mode the mode, or {@code null} for the cool range
     * @return the range, or {@code null} if the unit didn't report one
     */
    public double[] temperatureLimits(OperatingMode mode) {
        double[] l = temperatureLimits.get(mode == null ? OperatingMode.COOL : mode);
        if (l == null) {
            l = temperatureLimits.get(OperatingMode.COOL);
        }
        return l == null ? null : l.clone();
    }

    @Override
    public String toString() {
        return "Capabilities" + flags;
    }
}
