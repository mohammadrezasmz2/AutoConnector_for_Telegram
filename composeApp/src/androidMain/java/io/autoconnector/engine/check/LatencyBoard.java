package io.autoconnector.engine.check;

import io.autoconnector.engine.core.NetworkMode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speed-test results per (host, network mode), in milliseconds — available for
 * EVERY live host, unlike KB/s which needs real Telegram traffic through it.
 *
 * <ul>
 *   <li><b>ping</b> — TCP connect time to the proxy.</li>
 *   <li><b>Telegram response</b> — from the first byte we send until Telegram's
 *       datacenter answered through the proxy (TLS + MTProto round trip). This is
 *       the best "how fast is this proxy" number obtainable without a Telegram
 *       account, and it is what makes chats/media start quickly.</li>
 * </ul>
 * Fed by every successful full probe (scan + the 5-minute speed test), smoothed.
 */
public final class LatencyBoard {

    public static final class Entry {
        public volatile int pingMs = -1;
        public volatile int tgMs = -1;
        public volatile long at;
    }

    private static final Map<String, Entry> MAP = new ConcurrentHashMap<>();

    private static String key(long id, NetworkMode mode) {
        return id + "|" + (mode == null ? NetworkMode.UNKNOWN.code : mode.code);
    }

    public static void record(long proxyId, NetworkMode mode, int pingMs, int tgMs) {
        if (proxyId <= 0 || (pingMs <= 0 && tgMs <= 0)) return;
        Entry e = MAP.computeIfAbsent(key(proxyId, mode), k -> new Entry());
        synchronized (e) {
            if (pingMs > 0) e.pingMs = e.pingMs < 0 ? pingMs : (e.pingMs + pingMs) / 2;
            if (tgMs > 0) e.tgMs = e.tgMs < 0 ? tgMs : (e.tgMs + tgMs) / 2;
            e.at = System.currentTimeMillis();
        }
        if (MAP.size() > 20_000) MAP.clear();
    }

    /** Result for (id, mode), or null if never tested. */
    public static Entry get(long proxyId, NetworkMode mode) {
        return MAP.get(key(proxyId, mode));
    }

    public static void clear() { MAP.clear(); }

    private LatencyBoard() {}
}
