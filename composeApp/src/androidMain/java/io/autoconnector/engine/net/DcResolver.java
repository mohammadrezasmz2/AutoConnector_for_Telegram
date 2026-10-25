package io.autoconnector.engine.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which Telegram datacenter (DC 1..5) a relayed connection must be sent
 * to, from the address Telegram asked for over SOCKS5.
 *
 * <p><b>Why this matters.</b> An MTProto proxy does not take an IP address — it
 * takes a DC number and connects to that DC itself. If the relay hands it the
 * wrong DC, the server does not know Telegram's auth key, Telegram drops the
 * connection within a second and retries — forever. The old lookup knew only
 * five exact IPs and sent EVERY other address to DC2: fine for accounts on DC2,
 * but for an account on DC4 (common in Iran) almost every connection failed
 * (a user log showed ~95 % of sessions closing after 106 bytes).
 *
 * <p>Resolution order:
 * <ol>
 *   <li>IPv6: Telegram encodes the DC in the address
 *       ({@code 2001:67c:4e8:f004::a} → DC4) — exact.</li>
 *   <li>Learned mappings (confirmed by real traffic, persisted).</li>
 *   <li>Known IPv4 addresses, then the /24 blocks Telegram uses.</li>
 *   <li>Self-correction: if Telegram keeps hanging up right after the first
 *       reply on an address, the next candidate DC is tried, and the one that
 *       carries real traffic is remembered.</li>
 * </ol>
 */
public final class DcResolver {

    /** Persistence hook (Prefs on Android/desktop), set by the relay at start. */
    public interface Store {
        String load();
        void save(String encoded);
    }

    private static final class Entry {
        final int[] candidates;
        volatile int idx;
        volatile boolean confirmed;
        volatile int bad;

        Entry(int[] candidates) { this.candidates = candidates; }

        int dc() { return candidates[Math.floorMod(idx, candidates.length)]; }
    }

    private static final Map<String, Entry> MAP = new ConcurrentHashMap<>();
    private static volatile Store store;
    /** Sink for "DC map changed" log lines. */
    public interface Log { void line(String s); }
    private static volatile Log log;

    // Exact addresses seen in Telegram's DC options (main + media).
    private static final String[][] KNOWN = {
            {"1", "149.154.175.50", "149.154.175.51", "149.154.175.52", "149.154.175.53",
                    "149.154.175.54", "149.154.175.55", "149.154.175.56", "149.154.175.57",
                    "149.154.175.58", "149.154.175.59", "149.154.175.211"},
            {"2", "149.154.167.35", "149.154.167.40", "149.154.167.41", "149.154.167.50",
                    "149.154.167.51", "149.154.167.151", "149.154.167.222", "149.154.162.123",
                    "95.161.76.100"},
            {"3", "149.154.175.100", "149.154.175.117"},
            {"4", "149.154.167.90", "149.154.167.91", "149.154.167.92", "149.154.167.93",
                    "149.154.167.255", "149.154.165.136", "149.154.166.120", "149.154.164.250"},
            {"5", "149.154.171.5", "91.108.56.100", "91.108.56.102", "91.108.56.128",
                    "91.108.56.130", "91.108.56.162", "91.108.56.180", "91.108.56.181"},
    };
    private static final Map<String, Integer> KNOWN_MAP = new ConcurrentHashMap<>();
    static {
        for (String[] row : KNOWN) {
            int dc = Integer.parseInt(row[0]);
            for (int i = 1; i < row.length; i++) KNOWN_MAP.put(row[i], dc);
        }
    }

    public static void init(Store s, Log l) {
        store = s;
        log = l;
        if (s == null) return;
        try {
            String enc = s.load();
            if (enc == null || enc.isEmpty()) return;
            for (String part : enc.split(";")) {
                int eq = part.lastIndexOf('=');
                if (eq <= 0) continue;
                String ip = part.substring(0, eq);
                int dc = Integer.parseInt(part.substring(eq + 1));
                if (dc < 1 || dc > 5) continue;
                Entry e = new Entry(candidatesFor(ip, dc));
                e.confirmed = true;
                MAP.put(ip, e);
            }
        } catch (Exception ignored) {}
    }

    /**
     * DC to use for a connection Telegram opened to {@code host}.
     * {@code headerDc} is the dc field of Telegram's obfuscated2 header — only
     * trusted for addresses we can't place (Telegram leaves it random over SOCKS).
     */
    public static int resolve(String host, int headerDc) {
        if (host == null) return validDc(headerDc) ? Math.abs(headerDc) : DcAddresses.DEFAULT_DC;
        String ip = normalize(host);
        int v6 = ipv6Dc(ip);
        if (v6 > 0) return v6;
        Entry e = MAP.get(ip);
        if (e == null) {
            int hint = validDc(headerDc) && !isTelegramRange(ip) ? Math.abs(headerDc) : 0;
            e = new Entry(candidatesFor(ip, hint));
            Entry prev = MAP.putIfAbsent(ip, e);
            if (prev != null) e = prev;
        }
        return e.dc();
    }

    /**
     * Outcome of a relayed session: {@code good} = real data flowed; {@code bad}
     * = Telegram hung up right after the first tiny reply (the wrong-DC symptom).
     */
    public static void report(String host, int dcUsed, boolean good, boolean bad) {
        if (host == null) return;
        String ip = normalize(host);
        if (ipv6Dc(ip) > 0) return;               // exact — nothing to learn
        Entry e = MAP.get(ip);
        if (e == null || e.dc() != dcUsed) return; // stale report (already switched)
        synchronized (e) {
            if (good) {
                e.bad = 0;
                if (!e.confirmed) {
                    e.confirmed = true;
                    persist();
                    emit("✓ DC map: " + ip + " → DC" + dcUsed + " confirmed by real traffic");
                }
                return;
            }
            if (!bad) return;
            e.bad++;
            int limit = e.confirmed ? 4 : 2;
            if (e.bad < limit) return;
            e.bad = 0;
            e.confirmed = false;
            e.idx++;
            emit("⟳ DC map: " + ip + " — Telegram keeps hanging up on DC" + dcUsed
                    + ", trying DC" + e.dc());
            persist();
        }
    }

    /** Current map for diagnostics. */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> me : MAP.entrySet()) {
            Entry e = me.getValue();
            sb.append(me.getKey()).append(" → DC").append(e.dc())
                    .append(e.confirmed ? " (confirmed)" : " (guess)").append('\n');
        }
        return sb.toString();
    }

    // --- internals --------------------------------------------------------------

    private static boolean validDc(int dc) { return dc != 0 && Math.abs(dc) <= 5; }

    private static String normalize(String host) {
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        return h.toLowerCase();
    }

    /** 2001:b28:f23d:f00X / 2001:67c:4e8:f00X / 2001:b28:f23f:f00X → X. */
    static int ipv6Dc(String ip) {
        if (ip.indexOf(':') < 0) return 0;
        if (!(ip.startsWith("2001:b28:") || ip.startsWith("2001:67c:4e8:")
                || ip.startsWith("2001:067c:04e8:") || ip.startsWith("2001:0b28:"))) return 0;
        String[] g = ip.split(":", -1);
        if (g.length < 4) return 0;
        String h = g[3];
        if (h.length() >= 2 && (h.charAt(0) == 'f')) {
            char c = h.charAt(h.length() - 1);
            if (c >= '1' && c <= '5') return c - '0';
        }
        return 0;
    }

    private static boolean isTelegramRange(String ip) {
        return ip.startsWith("149.154.") || ip.startsWith("91.108.") || ip.startsWith("95.161.");
    }

    /** Ordered DC candidates for an IPv4 address (first = best guess). */
    static int[] candidatesFor(String ip, int preferred) {
        List<Integer> out = new ArrayList<>();
        if (preferred >= 1 && preferred <= 5) out.add(preferred);
        Integer known = KNOWN_MAP.get(ip);
        if (known != null) out.add(known);
        int[] oct = octets(ip);
        if (oct != null) {
            int a = oct[0], b = oct[1], c = oct[2], d = oct[3];
            if (a == 149 && b == 154) {
                if (c == 175) {
                    if (d >= 96 && d <= 127) { out.add(3); out.add(1); } else { out.add(1); out.add(3); }
                } else if (c == 167) {
                    if (d >= 64 && d <= 127) { out.add(4); out.add(2); } else { out.add(2); out.add(4); }
                } else if (c >= 164 && c <= 166) {
                    out.add(4); out.add(2);
                } else if (c >= 160 && c <= 163) {
                    out.add(2); out.add(4);
                } else if (c >= 168 && c <= 171) {
                    out.add(5);
                }
            } else if (a == 91 && b == 108) {
                if (c >= 56 && c <= 59) out.add(5);
                else if (c >= 4 && c <= 7) out.add(1);
                else if (c >= 8 && c <= 11) out.add(3);
                else if (c >= 12 && c <= 19) out.add(4);
            } else if (a == 95 && b == 161) {
                out.add(2);
            }
        }
        // Everything else as a last resort, most common first.
        for (int dc : new int[]{2, 4, 1, 5, 3}) out.add(dc);
        // De-duplicate, keep order.
        List<Integer> uniq = new ArrayList<>();
        for (Integer x : out) if (!uniq.contains(x)) uniq.add(x);
        int[] r = new int[uniq.size()];
        for (int i = 0; i < r.length; i++) r[i] = uniq.get(i);
        return r;
    }

    private static int[] octets(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return null;
        try {
            int[] o = new int[4];
            for (int i = 0; i < 4; i++) o[i] = Integer.parseInt(p[i]);
            return o;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void persist() {
        Store s = store;
        if (s == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> me : MAP.entrySet()) {
            if (!me.getValue().confirmed) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(me.getKey()).append('=').append(me.getValue().dc());
        }
        try { s.save(sb.toString()); } catch (Exception ignored) {}
    }

    private static void emit(String s) {
        Log l = log;
        if (l != null) try { l.line(s); } catch (Exception ignored) {}
    }

    /** Test hook. */
    static void reset() { MAP.clear(); }

    private DcResolver() {}

    static String debugCandidates(String ip) { return Arrays.toString(candidatesFor(ip, 0)); }
}
