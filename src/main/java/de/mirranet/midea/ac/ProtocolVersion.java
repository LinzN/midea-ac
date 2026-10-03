package de.mirranet.midea.ac;

/** Generation of the Wi-Fi module's LAN protocol, as reported by discovery. */
public enum ProtocolVersion {
    /** XML based, very old modules. Not supported. */
    V1(1),
    /** Plain 0x5A5A packets, no credentials needed. */
    V2(2),
    /** 0x8370 envelope, needs token and key. */
    V3(3);

    private final int value;

    ProtocolVersion(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /** @throws IllegalArgumentException for anything other than 1, 2 or 3 */
    public static ProtocolVersion of(int value) {
        for (ProtocolVersion v : values()) {
            if (v.value == value) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown protocol version " + value);
    }
}
