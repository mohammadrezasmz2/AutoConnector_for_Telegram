package io.autoconnector.engine.relay;

/**
 * Measures the real DOWNLOAD throughput of one relayed Telegram connection
 * (upstream → Telegram direction), so hosts can be ranked by the speed the
 * user actually gets inside Telegram instead of by handshake latency.
 *
 * <p>Bytes are bucketed in 250 ms slots. Two estimators are kept:
 * <ul>
 *   <li><b>Burst windows</b> — every sliding 2 s window that moved at least
 *       {@link #MIN_WINDOW_BYTES}: its rate is a clean sample of "how fast can
 *       this host push a file". Used when the session downloaded anything big.</li>
 *   <li><b>Busy buckets</b> — total bytes / time of buckets that carried at
 *       least {@link #BUSY_BUCKET_BYTES}. Catches SLOW hosts, whose windows never
 *       reach the burst threshold (a 20 KB/s host would otherwise look
 *       "unmeasured" forever and keep being retried).</li>
 * </ul>
 * Small control traffic (pings, acks, updates) is ignored by both, so an idle
 * session never produces a fake low speed.
 *
 * <p>Threading: {@link #onBytes} is called only from the relay's down-pipe
 * thread; the volatile fields are read by the stall watchdog / UI.
 */
public final class SpeedMeter {

    private static final long BUCKET_MS = 250L;
    /** Window length in buckets (8 × 250 ms = 2 s). */
    private static final int WIN = 8;
    /** A bucket with at least this many bytes counts as "transferring". */
    private static final long BUSY_BUCKET_BYTES = 1024L;
    /** A sustained window must carry at least this much (≈ 6 KB/s). */
    private static final long MIN_WINDOW_BYTES = 12L * 1024;
    /** Minimum busy bytes before the (display-only) estimate is reported. */
    private static final long MIN_BUSY_BYTES = 16L * 1024;
    /** A bucket with this much data marks "a download is in progress". */
    private static final long BURST_MARK_BYTES = 8L * 1024;

    private final long[] ring = new long[WIN];
    private long curBucket = -1;
    private long curBytes = 0;

    // Accumulators since the last take() (pipe thread only).
    private double winRateSum = 0;
    private int winCount = 0;
    private long busyBytes = 0;
    private long busyBuckets = 0;

    /** Wall-clock ms of the last bucket that moved ≥ 8 KB (download activity). */
    public volatile long lastBurstAt = 0L;
    /** Best SUSTAINED 2 s rate (KB/s) seen in this connection, -1 = none yet. */
    public volatile double peakKbps = -1;
    /** Latest sustained-window rate (KB/s) for the live UI, -1 = none yet. */
    public volatile double liveKbps = -1;
    /** Whether the value returned by the last {@link #take} was a confident
     *  (sustained-transfer) measurement rather than a rough estimate. */
    public boolean lastConfident = false;

    /** Feeds {@code n} downloaded bytes that arrived at {@code now}. */
    public void onBytes(int n, long now) {
        if (n <= 0) return;
        long b = now / BUCKET_MS;
        if (b != curBucket) roll(b);
        curBytes += n;
        if (curBytes >= BURST_MARK_BYTES) lastBurstAt = now;
    }

    /**
     * Speed is only trusted from SUSTAINED transfers: a 2 s window in which data
     * arrived in every 250 ms slot. That is a file actually streaming, so the rate
     * reflects what the host can deliver. Normal browsing (a thumbnail here, a
     * message there) is limited by what Telegram asks for, not by the host — it
     * used to be averaged over the idle gaps and made good hosts look "slow".
     */
    private void roll(long nb) {
        if (curBucket >= 0) {
            ring[(int) (curBucket % WIN)] = curBytes;
            if (curBytes >= BUSY_BUCKET_BYTES) {
                busyBytes += curBytes;
                busyBuckets++;
            }
            long sum = 0;
            boolean sustained = true;
            for (long v : ring) {
                sum += v;
                if (v < BUSY_BUCKET_BYTES) sustained = false;
            }
            if (sustained && sum >= MIN_WINDOW_BYTES) {
                double kbps = (sum / 1024.0) / (WIN * BUCKET_MS / 1000.0);
                winRateSum += kbps;
                winCount++;
                if (kbps > peakKbps) peakKbps = kbps;
                liveKbps = kbps;
            }
            // Zero the slots of skipped (silent) buckets.
            long gap = nb - curBucket;
            for (long k = 1; k < gap && k <= WIN; k++) {
                ring[(int) ((curBucket + k) % WIN)] = 0;
            }
        }
        curBucket = nb;
        curBytes = 0;
    }

    /**
     * Returns the speed (KB/s) measured since the previous call and resets the
     * accumulators, or -1 when nothing usable happened. {@link #lastConfident}
     * tells whether it came from sustained transfers (trusted for ranking and
     * switching) or is only a rough estimate from short bursts (display only —
     * it counts just the time data was actually arriving).
     */
    public double take(long now) {
        if (curBucket >= 0 && now / BUCKET_MS != curBucket) roll(now / BUCKET_MS);
        double out = -1;
        lastConfident = false;
        if (winCount > 0) {
            out = winRateSum / winCount;
            lastConfident = true;
        } else if (busyBytes >= MIN_BUSY_BYTES && busyBuckets > 0) {
            out = (busyBytes / 1024.0) / (busyBuckets * BUCKET_MS / 1000.0);
        }
        winRateSum = 0;
        winCount = 0;
        busyBytes = 0;
        busyBuckets = 0;
        return out;
    }

    /** True if this connection was downloading something within {@code windowMs}. */
    public boolean downloadingWithin(long now, long windowMs) {
        long t = lastBurstAt;
        return t > 0 && now - t <= windowMs;
    }
}
