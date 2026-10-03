package de.mirranet.midea.ac.protocol;

/**
 * Payload of the 0x40 "general set" command.
 *
 * <p>There is no way to change a single setting: every 0x40 frame carries power, mode, set point,
 * fan, swing and all flags at once. Start from the current device state, change what you need, and
 * send the whole thing. {@code AcControl} does that for you.
 */
public final class GeneralSetCommand {

    public boolean power;
    public boolean promptTone = true;
    /** 1 auto, 2 cool, 3 dry, 4 heat, 5 fan only. */
    public int mode;
    /** Celsius, 16.0 to 31.5 in 0.5 steps. */
    public double targetTemperature = 20.0;
    /** 1 to 100, or 102 for auto. */
    public int fanSpeed = 102;
    public boolean swingVertical;
    public boolean swingHorizontal;
    public boolean boost;
    public boolean powerSaving;
    public boolean smartEye;
    public boolean dry;
    public boolean auxHeating;
    public boolean eco;
    public boolean fahrenheit;
    public boolean sleep;
    public boolean naturalWind;
    public boolean frostProtect;
    public boolean comfort;
    public boolean anion;

    public GeneralSetCommand copy() {
        GeneralSetCommand c = new GeneralSetCommand();
        c.power = power;
        c.promptTone = promptTone;
        c.mode = mode;
        c.targetTemperature = targetTemperature;
        c.fanSpeed = fanSpeed;
        c.swingVertical = swingVertical;
        c.swingHorizontal = swingHorizontal;
        c.boost = boost;
        c.powerSaving = powerSaving;
        c.smartEye = smartEye;
        c.dry = dry;
        c.auxHeating = auxHeating;
        c.eco = eco;
        c.fahrenheit = fahrenheit;
        c.sleep = sleep;
        c.naturalWind = naturalWind;
        c.frostProtect = frostProtect;
        c.comfort = comfort;
        c.anion = anion;
        return c;
    }

    /**
     * The 22 bytes after the body type, laid out as in {@code MessageGeneralSet._body}.
     *
     * <p>The set point is stored as {@code (int) t & 0x0F} with 0x10 for the half degree; the device
     * adds 16 back. That is why only 16.0 to 31.5 can be expressed.
     *
     * <p>Some bits sit at different positions than in the 0xC0 status answer (eco is 0x80 in byte 8
     * here but 0x10 in the status). midea-local encodes it the same way.
     */
    byte[] payload() {
        byte[] b = new byte[22];
        b[0] = (byte) ((power ? 0x01 : 0) | (promptTone ? 0x40 : 0));
        int temp = ((int) targetTemperature) & 0x0F;
        boolean half = Math.round(targetTemperature * 2) % 2 != 0;
        b[1] = (byte) (((mode << 5) & 0xE0) | temp | (half ? 0x10 : 0));
        b[2] = (byte) (fanSpeed & 0x7F);
        b[6] = (byte) (0x30 | (swingVertical ? 0x0C : 0) | (swingHorizontal ? 0x03 : 0));
        b[7] = (byte) ((boost ? 0x20 : 0) | (powerSaving ? 0x08 : 0));
        b[8] = (byte) ((smartEye ? 0x01 : 0) | (dry ? 0x04 : 0) | (auxHeating ? 0x08 : 0)
                | (eco ? 0x80 : 0) | (anion ? 0x20 : 0));
        // boost is sent twice, here and in byte 7
        b[9] = (byte) ((fahrenheit ? 0x04 : 0) | (sleep ? 0x01 : 0) | (boost ? 0x02 : 0));
        b[16] = (byte) (naturalWind ? 0x40 : 0);
        b[20] = (byte) (frostProtect ? 0x80 : 0);
        b[21] = (byte) (comfort ? 0x01 : 0);
        return b;
    }
}
