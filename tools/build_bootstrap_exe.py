"""Build TtrBootstrap.exe, the small Windows program that starts the game inside Wine (windows/bootstrap.c)."""
import os
from common import ROOT, BUILD, TOOLS, find, run


def main():
    zig = find(TOOLS / 'zig', 'zig.exe')
    BUILD.mkdir(exist_ok=True)
    env = dict(os.environ, ZIG_GLOBAL_CACHE_DIR=str(BUILD / 'zig-global'), ZIG_LOCAL_CACHE_DIR=str(BUILD / 'zig-cache'))
    run(zig, 'cc', '-target', 'x86_64-windows-gnu', '-Os', '-s', '-municode', '-Wl,--subsystem,windows', ROOT / 'windows/bootstrap.c',
        '-o', BUILD / 'TtrBootstrap.exe', '-luser32', '-lgdi32', '-ladvapi32', env=env)


if __name__ == '__main__': main()
