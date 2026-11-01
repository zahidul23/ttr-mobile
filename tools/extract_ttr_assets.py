"""Extract unchanged artwork from the pinned official launcher into build/ttr-art; no executable is run.

The artwork belongs to the Toontown Rewritten team, so it is extracted at build time rather than kept here.
"""
import hashlib
import json
import struct
import subprocess
import zlib
from common import ROOT, TOOLS

INSTALLER = json.loads((ROOT / 'tools/sources.json').read_text())['archives']['ttr-installer']


def main():
    installer = TOOLS / INSTALLER['file']
    if hashlib.sha256(installer.read_bytes()).hexdigest() != INSTALLER['sha256']:
        raise RuntimeError('Unexpected official launcher installer')
    folder = ROOT / 'build/ttr-launcher-assets'
    subprocess.run([str(TOOLS / '7zip/7z.exe'), 'x', str(installer), '-o' + str(folder), 'Launcher.exe', '-y'], check=True, stdout=subprocess.DEVNULL)
    data = (folder / 'Launcher.exe').read_bytes()
    cookie = data.rfind(b'MEI\x0c\x0b\x0a\x0b\x0e')
    magic, length, offset, size, python, library = struct.unpack('!8sIIII64s', data[cookie:cookie + 88])
    start = cookie + 88 - length
    toc = data[start + offset:start + offset + size]
    target = ROOT / 'build/ttr-art'; target.mkdir(parents=True, exist_ok=True)
    wanted = {'resources\\icons\\icon_256x256.png': 'icon.png',
              'resources\\GoButton\\normal.png': 'play.png', 'resources\\MButton\\normal.png': 'minimize.png',
              'resources\\XButton\\normal.png': 'close.png'}
    i = 0
    while i < len(toc):
        n, pos, packed, raw, compressed, kind = struct.unpack('!iIIIBc', toc[i:i + 18])
        name = toc[i + 18:i + n].rstrip(b'\0').decode()
        if name in wanted:
            content = data[start + pos:start + pos + packed]
            if compressed: content = zlib.decompress(content)
            if len(content) != raw: raise RuntimeError('Invalid launcher asset')
            (target / wanted[name]).write_bytes(content)
        i += n
    if not all((target / name).is_file() for name in wanted.values()): raise RuntimeError('Missing launcher artwork')
    print('Extracted original TTR launcher artwork')


if __name__ == '__main__': main()
