package de.mirranet.midea.ac.discovery;

import de.mirranet.midea.ac.DeviceInfo;
import de.mirranet.midea.ac.ProtocolVersion;
import de.mirranet.midea.ac.protocol.Hex;
import de.mirranet.midea.ac.protocol.LocalSecurity;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Finds Midea units on the LAN. Port of {@code midealocal.discover}.
 *
 * <p>Sends a fixed probe to UDP ports 6445 and 20086. Units answer with their id, model, serial
 * and TCP port, encrypted with the same static key as the frames. V3 units wrap the answer in an
 * 0x8370 header; that's how the protocol version is detected.
 *
 * <p>Broadcasts don't cross VLANs or routers, and some access points filter them. In that case
 * ask the unit directly with {@link #discover(String, Duration)}.
 */
public final class MideaDiscovery {

    private static final Logger LOG = Logger.getLogger(MideaDiscovery.class.getName());

    // 8 byte header, 32 zero bytes, 32 byte fixed payload: 72 bytes, matching the 0x48 length field
    private static final byte[] BROADCAST_MSG = concat(
            Hex.decode("5a5a011148009200"),
            new byte[32],
            Hex.decode("7f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5"));
    private static final int[] PORTS = {6445, 20086};
    private static final int MIN_RESPONSE_LENGTH = 104;

    private MideaDiscovery() {
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /**
     * Broadcasts on every IPv4 interface and collects air conditioners until the timeout.
     *
     * @param timeout how long to listen; 3 to 5 seconds is plenty
     */
    public static List<DeviceInfo> discover(Duration timeout) throws IOException {
        return discover(timeout, null, true);
    }

    /**
     * Asks one address directly. Works across VLANs as long as UDP gets through.
     *
     * @return the unit, or {@code null} if it didn't answer
     */
    public static DeviceInfo discover(String ipAddress, Duration timeout) throws IOException {
        List<DeviceInfo> found = discover(timeout, ipAddress, false);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * @param target an address to ask, or {@code null} to broadcast
     * @param airConditioners false to also return dehumidifiers, fans and the rest
     */
    public static List<DeviceInfo> discover(Duration timeout, String target, boolean airConditioners)
            throws IOException {
        Map<Long, DeviceInfo> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            Set<InetAddress> targets = target != null
                    ? Set.of(InetAddress.getByName(target)) : broadcastAddresses();
            for (InetAddress addr : targets) {
                for (int port : PORTS) {
                    try {
                        socket.send(new DatagramPacket(BROADCAST_MSG, BROADCAST_MSG.length, addr, port));
                    } catch (IOException e) {
                        LOG.fine(() -> "Cannot send discovery to " + addr + ": " + e.getMessage());
                    }
                }
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            byte[] buf = new byte[1024];
            while (true) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000;
                if (remaining <= 0) {
                    break;
                }
                socket.setSoTimeout((int) remaining);
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                DeviceInfo info = parse(Arrays.copyOf(p.getData(), p.getLength()), p.getAddress().getHostAddress());
                if (info != null && !found.containsKey(info.deviceId())
                        && (!airConditioners || info.isAirConditioner())) {
                    found.put(info.deviceId(), info);
                    LOG.fine(() -> "Found " + info);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    static Set<InetAddress> broadcastAddresses() throws IOException {
        Set<InetAddress> result = new LinkedHashSet<>();
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) {
                continue;
            }
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) {
                    result.add(ia.getBroadcast());
                }
            }
        }
        result.add(InetAddress.getByName("255.255.255.255"));
        return result;
    }

    // Returns null for anything that isn't a V2/V3 answer we can decrypt.
    static DeviceInfo parse(byte[] data, String ip) {
        if (data.length < MIN_RESPONSE_LENGTH) {
            return null;
        }
        ProtocolVersion protocol;
        byte[] d = data;
        if ((d[0] & 0xFF) == 0x5A && (d[1] & 0xFF) == 0x5A) {
            protocol = ProtocolVersion.V2;
        } else if ((d[0] & 0xFF) == 0x83 && (d[1] & 0xFF) == 0x70 && (d[8] & 0xFF) == 0x5A && (d[9] & 0xFF) == 0x5A) {
            protocol = ProtocolVersion.V3;
            d = Arrays.copyOfRange(d, 8, d.length - 16);
        } else {
            return null; // V1 modules answer in XML; not supported
        }
        if (d.length < 56) {
            return null;
        }
        long deviceId = 0;
        for (int i = 0; i < 6; i++) {
            deviceId |= (long) (d[20 + i] & 0xFF) << (8 * i);
        }
        byte[] reply = LocalSecurity.decryptPayload(Arrays.copyOfRange(d, 40, d.length - 16));
        if (reply.length <= 41 || (reply[40] & 0xFF) + 41 > reply.length) {
            LOG.fine(() -> "Undecodable discovery reply from " + ip);
            return null;
        }
        int ssidLength = reply[40] & 0xFF;
        String ssid = new String(reply, 41, ssidLength, StandardCharsets.US_ASCII);
        String[] parts = ssid.split("_");
        int type;
        try {
            type = parts.length > 1 ? Integer.parseInt(parts[1], 16) : 0;
        } catch (NumberFormatException e) {
            type = 0;
        }
        int port = (reply[4] & 0xFF) | (reply[5] & 0xFF) << 8 | (reply[6] & 0xFF) << 16 | (reply[7] & 0xFF) << 24;
        String model = new String(reply, 17, 8, StandardCharsets.US_ASCII);
        String sn = new String(reply, 8, 32, StandardCharsets.US_ASCII);
        String mac = null;
        int macStart = 63 + ssidLength;
        if (reply.length >= macStart + 6) {
            mac = Hex.encode(Arrays.copyOfRange(reply, macStart, macStart + 6));
        } else if (sn.length() == 32) {
            mac = sn.substring(16, 28);
        }
        if (mac != null && !mac.matches("[0-9A-Fa-f]{12}")) {
            mac = null;
        }
        return new DeviceInfo(deviceId, type, ip, port, model, sn, protocol, mac);
    }
}
