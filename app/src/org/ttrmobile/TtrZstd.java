package org.ttrmobile;

/** zstd decompression (native, decompress-only build of the official zstd sources). */
final class TtrZstd {
    static { System.loadLibrary("ttrzstd"); }
    private TtrZstd() { }
    /** Decompresses {@code in} to {@code out}; returns 0 on success, negative on error. */
    static native int decompress(String in, String out);
    /** Decompresses an xz file; returns 0 on success, negative on error. */
    static native int unxz(String in, String out);
    /** Ignores SIGINT in the app process (see ttrexec.c). */
    static native void ignoreInterrupt();
}
