package io.autoconnector.engine.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * MTProto "intermediate" transport: each message is a little-endian 4-byte
 * length followed by that many payload bytes. Rides on the {@link Obfuscated2}
 * cipher pair — bytes are (de)obfuscated as they cross the wire.
 */
public final class IntermediateTransport {

    private static final int MAX_FRAME = 1 << 20;

    private final InputStream in;
    private final OutputStream out;
    private final Obfuscated2 obf;
    /** Padded intermediate (tag 0xdddddddd): 0–15 random bytes after each
     *  payload, counted in the length. */
    private final boolean padded;
    private static final java.security.SecureRandom RND = new java.security.SecureRandom();

    public IntermediateTransport(InputStream in, OutputStream out, Obfuscated2 obf) {
        this(in, out, obf, false);
    }

    public IntermediateTransport(InputStream in, OutputStream out, Obfuscated2 obf, boolean padded) {
        this.in = in;
        this.out = out;
        this.obf = obf;
        this.padded = padded;
    }

    public void writeMessage(byte[] payload) throws IOException {
        int pad = padded ? RND.nextInt(16) : 0;
        byte[] frame = new byte[4 + payload.length + pad];
        if (pad > 0) {
            byte[] rnd = new byte[pad];
            RND.nextBytes(rnd);
            System.arraycopy(rnd, 0, frame, 4 + payload.length, pad);
        }
        int len = payload.length + pad;
        frame[0] = (byte) len;
        frame[1] = (byte) (len >> 8);
        frame[2] = (byte) (len >> 16);
        frame[3] = (byte) (len >> 24);
        System.arraycopy(payload, 0, frame, 4, payload.length);
        out.write(obf.enc.process(frame));
        out.flush();
    }

    /** Reads one framed message, deobfuscating as it arrives. */
    public byte[] readMessage() throws IOException {
        byte[] lenBuf = obf.dec.process(readFully(4));
        int len = (lenBuf[0] & 0xff)
                | ((lenBuf[1] & 0xff) << 8)
                | ((lenBuf[2] & 0xff) << 16)
                | ((lenBuf[3] & 0xff) << 24);
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("bad frame length " + len);
        }
        return obf.dec.process(readFully(len));
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) throw new IOException("EOF after " + got + "/" + n);
            got += r;
        }
        return buf;
    }
}
