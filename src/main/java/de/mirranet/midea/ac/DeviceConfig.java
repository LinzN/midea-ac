package de.mirranet.midea.ac;

import de.mirranet.midea.ac.protocol.Hex;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything needed to reach one unit. Immutable; build it with {@link #builder()}.
 *
 * <pre>{@code
 * DeviceConfig cfg = DeviceConfig.builder()
 *         .host("192.168.1.50")
 *         .deviceId(151732605161920L)
 *         .token(System.getenv("MIDEA_TOKEN"))
 *         .key(System.getenv("MIDEA_KEY"))
 *         .build();
 * }</pre>
 */
public final class DeviceConfig {

    public static final int DEFAULT_PORT = 6444;

    private final String host;
    private final int port;
    private final long deviceId;
    private final ProtocolVersion protocol;
    private final byte[] token;
    private final byte[] key;
    private final boolean promptTone;
    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final int powerAnalysisMethod;
    private final Double minTemperature;
    private final Double maxTemperature;

    private DeviceConfig(Builder b) {
        this.host = Objects.requireNonNull(b.host, "host");
        this.port = b.port;
        this.deviceId = b.deviceId;
        this.protocol = b.protocol;
        this.token = b.token;
        this.key = b.key;
        this.promptTone = b.promptTone;
        this.connectTimeout = b.connectTimeout;
        this.responseTimeout = b.responseTimeout;
        this.powerAnalysisMethod = b.powerAnalysisMethod;
        this.minTemperature = b.minTemperature;
        this.maxTemperature = b.maxTemperature;
        if (protocol == ProtocolVersion.V1) {
            throw new IllegalArgumentException("Protocol V1 devices are not supported");
        }
        if (protocol == ProtocolVersion.V3 && (token == null || key == null)) {
            throw new IllegalArgumentException("Protocol V3 requires token and key");
        }
        if (deviceId == 0) {
            throw new IllegalArgumentException("deviceId is required");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder with host, port, id and protocol taken from a discovery result. */
    public static Builder builder(DeviceInfo info) {
        return new Builder().host(info.ipAddress()).port(info.port()).deviceId(info.deviceId())
                .protocol(info.protocol());
    }

    public String host() { return host; }
    public int port() { return port; }
    public long deviceId() { return deviceId; }
    public ProtocolVersion protocol() { return protocol; }
    public byte[] token() { return token == null ? null : token.clone(); }
    public byte[] key() { return key == null ? null : key.clone(); }
    public boolean promptTone() { return promptTone; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration responseTimeout() { return responseTimeout; }
    public int powerAnalysisMethod() { return powerAnalysisMethod; }
    public Double minTemperature() { return minTemperature; }
    public Double maxTemperature() { return maxTemperature; }

    /**
     * Builder for {@link DeviceConfig}. {@code host} and {@code deviceId} are required, and for V3
     * (the default) token and key as well. {@link #build()} checks this.
     */
    public static final class Builder {
        private String host;
        private int port = DEFAULT_PORT;
        private long deviceId;
        private ProtocolVersion protocol = ProtocolVersion.V3;
        private byte[] token;
        private byte[] key;
        private boolean promptTone = true;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(5);
        private int powerAnalysisMethod = AcResponseParser.POWER_BCD;
        private Double minTemperature;
        private Double maxTemperature;

        public Builder host(String host) { this.host = host; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder deviceId(long deviceId) { this.deviceId = deviceId; return this; }
        public Builder protocol(ProtocolVersion protocol) { this.protocol = protocol; return this; }

        /** Token as hex. {@code null} is accepted so you can pass an unset env variable for V2 units. */
        public Builder token(String hex) { this.token = hex == null ? null : Hex.decode(hex); return this; }

        /** Key as hex. */
        public Builder key(String hex) { this.key = hex == null ? null : Hex.decode(hex); return this; }

        public Builder credentials(DeviceKey k) {
            return token(k.token()).key(k.key());
        }

        /** Whether the unit beeps when it receives a command. Default true, same as the remote. */
        public Builder promptTone(boolean beep) { this.promptTone = beep; return this; }

        /** TCP connect including the V3 handshake. Default 10 s. */
        public Builder connectTimeout(Duration d) { this.connectTimeout = d; return this; }

        /**
         * How long to wait for the answer to one query or command. Default 5 s. Unsupported
         * optional queries cost twice this on the first refresh, so don't set it much higher.
         */
        public Builder responseTimeout(Duration d) { this.responseTimeout = d; return this; }

        /**
         * How the unit encodes energy figures. 1 BCD (default), 2 binary with 0.1 kWh steps,
         * 3 mixed, 12 binary, 101 BCD energy with binary power. Same values as the
         * {@code power_analysis_method} option in Home Assistant. If the kWh numbers look absurd,
         * try another one.
         */
        public Builder powerAnalysisMethod(int method) { this.powerAnalysisMethod = method; return this; }

        /** Overrides the set point range the unit reports. Either value may be {@code null}. */
        public Builder temperatureRange(Double min, Double max) {
            this.minTemperature = min;
            this.maxTemperature = max;
            return this;
        }

        /** @throws IllegalArgumentException if a required value is missing or V1 was selected */
        public DeviceConfig build() {
            return new DeviceConfig(this);
        }
    }
}
