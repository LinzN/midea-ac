package de.mirranet.midea.ac;

import java.time.Instant;

/**
 * State of a unit at one point in time.
 *
 * <p>The client only hands out copies, so a snapshot never changes after you got it. Getters with a
 * boxed return type ({@code Double}, {@code Integer}, {@code Boolean}) return {@code null} until the
 * unit has reported that value; many units never report energy or service data at all.
 * Temperatures are always Celsius.
 */
public final class AcState {

    // 0xC0 status / 0x40 set
    boolean power;
    int modeRaw;
    double targetTemperature = 24.0;
    int fanSpeedRaw = 102;
    boolean swingVertical;
    boolean swingHorizontal;
    boolean boost;
    boolean powerSaving;
    boolean smartEye;
    boolean dry;
    boolean auxHeating;
    boolean eco;
    boolean sleep;
    boolean naturalWind;
    boolean fahrenheit;
    boolean frostProtect;
    boolean comfort;
    boolean anion;
    boolean screenDisplay;
    boolean fullDust;
    Double indoorTemperature;
    Double outdoorTemperature;
    Integer indoorHumidity;
    Double pmv;

    // 0xB0 / 0xB1
    boolean screenDisplayAlternate;
    boolean indirectWind;
    boolean breezeless;
    Integer windLrAngle;
    Integer windUdAngle;
    Integer rateSelect;
    boolean outSilent;
    boolean sound = true;
    boolean selfClean;
    int errorCode;
    // which tag the fresh air module answers on: 0 none, 1 = 0x0233, 2 = 0x004B
    int freshAirVersion;
    boolean freshAirPower;
    int freshAirFanSpeed;

    // from DeviceConfig if set, otherwise from 0xB5
    Double minTemperature;
    Double maxTemperature;

    // 0xC1 groups 0x44 (energy) and 0x40 (runtime)
    Double totalEnergyConsumption;
    Double totalOperatingConsumption;
    Double currentEnergyConsumption;
    Double realtimePower;
    Double electrifyTime;
    Double totalOperatingTime;
    Double currentOperatingTime;

    // 0xC1 groups 0x41, 0x42, 0x43, 0x47 (service data)
    Integer compressorFrequency;
    Integer targetCompressorFrequency;
    Integer compressorCurrent;
    Integer compressorVoltage;
    Double indoorAmbientTemperature;
    Double indoorCoilTemperature;
    Double outdoorCoilTemperature;
    Double outdoorAmbientTemperature;
    Integer dischargePipeTemperature;
    Integer indoorFanSpeed;
    Integer targetIndoorFanSpeed;
    Boolean waterPumpRunning;
    Integer outdoorFanSpeed;
    Integer compressorPower;

    boolean available;
    Instant lastUpdate;

    AcState copy() {
        AcState c = new AcState();
        c.power = power;
        c.modeRaw = modeRaw;
        c.targetTemperature = targetTemperature;
        c.fanSpeedRaw = fanSpeedRaw;
        c.swingVertical = swingVertical;
        c.swingHorizontal = swingHorizontal;
        c.boost = boost;
        c.powerSaving = powerSaving;
        c.smartEye = smartEye;
        c.dry = dry;
        c.auxHeating = auxHeating;
        c.eco = eco;
        c.sleep = sleep;
        c.naturalWind = naturalWind;
        c.fahrenheit = fahrenheit;
        c.frostProtect = frostProtect;
        c.comfort = comfort;
        c.anion = anion;
        c.screenDisplay = screenDisplay;
        c.fullDust = fullDust;
        c.indoorTemperature = indoorTemperature;
        c.outdoorTemperature = outdoorTemperature;
        c.indoorHumidity = indoorHumidity;
        c.pmv = pmv;
        c.screenDisplayAlternate = screenDisplayAlternate;
        c.indirectWind = indirectWind;
        c.breezeless = breezeless;
        c.windLrAngle = windLrAngle;
        c.windUdAngle = windUdAngle;
        c.rateSelect = rateSelect;
        c.outSilent = outSilent;
        c.sound = sound;
        c.selfClean = selfClean;
        c.errorCode = errorCode;
        c.freshAirVersion = freshAirVersion;
        c.freshAirPower = freshAirPower;
        c.freshAirFanSpeed = freshAirFanSpeed;
        c.minTemperature = minTemperature;
        c.maxTemperature = maxTemperature;
        c.totalEnergyConsumption = totalEnergyConsumption;
        c.totalOperatingConsumption = totalOperatingConsumption;
        c.currentEnergyConsumption = currentEnergyConsumption;
        c.realtimePower = realtimePower;
        c.electrifyTime = electrifyTime;
        c.totalOperatingTime = totalOperatingTime;
        c.currentOperatingTime = currentOperatingTime;
        c.compressorFrequency = compressorFrequency;
        c.targetCompressorFrequency = targetCompressorFrequency;
        c.compressorCurrent = compressorCurrent;
        c.compressorVoltage = compressorVoltage;
        c.indoorAmbientTemperature = indoorAmbientTemperature;
        c.indoorCoilTemperature = indoorCoilTemperature;
        c.outdoorCoilTemperature = outdoorCoilTemperature;
        c.outdoorAmbientTemperature = outdoorAmbientTemperature;
        c.dischargePipeTemperature = dischargePipeTemperature;
        c.indoorFanSpeed = indoorFanSpeed;
        c.targetIndoorFanSpeed = targetIndoorFanSpeed;
        c.waterPumpRunning = waterPumpRunning;
        c.outdoorFanSpeed = outdoorFanSpeed;
        c.compressorPower = compressorPower;
        c.available = available;
        c.lastUpdate = lastUpdate;
        return c;
    }

    public boolean isPower() {
        return power;
    }

    /** Selected mode. Units keep it while switched off. {@code null} if unknown. */
    public OperatingMode getMode() {
        return OperatingMode.of(modeRaw);
    }

    /** Like {@link #getMode()}, but {@code null} while the unit is off. Handy for UIs. */
    public OperatingMode getActiveMode() {
        return power ? getMode() : null;
    }

    public double getTargetTemperature() {
        return targetTemperature;
    }

    public FanSpeed getFanSpeed() {
        return FanSpeed.fromRaw(fanSpeedRaw);
    }

    /** Fan speed as reported, 1 to 100 or 102 for auto. Useful on units with a stepless fan. */
    public int getFanSpeedRaw() {
        return fanSpeedRaw;
    }

    public SwingMode getSwingMode() {
        return SwingMode.of(swingVertical, swingHorizontal);
    }

    /**
     * Active preset. The unit shouldn't report more than one, but if it does, the first in the
     * order comfort, eco, boost, sleep, away wins.
     */
    public Preset getPreset() {
        if (comfort) {
            return Preset.COMFORT;
        }
        if (eco) {
            return Preset.ECO;
        }
        if (boost) {
            return Preset.BOOST;
        }
        if (sleep) {
            return Preset.SLEEP;
        }
        if (frostProtect) {
            return Preset.AWAY;
        }
        return Preset.NONE;
    }

    /** Fixed left/right position, {@code null} if not reported or not one of the presets. */
    public Vane.Horizontal getHorizontalVane() {
        return windLrAngle == null ? null : Vane.Horizontal.of(windLrAngle);
    }

    /** Fixed up/down position, {@code null} if not reported or not one of the presets. */
    public Vane.Vertical getVerticalVane() {
        return windUdAngle == null ? null : Vane.Vertical.of(windUdAngle);
    }

    public boolean isSwingVertical() { return swingVertical; }
    public boolean isSwingHorizontal() { return swingHorizontal; }
    public boolean isBoost() { return boost; }
    public boolean isPowerSaving() { return powerSaving; }
    /** Presence sensor ("smart eye"). */
    public boolean isSmartEye() { return smartEye; }
    /** Dries the evaporator after switch-off against mould. Called "dry" in midea-local. */
    public boolean isDry() { return dry; }
    /** Electric auxiliary heater (PTC). */
    public boolean isAuxHeating() { return auxHeating; }
    public boolean isEco() { return eco; }
    public boolean isSleep() { return sleep; }
    public boolean isNaturalWind() { return naturalWind; }
    /** The unit's display shows Fahrenheit. Values returned here are still Celsius. */
    public boolean isFahrenheit() { return fahrenheit; }
    public boolean isFrostProtect() { return frostProtect; }
    public boolean isComfort() { return comfort; }
    /** Ioniser. */
    public boolean isAnion() { return anion; }
    public boolean isScreenDisplay() { return screenDisplay; }
    /** Display state on units that report it through the new protocol instead of 0xC0. */
    public boolean isScreenDisplayAlternate() { return screenDisplayAlternate; }
    /** The filter needs cleaning. */
    public boolean isFullDust() { return fullDust; }
    public Double getIndoorTemperature() { return indoorTemperature; }
    public Double getOutdoorTemperature() { return outdoorTemperature; }
    public Integer getIndoorHumidity() { return indoorHumidity; }
    /** Predicted mean vote, the comfort index some units compute (-3.5 to +4). */
    public Double getPmv() { return pmv; }
    public boolean isIndirectWind() { return indirectWind; }
    public boolean isBreezeless() { return breezeless; }
    /** Power limit gear, see {@link MideaAirConditioner#setRateSelect(int)}. */
    public Integer getRateSelect() { return rateSelect; }
    public boolean isOutSilent() { return outSilent; }
    /** Buzzer of the indoor unit. */
    public boolean isSound() { return sound; }
    public boolean isSelfClean() { return selfClean; }
    /** Error code from the unit, 0 if none. Only filled on units that send it unasked. */
    public int getErrorCode() { return errorCode; }
    /** True if the unit has reported a fresh air module. */
    public boolean hasFreshAir() { return freshAirVersion != 0; }
    public boolean isFreshAirPower() { return freshAirPower; }
    public int getFreshAirFanSpeed() { return freshAirFanSpeed; }
    /** Lowest allowed set point for the current mode. */
    public Double getMinTemperature() { return minTemperature; }
    /** Highest allowed set point for the current mode. */
    public Double getMaxTemperature() { return maxTemperature; }

    /** Lifetime consumption in kWh. */
    public Double getTotalEnergyConsumption() { return totalEnergyConsumption; }
    /** Consumption while running, in kWh. Midea's own name; the difference to the total is unclear. */
    public Double getTotalOperatingConsumption() { return totalOperatingConsumption; }
    /** Consumption of the current run, in kWh. */
    public Double getCurrentEnergyConsumption() { return currentEnergyConsumption; }
    /** Current power draw in W. */
    public Double getRealtimePower() { return realtimePower; }
    /** Hours since the unit got mains power. */
    public Double getElectrifyTime() { return electrifyTime; }
    /** Lifetime runtime in hours. */
    public Double getTotalOperatingTime() { return totalOperatingTime; }
    /** Runtime since last switched on, in hours. */
    public Double getCurrentOperatingTime() { return currentOperatingTime; }

    /** Compressor frequency in Hz. */
    public Integer getCompressorFrequency() { return compressorFrequency; }
    public Integer getTargetCompressorFrequency() { return targetCompressorFrequency; }
    /** Raw value, unit not documented. */
    public Integer getCompressorCurrent() { return compressorCurrent; }
    /** Raw value, unit not documented. */
    public Integer getCompressorVoltage() { return compressorVoltage; }
    /** T1, indoor return air. */
    public Double getIndoorAmbientTemperature() { return indoorAmbientTemperature; }
    /** T2, indoor coil. */
    public Double getIndoorCoilTemperature() { return indoorCoilTemperature; }
    /** T3, outdoor coil. */
    public Double getOutdoorCoilTemperature() { return outdoorCoilTemperature; }
    /** T4, outdoor air sensor. */
    public Double getOutdoorAmbientTemperature() { return outdoorAmbientTemperature; }
    /** TP, compressor discharge pipe. */
    public Integer getDischargePipeTemperature() { return dischargePipeTemperature; }
    /** Indoor fan in RPM. */
    public Integer getIndoorFanSpeed() { return indoorFanSpeed; }
    public Integer getTargetIndoorFanSpeed() { return targetIndoorFanSpeed; }
    /** Condensate pump running. May also just be the float switch that starts it. */
    public Boolean getWaterPumpRunning() { return waterPumpRunning; }
    /** Outdoor fan in RPM. */
    public Integer getOutdoorFanSpeed() { return outdoorFanSpeed; }
    /** Compressor power in W. */
    public Integer getCompressorPower() { return compressorPower; }

    /** False after a refresh failed. Set back to true by the next successful one. */
    public boolean isAvailable() { return available; }
    /** Time of the last successful refresh, {@code null} before the first. */
    public Instant getLastUpdate() { return lastUpdate; }

    @Override
    public String toString() {
        return "AcState{power=" + power
                + ", mode=" + getMode()
                + ", target=" + targetTemperature
                + ", indoor=" + indoorTemperature
                + ", outdoor=" + outdoorTemperature
                + ", humidity=" + indoorHumidity
                + ", fan=" + getFanSpeed() + "(" + fanSpeedRaw + ")"
                + ", swing=" + getSwingMode()
                + ", preset=" + getPreset()
                + ", display=" + screenDisplay
                + (realtimePower != null ? ", power=" + realtimePower + "W" : "")
                + (totalEnergyConsumption != null ? ", energy=" + totalEnergyConsumption + "kWh" : "")
                + (errorCode != 0 ? ", error=" + errorCode : "")
                + ", available=" + available
                + '}';
    }
}
