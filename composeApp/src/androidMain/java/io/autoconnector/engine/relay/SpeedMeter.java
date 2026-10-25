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
    /** A 2 s window must carry at least this much to count as a burst sample. */
    private static final long MIN_WINDOW_BYTES = 32L * 1024;
    /** A bucket with at least this many bytes counts as "transferring". */
    private static final long BUSY_BUCKET_BYTES = 1024L;
    /** Minimum busy bytes before the busy-bucket estimate is trusted. */
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
    /** Length (in buckets) of the active transfer periods — consecutive busy
     *  buckets with gaps of at most {@link #GAP_BUCKETS} count as one period, so a
     *  slow host that delivers in lumps is timed over the whole period. */
    private long activeBuckets = 0;
    private long lastBusyBucket = -1;
    private static final int GAP_BUCKETS = 8;

    /** Wall-clock ms of the last bucket that moved ≥ 16 KB (download activity). */
    public volatile long lastBurstAt = 0L;
    /** Best 2 s window rate (KB/s) seen in this connection, -1 = none yet. */
    public volatile double peakKbps = -1;
    /** Latest usable speed estimate for the live UI (KB/s), -1 = none yet. */
    public volatile double liveKbps = -1;

    /** Feeds {@code n} downloaded bytes that arrived at {@code now}. */
    public void onBytes(int n, long now) {
        if (n <= 0) return;
        long b = now / BUCKET_MS;
        if (b != curBucket) roll(b);
        curBytes += n;
        if (curBytes >= BURST_MARK_BYTES) lastBurstAt = now;
    }

    private void roll(long nb) {
        if (curBucket >= 0) {
            // 1. Commit the finished bucket.
            ring[(int) (curBucket % WIN)] = curBytes;
            if (curBytes >= BUSY_BUCKET_BYTES) {
                busyBytes += curBytes;
                if (lastBusyBucket >= 0 && curBucket - lastBusyBucket <= GAP_BUCKETS) {
                    activeBuckets += curBucket - lastBusyBucket;
                } else {
                    activeBuckets += 1;
                }
                lastBusyBucket = curBucket;
            }
            // 2. Evaluate the 2 s window that ENDS at the finished bucket.
            long sum = 0;
            for (long v : ring) sum += v;
            if (sum >= MIN_WINDOW_BYTES) {
                double kbps = (sum / 1024.0) / (WIN * BUCKET_MS / 1000.0);
                winRateSum += kbps;
                winCount++;
                if (kbps > peakKbps) peakKbps = kbps;
                liveKbps = kbps;
            }
            // 3. Zero the slots of the buckets that were skipped (no data), so
            //    the next windows treat that silence as 0 bytes.
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
     * accumulators, or -1 when not enough real download happened to judge.
     * Called periodically from the pipe thread and once at session end.
     */
    public double take(long now) {
        // Flush the in-progress bucket if it is already over.
        if (curBucket >= 0 && now / BUCKET_MS != curBucket) roll(now / BUCKET_MS);
        double out = -1;
        if (winCount > 0) {
            out = winRateSum / winCount;
        } else if (busyBytes >= MIN_BUSY_BYTES && activeBuckets > 0) {
            out = (busyBytes / 1024.0) / (activeBuckets * BUCKET_MS / 1000.0);
        }
        if (out > 0 && liveKbps < 0) liveKbps = out;
        winRateSum = 0;
        winCount = 0;
        busyBytes = 0;
        activeBuckets = 0;
        return out;
    }

    /** True if this connection was downloading something within {@code windowMs}. */
    public boolean downloadingWithin(long now, long windowMs) {
        long t = lastBurstAt;
        return t > 0 && now - t <= windowMs;
    }
}
