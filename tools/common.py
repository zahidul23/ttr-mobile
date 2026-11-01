"""Paths and helpers shared by the build scripts."""
import hashlib
import subprocess
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'   # downloaded toolchain and pinned sources (git-ignored)
BUILD = ROOT / 'build'    # intermediate output (git-ignored)
NDK_BIN = TOOLS / 'android-ndk/android-ndk-r28c/toolchains/llvm/prebuilt/windows-x86_64/bin'


def find(root, name):
    return next(Path(root).rglob(name))


def run(*args, **kw):
    print('Running', Path(str(args[0])).name, flush=True)
    kw.setdefault('cwd', ROOT)
    subprocess.run([str(a) for a in args], check=True, **kw)


def fetch(url, name, digest=None, algorithm='sha256'):
    """Downloads url to .tools/name once, checking its digest when one is given."""
    TOOLS.mkdir(exist_ok=True)
    target = TOOLS / name
    if target.exists() and (not digest or hashlib.new(algorithm, target.read_bytes()).hexdigest() == digest):
        return target
    print('Downloading ' + name, flush=True)
    temp = target.with_suffix(target.suffix + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': 'TTR-Mobile-build'})
    with urllib.request.urlopen(request, timeout=300) as response, temp.open('wb') as out:
        while chunk := response.read(1024 * 1024):
            out.write(chunk)
    if digest and hashlib.new(algorithm, temp.read_bytes()).hexdigest() != digest:
        temp.unlink()
        raise RuntimeError('Checksum mismatch: ' + name)
    temp.replace(target)
    return target


def extract(archive, dest):
    if dest.exists():
        return
    dest.mkdir(parents=True)
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            if not (dest / member.filename).resolve().is_relative_to(dest.resolve()):
                raise RuntimeError('Unsafe archive entry')
        z.extractall(dest)


def checked_sources(*names):
    """Fails unless each pinned git source is checked out at its recorded commit."""
    import json
    pins = json.loads((ROOT / 'tools/sources.json').read_text())['git']
    for name in names or pins:
        source = TOOLS / name
        if not source.exists():
            raise RuntimeError('Missing ' + name + '; run python tools/bootstrap.py')
        actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != pins[name]['commit']:
            raise RuntimeError('Source revision mismatch: ' + name)
