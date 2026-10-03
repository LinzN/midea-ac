package de.mirranet.midea.ac;

import de.mirranet.midea.ac.protocol.GeneralSetCommand;

import java.io.IOException;

/**
 * Collects changes to the basic settings and sends them as one command.
 *
 * <p>Get one from {@link MideaAirConditioner#control()}. It starts from the last known state, so
 * anything you don't touch stays as it is.
 *
 * <pre>{@code
 * ac.control()
 *   .mode(OperatingMode.COOL)
 *   .targetTemperature(22.5)
 *   .fanSpeed(FanSpeed.AUTO)
 *   .send();
 * }</pre>
 *
 * Not thread safe and meant to be used once; build a new one for the next command.
 */
public final class AcControl {

    private final MideaAirConditioner ac;
    private final GeneralSetCommand cmd;
    private final int previousMode;
    private boolean modeSet;
    private boolean fanSet;

    AcControl(MideaAirConditioner ac, GeneralSetCommand base, int previousMode) {
        this.ac = ac;
        this.cmd = base;
        this.previousMode = previousMode;
    }

    public AcControl power(boolean on) {
        cmd.power = on;
        return this;
    }

    public AcControl on() {
        return power(true);
    }

    public AcControl off() {
        return power(false);
    }

    /**
     * Sets the mode and switches the unit on, like pressing mode on the remote.
     *
     * <p>Also clears the dry flag, which can keep some units from leaving dry mode. If the unit was
     * in dry mode before and you don't set a fan speed, the fan goes back to auto, as midea-local does.
     */
    public AcControl mode(OperatingMode mode) {
        cmd.mode = mode.value();
        cmd.power = true;
        cmd.dry = false;
        modeSet = true;
        return this;
    }

    /**
     * Set point in Celsius, rounded to the nearest 0.5.
     *
     * @throws IllegalArgumentException outside 16 to 31.5, which the protocol can't express
     */
    public AcControl targetTemperature(double celsius) {
        double rounded = Math.round(celsius * 2) / 2.0;
        if (rounded < 16.0 || rounded > 31.5) {
            throw new IllegalArgumentException("Target temperature must be between 16 and 31.5 C: " + celsius);
        }
        cmd.targetTemperature = rounded;
        return this;
    }

    public AcControl fanSpeed(FanSpeed speed) {
        return fanSpeedRaw(speed.value());
    }

    /**
     * Raw fan speed, 1 to 100 for units with a stepless fan, or 102 for auto.
     *
     * @throws IllegalArgumentException outside 1 to 102
     */
    public AcControl fanSpeedRaw(int raw) {
        if (raw < 1 || raw > 102) {
            throw new IllegalArgumentException("Fan speed must be 1..100 or 102 (auto)");
        }
        cmd.fanSpeed = raw;
        fanSet = true;
        return this;
    }

    public AcControl swing(SwingMode swing) {
        cmd.swingVertical = swing.vertical();
        cmd.swingHorizontal = swing.horizontal();
        return this;
    }

    /** Selects a preset and switches off the others, including power saving. */
    public AcControl preset(Preset preset) {
        clearExclusive();
        switch (preset) {
            case COMFORT -> cmd.comfort = true;
            case ECO -> cmd.eco = true;
            case BOOST -> cmd.boost = true;
            case SLEEP -> cmd.sleep = true;
            case AWAY -> cmd.frostProtect = true;
            case NONE -> {
                // everything already cleared
            }
        }
        return this;
    }

    /** Power saving. Like the presets, it switches the others off. */
    public AcControl powerSaving(boolean on) {
        clearExclusive();
        cmd.powerSaving = on;
        return this;
    }

    // midea-local clears all of these before setting any one of them
    private void clearExclusive() {
        cmd.boost = false;
        cmd.powerSaving = false;
        cmd.sleep = false;
        cmd.eco = false;
        cmd.comfort = false;
        cmd.frostProtect = false;
    }

    /** Electric auxiliary heater (PTC), on units that have one. */
    public AcControl auxHeating(boolean on) {
        cmd.auxHeating = on;
        return this;
    }

    /** Dry the evaporator after switching off, against mould. */
    public AcControl dry(boolean on) {
        cmd.dry = on;
        return this;
    }

    /** Presence sensor. */
    public AcControl smartEye(boolean on) {
        cmd.smartEye = on;
        return this;
    }

    public AcControl naturalWind(boolean on) {
        cmd.naturalWind = on;
        return this;
    }

    /** Ioniser. */
    public AcControl anion(boolean on) {
        cmd.anion = on;
        return this;
    }

    /** Switches the unit's display to Fahrenheit. This API keeps using Celsius either way. */
    public AcControl fahrenheitDisplay(boolean on) {
        cmd.fahrenheit = on;
        return this;
    }

    /**
     * Sends the command.
     *
     * @return the state as confirmed by the unit, or the requested state if no confirmation came
     * @throws IllegalStateException if the unit should switch on but its mode is unknown
     *         (call {@link MideaAirConditioner#refresh()} first or set a mode)
     */
    public AcState send() throws IOException {
        if (modeSet && previousMode == OperatingMode.DRY.value() && !fanSet) {
            cmd.fanSpeed = FanSpeed.AUTO.value();
        }
        return ac.sendGeneralSet(cmd);
    }
}
