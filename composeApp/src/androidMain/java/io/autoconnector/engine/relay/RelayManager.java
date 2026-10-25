package io.autoconnector.engine.relay;

import android.content.Context;

import io.autoconnector.engine.check.Rating;
import io.autoconnector.engine.db.ProxyStore;
import io.autoconnector.engine.model.ProxyEntry;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the two loopback SOCKS5 ports and the failover policy between them.
 *
 * <p>Each port keeps a sticky "current upstream" proxy. When Telegram migrates
 * from one port to the other — which it does when the proxy it was using stops
 * working — that is taken as proof the abandoned port's upstream is bad: it is
 * penalised in the rating and dropped, so the next connection picks a fresh
 * one.
 */
public final class RelayManager {

    /** Currently running manager (or {@code null}). Used by the UI to query sticky upstreams. */
    public static volatile RelayManager INSTANCE;

    public final Context appContext;
    public final int portA;
    public final int portB;
    private final ProxyStore store;
    private final RelayServer.Listener listener;

    private final AtomicReference<ProxyEntry> upstreamA = new AtomicReference<>();
    private final AtomicReference<ProxyEntry> upstreamB = new AtomicReference<>();
    private volatile int lastActivePort;
    /** Epoch-ms of recent relay-port switches (ring of 6), to detect Telegram
     *  "bouncing" between 55001/55002 — a sign it can't hold a link, so the
     *  upstream picker should widen its candidate set hard. */
    private final long[] portSwitchTimes = new long[6];
    private volatile int portSwitchIdx = 0;
    /** User-pinned upstream from the catalogue. While non-null it sticks to
     *  both ports and bypasses automatic refresh / penalisation. */
    private volatile ProxyEntry pinned;

    private RelayServer serverA;
    private RelayServer serverB;

    /** Experimental standby-socket pool (self-gates on Prefs.prewarmEnabled). */
    private volatile PrewarmPool prewarm;
    /** Dead-upstream / stalled-download supervisor + governor/auto-tune tick. */
    private volatile StallWatchdog watchdog;

    /**
     * ACTIVE upstream ("session affinity"). Telegram opens many sockets at once;
     * sending each one to a different random proxy made downloads land on random
     * (often bad) hosts and flooded the network with handshakes. Instead, once a
     * host carries Telegram, every new socket goes to that same host — exactly
     * like a proxy entered directly in Telegram — until it fails, stalls or turns
     * out slow; then a new one is elected (by measured speed). Unlike a pin, the
     * active host is switched automatically.
     */
    private volatile ProxyEntry active;
    private volatile long activeSince;
    /** Only one Telegram socket at a time runs the election race; the others wait
     *  for its result instead of each launching its own storm of handshakes. */
    private final java.util.concurrent.locks.ReentrantLock electLock =
            new java.util.concurrent.locks.ReentrantLock();
    public PrewarmPool prewarm() { return prewarm; }

    public RelayManager(int portA, int portB, ProxyStore store,
                        RelayServer.Listener listener, Context appContext) {
        this.portA = portA;
        this.portB = portB;
        this.store = store;
        this.listener = listener;
        this.appContext = appContext;
    }

    public void start() throws IOException {
        serverA = new RelayServer(portA, store, this, listener);
        serverB = new RelayServer(portB, store, this, listener);
        serverA.start();
        serverB.start();
        // Pre-populate stickies with current top-2 alive (per-mode aware)
        // so the catalogue's "in relay" marker shows up before the first
        // Telegram session — and so the first request lands on a host
        // that has worked on THIS network, not just any network.
        io.autoconnector.engine.core.NetworkMode net =
                io.autoconnector.engine.core.NetworkMonitor.currentMode();
        List<ProxyEntry> top = (net != io.autoconnector.engine.core.NetworkMode.UNKNOWN)
                ? store.topAliveForMode(net, 8)
                : store.topAlive(8);
        if (top.isEmpty()) top = store.topAlive(8);
        if (top.isEmpty()) top = store.top(8);
        if (!top.isEmpty()) {
            upstreamA.set(top.get(0));
            if (top.size() > 1) upstreamB.set(top.get(1));
            else upstreamB.set(top.get(0));
        }
        INSTANCE = this;
        SpeedBoard.init(store);
        initDcResolver(appContext);
        applyTuningPrefs(appContext);
        prewarm = new PrewarmPool(store, appContext);
        prewarm.start();
        watchdog = new StallWatchdog(this, store);
        watchdog.start();
    }

    /** Wires the DC resolver to Prefs (learned DC map) and the relay log. */
    public static void initDcResolver(Context ctx) {
        final io.autoconnector.engine.core.Prefs p = new io.autoconnector.engine.core.Prefs(ctx);
        io.autoconnector.engine.net.DcResolver.init(new io.autoconnector.engine.net.DcResolver.Store() {
            @Override public String load() { return p.dcMapLearned(); }
            @Override public void save(String encoded) { p.setDcMapLearned(encoded); }
        }, RelayLog::emit);
    }

    /** Pushes the speed/auto-tune feature switches from Prefs into the engine. */
    public static void applyTuningPrefs(Context ctx) {
        try {
            io.autoconnector.engine.core.Prefs p = new io.autoconnector.engine.core.Prefs(ctx);
            AutoTuner.setEnabled(p.autoTune());
            StallWatchdog.setGuardEnabled(p.stallGuard());
            io.autoconnector.engine.core.TrafficGovernor.setEnabled(p.tgFirstScan());
        } catch (Throwable ignored) {}
    }

    /** True if {@code proxyId} is the user-pinned upstream. */
    public boolean isPinned(long proxyId) {
        ProxyEntry p = pinned;
        return p != null && p.id == proxyId;
    }

    /** The user-pinned upstream entry, or null. */
    public ProxyEntry pinnedEntry() { return pinned; }

    /** The current active (affinity) upstream, or null. */
    public ProxyEntry activeEntry() { return active; }

    public long activeSince() { return activeSince; }

    public java.util.concurrent.locks.ReentrantLock electLock() { return electLock; }

    /** Makes {@code p} the host every new Telegram socket goes to. */
    public void setActive(ProxyEntry p, String why) {
        if (p == null) return;
        ProxyEntry prev = active;
        if (prev != null && prev.id == p.id) return;
        active = p;
        activeSince = System.currentTimeMillis();
        upstreamA.set(p);
        upstreamB.set(p);
        RelayLog.emit("★ active upstream → " + p.host + ":" + p.port + " (" + why + ")");
    }

    /** Drops {@code proxyId} as the active host (if it is). Returns true if it was. */
    public boolean clearActive(long proxyId, String why) {
        ProxyEntry a = active;
        if (a == null || a.id != proxyId) return false;
        active = null;
        RelayLog.emit("☆ active upstream " + a.host + ":" + a.port + " dropped (" + why
                + ") — electing a new one");
        return true;
    }

    /** Id of the current active upstream (0 = none), for the UI. */
    public static long currentActiveId() {
        RelayManager m = INSTANCE;
        ProxyEntry a = m != null ? m.active : null;
        return a != null ? a.id : 0;
    }

    /**
     * Catalog «use in relay»: make {@code p} the active host right now and close
     * the current relay sockets so Telegram reconnects through it at once. Unlike
     * a pin, the relay still switches away automatically if the host fails.
     */
    public static boolean useInRelay(ProxyEntry p) {
        RelayManager m = INSTANCE;
        if (m == null || p == null) return false;
        m.pinned = null;
        m.setActive(p, "chosen in the catalog");
        RelayStats.closeOnPort(m.portA);
        RelayStats.closeOnPort(m.portB);
        return true;
    }

    /** Re-creates and restarts the two listeners so they pick up a changed bind
     *  address (loopback ↔ 0.0.0.0) when "share over LAN" is toggled. In-flight
     *  relayed connections keep running on their own sockets; only the accept
     *  sockets swap. SO_REUSEADDR lets us re-bind the same port immediately. */
    public void rebindListeners() {
        try {
            if (serverA != null) serverA.stop();
            if (serverB != null) serverB.stop();
            serverA = new RelayServer(portA, store, this, listener);
            serverB = new RelayServer(portB, store, this, listener);
            serverA.start();
            serverB.start();
        } catch (Exception e) {
            if (listener != null) listener.event("relay rebind failed: " + e.getMessage());
        }
    }

    /** Rebinds the running relay's listeners (no-op if the relay isn't running). */
    public static void rebindAll() {
        RelayManager m = INSTANCE;
        if (m != null) m.rebindListeners();
    }

    public void stop() {
        if (INSTANCE == this) INSTANCE = null;
        if (watchdog != null) { watchdog.stop(); watchdog = null; }
        if (prewarm != null) { prewarm.stop(); prewarm = null; }
        if (serverA != null) serverA.stop();
        if (serverB != null) serverB.stop();
    }

    /** Snapshot of the proxy ids that are the current "sticky" upstream of a port. */
    public static Set<Long> currentStickyProxyIds() {
        Set<Long> out = new HashSet<>();
        RelayManager m = INSTANCE;
        if (m != null) {
            ProxyEntry a = m.upstreamA.get();
            ProxyEntry b = m.upstreamB.get();
            if (a != null) out.add(a.id);
            if (b != null) out.add(b.id);
        }
        return out;
    }

    /** Returns the id of the user-pinned upstream, or 0 if no pin. */
    public static long currentPinnedId() {
        RelayManager m = INSTANCE;
        ProxyEntry p = (m != null) ? m.pinned : null;
        return p != null ? p.id : 0;
    }

    /**
     * Pins {@code p} as the preferred upstream for both ports so the next
     * Telegram connection lands on it; passing {@code null} clears the pin.
     * Pinned upstreams are immune to {@link #refreshStickies()} and survive
     * port-switch penalisation.
     */
    public static void pinUpstream(ProxyEntry p) {
        RelayManager m = INSTANCE;
        if (m == null) return;
        m.pinned = p;
        if (p != null) {
            m.upstreamA.set(p);
            m.upstreamB.set(p);
        }
    }

    /**
     * Called when a fresh Telegram connection lands on {@code port}. A switch
     * away from the previously-active port condemns that port's upstream.
     */
    public void onConnection(int port) {
        int prev = lastActivePort;
        if (prev != 0 && prev != port) {
            // Record the switch for bounce detection (even when pinned).
            synchronized (portSwitchTimes) {
                portSwitchTimes[portSwitchIdx] = System.currentTimeMillis();
                portSwitchIdx = (portSwitchIdx + 1) % portSwitchTimes.length;
            }
        }
        if (prev != 0 && prev != port && pinned == null) {
            ProxyEntry bad = ref(prev).getAndSet(null);
            if (bad != null) {
                penalize(bad);
                clearActive(bad.id, "Telegram switched relay port");
                String msg = "⟳ Telegram switched from port " + prev + " to " + port
                        + " — upstream " + bad.host + ":" + bad.port + " demoted in the rating";
                listener.event(msg);
                RelayLog.emit(msg);
            }
            // Previously we yanked every still-live socket on the abandoned
            // port here, but Telegram opens many sockets in parallel and
            // shifts between them freely — those weren't "zombies", they
            // were healthy sessions Telegram still planned to use. Closing
            // them caused the flood of red ✗ entries the user complained
            // about. Telegram drops what it no longer needs on its own.
        }
        lastActivePort = port;
        io.autoconnector.engine.traffic.ConnectBuffer.INSTANCE.noteInbound(port);
    }

    /**
     * True if Telegram has switched relay ports at least 2 times in the last
     * 20 seconds — it can't hold a connection, so the upstream picker should
     * stop reusing the same few top-rated hosts and fan out across the pool.
     */
    public boolean isPortBouncing() {
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (portSwitchTimes) {
            for (long t : portSwitchTimes) {
                if (t > 0 && now - t <= 20_000L) n++;
            }
        }
        return n >= 2;
    }

    /**
     * Re-picks sticky upstreams from the current top of the rating —
     * called after the periodic check of the "main" upstreams so a freshly-
     * dead sticky gets replaced immediately.
     */
    public void refreshStickies() {
        if (pinned != null) return;
        // Mode-aware: a VPN-on Wi-Fi network has a totally different
        // working set than bare LTE, so we pick from the current mode's
        // alive list first.
        io.autoconnector.engine.core.NetworkMode net =
                io.autoconnector.engine.core.NetworkMonitor.currentMode();
        List<ProxyEntry> top = (net != io.autoconnector.engine.core.NetworkMode.UNKNOWN)
                ? store.topAliveForMode(net, 20)
                : store.topAlive(20);
        if (top.isEmpty()) top = store.topAlive(20);
        if (top.isEmpty()) top = store.top(20);
        if (top.isEmpty()) return;
        ProxyEntry pickA = top.get(0);
        ProxyEntry pickB = top.size() > 1 ? top.get(1) : pickA;
        upstreamA.set(pickA);
        upstreamB.set(pickB);
    }

    /** Sticky upstream for a port; picks a fresh best one if none is set. */
    public ProxyEntry stickyUpstream(int port) {
        AtomicReference<ProxyEntry> ref = ref(port);
        ProxyEntry cur = ref.get();
        if (cur == null) {
            cur = chooseBest(other(port).get());
            if (cur != null) ref.set(cur);
        }
        return cur;
    }

    /** Confirms the upstream a connection actually settled on for a port. */
    public void setUpstream(int port, ProxyEntry p) {
        ref(port).set(p);
        io.autoconnector.engine.traffic.ConnectBuffer.INSTANCE.noteOutbound(port);
    }

    /** A connection failed to use this upstream — penalise and drop it.
     *  Pinned upstreams stay set but still receive a rating penalty. */
    public void markUpstreamBad(int port, ProxyEntry p) {
        if (pinned == null || p == null || pinned.id != p.id) {
            ref(port).compareAndSet(p, null);
        }
        if (p != null) clearActive(p.id, "failed");
        penalize(p);
    }

    private ProxyEntry chooseBest(ProxyEntry exclude) {
        List<ProxyEntry> top = store.top(20);
        for (ProxyEntry p : top) {
            if (exclude == null || p.id != exclude.id) return p;
        }
        return top.isEmpty() ? null : top.get(0);
    }

    private void penalize(ProxyEntry p) {
        try {
            Rating.record(p, false, -1, "relay failover");
            store.updateStats(p);
        } catch (Exception ignored) {}
    }

    private AtomicReference<ProxyEntry> ref(int port) {
        return port == portA ? upstreamA : upstreamB;
    }

    private AtomicReference<ProxyEntry> other(int port) {
        return port == portA ? upstreamB : upstreamA;
    }
}
