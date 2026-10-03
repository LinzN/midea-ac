package de.mirranet.midea.ac.cloud;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The older cloud behind NetHome Plus and Midea Air (mapp.appsmb.com). Port of
 * {@code MideaAirCloud} and {@code MideaAirSecurity}.
 *
 * <p>Form encoded POSTs. Each request carries a {@code sign} parameter: SHA-256 over the URL path,
 * the sorted parameters and the app key.
 */
final class MideaAirCloud extends MideaCloud {

    private String sessionId;
    private String accessToken;
    private String uid;

    MideaAirCloud(String account, String password, String appId, String appKey) {
        super(account, password, appId, appKey, "https://mapp.appsmb.com");
    }

    @Override
    protected Map<String, Object> generalData() {
        Map<String, Object> d = map();
        d.put("src", appId);
        d.put("format", "2");
        d.put("stamp", stamp());
        d.put("deviceId", clientDeviceId);
        d.put("reqId", reqId());
        d.put("clientType", "1");
        d.put("appId", appId);
        if (sessionId != null) {
            d.put("sessionId", sessionId);
        }
        return d;
    }

    // Parameters are joined unescaped for the signature, even though they're sent escaped.
    static String sign(String path, Map<String, Object> data, String appKey) {
        StringJoiner j = new StringJoiner("&");
        new TreeMap<>(data).forEach((k, v) -> j.add(k + "=" + v));
        return CloudCrypto.sha256Hex(path + j + appKey);
    }

    static String encryptPassword(String loginId, String password, String appKey) {
        return CloudCrypto.sha256Hex(loginId + CloudCrypto.sha256Hex(password) + appKey);
    }

    @Override
    protected Map<String, Object> request(String endpoint, Map<String, Object> data) throws IOException {
        return withRetry(() -> doRequest(endpoint, new java.util.LinkedHashMap<>(data)));
    }

    private Map<String, Object> doRequest(String endpoint, Map<String, Object> data) throws IOException {
        URI uri = URI.create(apiUrl + endpoint);
        data.put("sign", sign(uri.getPath(), data, appKey));
        StringJoiner form = new StringJoiner("&");
        data.forEach((k, v) -> form.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8)));
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
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
        int code = asInt(json.get("errorCode"), -1);
        if (code != 0) {
            throw new MideaCloudException(code, String.valueOf(json.getOrDefault("msg", "")));
        }
        Map<String, Object> result = asMap(json.get("result"));
        return result != null ? result : asMap(json.get("data"));
    }

    @Override
    public boolean login() throws IOException {
        Map<String, Object> d = generalData();
        d.put("loginAccount", account);
        Map<String, Object> idResp = request("/v1/user/login/id/get", d);
        if (idResp == null || idResp.get("loginId") == null) {
            return false;
        }
        loginId = String.valueOf(idResp.get("loginId"));
        Map<String, Object> l = generalData();
        l.put("loginAccount", account);
        l.put("password", encryptPassword(loginId, password, appKey));
        Map<String, Object> resp = request("/v1/user/login", l);
        if (resp == null) {
            return false;
        }
        accessToken = String.valueOf(resp.get("accessToken"));
        uid = String.valueOf(resp.get("userId"));
        sessionId = String.valueOf(resp.get("sessionId"));
        return true;
    }
}
