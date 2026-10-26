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
    /** Telegram's requests on an idle socket unanswered for this long → reconnect. */
    static final long UNANSWERED_MS = 25_000L;

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
    private long prevUp = -1;
    private long lastReserveAt = 0;
    private static final long TG_BUSY_BPS = 8L * 1024;

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
        long upNow = RelayStats.bytesUp.get();
        long upBps = prevUp < 0 ? 0 : Math.max(0, upNow - prevUp);
        prevUp = upNow;

        // "Telegram is busy" = a real transfer right now (≥ 8 KB/s either way).
        // Telegram's idle pings and chat updates are far below that, so the scan
        // is no longer throttled the whole time Telegram is merely open.
        boolean tgActive = bps >= TG_BUSY_BPS || upBps >= TG_BUSY_BPS;
        for (RelayStats.LiveConn c : RelayStats.liveConnections()) {
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
                continue;
            }
            // Unanswered: Telegram keeps sending (pings / requests) but the
            // upstream stopped answering long ago. Seen in logs as sessions with
            // "↓178 B" that sat silent for minutes while Telegram showed
            // "Connecting… / Updating…". Close the socket so Telegram reconnects
            // at once (through the active host or a fresh one). Only this socket
            // is closed — a really broken host is caught by the dead rule on
            // the reconnect.
            if (pend > 0 && now - pend > UNANSWERED_MS
                    && (lastDown <= 0 || now - lastDown > UNANSWERED_MS)
                    && c.upWritesSinceDown.get() >= 2) {
                c.killReason = "no answer for " + (UNANSWERED_MS / 1000) + " s";
                RelayLog.emit("✂ " + c.upstream + " — Telegram's requests unanswered for "
                        + (UNANSWERED_MS / 1000) + " s → reconnecting this socket");
                try { Runnable k = c.killer; if (k != null) k.run(); } catch (Throwable ignored) {}
            }
        }
        TrafficGovernor.tick(bps, tgActive);
        if (guardEnabled) checkSlowActive(now);
        if (now - lastReserveAt >= 5_000L) {
            lastReserveAt = now;
            try { manager.refreshReserves(now); } catch (Throwable ignored) {}
            try { manager.considerUpgrade(now); } catch (Throwable ignored) {}
        }
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
