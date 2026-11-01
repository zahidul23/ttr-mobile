"""Download the project-local toolchain and pinned sources into .tools (no global installs).

Run once (and again after tools/sources.json changes):  python tools/bootstrap.py
Needs git, and the Python standard library only.
"""
import concurrent.futures
import hashlib
import json
import subprocess
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
from common import ROOT, TOOLS, fetch, extract, run

SOURCES = json.loads((ROOT / 'tools/sources.json').read_text())


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'TTR-Mobile-build'}), timeout=180)


def java():
    metadata = json.load(get('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'))
    package = metadata[0]['binary']['package']
    extract(fetch(package['link'], 'jdk17.zip', package['checksum']), TOOLS / 'jdk')


def sdk():
    xml = ET.fromstring(get('https://dl.google.com/android/repository/repository2-1.xml').read())
    for package_id, dest in [('build-tools;35.0.0', 'build-tools'), ('platforms;android-35', 'platform')]:
        package = next(p for p in xml if p.tag.endswith('remotePackage') and p.get('path') == package_id)
        archive = next(a for a in package.find('archives') if a.findtext('host-os') in (None, 'windows'))
        complete = archive.find('complete'); checksum = complete.find('checksum')
        path = fetch('https://dl.google.com/android/repository/' + complete.findtext('url'), dest + '.zip', checksum.text, checksum.get('type', 'sha1'))
        extract(path, TOOLS / dest)


def zig():
    metadata = json.load(get('https://ziglang.org/download/index.json'))['0.15.2']['x86_64-windows']
    extract(fetch(metadata['tarball'], 'zig.zip', metadata['shasum']), TOOLS / 'zig')


def archive(key):
    a = SOURCES['archives'][key]
    algorithm = 'sha1' if 'sha1' in a else 'sha256'
    return fetch(a['url'], a['file'], a[algorithm], algorithm)


def archives():
    extract(archive('ndk'), TOOLS / 'android-ndk')
    zstd = archive('zstd'); target = TOOLS / 'zstd-source'
    if not target.exists():
        with tarfile.open(zstd) as t: t.extractall(target, filter='data')
    archive('ttr-installer')
    # Full 7-Zip (it reads the official installer's NSIS format), unpacked from its setup file with 7zr.
    seven_zr, setup = archive('7zr'), archive('7zip')
    if not (TOOLS / '7zip/7z.exe').exists():
        run(seven_zr, 'x', '-o' + str(TOOLS / '7zip'), setup, '7z.exe', '7z.dll', '-y')


def git_sources():
    for name, source in SOURCES['git'].items():
        path = TOOLS / name
        if not path.exists():
            run('git', 'clone', '--no-checkout', '--filter=blob:none', source['url'], path)
            run('git', '-C', path, 'checkout', '--detach', source['commit'])
        if subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip() != source['commit']:
            raise RuntimeError(name + ' must be at ' + source['commit'])
    run('git', '-C', TOOLS / 'termux-x11-source', 'submodule', 'update', '--init', '--recursive', '--jobs', '8', '--depth', '1')


if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for future in [pool.submit(job) for job in (java, sdk, zig, archives)]:
            future.result()
    git_sources()
    print('Toolchain and sources ready in ' + str(TOOLS))
