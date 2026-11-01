"""Build the ARM64 libbzip2 adapter with Zig; link against an Android libc import stub.

The stub is build-only. Its SONAME imports Android's real libc.so at runtime.
No host libc, NDK implementation, or stub library is packaged in the APK.
"""
import os
from common import ROOT, BUILD, TOOLS, find, run


def main():
    output = BUILD / 'native'
    output.mkdir(parents=True, exist_ok=True)
    zig = find(TOOLS / 'zig', 'zig.exe')
    env = dict(os.environ, ZIG_GLOBAL_CACHE_DIR=str(BUILD / 'zig-global'), ZIG_LOCAL_CACHE_DIR=str(BUILD / 'zig-cache'))
    stub = output / 'libc-import.c'
    stub.write_text('void *malloc(unsigned long n){return 0;} void *calloc(unsigned long a,unsigned long b){return 0;} void free(void*p){} void abort(void){for(;;){}} void *memcpy(void*a,const void*b,unsigned long n){return a;} void *memset(void*a,int c,unsigned long n){return a;}\n')
    base = ['cc', '-target', 'aarch64-linux-android', '-shared', '-nostdlib', '-fPIC', '-fno-stack-protector', '-Wl,-z,max-page-size=16384']
    run(zig, *base, stub, '-Wl,-soname,libc.so', '-o', output / 'libc.so', env=env)
    native = ROOT / 'native/bzip2'
    vendor = native / 'vendor/bzip2-1.0.8'
    run(zig, *base, '-O3', '-fvisibility=hidden', '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
        '-DBZ_NO_STDIO', '-I' + str(native / 'include'), '-I' + str(find(TOOLS / 'jdk', 'jni.h').parent), '-I' + str(vendor),
        native / 'bzip_jni.c', *(vendor / name for name in ['bzlib.c', 'decompress.c', 'huffman.c', 'crctable.c', 'randtable.c', 'compress.c', 'blocksort.c']),
        output / 'libc.so', '-Wl,-soname,libttrbz2.so', '-o', output / 'libttrbz2.so', env=env)


if __name__ == '__main__': main()
