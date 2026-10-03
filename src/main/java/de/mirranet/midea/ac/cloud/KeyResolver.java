package de.mirranet.midea.ac.cloud;

import de.mirranet.midea.ac.DeviceInfo;
import de.mirranet.midea.ac.DeviceKey;
import de.mirranet.midea.ac.MideaException;
import de.mirranet.midea.ac.ProtocolVersion;
import de.mirranet.midea.ac.protocol.Connection;
import de.mirranet.midea.ac.protocol.Hex;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Finds the token and key a V3 unit accepts.
 *
 * <p>The cloud can return more than one candidate, and only the unit knows which is right.
 * So every candidate, plus the built-in default key, is tried with a real handshake.
 *
 * <pre>{@code
 * DeviceInfo dev = MideaDiscovery.discover("192.168.1.50", Duration.ofSeconds(5));
 * DeviceKey key = KeyResolver.resolve(dev, MideaCloud.presetAccount()).orElseThrow();
 * }</pre>
 */
public final class KeyResolver {

    private static final Logger LOG = Logger.getLogger(KeyResolver.class.getName());

    private KeyResolver() {
    }

    /**
     * Logs in, fetches candidates and returns the first one the unit accepts. If the cloud rejects
     * the token request, the default key is still tried.
     *
     * @param device must be reachable from here
     * @param cloud a not yet logged in client, or {@code null} to try only the default key
     * @return the working key, or empty if the unit accepted none
     * @throws IllegalArgumentException for V2 devices, which need no key
     * @throws MideaException if the cloud login fails
     */
    public static Optional<DeviceKey> resolve(DeviceInfo device, MideaCloud cloud) throws IOException {
        if (device.protocol() != ProtocolVersion.V3) {
            throw new IllegalArgumentException("Only V3 devices need a token/key");
        }
        List<DeviceKey> candidates = new ArrayList<>();
        if (cloud != null) {
            try {
                if (!cloud.login()) {
                    throw new MideaException("Cloud login failed");
                }
                candidates.addAll(cloud.getTokens(device.deviceId()));
            } catch (MideaCloudException e) {
                // the cloud sometimes refuses (Home Assistant has seen 3201); the default key might still work
                LOG.warning("Cloud token request failed: " + e.getMessage());
            }
        }
        candidates.add(MideaCloud.DEFAULT_KEY);
        for (DeviceKey k : candidates) {
            if (verify(device, k)) {
                return Optional.of(k);
            }
        }
        return Optional.empty();
    }

    /** True if the unit accepts this key. Opens a connection, does the handshake and closes it again. */
    public static boolean verify(DeviceInfo device, DeviceKey key) {
        Connection c = new Connection(device.ipAddress(), device.port(), device.deviceId(), ProtocolVersion.V3,
                Hex.decode(key.token()), Hex.decode(key.key()));
        try {
            c.open((int) Duration.ofSeconds(8).toMillis());
            return true;
        } catch (IOException e) {
            LOG.fine(() -> "Key " + key + " rejected: " + e.getMessage());
            return false;
        } finally {
            c.close();
        }
    }
}
