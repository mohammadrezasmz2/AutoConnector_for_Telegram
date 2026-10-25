package io.autoconnector.engine.relay;

import io.autoconnector.engine.core.NetworkMode;
import io.autoconnector.engine.core.NetworkMonitor;

import java.util.Arrays;

/**
 * Smart auto-tune: watches how the relay is actually doing on the current
 * network and continuously picks the connection settings for the user —
 * per-upstream connect timeout, how many upstreams to race, how widely to pick
 * hosts and how often to explore unmeasured hosts.
 *
 * <p>It is a feedback controller over live measurements, not a guess:
 * <ul>
 *   <li><b>Timeout</b> = 2.5 × the 80th-percentile time successful upstreams
 *       needed to connect (clamped 1.5–8 s) — dead hosts are abandoned as soon
 *       as a live one would certainly have answered.</li>
 *   <li><b>Race width</b> follows the handshake success rate: few hosts
 *       answer → race many at once; most answer → race few (less waste).</li>
 *   <li><b>Breadth / exploration</b> follow how many proven-fast hosts exist:
 *       plenty → stick to them; none → explore widely to find some.</li>
 * </ul>
 * The user's own values stay stored untouched; when auto-tune is on the relay
 * simply reads the tuned values through the getters below.
 */
public final class AutoTuner {

    private static volatile boolean enabled = true;

    // --- live inputs (written from relay threads) --------------------------
    private static final int RING = 64;
    private static final int[] okMs = new int[RING];
    private static int okIdx = 0, okFill = 0;
    private static long winOk = 0, winFail = 0, winStalls = 0, winDead = 0;
    private static double winSpeedSum = 0;
    private static int winSpeedN = 0;
    /** Smoothed handshake success rate across windows (0..1), -1 = unknown. */
    private static double hsRate = -1;

    // --- tuned outputs ------------------------------------------------------
    private static volatile int tunedTimeoutMs = -1;
    private static volatile int tunedRaceWidth = -1;
    private static volatile int tunedBreadth = -1;
    private static volatile double explore = 0.25;
    private static volatile String summary = "";
    private static long lastTickAt = 0;

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean isEnabled() { return enabled; }

    // --- inputs -------------------------------------------------------------

    /** One upstream connect attempt finished (handshake ok / failed). */
    public static synchronized void onConnect(boolean ok, int totalMs) {
        if (ok) {
            winOk++;
            if (totalMs > 0) {
                okMs[okIdx] = totalMs;
                okIdx = (okIdx + 1) % RING;
                if (okFill < RING) okFill++;
            }
        } else {
            winFail++;
        }
    }

    public static synchronized void onStall() { winStalls++; }

    public static synchronized void onDead() { winDead++; }

    public static synchronized void onSpeedSample(double kbps) {
        winSpeedSum += kbps;
        winSpeedN++;
    }

    // --- outputs (read by the relay) -----------------------------------------

    public static int timeoutMs(int userValue) {
        return enabled && tunedTimeoutMs > 0 ? tunedTimeoutMs : userValue;
    }

    public static int raceWidth(int userValue) {
        return enabled && tunedRaceWidth > 0 ? tunedRaceWidth : userValue;
    }

    public static int breadth(int userValue) {
        return enabled && tunedBreadth >= 0 ? tunedBreadth : userValue;
    }

    /** Connect strategy: auto-tune always uses the parallel race. */
    public static int connectMode(int userValue) {
        return enabled ? RelayConnectMode.PARALLEL_RACE.code : userValue;
    }

    /** Probability of putting unmeasured hosts first (to learn their speed). */
    public static double exploreRate() { return enabled ? explore : 0.2; }

    /** Human-readable "what auto-tune decided" line for the UI. */
    public static String summary() { return enabled ? summary : ""; }

    // --- controller -----------------------------------------------------------

    /** Called about once per second by the watchdog; re-tunes every 20 s. */
    public static void tick(boolean portBouncing) {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < 20_000L) return;
        lastTickAt = now;
        long ok, fail, stalls, dead;
        int fill;
        int[] sample;
        double avgSpeed;
        synchronized (AutoTuner.class) {
            ok = winOk; fail = winFail; stalls = winStalls; dead = winDead;
            avgSpeed = winSpeedN > 0 ? winSpeedSum / winSpeedN : -1;
            winOk = winFail = winStalls = winDead = 0;
            winSpeedSum = 0; winSpeedN = 0;
            fill = okFill;
            sample = Arrays.copyOf(okMs, fill);
        }
        long attempts = ok + fail;
        if (attempts >= 4) {
            double r = (double) ok / attempts;
            hsRate = hsRate < 0 ? r : hsRate * 0.6 + r * 0.4;
        }

        // 1. Timeout from the real connect-time distribution of live hosts.
        if (fill >= 8) {
            Arrays.sort(sample);
            int p80 = sample[Math.min(sample.length - 1, (int) (sample.length * 0.8))];
            tunedTimeoutMs = clamp((int) (p80 * 2.5), 1500, 8000);
        }

        // 2. Race width from the handshake success rate.
        if (hsRate >= 0) {
            if (hsRate < 0.2) tunedRaceWidth = 24;
            else if (hsRate < 0.5) tunedRaceWidth = 14;
            else tunedRaceWidth = 8;
        } else if (tunedRaceWidth < 0) {
            tunedRaceWidth = 12;
        }

        // 3. Breadth + exploration from the number of proven-fast hosts.
        NetworkMode mode = NetworkMonitor.currentMode();
        int fast = SpeedBoard.fastCount(mode);
        boolean troubled = portBouncing || stalls + dead >= 3;
        if (troubled) {
            tunedBreadth = 80;
            explore = 0.45;
        } else if (fast >= 4) {
            tunedBreadth = 20;
            explore = 0.12;
        } else if (fast >= 1) {
            tunedBreadth = 40;
            explore = 0.25;
        } else {
            tunedBreadth = 70;
            explore = 0.5;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("timeout ").append(fmtSec(tunedTimeoutMs > 0 ? tunedTimeoutMs : 0));
        sb.append(" · race ").append(tunedRaceWidth);
        sb.append(" · breadth ").append(tunedBreadth).append('%');
        sb.append(" · explore ").append(Math.round(explore * 100)).append('%');
        sb.append(" · fast hosts ").append(fast);
        if (hsRate >= 0) sb.append(" · handshake ok ").append(Math.round(hsRate * 100)).append('%');
        if (avgSpeed > 0) sb.append(" · avg ").append(fmtKbps(avgSpeed));
        if (stalls + dead > 0) sb.append(" · stalls ").append(stalls + dead);
        String s = sb.toString();
        if (enabled && !s.equals(summary)) {
            RelayLog.emit("🧠 auto-tune: " + s);
        }
        summary = s;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String fmtSec(int ms) {
        if (ms <= 0) return "auto";
        return (ms / 1000) + "." + ((ms % 1000) / 100) + " s";
    }

    /** Formats a KB/s value compactly, e.g. "850 KB/s" / "2.4 MB/s". */
    public static String fmtKbps(double kbps) {
        if (kbps < 0) return "—";
        if (kbps >= 1024) {
            double mb = kbps / 1024.0;
            return (Math.round(mb * 10) / 10.0) + " MB/s";
        }
        return Math.round(kbps) + " KB/s";
    }

    private AutoTuner() {}
}
