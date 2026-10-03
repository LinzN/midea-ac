package de.mirranet.midea.ac.cloud;

import de.mirranet.midea.ac.protocol.Hex;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MSmartHome account. Port of {@code SmartHomeCloud} and {@code MSmartCloudSecurity}.
 *
 * <p>JSON over HTTPS, signed with HMAC-SHA256 over iot key + body + a timestamp. Before logging in
 * the client asks which regional server the account lives on.
 */
final class MSmartHomeCloud extends MideaCloud {

    private static final Logger LOG = Logger.getLogger(MSmartHomeCloud.class.getName());

    static final String APP_ID = "1010";
    static final String APP_KEY = "ac21b9f9cbfe4ca5a88562ef25e2b768";
    static final String IOT_KEY = new String(Hex.decode(new BigInteger("7882822598523843940").toString(16)),
            StandardCharsets.US_ASCII);
    static final String HMAC_KEY = new String(Hex.decode(
            new BigInteger("117390035944627627450677220413733956185864939010425").toString(16)),
            StandardCharsets.US_ASCII);

    private final String authBase;
    private String uid;
    private String accessToken;

    MSmartHomeCloud(String account, String password) {
        super(account, password, APP_ID, APP_KEY, "https://mp-prod.appsmb.com/mas/v5/app/proxy?alias=");
        authBase = Base64.getEncoder().encodeToString((APP_KEY + ":" + IOT_KEY).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    protected Map<String, Object> generalData() {
        Map<String, Object> d = map();
        d.put("src", appId);
        d.put("format", "2");
        d.put("stamp", stamp());
        d.put("platformId", "1");
        d.put("deviceId", clientDeviceId);
        d.put("reqId", reqId());
        d.put("uid", uid);
        d.put("clientType", "1");
        d.put("appId", appId);
        d.put("language", "en_US");
        return d;
    }

    static String sign(String body, String random) {
        return CloudCrypto.hmacSha256Hex(HMAC_KEY, IOT_KEY + body + random);
    }

    static String encryptPassword(String loginId, String password) {
        return CloudCrypto.sha256Hex(loginId + CloudCrypto.sha256Hex(password) + APP_KEY);
    }

    // MSmartHome wants the password twice, hashed in two different ways.
    static String encryptIamPassword(String loginId, String password) {
        String md = CloudCrypto.md5Hex(CloudCrypto.md5Hex(password));
        return CloudCrypto.sha256Hex(loginId + md + APP_KEY);
    }

    @Override
    protected Map<String, Object> request(String endpoint, Map<String, Object> data) throws IOException {
        return withRetry(() -> doRequest(endpoint, data));
    }

    private Map<String, Object> doRequest(String endpoint, Map<String, Object> data) throws IOException {
        Map<String, Object> d = new LinkedHashMap<>(data);
        d.putIfAbsent("reqId", reqId());
        d.putIfAbsent("stamp", stamp());
        String body = MiniJson.write(d);
        String random = String.valueOf(System.currentTimeMillis() / 1000);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(apiUrl + endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("secretVersion", "1")
                .header("sign", sign(body, random))
                .header("random", random)
                .header("x-recipe-app", appId)
                .header("Authorization", "Basic " + authBase)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (uid != null) {
            rb.header("uid", uid);
        }
        if (accessToken != null) {
            rb.header("accessToken", accessToken);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        Map<String, Object> json = MiniJson.parseObject(resp.body());
        int code = asInt(json.get("code"), -1);
        if (code != 0) {
            throw new MideaCloudException(code, String.valueOf(json.getOrDefault("msg", "")));
        }
        return asMap(json.get("data"));
    }

    // Accounts live on regional servers. If the lookup fails we stay on the default one.
    private void reRoute() {
        try {
            Map<String, Object> d = generalData();
            d.put("userType", "0");
            d.put("userName", account);
            Map<String, Object> resp = request("/v1/multicloud/platform/user/route", d);
            if (resp != null && resp.get("masUrl") instanceof String url && !url.isEmpty()) {
                apiUrl = url;
            }
        } catch (IOException e) {
            LOG.fine(() -> "Re-route failed, using default endpoint: " + e.getMessage());
        }
    }

    @Override
    public boolean login() throws IOException {
        reRoute();
        Map<String, Object> d = generalData();
        d.put("loginAccount", account);
        Map<String, Object> idResp = request("/v1/user/login/id/get", d);
        if (idResp == null || idResp.get("loginId") == null) {
            return false;
        }
        loginId = String.valueOf(idResp.get("loginId"));
        String stamp = stamp();
        Map<String, Object> iot = generalData();
        iot.remove("uid");
        iot.put("iampwd", encryptIamPassword(loginId, password));
        iot.put("loginAccount", account);
        iot.put("password", encryptPassword(loginId, password));
        iot.put("stamp", stamp);
        Map<String, Object> inner = map();
        inner.put("appKey", appKey);
        inner.put("deviceId", clientDeviceId);
        inner.put("platform", "2");
        Map<String, Object> req = map();
        req.put("iotData", iot);
        req.put("data", inner);
        req.put("stamp", stamp);
        Map<String, Object> resp = request("/mj/user/login", req);
        if (resp == null) {
            return false;
        }
        uid = String.valueOf(resp.get("uid"));
        Map<String, Object> mdata = asMap(resp.get("mdata"));
        accessToken = mdata != null ? String.valueOf(mdata.get("accessToken")) : null;
        return true;
    }
}
