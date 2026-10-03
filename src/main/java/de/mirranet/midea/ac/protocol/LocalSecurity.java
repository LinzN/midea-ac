package de.mirranet.midea.ac.protocol;

import de.mirranet.midea.ac.MideaException;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto used on the LAN, ported from {@code midealocal.security.LocalSecurity}.
 *
 * <p>There are two layers:
 * <ul>
 *   <li>The inner 0xAA frame is AES-128-ECB encrypted with a key that is the same for every device,
 *       and the surrounding 0x5A5A packet ends with an MD5 over packet and salt. Both V2 and V3 use this.</li>
 *   <li>V3 additionally wraps each packet in an 0x8370 envelope: AES-256-CBC with a per-connection
 *       session key plus a SHA-256 signature. The session key comes out of the token/key handshake.</li>
 * </ul>
 *
 * <p>An instance holds the session key and request counter of one connection, so don't share it.
 */
public final class LocalSecurity {

    public static final int MSGTYPE_HANDSHAKE_REQUEST = 0x0;
    public static final int MSGTYPE_HANDSHAKE_RESPONSE = 0x1;
    public static final int MSGTYPE_ENCRYPTED_RESPONSE = 0x3;
    public static final int MSGTYPE_ENCRYPTED_REQUEST = 0x6;

    // Kept in the same numeric form as midea-local so the two are easy to compare.
    private static final byte[] AES_KEY =
            Hex.decode(new BigInteger("141661095494369103254425781617665632877").toString(16));
    private static final byte[] SALT = Hex.decode(new BigInteger(
            "233912452794221312800602098970898185176935770387238278451789080441632479840061417076563")
            .toString(16));
    private static final byte[] ZERO_IV = new byte[16];
    private static final int TCP_KEY_RESPONSE_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private byte[] tcpKey;
    private int requestCount;

    /**
     * Payloads of all complete envelopes in a buffer, plus whatever was left over
     * (an incomplete envelope that needs more bytes from the socket).
     */
    public record Decoded(List<byte[]> packets, byte[] remainder) {
    }

    /** Encrypts an 0xAA frame for the 0x5A5A packet (AES-128-ECB, PKCS#7). */
    public static byte[] encryptPayload(byte[] raw) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            return c.doFinal(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES not available", e);
        }
    }

    /**
     * Counterpart of {@link #encryptPayload}. Returns an empty array instead of throwing when the
     * data is not valid ciphertext, same as midea-local, because callers treat that as "not for us".
     */
    public static byte[] decryptPayload(byte[] raw) {
        if (raw.length == 0 || raw.length % 16 != 0) {
            return new byte[0];
        }
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"));
            return c.doFinal(raw);
        } catch (GeneralSecurityException e) {
            return new byte[0];
        }
    }

    /** MD5(data + salt). Appended as the last 16 bytes of every 0x5A5A packet. */
    public static byte[] encode32(byte[] data) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(data);
            md5.update(SALT);
            return md5.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                sha.update(p);
            }
            return sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] aesCbc(int mode, byte[] key, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(ZERO_IV));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CBC failed: " + e.getMessage(), e);
        }
    }

    /**
     * Derives the V3 session key from the device's handshake answer.
     *
     * <p>The answer is 32 bytes of AES-CBC(key, random) followed by SHA-256(random). If the
     * signature matches, the session key is {@code random XOR key}. A mismatch almost always means
     * the key belongs to a different device or the token was rejected.
     *
     * @param response the 64 bytes after the 8 byte envelope header
     * @param key the device key (32 bytes)
     * @throws MideaException.Authentication if the device said ERROR or the signature is wrong
     */
    public byte[] deriveTcpKey(byte[] response, byte[] key) throws MideaException {
        if (Arrays.equals(response, "ERROR".getBytes())) {
            throw new MideaException.Authentication("Device answered handshake with ERROR (wrong token?)");
        }
        if (response.length != TCP_KEY_RESPONSE_LENGTH) {
            throw new MideaException.Authentication("Unexpected handshake length " + response.length);
        }
        byte[] payload = Arrays.copyOfRange(response, 0, 32);
        byte[] sign = Arrays.copyOfRange(response, 32, 64);
        byte[] plain = aesCbc(Cipher.DECRYPT_MODE, key, payload);
        if (!MessageDigest.isEqual(sha256(plain), sign)) {
            throw new MideaException.Authentication("Handshake signature mismatch (wrong key?)");
        }
        byte[] derived = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            derived[i] = (byte) (plain[i] ^ key[i]);
        }
        tcpKey = derived;
        requestCount = 0;
        return derived.clone();
    }

    void setTcpKey(byte[] key) {
        tcpKey = key.clone();
        requestCount = 0;
    }

    /**
     * Wraps data in an 0x8370 envelope.
     *
     * <p>Layout: {@code 83 70 | size (BE) | 20 | padding<<4 | type | counter (BE) | data}.
     * Encrypted types pad the data with random bytes to a 16 byte boundary (counting the 2 counter
     * bytes), encrypt counter + data, and append SHA-256 over header + plaintext. The size field then
     * includes padding and signature. The odd size arithmetic is copied as is from midea-local; the
     * firmware expects exactly that.
     */
    public synchronized byte[] encode8370(byte[] data, int msgType) {
        boolean encrypted = msgType == MSGTYPE_ENCRYPTED_RESPONSE || msgType == MSGTYPE_ENCRYPTED_REQUEST;
        int size = data.length;
        int padding = 0;
        byte[] body = data;
        if (encrypted && (size + 2) % 16 != 0) {
            padding = 16 - ((size + 2) & 0xF);
            size += padding + 32;
            byte[] rnd = new byte[padding];
            RANDOM.nextBytes(rnd);
            body = concat(data, rnd);
        }
        byte[] header = {(byte) 0x83, 0x70, (byte) (size >> 8), (byte) size, 0x20,
                (byte) ((padding << 4) | msgType)};
        byte[] counted = concat(new byte[]{(byte) (requestCount >> 8), (byte) requestCount}, body);
        requestCount++;
        if (requestCount >= 0xFFFF) {
            requestCount = 0;
        }
        if (encrypted) {
            if (tcpKey == null) {
                throw new IllegalStateException("V3 session not authenticated");
            }
            byte[] sign = sha256(header, counted);
            counted = concat(aesCbc(Cipher.ENCRYPT_MODE, tcpKey, counted), sign);
        }
        return concat(header, counted);
    }

    /**
     * Splits a byte stream into envelopes, verifies and decrypts them.
     *
     * <p>TCP gives no message boundaries, so the stream can contain several envelopes or end in the
     * middle of one. The incomplete tail is returned as {@link Decoded#remainder()} and should be
     * prepended to the next read.
     *
     * @throws MideaException.Protocol on a broken header or a signature mismatch
     */
    public Decoded decode8370(byte[] input) throws MideaException {
        List<byte[]> packets = new ArrayList<>();
        byte[] data = input;
        while (true) {
            if (data.length < 6) {
                return new Decoded(packets, data);
            }
            if ((data[0] & 0xFF) != 0x83 || (data[1] & 0xFF) != 0x70) {
                throw new MideaException.Protocol("Not an 8370 message");
            }
            int size = (((data[2] & 0xFF) << 8) | (data[3] & 0xFF)) + 8;
            if (data.length < size) {
                return new Decoded(packets, data);
            }
            byte[] leftover = Arrays.copyOfRange(data, size, data.length);
            byte[] msg = Arrays.copyOfRange(data, 0, size);
            if ((msg[4] & 0xFF) != 0x20) {
                throw new MideaException.Protocol("8370 header byte 4 missing");
            }
            byte[] header = Arrays.copyOfRange(msg, 0, 6);
            int padding = (msg[5] & 0xFF) >> 4;
            int msgType = msg[5] & 0x0F;
            byte[] payload = Arrays.copyOfRange(msg, 6, msg.length);
            if (msgType == MSGTYPE_ENCRYPTED_RESPONSE || msgType == MSGTYPE_ENCRYPTED_REQUEST) {
                if (tcpKey == null) {
                    throw new MideaException.Protocol("Encrypted 8370 message without session key");
                }
                if (payload.length < 32 || (payload.length - 32) % 16 != 0) {
                    throw new MideaException.Protocol("Malformed encrypted 8370 payload");
                }
                byte[] sign = Arrays.copyOfRange(payload, payload.length - 32, payload.length);
                byte[] plain = aesCbc(Cipher.DECRYPT_MODE, tcpKey,
                        Arrays.copyOfRange(payload, 0, payload.length - 32));
                if (!MessageDigest.isEqual(sha256(header, plain), sign)) {
                    throw new MideaException.Protocol("8370 signature mismatch");
                }
                payload = padding > 0 ? Arrays.copyOfRange(plain, 0, plain.length - padding) : plain;
            }
            // first two bytes are the sender's counter, we don't track it
            if (payload.length >= 2) {
                packets.add(Arrays.copyOfRange(payload, 2, payload.length));
            }
            data = leftover;
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
