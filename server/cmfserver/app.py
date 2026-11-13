"""HTTP server: website (login + dashboards), JSON API, uploads, import folder, Home Assistant publishing."""
from __future__ import annotations

import hmac
import json
import logging
import mimetypes
import re
import signal
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

from . import __version__, analysis
from .config import Config
from .ingest import UnsupportedFile, ingest_file
from .llm import LLM, LLMError
from .publish import make_publishers
from .store import SESSION_DAYS, Store, now_ts

log = logging.getLogger("cmfserver")
WEB = Path(__file__).parent / "web"
COOKIE = "cmf_session"
USERNAME_RE = re.compile(r"^[A-Za-z0-9_.-]{3,32}$")
CSP = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
       "img-src 'self' data: blob: https://tiles.openfreemap.org; "
       "connect-src 'self' https://tiles.openfreemap.org; worker-src blob:; child-src blob:; "
       "frame-ancestors 'none'; base-uri 'none'; form-action 'self'")


class HttpError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class Service:
    def __init__(self, cfg: Config, publishers=None):
        self.cfg = cfg
        self.store = Store(cfg.db_path)
        self.publishers = make_publishers(cfg) if publishers is None else publishers
        self.llm = LLM(cfg)
        self.stop = threading.Event()
        self.ingest_lock = threading.Lock()
        self.failed_logins: dict[str, list[float]] = {}

    def today(self):
        return datetime.now().astimezone().date()

    def state(self, uid: int) -> dict:
        return self.store.latest_state(uid, self.today())

    def publish(self, uid: int | None = None) -> None:
        users = [self.store.user(uid)] if uid else self.store.users()
        for u in users:
            if not u or not u.get("publish_ha", 1):
                continue
            payload = self.state(u["id"])
            for p in self.publishers:
                try:
                    p.publish(payload, u)
                except Exception:  # noqa: BLE001
                    log.exception("Veröffentlichen fehlgeschlagen")

    def ingest(self, uid: int, path: Path, name: str) -> dict:
        with self.ingest_lock:
            res = ingest_file(self.store, uid, path, name)
        log.info("Import %s für Benutzer %s: %s %s", name, uid, res["kind"], res["counts"])
        self.publish(uid)
        return res

    def user_for_token(self, token: str) -> dict | None:
        u = self.store.user_by_token(token)
        if u:
            return u
        # API_TOKEN from the environment (cmfserver 1.x setups) keeps working for the first account
        if self.cfg.api_token and token and hmac.compare_digest(token.encode(), self.cfg.api_token.encode()):
            return self.store.user(1)
        return None

    # -------------------------------------------------------------------- background
    def watch_import_dir(self) -> None:
        """Files in /import belong to the first account, files in /import/<username>/ to that user."""
        d = self.cfg.import_dir
        if not d:
            return
        try:
            d.mkdir(parents=True, exist_ok=True)
        except OSError:
            pass
        seen: dict[str, tuple] = {}
        state_file = self.cfg.data_dir / "imported.json"
        try:
            seen = {k: tuple(v) for k, v in json.loads(state_file.read_text()).items()}
        except (OSError, ValueError):
            pass
        log.info("Überwache Import-Ordner %s", d)
        while not self.stop.is_set():
            for p in sorted(d.rglob("*")) if d.exists() else []:
                if not p.is_file() or p.name.startswith("."):
                    continue
                try:
                    st = p.stat()
                except OSError:
                    continue
                sig = (st.st_mtime, st.st_size)
                if seen.get(str(p)) == sig or time.time() - st.st_mtime < 10:
                    continue
                rel = p.relative_to(d).parts
                user = None
                if len(rel) > 1:
                    user = next((u for u in self.store.users() if u["username"].lower() == rel[0].lower()), None)
                user = user or self.store.user(1)
                if user is None:
                    continue  # no account yet - try again later
                try:
                    self.ingest(user["id"], p, p.name)
                except UnsupportedFile as e:
                    log.warning("%s übersprungen: %s", p.name, e)
                except Exception:  # noqa: BLE001
                    log.exception("Import von %s fehlgeschlagen", p.name)
                seen[str(p)] = sig
                try:
                    state_file.write_text(json.dumps(seen))
                except OSError:
                    pass
            self.stop.wait(self.cfg.watch_interval)

    def republish_loop(self) -> None:
        while not self.stop.wait(self.cfg.publish_interval):
            self.publish()


def make_handler(svc: Service):
    cfg = svc.cfg
    routes: list[tuple[str, re.Pattern, object, str]] = []  # method, path regex, function, auth: none|user|session

    def route(method: str, pattern: str, auth: str = "user"):
        def deco(fn):
            routes.append((method, re.compile("^" + pattern + "$"), fn, auth))
            return fn
        return deco

    class Handler(BaseHTTPRequestHandler):
        server_version = f"cmfserver/{__version__}"
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            log.debug("%s %s", self.address_string(), fmt % args)

        # ------------------------------------------------------------ helpers
        @property
        def client_ip(self) -> str:
            return (self.headers.get("X-Forwarded-For") or self.client_address[0]).split(",")[0].strip()

        @property
        def https(self) -> bool:
            if cfg.cookie_secure in ("1", "true", "yes"):
                return True
            if cfg.cookie_secure in ("0", "false", "no"):
                return False
            return self.headers.get("X-Forwarded-Proto", "").lower() == "https"

        def base_url(self) -> str:
            if cfg.public_url:
                return cfg.public_url
            host = self.headers.get("X-Forwarded-Host") or self.headers.get("Host") or f"localhost:{cfg.port}"
            return f"{'https' if self.https else 'http'}://{host}"

        def _send(self, code: int, body, ctype="application/json", headers: dict | None = None):
            if isinstance(body, (bytes, bytearray)):
                data = bytes(body)
            elif ctype == "application/json":
                data = json.dumps(body, ensure_ascii=False, default=str).encode()
            else:
                data = str(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith(("text/", "application/json", "application/javascript")) else ""))
            self.send_header("Content-Length", str(len(data)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "same-origin")
            self.send_header("X-Frame-Options", "DENY")
            if ctype == "text/html":
                self.send_header("Content-Security-Policy", CSP)
            if ctype == "application/json":
                self.send_header("Cache-Control", "no-store")
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)

        def cookie(self, name: str) -> str | None:
            c = SimpleCookie(self.headers.get("Cookie", ""))
            return c[name].value if name in c else None

        def session_cookie(self, sid: str, max_age: int) -> str:
            return (f"{COOKIE}={sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age}"
                    + ("; Secure" if self.https else ""))

        def token_from_request(self) -> str:
            auth = self.headers.get("Authorization", "")
            if auth.lower().startswith("bearer "):
                return auth[7:].strip()
            return self.headers.get("X-Api-Token") or parse_qs(urlparse(self.path).query).get("token", [""])[0]

        def current_user(self) -> tuple[dict | None, str]:
            u = svc.store.session_user(self.cookie(COOKIE) or "")
            if u:
                return u, "session"
            u = svc.user_for_token(self.token_from_request())
            return (u, "token") if u else (None, "")

        def body_json(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 1_000_000:
                raise HttpError(413, "Anfrage zu groß")
            try:
                return json.loads(self.rfile.read(n) or b"{}")
            except ValueError as e:
                raise HttpError(400, "Ungültiges JSON") from e

        # ------------------------------------------------------------ dispatch
        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            self.dispatch("GET")

        def do_POST(self):
            self.dispatch("POST")

        def do_DELETE(self):
            self.dispatch("DELETE")

        def dispatch(self, method: str):
            url = urlparse(self.path)
            try:
                for m, rx, fn, auth in routes:
                    if m != method and not (m == "GET" and method == "HEAD"):
                        continue
                    match = rx.match(url.path)
                    if not match:
                        continue
                    user, via = self.current_user() if auth != "none" else (None, "")
                    if auth != "none" and not user:
                        raise HttpError(401, "Nicht angemeldet")
                    if auth == "session" and via != "session":
                        raise HttpError(403, "Nur auf der Website möglich")
                    # CSRF: browsers cannot send this header cross-site without a CORS preflight (which we never allow)
                    if method != "GET" and via == "session" and self.headers.get("X-Requested-With") != "cmf":
                        raise HttpError(403, "CSRF-Schutz: Header fehlt")
                    return fn(self, user, match, parse_qs(url.query))
                if method == "GET":
                    return self.static(url.path)
                raise HttpError(404, "Nicht gefunden")
            except HttpError as e:
                self._send(e.code, {"error": str(e)})
            except LLMError as e:
                self._send(503, {"error": str(e)})
            except Exception as e:  # noqa: BLE001
                log.exception("Fehler bei %s %s", method, url.path)
                self._send(500, {"error": f"Interner Fehler: {e.__class__.__name__}"})

        def static(self, path: str):
            if path in ("", "/") or not path.startswith("/static/"):
                f = WEB / "index.html"
            else:
                f = (WEB / path[len("/static/"):]).resolve()
                if WEB.resolve() not in f.parents or not f.is_file():
                    raise HttpError(404, "Nicht gefunden")
            ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
            if f.suffix == ".js":
                ctype = "application/javascript"
            self._send(200, f.read_bytes(), ctype, {"Cache-Control": "no-cache"})

    # =================================================================== routes
    @route("GET", "/healthz", auth="none")
    def healthz(h, user, m, qs):
        h._send(200, {"ok": True, "version": __version__})

    # ---------------------------------------------------------------- auth
    @route("GET", "/api/auth/status", auth="none")
    def auth_status(h, user, m, qs):
        u, _ = h.current_user()
        h._send(200, {"logged_in": bool(u), "signup": cfg.signup == "open" or svc.store.user_count() == 0,
                      "first_user": svc.store.user_count() == 0, "version": __version__})

    def check_rate(h):
        now = time.time()
        fails = [t for t in svc.failed_logins.get(h.client_ip, []) if now - t < 600]
        svc.failed_logins[h.client_ip] = fails
        if len(fails) >= 10:
            raise HttpError(429, "Zu viele Fehlversuche – bitte in 10 Minuten erneut versuchen")

    @route("POST", "/api/auth/register", auth="none")
    def register(h, user, m, qs):
        check_rate(h)
        if h.headers.get("X-Requested-With") != "cmf":
            raise HttpError(403, "CSRF-Schutz: Header fehlt")
        b = h.body_json()
        username, pw = (b.get("username") or "").strip(), b.get("password") or ""
        first = svc.store.user_count() == 0
        if not first and cfg.signup != "open":
            raise HttpError(403, "Registrierung ist geschlossen – frag den Admin nach einem Konto")
        if not USERNAME_RE.match(username):
            raise HttpError(400, "Benutzername: 3–32 Zeichen, nur Buchstaben, Zahlen, . _ -")
        if len(pw) < 8:
            raise HttpError(400, "Passwort: mindestens 8 Zeichen")
        try:
            u = svc.store.create_user(username, pw, is_admin=first)
        except Exception as e:  # noqa: BLE001 - unique constraint
            raise HttpError(409, "Benutzername ist schon vergeben") from e
        sid = svc.store.create_session(u["id"])
        h._send(200, {"ok": True}, headers={"Set-Cookie": h.session_cookie(sid, SESSION_DAYS * 86400)})

    @route("POST", "/api/auth/login", auth="none")
    def login(h, user, m, qs):
        check_rate(h)
        if h.headers.get("X-Requested-With") != "cmf":
            raise HttpError(403, "CSRF-Schutz: Header fehlt")
        b = h.body_json()
        u = svc.store.authenticate(b.get("username") or "", b.get("password") or "")
        if not u:
            svc.failed_logins.setdefault(h.client_ip, []).append(time.time())
            raise HttpError(401, "Benutzername oder Passwort falsch")
        sid = svc.store.create_session(u["id"])
        h._send(200, {"ok": True}, headers={"Set-Cookie": h.session_cookie(sid, SESSION_DAYS * 86400)})

    @route("POST", "/api/auth/logout", auth="none")
    def logout(h, user, m, qs):
        if h.cookie(COOKIE):
            svc.store.delete_session(h.cookie(COOKIE))
        h._send(200, {"ok": True}, headers={"Set-Cookie": h.session_cookie("", 0)})

    # ---------------------------------------------------------------- account
    def me_dict(h, u):
        base = h.base_url()
        q = f"server={quote(base, safe='')}&token={quote(u['token'])}"
        return {"id": u["id"], "username": u["username"], "is_admin": bool(u["is_admin"]), "token": u["token"],
                "server": base, "publish_ha": bool(u.get("publish_ha", 1)),
                "connect": {"export": f"cmfexport://connect?{q}", "track": f"cmftrack://connect?{q}"},
                "ai": svc.llm.enabled, "mqtt": any(type(p).__name__ == "MqttPublisher" for p in svc.publishers)}

    @route("GET", "/api/me")
    def me(h, user, m, qs):
        h._send(200, me_dict(h, user))

    @route("POST", "/api/me/token", auth="session")
    def rotate(h, user, m, qs):
        svc.store.rotate_token(user["id"])
        h._send(200, me_dict(h, svc.store.user(user["id"])))

    @route("POST", "/api/me/password", auth="session")
    def password(h, user, m, qs):
        b = h.body_json()
        if not svc.store.authenticate(user["username"], b.get("old") or ""):
            raise HttpError(400, "Aktuelles Passwort ist falsch")
        if len(b.get("new") or "") < 8:
            raise HttpError(400, "Neues Passwort: mindestens 8 Zeichen")
        svc.store.set_password(user["id"], b["new"])
        sid = svc.store.create_session(user["id"])
        h._send(200, {"ok": True}, headers={"Set-Cookie": h.session_cookie(sid, SESSION_DAYS * 86400)})

    @route("POST", "/api/me/settings", auth="session")
    def settings(h, user, m, qs):
        b = h.body_json()
        if "publish_ha" in b:
            svc.store.set_publish_ha(user["id"], bool(b["publish_ha"]))
        h._send(200, me_dict(h, svc.store.user(user["id"])))

    @route("DELETE", "/api/me", auth="session")
    def delete_me(h, user, m, qs):
        b = h.body_json()
        if not svc.store.authenticate(user["username"], b.get("password") or ""):
            raise HttpError(400, "Passwort ist falsch")
        svc.store.delete_user(user["id"])
        h._send(200, {"ok": True}, headers={"Set-Cookie": h.session_cookie("", 0)})

    @route("GET", "/api/export")
    def export(h, user, m, qs):
        data = json.dumps(svc.store.export_all(user["id"]), ensure_ascii=False, default=str).encode()
        h._send(200, data, "application/json",
                {"Content-Disposition": f'attachment; filename="cmf-daten-{user["username"]}.json"'})

    @route("GET", "/api/admin/users", auth="session")
    def admin_users(h, user, m, qs):
        if not user["is_admin"]:
            raise HttpError(403, "Nur für Admins")
        h._send(200, svc.store.users())

    @route("POST", "/api/admin/users", auth="session")
    def admin_create(h, user, m, qs):
        if not user["is_admin"]:
            raise HttpError(403, "Nur für Admins")
        b = h.body_json()
        if not USERNAME_RE.match(b.get("username") or "") or len(b.get("password") or "") < 8:
            raise HttpError(400, "Benutzername 3–32 Zeichen, Passwort mindestens 8 Zeichen")
        try:
            svc.store.create_user(b["username"], b["password"])
        except Exception as e:  # noqa: BLE001
            raise HttpError(409, "Benutzername ist schon vergeben") from e
        h._send(200, svc.store.users())

    # ---------------------------------------------------------------- data
    @route("GET", "/api/latest")
    def latest(h, user, m, qs):
        h._send(200, svc.state(user["id"]))

    @route("GET", "/api/dashboard")
    def dashboard(h, user, m, qs):
        uid = user["id"]
        ins = analysis.insights(svc.store, uid)
        nights = svc.store.nights(uid, 30)
        last = next((n for n in reversed(nights) if n["type"] == "night"), None)
        report = svc.store.report(uid, svc.today().isoformat(), "daily") or svc.store.report(uid, (svc.today() - timedelta(days=1)).isoformat(), "daily")
        h._send(200, {
            "state": svc.state(uid)["state"],
            "last_night": last, "stages": svc.store.stages(uid, last["start_ts"], last["end_ts"]) if last else [],
            "nights": nights, "daily": svc.store.daily(uid, 30),
            "heart_today": svc.store.series(uid, "heart_rate", now_ts() - 86400, max_points=300),
            "insights": ins, "report": report, "uploads": svc.store.uploads(uid, 5),
        })

    @route("GET", "/api/sleep")
    def sleep(h, user, m, qs):
        days = min(int(qs.get("days", ["30"])[0]), 400)
        nights = svc.store.nights(user["id"], days)
        for n in nights[-14:]:
            if n["type"] == "night":
                n["stages"] = svc.store.stages(user["id"], n["start_ts"], n["end_ts"])
                n["spo2"] = svc.store.series(user["id"], "spo2", n["start_ts"], n["end_ts"], max_points=200)
        h._send(200, {"nights": nights})

    @route("GET", "/api/series")
    def series(h, user, m, qs):
        metric = qs.get("metric", ["heart_rate"])[0]
        days = min(float(qs.get("days", ["1"])[0]), 400)
        since = int(qs.get("from", [now_ts() - days * 86400])[0])
        until = int(qs.get("to", [now_ts() + 3600])[0])
        h._send(200, {"metric": metric, "points": svc.store.series(user["id"], metric, since, until)})

    @route("GET", "/api/daily")
    def daily(h, user, m, qs):
        h._send(200, svc.store.daily(user["id"], min(int(qs.get("days", ["90"])[0]), 800)))

    @route("GET", "/api/workouts")
    def workouts(h, user, m, qs):
        h._send(200, svc.store.workouts(user["id"]))

    @route("GET", r"/api/workouts/(\d+)")
    def workout(h, user, m, qs):
        w = svc.store.workout(user["id"], int(m.group(1)))
        if not w:
            raise HttpError(404, "Training nicht gefunden")
        h._send(200, w)

    @route("GET", "/api/insights")
    def insights(h, user, m, qs):
        h._send(200, analysis.insights(svc.store, user["id"]))

    @route("GET", "/api/uploads")
    def uploads(h, user, m, qs):
        h._send(200, svc.store.uploads(user["id"], 50))

    # ---------------------------------------------------------------- AI
    @route("POST", "/api/ai/report")
    def ai_report(h, user, m, qs):
        day = svc.today().isoformat()
        content = svc.llm.report(analysis.ai_context(svc.store, user["id"]))
        svc.store.save_report(user["id"], day, "daily", content)
        h._send(200, svc.store.report(user["id"], day, "daily"))

    @route("POST", "/api/ai/chat")
    def ai_chat(h, user, m, qs):
        b = h.body_json()
        q = (b.get("message") or "").strip()
        if not q:
            raise HttpError(400, "Frage fehlt")
        answer = svc.llm.ask(analysis.ai_context(svc.store, user["id"]), b.get("history") or [], q)
        h._send(200, {"answer": answer})

    # ---------------------------------------------------------------- upload & publish
    @route("POST", "/api/publish")
    def publish(h, user, m, qs):
        svc.publish(user["id"])
        h._send(200, {"ok": True})

    @route("POST", "/api/upload")
    def upload(h, user, m, qs):
        length = int(h.headers.get("Content-Length") or 0)
        if length <= 0:
            raise HttpError(411, "Content-Length erforderlich")
        if length > cfg.max_upload_mb * 1024 * 1024:
            raise HttpError(413, f"größer als {cfg.max_upload_mb} MB")
        name = qs.get("name", [None])[0] or h.headers.get("X-File-Name") or "upload"
        ctype = h.headers.get("Content-Type", "")
        with tempfile.TemporaryDirectory(prefix="upload-") as tmp:
            path = Path(tmp) / "upload.bin"
            with path.open("wb") as fh:
                remaining = length
                while remaining:
                    chunk = h.rfile.read(min(1 << 20, remaining))
                    if not chunk:
                        break
                    fh.write(chunk)
                    remaining -= len(chunk)
            if ctype.startswith("multipart/form-data"):
                try:
                    path, name = _extract_multipart(path, ctype, Path(tmp))
                except ValueError as e:
                    raise HttpError(400, str(e)) from e
            try:
                res = svc.ingest(user["id"], path, Path(name).name)
            except UnsupportedFile as e:
                raise HttpError(415, str(e)) from e
        h._send(200, {"ok": True, **res, "state": svc.state(user["id"])["state"]})

    return Handler


def _extract_multipart(path: Path, ctype: str, tmp: Path):
    """Return (file path, file name) of the first file part of a multipart body."""
    from email.parser import BytesParser
    from email.policy import HTTP

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
    svc = Service(cfg)
    if svc.store.user_count() == 0:
        log.info("Noch kein Konto: Website öffnen und registrieren (das erste Konto wird Admin).")
    if svc.llm.enabled:
        log.info("KI-Analyse aktiv: %s (Modell %s)", cfg.llm_base_url, cfg.llm_model)
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
