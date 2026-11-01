"""Build pinned Termux:X11 native code with NDK r28c on Windows.

No APK, DEX or native library from another Android app is consumed.
Host-only CMake adaptations replace shell patching, symlinks and gcc generators.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from common import ROOT, TOOLS, find, fetch, extract

COMMIT = '7e5f5c5c9226e2cdab392b2efba91bc4bd86463e'
SOURCE = TOOLS / 'termux-x11-source'
BUILD = ROOT / 'build/linux-x11'


def run(*args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    if not SOURCE.exists():
        run('git', 'clone', 'https://github.com/termux/termux-x11.git', SOURCE)
    revision = subprocess.check_output(['git', '-C', str(SOURCE), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != COMMIT:
        raise RuntimeError('Termux:X11 source must be at ' + COMMIT)
    run('git', '-C', SOURCE, 'submodule', 'update', '--init', '--recursive', '--jobs', '8', '--depth', '1')
    archive = fetch('https://github.com/lexxmark/winflexbison/releases/download/v2.5.25/win_flex_bison-2.5.25.zip',
                    'winflexbison-2.5.25.zip', '8d324b62be33604b2c45ad1dd34ab93d722534448f55a16ca7292de32b6ac135')
    extract(archive, TOOLS / 'winflexbison')
    cpp = BUILD / 'source'
    shutil.copytree(SOURCE / 'lorie/src/main/cpp', cpp, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns('.git'))
    # NTFS is case insensitive: X11/Xlocale.h otherwise shadows NDK xlocale.h.
    locale = cpp / 'libx11/include/X11/Xlocale.h'
    locale.replace(locale.with_name('XTtrLocale.h'))
    for p in (cpp / 'libx11').rglob('*'):
        if p.is_file() and p.suffix in ('.c', '.h'):
            text = p.read_text(encoding='utf-8', errors='surrogateescape')
            if 'Xlocale.h' in text:
                p.write_text(text.replace('Xlocale.h', 'XTtrLocale.h'), encoding='utf-8', errors='surrogateescape')
    # Apply exactly the patches used by upstream's CMake before configuring.
    for name, component in [('pixman', 'pixman'), ('Xtrans', 'libxtrans'), ('xkbcomp', 'xkbcomp'),
                            ('xkbfile', 'libxkbfile'), ('x11', 'libx11'), ('xserver', 'xserver'), ('libepoxy', 'libepoxy')]:
        patch = cpp / 'patches' / (name + '.patch')
        run(Path('C:/Program Files/Git/usr/bin/patch.exe'), '-p1', '-N', '-d', (cpp / component).as_posix(), '-i', patch.as_posix())
    cmake = cpp / 'CMakeLists.txt'
    text = cmake.read_text()
    text = re.sub(r'function\(target_apply_patch.*?endfunction\(\)',
                  'function(target_apply_patch patch_target d p)\nendfunction()', text, flags=re.S)
    cmake.write_text(text)
    # Copies are portable and need no Windows symlink privilege.
    p = cpp / 'recipes/xorgproto.cmake'
    p.write_text(p.read_text().replace('execute_process(COMMAND ${CMAKE_COMMAND} -E create_symlink', 'file(COPY')
        .replace('"${CMAKE_CURRENT_BINARY_DIR}/X11/Xtrans")', 'DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/X11")'))
    # libxtrans is named Xtrans in the include tree.
    shutil.copytree(cpp / 'libxtrans', BUILD / 'native/X11/Xtrans', dirs_exist_ok=True)
    p = cpp / 'recipes/xshmfence.cmake'
    p.write_text(p.read_text().replace('execute_process(COMMAND ${CMAKE_COMMAND} -E create_symlink "${CMAKE_CURRENT_SOURCE_DIR}/libxshmfence/src/xshmfence.h" "${CMAKE_CURRENT_BINARY_DIR}/X11/xshmfence.h")',
        'configure_file("${CMAKE_CURRENT_SOURCE_DIR}/libxshmfence/src/xshmfence.h" "${CMAKE_CURRENT_BINARY_DIR}/X11/xshmfence.h" COPYONLY)'))
    # Run the host generator explicitly; redirection is handled by Python.
    native = BUILD / 'native'
    native.mkdir(parents=True, exist_ok=True)
    zig = find(TOOLS / 'zig', 'zig.exe')
    env = dict(os.environ, ZIG_GLOBAL_CACHE_DIR=str(ROOT / 'build/zig-global'), ZIG_LOCAL_CACHE_DIR=str(ROOT / 'build/zig-cache'))
    run(zig, 'cc', '-O2', cpp / 'libx11/src/util/makekeys.c', '-I' + str(cpp / 'xorgproto/include'), '-o', native / 'makekeys.exe', env=env)
    with (native / 'ks_tables.h').open('w') as out:
        run(native / 'makekeys.exe', 'keysymdef.h', 'XF86keysym.h', 'Sunkeysym.h', 'DECkeysym.h', 'HPkeysym.h',
            cwd=cpp / 'xorgproto/include/X11', stdout=out)
    p = cpp / 'recipes/xkbcomp.cmake'
    p.write_text(re.sub(r'add_custom_command\(.*?VERBATIM\)', '', p.read_text(), count=1, flags=re.S))
    # Use regular JNI throughout our small view adapter (no hidden annotation APIs).
    p = cpp / 'lorie/activity.cpp'
    text = p.read_text().replace('static jboolean requestConnection(__unused jlong ptr)',
        'static jboolean requestConnection(__unused JNIEnv* env, __unused jclass cls, __unused jlong ptr)')
    text = text.replace('+[](jlong ptr) -> jboolean', '+[](__unused JNIEnv* env, __unused jclass cls, jlong ptr) -> jboolean')
    p.write_text(text)
    p = cpp / 'lorie/cmdentrypoint.cpp'
    p.write_text(p.read_text().replace('+[]() -> jboolean { return conn_fd != -1; }',
        '+[](__unused JNIEnv* env, __unused jclass cls) -> jboolean { return conn_fd != -1; }'))
    # Vulkan wrappers (e.g. GameNative's) hand over AHardwareBuffers labelled RGBA that hold BGRA pixels,
    # so red and blue appear swapped. TTR_DRI3_SWAP_RB=1 (set by the app from a developer flag) marks those
    # imports so the renderer applies its existing .bgra swizzle.
    p = cpp / 'lorie/buffer.c'
    text = p.read_text(encoding='utf-8')
    if 'swapRB' not in text:
        old = '    GLuint id;\n    EGLImage image;\n'
        assert text.count(old) == 1, 'Unsupported LorieBuffer layout'
        text = text.replace(old, '    GLuint id;\n    EGLImage image;\n    bool swapRB;\n', 1)
        old = 'return LorieBuffer_description(buffer)->format != AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM;'
        assert text.count(old) == 1, 'Unsupported LorieBuffer_isRgba'
        text = text.replace(old, 'return (LorieBuffer_description(buffer)->format != AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM) != (buffer && buffer->swapRB);')
        text += '\n__LIBC_HIDDEN__ void LorieBuffer_setSwapRB(LorieBuffer *buffer, bool swap) {\n    if (buffer) buffer->swapRB = swap;\n}\n'
        p.write_text(text, encoding='utf-8')
    p = cpp / 'lorie/buffer.h'
    text = p.read_text(encoding='utf-8')
    if 'LorieBuffer_setSwapRB' not in text:
        old = 'bool LorieBuffer_isRgba(LorieBuffer* _Nullable buffer);'
        assert text.count(old) == 1
        text = text.replace(old, old + '\nvoid LorieBuffer_setSwapRB(LorieBuffer* _Nullable buffer, bool swap);')
        p.write_text(text, encoding='utf-8')
    p = cpp / 'lorie/InitOutput.c'
    text = p.read_text(encoding='utf-8')
    if 'TTR_DRI3_SWAP_RB' not in text:
        old = '        check(!priv->buffer, "DRI3: LorieBuffer_wrapAHardwareBuffer failed.");\n'
        assert text.count(old) == 1, 'Unsupported DRI3 AHardwareBuffer import code'
        text = text.replace(old, old + '        if (getenv("TTR_DRI3_SWAP_RB")) LorieBuffer_setSwapRB(priv->buffer, true);\n')
        p.write_text(text, encoding='utf-8')
    ndk = TOOLS / 'android-ndk/android-ndk-r28c'
    run('cmake', '-S', cpp, '-B', native, '-G', 'Ninja',
        '-DCMAKE_TOOLCHAIN_FILE=' + (ndk / 'build/cmake/android.toolchain.cmake').as_posix(),
        '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-29',
        '-DBISON_EXECUTABLE=' + (TOOLS / 'winflexbison/win_bison.exe').as_posix(), '-DCMAKE_POLICY_VERSION_MINIMUM=3.5')
    run('cmake', '--build', native, '--target', 'Xlorie', '-j', '6')
    print('Built source X server:', native / 'libXlorie.so')


if __name__ == '__main__':
    main()
