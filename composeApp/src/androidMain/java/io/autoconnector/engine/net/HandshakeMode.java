package io.autoconnector.engine.net;

import java.util.Random;

/**
 * Anti-DPI obfuscation modes for the upstream handshake. The user picks one
 * in Settings; the relay applies it on every fresh upstream connect. The
 * {@link #AUTO_RANDOM} mode picks a random "trick" each time and the success
 * counters in {@link HandshakeStats} build up a per-mode leaderboard.
 *
 * <p>The set covers SNI manipulation (override / empty / padded), browser
 * fingerprint mimicry (Chrome / Firefox / Safari / Edge / OkHttp), TLS-record
 * level tricks (fragmentation, CCS prelude, record-header version override),
 * cipher shuffling, padding-size variation and TCP timing tricks. Most are
 * inspired by ideas from goodbyedpi/Xray-uTLS and the broader anti-DPI scene.
 */
public enum HandshakeMode {

    NORMAL("0 · Plain",
            "Standard FakeTLS with the SNI from the secret"),
    SNI_JSDELIVR("[N/A] SNI jsdelivr",
            "Incompatible with FakeTLS-mtg (breaks the secret HMAC)"),
    SNI_GOOGLE("[N/A] SNI google",
            "Incompatible with FakeTLS-mtg (breaks the secret HMAC)"),
    SNI_CLOUDFLARE("[N/A] SNI cloudflare",
            "Incompatible with FakeTLS-mtg (breaks the secret HMAC)"),
    SNI_NONE("[N/A] No SNI",
            "Incompatible with FakeTLS-mtg (breaks the secret HMAC)"),
    SNI_EMPTY("[N/A] Empty SNI",
            "Incompatible with FakeTLS-mtg (breaks the secret HMAC)"),
    SNI_PADDED("[N/A] SNI with a space",
            "Incompatible with FakeTLS-mtg (breaks the secret HMAC)"),
    MIMIC_CHROME("1 · Mimic Chrome",
            "Cipher list and extensions like Chrome, + GREASE + ALPN"),
    MIMIC_FIREFOX("2 · Mimic Firefox",
            "Cipher list and extensions like Firefox, + GREASE"),
    MIMIC_SAFARI("3 · Mimic Safari",
            "Cipher list and extensions like Safari, + GREASE + ALPN"),
    MIMIC_EDGE("4 · Mimic Edge",
            "Edge fingerprint: different GREASE + cipher order"),
    MIMIC_OKHTTP("[N/A] Mimic OkHttp",
            "No TLS 1.3 ciphers — some mtg servers close the connection"),
    BIG_PADDING("6 · ClientHello 1024 bytes",
            "TLS record inflated with the padding extension"),
    SEGMENTED("7 · Segmented (TCP)",
            "Splits the ClientHello into 3–5 TCP pieces with a delay"),
    TCP_DELAY("8 · TCP delay",
            "Holds the TCP socket 200–500 ms before the ClientHello"),
    RECORD_FRAGMENT("[N/A] TLS-record split",
            "Incompatible with FakeTLS-mtg (needs a single record)"),
    CCS_PRELUDE("[N/A] CCS prelude",
            "Incompatible with FakeTLS-mtg (confuses its state machine)"),
    CIPHER_SHUFFLE("[N/A] Random cipher order",
            "Shuffling moves the TLS 1.3 ciphers — the server closes"),
    RECORD_TLS12("[N/A] Record header 0x0303",
            "Strict mtg servers close the handshake"),
    COMBO("[N/A] Combo",
            "Incompatible (changes the SNI)"),
    COMBO_MAX("[N/A] Combo Max",
            "Incompatible (SNI change + record-frag + CCS)"),
    AUTO_RANDOM("Auto · random tricks",
            "Every connect uses a random working trick; leaderboard in Stats."),
    SPLIT_BEFORE_SNI("11 · TCP split before SNI",
            "Splits the TCP segment right before the server_name extension"),
    SESSION_TICKET("[N/A] Session Ticket",
            "The extension is part of the HMAC — some mtg servers reject it"),
    RANDOM_PADDING("13 · Random padding",
            "TLS record length randomised (600–2000 B)"),
    ALPN_STRICT_H2("14 · ALPN h2 only",
            "Strict ALPN h2 like a CDN, without http/1.1"),
    TLS13_ONLY("15 · supported_versions 1.3",
            "ClientHello offers TLS 1.3 only"),
    TLS12_ONLY("16 · supported_versions 1.2",
            "ClientHello offers TLS 1.2 only");

    public final String label;
    public final String description;

    HandshakeMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public static HandshakeMode fromOrdinal(int o) {
        HandshakeMode[] v = values();
        if (o < 0 || o >= v.length) return NORMAL;
        HandshakeMode m = v[o];
        // If the user's persisted choice is now-disabled, fall back gracefully.
        return m.isWorking() ? m : NORMAL;
    }

    /** Modes proven by bin/test_modes.py to handshake with real mtg-FakeTLS
     *  upstreams. {@code [N/A]}-prefixed modes are kept for ordinal stability
     *  but execute as {@link #NORMAL} on the wire. */
    public boolean isWorking() {
        switch (this) {
            // 0% with mtg-FakeTLS — architectural HMAC / state-machine breakage.
            case SNI_JSDELIVR: case SNI_GOOGLE: case SNI_CLOUDFLARE:
            case SNI_NONE: case SNI_EMPTY: case SNI_PADDED:
            case RECORD_FRAGMENT: case CCS_PRELUDE:
            case COMBO: case COMBO_MAX:
            // Partial-failure modes from bin/test_modes.py — kept ordinal-safe
            // but execute as NORMAL so the user never gets stuck on a flaky pick.
            case MIMIC_OKHTTP: case CIPHER_SHUFFLE:
            case RECORD_TLS12: case SESSION_TICKET:
                return false;
            default:
                return true;
        }
    }

    private static final Random RND = new Random();

    /**
     * Picks a random working "trick" (skips AUTO_RANDOM and N/A) — but with
     * {@link #CONTROL_PROBABILITY} the answer is plain {@link #NORMAL},
     * so the leaderboard always carries a "no-trick" baseline to compare
     * against. Without this baseline you can't tell whether a trick is
     * helping or just no worse than nothing.
     */
    public static final double CONTROL_PROBABILITY = 0.15;

    public static HandshakeMode randomTrick() {
        if (RND.nextDouble() < CONTROL_PROBABILITY) return NORMAL;
        HandshakeMode[] v = values();
        while (true) {
            HandshakeMode m = v[RND.nextInt(v.length)];
            if (m != NORMAL && m != AUTO_RANDOM && m.isWorking()) return m;
        }
    }
}
