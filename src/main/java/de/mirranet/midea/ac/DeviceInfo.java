package de.mirranet.midea.ac;

/**
 * A device that answered discovery.
 *
 * @param deviceId appliance id; also what the cloud token lookup is keyed on
 * @param deviceType 0xAC for air conditioners
 * @param ipAddress address the answer came from
 * @param port TCP port for control, usually 6444
 * @param model 8 digit model code (SN8)
 * @param serialNumber serial as reported by the module
 * @param protocol V2 or V3
 * @param mac MAC address without separators, {@code null} if the module didn't report it
 */
public record DeviceInfo(long deviceId, int deviceType, String ipAddress, int port, String model,
                         String serialNumber, ProtocolVersion protocol, String mac) {

    public boolean isAirConditioner() {
        return deviceType == 0xAC;
    }

    @Override
    public String toString() {
        return String.format("DeviceInfo[id=%d, type=0x%02X, ip=%s:%d, model=%s, protocol=%s, mac=%s]",
                deviceId, deviceType, ipAddress, port, model, protocol, mac);
    }
}
