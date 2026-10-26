package io.autoconnector.engine.check;

import android.content.Context;

import io.autoconnector.engine.core.NetworkMode;
import io.autoconnector.engine.core.NetworkMonitor;
import io.autoconnector.engine.core.Prefs;
import io.autoconnector.engine.db.ProxyStore;
import io.autoconnector.engine.model.ProxyEntry;
import io.autoconnector.engine.net.HandshakeMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "AI optimizer": tries the app's settings for real on this network and keeps
 * the best one.
 *
 * <p>Today it experiments with the anti-DPI connection method (the way the
 * FakeTLS handshake is shaped): each candidate method is used to open a real
 * Telegram handshake through the same top proxies, and the method with the most
 * successful handshakes — ties broken by the fastest Telegram reply — is applied
 * to the relay and the scanner. It runs on demand ("Optimize now") and by itself
 * every hour and after a network change while Auto-tune is on. The live tuning of
 * timeouts / race width / breadth is done continuously by {@code AutoTuner}.
 */
public final class AutoOptimizer {

    public interface Log { void line(String s); }

    private static final HandshakeMode[] CANDIDATES = {
            HandshakeMode.AUTO_RANDOM, HandshakeMode.NORMAL, HandshakeMode.MIMIC_CHROME, HandshakeMode.MIMIC_FIREFOX,
            HandshakeMode.MIMIC_SAFARI, HandshakeMode.BIG_PADDING, HandshakeMode.SEGMENTED,
            HandshakeMode.SPLIT_BEFORE_SNI, HandshakeMode.RANDOM_PADDING,
    };
    private static final int HOSTS = 12;

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile String summary = "";
    private static volatile long lastRunAt = 0;
    private static volatile String progress = "";

    public static boolean isRunning() { return RUNNING.get(); }
    public static String summary() { return summary; }
    public static long lastRunAt() { return lastRunAt; }
    public static String progress() { return progress; }

    private static final class Score {
        final HandshakeMode mode;
        int ok;
        int tried;
        final List<Integer> ms = new ArrayList<>();
        Score(HandshakeMode m) { mode = m; }
        int median() {
            if (ms.isEmpty()) return Integer.MAX_VALUE / 2;
            List<Integer> c = new ArrayList<>(ms);
            Collections.sort(c);
            return c.get(c.size() / 2);
        }
        /** Higher is better: successes dominate, speed breaks ties. */
        double value() { return ok * 10_000.0 - Math.min(median(), 9_999); }
    }

    /** Runs one optimization (blocking). Returns false if one is already running. */
    public static boolean run(Context ctx, Log log) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "ai-opt");
            t.setDaemon(true);
            return t;
        });
        try {
            Prefs prefs = new Prefs(ctx);
            ProxyStore store = ProxyStore.get(ctx);
            NetworkMode mode = NetworkMonitor.currentMode();
            // Test hosts: the ones carrying Telegram right now (active + reserves)
            // first, then the best-rated FakeTLS hosts — the methods only differ
            // for FakeTLS ("ee") proxies.
            List<ProxyEntry> hosts = new ArrayList<>();
            io.autoconnector.engine.relay.RelayManager rm = io.autoconnector.engine.relay.RelayManager.INSTANCE;
            if (rm != null) {
                if (rm.activeEntry() != null && rm.activeEntry().isFakeTls()) hosts.add(rm.activeEntry());
                for (ProxyEntry p : rm.reserveEntries()) if (p.isFakeTls() && !contains(hosts, p)) hosts.add(p);
            }
            List<ProxyEntry> top = mode != NetworkMode.UNKNOWN
                    ? store.topAliveForMode(mode, 80) : store.topAlive(80);
            for (ProxyEntry p : top) {
                if (hosts.size() >= HOSTS) break;
                if (p.isFakeTls() && !contains(hosts, p)) hosts.add(p);
            }
            if (hosts.size() < 4) {
                summary = "🧠 Waiting for more live proxies";
                log.line(summary);
                return true;
            }
            log.line("🧠 AI optimizer: testing " + CANDIDATES.length + " connection methods on "
                    + hosts.size() + " top proxies…");
            List<Score> scores = new ArrayList<>();
            int step = 0;
            for (HandshakeMode m : CANDIDATES) {
                step++;
                progress = step + " / " + CANDIDATES.length + " · " + m.label;
                Score sc = new Score(m);
                List<Future<Integer>> fs = new ArrayList<>();
                for (ProxyEntry p : hosts) {
                    fs.add(pool.submit(() -> testOnce(p, m)));
                }
                for (Future<Integer> f : fs) {
                    sc.tried++;
                    try {
                        Integer ms = f.get(20, TimeUnit.SECONDS);
                        if (ms != null && ms > 0) { sc.ok++; sc.ms.add(ms); }
                    } catch (Exception e) {
                        f.cancel(true);
                    }
                }
                scores.add(sc);
                log.line("🧠   " + m.label + ": " + sc.ok + "/" + sc.tried + " ok"
                        + (sc.ms.isEmpty() ? "" : " · " + sc.median() + " ms"));
            }
            Score best = null;
            for (Score s : scores) if (best == null || s.value() > best.value()) best = s;
            HandshakeMode current = HandshakeMode.fromOrdinal(prefs.handshakeMode());
            Score cur = null;
            for (Score s : scores) if (s.mode == current) cur = s;
            // Apply only a clear winner that works on a fair share of the hosts.
            int need = Math.max(3, hosts.size() / 4);
            boolean apply = best != null && best.ok >= need && best.mode != current
                    && (cur == null || best.ok > cur.ok
                        || (best.ok == cur.ok && best.median() < cur.median() * 0.85));
            String bestTxt = best == null || best.ok == 0 ? "—"
                    : shortLabel(best.mode) + " " + best.ok + "/" + best.tried
                      + (best.ms.isEmpty() ? "" : " · " + best.median() + " ms");
            if (apply) {
                prefs.setHandshakeMode(best.mode.ordinal());
                summary = "✓ " + bestTxt + " — applied";
            } else if (best != null && best.mode == current && best.ok > 0) {
                summary = "✓ " + bestTxt + " — already in use";
            } else {
                summary = "Best: " + bestTxt + " · kept " + shortLabel(current);
            }
            log.line("🧠 AI optimizer: " + summary);
            return true;
        } catch (Throwable t) {
            summary = "🧠 Optimizer error: " + t.getClass().getSimpleName();
            log.line(summary);
            return true;
        } finally {
            pool.shutdownNow();
            lastRunAt = System.currentTimeMillis();
            progress = "";
            RUNNING.set(false);
        }
    }

    /** One real connect through {@code p} with method {@code m} (exactly like the
     *  relay does) + a Telegram request; returns the Telegram reply time in ms
     *  (handshake + request), or -1 on failure. */
    private static int testOnce(ProxyEntry p, HandshakeMode m) {
        io.autoconnector.engine.relay.UpstreamConnector.Channel ch = null;
        try {
            ch = io.autoconnector.engine.relay.UpstreamConnector.connect(
                    p, io.autoconnector.engine.net.Obfuscated2.TAG_INTERMEDIATE, 2, null, 443, 6000, m);
            long t0 = System.nanoTime();
            io.autoconnector.engine.net.MtHandshakeProbe.probe(
                    new io.autoconnector.engine.net.IntermediateTransport(ch.in, ch.out, ch.obf));
            int req = (int) ((System.nanoTime() - t0) / 1_000_000L);
            int hs = (ch.totalMs > 0 && ch.connectMs >= 0) ? ch.totalMs - ch.connectMs : 0;
            return Math.max(1, hs + req);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (ch != null) try { ch.socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean contains(List<ProxyEntry> l, ProxyEntry p) {
        for (ProxyEntry x : l) if (x.id == p.id) return true;
        return false;
    }

    /** "13 · Random padding" → "Random padding". */
    private static String shortLabel(HandshakeMode m) {
        String l = m.label;
        int i = l.indexOf('·');
        return i >= 0 ? l.substring(i + 1).trim() : l;
    }

    private AutoOptimizer() {}
}
