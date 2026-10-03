package de.mirranet.midea.ac.protocol;

/**
 * Parameter tags of the "new protocol" bodies: 0xB0 (set), 0xB1 (query) and 0xB5 (capabilities).
 * Names follow {@code NewProtocolTags} in midea-local.
 */
public final class NewProtocolTags {

    public static final int WIND_UD_ANGLE = 0x0009;
    public static final int WIND_LR_ANGLE = 0x000A;
    public static final int INDOOR_HUMIDITY = 0x0015;
    public static final int SCREEN_DISPLAY = 0x0017;
    public static final int BREEZELESS = 0x0018;
    public static final int PROMPT_TONE = 0x001A;
    public static final int SELF_CLEAN = 0x0039;
    public static final int ERROR_CODE_QUERY = 0x003F;
    public static final int INDIRECT_WIND = 0x0042;
    public static final int RATE_SELECT = 0x0048;
    public static final int FRESH_AIR_2 = 0x004B;
    public static final int OUT_SILENT = 0x00CD;
    public static final int BUZZER_ALL = 0x022C;
    public static final int FRESH_AIR_1 = 0x0233;

    // Only meaningful in 0xB5 capability answers.
    public static final int B5_WIND_SPEED = 0x0210;
    public static final int B5_ECO = 0x0212;
    public static final int B5_MODE = 0x0214;
    public static final int B5_WIND_SWING = 0x0215;
    public static final int B5_ELECTRICITY = 0x0216;
    public static final int B5_FILTER_REMIND = 0x0217;
    public static final int B5_PTC = 0x0219;
    public static final int B5_STRONG_WIND = 0x021A;
    public static final int B5_ANION = 0x021E;
    public static final int B5_HUMIDITY = 0x021F;
    public static final int B5_FILTER_CHECK = 0x0221;
    public static final int B5_FAHRENHEIT = 0x0222;
    public static final int B5_SCREEN_DISPLAY = 0x0224;
    public static final int B5_TEMPERATURE = 0x0225;
    public static final int B5_SOUND = 0x022C;

    /**
     * Tags of the regular 0xB1 status query. The error code tag is left out on purpose: with it,
     * some units stop answering this query and the capability query (see midea-local).
     */
    static final int[] DEFAULT_QUERY = {
            INDIRECT_WIND, BREEZELESS, INDOOR_HUMIDITY, SCREEN_DISPLAY, FRESH_AIR_1, FRESH_AIR_2,
            WIND_LR_ANGLE, WIND_UD_ANGLE, OUT_SILENT, BUZZER_ALL};

    private NewProtocolTags() {
    }
}
