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

    private static volatile boolean guardEnabled = true;

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

        boolean burst = false;
        for (RelayStats.LiveConn c : RelayStats.liveConnections()) {
            if (c.meter.downloadingWithin(now, 3_000L)) burst = true;
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
        TrafficGovernor.tick(bps, burst);
        AutoTuner.tick(manager.isPortBouncing());
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
