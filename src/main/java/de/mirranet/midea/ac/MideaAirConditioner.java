package de.mirranet.midea.ac;

import de.mirranet.midea.ac.Capabilities.Feature;
import de.mirranet.midea.ac.protocol.AcMessages;
import de.mirranet.midea.ac.protocol.Connection;
import de.mirranet.midea.ac.protocol.Frame;
import de.mirranet.midea.ac.protocol.GeneralSetCommand;
import de.mirranet.midea.ac.protocol.NewProtocolSetCommand;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Talks to one air conditioner over the LAN.
 *
 * <pre>{@code
 * try (MideaAirConditioner ac = new MideaAirConditioner(cfg)) {
 *     AcState s = ac.refresh();
 *     ac.control().mode(OperatingMode.COOL).targetTemperature(23).send();
 * }
 * }</pre>
 *
 * <p>Every network call blocks until the unit answered or the response timeout ran out. Calls are
 * synchronized, so several threads can share one instance, but they run one at a time. Many
 * Wi-Fi modules only accept a single TCP connection, so use one instance per unit.
 *
 * <p>The connection is opened on first use. If it breaks, the call reconnects once and retries
 * before giving up. Listeners run on whichever thread handled the update: the caller's for
 * direct calls, the polling thread otherwise.
 */
public final class MideaAirConditioner implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MideaAirConditioner.class.getName());
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final int MAX_MESSAGE_ID = 254;

    private final DeviceConfig config;
    private final AcResponseParser parser = new AcResponseParser();
    private final List<Consumer<AcState>> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Set<String> answeredOnce = new HashSet<>();

    private AcState state = new AcState();
    private Connection connection;
    private int messageProtocolVersion;
    private boolean applianceQueryDone;
    private int messageId;

    private ScheduledExecutorService poller;
    private Duration pollInterval = Duration.ofSeconds(30);
    private long nextRefreshNanos;

    // A status query and how to recognise its answer. "core" queries must be answered, the rest
    // are optional and get skipped once the unit ignores them. "once" queries stop after one answer.
    private record Query(String name, IntFunction<byte[]> frame, Predicate<Frame> expect,
                         boolean core, boolean once) {
    }

    public MideaAirConditioner(DeviceConfig config) {
        this.config = config;
        parser.powerAnalysisMethod = config.powerAnalysisMethod();
        parser.customMinTemperature = config.minTemperature();
        parser.customMaxTemperature = config.maxTemperature();
        parser.refreshLimits(state);
    }

    public DeviceConfig getConfig() {
        return config;
    }

    public synchronized boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    /**
     * Opens the connection and, for V3, does the handshake. You don't need to call this; every
     * other method connects on demand. It's useful to find wrong credentials early.
     *
     * @throws MideaException.Authentication if the unit rejects token or key
     */
    public synchronized void connect() throws IOException {
        if (isConnected()) {
            return;
        }
        Connection c = new Connection(config.host(), config.port(), config.deviceId(), config.protocol(),
                config.token(), config.key());
        c.open((int) config.connectTimeout().toMillis());
        connection = c;
    }

    public synchronized void disconnect() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public void close() {
        stopPolling();
        disconnect();
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }

    // Runs the action and retries it once on a fresh connection if anything went wrong.
    // Authentication errors are not retried; they won't fix themselves.
    private <T> T io(IoAction<T> action) throws IOException {
        try {
            connect();
            return action.run();
        } catch (MideaException.Authentication e) {
            disconnect();
            markUnavailable();
            throw e;
        } catch (IOException first) {
            LOG.log(Level.FINE, "I/O error, reconnecting: " + first.getMessage());
            disconnect();
            try {
                connect();
                return action.run();
            } catch (IOException second) {
                disconnect();
                markUnavailable();
                second.addSuppressed(first);
                throw second;
            }
        }
    }

    private int nextMessageId() {
        messageId++;
        if (messageId >= MAX_MESSAGE_ID - 1) {
            messageId = 1;
        }
        return messageId;
    }

    private int responseTimeoutMs() {
        return (int) config.responseTimeout().toMillis();
    }

    /*
     * Sends a frame and reads until an answer of the expected body type shows up or the response
     * timeout runs out. Everything else that arrives meanwhile (push notifications, late answers
     * to earlier queries) is applied to the state too. Pending data is drained before sending so
     * an old answer can't be mistaken for the new one.
     */
    private boolean exchange(byte[] frame, Predicate<Frame> expect) throws IOException {
        for (Frame f : connection.drain()) {
            handleFrame(f);
        }
        connection.sendFrame(frame);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMs());
        while (true) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return false;
            }
            List<Frame> frames;
            try {
                frames = connection.read((int) remaining);
            } catch (SocketTimeoutException e) {
                return false;
            }
            boolean matched = false;
            for (Frame f : frames) {
                handleFrame(f);
                if (expect.test(f)) {
                    matched = true;
                }
            }
            if (matched) {
                return true;
            }
        }
    }

    private void handleFrame(Frame f) {
        if (f.messageType() == AcMessages.MSG_QUERY_APPLIANCE) {
            messageProtocolVersion = f.protocolVersion();
            applianceQueryDone = true;
            LOG.fine(() -> "Message protocol version " + messageProtocolVersion);
            return;
        }
        if (!parser.apply(f, state)) {
            LOG.fine(() -> "Ignored " + f);
        }
    }

    /** Last known state, without asking the unit. */
    public synchronized AcState getState() {
        return state.copy();
    }

    /** What the unit says it supports. Empty until the first {@link #refresh()}. */
    public synchronized Capabilities getCapabilities() {
        return parser.capabilities();
    }

    private static Predicate<Frame> body(int... types) {
        return f -> {
            for (int t : types) {
                if (f.bodyType() == t) {
                    return true;
                }
            }
            return false;
        };
    }

    // Same order as MideaACDevice.build_query. Built fresh each time because the protocol version
    // and the rate select capability can change after the first answers.
    private List<Query> queries() {
        boolean rate = parser.capabilities.getOrDefault(Feature.RATE_SELECT, false);
        int pv = messageProtocolVersion;
        List<Query> q = new ArrayList<>();
        q.add(new Query("status", id -> AcMessages.statusQuery(pv, id), body(0xC0), true, false));
        q.add(new Query("newProtocol" + (rate ? "+rate" : ""),
                id -> AcMessages.newProtocolQuery(pv, id, rate), body(0xB1), false, false));
        q.add(new Query("selfClean", id -> AcMessages.newProtocolQuery(pv, id,
                de.mirranet.midea.ac.protocol.NewProtocolTags.SELF_CLEAN), body(0xB1), false, false));
        q.add(new Query("energy", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_ENERGY), body(0xC1), false, false));
        q.add(new Query("humidity", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_HUMIDITY), body(0xC1), false, false));
        q.add(new Query("group0", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_OPERATING_TIME), body(0xC1), false, false));
        q.add(new Query("group1", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_COMPRESSOR), body(0xC1), false, false));
        q.add(new Query("group2", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_INDOOR_FAN), body(0xC1), false, false));
        q.add(new Query("group3", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_OUTDOOR_FAN), body(0xC1), false, false));
        q.add(new Query("group7", id -> AcMessages.groupQuery(pv, AcMessages.GROUP_COMPRESSOR_POWER), body(0xC1), false, false));
        q.add(new Query("capabilities", id -> AcMessages.capabilitiesQuery(pv, id, false), body(0xB5), false, true));
        q.add(new Query("capabilities2", id -> AcMessages.capabilitiesQuery(pv, id, true), body(0xB5), false, true));
        return q;
    }

    /**
     * Reads the full status from the unit.
     *
     * <p>The first call is slow: it tries every optional query (energy, humidity, service data,
     * capabilities ...) and waits for the response timeout on each one the unit ignores. Those are
     * remembered and skipped from then on, so later calls only take a moment. Capabilities are
     * read once.
     *
     * @return a snapshot of the new state
     * @throws MideaException.Timeout if the unit doesn't answer the basic status query
     * @throws MideaException.Authentication if a reconnect fails because of the credentials
     */
    public synchronized AcState refresh() throws IOException {
        io(() -> {
            if (!applianceQueryDone && !unsupported.contains("appliance")) {
                if (!probe(AcMessages.applianceQuery(), f -> f.messageType() == AcMessages.MSG_QUERY_APPLIANCE)) {
                    unsupported.add("appliance");
                }
            }
            for (Query q : queries()) {
                if (unsupported.contains(q.name()) || (q.once() && answeredOnce.contains(q.name()))) {
                    continue;
                }
                boolean ok = probe(q.frame().apply(nextMessageId()), q.expect());
                if (ok) {
                    if (q.once()) {
                        answeredOnce.add(q.name());
                    }
                } else if (q.core()) {
                    throw new MideaException.Timeout("Device did not answer the status query");
                } else {
                    LOG.fine(() -> "Query '" + q.name() + "' not supported, skipping from now on");
                    unsupported.add(q.name());
                }
            }
            return null;
        });
        state.available = true;
        state.lastUpdate = Instant.now();
        nextRefreshNanos = System.nanoTime() + pollInterval.toNanos();
        AcState snapshot = state.copy();
        notifyListeners(snapshot);
        return snapshot;
    }

    // One timeout can just be a slow module, so give every query a second chance.
    private boolean probe(byte[] frame, Predicate<Frame> expect) throws IOException {
        return exchange(frame, expect) || exchange(frame, expect);
    }

    /**
     * Forgets which optional queries the unit ignored and reads the capabilities again on the next
     * {@link #refresh()}. Only needed if the first refresh happened while the unit was busy.
     */
    public synchronized void resetProtocolProbe() {
        unsupported.clear();
        answeredOnce.clear();
        applianceQueryDone = false;
    }

    /**
     * Starts a command for the basic settings, based on the last known state. Call
     * {@link #refresh()} at least once before, otherwise that state is just defaults.
     */
    public synchronized AcControl control() {
        return new AcControl(this, baseCommand(), state.modeRaw);
    }

    private GeneralSetCommand baseCommand() {
        GeneralSetCommand c = new GeneralSetCommand();
        c.power = state.power;
        c.promptTone = config.promptTone();
        c.mode = state.modeRaw;
        c.targetTemperature = state.targetTemperature;
        c.fanSpeed = state.fanSpeedRaw;
        c.swingVertical = state.swingVertical;
        c.swingHorizontal = state.swingHorizontal;
        c.boost = state.boost;
        c.powerSaving = state.powerSaving;
        c.smartEye = state.smartEye;
        c.dry = state.dry;
        c.auxHeating = state.auxHeating;
        c.eco = state.eco;
        c.fahrenheit = state.fahrenheit;
        c.sleep = state.sleep;
        c.naturalWind = state.naturalWind;
        c.frostProtect = state.frostProtect;
        c.comfort = state.comfort;
        c.anion = state.anion;
        return c;
    }

    synchronized AcState sendGeneralSet(GeneralSetCommand cmd) throws IOException {
        if (cmd.mode < OperatingMode.AUTO.value() || cmd.mode > OperatingMode.FAN_ONLY.value()) {
            if (cmd.power) {
                throw new IllegalStateException("Mode unknown - call refresh() first or set a mode explicitly");
            }
        }
        final GeneralSetCommand frozen = cmd.copy();
        io(() -> {
            // Take the new values right away. Otherwise a second command sent before the answer
            // arrives would be built from the old state and could switch the unit back off.
            applyOptimistic(frozen);
            if (!exchange(AcMessages.generalSet(messageProtocolVersion, nextMessageId(), frozen), body(0xC0))) {
                LOG.fine("No confirmation for set command, keeping optimistic state");
            }
            return null;
        });
        parser.refreshLimits(state);
        AcState snapshot = state.copy();
        notifyListeners(snapshot);
        return snapshot;
    }

    private void applyOptimistic(GeneralSetCommand c) {
        state.power = c.power;
        state.modeRaw = c.mode;
        state.targetTemperature = c.targetTemperature;
        state.fanSpeedRaw = c.fanSpeed;
        state.swingVertical = c.swingVertical;
        state.swingHorizontal = c.swingHorizontal;
        state.boost = c.boost;
        state.powerSaving = c.powerSaving;
        state.smartEye = c.smartEye;
        state.dry = c.dry;
        state.auxHeating = c.auxHeating;
        state.eco = c.eco;
        state.fahrenheit = c.fahrenheit;
        state.sleep = c.sleep;
        state.naturalWind = c.naturalWind;
        state.frostProtect = c.frostProtect;
        state.comfort = c.comfort;
        state.anion = c.anion;
    }

    /** Shortcut for {@code control().on().send()}. The other setters below work the same way. */
    public AcState turnOn() throws IOException {
        return control().on().send();
    }

    public AcState turnOff() throws IOException {
        return control().off().send();
    }

    public AcState setMode(OperatingMode mode) throws IOException {
        return control().mode(mode).send();
    }

    public AcState setTargetTemperature(double celsius) throws IOException {
        return control().targetTemperature(celsius).send();
    }

    public AcState setFanSpeed(FanSpeed speed) throws IOException {
        return control().fanSpeed(speed).send();
    }

    public AcState setSwingMode(SwingMode swing) throws IOException {
        return control().swing(swing).send();
    }

    public AcState setPreset(Preset preset) throws IOException {
        return control().preset(preset).send();
    }

    /**
     * Switches the LED display on or off.
     *
     * <p>The unit only understands "toggle", so this sends one only if the requested state differs
     * from the last reported one. Calling it twice with {@code false} leaves the display off instead
     * of flipping it back on.
     */
    public synchronized AcState setScreenDisplay(boolean on) throws IOException {
        if (on != state.screenDisplay) {
            io(() -> exchange(AcMessages.toggleDisplay(messageProtocolVersion, nextMessageId(),
                    config.promptTone()), body(0xC0)));
        }
        AcState snapshot = state.copy();
        notifyListeners(snapshot);
        return snapshot;
    }

    // midea-local always includes the prompt tone in 0xB0 frames, so we do too.
    private AcState sendNewProtocol(NewProtocolSetCommand cmd, Consumer<AcState> optimistic) throws IOException {
        cmd.promptTone = config.promptTone();
        synchronized (this) {
            io(() -> {
                optimistic.accept(state);
                exchange(AcMessages.newProtocolSet(messageProtocolVersion, nextMessageId(), cmd), body(0xB0, 0xB1));
                return null;
            });
            AcState snapshot = state.copy();
            notifyListeners(snapshot);
            return snapshot;
        }
    }

    /** Display on/off for units that handle it through the new protocol instead of the toggle. */
    public AcState setScreenDisplayAlternate(boolean on) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.screenDisplayAlternate = on;
        return sendNewProtocol(c, s -> s.screenDisplayAlternate = on);
    }

    /** Breezeless (draught-free) mode, on units that have it. */
    public AcState setBreezeless(boolean on) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.breezeless = on;
        return sendNewProtocol(c, s -> s.breezeless = on);
    }

    /** Keeps the airflow from blowing directly at people. Reported as off while vertical swing runs. */
    public AcState setIndirectWind(boolean on) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.indirectWind = on;
        return sendNewProtocol(c, s -> s.indirectWind = on);
    }

    /** Parks the left/right louver at a fixed position, on units with angle control. */
    public AcState setHorizontalVane(Vane.Horizontal position) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.windLrAngle = position.raw();
        return sendNewProtocol(c, s -> s.windLrAngle = position.raw());
    }

    /** Parks the up/down louver at a fixed position, on units with angle control. */
    public AcState setVerticalVane(Vane.Vertical position) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.windUdAngle = position.raw();
        return sendNewProtocol(c, s -> s.windUdAngle = position.raw());
    }

    /** Quiet mode for the outdoor unit. */
    public AcState setOutdoorSilent(boolean on) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.outSilent = on;
        return sendNewProtocol(c, s -> s.outSilent = on);
    }

    /** Buzzer of the indoor unit, independent of the per-command beep in {@link DeviceConfig}. */
    public AcState setSound(boolean on) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.sound = on;
        return sendNewProtocol(c, s -> s.sound = on);
    }

    /**
     * Starts or stops self cleaning. The unit takes a moment to report the new state, so reports
     * that still show the old one are ignored for a while (at least 30 seconds).
     */
    public AcState setSelfClean(boolean on) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.selfClean = on;
        return sendNewProtocol(c, s -> {
            s.selfClean = on;
            parser.pendingSelfClean = on;
            long window = Math.max(pollInterval.toNanos(), Duration.ofSeconds(30).toNanos());
            parser.pendingSelfCleanDeadline = System.nanoTime() + window;
        });
    }

    /**
     * Caps the power draw. Five-level units accept 1, 20, 40, 60, 80 and 100, two-level units
     * 50, 75 and 100. {@link Capabilities.Feature#RATE_SELECT_2_LEVEL} tells which kind you have.
     */
    public AcState setRateSelect(int gear) throws IOException {
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        c.rateSelect = gear;
        return sendNewProtocol(c, s -> s.rateSelect = gear);
    }

    /**
     * Fresh air module.
     *
     * @param speed 0 to 100; 0 switches it off as well
     * @throws UnsupportedOperationException if the unit hasn't reported a module, see
     *         {@link AcState#hasFreshAir()}
     */
    public AcState setFreshAir(boolean on, int speed) throws IOException {
        int version;
        synchronized (this) {
            version = state.freshAirVersion;
        }
        if (version == 0) {
            throw new UnsupportedOperationException("Unit did not report a fresh air module");
        }
        int clamped = Math.max(0, Math.min(100, speed));
        NewProtocolSetCommand c = new NewProtocolSetCommand();
        int[] value = {on && clamped > 0 ? 1 : 0, clamped};
        if (version == 1) {
            c.freshAir1 = value;
        } else {
            c.freshAir2 = value;
        }
        return sendNewProtocol(c, s -> {
            s.freshAirPower = value[0] > 0;
            s.freshAirFanSpeed = clamped;
        });
    }

    /**
     * Adds a listener. It gets a snapshot after every refresh, command and push notification, and
     * once with {@code isAvailable() == false} when the unit stops answering.
     */
    public void addListener(Consumer<AcState> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<AcState> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(AcState snapshot) {
        for (Consumer<AcState> l : listeners) {
            try {
                l.accept(snapshot);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Listener failed", e);
            }
        }
    }

    private void markUnavailable() {
        if (state.available) {
            state.available = false;
            notifyListeners(state.copy());
        }
    }

    /**
     * Keeps the state current in the background: a full refresh every {@code interval}, a
     * heartbeat every 10 seconds in between, and push notifications as they come in. After a failure
     * it keeps trying to reconnect. The thread is a daemon. Calling it again replaces the old one.
     */
    public synchronized void startPolling(Duration interval) {
        stopPolling();
        pollInterval = interval;
        nextRefreshNanos = System.nanoTime();
        long tick = Math.min(interval.toMillis(), HEARTBEAT_INTERVAL.toMillis());
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "midea-ac-" + config.deviceId());
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::pollTick, 0, tick, TimeUnit.MILLISECONDS);
    }

    public void stopPolling() {
        ScheduledExecutorService p;
        synchronized (this) {
            p = poller;
            poller = null;
        }
        if (p != null) {
            p.shutdownNow();
        }
    }

    // Runs every min(interval, 10 s): either a full refresh when due, or a heartbeat plus
    // whatever the unit pushed in the meantime.
    private void pollTick() {
        synchronized (this) {
            try {
                if (System.nanoTime() >= nextRefreshNanos || !isConnected()) {
                    refresh();
                } else {
                    connection.sendHeartbeat();
                    List<Frame> frames = connection.drain();
                    if (!frames.isEmpty()) {
                        frames.forEach(this::handleFrame);
                        notifyListeners(state.copy());
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Polling failed: " + e.getMessage());
                disconnect();
                markUnavailable();
                nextRefreshNanos = System.nanoTime();
            }
        }
    }
}
