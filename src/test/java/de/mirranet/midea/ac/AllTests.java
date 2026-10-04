package de.mirranet.midea.ac;

import static de.mirranet.midea.ac.Check.eq;
import static de.mirranet.midea.ac.Check.near;
import static de.mirranet.midea.ac.Check.ok;

import de.mirranet.midea.ac.protocol.FakeAcDevice;
import de.mirranet.midea.ac.protocol.Frame;
import de.mirranet.midea.ac.protocol.Hex;
import de.mirranet.midea.ac.protocol.ProtocolVectorsTest;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs all tests: {@code java -cp build:build-test de.mirranet.midea.ac.AllTests}. */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) throws Exception {
        ProtocolVectorsTest.run();
        parserVectors();
        discoveryAndCloudVectors();
        endToEnd(ProtocolVersion.V2);
        endToEnd(ProtocolVersion.V3);
        wrongKey();
        System.out.printf("%n%d passed, %d failed%n", Check.passed, Check.failed);
        System.exit(Check.failed == 0 ? 0 : 1);
    }

    private static Frame frame(String hex) {
        byte[] f = Hex.decode(hex);
        return Frame.parse(f);
    }

    static void parserVectors() {
        System.out.println("Response parsing (values from midea-local 12.1.0)");
        AcResponseParser p = new AcResponseParser();
        AcState s = new AcState();

        ok("C0 handled", p.apply(frame("aa24ac00000000000303c00157280000003c0010006256000035000000000000000000b1"), s));
        eq("C0 power", true, s.power);
        eq("C0 mode", OperatingMode.COOL, s.getMode());
        near("C0 target", 23.5, s.targetTemperature);
        eq("C0 fan", 40, s.fanSpeedRaw);
        eq("C0 swing", SwingMode.VERTICAL, s.getSwingMode());
        eq("C0 eco", true, s.eco);
        near("C0 indoor", 24.5, s.indoorTemperature);
        near("C0 outdoor", 18.3, s.outdoorTemperature);

        // 16 C on units with the alternate set point field: byte 2 says 17, byte 13 says 4 (+12)
        p.apply(frame("aa24ac00000000000303c00181000000000000000062562400000000000000000000000c"), s);
        near("C0 alternate set point 16", 16.0, s.targetTemperature);
        eq("C0 filter flag next to alternate field", true, s.fullDust);
        p.apply(frame("aa24ac00000000000303c00191000000000000000062560400000000000000000000001c"), s);
        near("C0 alternate set point 16.5", 16.5, s.targetTemperature);
        p.apply(frame("aa24ac00000000000303c00157280000003c0010006256000035000000000000000000b1"), s);
        near("C0 without alternate field unchanged", 23.5, s.targetTemperature);

        p.apply(frame("aa2cac00000000000303b1064200000102150000013718000001010a00000132cd000001033900000101000076"), s);
        eq("B1 indirect wind", true, s.indirectWind);
        eq("B1 humidity", 55, s.indoorHumidity);
        eq("B1 breezeless", true, s.breezeless);
        eq("B1 lr angle", Vane.Horizontal.MIDDLE, s.getHorizontalVane());
        eq("B1 out silent", true, s.outSilent);
        eq("B1 self clean", true, s.selfClean);

        p.apply(frame("aa24ac00000000000303b50414020101250207223c223c203c0010020106240201010000d2"), s);
        Capabilities c = p.capabilities();
        eq("B5 heat", true, c.get(Capabilities.Feature.HEAT_MODE));
        eq("B5 fan silent", true, c.get(Capabilities.Feature.FAN_SILENT));
        eq("B5 fan custom", false, c.get(Capabilities.Feature.FAN_CUSTOM));
        eq("B5 display", true, c.get(Capabilities.Feature.DISPLAY_CONTROL));
        eq("B5 cool limits", "[17.0, 30.0]", java.util.Arrays.toString(c.temperatureLimits(OperatingMode.COOL)));
        eq("B5 heat limits", "[16.0, 30.0]", java.util.Arrays.toString(c.temperatureLimits(OperatingMode.HEAT)));
        near("limits applied (cool)", 17.0, s.minTemperature);

        String c1 = "aa20ac00000000000303c121014400012345000012340000056700891200000051";
        p.apply(frame(c1), s);
        near("C1 total energy (BCD)", 123.45, s.totalEnergyConsumption);
        near("C1 operating energy", 12.34, s.totalOperatingConsumption);
        near("C1 current energy", 5.67, s.currentEnergyConsumption);
        near("C1 realtime power", 891.2, s.realtimePower);
        p.powerAnalysisMethod = AcResponseParser.POWER_BINARY;
        p.apply(frame(c1), s);
        near("C1 total energy (binary)", 7456.5, s.totalEnergyConsumption);
        near("C1 power (binary)", 3509.0, s.realtimePower);

        p.apply(frame("aa20ac00000000000305a0558066000000030008000000007000000000000000d6"), s);
        eq("A0 power", true, s.power);
        near("A0 target", 22.5, s.targetTemperature);
        eq("A0 mode", OperatingMode.HEAT, s.getMode());
        eq("A0 fan", FanSpeed.AUTO, s.getFanSpeed());
        eq("A0 swing horizontal", true, s.swingHorizontal);
        eq("A0 aux heating", true, s.auxHeating);
        eq("A0 display", false, s.screenDisplay);
        near("limits follow mode (heat)", 16.0, s.minTemperature);

        eq("fan buckets", FanSpeed.MEDIUM, FanSpeed.fromRaw(55));
        eq("fan buckets low edge", FanSpeed.SILENT, FanSpeed.fromRaw(20));
        eq("temperature negative", -5.3, AcResponseParser.temperature(50 - 10, 3));
        eq("temperature invalid", null, AcResponseParser.temperature(0xFF, 0));
    }

    static void discoveryAndCloudVectors() throws Exception {
        System.out.println("Discovery and cloud crypto");
        Method parse = de.mirranet.midea.ac.discovery.MideaDiscovery.class
                .getDeclaredMethod("parse", byte[].class, String.class);
        parse.setAccessible(true);
        String v2 = "5a5a000000000000000000000000000000000000c0110800008a00000000000000000000000000008a17e3731a9cd9e1aff3490881a43b00e485fed2c8365233b665ba0f2c411abc433c485c023dae930cc04409b082bade88d9f0d268004a78eb847747c3a8d51969916493e4785459c89c1a304147a5096765269afc2e46732ffdbea7dde107eb00000000000000000000000000000000";
        DeviceInfo d = (DeviceInfo) parse.invoke(null, Hex.decode(v2), "192.168.1.50");
        eq("discovery id", 151732605161920L, d.deviceId());
        eq("discovery type", 0xAC, d.deviceType());
        eq("discovery port", 6444, d.port());
        eq("discovery model", "00000Q1A", d.model());
        eq("discovery protocol", ProtocolVersion.V2, d.protocol());
        eq("discovery mac", "a0b1c2d3e4f5", d.mac());
        DeviceInfo d3 = (DeviceInfo) parse.invoke(null, Hex.decode("8370000000000000" + v2 + "00000000000000000000000000000000"),
                "192.168.1.50");
        eq("discovery v3", ProtocolVersion.V3, d3.protocol());

        java.lang.reflect.Field f = de.mirranet.midea.ac.discovery.MideaDiscovery.class.getDeclaredField("BROADCAST_MSG");
        f.setAccessible(true);
        eq("broadcast message", "5a5a01114800920000000000000000000000000000000000000000000000000000000000000000007f75bd6b3e4f8b762e849c6e578d6590036e9d4342a50f1f569eb8ec918e92e5",
                Hex.encode((byte[]) f.get(null)));

        eq("udp id method 1", "2b4ee2436cc634040cb976a92a53bcd5",
                de.mirranet.midea.ac.cloud.CloudCrypto.udpId(151732605161920L, 1));
        eq("udp id method 2", "1a795626332686b426df2939df3e9e3a",
                de.mirranet.midea.ac.cloud.CloudCrypto.udpId(151732605161920L, 2));
        de.mirranet.midea.ac.cloud.CloudVectors.run();
    }

    static void endToEnd(ProtocolVersion version) throws Exception {
        System.out.println("End-to-end against fake device (" + version + ")");
        try (FakeAcDevice dev = new FakeAcDevice(version)) {
            DeviceConfig cfg = DeviceConfig.builder()
                    .host("127.0.0.1").port(dev.port()).deviceId(dev.deviceId).protocol(version)
                    .token(Hex.encode(dev.token)).key(Hex.encode(dev.key))
                    .responseTimeout(Duration.ofMillis(300))
                    .build();
            try (MideaAirConditioner ac = new MideaAirConditioner(cfg)) {
                AtomicReference<AcState> last = new AtomicReference<>();
                ac.addListener(last::set);
                AcState s = ac.refresh();
                eq("refresh power", false, s.isPower());
                near("refresh indoor", 23.4, s.getIndoorTemperature());
                near("refresh outdoor", 9.2, s.getOutdoorTemperature());
                eq("refresh humidity (B1)", 55, s.getIndoorHumidity());
                near("refresh energy (C1)", 123.45, s.getTotalEnergyConsumption());
                eq("capabilities fetched", true, ac.getCapabilities().isReceived());
                ok("listener notified", last.get() != null);

                long t0 = System.nanoTime();
                ac.refresh();
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                ok("unsupported queries skipped on 2nd refresh (" + ms + " ms)", ms < 1000);

                s = ac.control().mode(OperatingMode.HEAT).targetTemperature(22.5).fanSpeed(FanSpeed.LOW)
                        .swing(SwingMode.VERTICAL).preset(Preset.ECO).send();
                eq("device power", true, dev.power);
                eq("device mode", 4, dev.mode);
                eq("device target", 22.5, dev.target);
                eq("device fan", 40, dev.fan);
                eq("device swing", true, dev.swingV);
                eq("device eco", true, dev.eco);
                eq("confirmed state", OperatingMode.HEAT, s.getActiveMode());
                eq("confirmed preset", Preset.ECO, s.getPreset());

                ac.setTargetTemperature(19);
                eq("follow-up keeps mode", 4, dev.mode);
                eq("follow-up keeps power", true, dev.power);
                eq("follow-up temp", 19.0, dev.target);

                ac.setScreenDisplay(true);
                eq("display already on -> no toggle", 0, dev.toggles);
                ac.setScreenDisplay(false);
                eq("display toggled", 1, dev.toggles);
                eq("display state", false, ac.getState().isScreenDisplay());

                ac.setBreezeless(true);
                eq("breezeless echo", true, ac.getState().isBreezeless());

                ac.turnOff();
                eq("device off", false, dev.power);

                // connection drop → transparent reconnect
                ac.disconnect();
                ok("reconnect after drop", ac.refresh().isAvailable());

                // polling + listener
                CountDownLatch latch = new CountDownLatch(2);
                ac.addListener(x -> latch.countDown());
                ac.startPolling(Duration.ofMillis(200));
                ok("polling delivers updates", latch.await(5, TimeUnit.SECONDS));
                ac.stopPolling();
            }
        }
    }

    static void wrongKey() throws Exception {
        System.out.println("V3 authentication failure");
        try (FakeAcDevice dev = new FakeAcDevice(ProtocolVersion.V3)) {
            DeviceConfig cfg = DeviceConfig.builder()
                    .host("127.0.0.1").port(dev.port()).deviceId(dev.deviceId).protocol(ProtocolVersion.V3)
                    .token(Hex.encode(new byte[64])).key(Hex.encode(dev.key))
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            try (MideaAirConditioner ac = new MideaAirConditioner(cfg)) {
                ac.refresh();
                ok("wrong token rejected", false);
            } catch (MideaException.Authentication e) {
                ok("wrong token rejected", true);
            }
        }
    }
}
