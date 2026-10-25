package io.autoconnector.engine.relay;

/**
 * Experimental strategies for <b>how the relay acquires an upstream proxy</b>
 * for a fresh Telegram connection — opt-in via Settings → «Экспериментально».
 *
 * <p>Motivation: pasting a single good MTProto proxy straight into Telegram
 * connects instantly, but through the relay it can be slow. The reference path
 * ({@link #OFF}) tries candidate proxies <em>one by one</em> with an 8-second
 * timeout each, so a couple of dead/slow candidates at the top stall the very
 * first connection for tens of seconds. These modes change only the
 * <em>selection/connect strategy</em>; the byte-level relay is untouched.
 */
public enum RelayConnectMode {

    /** Reference: serial candidate trial, 8 s timeout each. Default. */
    OFF(0, "off", "Off (reference path)",
            "Candidates are tried one by one with an 8 s timeout each."),

    /** Race several upstreams at once, first completed handshake wins. */
    PARALLEL_RACE(1, "race", "Parallel upstream race",
            "Opens several upstreams at once (count is configurable) and takes the first one "
            + "that completes the handshake; the others are closed. Gives an almost "
            + "instant connect, like entering one good proxy directly."),

    /** Serial, but a short 3 s timeout so dead candidates are skipped fast. */
    FAST_TIMEOUT(2, "fast", "Fast timeout (3 s)",
            "The same one-by-one trial, but with a 3 s timeout per candidate instead of 8 — "
            + "skips dead proxies faster."),

    /** Try the proven sticky/pinned upstream first (short timeout), then full list. */
    STICKY_FIRST(3, "sticky", "Proven host first",
            "First tries the pinned/best proxy with a short timeout "
            + "(like entering one proxy directly), and only if it fails tries the full list.");

    /** Short connect/handshake timeout for the fast / sticky-first modes. */
    public static final int FAST_TIMEOUT_MS = 3000;

    public final int code;
    public final String key;
    public final String label;
    public final String description;

    RelayConnectMode(int code, String key, String label, String description) {
        this.code = code;
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public static RelayConnectMode fromCode(int c) {
        for (RelayConnectMode m : values()) if (m.code == c) return m;
        return OFF;
    }
}
