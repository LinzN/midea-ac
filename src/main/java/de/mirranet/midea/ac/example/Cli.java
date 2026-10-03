package de.mirranet.midea.ac.example;

import de.mirranet.midea.ac.AcControl;
import de.mirranet.midea.ac.AcState;
import de.mirranet.midea.ac.DeviceConfig;
import de.mirranet.midea.ac.DeviceInfo;
import de.mirranet.midea.ac.DeviceKey;
import de.mirranet.midea.ac.FanSpeed;
import de.mirranet.midea.ac.MideaAirConditioner;
import de.mirranet.midea.ac.OperatingMode;
import de.mirranet.midea.ac.Preset;
import de.mirranet.midea.ac.ProtocolVersion;
import de.mirranet.midea.ac.SwingMode;
import de.mirranet.midea.ac.cloud.KeyResolver;
import de.mirranet.midea.ac.cloud.MideaCloud;
import de.mirranet.midea.ac.discovery.MideaDiscovery;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small command line front end, mostly for setting up and trying out a unit. It's also the jar's
 * main class.
 *
 * <pre>
 * discover                    list air conditioners on the LAN
 * keys   --ip IP [--cloud msmart|nethome|mideaair --user U --password P]
 *                             fetch and check token/key; uses the shared account without --cloud
 * status --ip IP --id ID [--protocol 2|3] [--token T --key K]
 * set    --ip IP --id ID ... power=on mode=cool temp=22.5 fan=auto swing=off preset=eco
 * watch  --ip IP --id ID ... [--interval 30]
 * </pre>
 *
 * {@code --debug} prints every frame. {@code --beep false} keeps the unit quiet.
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> opt = new HashMap<>();
        Map<String, String> assignments = new java.util.LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--debug")) {
                enableDebug();
            } else if (a.startsWith("--") && i + 1 < args.length) {
                opt.put(a.substring(2), args[++i]);
            } else if (a.contains("=")) {
                String[] kv = a.split("=", 2);
                assignments.put(kv[0].toLowerCase(Locale.ROOT), kv[1].toLowerCase(Locale.ROOT));
            }
        }
        switch (args[0]) {
            case "discover" -> discover();
            case "keys" -> keys(opt);
            case "status" -> {
                try (MideaAirConditioner ac = new MideaAirConditioner(config(opt))) {
                    AcState s = ac.refresh();
                    System.out.println(s);
                    System.out.println(ac.getCapabilities());
                }
            }
            case "set" -> {
                try (MideaAirConditioner ac = new MideaAirConditioner(config(opt))) {
                    ac.refresh();
                    AcControl c = ac.control();
                    assignments.forEach((k, v) -> apply(c, k, v));
                    System.out.println(c.send());
                }
            }
            case "watch" -> {
                MideaAirConditioner ac = new MideaAirConditioner(config(opt));
                ac.addListener(s -> System.out.println(java.time.LocalTime.now() + " " + s));
                ac.startPolling(Duration.ofSeconds(Long.parseLong(opt.getOrDefault("interval", "30"))));
                Thread.currentThread().join();
            }
            default -> usage();
        }
    }

    private static void discover() throws Exception {
        List<DeviceInfo> found = MideaDiscovery.discover(Duration.ofSeconds(5));
        if (found.isEmpty()) {
            System.out.println("No air conditioner found.");
        }
        found.forEach(System.out::println);
    }

    private static void keys(Map<String, String> opt) throws Exception {
        DeviceInfo info = MideaDiscovery.discover(require(opt, "ip"), Duration.ofSeconds(5));
        if (info == null) {
            System.out.println("Device did not answer discovery.");
            return;
        }
        System.out.println(info);
        if (info.protocol() != ProtocolVersion.V3) {
            System.out.println("V2 device - no token/key needed.");
            return;
        }
        MideaCloud cloud = switch (opt.getOrDefault("cloud", "preset")) {
            case "msmart" -> MideaCloud.msmartHome(require(opt, "user"), require(opt, "password"));
            case "nethome" -> MideaCloud.netHomePlus(require(opt, "user"), require(opt, "password"));
            case "mideaair" -> MideaCloud.mideaAir(require(opt, "user"), require(opt, "password"));
            default -> MideaCloud.presetAccount();
        };
        Optional<DeviceKey> key = KeyResolver.resolve(info, cloud);
        if (key.isEmpty()) {
            System.out.println("No working token/key found.");
            return;
        }
        System.out.println("--id " + info.deviceId() + " --protocol 3 --port " + info.port());
        System.out.println("--token " + key.get().token());
        System.out.println("--key " + key.get().key());
    }

    private static DeviceConfig config(Map<String, String> opt) {
        DeviceConfig.Builder b = DeviceConfig.builder()
                .host(require(opt, "ip"))
                .port(Integer.parseInt(opt.getOrDefault("port", String.valueOf(DeviceConfig.DEFAULT_PORT))))
                .deviceId(Long.parseLong(require(opt, "id")))
                .protocol(ProtocolVersion.of(Integer.parseInt(opt.getOrDefault("protocol", "3"))))
                .token(opt.get("token"))
                .key(opt.get("key"));
        if (opt.containsKey("beep")) {
            b.promptTone(Boolean.parseBoolean(opt.get("beep")));
        }
        return b.build();
    }

    private static void apply(AcControl c, String k, String v) {
        switch (k) {
            case "power" -> c.power(v.equals("on") || v.equals("true") || v.equals("1"));
            case "mode" -> c.mode(OperatingMode.valueOf(v.toUpperCase(Locale.ROOT)));
            case "temp", "temperature" -> c.targetTemperature(Double.parseDouble(v));
            case "fan" -> c.fanSpeed(FanSpeed.valueOf(v.toUpperCase(Locale.ROOT)));
            case "swing" -> c.swing(SwingMode.valueOf(v.toUpperCase(Locale.ROOT)));
            case "preset" -> c.preset(Preset.valueOf(v.toUpperCase(Locale.ROOT)));
            default -> throw new IllegalArgumentException("Unknown setting " + k);
        }
    }

    private static String require(Map<String, String> opt, String name) {
        String v = opt.get(name);
        if (v == null) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return v;
    }

    private static void enableDebug() {
        Logger root = Logger.getLogger("de.mirranet.midea");
        root.setLevel(Level.FINEST);
        ConsoleHandler h = new ConsoleHandler();
        h.setLevel(Level.FINEST);
        root.addHandler(h);
    }

    private static void usage() {
        System.out.println("""
                Usage: java -jar midea-ac.jar <command> [options]
                  discover
                  keys   --ip IP [--cloud msmart|nethome|mideaair --user U --password P]
                  status --ip IP --id ID [--protocol 2|3] [--token T --key K]
                  set    --ip IP --id ID [...] power=on mode=cool temp=22.5 fan=auto swing=off preset=eco
                  watch  --ip IP --id ID [...] [--interval 30]
                  options: --port 6444 --beep false --debug""");
    }
}
