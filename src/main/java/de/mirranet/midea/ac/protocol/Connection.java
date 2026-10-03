package de.mirranet.midea.ac.protocol;

import de.mirranet.midea.ac.MideaException;
import de.mirranet.midea.ac.ProtocolVersion;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A TCP connection to one appliance. Takes care of the V3 handshake, wraps outgoing frames and
 * reassembles incoming ones, which can arrive split over several reads or several to a read.
 *
 * <p>Not thread safe. {@code MideaAirConditioner} serializes all access.
 */
public final class Connection implements Closeable {

    private static final Logger LOG = Logger.getLogger(Connection.class.getName());

    private final String host;
    private final int port;
    private final long deviceId;
    private final ProtocolVersion protocol;
    private final byte[] token;
    private final byte[] key;
    private final LocalSecurity security = new LocalSecurity();

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private byte[] buffer = new byte[0];

    /**
     * @param token 64 byte token, only needed for V3
     * @param key 32 byte key, only needed for V3
     */
    public Connection(String host, int port, long deviceId, ProtocolVersion protocol, byte[] token, byte[] key) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.protocol = protocol;
        this.token = token;
        this.key = key;
    }

    /**
     * Connects and, for V3, authenticates. On failure the socket is closed again.
     *
     * @throws MideaException.Authentication if the device rejects token or key
     */
    public void open(int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
            if (protocol == ProtocolVersion.V3) {
                authenticate();
            }
            LOG.fine(() -> "Connected to " + host + ":" + port + " (" + protocol + ")");
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public boolean isOpen() {
        return socket != null && !socket.isClosed();
    }

    // Sends the token, reads the 72 byte answer (8 byte envelope header + 64 bytes) and derives the
    // session key from it. A device that doesn't know the token answers with less, or just ERROR.
    private void authenticate() throws IOException {
        if (token == null || key == null) {
            throw new MideaException.Authentication("V3 device requires token and key");
        }
        out.write(security.encode8370(token, LocalSecurity.MSGTYPE_HANDSHAKE_REQUEST));
        out.flush();
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        while (resp.size() < 72) {
            int n = in.read(chunk);
            if (n < 0) {
                break;
            }
            resp.write(chunk, 0, n);
            byte[] sofar = resp.toByteArray();
            if (sofar.length >= 6 && sofar.length >= (((sofar[2] & 0xFF) << 8) | (sofar[3] & 0xFF)) + 8) {
                break;
            }
        }
        byte[] response = resp.toByteArray();
        if (response.length < 72) {
            throw new MideaException.Authentication(
                    "Handshake rejected (" + response.length + " bytes) - token/key or protocol wrong");
        }
        security.deriveTcpKey(Arrays.copyOfRange(response, 8, 72), key);
        LOG.fine("V3 authentication successful");
    }

    /** Sends a serialized 0xAA frame. */
    public void sendFrame(byte[] frame) throws IOException {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest("TX " + Hex.encode(frame));
        }
        sendPacket(Packet.build(deviceId, frame));
    }

    public void sendHeartbeat() throws IOException {
        sendPacket(Packet.heartbeat(deviceId));
    }

    private void sendPacket(byte[] packet) throws IOException {
        ensureOpen();
        byte[] data = protocol == ProtocolVersion.V3
                ? security.encode8370(packet, LocalSecurity.MSGTYPE_ENCRYPTED_REQUEST)
                : packet;
        out.write(data);
        out.flush();
    }

    /**
     * Waits up to {@code timeoutMs} for data and returns every frame that is now complete.
     * The list can be empty, e.g. when only part of a packet or a heartbeat answer arrived.
     *
     * @throws java.net.SocketTimeoutException if nothing arrived in time
     * @throws EOFException if the device closed the connection
     */
    public List<Frame> read(int timeoutMs) throws IOException {
        ensureOpen();
        socket.setSoTimeout(Math.max(1, timeoutMs));
        byte[] chunk = new byte[1024];
        int n = in.read(chunk);
        if (n < 0) {
            throw new EOFException("Connection closed by device");
        }
        return process(Arrays.copyOf(chunk, n));
    }

    /**
     * Reads whatever is already waiting in the socket, without blocking. Used to pick up push
     * notifications before sending the next request.
     */
    public List<Frame> drain() throws IOException {
        ensureOpen();
        List<Frame> frames = new ArrayList<>();
        while (in.available() > 0) {
            byte[] chunk = new byte[Math.min(in.available(), 4096)];
            int n = in.read(chunk);
            if (n < 0) {
                throw new EOFException("Connection closed by device");
            }
            frames.addAll(process(Arrays.copyOf(chunk, n)));
        }
        return frames;
    }

    private List<Frame> process(byte[] received) throws IOException {
        byte[] data = LocalSecurity.concat(buffer, received);
        List<byte[]> packets;
        if (protocol == ProtocolVersion.V3) {
            LocalSecurity.Decoded decoded = security.decode8370(data);
            buffer = decoded.remainder();
            packets = decoded.packets();
        } else {
            Packet.Split split = Packet.splitV2(data);
            buffer = split.remainder();
            packets = split.packets();
        }
        List<Frame> frames = new ArrayList<>();
        for (byte[] packet : packets) {
            if (Arrays.equals(packet, "ERROR".getBytes())) {
                throw new MideaException.Protocol("Device answered ERROR");
            }
            Frame frame = Frame.parse(Packet.extractFrame(packet));
            if (frame != null) {
                if (LOG.isLoggable(Level.FINEST)) {
                    LOG.finest("RX " + frame);
                }
                frames.add(frame);
            }
        }
        return frames;
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("Connection is not open");
        }
    }

    /** Closes the socket. Safe to call more than once. */
    @Override
    public void close() {
        buffer = new byte[0];
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        socket = null;
        in = null;
        out = null;
    }
}
