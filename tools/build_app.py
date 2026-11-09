"""Build the TTR Mobile APK:  python tools/build_app.py [--dev-server URL]

Builds any missing native pieces first (X server, X11 libraries, GL4ES, bzip2, keyboard maps, Windows bootstrap,
TTR artwork and icon). The Vulkan graphics stack is built separately in WSL: bash tools/vulkan_stack/build.sh

Releases are help.toontown.ttrmobile, signed with the private release key (docs/release-signing.md):
TTR_RELEASE_KEYSTORE (default ~/.ttr-mobile/release.p12) and its password from TTR_RELEASE_PASSWORD,
or typed when apksigner asks. Test-server builds (--dev-server) are help.toontown.ttrmobile.dev,
named "TTR Mobile Dev", signed with the development key, and install next to the release.
The Windows runtime itself (GameNative Proton + FEX) is downloaded on the phone from pinned public
sources; see TtrWineRuntime.
"""
import argparse, hashlib, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path
from common import ROOT, TOOLS, NDK_BIN, find, run

BUILD = ROOT / 'build/app'
VERSION = '0.8.4'
PACKAGE = 'help.toontown.ttrmobile'
DEV_PACKAGE = PACKAGE + '.dev'
RELEASE_KEYSTORE = Path(os.environ.get('TTR_RELEASE_KEYSTORE', Path.home() / '.ttr-mobile/release.p12'))


def native(clang, out):
    out.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, TEMP=str(out), TMP=str(out))
    base = [clang, '--target=aarch64-linux-android29', '-O2', '-fPIC', '-shared', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384']
    src = ROOT / 'app/native'
    run(*base, src / 'ttrexec.c', '-o', out / 'libttrexec.so', env=env)
    run(*base, '-Wl,-soname,libandroid-sysvshm.so', src / 'sysvshm.c', '-o', out / 'libandroid-sysvshm.so', env=env)
    run(*base, '-fvisibility=hidden', '-Wl,-soname,libasound.so', src / 'asound_aaudio.c', '-laaudio', '-llog', '-o', out / 'libasound.so', env=env)
    zstd = TOOLS / 'zstd-source/zstd-1.5.6/lib'
    sources = [zstd / 'decompress' / n for n in ('zstd_decompress.c', 'zstd_decompress_block.c', 'zstd_ddict.c', 'huf_decompress.c')]
    sources += [zstd / 'common' / n for n in ('entropy_common.c', 'fse_decompress.c', 'zstd_common.c', 'error_private.c', 'xxhash.c', 'debug.c')]
    xz = TOOLS / 'xz-embedded-source'  # official repo, commit ae63ae3a36ed01724674e8f3d750dc47bf125410 (0BSD)
    sources += [xz / 'linux/lib/xz' / n for n in ('xz_crc32.c', 'xz_crc64.c', 'xz_dec_lzma2.c', 'xz_dec_stream.c')]
    run(*base, '-DZSTD_DISABLE_ASM', '-DXZ_USE_CRC64', '-DXZ_DEC_ANY_CHECK', '-fvisibility=hidden', '-I' + str(zstd), '-I' + str(zstd / 'common'),
        '-I' + str(xz / 'userspace'), '-I' + str(xz / 'linux/include/linux'), '-I' + str(xz / 'linux/lib/xz'),
        src / 'zstd_jni.c', *sources, '-o', out / 'libttrzstd.so', env=env)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dev-server', metavar='URL', help='test build that sends logs to and updates from tools/dev_server.py at URL, e.g. http://192.168.1.20:8765')
    args = parser.parse_args()
    if not args.dev_server and not RELEASE_KEYSTORE.is_file():
        raise RuntimeError(f'Release key not found at {RELEASE_KEYSTORE}; set TTR_RELEASE_KEYSTORE (see docs/release-signing.md), or use --dev-server for a test build')
    BUILD.mkdir(parents=True, exist_ok=True)
    java = find(TOOLS / 'jdk', 'java.exe'); android = find(TOOLS / 'platform', 'android.jar'); bt = find(TOOLS / 'build-tools', 'aapt2.exe').parent
    clang = find(NDK_BIN, 'clang.exe')
    # Each piece and the script that builds it, in dependency order (GL4ES links against the X11 libraries).
    steps = [('build_x11.py', ROOT / 'build/linux-x11/native/libXlorie.so'),
             ('build_x11_libraries.py', ROOT / 'build/linux-libraries/native/libX11.so'),
             ('build_gl4es.py', ROOT / 'build/linux-graphics/source/lib/libGL.so.1'),
             ('build_bzip2.py', ROOT / 'build/native/libttrbz2.so'),
             ('build_xkb.py', ROOT / 'build/xkb/xkb.zip'),
             ('build_bootstrap_exe.py', ROOT / 'build/TtrBootstrap.exe'),
             ('extract_ttr_assets.py', ROOT / 'build/ttr-art/icon.png'),
             ('make_icon.py', ROOT / 'build/app-res/mipmap-xxxhdpi/ic_launcher.png')]
    for script, artifact in steps:
        if not artifact.is_file(): run(sys.executable, ROOT / 'tools' / script)
    prerequisites = {'xkb': ROOT / 'build/xkb/xkb.zip', 'bootstrap': ROOT / 'build/TtrBootstrap.exe',
                     'x11': ROOT / 'build/linux-x11/native/libXlorie.so', 'gl4es': ROOT / 'build/linux-graphics/source/lib/libGL.so.1',
                     'bzip2': ROOT / 'build/native/libttrbz2.so',
                     # OpenGL-on-Vulkan stack (Mesa zink + Vulkan loader), built by tools/vulkan_stack/build.sh in WSL
                     'vulkan': ROOT / 'build/vulkan-stack/vulkan-stack.tar.zst'}
    if not prerequisites['vulkan'].is_file():
        raise RuntimeError('Build the Vulkan graphics stack first, in WSL Ubuntu 24.04: bash tools/vulkan_stack/build.sh (see README)')
    out = BUILD / 'native-build'; native(clang, out)
    classes = BUILD / 'classes'; shutil.rmtree(classes, ignore_errors=True); classes.mkdir()
    sources = list((ROOT / 'app/src').rglob('*.java'))
    if not args.dev_server:  # release: no test-server client at all, only a no-op stand-in for its two calls
        sources = [f for f in sources if f.name != 'TtrDevServer.java']
        stub = BUILD / 'release-stubs/org/ttrmobile/TtrDevServer.java'; stub.parent.mkdir(parents=True, exist_ok=True)
        stub.write_text('package org.ttrmobile;\n/** Release build: the test-server client (tools/dev_server.py) is not included. */\n'
                        'public final class TtrDevServer { private TtrDevServer() { }\n'
                        '    public static void start(android.app.Activity a) { } public static void stop(android.app.Activity a) { } }\n', encoding='utf-8')
        sources.append(stub)
    run(java.with_name('javac.exe'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', android, '-d', classes, *sources)
    dex = BUILD / 'dex'; dex.mkdir(exist_ok=True)
    run(java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '29', '--lib', android, '--output', dex, *classes.rglob('*.class'))
    unsigned = BUILD / 'unsigned.apk'
    # The version name carries the source commit (and +dirty for uncommitted changes) so diagnostics show the exact build.
    commit = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', '--short', 'HEAD'], capture_output=True, text=True).stdout.strip() or 'unknown'
    dirty = subprocess.run(['git', '-C', str(ROOT), 'status', '--porcelain', '--untracked-files=no'], capture_output=True, text=True).stdout.strip()
    # A release is named just by its version, so it must come from committed source; test builds carry the commit.
    if not args.dev_server and dirty: print('Warning: uncommitted changes; this build is labelled', VERSION + '-dev+' + commit + '-dirty')
    manifest = ROOT / 'app/AndroidManifest.xml'
    if args.dev_server:  # plain HTTP to the PC, and permission to install the PC's newer builds
        text = manifest.read_text(encoding='utf-8')
        text = text.replace('android:usesCleartextTraffic="false"', 'android:usesCleartextTraffic="true"').replace('android:label="TTR Mobile"', 'android:label="TTR Mobile Dev"')
        text = text.replace('<uses-permission android:name="android.permission.INTERNET" />',
                            '<uses-permission android:name="android.permission.INTERNET" /><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />')
        manifest = BUILD / 'AndroidManifest-dev-server.xml'; manifest.write_text(text, encoding='utf-8')
    else:  # normal builds are not debuggable (fewer Play Protect flags); test-server builds keep it for run-as
        text = manifest.read_text(encoding='utf-8')
        assert 'android:debuggable="true"' in text
        manifest = BUILD / 'AndroidManifest-release.xml'; manifest.write_text(text.replace('android:debuggable="true"', 'android:debuggable="false"'), encoding='utf-8')
    # App icon (tools/make_icon.py): compiled resources linked into the APK.
    resources = BUILD / 'resources.zip'
    run(bt / 'aapt2.exe', 'compile', '--dir', ROOT / 'build/app-res', '-o', resources)
    run(bt / 'aapt2.exe', 'link', '-I', android, '--manifest', manifest, '-R', resources, '--auto-add-overlay',
        '--version-name', VERSION if not args.dev_server and not dirty else VERSION + '-dev+' + commit + ('-dirty' if dirty else ''), '--replace-version',
        *(['--rename-manifest-package', DEV_PACKAGE] if args.dev_server else []), '-o', unsigned)
    stripper = NDK_BIN / 'llvm-strip.exe'
    libs = {'libXlorie.so': prerequisites['x11'], 'libttrgl4es.so': prerequisites['gl4es'], 'libttrbz2.so': prerequisites['bzip2'],
            'libttrexec.so': out / 'libttrexec.so', 'libandroid-sysvshm.so': out / 'libandroid-sysvshm.so', 'libttrzstd.so': out / 'libttrzstd.so', 'libasound.so': out / 'libasound.so'}
    libs.update({n: ROOT / 'build/linux-libraries/native' / n for n in ('libX11.so', 'libXext.so', 'libxcb.so', 'libXau.so', 'libXdmcp.so', 'libXrender.so', 'libXfixes.so', 'libXrandr.so', 'libXcursor.so')})
    staged = BUILD / 'lib'; staged.mkdir(exist_ok=True)
    for name, path in libs.items(): shutil.copy2(path, staged / name); run(stripper, '--strip-unneeded', staged / name)
    licenses = {'Termux-X11-GPL-3.0.txt': TOOLS / 'termux-x11-source/LICENSE', 'GL4ES-LICENSE.txt': TOOLS / 'gl4es-source-host/LICENSE',
                'bzip2-LICENSE.txt': ROOT / 'native/bzip2/vendor/bzip2-1.0.8/LICENSE', 'zstd-LICENSE.txt': TOOLS / 'zstd-source/zstd-1.5.6/LICENSE',
                'xz-embedded-COPYING.txt': TOOLS / 'xz-embedded-source/COPYING'}
    inventory = {'version': VERSION, 'native': {n: hashlib.sha256((staged / n).read_bytes()).hexdigest() for n in libs}}
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as z:
        z.write(dex / 'classes.dex', 'classes.dex')
        z.write(prerequisites['xkb'], 'assets/runtime/xkb.zip')
        z.write(prerequisites['vulkan'], 'assets/runtime/vulkan-stack.tar.zst')
        z.writestr('assets/runtime/vulkan-stack.sha256', hashlib.sha256(prerequisites['vulkan'].read_bytes()).hexdigest())
        z.write(prerequisites['bootstrap'], 'assets/ttr/TtrBootstrap.exe')
        z.writestr('assets/runtime/components.json', json.dumps(inventory, indent=2))
        if args.dev_server: z.writestr('assets/dev-server.txt', args.dev_server)
        for name in libs: z.write(staged / name, 'lib/arm64-v8a/' + name)
        for name, p in licenses.items(): z.write(p, 'assets/licenses/' + name)
        # Launch-screen artwork: the background (app/assets) and the logo and buttons extracted from TTR's installer.
        for p in [*(ROOT / 'app/assets/ttr/art').glob('*.png'), *(ROOT / 'build/ttr-art').glob('*.png')]:
            if p.is_file(): z.write(p, 'assets/ttr/art/' + p.name)
    aligned = BUILD / 'aligned.apk'; run(bt / 'zipalign.exe', '-f', '-p', '4', unsigned, aligned)
    output = ROOT / 'dist' / f'TTR-Mobile-{VERSION}{"-devserver" if args.dev_server else ""}.apk'
    output.parent.mkdir(exist_ok=True)
    if args.dev_server:
        if not (TOOLS / 'ttr-development.jks').exists():  # a throwaway local key for test builds
            run(java.with_name('keytool.exe'), '-genkeypair', '-keystore', TOOLS / 'ttr-development.jks', '-storepass', 'android', '-keypass', 'android',
                '-alias', 'ttr-mobile-dev', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=TTR Mobile Local Development')
        signing = ['--ks', TOOLS / 'ttr-development.jks', '--ks-pass', 'pass:android', '--ks-key-alias', 'ttr-mobile-dev']
    else:  # the password never goes on the command line: from the environment, or typed at apksigner's prompt
        signing = ['--ks', RELEASE_KEYSTORE, '--ks-key-alias', 'ttr-mobile'] + (['--ks-pass', 'env:TTR_RELEASE_PASSWORD'] if os.environ.get('TTR_RELEASE_PASSWORD') else [])
    run(java, '-jar', bt / 'lib/apksigner.jar', 'sign', *signing, '--out', output, aligned)
    run(java, '-jar', bt / 'lib/apksigner.jar', 'verify', output)
    output.with_suffix('.apk.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest() + '  ' + output.name + '\n')
    print('Built standalone Windows app:', output)


if __name__ == '__main__': main()
