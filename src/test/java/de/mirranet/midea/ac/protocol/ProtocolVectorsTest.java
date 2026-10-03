package de.mirranet.midea.ac.protocol;

import static de.mirranet.midea.ac.Check.eq;
import static de.mirranet.midea.ac.Check.ok;

import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Byte-exact comparison with frames produced by midea-local 12.1.0 (see vectors.txt).
 */
public final class ProtocolVectorsTest {

    private ProtocolVectorsTest() {
    }

    public static void run() throws Exception {
        System.out.println("Protocol vectors (midea-local 12.1.0)");

        byte[] table = new byte[256];
        int[] t = Crc8.table();
        for (int i = 0; i < 256; i++) {
            table[i] = (byte) t[i];
        }
        eq("crc8 table", "ced932fcf961f1968f916a51aa7ecc44",
                Hex.encode(MessageDigest.getInstance("MD5").digest(table)));

        eq("0x41 status query", "aa20ac00000000000303418100ff0000000000000000000000000000000001abc1",
                Hex.encode(AcMessages.statusQuery(3, 1)));

        GeneralSetCommand s = new GeneralSetCommand();
        s.power = true;
        s.mode = 2;
        s.targetTemperature = 23.5;
        s.fanSpeed = 60;
        s.swingVertical = true;
        s.eco = true;
        s.promptTone = true;
        eq("0x40 general set", "aa23ac000000000003024041573c0000003c00800000000000000000000000000002fa60",
                Hex.encode(AcMessages.generalSet(3, 2, s)));

        eq("0xB1 new protocol query",
                "aa22ac00000000000303b10a420018001500170033024b000a000900cd002c0203da80",
                Hex.encode(AcMessages.newProtocolQuery(3, 3, false)));

        NewProtocolSetCommand n = new NewProtocolSetCommand();
        n.promptTone = true;
        n.breezeless = true;
        n.windUdAngle = 50;
        eq("0xB0 new protocol set", "aa1aac00000000000302b003180001011a0001010900013204ce3e",
                Hex.encode(AcMessages.newProtocolSet(3, 4, n)));

        eq("0xB5 capabilities", "aa0fac00000000000303b50100058400",
                Hex.encode(AcMessages.capabilitiesQuery(3, 5, false)));
        eq("0xB5 capabilities 2", "aa10ac00000000000303b501010106a0e0",
                Hex.encode(AcMessages.capabilitiesQuery(3, 6, true)));
        eq("C1 energy query", "aa11ac00000000000303412101440001098c",
                Hex.encode(AcMessages.groupQuery(3, AcMessages.GROUP_ENERGY)));
        eq("C1 humidity query", "aa11ac00000000000303412101450001a2f2",
                Hex.encode(AcMessages.groupQuery(3, AcMessages.GROUP_HUMIDITY)));
        eq("display toggle", "aa20ac00000000000303414200ff0200020000000000000000000000000009faa5",
                Hex.encode(AcMessages.toggleDisplay(3, 9, true)));
        eq("appliance query", "aa1dac000000000000a00000000000000000000000000000000000000097",
                Hex.encode(AcMessages.applianceQuery()));

        eq("encode32", "09a02ba1291000b060665e186676fc7a",
                Hex.encode(LocalSecurity.encode32("hello".getBytes())));
        eq("aes ecb", "9c1d3ed0946b06fd7c72cc91ba7c949e",
                Hex.encode(LocalSecurity.encryptPayload(Hex.decode("aa20ac0000"))));
        eq("aes ecb roundtrip", "aa20ac0000",
                Hex.encode(LocalSecurity.decryptPayload(Hex.decode("9c1d3ed0946b06fd7c72cc91ba7c949e"))));

        // timestamp: 2026-10-03 12:17:45.678 -> [67,45,17,12,03,10,26,20]
        byte[] ts = Packet.timestamp(ZonedDateTime.of(2026, 10, 3, 12, 17, 45, 678_000_000, ZoneOffset.UTC));
        eq("packet timestamp", "432d110c030a1a14", Hex.encode(ts));

        // packet wrap/unwrap
        byte[] frame = AcMessages.statusQuery(3, 1);
        byte[] packet = Packet.build(151732605161920L, frame);
        eq("packet length field", packet.length, (packet[4] & 0xFF) | ((packet[5] & 0xFF) << 8));
        eq("packet checksum", Hex.encode(LocalSecurity.encode32(java.util.Arrays.copyOf(packet, packet.length - 16))),
                Hex.encode(java.util.Arrays.copyOfRange(packet, packet.length - 16, packet.length)));
        eq("packet roundtrip", Hex.encode(frame), Hex.encode(Packet.extractFrame(packet)));
        ok("heartbeat ignored on receive", Packet.extractFrame(Packet.heartbeat(1)) == null);

        // fragmented V2 stream
        byte[] two = LocalSecurity.concat(packet, packet);
        Packet.Split part = Packet.splitV2(java.util.Arrays.copyOf(two, packet.length + 10));
        eq("v2 split complete", 1, part.packets().size());
        eq("v2 split remainder", 10, part.remainder().length);

        // 8370 envelope roundtrip (length 102 for 56 bytes like python)
        LocalSecurity a = new LocalSecurity();
        LocalSecurity b = new LocalSecurity();
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) {
            key[i] = (byte) i;
        }
        a.setTcpKey(key);
        b.setTcpKey(key);
        byte[] payload = new byte[56];
        for (int i = 0; i < 56; i++) {
            payload[i] = (byte) i;
        }
        byte[] env = a.encode8370(payload, LocalSecurity.MSGTYPE_ENCRYPTED_REQUEST);
        eq("8370 length", 102, env.length);
        byte[] env2 = a.encode8370(payload, LocalSecurity.MSGTYPE_ENCRYPTED_REQUEST);
        LocalSecurity.Decoded dec = b.decode8370(LocalSecurity.concat(env, env2, new byte[]{(byte) 0x83}));
        eq("8370 decode count", 2, dec.packets().size());
        eq("8370 decode payload", Hex.encode(payload), Hex.encode(dec.packets().get(0)));
        eq("8370 remainder", 1, dec.remainder().length);

        // new protocol TLV parser on a B1 response
        List<Integer> tags = List.copyOf(NewProtocolCodec.parse(Hex.decode(
                "b1064200000102150000013718000001010a00000132cd000001033900000101")).keySet());
        eq("tlv tags", List.of(0x42, 0x15, 0x18, 0x0A, 0xCD, 0x39), tags);
    }
}
