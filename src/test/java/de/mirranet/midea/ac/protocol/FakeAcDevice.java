package de.mirranet.midea.ac.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import de.mirranet.midea.ac.ProtocolVersion;

/**
 * In-process fake air conditioner speaking the real wire protocol (V2 or V3 incl. handshake).
 * Answers status, set, new protocol, energy and capability queries; other group queries stay
 * unanswered so the client's "unsupported query" detection is exercised.
 */
public final class FakeAcDevice implements AutoCloseable {

    public final long deviceId = 151732605161920L;
    public final byte[] token = new byte[64];
    public final byte[] key = new byte[32];
    private final ProtocolVersion protocol;
    private final ServerSocket server;
    private final Thread thread;

    // simulated state
    public volatile boolean power;
    public volatile int mode = 2;
    public volatile double target = 24.0;
    public volatile int fan = 102;
    public volatile boolean swingV;
    public volatile boolean eco;
    public volatile boolean display = true;
    public volatile int setCommands;
    public volatile int toggles;

    public FakeAcDevice(ProtocolVersion protocol) throws IOException {
        this.protocol = protocol;
        for (int i = 0; i < 64; i++) {
            token[i] = (byte) (i * 7);
        }
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) (0xA0 + i);
        }
        server = new ServerSocket(0);
        thread = new Thread(this::serve, "fake-ac");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                handle(s);
            } catch (IOException e) {
                // client went away
            }
        }
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        LocalSecurity sec = new LocalSecurity();
        byte[] buffer = new byte[0];
        byte[] chunk = new byte[2048];
        boolean authenticated = protocol == ProtocolVersion.V2;
        while (true) {
            int n = in.read(chunk);
            if (n < 0) {
                return;
            }
            buffer = LocalSecurity.concat(buffer, Arrays.copyOf(chunk, n));
            if (!authenticated) {
                if (buffer.length < 72) {
                    continue;
                }
                byte[] recvToken = Arrays.copyOfRange(buffer, 8, 72);
                if (!Arrays.equals(recvToken, token)) {
                    out.write("ERROR".getBytes());
                    return;
                }
                byte[] plain = new byte[32];
                for (int i = 0; i < 32; i++) {
                    plain[i] = (byte) (i * 3 + 1);
                }
                Cipher c = cipher(Cipher.ENCRYPT_MODE, key);
                byte[] resp;
                try {
                    resp = LocalSecurity.concat(c.doFinal(plain),
                            MessageDigest.getInstance("SHA-256").digest(plain));
                } catch (Exception e) {
                    throw new IOException(e);
                }
                out.write(sec.encode8370(resp, LocalSecurity.MSGTYPE_HANDSHAKE_RESPONSE));
                byte[] tcp = new byte[32];
                for (int i = 0; i < 32; i++) {
                    tcp[i] = (byte) (plain[i] ^ key[i]);
                }
                sec.setTcpKey(tcp);
                authenticated = true;
                buffer = Arrays.copyOfRange(buffer, 72, buffer.length);
                continue;
            }
            List<byte[]> packets;
            if (protocol == ProtocolVersion.V3) {
                LocalSecurity.Decoded d = sec.decode8370(buffer);
                buffer = d.remainder();
                packets = d.packets();
            } else {
                Packet.Split sp = Packet.splitV2(buffer);
                buffer = sp.remainder();
                packets = sp.packets();
            }
            for (byte[] p : packets) {
                Frame f = Frame.parse(Packet.extractFrame(p));
                if (f == null) {
                    continue;
                }
                for (byte[] answer : answer(f)) {
                    byte[] pkt = Packet.build(deviceId, answer);
                    out.write(protocol == ProtocolVersion.V3
                            ? sec.encode8370(pkt, LocalSecurity.MSGTYPE_ENCRYPTED_RESPONSE) : pkt);
                }
                out.flush();
            }
        }
    }

    private static Cipher cipher(int mode, byte[] key) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return c;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private List<byte[]> answer(Frame f) {
        List<byte[]> out = new ArrayList<>();
        byte[] b = f.body();
        if (f.messageType() == AcMessages.MSG_QUERY_APPLIANCE) {
            out.add(Frame.build(0xAC, 3, AcMessages.MSG_QUERY_APPLIANCE, new byte[19]));
            return out;
        }
        int bt = f.bodyType();
        if (bt == 0x41 && (b[1] & 0xFF) == 0x81) {
            out.add(status(AcMessages.MSG_QUERY));
        } else if (bt == 0x41 && (b[1] & 0xFF) == 0x21) {
            if ((b[3] & 0xFF) == 0x44) {
                byte[] c1 = Hex.decode("c121014400012345000012340000056700891200000000");
                out.add(Frame.build(0xAC, 3, AcMessages.MSG_QUERY, c1));
            } // other groups: unsupported
        } else if (bt == 0x41 && (b[1] & 0x02) != 0 && (b[4] & 0xFF) == 0x02) {
            display = !display;
            toggles++;
            out.add(status(AcMessages.MSG_QUERY));
        } else if (bt == 0x40) {
            setCommands++;
            power = (b[1] & 0x01) != 0;
            mode = (b[2] & 0xE0) >> 5;
            target = (b[2] & 0x0F) + 16 + ((b[2] & 0x10) != 0 ? 0.5 : 0);
            fan = b[3] & 0x7F;
            swingV = (b[7] & 0x0C) != 0;
            eco = (b[9] & 0x80) != 0;
            out.add(status(AcMessages.MSG_SET));
        } else if (bt == 0xB1) {
            out.add(Frame.build(0xAC, 3, AcMessages.MSG_QUERY,
                    Hex.decode("b10215000001370a0000013200")));
        } else if (bt == 0xB0) {
            out.add(Frame.build(0xAC, 3, AcMessages.MSG_SET, Hex.decode("b001180000010100")));
        } else if (bt == 0xB5) {
            out.add(Frame.build(0xAC, 3, AcMessages.MSG_QUERY,
                    Hex.decode("b50414020101250207223c223c203c0010020106240201010000")));
        }
        return out;
    }

    private byte[] status(int messageType) {
        byte[] c0 = new byte[24];
        c0[0] = (byte) 0xC0;
        c0[1] = (byte) (power ? 1 : 0);
        int t = (int) target;
        c0[2] = (byte) ((mode << 5) | ((t - 16) & 0x0F) | (target - t >= 0.5 ? 0x10 : 0));
        c0[3] = (byte) fan;
        c0[7] = (byte) (swingV ? 0x0C : 0);
        c0[9] = (byte) (eco ? 0x10 : 0);
        c0[11] = (byte) (50 + 2 * 23); // 23.x indoor
        c0[12] = (byte) (50 + 2 * 9);  // 9.x outdoor
        c0[14] = (byte) (display ? 0x00 : 0x70);
        c0[15] = (byte) 0x24;          // decimals: indoor .4, outdoor .2
        return Frame.build(0xAC, 3, messageType, c0);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
