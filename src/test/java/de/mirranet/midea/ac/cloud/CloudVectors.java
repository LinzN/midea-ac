package de.mirranet.midea.ac.cloud;

import static de.mirranet.midea.ac.Check.eq;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cloud signing vectors from midea-local 12.1.0. */
public final class CloudVectors {

    private CloudVectors() {
    }

    public static void run() {
        eq("client device id", "56c2e4da8ebc13f7", CloudCrypto.clientDeviceId("user@example.com"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("src", "1017");
        data.put("loginAccount", "a@b.c");
        data.put("appId", "1017");
        data.put("format", "2");
        eq("NetHome Plus sign", "bd3a5fa5da072f751c96cbec7088d81f722bbaab7af7495b5bb485ed49948c9e",
                MideaAirCloud.sign("/v1/user/login/id/get", data, "3742e9e5842d4ad59c2db887e12449f9"));
        eq("NetHome Plus password", "69f891b04bf188bb3a6f20fd80b1971bcf67391c338265dd465db3a4ee29e742",
                MideaAirCloud.encryptPassword("loginid123", "secret", "3742e9e5842d4ad59c2db887e12449f9"));
        eq("MSmartHome iot key", "meicloud", MSmartHomeCloud.IOT_KEY);
        eq("MSmartHome hmac key", "PROD_VnoClJI9aikS8dyy", MSmartHomeCloud.HMAC_KEY);
        eq("MSmartHome sign", "c14bd371d3e09fafbcbac8900a25ee82bf2ce23c99259efe17a4251e6bec5aba",
                MSmartHomeCloud.sign("{\"a\": 1}", "1700000000"));
        eq("MSmartHome iam password", "9ff699f91ba2627e7f489d81cc691e06430ad6453f063d181a5722c485417f1e",
                MSmartHomeCloud.encryptIamPassword("loginid123", "secret"));
        Object parsed = MiniJson.parse("{\"errorCode\":\"0\",\"result\":{\"tokenlist\":[{\"udpId\":\"ab\",\"token\":\"T\",\"key\":\"K\"}],\"n\":-1.5,\"u\":\"\\u00e4\"}}");
        eq("json roundtrip", "{\"errorCode\": \"0\", \"result\": {\"tokenlist\": [{\"udpId\": \"ab\", \"token\": \"T\", \"key\": \"K\"}], \"n\": -1.5, \"u\": \"\\u00e4\"}}",
                MiniJson.write(parsed));
        eq("preset account cloud", MideaAirCloud.class, MideaCloud.presetAccount().getClass());
    }
}
