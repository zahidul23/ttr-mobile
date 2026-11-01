"""Package the keyboard maps the app's X server needs (xkeyboard-config) as build/xkb/xkb.zip."""
import shutil
import subprocess
import sys
import zipfile
from common import BUILD, TOOLS, run, checked_sources

OUT = BUILD / 'xkb'


def main():
    checked_sources('xkeyboard-config-source')
    source = TOOLS / 'xkeyboard-config-source'
    data = OUT / 'xkb-source'
    shutil.copytree(source, data, dirs_exist_ok=True, ignore=shutil.ignore_patterns('.git', '__pycache__'))
    # Git on Windows checks symlinks out as small text files; replace them with the files they point to.
    for entry in subprocess.check_output(['git', '-C', str(source), 'ls-files', '-s'], text=True).splitlines():
        mode, rest = entry.split(' ', 1)
        if mode != '120000': continue
        p = data / rest.split('\t', 1)[1]; target = (p.parent / p.read_text().strip()).resolve()
        if not target.is_relative_to(data.resolve()): raise RuntimeError('Unsafe XKB symlink')
        p.write_bytes(target.read_bytes())
    rules = OUT / 'evdev'
    run(sys.executable, '-m', 'rules.generator', 'rules', '--ruleset', 'evdev', '--version', 'v2', '--output', rules, data / 'rules/rules.in', cwd=data)
    with zipfile.ZipFile(OUT / 'xkb.zip', 'w', compression=zipfile.ZIP_DEFLATED) as z:
        for folder in ('compat', 'geometry', 'keycodes', 'symbols', 'types'):
            for p in (data / folder).rglob('*'):
                if p.is_file() and p.name != 'meson.build': z.write(p, p.relative_to(data).as_posix())
        z.write(rules, 'rules/evdev'); z.write(data / 'COPYING', 'COPYING')
    print('Built', OUT / 'xkb.zip')


if __name__ == '__main__': main()
