"""Host tests (no phone needed):  python tools/test.py"""
from common import ROOT, BUILD, TOOLS, find, run

TESTS = [  # (test class, app sources it needs)
    ('TtrControlLayoutTest', ['TtrControlLayout']),
    ('TtrLoginTest', ['TtrPaths', 'TtrLoginFlow']),
]


def main():
    java = find(TOOLS / 'jdk', 'java.exe'); out = BUILD / 'host-tests'; out.mkdir(parents=True, exist_ok=True)
    for test, sources in TESTS:
        run(java.with_name('javac.exe'), '-encoding', 'UTF-8', '-d', out, *(ROOT / 'app/src/org/ttrmobile' / f'{s}.java' for s in sources), ROOT / 'tests' / f'{test}.java')
        run(java, '-cp', out, 'org.ttrmobile.' + test, out)


if __name__ == '__main__': main()
