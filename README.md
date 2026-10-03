# midea-ac

Control Midea air conditioners from Java over your local network. No cloud at runtime, no dependencies, Java 17+.

Midea builds the indoor units and Wi-Fi modules for a lot of brands (Comfee, Inventor, Carrier, Toshiba, Electrolux, Pro Klima and others), so this works for many units that never say "Midea" on the box. If your unit is set up through the **MSmartHome**, **NetHome Plus** or **Midea Air** app, it's probably supported.

The protocol code is a port of [midea-local](https://github.com/midea-lan/midea-local) 12.1.0, the library behind Home Assistant's [`midea` integration](https://github.com/home-assistant/core/tree/dev/homeassistant/components/midea). Only the air conditioner part (device type `0xAC`) was ported.

```java
try (MideaAirConditioner ac = new MideaAirConditioner(cfg)) {
    AcState s = ac.refresh();
    System.out.println(s.getIndoorTemperature() + " C");

    ac.control().mode(OperatingMode.COOL).targetTemperature(23).send();
}
```

> **More in the [wiki](https://github.com/LinzN/midea-ac/wiki):** step-by-step setup, the full API reference, a [protocol overview](https://github.com/LinzN/midea-ac/wiki/Protocol-Overview) of what goes over the wire, and [troubleshooting](https://github.com/LinzN/midea-ac/wiki/Troubleshooting) for common problems.

## Contents

- [Features](#features)
- [Download](#download)
- [Getting started](#getting-started)
- [Command line tool](#command-line-tool)
- [Using the API](#using-the-api)
  - [Connecting](#connecting)
  - [Reading status](#reading-status)
  - [Control](#control)
  - [Extra features](#extra-features)
  - [Capabilities](#capabilities)
  - [Listeners and polling](#listeners-and-polling)
  - [Error handling](#error-handling)
  - [Fetching the token in code](#fetching-the-token-in-code)
- [How it behaves](#how-it-behaves)
- [Project layout](#project-layout)
- [Building and testing](#building-and-testing)
- [Limitations](#limitations)
- [Credits and license](#credits-and-license)

## Features

- **V2 and V3 modules.** V2 talks plain AES, V3 adds the token/key handshake and an encrypted, signed session.
- **Full status:** power, mode, set point, room and outdoor temperature, humidity, fan, swing, presets, energy use and live power draw, runtime, and service data like compressor frequency and coil temperatures (if the unit reports them).
- **Control:** everything the remote does, plus breezeless, indirect wind, fixed louver positions, quiet outdoor unit, buzzer, self cleaning, power limit and fresh air on units that have them.
- **Capabilities:** which modes, fan steps and features the unit supports, and the allowed set point range per mode.
- **Discovery** of units on the LAN by broadcast or by asking one IP.
- **One-time cloud login** to fetch a V3 unit's token and key. After that the cloud is never contacted again.
- **Background polling** with listeners, heartbeats and automatic reconnect.
- **No dependencies.** Not even for the tests or the bit of JSON the cloud login needs.

## Download

Prebuilt jars come from the CI server: [builds.mirranet.de/job/midea-ac](https://builds.mirranet.de/job/midea-ac/).

To use it from Maven without building it yourself, add the repository and the dependency:

```xml
<repositories>
    <repository>
        <id>mirranet</id>
        <url>https://builds.mirranet.de/plugin/repository/everything/</url>
    </repository>
</repositories>

<dependency>
    <groupId>de.mirranet</groupId>
    <artifactId>midea-ac</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Getting started

You need Java 17 or newer and a machine on the same network as the air conditioner.

**1. Build** (or grab the jar from [Download](#download) and skip this step)

```sh
git clone https://github.com/LinzN/midea-ac.git
cd midea-ac
mvn package
```

This gives you `target/midea-ac-1.0.0.jar`, which is both the library and a command line tool.

**2. Find the unit**

```sh
java -jar target/midea-ac-1.0.0.jar discover
```

```
DeviceInfo[id=151732605161920, type=0xAC, ip=192.168.1.50:6444, model=00000Q1A, protocol=V3, mac=...]
```

Note the `id`, the `ip` and the protocol. A V2 unit needs nothing else; skip to step 4.

**3. Get the token and key (V3 only)**

V3 units only accept connections with a token and key that the Midea cloud hands out. You fetch them once:

```sh
java -jar target/midea-ac-1.0.0.jar keys --ip 192.168.1.50 --cloud msmart --user you@example.com --password secret
```

Use `--cloud nethome` or `--cloud mideaair` if that's the app your unit is paired with. Without `--cloud`, the tool uses the shared account Home Assistant ships with, which often works too.

Every candidate the cloud returns is tested against the unit with a real handshake, so what you get back is known to work:

```
--id 151732605161920 --protocol 3 --port 6444
--token 4b2f...   (128 hex characters)
--key   9a1c...   (64 hex characters)
```

Store both somewhere safe. They stay valid until the unit is reset or paired again in the app. The app keeps working alongside local control; you don't have to remove the unit from it.

**4. Try it**

```sh
java -jar target/midea-ac-1.0.0.jar status --ip 192.168.1.50 --id 151732605161920 --token ... --key ...
```

The first status call can take up to a minute. See [How it behaves](#how-it-behaves) for why.

## Command line tool

```
discover                    list air conditioners on the LAN
keys   --ip IP [--cloud msmart|nethome|mideaair --user U --password P]
status --ip IP --id ID [--protocol 2|3] [--token T --key K]
set    --ip IP --id ID ... power=on mode=cool temp=22.5 fan=auto swing=off preset=eco
watch  --ip IP --id ID ... [--interval 30]

options: --port 6444   --beep false   --debug
```

`set` takes any combination of `power` (on/off), `mode` (auto, cool, dry, heat, fan_only), `temp`, `fan` (silent, low, medium, high, full, auto), `swing` (off, vertical, horizontal, both) and `preset` (none, comfort, eco, boost, sleep, away). Everything goes out as one command.

`--debug` prints every frame sent and received, which is the first thing to look at when a unit misbehaves.

## Using the API

Add the MirraNET repository and the dependency as shown under [Download](#download), or run `mvn install` on a local checkout and use just the dependency. Or put the jar on the classpath. Everything you normally need is in `de.mirranet.midea.ac`.

### Connecting

```java
DeviceConfig cfg = DeviceConfig.builder()
        .host("192.168.1.50")
        .deviceId(151732605161920L)
        .protocol(ProtocolVersion.V3)
        .token(System.getenv("MIDEA_TOKEN"))
        .key(System.getenv("MIDEA_KEY"))
        .promptTone(false)                 // no beep on every command
        .build();

try (MideaAirConditioner ac = new MideaAirConditioner(cfg)) {
    System.out.println(ac.refresh());
}
```

The client connects on first use; `connect()` is optional and mainly useful to catch wrong credentials early.

| Builder option | Default | |
|---|---|---|
| `host`, `deviceId` | required | address and id from discovery |
| `port` | 6444 | |
| `protocol` | V3 | `V2` or `V3` |
| `token`, `key` / `credentials(DeviceKey)` | | hex strings, required for V3 |
| `promptTone` | true | unit beeps when it receives a command |
| `connectTimeout` | 10 s | TCP connect including handshake |
| `responseTimeout` | 5 s | wait for the answer to one query or command |
| `powerAnalysisMethod` | 1 | how the unit encodes energy: 1 BCD, 2 binary, 3 mixed, 12, 101. Same as Home Assistant's `power_analysis_method`. Try another value if the kWh figures look wrong |
| `temperatureRange(min, max)` | from unit | override the set point limits |

`DeviceConfig.builder(deviceInfo)` fills host, port, id and protocol from a discovery result.

### Reading status

`refresh()` asks the unit and returns a snapshot. `getState()` returns the last known one without network traffic. Snapshots never change once you have them.

```java
AcState s = ac.refresh();

if (s.isPower()) {
    System.out.printf("%s, set %.1f, room %.1f, fan %s%n",
            s.getMode(), s.getTargetTemperature(), s.getIndoorTemperature(), s.getFanSpeed());
}
Double watts = s.getRealtimePower();   // null if the unit doesn't report energy
```

Getters that return `Double`, `Integer` or `Boolean` give `null` until the unit has reported that value. Plenty of units never send energy or service data. Temperatures are always Celsius, even if the unit's display is set to Fahrenheit.

| Getter | Content |
|---|---|
| `isPower()`, `getMode()`, `getActiveMode()` | power and mode; `getActiveMode()` is null while off |
| `getTargetTemperature()` | set point, 0.5 steps |
| `getIndoorTemperature()`, `getOutdoorTemperature()` | sensors |
| `getIndoorHumidity()` | percent |
| `getFanSpeed()`, `getFanSpeedRaw()` | preset, or raw 1 to 100 (102 = auto) |
| `getSwingMode()`, `getPreset()` | |
| `isScreenDisplay()`, `isFullDust()` | display on, filter needs cleaning |
| `getRealtimePower()` | W |
| `getTotalEnergyConsumption()`, `getCurrentEnergyConsumption()` | kWh |
| `getTotalOperatingTime()`, `getCurrentOperatingTime()` | hours |
| `getCompressorFrequency()`, `getIndoorCoilTemperature()`, ... | service data |
| `getMinTemperature()`, `getMaxTemperature()` | allowed set point range for the current mode |
| `getErrorCode()` | 0 if none |
| `isAvailable()`, `getLastUpdate()` | unit reachable, time of last successful refresh |

Plus boolean getters for every flag: `isEco()`, `isBoost()`, `isSleep()`, `isComfort()`, `isFrostProtect()`, `isAuxHeating()`, `isBreezeless()`, `isSelfClean()` and so on.

### Control

The basic settings all travel in one command that carries the complete state. So `control()` starts from the last known state, you change what you need, and `send()` sends it all at once:

```java
ac.refresh();   // once, so the client knows the current state

AcState s = ac.control()
        .mode(OperatingMode.COOL)     // also switches the unit on
        .targetTemperature(25)
        .fanSpeed(FanSpeed.AUTO)
        .swing(SwingMode.VERTICAL)
        .preset(Preset.ECO)
        .send();                      // returns the state the unit confirmed
```

| `AcControl` method | |
|---|---|
| `on()`, `off()`, `power(boolean)` | |
| `mode(OperatingMode)` | switches on as well. Coming out of DRY, the fan goes back to auto unless you set it |
| `targetTemperature(double)` | 16 to 31.5, rounded to 0.5 |
| `fanSpeed(FanSpeed)`, `fanSpeedRaw(int)` | presets, or 1 to 100 / 102 for stepless fans |
| `swing(SwingMode)` | off, vertical, horizontal, both |
| `preset(Preset)` | comfort, eco, boost, sleep, away (frost protection). Selecting one clears the others |
| `powerSaving(boolean)` | exclusive with the presets |
| `auxHeating`, `dry`, `smartEye`, `naturalWind`, `anion`, `fahrenheitDisplay` | flags |

For single changes there are shortcuts: `turnOn()`, `turnOff()`, `setMode()`, `setTargetTemperature()`, `setFanSpeed()`, `setSwingMode()`, `setPreset()`. If you change several things, use one `control()` call anyway: one command, one beep, no intermediate states.

### Extra features

These go out as separate commands. Not every unit has every feature; check the [capabilities](#capabilities) first.

| Method | |
|---|---|
| `setScreenDisplay(boolean)` | LED display. The unit only knows "toggle", so a command is only sent if the state needs to change |
| `setScreenDisplayAlternate(boolean)` | display on units that handle it through the newer protocol |
| `setBreezeless(boolean)` | draught-free mode |
| `setIndirectWind(boolean)` | keep the airflow off people |
| `setHorizontalVane(Vane.Horizontal)`, `setVerticalVane(Vane.Vertical)` | park a louver at a fixed position |
| `setOutdoorSilent(boolean)` | quiet outdoor unit |
| `setSound(boolean)` | indoor unit buzzer |
| `setSelfClean(boolean)` | self cleaning cycle |
| `setRateSelect(int)` | power limit: 1, 20, 40, 60, 80, 100, or 50, 75, 100 on two-level units |
| `setFreshAir(boolean, int)` | fresh air module, speed 0 to 100 |

### Capabilities

Read on the first `refresh()`:

```java
Capabilities caps = ac.getCapabilities();

Set<OperatingMode> modes = caps.supportedModes();
boolean silentFan = caps.supports(Capabilities.Feature.FAN_SILENT, false);
double[] heatRange = caps.temperatureLimits(OperatingMode.HEAT);   // {16.0, 30.0} or null
```

`get(Feature)` returns `null` for features the unit didn't mention; `supports(feature, default)` lets you pick the fallback. `isReceived()` tells you whether the unit answered the capability query at all.

### Listeners and polling

For long-running use (a dashboard, MQTT bridge, home automation) let the client keep itself up to date:

```java
MideaAirConditioner ac = new MideaAirConditioner(cfg);

ac.addListener(state -> {
    if (!state.isAvailable()) {
        log.warn("air conditioner not reachable");
        return;
    }
    mqtt.publish("climate/livingroom/temperature", state.getIndoorTemperature());
});

ac.startPolling(Duration.ofSeconds(30));
// ...
ac.close();   // stops polling and disconnects
```

Polling does a full refresh at the interval you choose, sends a heartbeat every 10 seconds in between, and picks up changes the unit reports on its own (for example when someone uses the remote). When the unit goes away, listeners get one snapshot with `isAvailable() == false`, and the client keeps trying to reconnect. The polling thread is a daemon thread.

Listeners run on the thread that handled the update. Sending a command from inside a listener is fine; hand off anything slow to your own executor.

### Error handling

Network methods throw `IOException`. The library's own subclasses:

| Exception | Meaning |
|---|---|
| `MideaException.Authentication` | V3 handshake rejected. Token or key is wrong, or the unit was paired again and has new ones |
| `MideaException.Timeout` | the unit didn't answer the basic status query |
| `MideaException.Protocol` | a packet couldn't be decoded or had a bad signature |
| `MideaCloudException` | the cloud refused a request; `code()` has the error number |

Before throwing a plain network error, the client reconnects and retries once. Invalid input fails right away without touching the network: `IllegalArgumentException` for out-of-range values, `IllegalStateException` if you switch the unit on before its mode is known, `UnsupportedOperationException` for fresh air on a unit without the module.

### Fetching the token in code

What the `keys` command does, as code:

```java
DeviceInfo dev = MideaDiscovery.discover("192.168.1.50", Duration.ofSeconds(5));

DeviceKey key = KeyResolver.resolve(dev, MideaCloud.msmartHome("you@example.com", "secret"))
        .orElseThrow(() -> new IllegalStateException("unit accepted none of the keys"));

DeviceConfig cfg = DeviceConfig.builder(dev).credentials(key).build();
```

Cloud options: `MideaCloud.msmartHome(user, pw)`, `netHomePlus(user, pw)`, `mideaAir(user, pw)`, or `presetAccount()` for the shared Home Assistant account. `MideaDiscovery.discover(Duration)` broadcasts on all interfaces and returns every unit it finds.

## How it behaves

- **The first refresh is slow.** It tries every optional query (energy, humidity, service data, capabilities). Each one the unit ignores costs two response timeouts, after which it's skipped for the lifetime of the client. Later refreshes take well under a second. `resetProtocolProbe()` starts the detection over.
- **Commands are optimistic.** The client takes the new values immediately, then overwrites them with whatever the unit confirms. This stops a quick second command from being built on the old state and switching the unit back off. If no confirmation comes, the call doesn't fail.
- **One client per unit.** Many Wi-Fi modules accept only one TCP connection at a time. A client is safe to share between threads; calls are serialized.
- **Self cleaning** takes the unit a moment to report. For at least 30 seconds after `setSelfClean()`, reports that still show the old state are ignored.
- **Logging** goes through `java.util.logging`. Set the `de.mirranet.midea` logger to `FINEST` to see every frame.

## Project layout

```
src/main/java/de/mirranet/midea/ac/
  MideaAirConditioner     the client
  AcControl               builder for the basic settings
  AcState, Capabilities   status snapshot, feature flags
  DeviceConfig, DeviceInfo, DeviceKey, enums
  AcResponseParser        decodes the unit's answers (internal)
  protocol/               wire format: 0xAA frame, 0x5A5A packet, 0x8370 envelope, message builders
  discovery/              LAN discovery
  cloud/                  one-time token fetch, KeyResolver
  example/Cli             command line tool
src/test/java/            tests, fake device, reference vectors from midea-local
```

## Building and testing

```sh
mvn package        # jar in target/
mvn test           # runs the test suite
```

Without Maven:

```sh
javac -d build $(find src/main -name '*.java')
javac -cp build -d build-test $(find src/test -name '*.java')
java -cp build:build-test de.mirranet.midea.ac.AllTests
```

The tests need no network and no unit. They check, byte for byte, that requests, checksums, encryption, discovery parsing and cloud signatures match what midea-local 12.1.0 produces (the vectors and the script that made them are in `src/test/resources`). Then they run the client against a simulated unit on localhost, over V2 and V3 with a real handshake, including reconnects, polling and a wrong token.

## Limitations

- Only air conditioners (`0xAC`). midea-local supports many other Midea appliances; those weren't ported.
- Units that use the "BB" sub-protocol (for example model 23096633) aren't supported.
- midea-local's temperature workaround for model 22013279 isn't included.
- Very old Wi-Fi modules that speak protocol V1 (XML) aren't supported.
- The cloud code only logs in, fetches tokens and lists appliances. Controlling units through the cloud is out of scope.

## Credits and license

The protocol work is entirely the effort of the [midea-local](https://github.com/midea-lan/midea-local) contributors and the projects it grew out of (midea_ac_lan, msmart). This is a translation of their code to Java. midea-local is MIT licensed; its license is reproduced in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

This project is released under the MIT License as well, see [LICENSE](LICENSE). You're free to use, change and redistribute it, including in commercial projects, as long as the copyright notice stays in.

This project isn't affiliated with or endorsed by Midea. Midea, MSmartHome and NetHome Plus are trademarks of their owners.
