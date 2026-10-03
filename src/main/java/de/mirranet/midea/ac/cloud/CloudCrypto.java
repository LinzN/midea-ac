package de.mirranet.midea.ac.cloud;

import de.mirranet.midea.ac.protocol.Hex;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Hashes and ids the cloud APIs expect. Ported from {@code midealocal.security.CloudSecurity}. */
public final class CloudCrypto {

    private CloudCrypto() {
    }

    static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        return Hex.encode(digest("SHA-256", s.getBytes(StandardCharsets.UTF_8)));
    }

    static String md5Hex(String s) {
        return Hex.encode(digest("MD5", s.getBytes(StandardCharsets.UTF_8)));
    }

    static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return Hex.encode(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // The cloud wants a stable id for the "phone". midea-local derives it from the account name.
    static String clientDeviceId(String account) {
        return sha256Hex("Hello, " + account + "!").substring(0, 16);
    }

    /**
     * The id the cloud files a device's token under: SHA-256 of the appliance id bytes, with the
     * two halves XORed together. Which byte order a device uses isn't known up front, so
     * {@link MideaCloud#getTokens(long)} asks for methods 1 and 2.
     *
     * @param method 0 reversed big endian (8 bytes), 1 big endian (6 bytes), 2 little endian (6 bytes)
     * @return 32 hex characters
     */
    public static String udpId(long applianceId, int method) {
        byte[] id;
        switch (method) {
            case 0 -> {
                id = new byte[8];
                for (int i = 0; i < 8; i++) {
                    id[i] = (byte) (applianceId >>> (8 * i));
                }
            }
            case 1 -> {
                id = new byte[6];
                for (int i = 0; i < 6; i++) {
                    id[i] = (byte) (applianceId >>> (8 * (5 - i)));
                }
            }
            case 2 -> {
                id = new byte[6];
                for (int i = 0; i < 6; i++) {
                    id[i] = (byte) (applianceId >>> (8 * i));
                }
            }
            default -> throw new IllegalArgumentException("Unknown udp id method " + method);
        }
        byte[] h = digest("SHA-256", id);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (h[i] ^ h[i + 16]);
        }
        return Hex.encode(out);
    }
}
