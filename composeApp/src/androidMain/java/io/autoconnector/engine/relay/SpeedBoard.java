package io.autoconnector.engine.relay;

import io.autoconnector.engine.core.NetworkMode;
import io.autoconnector.engine.db.ProxyStore;
import io.autoconnector.engine.model.ProxyEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-memory leaderboard of the REAL Telegram download speed each upstream host
 * delivered, kept separately per network mode (a host that flies on Wi-Fi may
 * crawl on LTE). Fed by {@link SpeedMeter} samples from live relay sessions and
 * by stall events from {@link StallWatchdog}; persisted to {@code proxy_speed}
 * off the hot path so the ranking survives restarts.
 *
 * <p>The relay asks this board to ORDER its upstream candidates: proven-fast
 * hosts first, a controlled share of unmeasured hosts for exploration (so new
 * fast hosts are discovered), and proven-slow / recently-stalled hosts last.
 * All reads are lock-free map lookups — no DB access in the relay path.
 */
public final class SpeedBoard {

    /** KB/s at or above which a host counts as "fast" for the fast-lane race. */
    public static final double FAST_KBPS = 150.0;
    /** KB/s below which a measured host is considered slow. */
    public static final double SLOW_KBPS = 40.0;
    /** A stall within this window heavily demotes the host. */
    private static final long STALL_MEMORY_MS = 10 * 60_000L;

    /** Per-(host, mode) speed record. */
    public static final class Stat {
        public final long proxyId;
        public final String mode;
        public volatile ProxyEntry entry;
        public volatile double kbps;       // EWMA of session download speed
        public volatile double bestKbps;   // best session sample
        public volatile int samples;
        public volatile int stalls;
        public volatile long lastAt;
        public volatile long lastStallAt;
        /** The most recent single session sample (not smoothed) and its time. */
        public volatile double lastSampleKbps = -1;
        public volatile long lastSampleAt;

        Stat(long proxyId, String mode) {
            this.proxyId = proxyId;
            this.mode = mode;
        }

        /** Speed used for ranking: EWMA, discounted for staleness and stalls. */
        public double effective(long now) {
            if (samples <= 0) return -1;
            double v = kbps;
            long age = now - lastAt;
            if (age > 24 * 3_600_000L) v *= 0.5;
            else if (age > 6 * 3_600_000L) v *= 0.75;
            if (lastStallAt > 0 && now - lastStallAt < STALL_MEMORY_MS) v *= 0.25;
            return v;
        }
    }

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static volatile ProxyStore store;
    private static volatile boolean loaded = false;
    private static final ExecutorService PERSIST = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "speed-persist");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static String key(long id, String mode) { return id + "|" + mode; }

    /** Binds the store and warms the board from disk once (idempotent). */
    public static void init(ProxyStore s) {
        if (s == null) return;
        store = s;
        if (loaded) return;
        synchronized (SpeedBoard.class) {
            if (loaded) return;
            try {
                for (ProxyStore.SpeedRow r : s.loadSpeeds()) {
                    Stat st = new Stat(r.proxy.id, r.mode);
                    st.entry = r.proxy;
                    st.kbps = r.kbps;
                    st.bestKbps = r.kbps;
                    st.samples = r.samples;
                    st.stalls = r.stalls;
                    st.lastAt = r.lastAt;
                    st.lastStallAt = r.lastStallAt;
                    STATS.put(key(st.proxyId, st.mode), st);
                }
            } catch (Throwable ignored) {}
            loaded = true;
        }
    }

    private static Stat stat(ProxyEntry p, NetworkMode mode, boolean create) {
        if (p == null) return null;
        String m = mode == null ? NetworkMode.UNKNOWN.code : mode.code;
        String k = key(p.id, m);
        Stat st = STATS.get(k);
        if (st == null && create) {
            st = new Stat(p.id, m);
            Stat prev = STATS.putIfAbsent(k, st);
            if (prev != null) st = prev;
        }
        if (st != null) st.entry = p;
        return st;
    }

    /** Returns the record for (id, mode) or null. */
    public static Stat get(long proxyId, NetworkMode mode) {
        return STATS.get(key(proxyId, mode == null ? NetworkMode.UNKNOWN.code : mode.code));
    }

    /** Effective KB/s for (id, mode), -1 when never measured. */
    public static double effectiveKbps(long proxyId, NetworkMode mode) {
        Stat st = get(proxyId, mode);
        return st == null ? -1 : st.effective(System.currentTimeMillis());
    }

    /** Folds one measured session speed into the host's record. */
    public static void record(ProxyEntry p, NetworkMode mode, double kbps) {
        if (p == null || p.id <= 0 || !(kbps > 0)) return;
        long now = System.currentTimeMillis();
        Stat st = stat(p, mode, true);
        synchronized (st) {
            st.kbps = st.samples == 0 ? kbps : st.kbps * 0.6 + kbps * 0.4;
            if (kbps > st.bestKbps) st.bestKbps = kbps;
            st.samples++;
            st.lastAt = now;
            st.lastSampleKbps = kbps;
            st.lastSampleAt = now;
        }
        persist(st);
        AutoTuner.onSpeedSample(kbps);
    }

    /** Records a stall / dead-upstream event: the host is demoted for a while. */
    public static void stall(ProxyEntry p, NetworkMode mode) {
        if (p == null || p.id <= 0) return;
        long now = System.currentTimeMillis();
        Stat st = stat(p, mode, true);
        synchronized (st) {
            st.stalls++;
            st.lastStallAt = now;
            if (st.samples > 0) st.kbps *= 0.5;
        }
        persist(st);
    }

    /** A MEASURED host failed to even connect: demote it for a while (no stall
     *  counted — it may just be a transient refusal). Unmeasured hosts are left
     *  to the regular cooldown logic. */
    public static void connectFailed(ProxyEntry p, NetworkMode mode) {
        if (p == null) return;
        Stat st = get(p.id, mode);
        if (st == null || st.samples <= 0) return;
        st.lastStallAt = System.currentTimeMillis();
    }

    private static void persist(Stat st) {
        final ProxyStore s = store;
        if (s == null) return;
        final double kbps = st.kbps;
        final int samples = st.samples, stalls = st.stalls;
        final long lastAt = st.lastAt, lastStallAt = st.lastStallAt;
        try {
            PERSIST.submit(() -> {
                try {
                    s.saveSpeed(st.proxyId, st.mode, kbps, samples, stalls, lastAt, lastStallAt);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** Fastest known hosts for a mode (effective speed desc), stall-free lately. */
    public static List<ProxyEntry> topFast(NetworkMode mode, int limit, double minKbps) {
        long now = System.currentTimeMillis();
        String m = mode == null ? NetworkMode.UNKNOWN.code : mode.code;
        List<Stat> list = new ArrayList<>();
        for (Stat st : STATS.values()) {
            if (!st.mode.equals(m) || st.entry == null) continue;
            if (st.effective(now) >= minKbps) list.add(st);
        }
        list.sort((a, b) -> Double.compare(b.effective(now), a.effective(now)));
        List<ProxyEntry> out = new ArrayList<>();
        for (Stat st : list) {
            out.add(st.entry);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** How many hosts are currently proven fast on this mode. */
    public static int fastCount(NetworkMode mode) {
        long now = System.currentTimeMillis();
        String m = mode == null ? NetworkMode.UNKNOWN.code : mode.code;
        int n = 0;
        for (Stat st : STATS.values()) {
            if (st.mode.equals(m) && st.effective(now) >= FAST_KBPS) n++;
        }
        return n;
    }

    /** Best effective speed known on this mode (KB/s), -1 if none. */
    public static double bestKbps(NetworkMode mode) {
        long now = System.currentTimeMillis();
        String m = mode == null ? NetworkMode.UNKNOWN.code : mode.code;
        double best = -1;
        for (Stat st : STATS.values()) {
            if (st.mode.equals(m)) best = Math.max(best, st.effective(now));
        }
        return best;
    }

    /** Result of {@link #order}: the full ordered list plus its fast head. */
    public static final class Ordering {
        public final List<ProxyEntry> ordered;
        /** Proven-fast hosts at the head of {@link #ordered} (may be empty). */
        public final List<ProxyEntry> fastHead;
        public final boolean exploring;

        Ordering(List<ProxyEntry> ordered, List<ProxyEntry> fastHead, boolean exploring) {
            this.ordered = ordered;
            this.fastHead = fastHead;
            this.exploring = exploring;
        }
    }

    /**
     * Orders upstream candidates by measured download speed.
     *
     * @param candidates alive candidates (any order — unmeasured ones keep it)
     * @param explore    probability [0..1] of putting unmeasured hosts first so
     *                   their speed gets learned
     */
    public static Ordering order(List<ProxyEntry> candidates, NetworkMode mode, double explore) {
        long now = System.currentTimeMillis();
        List<ProxyEntry> fast = new ArrayList<>();
        List<ProxyEntry> mid = new ArrayList<>();
        List<ProxyEntry> unknown = new ArrayList<>();
        List<ProxyEntry> slow = new ArrayList<>();
        Map<Long, Double> eff = new java.util.HashMap<>();
        double best = -1;
        for (ProxyEntry p : candidates) {
            Stat st = get(p.id, mode);
            double e = st == null ? -1 : st.effective(now);
            eff.put(p.id, e);
            if (e > best) best = e;
        }
        // "Fast" is relative to what this network can do right now (on a slow
        // network nothing reaches 150 KB/s, but the best hosts still matter).
        double fastBar = Math.max(SLOW_KBPS, Math.min(FAST_KBPS, best * 0.5));
        for (ProxyEntry p : candidates) {
            double e = eff.get(p.id);
            if (e < 0) unknown.add(p);
            else if (e >= fastBar) fast.add(p);
            else if (e >= SLOW_KBPS) mid.add(p);
            else slow.add(p);
        }
        Collections.sort(fast, (a, b) -> Double.compare(eff.get(b.id), eff.get(a.id)));
        Collections.sort(mid, (a, b) -> Double.compare(eff.get(b.id), eff.get(a.id)));
        Collections.sort(slow, (a, b) -> Double.compare(eff.get(b.id), eff.get(a.id)));

        boolean exploring = !unknown.isEmpty()
                && (fast.isEmpty() || ThreadLocalRandom.current().nextDouble() < explore);
        List<ProxyEntry> out = new ArrayList<>(candidates.size());
        List<ProxyEntry> head = new ArrayList<>();
        if (exploring) {
            out.addAll(unknown);
            out.addAll(fast);
            out.addAll(mid);
        } else {
            for (int i = 0; i < Math.min(4, fast.size()); i++) head.add(fast.get(i));
            out.addAll(fast);
            out.addAll(unknown);
            out.addAll(mid);
        }
        out.addAll(slow);
        return new Ordering(out, head, exploring);
    }

    public static void clearAll() { STATS.clear(); }

    public static void clearMode(NetworkMode mode) {
        if (mode == null) return;
        STATS.values().removeIf(st -> st.mode.equals(mode.code));
    }

    private SpeedBoard() {}
}
