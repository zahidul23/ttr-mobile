/* Streaming libbzip2 JNI adapter. Buffers are bounded and no Java critical array is held across I/O. */
#include <jni.h>
#include <stdlib.h>
#include "bzlib.h"
typedef struct { bz_stream stream; int ended; } decoder;
void bz_internal_error(int code) { (void)code; abort(); }
JNIEXPORT jlong JNICALL Java_org_ttrmobile_TtrBzip_create(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; decoder *d = calloc(1, sizeof(*d));
    if (!d) return 0;
    if (BZ2_bzDecompressInit(&d->stream, 0, 0) != BZ_OK) { free(d); return 0; }
    return (jlong)(size_t)d;
}
JNIEXPORT jint JNICALL Java_org_ttrmobile_TtrBzip_step(JNIEnv *env, jclass cls, jlong handle, jbyteArray input, jint offset, jint length, jbyteArray output, jintArray counts) {
    (void)cls; decoder *d = (decoder *)(size_t)handle;
    if (!d || d->ended || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, input) - length || (*env)->GetArrayLength(env, counts) < 2) return BZ_PARAM_ERROR;
    jbyte *in = (*env)->GetByteArrayElements(env, input, 0); if (!in) return BZ_MEM_ERROR;
    jbyte *out = (*env)->GetByteArrayElements(env, output, 0);
    if (!out) { (*env)->ReleaseByteArrayElements(env, input, in, JNI_ABORT); return BZ_MEM_ERROR; }
    unsigned int space = (unsigned int)(*env)->GetArrayLength(env, output);
    d->stream.next_in = (char *)in + offset; d->stream.avail_in = (unsigned int)length;
    d->stream.next_out = (char *)out; d->stream.avail_out = space;
    int status = BZ2_bzDecompress(&d->stream);
    jint result[2] = {length - (jint)d->stream.avail_in, (jint)(space - d->stream.avail_out)};
    d->stream.next_in = 0; d->stream.next_out = 0;
    (*env)->ReleaseByteArrayElements(env, input, in, JNI_ABORT); (*env)->ReleaseByteArrayElements(env, output, out, 0);
    (*env)->SetIntArrayRegion(env, counts, 0, 2, result);
    if (status == BZ_STREAM_END) d->ended = 1;
    return status;
}
JNIEXPORT void JNICALL Java_org_ttrmobile_TtrBzip_destroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; decoder *d = (decoder *)(size_t)handle;
    if (d) { BZ2_bzDecompressEnd(&d->stream); free(d); }
}
