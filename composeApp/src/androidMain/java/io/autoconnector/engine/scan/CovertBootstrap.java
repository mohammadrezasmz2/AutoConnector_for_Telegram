package io.autoconnector.engine.scan;

import io.autoconnector.engine.db.ProxyStore;
import io.autoconnector.engine.model.ProxyEntry;
import io.autoconnector.engine.model.ProxyType;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Private bootstrap channel.
 *
 * <p>Downloads an encrypted blob from a reachable site and turns it into proxy
 * entries, so a device that can only reach the national intranet can still get
 * a fresh pool and connect out. It is deliberately understated:
 *
 * <ul>
 *   <li>The endpoint, the key and the label are stored XOR-encoded as {@code int[]}
 *       (never string literals), so {@code strings} on the APK and a casual
 *       decompile do not surface the host or the key.</li>
 *   <li>The payload is AES-256-GCM, so the hosting provider, server logs and any
 *       plain-HTTP observer see only an opaque asset; the request looks like a
 *       fetch of a static image.</li>
 *   <li>Parsed proxies are stored under a neutral label, so neither the UI nor
 *       the database reveals where they came from.</li>
 * </ul>
 *
 * <p><b>Honest limits.</b> This resists static inspection and keeps the payload
 * confidential; it does not make the channel invisible. The TLS SNI still
 * carries the hostname to a network observer (fine here, since the host is an
 * allowed site), and anyone who runs the app and captures its traffic, or fully
 * reverses it, can recover the endpoint and the symmetric key. It raises the
 * bar; it is not a guarantee of secrecy.
 */
public final class CovertBootstrap {

    public interface Log { void line(String s); }

    // Neutral placeholder (example.invalid). The real endpoint/key are injected
    // at build time from the private CLUB_CONSTANTS secret, so the public source
    // never carries them. Until a build injects them this stays a no-op.
    private static final int[] U = { 50, 183, 109, 14, 215, 81, 253, 32, 63, 187, 120, 19, 212, 7, 183, 33, 51, 173, 111, 31, 200, 2, 182, 32, 59, 237, 123, 23, 202 };
    private static final int[] K = { 112, 164, 25, 183, 3, 198, 158, 111, 202, 9, 189, 236, 224, 68, 219, 251, 96, 189, 67, 208, 61, 134, 155, 89, 245, 226, 202, 202, 222, 78, 16, 113 };
    private static final int[] L = { 57, 175, 118, 11, 192 };

    private static final int[] XSEED = { 0x5A, 0xC3, 0x19, 0x7E, 0xA4, 0x6B, 0xD2, 0x0F };

    private static byte[] de(int[] a) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (a[i] ^ XSEED[i % XSEED.length]);
        return out;
    }

    private static final long MIN_INTERVAL_MS = 90_000L;
    private static volatile long lastRun = 0;

    /** True once a real endpoint has been pasted in (the default is the neutral
     *  {@code example.invalid} placeholder, which keeps the feature a no-op and
     *  leaves no real host in the shipped build). */
    public static boolean configured() {
        String u = new String(de(U));
        return u.startsWith("http") && !u.contains("example.invalid");
    }

    /**
     * Fetches, decrypts and ingests the pool. Runs at most once per
     * {@link #MIN_INTERVAL_MS}. Returns the number of new proxies, or -1 on any
     * failure (kept quiet — a failure is just "nothing new this time").
     */
    public static int refresh(ProxyStore store, Log log) {
        long now = System.currentTimeMillis();
        if (now - lastRun < MIN_INTERVAL_MS) return 0;
        lastRun = now;
        String label = new String(de(L));
        try {
            byte[] blob = download(new String(de(U)));
            if (blob == null || blob.length < 29) {   // 12 nonce + 16 tag + >=1
                if (log != null) log.line("⚠ " + label + ": couldn't fetch");
                return -1;
            }
            byte[] plain = decrypt(blob);
            if (plain == null) {
                if (log != null) log.line("⚠ " + label + ": fetched but couldn't open (key mismatch?)");
                return -1;
            }
            String text = new String(plain, "UTF-8");
            List<ProxyEntry> list = ProxyParser.parse(text, label, ProxyType.SOCKS5);
            int added = list.isEmpty() ? 0 : store.addAll(list);
            // Heartbeat: logged every refresh so the channel is visibly working,
            // even when every proxy is already known (+0 new).
            if (log != null) log.line("⇣ " + label + ": " + list.size() + " proxies, +" + added + " new");
            return added;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static byte[] download(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 4 * 1024 * 1024) break;   // sanity cap
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** blob = [12-byte nonce][ciphertext + 16-byte GCM tag]. */
    private static byte[] decrypt(byte[] blob) {
        try {
            byte[] nonce = new byte[12];
            System.arraycopy(blob, 0, nonce, 0, 12);
            byte[] ct = new byte[blob.length - 12];
            System.arraycopy(blob, 12, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(de(K), "AES"),
                    new GCMParameterSpec(128, nonce));
            return cipher.doFinal(ct);
        } catch (Exception e) {
            return null;
        }
    }

    private CovertBootstrap() {}
}
