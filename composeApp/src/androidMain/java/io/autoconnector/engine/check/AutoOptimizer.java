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
    private static final int ROUNDS = 2;
    /** Scan parallelism levels tried in step 2. */
    private static final int[] CONC_LEVELS = {8, 16, 32, 48};
    private static final int CONC_SAMPLE = 40;

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
            // Test hosts: FakeTLS ("ee") proxies PROVEN to work — the methods only
            // differ for FakeTLS. "Alive" in the scan also counts half-open
            // ("≈") hosts that never complete a handshake; testing on those made
            // every method score the same (2/24). Order: active + reserves, then
            // hosts that carried Telegram traffic (KB/s known), then hosts whose
            // full speed test passed in the last 30 min, then the rest.
            List<ProxyEntry> hosts = new ArrayList<>();
            io.autoconnector.engine.relay.RelayManager rm = io.autoconnector.engine.relay.RelayManager.INSTANCE;
            if (rm != null) {
                if (rm.activeEntry() != null && rm.activeEntry().isFakeTls()) hosts.add(rm.activeEntry());
                for (ProxyEntry p : rm.reserveEntries()) if (p.isFakeTls() && !contains(hosts, p)) hosts.add(p);
            }
            long now0 = System.currentTimeMillis();
            List<Long> measuredIds = new ArrayList<>();
            for (io.autoconnector.engine.relay.SpeedBoard.Stat st : io.autoconnector.engine.relay.SpeedBoard.snapshot()) {
                if (st.mode.equals(mode.code) && st.samples > 0) measuredIds.add(st.proxyId);
            }
            if (!measuredIds.isEmpty()) {
                for (ProxyEntry p : store.proxiesByIds(measuredIds)) {
                    if (hosts.size() >= HOSTS) break;
                    if (p.isFakeTls() && !contains(hosts, p)) hosts.add(p);
                }
            }
            List<ProxyEntry> top = mode != NetworkMode.UNKNOWN
                    ? store.topAliveForMode(mode, 200) : store.topAlive(200);
            for (ProxyEntry p : top) {
                if (hosts.size() >= HOSTS) break;
                LatencyBoard.Entry e = LatencyBoard.get(p.id, mode);
                boolean proven = e != null && e.tgMs > 0 && now0 - e.at < 30 * 60_000L;
                if (proven && p.isFakeTls() && !contains(hosts, p)) hosts.add(p);
            }
            // No fallback to merely "alive" hosts: those are mostly half-open
            // FakeTLS servers that reject every method (EOF / alert), which made
            // all methods score the same 2/24.
            if (hosts.size() < 4) {
                summary = "Connection method: not enough working FakeTLS proxies to compare · kept "
                        + shortLabel(HandshakeMode.fromOrdinal(prefs.handshakeMode()));
                String concTxt = tuneScanParallelism(prefs, provenSample(top, mode), 
                        HandshakeMode.fromOrdinal(prefs.handshakeMode()), log);
                if (concTxt != null) summary = summary + " · " + concTxt;
                log.line("🧠 AI optimizer: " + summary);
                return true;
            }
            LAST_ERR.clear();
            StringBuilder hl = new StringBuilder();
            for (ProxyEntry p : hosts) { if (hl.length() > 0) hl.append(", "); hl.append(p.host); }
            log.line("🧠 AI optimizer: testing " + CANDIDATES.length + " connection methods on "
                    + hosts.size() + " proxies: " + hl);
            List<Score> scores = new ArrayList<>();
            int step = 0;
            for (HandshakeMode m : CANDIDATES) {
                step++;
                progress = step + " / " + CANDIDATES.length + " · " + m.label;
                Score sc = new Score(m);
                List<Future<Integer>> fs = new ArrayList<>();
                // Two rounds per method so one lucky/unlucky connect doesn't decide.
                for (int round = 0; round < ROUNDS; round++) {
                    for (ProxyEntry p : hosts) {
                        fs.add(pool.submit(() -> testOnce(p, m)));
                    }
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
            // Which hosts never answered, and why (helps tell a bad host from a bad method).
            if (!LAST_ERR.isEmpty()) {
                StringBuilder eb = new StringBuilder();
                int n = 0;
                for (ProxyEntry p : hosts) {
                    String e = LAST_ERR.get(p.id);
                    if (e == null) continue;
                    if (n++ > 0) eb.append(" · ");
                    eb.append(p.host).append(": ").append(e);
                    if (n >= 6) break;
                }
                log.line("🧠   failures: " + eb);
            }
            Score best = null;
            for (Score s : scores) if (best == null || s.value() > best.value()) best = s;
            int minOk = Integer.MAX_VALUE, maxOk = 0;
            for (Score s : scores) { minOk = Math.min(minOk, s.ok); maxOk = Math.max(maxOk, s.ok); }
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
                if (prefs.dpiApplyProbes()) HealthChecker.setDefaultProbeMode(best.mode);
                summary = "✓ " + bestTxt + " — applied";
            } else if (best != null && best.mode == current && best.ok > 0) {
                summary = "✓ " + bestTxt + " — already in use";
            } else if (maxOk - minOk <= 1) {
                summary = "All methods equal (" + maxOk + "/" + (best != null ? best.tried : 0)
                        + ") · kept " + shortLabel(current);
            } else {
                summary = "Best: " + bestTxt + " · kept " + shortLabel(current);
            }
            // Step 2: scan parallelism. The same sample of live hosts is probed at
            // several parallelism levels; the highest level that doesn't lose
            // answers is kept (too many parallel probes on a weak network make
            // healthy proxies time out).
            HandshakeMode useMode = apply ? best.mode : current;
            String concTxt = tuneScanParallelism(prefs, provenSample(top, mode), useMode, log);
            if (concTxt != null) summary = summary + " · " + concTxt;
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
    /** Last failure reason per host (diagnostics for the log). */
    private static final java.util.Map<Long, String> LAST_ERR = new java.util.concurrent.ConcurrentHashMap<>();

    private static int testOnce(ProxyEntry p, HandshakeMode m) {
        io.autoconnector.engine.relay.UpstreamConnector.Channel ch = null;
        try {
            // Same transport Telegram uses for this secret: FakeTLS (mtg) servers
            // accept only padded-intermediate (0xdddddddd) — the plain tag made
            // every test fail (0/12 for every method).
            int tag = io.autoconnector.engine.net.Obfuscated2.tagForSecret(p.secret);
            ch = io.autoconnector.engine.relay.UpstreamConnector.connect(p, tag, 2, null, 443, 6000, m);
            long t0 = System.nanoTime();
            io.autoconnector.engine.net.MtHandshakeProbe.probe(
                    new io.autoconnector.engine.net.IntermediateTransport(ch.in, ch.out, ch.obf,
                            tag == io.autoconnector.engine.net.Obfuscated2.TAG_PADDED));
            int req = (int) ((System.nanoTime() - t0) / 1_000_000L);
            int hs = (ch.totalMs > 0 && ch.connectMs >= 0) ? ch.totalMs - ch.connectMs : 0;
            return Math.max(1, hs + req);
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            LAST_ERR.put(p.id, msg.length() > 60 ? msg.substring(0, 60) : msg);
            return -1;
        } finally {
            if (ch != null) try { ch.socket.close(); } catch (Throwable ignored) {}
        }
    }

    /** Hosts whose full speed test passed in the last 30 min (any proxy type) —
     *  the parallelism test must measure the network, not dead hosts. */
    private static List<ProxyEntry> provenSample(List<ProxyEntry> top, NetworkMode mode) {
        long now = System.currentTimeMillis();
        List<ProxyEntry> out = new ArrayList<>();
        for (ProxyEntry p : top) {
            LatencyBoard.Entry e = LatencyBoard.get(p.id, mode);
            if (e != null && e.tgMs > 0 && now - e.at < 30 * 60_000L) out.add(p);
        }
        return out;
    }

    /** Returns a short result ("scan ×16"), or null if not enough hosts. */
    private static String tuneScanParallelism(Prefs prefs, List<ProxyEntry> pool, HandshakeMode mode, Log log) {
        List<ProxyEntry> sample = new ArrayList<>(pool);
        Collections.shuffle(sample);
        if (sample.size() > CONC_SAMPLE) sample = new ArrayList<>(sample.subList(0, CONC_SAMPLE));
        if (sample.size() < 15) return null;
        int[] ok = new int[CONC_LEVELS.length];
        long[] took = new long[CONC_LEVELS.length];
        for (int i = 0; i < CONC_LEVELS.length; i++) {
            int c = CONC_LEVELS[i];
            progress = "scan " + (i + 1) + " / " + CONC_LEVELS.length + " · " + c + " parallel";
            ExecutorService ex = Executors.newFixedThreadPool(c, r -> {
                Thread t = new Thread(r, "ai-scan");
                t.setDaemon(true);
                return t;
            });
            long t0 = System.currentTimeMillis();
            List<Future<Boolean>> fs = new ArrayList<>();
            for (ProxyEntry p : sample) {
                fs.add(ex.submit(() -> {
                    HealthChecker hc = new HealthChecker(3500, 8000);
                    hc.setProbeMode(mode);
                    return hc.check(p).grade == HealthChecker.Grade.FULL;
                }));
            }
            for (Future<Boolean> f : fs) {
                try { if (Boolean.TRUE.equals(f.get(30, TimeUnit.SECONDS))) ok[i]++; }
                catch (Exception e) { f.cancel(true); }
            }
            took[i] = System.currentTimeMillis() - t0;
            ex.shutdownNow();
            log.line("🧠   scan " + c + " parallel: " + ok[i] + "/" + sample.size() + " ok in "
                    + (took[i] / 1000) + " s");
        }
        int maxOk = 0;
        for (int v : ok) maxOk = Math.max(maxOk, v);
        if (maxOk == 0) return null;
        int pick = CONC_LEVELS[0];
        for (int i = 0; i < CONC_LEVELS.length; i++) {
            if (ok[i] >= maxOk - 2) pick = CONC_LEVELS[i];
        }
        if (pick != prefs.checkConcurrency()) {
            prefs.setCheckConcurrency(pick);
            return "scan ×" + pick + " applied";
        }
        return "scan ×" + pick;
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
