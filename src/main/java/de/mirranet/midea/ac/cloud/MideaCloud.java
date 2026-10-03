package de.mirranet.midea.ac.cloud;

import de.mirranet.midea.ac.DeviceKey;

import java.io.IOException;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Just enough of the Midea cloud to get a unit's token and key. Nothing here is needed for
 * day-to-day control.
 *
 * <pre>{@code
 * MideaCloud cloud = MideaCloud.msmartHome("you@example.com", "secret");
 * cloud.login();
 * List<DeviceKey> candidates = cloud.getTokens(deviceId);
 * }</pre>
 *
 * Usually you don't call this directly but hand it to {@link KeyResolver}, which also tests the
 * candidates against the unit.
 */
public abstract class MideaCloud {

    private static final Logger LOG = Logger.getLogger(MideaCloud.class.getName());
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RETRIES = 3;
    private static final int TRANSIENT_ERROR = 9999;

    /** Fallback key from midea-local that some V3 modules accept. {@link KeyResolver} tries it last. */
    public static final DeviceKey DEFAULT_KEY = new DeviceKey(
            "ee755a84a115703768bcc7c6c13d3d629aa416f1e2fd798beb9f78cbb1381d09"
                    + "1cc245d7b063aad2a900e5b498fbd936c811f5d504b2e656d4f33b3bbc6d1da3",
            "ed37bd31558a4b039aaf4e7a7a59aa7a75fd9101682045f69baf45d28380ae5c");

    private static final BigInteger[] PRESET_ACCOUNT = {
            new BigInteger("39182118275972017797890111985649342047468653967530949796945843010512"),
            new BigInteger("39182118275980892824833804202177448991093361348247890162501600564413"),
            new BigInteger("39182118275972017797890111985649342050088014265865102175083010656997")};

    protected final String account;
    protected final String password;
    protected final String appId;
    protected final String appKey;
    protected final String clientDeviceId;
    protected String apiUrl;
    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    protected String loginId;

    protected MideaCloud(String account, String password, String appId, String appKey, String apiUrl) {
        this.account = account;
        this.password = password;
        this.appId = appId;
        this.appKey = appKey;
        this.apiUrl = apiUrl;
        this.clientDeviceId = CloudCrypto.clientDeviceId(account);
    }


    /** Account of the MSmartHome app, which most current units in Europe use. */
    public static MideaCloud msmartHome(String account, String password) {
        return new MSmartHomeCloud(account, password);
    }

    /** Account of the NetHome Plus app. */
    public static MideaCloud netHomePlus(String account, String password) {
        return new MideaAirCloud(account, password, "1017", "3742e9e5842d4ad59c2db887e12449f9");
    }

    /** Account of the Midea Air app. */
    public static MideaCloud mideaAir(String account, String password) {
        return new MideaAirCloud(account, password, "1117", "ff0cf6f5f0c3471de36341cab3f7a9af");
    }

    /**
     * The shared NetHome Plus account that Home Assistant uses by default. The token lookup isn't
     * tied to the account that owns the unit, so this often works without your own login.
     */
    public static MideaCloud presetAccount() {
        String user = decode(PRESET_ACCOUNT[0].xor(PRESET_ACCOUNT[1]));
        String pass = decode(PRESET_ACCOUNT[0].xor(PRESET_ACCOUNT[2]));
        return netHomePlus(user, pass);
    }

    private static String decode(BigInteger v) {
        return new String(de.mirranet.midea.ac.protocol.Hex.decode(v.toString(16)), StandardCharsets.UTF_8);
    }


    public abstract boolean login() throws IOException;

    /** One API call. Returns the payload object of the answer, throws on any error code. */
    protected abstract Map<String, Object> request(String endpoint, Map<String, Object> data) throws IOException;

    protected abstract Map<String, Object> generalData();

    /**
     * Asks for token/key pairs for a unit. Call {@link #login()} first.
     *
     * <p>Two lookups are made, one per byte order of the id (see {@link CloudCrypto#udpId}). The
     * result can hold zero, one or two pairs; only a handshake shows which one is right.
     *
     * @param applianceId the device id from discovery
     */
    public List<DeviceKey> getTokens(long applianceId) throws IOException {
        List<DeviceKey> result = new ArrayList<>();
        for (int method = 1; method <= 2; method++) {
            String udpId = CloudCrypto.udpId(applianceId, method);
            Map<String, Object> data = generalData();
            data.put("udpid", udpId);
            data.put("applianceCodes", String.valueOf(applianceId));
            Map<String, Object> resp = request("/v1/iot/secure/getToken", data);
            Object list = resp == null ? null : resp.get("tokenlist");
            if (list instanceof List<?> tokens) {
                for (Object o : tokens) {
                    if (o instanceof Map<?, ?> t && udpId.equalsIgnoreCase(String.valueOf(t.get("udpId")))) {
                        DeviceKey k = new DeviceKey(String.valueOf(t.get("token")), String.valueOf(t.get("key")));
                        if (!result.contains(k)) {
                            result.add(k);
                        }
                    }
                }
            }
        }
        LOG.fine(() -> "Cloud returned " + result.size() + " key candidate(s) for " + applianceId);
        return result;
    }

    /** Units registered to this account. Handy when discovery doesn't work and you need the id. */
    public List<CloudAppliance> listAppliances() throws IOException {
        Map<String, Object> resp = request("/v1/appliance/user/list/get", generalData());
        List<CloudAppliance> out = new ArrayList<>();
        Object list = resp == null ? null : resp.get("list");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> a) {
                    int type;
                    try {
                        type = Integer.parseInt(String.valueOf(a.get("type")).replace("0x", ""), 16);
                    } catch (NumberFormatException e) {
                        type = 0;
                    }
                    out.add(new CloudAppliance(Long.parseLong(String.valueOf(a.get("id"))),
                            String.valueOf(a.get("name")), type, "1".equals(String.valueOf(a.get("onlineStatus")))));
                }
            }
        }
        return out;
    }

    /** One appliance from {@link #listAppliances()}. */
    public record CloudAppliance(long id, String name, int type, boolean online) {
    }


    protected static String stamp() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
    }

    protected static String reqId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return de.mirranet.midea.ac.protocol.Hex.encode(b);
    }

    protected static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    // Up to three attempts. Network errors and code 9999 ("system error", which the cloud returns
    // now and then for valid requests) are retried; any other error code is final.
    protected Map<String, Object> withRetry(IoCall call) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                return call.run();
            } catch (MideaCloudException e) {
                if (e.code() != TRANSIENT_ERROR) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
            try {
                Thread.sleep(1000L * (attempt + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", ie);
            }
        }
        throw last;
    }

    @FunctionalInterface
    protected interface IoCall {
        Map<String, Object> run() throws IOException;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    protected static int asInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
