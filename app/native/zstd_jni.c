/*
 * Streaming zstd decompression for the Windows runtime installer (Android has no zstd API).
 * org.ttrmobile.TtrZstd.decompress(in, out) returns 0 on success or a negative error code.
 * Only zstd's decompression sources are compiled in.
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "zstd.h"

JNIEXPORT jint JNICALL Java_org_ttrmobile_TtrZstd_decompress(JNIEnv *env, jclass cls, jstring input, jstring output) {
    (void)cls;
    const char *in_path = (*env)->GetStringUTFChars(env, input, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output, NULL);
    FILE *in = in_path ? fopen(in_path, "rb") : NULL, *out = out_path ? fopen(out_path, "wb") : NULL;
    int result = 0;
    size_t in_size = ZSTD_DStreamInSize(), out_size = ZSTD_DStreamOutSize();
    void *in_buffer = malloc(in_size), *out_buffer = malloc(out_size);
    ZSTD_DCtx *ctx = ZSTD_createDCtx();
    if (!in || !out) result = -1;
    else if (!in_buffer || !out_buffer || !ctx) result = -2;
    else {
        size_t last = 0, read;
        while (result == 0 && (read = fread(in_buffer, 1, in_size, in)) > 0) {
            ZSTD_inBuffer source = {in_buffer, read, 0};
            while (source.pos < source.size) {
                ZSTD_outBuffer target = {out_buffer, out_size, 0};
                last = ZSTD_decompressStream(ctx, &target, &source);
                if (ZSTD_isError(last)) { result = -3; break; }
                if (fwrite(out_buffer, 1, target.pos, out) != target.pos) { result = -4; break; }
            }
        }
        if (result == 0 && (ferror(in) || last != 0)) result = -5; /* read error or truncated frame */
    }
    if (ctx) ZSTD_freeDCtx(ctx);
    free(in_buffer); free(out_buffer);
    if (in) fclose(in);
    if (out && fclose(out) != 0 && result == 0) result = -4;
    if (in_path) (*env)->ReleaseStringUTFChars(env, input, in_path);
    if (out_path) (*env)->ReleaseStringUTFChars(env, output, out_path);
    return result;
}

/* xz (XZ Embedded, 0BSD): org.ttrmobile.TtrZstd.unxz(in, out); 0 on success, negative on error. */
#include "xz.h"
JNIEXPORT jint JNICALL Java_org_ttrmobile_TtrZstd_unxz(JNIEnv *env, jclass cls, jstring input, jstring output) {
    (void)cls;
    const char *in_path = (*env)->GetStringUTFChars(env, input, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output, NULL);
    FILE *in = in_path ? fopen(in_path, "rb") : NULL, *out = out_path ? fopen(out_path, "wb") : NULL;
    static unsigned char in_buffer[1 << 16], out_buffer[1 << 16];
    int result = 0;
    xz_crc32_init(); xz_crc64_init();
    struct xz_dec *dec = xz_dec_init(XZ_DYNALLOC, 1u << 27);
    if (!in || !out) result = -1;
    else if (!dec) result = -2;
    else {
        struct xz_buf b = {in_buffer, 0, 0, out_buffer, 0, sizeof out_buffer};
        enum xz_ret ret = XZ_OK;
        while (ret == XZ_OK) {
            if (b.in_pos == b.in_size) { b.in_size = fread(in_buffer, 1, sizeof in_buffer, in); b.in_pos = 0; }
            ret = xz_dec_run(dec, &b);
            if (b.out_pos == sizeof out_buffer || ret != XZ_OK) {
                if (fwrite(out_buffer, 1, b.out_pos, out) != b.out_pos) { result = -4; break; }
                b.out_pos = 0;
            }
            if (ret == XZ_OK && b.in_size == 0) { result = -5; break; } /* truncated */
        }
        if (result == 0 && ret != XZ_STREAM_END) result = -3;
    }
    if (dec) xz_dec_end(dec);
    if (in) fclose(in);
    if (out && fclose(out) != 0 && result == 0) result = -4;
    if (in_path) (*env)->ReleaseStringUTFChars(env, input, in_path);
    if (out_path) (*env)->ReleaseStringUTFChars(env, output, out_path);
    return result;
}

#include <signal.h>
/* The app never uses SIGINT; ignoring it keeps a stray interrupt from Wine from closing the app. */
JNIEXPORT void JNICALL Java_org_ttrmobile_TtrZstd_ignoreInterrupt(JNIEnv *env, jclass cls) {
    (void) env; (void) cls; signal(SIGINT, SIG_IGN);
}
