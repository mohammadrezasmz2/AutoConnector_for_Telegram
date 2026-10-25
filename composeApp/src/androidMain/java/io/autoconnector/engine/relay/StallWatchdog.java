package io.autoconnector.engine.relay;

import io.autoconnector.engine.core.NetworkMode;
import io.autoconnector.engine.core.NetworkMonitor;
import io.autoconnector.engine.core.TrafficGovernor;
import io.autoconnector.engine.db.ProxyStore;
import io.autoconnector.engine.model.ProxyEntry;

/**
 * Once-a-second supervisor of the live relayed connections.
 *
 * <p><b>Dead upstreams.</b> Some public proxies complete the handshake but then
 * never forward a single byte. Telegram sits on such a socket showing
 * "Connecting…" / "Updating…" until the 3-minute idle timeout. If Telegram has
 * sent data and NOTHING came back for {@link #DEAD_MS}, the connection is cut
 * so Telegram reconnects at once and the relay picks another (faster) host.
 *
 * <p><b>Stalled downloads.</b> If a connection was downloading and then went
 * silent for {@link #STALL_MS} while Telegram kept asking (requests / pings
 * still going up), it is cut the same way — Telegram resumes the file on a new
 * connection through a better host.
 *
 * <p>Every cut demotes the host in {@link SpeedBoard}, counts as a real relay
 * failure (towards the host's cooldown) and drops it as the port's sticky
 * upstream. The same tick also feeds {@link TrafficGovernor} ("Telegram
 * first" scan throttling) and {@link AutoTuner}.
 */
public final class StallWatchdog {

    /** No reply at all from a fresh upstream for this long → dead. */
    static final long DEAD_MS = 12_000L;
    /** Download went silent (with requests pending) for this long → stalled. */
    static final long STALL_MS = 20_000L;

    /** An active host whose latest real download sample is below this is replaced. */
    static final double SLOW_SWITCH_KBPS = 40.0;
    /** Without a known-better host, allow at most this many slow-switches per 5 min. */
    private static final int MAX_EXPLORE_SWITCHES = 3;

    private static volatile boolean guardEnabled = true;
    private final java.util.ArrayDeque<Long> switchTimes = new java.util.ArrayDeque<>();

    public static void setGuardEnabled(boolean on) { guardEnabled = on; }
    public static boolean isGuardEnabled() { return guardEnabled; }

    private final RelayManager manager;
    private final ProxyStore store;
    private volatile boolean running = false;
    private Thread thread;
    private long prevDown = -1;

    public StallWatchdog(RelayManager manager, ProxyStore store) {
        this.manager = manager;
        this.store = store;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "relay-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (Throwable ignored) {}
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long down = RelayStats.bytesDown.get();
        long bps = prevDown < 0 ? 0 : Math.max(0, down - prevDown);
        prevDown = down;

        boolean tgActive = false;
        for (RelayStats.LiveConn c : RelayStats.liveConnections()) {
            // Telegram moved data through the relay just now (either direction).
            if (now - c.lastDataAt.get() < 3_000L && c.bytesUp.get() + c.bytesDown.get() > 0) tgActive = true;
            if (!guardEnabled) continue;
            if (c.upstreamProxyId <= 0 || c.killer == null || c.killReason != null) continue;
            if (manager.isPinned(c.upstreamProxyId)) continue;   // user forced this host

            long firstUp = c.firstUpAt.get();
            if (c.bytesDown.get() == 0 && firstUp > 0 && now - firstUp > DEAD_MS) {
                cut(c, "no reply from upstream in " + (DEAD_MS / 1000) + " s", true);
                continue;
            }
            long pend = c.pendingWriteAt.get();
            long lastDown = c.lastDownAt.get();
            if (c.meter.downloadingWithin(now, 90_000L)
                    && pend > 0 && now - pend > STALL_MS
                    && lastDown > 0 && now - lastDown > STALL_MS
                    && c.upWritesSinceDown.get() >= 2) {
                cut(c, "download stalled for " + (STALL_MS / 1000) + " s", false);
            }
        }
        TrafficGovernor.tick(bps, tgActive);
        if (guardEnabled) checkSlowActive(now);
        AutoTuner.tick(manager.isPortBouncing());
    }

    /**
     * Replaces the active host when its latest REAL download sample (measured
     * while Telegram was downloading through it) is slow. Its downloading sockets
     * are closed so Telegram moves to the newly elected host right away. Limited
     * so that on a network where every host is slow we don't keep thrashing.
     */
    private void checkSlowActive(long now) {
        ProxyEntry act = manager.activeEntry();
        if (act == null || manager.isPinned(act.id)) return;
        NetworkMode mode = NetworkMonitor.currentMode();
        SpeedBoard.Stat st = SpeedBoard.get(act.id, mode);
        if (st == null || st.lastSampleAt <= manager.activeSince()) return;   // no sample as active yet
        if (now - st.lastSampleAt > 30_000L || st.lastSampleKbps >= SLOW_SWITCH_KBPS) return;
        // Only when the host is CONSISTENTLY slow, not after one small sample.
        if (st.samples < 3 || st.kbps >= SLOW_SWITCH_KBPS) return;
        if (now - manager.activeSince() < 20_000L) return;                    // give it a fair chance
        while (!switchTimes.isEmpty() && now - switchTimes.peekFirst() > 5 * 60_000L) switchTimes.pollFirst();
        double cur = st.lastSampleKbps;
        boolean betterKnown = SpeedBoard.bestKbps(mode) > Math.max(2 * cur, SLOW_SWITCH_KBPS);
        if (!betterKnown && switchTimes.size() >= MAX_EXPLORE_SWITCHES) return;
        switchTimes.addLast(now);
        manager.clearActive(act.id, "slow: " + AutoTuner.fmtKbps(cur) + " while downloading");
        for (RelayStats.LiveConn c : RelayStats.liveConnections()) {
            if (c.upstreamProxyId != act.id || c.killer == null || c.killReason != null) continue;
            if (!c.meter.downloadingWithin(now, 30_000L) && now - c.lastDownAt.get() > 5_000L) continue;
            if (c.meter.peakKbps >= 2 * SLOW_SWITCH_KBPS) continue;   // this socket is doing fine
            c.killReason = "slow host — moving Telegram to a faster one";
            try { c.killer.run(); } catch (Throwable ignored) {}
        }
    }

    private void cut(RelayStats.LiveConn c, String reason, boolean dead) {
        c.killReason = reason;
        ProxyEntry p = c.upstreamEntry;
        NetworkMode mode = NetworkMonitor.currentMode();
        RelayLog.emit("✂ " + c.upstream + " — " + reason
                + " → closing so Telegram reconnects through another host");
        try {
            Runnable k = c.killer;
            if (k != null) k.run();
        } catch (Throwable ignored) {}
        if (dead) AutoTuner.onDead(); else AutoTuner.onStall();
        if (p != null) {
            SpeedBoard.stall(p, mode);
            try { store.markRelayFailure(p.id, mode, System.currentTimeMillis()); } catch (Throwable ignored) {}
            try { manager.markUpstreamBad(c.localPort, p); } catch (Throwable ignored) {}
        }
    }
}
