package org.ttrmobile;

import java.io.*;
import java.security.MessageDigest;

/** Bounded native streaming extraction with the output hash computed in the same pass. */
public final class TtrBzip {
    private static final boolean available;
    static { boolean loaded; try { System.loadLibrary("ttrbz2"); loaded = true; } catch (LinkageError e) { loaded = false; } available = loaded; }
    private static native long create();
    private static native int step(long handle, byte[] input, int offset, int length, byte[] output, int[] counts);
    private static native void destroy(long handle);
    public interface Progress { void bytes(long size); }
    private TtrBzip() { }
    public static void unpack(InputStream in, OutputStream out, MessageDigest hash, Progress progress) throws Exception {
        if (!available) throw new IOException("The native extractor could not load. Reinstall the app and retry.");
        long handle = create(); if (handle == 0) throw new IOException("Unable to allocate the game extractor.");
        byte[] input = new byte[262144], output = new byte[262144]; int[] counts = new int[2];
        int offset = 0, remaining = 0; long total = 0; boolean eof = false;
        try {
            for (;;) {
                TtrHttp.checkCancelled();
                if (remaining == 0 && !eof) { remaining = in.read(input); offset = 0; if (remaining < 0) { eof = true; remaining = 0; } }
                int status = step(handle, input, offset, remaining, output, counts);
                offset += counts[0]; remaining -= counts[0];
                if (status != 0 && status != 4) throw new IOException("Game archive decompression failed.");
                if (counts[1] > 0) {
                    total += counts[1]; if (total > 2L * 1024 * 1024 * 1024) throw new IOException("Game file is unexpectedly large.");
                    out.write(output, 0, counts[1]); hash.update(output, 0, counts[1]); progress.bytes(total);
                }
                if (status == 4) { if (remaining != 0 || in.read() != -1) throw new IOException("Unexpected data after game archive."); break; }
                if (counts[0] == 0 && counts[1] == 0) throw new IOException(eof ? "Truncated game archive." : "Game archive made no progress.");
            }
        } finally { destroy(handle); }
    }
}
