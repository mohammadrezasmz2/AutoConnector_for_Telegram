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
            io.autoconnector.engine.check.HealthChecker.setDefaultProbeMode(p.dpiApplyProbes()
                    ? io.autoconnector.engine.net.HandshakeMode.fromOrdinal(p.handshakeMode())
                    : io.autoconnector.engine.net.HandshakeMode.NORMAL);
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

    // --- Reserve list · speed scouting · automatic upgrade ----------------------
    //
    // Speeds are only known for hosts that carried real Telegram traffic, and with
    // session affinity every socket goes to the active host — so before, only ONE
    // host ever got measured. Now:
    //  * SCOUTING: once the active host is settled, every 2nd new Telegram socket
    //    goes to a candidate (a top-rated host not measured yet, or a reserve whose
    //    number is getting old) so its real speed gets measured. If the candidate
    //    doesn't answer within 2.5 s the socket falls back to the active host.
    //  * RESERVES: the fastest measured hosts (up to RESERVE_MAX) are kept ready;
    //    when the active host fails, the election tries them first.
    //  * UPGRADE: if a reserve is clearly faster than the active host
    //    (≥ 1.4× and ≥ +40 KB/s, measured recently), it becomes the active host
    //    automatically; the previous one stays in the reserve list.

    public static final int RESERVE_MAX = 5;
    private static final long SCOUT_REPEAT_MS = 10 * 60_000L;
    private static final long UPGRADE_GAP_MS = 60_000L;
    private volatile List<ProxyEntry> reserves = java.util.Collections.emptyList();
    private volatile List<ProxyEntry> scoutQueue = java.util.Collections.emptyList();
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> scoutedAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger scoutTick =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastUpgradeAt = 0;
    private volatile String lastReserveSig = "";

    /** Reserve hosts, fastest first (never includes the active host). */
    public List<ProxyEntry> reserveEntries() { return reserves; }

    /** Reserve ids in order, for the UI (empty when the relay is off). */
    public static List<Long> reserveIds() {
        RelayManager m = INSTANCE;
        List<Long> out = new java.util.ArrayList<>();
        if (m == null) return out;
        for (ProxyEntry p : m.reserves) out.add(p.id);
        return out;
    }

    /** Rebuilds the reserve list and the scouting queue (watchdog, every 5 s). */
    public void refreshReserves(long now) {
        io.autoconnector.engine.core.NetworkMode mode =
                io.autoconnector.engine.core.NetworkMonitor.currentMode();
        String m = mode.code;
        ProxyEntry act = active;
        long actId = act != null ? act.id : 0;
        // Reserves: measured on this network in the last hour, not slow.
        List<SpeedBoard.Stat> fast = new java.util.ArrayList<>();
        for (SpeedBoard.Stat st : SpeedBoard.snapshot()) {
            if (!st.mode.equals(m) || st.proxyId == actId || st.samples <= 0) continue;
            if (now - st.lastAt > 60 * 60_000L) continue;
            if (st.effective(now) < SpeedBoard.SLOW_KBPS) continue;
            fast.add(st);
        }
        fast.sort((a, b) -> Double.compare(b.effective(now), a.effective(now)));
        List<Long> ids = new java.util.ArrayList<>();
        for (SpeedBoard.Stat st : fast) {
            ids.add(st.proxyId);
            if (ids.size() >= RESERVE_MAX * 2) break;
        }
        List<ProxyEntry> res = new java.util.ArrayList<>();
        if (!ids.isEmpty()) {
            Set<Long> cooled = new HashSet<>();
            try { cooled = store.cooledAmong(ids); } catch (Throwable ignored) {}
            java.util.Map<Long, ProxyEntry> byId = new java.util.HashMap<>();
            try { for (ProxyEntry p : store.proxiesByIds(ids)) byId.put(p.id, p); } catch (Throwable ignored) {}
            for (Long id : ids) {
                ProxyEntry p = byId.get(id);
                if (p == null || cooled.contains(id)) continue;
                res.add(p);
                if (res.size() >= RESERVE_MAX) break;
            }
        }
        reserves = res;
        StringBuilder sig = new StringBuilder();
        for (ProxyEntry p : res) sig.append(p.id).append(',');
        if (!sig.toString().equals(lastReserveSig)) {
            lastReserveSig = sig.toString();
            if (!res.isEmpty()) {
                StringBuilder sb = new StringBuilder("⛁ reserve (" + res.size() + "): ");
                for (int i = 0; i < res.size(); i++) {
                    ProxyEntry p = res.get(i);
                    if (i > 0) sb.append(" · ");
                    sb.append(p.host).append(' ')
                            .append(AutoTuner.fmtKbps(SpeedBoard.effectiveKbps(p.id, mode)));
                }
                RelayLog.emit(sb.toString());
            }
        }
        // Scouting queue: stale reserves first, then hosts never measured on this
        // network — but ONLY ones whose speed test (a real Telegram handshake
        // through them) passed in the last 5 minutes, quickest first. A scout
        // that doesn't answer delays that one socket, so only likely winners
        // are tried.
        List<ProxyEntry> q = new java.util.ArrayList<>();
        for (ProxyEntry p : res) {
            SpeedBoard.Stat st = SpeedBoard.get(p.id, mode);
            if (st != null && now - st.lastAt > 15 * 60_000L && freshTest(p.id, mode, now) > 0) q.add(p);
        }
        try {
            List<ProxyEntry> top = mode != io.autoconnector.engine.core.NetworkMode.UNKNOWN
                    ? store.topAliveForMode(mode, 80) : store.topAlive(80);
            List<ProxyEntry> cand = new java.util.ArrayList<>();
            for (ProxyEntry p : top) {
                if (p.id == actId) continue;
                SpeedBoard.Stat st = SpeedBoard.get(p.id, mode);
                if (st != null && st.samples > 0) continue;
                if (freshTest(p.id, mode, now) <= 0) continue;
                cand.add(p);
            }
            cand.sort((a, b) -> Integer.compare(freshTest(a.id, mode, now), freshTest(b.id, mode, now)));
            for (ProxyEntry p : cand) {
                q.add(p);
                if (q.size() >= 12) break;
            }
        } catch (Throwable ignored) {}
        scoutQueue = q;
    }

    /** Telegram reply time (ms) from a speed test in the last 5 min, else -1. */
    private static int freshTest(long id, io.autoconnector.engine.core.NetworkMode mode, long now) {
        io.autoconnector.engine.check.LatencyBoard.Entry e =
                io.autoconnector.engine.check.LatencyBoard.get(id, mode);
        if (e == null || e.tgMs <= 0 || now - e.at > 5 * 60_000L) return -1;
        return e.tgMs;
    }

    /**
     * Candidate host for this new Telegram socket to measure, or null to use the
     * active host as usual. Every 2nd socket, once the active host is settled.
     */
    public ProxyEntry nextScout() {
        ProxyEntry act = active;
        if (act == null || pinned != null || !AutoTuner.isEnabled()) return null;
        long now = System.currentTimeMillis();
        if (now - activeSince < 15_000L) return null;
        if (scoutTick.incrementAndGet() % 2 != 0) return null;
        for (ProxyEntry p : scoutQueue) {
            if (p.id == act.id) continue;
            Long at = scoutedAt.get(p.id);
            if (at != null && now - at < SCOUT_REPEAT_MS) continue;
            scoutedAt.put(p.id, now);
            return p;
        }
        return null;
    }

    // --- Load spreading --------------------------------------------------------
    // A log showed 40% of sessions closing with ↓0 B: Telegram opens many parallel
    // sockets and session affinity forced ALL of them onto the single active host,
    // which saturated and let Telegram drop the extras. When the active host is
    // already carrying several concurrent sockets, hand some NEW sockets to a
    // proven reserve instead (round-robin), so the parallel load is split across a
    // few good proxies. The socket still falls back to the active host if the
    // reserve doesn't answer, so this never costs a connection.
    private static final int SPREAD_WHEN_CONNS = 3;   // active this busy → start spreading
    private final java.util.concurrent.atomic.AtomicInteger spreadTick =
            new java.util.concurrent.atomic.AtomicInteger();

    /** A reserve to offload this new socket onto, or null to use the active host. */
    public ProxyEntry nextSpread() {
        ProxyEntry act = active;
        if (act == null || pinned != null || !AutoTuner.isEnabled()) return null;
        if (System.currentTimeMillis() - activeSince < 15_000L) return null;
        List<ProxyEntry> res = reserves;
        if (res.isEmpty()) return null;
        // How many sockets is the active host currently carrying?
        int onActive = 0;
        for (RelayStats.LiveConn c : RelayStats.liveConnections()) {
            if (c.upstreamProxyId == act.id) onActive++;
        }
        if (onActive < SPREAD_WHEN_CONNS) return null;   // not busy — keep affinity
        // Round-robin across the proven reserves so the extra sockets fan out.
        ProxyEntry r = res.get(Math.floorMod(spreadTick.getAndIncrement(), res.size()));
        return (r != null && r.id != act.id) ? r : null;
    }

    /** Switches to a reserve that is clearly faster than the active host. */
    public void considerUpgrade(long now) {
        ProxyEntry act = active;
        if (act == null || pinned != null || !AutoTuner.isEnabled()) return;
        if (now - activeSince < 30_000L || now - lastUpgradeAt < UPGRADE_GAP_MS) return;
        io.autoconnector.engine.core.NetworkMode mode =
                io.autoconnector.engine.core.NetworkMonitor.currentMode();
        SpeedBoard.Stat a = SpeedBoard.get(act.id, mode);
        if (a == null || a.samples <= 0) return;       // active not measured yet
        double ea = a.effective(now);
        for (ProxyEntry r : reserves) {
            SpeedBoard.Stat s = SpeedBoard.get(r.id, mode);
            if (s == null || s.samples <= 0) continue;
            // Proven: a sustained sample this session, or a recent measurement.
            // (Loosened — one fresh sample in the last 20 min now counts, so a
            // genuinely faster reserve actually gets promoted instead of the relay
            // sitting on a slower host forever.)
            boolean proven = (s.lastSampleAt > 0 && now - s.lastSampleAt < 45 * 60_000L)
                    || (s.samples >= 2 && now - s.lastAt < 45 * 60_000L)
                    || (s.samples >= 1 && now - s.lastAt < 20 * 60_000L);
            if (!proven) continue;
            double er = s.effective(now);
            if (er < Math.max(ea * 1.18, ea + 25)) continue;
            lastUpgradeAt = now;
            setActive(r, "faster: " + AutoTuner.fmtKbps(er) + " vs " + AutoTuner.fmtKbps(ea)
                    + " — previous host kept in reserve");
            // Idle sockets on the old host reconnect through the new one; sockets
            // in the middle of a transfer are left alone to finish.
            for (RelayStats.LiveConn c : RelayStats.liveConnections()) {
                if (c.upstreamProxyId != act.id || c.killer == null || c.killReason != null) continue;
                if (now - c.lastDataAt.get() < 3_000L) continue;
                c.killReason = "moving to a faster host";
                try { c.killer.run(); } catch (Throwable ignored) {}
            }
            return;
        }
    }

    // --- Unstable active host --------------------------------------------------
    // A log showed the active host closing Telegram's sockets again and again
    // (EOF right after the first reply, or after a few KB) for 13 minutes while
    // it stayed active, because no single socket looked "dead". Three hang-ups by
    // the proxy within 3 minutes now drop it as the active host.
    private static final int HANGUPS_TO_DROP = 3;
    private static final long HANGUP_WINDOW_MS = 3 * 60_000L;
    private final java.util.ArrayDeque<Long> hangups = new java.util.ArrayDeque<>();
    private long hangupsFor = 0;

    /** The proxy (not Telegram) ended a session. */
    public void onUpstreamHangup(ProxyEntry p, long downBytes, long durMs) {
        ProxyEntry act = active;
        if (p == null || act == null || act.id != p.id || pinned != null) return;
        // A long, productive session ending is normal; short or near-empty ones aren't.
        if (downBytes > 2 * 1024 * 1024 || durMs > 5 * 60_000L) return;
        long now = System.currentTimeMillis();
        synchronized (hangups) {
            if (hangupsFor != p.id) { hangups.clear(); hangupsFor = p.id; }
            hangups.addLast(now);
            while (!hangups.isEmpty() && now - hangups.peekFirst() > HANGUP_WINDOW_MS) hangups.pollFirst();
            if (hangups.size() < HANGUPS_TO_DROP) return;
            hangups.clear();
        }
        io.autoconnector.engine.core.NetworkMode mode = io.autoconnector.engine.core.NetworkMonitor.currentMode();
        SpeedBoard.stall(p, mode);
        try { store.markRelayFailure(p.id, mode, now); } catch (Throwable ignored) {}
        clearActive(p.id, "unstable: the proxy closed Telegram's connection " + HANGUPS_TO_DROP
                + "× in " + (HANGUP_WINDOW_MS / 60_000L) + " min");
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
