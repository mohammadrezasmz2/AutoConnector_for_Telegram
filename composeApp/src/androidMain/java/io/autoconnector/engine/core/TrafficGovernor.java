package io.autoconnector.engine.core;

/**
 * "Telegram first": tells background work (host probes, subscription downloads)
 * when Telegram is actively pulling data through the relay, so scanning backs
 * off and leaves the bandwidth / radio to the user's download instead of
 * competing with it. Fed once per second by the relay watchdog.
 */
public final class TrafficGovernor {

    /** Relay download rate (bytes/s) above which Telegram counts as busy. */
    private static final long BUSY_BPS = 48L * 1024;
    /** Stay "busy" this long after the last busy second (avoid flapping). */
    private static final long HOLD_MS = 8_000L;
    /** Max parallel probes while Telegram is busy. */
    private static final int BUSY_PROBE_CAP = 4;

    private static volatile boolean enabled = true;
    private static volatile long lastBusyAt = 0L;
    private static volatile long downBps = 0L;

    /** Called once per second with the relay's current download rate. */
    public static void tick(long relayDownBps, boolean downloadBurstSeen) {
        downBps = relayDownBps;
        if (relayDownBps >= BUSY_BPS || downloadBurstSeen) {
            lastBusyAt = System.currentTimeMillis();
        }
    }

    public static void setEnabled(boolean on) { enabled = on; }
    public static boolean isEnabled() { return enabled; }

    /** True while Telegram is downloading through the relay (and the feature is on). */
    public static boolean busy() {
        return enabled && System.currentTimeMillis() - lastBusyAt < HOLD_MS;
    }

    /** Current relay download rate (bytes/s), for logs / UI. */
    public static long downBps() { return downBps; }

    /** Probe concurrency to use right now, given the configured value. */
    public static int probeCap(int configured) {
        return busy() ? Math.min(configured, BUSY_PROBE_CAP) : configured;
    }

    /**
     * Blocks (politely, abort-aware) while Telegram is busy, up to {@code maxMs}.
     * Returns true if it actually waited. Used before bulk subscription downloads.
     */
    public static boolean waitWhileBusy(long maxMs) {
        long start = System.currentTimeMillis();
        boolean waited = false;
        while (busy() && System.currentTimeMillis() - start < maxMs) {
            if (ScanGate.isAborted()) break;
            waited = true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return waited;
    }

    private TrafficGovernor() {}
}
