package de.mirranet.midea.ac;

/**
 * Token and key of a V3 device, as hex strings (128 and 64 characters).
 *
 * <p>Fetch them once with {@link de.mirranet.midea.ac.cloud.KeyResolver} and store them. They stay
 * valid until the unit is reset or paired again. {@link #toString()} only shows the first few
 * characters so the record can end up in logs without leaking the secret.
 */
public record DeviceKey(String token, String key) {

    public DeviceKey {
        if (token == null || key == null) {
            throw new IllegalArgumentException("token and key required");
        }
        token = token.toLowerCase();
        key = key.toLowerCase();
    }

    @Override
    public String toString() {
        return "DeviceKey[token=" + token.substring(0, Math.min(6, token.length())) + "..., key="
                + key.substring(0, Math.min(6, key.length())) + "...]";
    }
}
