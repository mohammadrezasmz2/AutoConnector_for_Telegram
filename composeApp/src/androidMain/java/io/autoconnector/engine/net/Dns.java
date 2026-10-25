package io.autoconnector.engine.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Host-name resolution with a hard timeout and a small cache.
 *
 * <p>{@code new InetSocketAddress(host, port)} resolves the name synchronously
 * and has NO timeout — on a flaky network Android's resolver can block for
 * minutes, which froze a scan pass on its last probe ("checking 201 / 202"
 * forever). Every probe / upstream connect resolves through here instead.
 */
public final class Dns {

    private static final long OK_TTL_MS = 10 * 60_000L;
    private static final long FAIL_TTL_MS = 60_000L;

    private static final class Cached {
        final InetAddress addr;   // null = failed lookup
        final long until;
        Cached(InetAddress a, long u) { addr = a; until = u; }
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dns");
        t.setDaemon(true);
        return t;
    });

    /** Resolved address for (host, port); throws if the lookup fails or takes
     *  longer than {@code timeoutMs}. IP literals are returned as is. */
    public static InetSocketAddress resolve(String host, int port, int timeoutMs) throws IOException {
        if (isIpLiteral(host)) return new InetSocketAddress(InetAddress.getByName(host), port);
        String key = host.toLowerCase();
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(key);
        if (c != null && c.until > now) {
            if (c.addr == null) throw new IOException("DNS: " + host + " not found (cached)");
            return new InetSocketAddress(c.addr, port);
        }
        Future<InetAddress> f = POOL.submit(() -> InetAddress.getByName(host));
        try {
            InetAddress a = f.get(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
            CACHE.put(key, new Cached(a, now + OK_TTL_MS));
            if (CACHE.size() > 8192) CACHE.clear();
            return new InetSocketAddress(a, port);
        } catch (TimeoutException te) {
            f.cancel(true);
            CACHE.put(key, new Cached(null, now + FAIL_TTL_MS));
            throw new IOException("DNS timeout: " + host);
        } catch (InterruptedException ie) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("DNS interrupted: " + host);
        } catch (java.util.concurrent.ExecutionException ee) {
            CACHE.put(key, new Cached(null, now + FAIL_TTL_MS));
            Throwable cause = ee.getCause();
            throw new IOException("DNS: " + host + " — "
                    + (cause != null ? cause.getClass().getSimpleName() : "lookup failed"));
        }
    }

    static boolean isIpLiteral(String h) {
        if (h == null || h.isEmpty()) return false;
        if (h.indexOf(':') >= 0) return true;                 // IPv6
        for (int i = 0; i < h.length(); i++) {
            char ch = h.charAt(i);
            if (!(ch == '.' || (ch >= '0' && ch <= '9'))) return false;
        }
        return true;
    }

    private Dns() {}
}
