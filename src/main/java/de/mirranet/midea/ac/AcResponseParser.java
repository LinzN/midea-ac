package de.mirranet.midea.ac;

import de.mirranet.midea.ac.Capabilities.Feature;
import de.mirranet.midea.ac.protocol.AcMessages;
import de.mirranet.midea.ac.protocol.Frame;
import de.mirranet.midea.ac.protocol.NewProtocolCodec;
import de.mirranet.midea.ac.protocol.NewProtocolTags;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns answers from the unit into {@link AcState} fields.
 *
 * <p>Port of {@code MessageACResponse}, the {@code X*MessageBody} classes and the post-processing
 * in {@code MideaACDevice.process_message} from midea-local. Byte offsets refer to the body
 * including its leading body type byte, exactly as in the Python code, so the two can be compared
 * line by line.
 *
 * <p>Also keeps what has to survive between frames: decoded capabilities, set point limits and
 * the pending self-clean command. Not thread safe; the client calls it under its own lock.
 */
final class AcResponseParser {

    // energy encodings of the 0xC1/0x44 answer, see DeviceConfig.Builder.powerAnalysisMethod
    static final int POWER_BCD = 1;
    static final int POWER_BINARY = 2;
    static final int POWER_MIXED = 3;
    static final int POWER_BINARY1 = 12;
    static final int POWER_BCD_ENERGY_BINARY_POWER = 101;

    private static final Set<Integer> B5_HEAT_MODE = Set.of(1, 2, 4, 6, 7, 9, 10, 11, 12, 13);
    private static final Set<Integer> B5_NO_COOL_MODE = Set.of(2, 10, 12);
    private static final Set<Integer> B5_DRY_MODE = Set.of(0, 1, 5, 6, 9, 11, 13);
    private static final Set<Integer> B5_AUTO_MODE = Set.of(0, 1, 2, 7, 8, 9, 13);
    private static final Set<Integer> B5_SWING_H = Set.of(1, 3);
    private static final Set<Integer> B5_FAN_LOW_HIGH = Set.of(3, 4, 5, 6, 7, 9);
    private static final Set<Integer> B5_FAN_MEDIUM = Set.of(5, 6, 7);
    private static final Set<Integer> B5_FAN_AUTO = Set.of(4, 5, 6, 9);
    private static final Set<Integer> B5_FAN_SILENT = Set.of(6, 9);
    private static final Set<Integer> B5_ECO = Set.of(1, 2);
    private static final Set<Integer> B5_TURBO_HEAT = Set.of(1, 3);
    private static final Set<Integer> B5_DISPLAY = Set.of(1, 2, 100);
    private static final Set<Integer> B5_ENERGY_STATS = Set.of(2, 3, 4, 5);
    private static final Set<Integer> B5_ENERGY_SETTING = Set.of(3, 5);
    private static final Set<Integer> B5_ENERGY_BCD = Set.of(2, 3);

    final Map<Feature, Boolean> capabilities = new EnumMap<>(Feature.class);
    final Map<OperatingMode, double[]> temperatureLimits = new EnumMap<>(OperatingMode.class);
    boolean capabilitiesReceived;
    int powerAnalysisMethod = POWER_BCD;
    Double customMinTemperature;
    Double customMaxTemperature;
    Boolean pendingSelfClean;
    long pendingSelfCleanDeadline;

    Capabilities capabilities() {
        return new Capabilities(capabilities, temperatureLimits, capabilitiesReceived);
    }

    /**
     * Applies one frame. Frames from other device types and unknown body types are ignored.
     *
     * @return true if the frame was understood
     */
    boolean apply(Frame f, AcState s) {
        if (f.deviceType() != AcMessages.DEVICE_TYPE) {
            return false;
        }
        byte[] b = f.body();
        int mt = f.messageType();
        int bt = f.bodyType();
        boolean handled = true;
        boolean basic = false;
        if (mt == AcMessages.MSG_NOTIFY2 && bt == 0xA0) {
            parseA0(b, s);
            basic = true;
        } else if (mt == AcMessages.MSG_NOTIFY1 && bt == 0xA1) {
            if (b.length < 18) {
                return false;
            }
            parseA1(b, s);
        } else if (mt == AcMessages.MSG_QUERY && bt == 0xB5) {
            parseB5(b);
        } else if ((mt == AcMessages.MSG_QUERY || mt == AcMessages.MSG_SET || mt == AcMessages.MSG_NOTIFY2)
                && (bt == 0xB0 || bt == 0xB1 || bt == 0xB5)) {
            parseBX(b, bt, s);
        } else if ((mt == AcMessages.MSG_QUERY || mt == AcMessages.MSG_SET) && bt == 0xC0) {
            parseC0(b, s);
            basic = true;
        } else if (mt == AcMessages.MSG_QUERY && bt == 0xC1) {
            parseC1(b, s);
        } else {
            handled = false; // includes 0xBB from sub-protocol units, which we don't support
        }
        if (!handled) {
            return false;
        }
        if (!s.power || (basic && s.swingVertical)) {
            s.indirectWind = false;
        }
        if (!s.power) {
            s.screenDisplay = false;
        }
        refreshLimits(s);
        return true;
    }

    // Limits depend on the mode, so this runs after every frame. Values from DeviceConfig win.
    void refreshLimits(AcState s) {
        double[] b5 = null;
        if (!temperatureLimits.isEmpty()) {
            b5 = temperatureLimits.getOrDefault(OperatingMode.of(s.modeRaw) == null ? OperatingMode.COOL
                    : OperatingMode.of(s.modeRaw), temperatureLimits.get(OperatingMode.COOL));
        }
        s.minTemperature = customMinTemperature != null ? customMinTemperature : (b5 != null ? b5[0] : null);
        s.maxTemperature = customMaxTemperature != null ? customMaxTemperature : (b5 != null ? b5[1] : null);
    }

    private static int u(byte[] b, int i) {
        return i < b.length ? b[i] & 0xFF : 0;
    }

    /**
     * Temperature as the unit sends it: {@code (raw - 50) / 2} for the integer part, with an
     * optional tenth digit from a separate nibble. 0xFF means no sensor. Port of
     * {@code XMessageBody.parse_temperature}, including the way it combines the two parts.
     */
    static Double temperature(int integer, int decimal) {
        if (integer == 0xFF) {
            return null;
        }
        double t = (integer - 50) / 2.0;
        if (decimal == 0) {
            return t;
        }
        double v = t < 0 ? (int) t - decimal * 0.1 : (int) t + decimal * 0.1;
        return Math.round(v * 10) / 10.0;
    }

    // 0 and 0xFF are placeholders for "not measured"
    private static Integer humidity(int raw) {
        return raw == 0 || raw == 0xFF ? null : raw;
    }

    // 0xC0: answer to the status query and to every 0x40 set

    static void parseC0(byte[] b, AcState s) {
        s.power = (u(b, 1) & 0x01) > 0;
        s.modeRaw = (u(b, 2) & 0xE0) >> 5;
        s.targetTemperature = (u(b, 2) & 0x0F) + 16.0 + ((u(b, 2) & 0x10) > 0 ? 0.5 : 0.0);
        // Units that go below 17 C also send the set point as "degrees - 12" in the low 5 bits of
        // byte 13. At 16 C the field above still says 17, so this one wins when it's set.
        // midea-local ignores it; msmart-ng reads it the same way.
        int alternate = u(b, 13) & 0x1F;
        if (alternate != 0) {
            s.targetTemperature = alternate + 12.0 + ((u(b, 2) & 0x10) > 0 ? 0.5 : 0.0);
        }
        s.fanSpeedRaw = u(b, 3) & 0x7F;
        s.swingVertical = (u(b, 7) & 0x0C) > 0;
        s.swingHorizontal = (u(b, 7) & 0x03) > 0;
        s.boost = (u(b, 8) & 0x20) > 0 || (u(b, 10) & 0x02) > 0;
        s.powerSaving = (u(b, 8) & 0x08) > 0;
        s.smartEye = (u(b, 8) & 0x40) > 0;
        s.pmv = (u(b, 14) & 0x0F) * 0.5 - 3.5;
        s.naturalWind = (u(b, 9) & 0x02) > 0;
        s.dry = (u(b, 9) & 0x04) > 0;
        s.auxHeating = (u(b, 9) & 0x08) > 0;
        s.eco = (u(b, 9) & 0x10) > 0;
        s.anion = (u(b, 9) & 0x20) > 0;
        s.fahrenheit = (u(b, 10) & 0x04) > 0;
        s.sleep = (u(b, 10) & 0x01) > 0;
        int decimal = b.length > 20 ? u(b, 15) : 0;
        s.indoorTemperature = temperature(u(b, 11), decimal & 0x0F);
        s.outdoorTemperature = temperature(u(b, 12), decimal >> 4);
        s.fullDust = (u(b, 13) & 0x20) > 0;
        s.screenDisplay = ((u(b, 14) >> 4) & 0x07) != 0x07 && s.power;
        s.frostProtect = b.length >= 22 && (u(b, 21) & 0x80) > 0;
        s.comfort = b.length >= 23 && (u(b, 22) & 0x01) > 0;
    }

    // 0xA0 / 0xA1: notifications the unit sends without being asked

    static void parseA0(byte[] b, AcState s) {
        s.power = (u(b, 1) & 0x01) > 0;
        s.targetTemperature = ((u(b, 1) & 0x3E) >> 1) - 4 + 16.0 + ((u(b, 1) & 0x40) > 0 ? 0.5 : 0.0);
        s.modeRaw = (u(b, 2) & 0xE0) >> 5;
        s.fanSpeedRaw = u(b, 3) & 0x7F;
        s.swingVertical = (u(b, 7) & 0x0C) > 0;
        s.swingHorizontal = (u(b, 7) & 0x03) > 0;
        s.boost = (u(b, 8) & 0x20) > 0 || (u(b, 10) & 0x02) > 0;
        s.powerSaving = (u(b, 8) & 0x08) > 0;
        s.pmv = ((u(b, 11) & 0xF0) >> 4) * 0.5 - 3.5;
        s.screenDisplay = ((u(b, 14) >> 4) & 0x07) != 0x07 && s.power;
        s.smartEye = (u(b, 9) & 0x01) > 0;
        s.dry = (u(b, 9) & 0x04) > 0;
        s.auxHeating = (u(b, 9) & 0x08) > 0;
        s.eco = (u(b, 9) & 0x10) > 0;
        s.anion = (u(b, 9) & 0x20) > 0;
        s.sleep = (u(b, 10) & 0x01) > 0;
        s.naturalWind = (u(b, 10) & 0x40) > 0;
        s.fullDust = (u(b, 13) & 0x20) > 0;
        s.comfort = b.length > 16 && (u(b, 14) & 0x01) > 0;
        s.frostProtect = b.length >= 22 && (u(b, 21) & 0x80) > 0;
    }

    static void parseA1(byte[] b, AcState s) {
        int decimal = b.length > 20 ? u(b, 18) : 0;
        s.indoorTemperature = temperature(u(b, 13), decimal & 0x0F);
        s.outdoorTemperature = temperature(u(b, 14), decimal >> 4);
        s.indoorHumidity = humidity(u(b, 17));
    }


    // 0xB0 / 0xB1: new protocol values. Only tags present in the body are touched.
    void parseBX(byte[] b, int bt, AcState s) {
        Map<Integer, byte[]> p = NewProtocolCodec.parse(b);
        byte[] v;
        if ((v = p.get(NewProtocolTags.INDIRECT_WIND)) != null) {
            s.indirectWind = (v[0] & 0xFF) == 0x02;
        }
        if ((v = p.get(NewProtocolTags.INDOOR_HUMIDITY)) != null) {
            s.indoorHumidity = humidity(v[0] & 0xFF);
        }
        if ((v = p.get(NewProtocolTags.BREEZELESS)) != null) {
            s.breezeless = (v[0] & 0xFF) == 1;
        }
        if ((v = p.get(NewProtocolTags.SCREEN_DISPLAY)) != null) {
            s.screenDisplayAlternate = (v[0] & 0xFF) > 0;
        }
        if ((v = p.get(NewProtocolTags.FRESH_AIR_1)) != null && v.length >= 2) {
            s.freshAirVersion = 1;
            s.freshAirPower = (v[0] & 0xFF) == 0x02;
            s.freshAirFanSpeed = v[1] & 0xFF;
        }
        if ((v = p.get(NewProtocolTags.FRESH_AIR_2)) != null && v.length >= 2) {
            s.freshAirVersion = 2;
            s.freshAirPower = (v[0] & 0xFF) > 0;
            s.freshAirFanSpeed = v[1] & 0xFF;
        }
        if ((v = p.get(NewProtocolTags.WIND_LR_ANGLE)) != null) {
            s.windLrAngle = v[0] & 0xFF;
        }
        if ((v = p.get(NewProtocolTags.WIND_UD_ANGLE)) != null) {
            s.windUdAngle = v[0] & 0xFF;
        }
        if ((v = p.get(NewProtocolTags.RATE_SELECT)) != null) {
            s.rateSelect = v[0] & 0xFF;
        }
        if ((v = p.get(NewProtocolTags.OUT_SILENT)) != null) {
            s.outSilent = (v[0] & 0xFF) == 0x03;
        }
        if ((v = p.get(NewProtocolTags.BUZZER_ALL)) != null) {
            s.sound = (v[0] & 0xFF) > 0;
        }
        if ((v = p.get(NewProtocolTags.ERROR_CODE_QUERY)) != null) {
            s.errorCode = v[0] & 0xFF;
        }
        // In a 0xB5 body this tag only means "supported", so ignore it there.
        if (bt != 0xB5 && (v = p.get(NewProtocolTags.SELF_CLEAN)) != null) {
            boolean active = (v[0] & 0xFF) > 0;
            if (pendingSelfClean != null) {
                if (active == pendingSelfClean || System.nanoTime() >= pendingSelfCleanDeadline) {
                    pendingSelfClean = null;
                } else {
                    return; // still the old state, sent before the unit processed our command
                }
            }
            s.selfClean = active;
        }
    }

    /*
     * 0xB5: capabilities, spread over two answers that both land here. Flags accumulate.
     * The value sets below are reverse engineered (msmart via midea-local); most bytes are
     * small enums rather than on/off.
     */
    void parseB5(byte[] b) {
        Map<Integer, byte[]> p = NewProtocolCodec.parse(b);
        capabilitiesReceived = true;
        byte[] t = p.get(NewProtocolTags.B5_TEMPERATURE);
        if (t != null && t.length >= 6) {
            double[] cool = {(t[0] & 0xFF) / 2.0, (t[1] & 0xFF) / 2.0};
            double[] auto = {(t[2] & 0xFF) / 2.0, (t[3] & 0xFF) / 2.0};
            double[] heat = {(t[4] & 0xFF) / 2.0, (t[5] & 0xFF) / 2.0};
            temperatureLimits.put(OperatingMode.AUTO, auto);
            temperatureLimits.put(OperatingMode.COOL, cool);
            temperatureLimits.put(OperatingMode.DRY, cool);
            temperatureLimits.put(OperatingMode.HEAT, heat);
            temperatureLimits.put(OperatingMode.FAN_ONLY, cool);
        }
        Map<Feature, Boolean> c = capabilities;
        c.putIfAbsent(Feature.FAN_LOW, true);
        c.putIfAbsent(Feature.FAN_MEDIUM, true);
        c.putIfAbsent(Feature.FAN_HIGH, true);
        c.putIfAbsent(Feature.FAN_AUTO, true);
        Integer v;
        if ((v = first(p, NewProtocolTags.B5_MODE)) != null) {
            c.put(Feature.HEAT_MODE, B5_HEAT_MODE.contains(v));
            c.put(Feature.COOL_MODE, !B5_NO_COOL_MODE.contains(v));
            c.put(Feature.DRY_MODE, B5_DRY_MODE.contains(v));
            c.put(Feature.AUTO_MODE, B5_AUTO_MODE.contains(v));
        }
        if ((v = first(p, NewProtocolTags.B5_WIND_SWING)) != null) {
            c.put(Feature.SWING_HORIZONTAL, B5_SWING_H.contains(v));
            c.put(Feature.SWING_VERTICAL, v < 2);
        }
        if ((v = first(p, NewProtocolTags.WIND_LR_ANGLE)) != null) {
            c.put(Feature.SWING_HORIZONTAL_ANGLE, v == 1);
        }
        if ((v = first(p, NewProtocolTags.WIND_UD_ANGLE)) != null) {
            c.put(Feature.SWING_VERTICAL_ANGLE, v == 1);
        }
        if ((v = first(p, NewProtocolTags.B5_WIND_SPEED)) != null) {
            boolean custom = v == 1;
            c.put(Feature.FAN_SILENT, custom || B5_FAN_SILENT.contains(v));
            c.put(Feature.FAN_LOW, custom || B5_FAN_LOW_HIGH.contains(v));
            c.put(Feature.FAN_MEDIUM, custom || B5_FAN_MEDIUM.contains(v));
            c.put(Feature.FAN_HIGH, custom || B5_FAN_LOW_HIGH.contains(v));
            c.put(Feature.FAN_AUTO, custom || B5_FAN_AUTO.contains(v));
            c.put(Feature.FAN_CUSTOM, custom);
        }
        if ((v = first(p, NewProtocolTags.B5_ECO)) != null) {
            c.put(Feature.ECO, B5_ECO.contains(v));
        }
        if ((v = first(p, NewProtocolTags.B5_ANION)) != null) {
            c.put(Feature.ANION, v == 1);
        }
        if ((v = first(p, NewProtocolTags.B5_STRONG_WIND)) != null) {
            c.put(Feature.TURBO_COOL, v < 2);
            c.put(Feature.TURBO_HEAT, B5_TURBO_HEAT.contains(v));
        }
        if ((v = first(p, NewProtocolTags.B5_SCREEN_DISPLAY)) != null) {
            c.put(Feature.DISPLAY_CONTROL, B5_DISPLAY.contains(v));
        }
        if ((v = first(p, NewProtocolTags.B5_ELECTRICITY)) != null) {
            c.put(Feature.ENERGY_STATS, B5_ENERGY_STATS.contains(v));
            c.put(Feature.ENERGY_SETTING, B5_ENERGY_SETTING.contains(v));
            c.put(Feature.ENERGY_BCD, B5_ENERGY_BCD.contains(v));
        }
        if ((v = first(p, NewProtocolTags.RATE_SELECT)) != null) {
            c.put(Feature.RATE_SELECT, v > 0);
            c.put(Feature.RATE_SELECT_2_LEVEL, (v & 0x1) > 0);
            c.put(Feature.RATE_SELECT_5_LEVEL, (v & 0x2) > 0);
        }
        if ((v = first(p, NewProtocolTags.B5_SOUND)) != null) {
            c.put(Feature.SOUND, v == 1);
        }
        if ((v = first(p, NewProtocolTags.B5_HUMIDITY)) != null) {
            c.put(Feature.HUMIDITY, (v & 0x3) > 0);
        }
    }

    private static Integer first(Map<Integer, byte[]> p, int tag) {
        byte[] v = p.get(tag);
        return v == null || v.length == 0 ? null : v[0] & 0xFF;
    }

    // 0xC1: group data. Byte 3 says which group, 0x40 | group number.

    void parseC1(byte[] b, AcState s) {
        if (b.length <= 3) {
            return;
        }
        int group = u(b, 3);
        switch (group) {
            case 0x44 -> {
                if (b.length < 19) {
                    return;
                }
                s.totalEnergyConsumption = consumption(powerAnalysisMethod, b, 4, 8);
                s.totalOperatingConsumption = consumption(powerAnalysisMethod, b, 8, 12);
                s.currentEnergyConsumption = consumption(powerAnalysisMethod, b, 12, 16);
                s.realtimePower = power(powerAnalysisMethod, b, 16, 19);
            }
            case 0x41 -> {
                if (b.length < 15) {
                    return;
                }
                s.compressorFrequency = u(b, 4);
                s.targetCompressorFrequency = u(b, 5);
                s.compressorCurrent = u(b, 7);
                s.compressorVoltage = u(b, 8);
                s.indoorAmbientTemperature = (u(b, 10) - 30) / 2.0;
                s.indoorCoilTemperature = (u(b, 11) - 30) / 2.0;
                s.outdoorCoilTemperature = (u(b, 12) - 50) / 2.0;
                s.outdoorAmbientTemperature = (u(b, 13) - 50) / 2.0;
                s.dischargePipeTemperature = u(b, 14);
            }
            case 0x42 -> {
                if (b.length < 9) {
                    return;
                }
                s.targetIndoorFanSpeed = u(b, 4) * 8;
                s.indoorFanSpeed = u(b, 5) * 8;
                s.waterPumpRunning = (u(b, 8) & 0x10) != 0;
            }
            case 0x03, 0x43 -> s.outdoorFanSpeed = u(b, 10) * 8;
            case 0x47 -> {
                if (b.length >= 12) {
                    s.compressorPower = u(b, 10) + (u(b, 11) << 8);
                }
            }
            case 0x40 -> {
                if (b.length < 19) {
                    return;
                }
                s.electrifyTime = hours(b, 4);
                s.totalOperatingTime = hours(b, 9);
                s.currentOperatingTime = hours(b, 14);
            }
            case 0x45 -> {
                if (b.length > 4) {
                    s.indoorHumidity = humidity(u(b, 4));
                }
            }
            default -> {
                // not decoded
            }
        }
    }

    // days (2 bytes, big endian), hours, minutes, seconds, summed up as hours
    private static double hours(byte[] b, int i) {
        int days = (u(b, i) << 8) | u(b, i + 1);
        return days * 24 + u(b, i + 2) + u(b, i + 3) / 60.0 + u(b, i + 4) / 3600.0;
    }

    /*
     * Folds the bytes of an energy field into one number. BCD: two decimal digits per byte.
     * Binary: plain big endian. Mixed: each byte is 0..99. Method 12 and 101 share the
     * folding of 2 and 1 (method % 10), as in midea-local.
     */
    static double value(int method, byte[] b, int from, int to) {
        if (method != POWER_BCD && method != POWER_BINARY && method != POWER_MIXED
                && method != POWER_BINARY1 && method != POWER_BCD_ENERGY_BINARY_POWER) {
            return 0.0;
        }
        int m = method % 10;
        long v = 0;
        for (int i = from; i < to; i++) {
            int x = u(b, i);
            switch (m) {
                case POWER_BCD -> v = (x >> 4) * 10L + (x & 0x0F) + v * 100;
                case POWER_BINARY -> v = x + (v << 8);
                case POWER_MIXED -> v = x + v * 100;
                default -> {
                    return 0.0;
                }
            }
        }
        return v;
    }

    // power is sent in 0.1 W
    static double power(int method, byte[] b, int from, int to) {
        if (method == POWER_BCD_ENERGY_BINARY_POWER) {
            return value(POWER_BINARY, b, from, to) / 10;
        }
        return value(method, b, from, to) / 10;
    }

    // energy is sent in 0.01 kWh, except plain binary which uses 0.1 kWh
    static double consumption(int method, byte[] b, int from, int to) {
        if (method == POWER_BCD_ENERGY_BINARY_POWER) {
            return value(POWER_BCD, b, from, to) / 100;
        }
        return value(method, b, from, to) / (method == POWER_BINARY ? 10 : 100);
    }
}
