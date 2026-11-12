"""HTTP API + import-folder watcher + periodic Home Assistant publishing."""
from __future__ import annotations

import hmac
import html
import json
import logging
import signal
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import __version__
from .config import Config
from .ingest import UnsupportedFile, ingest_file
from .publish import make_publishers
from .store import Store

log = logging.getLogger("cmfserver")


class Service:
    def __init__(self, cfg: Config, publishers=None):
        self.cfg = cfg
        self.store = Store(cfg.db_path)
        self.publishers = make_publishers(cfg) if publishers is None else publishers
        self.stop = threading.Event()
        self.ingest_lock = threading.Lock()

    def today(self):
        return datetime.now().astimezone().date()

    def state(self) -> dict:
        return self.store.latest_state(self.today())

    def publish(self) -> None:
        payload = self.state()
        for p in self.publishers:
            try:
                p.publish(payload)
            except Exception:  # noqa: BLE001
                log.exception("Veröffentlichen fehlgeschlagen")

    def ingest(self, path: Path, name: str) -> dict:
        with self.ingest_lock:
            res = ingest_file(self.store, path, name)
        log.info("Import %s: %s %s", name, res["kind"], res["counts"])
        self.publish()
        return res

    # ------------------------------------------------------------ background
    def watch_import_dir(self) -> None:
        d = self.cfg.import_dir
        if not d:
            return
        d.mkdir(parents=True, exist_ok=True)
        seen: dict[str, tuple[float, int]] = {}
        state_file = self.cfg.data_dir / "imported.json"
        try:
            seen = {k: tuple(v) for k, v in json.loads(state_file.read_text()).items()}
        except (OSError, ValueError):
            pass
        log.info("Überwache Import-Ordner %s", d)
        while not self.stop.is_set():
            for p in sorted(d.rglob("*")):
                if not p.is_file() or p.name.startswith("."):
                    continue
                try:
                    st = p.stat()
                except OSError:
                    continue
                sig = (st.st_mtime, st.st_size)
                if seen.get(str(p)) == sig or time.time() - st.st_mtime < 10:  # unchanged or still being written
                    continue
                try:
                    self.ingest(p, p.name)
                except UnsupportedFile as e:
                    log.warning("%s übersprungen: %s", p.name, e)
                except Exception:  # noqa: BLE001
                    log.exception("Import von %s fehlgeschlagen", p.name)
                seen[str(p)] = sig
                state_file.write_text(json.dumps(seen))
            self.stop.wait(self.cfg.watch_interval)

    def republish_loop(self) -> None:
        while not self.stop.wait(self.cfg.publish_interval):
            self.publish()  # day rollover for "today" sensors, reconnects


def make_handler(svc: Service):
    cfg = svc.cfg

    class Handler(BaseHTTPRequestHandler):
        server_version = f"cmfserver/{__version__}"

        def log_message(self, fmt, *args):
            log.debug("%s %s", self.address_string(), fmt % args)

        def _send(self, code: int, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else (
                json.dumps(body, ensure_ascii=False, default=str, indent=1).encode() if ctype == "application/json"
                else str(body).encode())
            self.send_response(code)
            self.send_header("Content-Type", ctype + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _authorized(self) -> bool:
            if not cfg.api_token:
                return True
            auth = self.headers.get("Authorization", "")
            token = auth[7:] if auth.lower().startswith("bearer ") else self.headers.get("X-Api-Token", "")
            if not token:
                token = parse_qs(urlparse(self.path).query).get("token", [""])[0]
            return hmac.compare_digest(token.encode(), cfg.api_token.encode())

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/healthz":
                return self._send(200, {"ok": True, "version": __version__})
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            if url.path in ("/", "/index.html"):
                return self._send(200, self._page(), "text/html")
            if url.path == "/api/latest":
                return self._send(200, svc.state())
            if url.path == "/api/history":
                qs = parse_qs(url.query)
                metric = qs.get("metric", ["heart_rate"])[0]
                days = int(qs.get("days", ["7"])[0])
                since = int((datetime.now(timezone.utc) - timedelta(days=days)).timestamp())
                return self._send(200, svc.store.history(metric, since))
            if url.path == "/api/uploads":
                return self._send(200, svc.store.uploads())
            return self._send(404, {"error": "not found"})

        def do_POST(self):
            url = urlparse(self.path)
            if not self._authorized():
                return self._send(401, {"error": "unauthorized"})
            if url.path == "/api/publish":
                svc.publish()
                return self._send(200, {"ok": True})
            if url.path != "/api/upload":
                return self._send(404, {"error": "not found"})
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0:
                return self._send(411, {"error": "Content-Length erforderlich"})
            if length > cfg.max_upload_mb * 1024 * 1024:
                return self._send(413, {"error": f"größer als {cfg.max_upload_mb} MB"})
            name = parse_qs(url.query).get("name", [None])[0] or self.headers.get("X-File-Name") or "upload"
            ctype = self.headers.get("Content-Type", "")
            with tempfile.TemporaryDirectory(prefix="upload-") as tmp:
                path = Path(tmp) / "upload.bin"
                with path.open("wb") as fh:
                    remaining = length
                    while remaining:
                        chunk = self.rfile.read(min(1 << 20, remaining))
                        if not chunk:
                            break
                        fh.write(chunk)
                        remaining -= len(chunk)
                if ctype.startswith("multipart/form-data"):
                    try:
                        path, name = _extract_multipart(path, ctype, Path(tmp))
                    except ValueError as e:
                        return self._send(400, {"error": str(e)})
                try:
                    res = svc.ingest(path, Path(name).name)
                except UnsupportedFile as e:
                    return self._send(415, {"error": str(e)})
                except Exception as e:  # noqa: BLE001
                    log.exception("Upload fehlgeschlagen")
                    return self._send(500, {"error": str(e)})
            if "text/html" in self.headers.get("Accept", ""):
                return self._send(200, self._page(f"Import OK: {res['counts']}"), "text/html")
            return self._send(200, {"ok": True, **res, "state": svc.state()["state"]})

        def _page(self, msg: str = "") -> str:
            st = svc.state()["state"]
            rows = "".join(f"<tr><td>{html.escape(k)}</td><td>{html.escape(str(v))}</td></tr>" for k, v in st.items())
            ups = "".join(f"<tr><td>{datetime.fromtimestamp(u['ts']).isoformat(' ', 'seconds')}</td>"
                          f"<td>{html.escape(u['name'] or '')}</td><td>{html.escape(u['kind'] or '')}</td>"
                          f"<td>{html.escape(u['summary'] or '')}</td></tr>" for u in svc.store.uploads(10))
            token = parse_qs(urlparse(self.path).query).get("token", [""])[0]
            pubs = ", ".join(type(p).__name__.replace("Publisher", "") for p in svc.publishers) or "keine"
            return f"""<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CMF Watch Bridge</title>
<style>:root{{--bg:#fff;--fg:#222;--line:#ddd}}@media (prefers-color-scheme:dark){{:root{{--bg:#151515;--fg:#eee;--line:#333}}}}
body{{background:var(--bg);color:var(--fg);font:14px system-ui,sans-serif;margin:16px;max-width:900px}}
table{{border-collapse:collapse;width:100%;margin-bottom:20px}}td{{border-bottom:1px solid var(--line);padding:4px 6px}}
.msg{{padding:8px;border:1px solid var(--line)}}.wrap{{overflow-x:auto}}</style></head><body>
<h1>CMF Watch → Home Assistant</h1>
{f'<p class="msg">{html.escape(msg)}</p>' if msg else ''}
<p>Weiterleitung an: {html.escape(pubs)}</p>
<form method="post" action="/api/upload?token={html.escape(token)}" enctype="multipart/form-data">
<input type="file" name="file" required> <button>Hochladen</button></form>
<p style="opacity:.7">ZIP aus der CMF-Export-App, Gadgetbridge-Datenbank oder Gadgetbridge-ZIP.</p>
<h2>Aktuelle Werte</h2><div class="wrap"><table>{rows}</table></div>
<h2>Letzte Importe</h2><div class="wrap"><table>{ups}</table></div></body></html>"""

    return Handler


def _extract_multipart(path: Path, ctype: str, tmp: Path):
    """Return (file path, file name) of the first file part of a multipart body."""
    from email.parser import BytesParser
    from email.policy import HTTP

    # Parse the stored body as a MIME message (browser form uploads; the app sends raw bytes).
    raw = path.read_bytes()
    msg = BytesParser(policy=HTTP).parsebytes(b"Content-Type: " + ctype.encode() + b"\r\n\r\n" + raw)
    for part in msg.iter_parts():
        fname = part.get_filename()
        if fname:
            out = tmp / "part.bin"
            out.write_bytes(part.get_payload(decode=True) or b"")
            return out, fname
    raise ValueError("keine Datei im Formular")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = Config()
    if not cfg.api_token:
        log.warning("API_TOKEN ist nicht gesetzt – die API ist ohne Anmeldung erreichbar!")
    svc = Service(cfg)
    threading.Thread(target=svc.watch_import_dir, daemon=True, name="watch").start()
    threading.Thread(target=svc.republish_loop, daemon=True, name="republish").start()
    svc.publish()
    httpd = ThreadingHTTPServer(("0.0.0.0", cfg.port), make_handler(svc))

    def shutdown(*_):
        svc.stop.set()
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    log.info("cmfserver %s lauscht auf Port %s", __version__, cfg.port)
    httpd.serve_forever()
    for p in svc.publishers:
        p.close()


if __name__ == "__main__":
    main()
