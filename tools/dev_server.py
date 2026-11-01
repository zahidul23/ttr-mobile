"""Temporary test server for phones on the same network (no adb needed).

  python tools/dev_server.py                     # serves on port 8765
  python tools/build_app.py --dev-server http://<this PC>:8765

Open http://<this PC>:8765/ on the phone once and install the test build. After that the app
  - sends its diagnostics report and log files here every few seconds
    (diagnostics/dev-server/<device>/; latest copies plus a history of reports),
  - offers to install whenever a newer dist/TTR-Mobile-*-devserver.apk is built,
  - copies developer switch files from diagnostics/dev-server/switches.json into no_backup/ttr
    on the phone, e.g. {"winedebug": "+process,+seh", "gl-env": "FEX_TSOENABLED=0\\n", "present-off": null}
    (null removes a file). Switches apply to the next game launch.
"""
import argparse, hashlib, html, json, re, socket, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, quote

ROOT = Path(__file__).resolve().parent.parent
LOGS = ROOT / 'diagnostics/dev-server'
SWITCHES = LOGS / 'switches.json'
SAFE = re.compile(r'^[A-Za-z0-9._-]{1,100}$')
_hash_cache = {}


def newest_apk():
    apks = sorted((ROOT / 'dist').glob('TTR-Mobile-*-devserver.apk'), key=lambda p: p.stat().st_mtime)
    return apks[-1] if apks else None


def release_apk():
    """Newest plain release (TTR-Mobile-X.Y.Z.apk): offered for download only, never as an update."""
    apks = [p for p in (ROOT / 'dist').glob('TTR-Mobile-*.apk') if re.fullmatch(r'TTR-Mobile-\d+\.\d+\.\d+\.apk', p.name)]
    return max(apks, key=lambda p: tuple(int(x) for x in p.stem.split('-')[-1].split('.'))) if apks else None


def apk_hash(path):
    st = path.stat(); key = (str(path), st.st_mtime_ns, st.st_size)
    if key not in _hash_cache: _hash_cache.clear(); _hash_cache[key] = hashlib.sha256(path.read_bytes()).hexdigest()
    return _hash_cache[key]


def switches():
    if not SWITCHES.is_file(): return {}, 0
    try: data = json.loads(SWITCHES.read_text(encoding='utf-8'))
    except ValueError as e: print('switches.json is not valid JSON:', e); return {}, 0
    return data, SWITCHES.stat().st_mtime_ns // 1_000_000 % 2_000_000_000  # changes whenever the file is saved


class Handler(BaseHTTPRequestHandler):
    seen = {}  # device -> last contact time

    def reply(self, code, body, kind='application/json'):
        if isinstance(body, str): body = body.encode('utf-8')
        self.send_response(code); self.send_header('Content-Type', kind); self.send_header('Content-Length', str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def log_message(self, fmt, *args):  # quiet: the phone polls every few seconds
        if not self.path.startswith(('/api/state', '/api/log')): super().log_message(fmt, *args)

    def device(self, q):
        d = q.get('device', [''])[0]
        return d if SAFE.match(d) else None

    def do_GET(self):
        url = urlparse(self.path); q = parse_qs(url.query)
        if url.path == '/api/state':
            d = self.device(q)
            if d:
                if d not in self.seen: print(time.strftime('%H:%M:%S'), 'phone connected:', d)
                self.seen[d] = time.time()
            apk = newest_apk(); config, rev = switches()
            return self.reply(200, json.dumps({'apk_sha256': apk_hash(apk) if apk else '', 'apk_name': apk.name + ', built ' + time.strftime('%H:%M:%S', time.localtime(apk.stat().st_mtime)) if apk else '',
                                               'config': config, 'config_rev': rev}))
        if url.path in ('/apk', '/release'):
            apk = newest_apk() if url.path == '/apk' else release_apk()
            if not apk: return self.reply(404, 'That build has not been made yet.', 'text/plain')
            print(time.strftime('%H:%M:%S'), 'sending', apk.name, 'to', self.client_address[0])
            data = apk.read_bytes(); self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive'); self.send_header('Content-Length', str(len(data)))
            self.send_header('Content-Disposition', f'attachment; filename="{apk.name}"'); self.end_headers(); self.wfile.write(data); return
        parts = url.path.strip('/').split('/')
        if len(parts) == 3 and parts[0] == 'logs' and all(SAFE.match(p) for p in parts[1:]):
            f = LOGS / parts[1] / parts[2]
            if f.is_file(): return self.reply(200, f.read_bytes(), 'text/plain; charset=utf-8')
            return self.reply(404, 'not found', 'text/plain')
        if url.path == '/': return self.reply(200, self.index(), 'text/html; charset=utf-8')
        self.reply(404, 'not found', 'text/plain')

    def do_POST(self):
        url = urlparse(self.path); q = parse_qs(url.query)
        d, name = self.device(q), q.get('name', [''])[0]
        if url.path != '/api/log' or not d or not SAFE.match(name): return self.reply(400, '{}')
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        folder = LOGS / d; folder.mkdir(parents=True, exist_ok=True)
        target = folder / name
        if name == 'report.txt':  # history of reports, kept only when something besides free memory changed
            meaningful = lambda b: re.sub(rb'(?m)^Memory .*$', b'', b)
            if not target.is_file() or meaningful(target.read_bytes()) != meaningful(body):
                history = folder / 'history'; history.mkdir(exist_ok=True)
                (history / time.strftime('report-%Y%m%d-%H%M%S.txt')).write_bytes(body)
                phases = re.findall(rb'Windows phase: ([^\n]*)', body)
                print(time.strftime('%H:%M:%S'), d, 'report changed' + (': last phase ' + phases[-1].decode('utf-8', 'replace') if phases else ''))
        target.write_bytes(body)
        self.reply(200, '{}')

    def index(self):
        apk, release = newest_apk(), release_apk(); rows = []
        if LOGS.is_dir():
            for folder in sorted(p for p in LOGS.iterdir() if p.is_dir()):
                last = self.seen.get(folder.name)
                links = ' · '.join(f'<a href="/logs/{quote(folder.name)}/{quote(f.name)}">{html.escape(f.name)}</a>' for f in sorted(folder.iterdir()) if f.is_file())
                rows.append(f'<li><b>{html.escape(folder.name)}</b> {"(last seen " + time.strftime("%H:%M:%S", time.localtime(last)) + ")" if last else ""}<br>{links}</li>')
        download = ('<h2>Test build</h2><p>Sends its logs to this PC and offers newer test builds automatically.</p>' +
                    (f'<p><a class="button" href="/apk">Download {html.escape(apk.name)}</a><br><small>built {time.ctime(apk.stat().st_mtime)}</small></p>'
                     if apk else '<p>No test build yet: run <code>python tools/build_app.py --dev-server http://…:8765</code></p>'))
        download += ('<h2>Release</h2><p>The normal app: no logs sent here and no update prompts.</p>' +
                     (f'<p><a class="button release" href="/release">Download {html.escape(release.name)}</a><br><small>built {time.ctime(release.stat().st_mtime)}</small></p>'
                      if release else '<p>No release built yet: run <code>python tools/build_app.py</code></p>'))
        return f'''<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TTR test server</title>
<style>body{{font:16px system-ui;margin:16px;max-width:720px;background:#fff;color:#111}}a.button{{display:inline-block;padding:12px 18px;background:#1565c0;color:#fff;border-radius:8px;text-decoration:none}}a.release{{background:#2e7d32}}li{{margin:10px 0}}</style>
<h1>TTR test server</h1>{download}<h2>Phones</h2><ul>{"".join(rows) or "<li>None yet</li>"}</ul>'''


def lan_addresses():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(('10.255.255.255', 1)); a = s.getsockname()[0]; s.close(); return [a]
    except OSError: return [socket.gethostbyname(socket.gethostname())]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--port', type=int, default=8765)
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)
    LOGS.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('0.0.0.0', args.port), Handler)
    for a in lan_addresses(): print(f'TTR test server: http://{a}:{args.port}/')
    apk = newest_apk(); print('Serving', apk.name if apk else 'no test build yet', '| logs in', LOGS)
    try: server.serve_forever()
    except KeyboardInterrupt: pass


if __name__ == '__main__': main()
