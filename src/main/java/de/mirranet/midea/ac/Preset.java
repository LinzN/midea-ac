package de.mirranet.midea.ac;

/**
 * Comfort presets. Only one can be active: selecting one clears the others, and power saving
 * as well, the same way the Midea app behaves.
 */
public enum Preset {
    NONE,
    COMFORT,
    ECO,
    /** Turbo, called "strong wind" in Midea's own code. */
    BOOST,
    SLEEP,
    /** Frost protection. Home Assistant shows it as "away". */
    AWAY
}
