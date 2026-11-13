"""HTTP server: website (login + dashboards), JSON API, uploads, import folder, Home Assistant publishing."""
from __future__ import annotations

import hmac
import json
import logging
import mimetypes
import os
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

import dataclasses

from . import __version__, analysis, asthma, environment
from .config import RUNTIME_SETTINGS, Config, coerce
from .ingest import UnsupportedFile, ingest_file
from .llm import LLM, LLMError
from .publish import make_publishers, test_mqtt
from .store import SESSION_DAYS, Store, now_ts

log = logging.getLogger("cmfserver")
WEB = Path(__file__).parent / "web"
COOKIE = "cmf_session"
USERNAME_RE = re.compile(r"^[A-Za-z0-9_.-]{3,32}$")
CSP = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
       "img-src 'self' data: blob: https://tiles.openfreemap.org; "
       "connect-src 'self' https://tiles.openfreemap.org; worker-src 'self' blob:; child-src blob:; "
       "frame-ancestors 'none'; base-uri 'none'; form-action 'self'")


class HttpError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


class Service:
    def __init__(self, cfg: Config, publishers=None, env: environment.Environment | None = None):
        self.base_cfg = cfg
        self.store = Store(cfg.db_path)
        self.fixed_publishers = publishers is not None
        self.publishers = publishers or []
        self.pub_lock = threading.Lock()
        self.env = env or environment.Environment()
        self.stop = threading.Event()
        self.ingest_lock = threading.Lock()
        self.failed_logins: dict[str, list[float]] = {}
        self.apply_settings()

    # ------------------------------------------------------------------ admin settings
    def apply_settings(self) -> None:
        """Effective config = environment defaults + values the admin saved in the website."""
        saved = {k: v for k, v in self.store.get_settings().items() if k in RUNTIME_SETTINGS}
        self.cfg = dataclasses.replace(self.base_cfg, **saved)
        self.llm = LLM(self.cfg)
        if self.fixed_publishers:
            return
        with self.pub_lock:
            old, self.publishers = self.publishers, []
            for p in old:
                try:
                    p.close()
                except Exception:  # noqa: BLE001
                    log.exception("Schließen fehlgeschlagen")
            try:
                self.publishers = make_publishers(self.cfg)
            except Exception:  # noqa: BLE001
                log.exception("Home-Assistant-Verbindung konnte nicht aufgebaut werden")

    def settings_view(self) -> dict:
        saved = self.store.get_settings()
        out = {}
        for k, (typ, secret) in RUNTIME_SETTINGS.items():
            v = getattr(self.cfg, k)
            env = {"discovery_prefix": "MQTT_DISCOVERY_PREFIX", "base_topic": "MQTT_BASE_TOPIC"}.get(k, k.upper())
            src = "ui" if k in saved else ("env" if os.environ.get(env) or os.environ.get(env + "_FILE") else "default")
            out[k] = {"set": bool(v), "source": src} if secret else {"value": v, "source": src}
        return out

    def save_settings(self, body: dict) -> None:
        for k, v in body.items():
            if k not in RUNTIME_SETTINGS:
                raise HttpError(400, f"Unbekannte Einstellung: {k}")
            if RUNTIME_SETTINGS[k][1] and v is None:
                continue  # secret not touched in the form -> keep
            try:
                self.store.set_setting(k, coerce(k, v))
            except ValueError as e:
                raise HttpError(400, str(e)) from e
        self.apply_settings()
        threading.Thread(target=self._publish_soon, daemon=True).start()

    def _publish_soon(self) -> None:
        for _ in range(20):  # give the new MQTT connection a moment
            if self.stop.wait(0.5) or all(p.status().get("connected") for p in self.publishers if hasattr(p, "status")):
                break
        self.publish()

    def connection_status(self) -> list[dict]:
        return [p.status() for p in self.publishers if hasattr(p, "status")]

    def today(self):
        return datetime.now().astimezone().date()

    # ------------------------------------------------------------------- per-user extras
    def environment_for(self, u: dict | None) -> dict | None:
        if not u or u.get("loc_lat") is None:
            return None
        env = self.env.get(u["loc_lat"], u["loc_lon"])
        if env:
            env = {**env, "place": u.get("loc_name")}
        return env

    def health(self, uid: int, with_env: bool = True) -> dict:
        """Insights + asthma diary + environment in one go (used by dashboard, HA and the doctor report)."""
        u = self.store.user(uid)
        diary = asthma.summary(self.store, uid, self.today())
        env = self.environment_for(u) if with_env else None
        ins = analysis.insights(self.store, uid, self.today(), extra=asthma.flags(diary) + environment.flags(env, diary))
        return {"user": u, "insights": ins, "asthma": diary, "environment": env}

    def state(self, uid: int, with_env: bool = True) -> dict:
        res = self.store.latest_state(uid, self.today())
        s, a = res["state"], res["attributes"]
        try:
            h = self.health(uid, with_env)
        except Exception:  # noqa: BLE001 - extras must never break the basic sensors
            log.exception("Zusatzwerte fehlgeschlagen")
            return res
        u, ins, diary, env = h["user"], h["insights"], h["asthma"], h["environment"]
        goal = (u or {}).get("goal_steps") or 10000
        s["steps_goal"] = round(100 * s.get("steps_today", 0) / goal)
        a["steps_goal"] = {"goal": goal}
        if ins["recovery"] is not None:
            s["recovery"] = ins["recovery"]
        worst = ins["flags"][0]["level"] if ins["flags"] else "good"
        s["health_status"] = {"alert": "Warnung", "warn": "Hinweis", "info": "OK", "good": "OK"}[worst]
        a["health_status"] = {"level": worst, "hinweise": [f"{f['area']}: {f['title']}" for f in ins["flags"] if f["level"] in ("alert", "warn")]}
        if diary["has_data"]:
            s["inhaler_today"] = diary["today"]["reliever"]
            a["inhaler_today"] = {"last_7_days": diary["reliever_7d"], "controller_today": diary["today"]["controller"]}
            if diary["control"]:
                s["asthma_control"] = diary["control"]
                a["asthma_control"] = {q["q"]: q["yes"] for q in diary["questions"]}
            if diary["peakflow"]:
                s["peak_flow"] = diary["peakflow"]["value"]
                a["peak_flow"] = diary["peakflow"]
        if env:
            if env.get("aqi") is not None:
                s["air_quality"] = env["aqi"]
                a["air_quality"] = {"label": env["aqi_label"], "pm2_5": env["pm2_5"], "pm10": env["pm10"],
                                    "ozone": env["ozone"], "no2": env["no2"], "place": env.get("place")}
            s["pollen"] = env["pollen_max"]
            a["pollen"] = {p["name"]: p["today_max"] for p in env["pollen"]} | {"top": env["pollen_top"], "place": env.get("place")}
        return res

    def publish(self, uid: int | None = None) -> None:
        users = [self.store.user(uid)] if uid else self.store.users()
        for u in users:
            if not u or not u.get("publish_ha", 1):
                continue
            payload = self.state(u["id"])
            for p in list(self.publishers):
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
        while not self.stop.wait(max(30, self.cfg.publish_interval)):
            self.publish()


def make_handler(svc: Service):
    class _Cfg:  # always the current settings (the admin can change them at runtime)
        def __getattr__(self, name):
            return getattr(svc.cfg, name)
    cfg = _Cfg()
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
            if path == "/sw.js":  # service worker must live at the root to control the whole site
                self._send(200, (WEB / "sw.js").read_bytes(), "application/javascript",
                           {"Cache-Control": "no-cache", "Service-Worker-Allowed": "/"})
                return
            if path in ("", "/") or not path.startswith("/static/"):
                f = WEB / "index.html"
            else:
                f = (WEB / path[len("/static/"):]).resolve()
                if WEB.resolve() not in f.parents or not f.is_file():
                    raise HttpError(404, "Nicht gefunden")
            ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
            if f.suffix == ".js":
                ctype = "application/javascript"
            elif f.suffix == ".webmanifest":
                ctype = "application/manifest+json"
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
                "ai": svc.llm.enabled, "mqtt": any(type(p).__name__ == "MqttPublisher" for p in svc.publishers),
                "ha": bool(svc.publishers), "goal_steps": u.get("goal_steps") or 10000,
                "goal_sleep_min": u.get("goal_sleep_min") or 450,
                "location": {"lat": u["loc_lat"], "lon": u["loc_lon"], "name": u.get("loc_name")} if u.get("loc_lat") is not None else None}

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
        prof = {}
        try:
            if "goal_steps" in b:
                prof["goal_steps"] = max(1000, min(50000, int(b["goal_steps"])))
            if "goal_sleep_min" in b:
                prof["goal_sleep_min"] = max(240, min(720, int(b["goal_sleep_min"])))
            if "location" in b:
                loc = b["location"]
                if loc:
                    lat, lon = float(loc["lat"]), float(loc["lon"])
                    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
                        raise ValueError
                    prof.update(loc_lat=round(lat, 3), loc_lon=round(lon, 3), loc_name=str(loc.get("name") or "")[:80] or None)
                else:
                    prof.update(loc_lat=None, loc_lon=None, loc_name=None)
        except (TypeError, ValueError, KeyError) as e:
            raise HttpError(400, "Ungültiger Wert") from e
        svc.store.set_profile(user["id"], **prof)
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

    def admin_only(user):
        if not user["is_admin"]:
            raise HttpError(403, "Nur für Admins")

    @route("GET", "/api/admin/settings", auth="session")
    def admin_settings(h, user, m, qs):
        admin_only(user)
        h._send(200, {"settings": svc.settings_view(), "status": svc.connection_status(),
                      "ai": svc.llm.enabled, "version": __version__})

    @route("POST", "/api/admin/settings", auth="session")
    def admin_settings_save(h, user, m, qs):
        admin_only(user)
        svc.save_settings(h.body_json())
        log.info("Servereinstellungen geändert von %s", user["username"])
        h._send(200, {"settings": svc.settings_view(), "status": svc.connection_status(), "ai": svc.llm.enabled})

    @route("POST", "/api/admin/mqtt/test", auth="session")
    def admin_mqtt_test(h, user, m, qs):
        admin_only(user)
        b = h.body_json()
        over = {}
        for k in ("mqtt_host", "mqtt_port", "mqtt_user", "mqtt_password", "mqtt_tls", "mqtt_tls_insecure", "mqtt_client_id"):
            if k in b and b[k] is not None:
                try:
                    over[k] = coerce(k, b[k])
                except ValueError as e:
                    raise HttpError(400, str(e)) from e
        h._send(200, test_mqtt(dataclasses.replace(svc.cfg, **over)))

    @route("POST", "/api/admin/ai/test", auth="session")
    def admin_ai_test(h, user, m, qs):
        admin_only(user)
        t0 = time.time()
        try:
            answer = svc.llm.chat([{"role": "user", "content": "Antworte nur mit: OK"}], max_tokens=10)
            h._send(200, {"ok": True, "answer": answer[:80], "ms": round((time.time() - t0) * 1000)})
        except LLMError as e:
            h._send(200, {"ok": False, "error": str(e)})

    @route("DELETE", r"/api/admin/users/(\d+)", auth="session")
    def admin_delete(h, user, m, qs):
        admin_only(user)
        uid = int(m.group(1))
        if uid == user["id"]:
            raise HttpError(400, "Das eigene Konto bitte unter Konto löschen")
        svc.store.delete_user(uid)
        h._send(200, svc.store.users())

    @route("POST", r"/api/admin/users/(\d+)", auth="session")
    def admin_update(h, user, m, qs):
        admin_only(user)
        uid, b = int(m.group(1)), h.body_json()
        if not svc.store.user(uid):
            raise HttpError(404, "Konto nicht gefunden")
        if "password" in b:
            if len(b["password"] or "") < 8:
                raise HttpError(400, "Passwort: mindestens 8 Zeichen")
            svc.store.set_password(uid, b["password"])
        if "is_admin" in b and uid != user["id"]:
            svc.store.set_admin(uid, bool(b["is_admin"]))
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
        hl = svc.health(uid)
        ins = hl["insights"]
        nights = svc.store.nights(uid, 30)
        last = next((n for n in reversed(nights) if n["type"] == "night"), None)
        report = svc.store.report(uid, svc.today().isoformat(), "daily") or svc.store.report(uid, (svc.today() - timedelta(days=1)).isoformat(), "daily")
        h._send(200, {
            "state": svc.state(uid)["state"],
            "last_night": last, "stages": svc.store.stages(uid, last["start_ts"], last["end_ts"]) if last else [],
            "nights": nights, "daily": svc.store.daily(uid, 30),
            "heart_today": svc.store.series(uid, "heart_rate", now_ts() - 86400, max_points=300),
            "insights": ins, "report": report, "uploads": svc.store.uploads(uid, 5),
            "asthma": {k: hl["asthma"][k] for k in ("today", "control", "peakflow", "reliever_7d", "has_data")},
            "environment": hl["environment"],
            "goals": {"steps": hl["user"].get("goal_steps") or 10000, "sleep_min": hl["user"].get("goal_sleep_min") or 450},
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
        h._send(200, svc.health(user["id"])["insights"])

    # ---------------------------------------------------------------- asthma diary
    @route("GET", "/api/diary")
    def diary(h, user, m, qs):
        h._send(200, asthma.summary(svc.store, user["id"], svc.today()))

    @route("POST", "/api/diary")
    def diary_add(h, user, m, qs):
        b = h.body_json()
        items = b.get("entries") if isinstance(b.get("entries"), list) else [b]
        if not items or len(items) > 20:
            raise HttpError(400, "Keine Einträge")
        for e in items:
            kind = e.get("kind")
            value = e.get("value")
            detail = e.get("detail")
            try:
                value = float(value) if value not in (None, "") else None
            except (TypeError, ValueError) as ex:
                raise HttpError(400, "Wert muss eine Zahl sein") from ex
            if kind == "peakflow" and not (value and 50 <= value <= 900):
                raise HttpError(400, "Peak-Flow: 50–900 l/min")
            if kind in ("reliever", "controller") and value is not None and not 0 < value <= 20:
                raise HttpError(400, "Hübe: 1–20")
            if kind == "symptom" and value is not None and not 1 <= value <= 3:
                raise HttpError(400, "Stärke: 1–3")
            if isinstance(detail, list):
                detail = ",".join(str(x) for x in detail)
            ts = e.get("ts")
            try:
                ts = int(ts) if ts else None
            except (TypeError, ValueError) as ex:
                raise HttpError(400, "Zeitpunkt ungültig") from ex
            if ts and not now_ts() - 400 * 86400 < ts < now_ts() + 3600:
                raise HttpError(400, "Zeitpunkt ungültig")
            try:
                svc.store.add_diary(user["id"], kind, value, detail, ts)
            except ValueError as ex:
                raise HttpError(400, str(ex)) from ex
        svc.publish(user["id"])
        h._send(200, asthma.summary(svc.store, user["id"], svc.today()))

    @route("DELETE", r"/api/diary/(\d+)")
    def diary_delete(h, user, m, qs):
        if not svc.store.delete_diary(user["id"], int(m.group(1))):
            raise HttpError(404, "Eintrag nicht gefunden")
        h._send(200, asthma.summary(svc.store, user["id"], svc.today()))

    # ---------------------------------------------------------------- environment
    @route("GET", "/api/environment")
    def env(h, user, m, qs):
        h._send(200, svc.environment_for(svc.store.user(user["id"])) or {"none": True})

    @route("GET", "/api/geo", auth="session")
    def geo(h, user, m, qs):
        q = (qs.get("q", [""])[0] or "").strip()
        if len(q) < 2:
            h._send(200, [])
            return
        try:
            h._send(200, svc.env.search(q[:80]))
        except Exception as e:  # noqa: BLE001
            raise HttpError(502, f"Ortssuche nicht erreichbar: {e}") from e

    # ---------------------------------------------------------------- doctor report
    @route("GET", "/api/report")
    def doctor_report(h, user, m, qs):
        uid = user["id"]
        days = max(7, min(int(qs.get("days", ["28"])[0]), 180))
        hl = svc.health(uid)
        nights = [n for n in svc.store.nights(uid, days) if n["type"] == "night"]
        daily = svc.store.daily(uid, days)
        def avg(xs):
            xs = [x for x in xs if x is not None]
            return round(sum(xs) / len(xs), 1) if xs else None
        h._send(200, {
            "user": user["username"], "days": days, "created": datetime.now().astimezone().isoformat(timespec="minutes"),
            "from": (svc.today() - timedelta(days=days - 1)).isoformat(), "to": svc.today().isoformat(),
            "summary": {"sleep_h": avg([n["asleep_min"] / 60 for n in nights]), "nights": len(nights),
                        "efficiency": avg([n.get("efficiency") for n in nights]),
                        "awake_min": avg([n["awake_min"] for n in nights]),
                        "steps": avg([d.get("steps") for d in daily]), "resting_hr": avg([d.get("resting_hr") for d in daily]),
                        "spo2": avg([d.get("spo2_avg") for d in daily])},
            "nights": nights, "daily": daily, "insights": hl["insights"], "asthma": hl["asthma"],
            "workouts": [w for w in svc.store.workouts(uid, 100) if w["start"][:10] >= (svc.today() - timedelta(days=days)).isoformat()],
        })

    @route("GET", "/api/uploads")
    def uploads(h, user, m, qs):
        h._send(200, svc.store.uploads(user["id"], 50))

    # ---------------------------------------------------------------- AI
    @route("POST", "/api/ai/report")
    def ai_report(h, user, m, qs):
        day = svc.today().isoformat()
        content = svc.llm.report(analysis.ai_context(svc.store, user["id"], svc.health(user["id"])))
        svc.store.save_report(user["id"], day, "daily", content)
        h._send(200, svc.store.report(user["id"], day, "daily"))

    @route("POST", "/api/ai/chat")
    def ai_chat(h, user, m, qs):
        b = h.body_json()
        q = (b.get("message") or "").strip()
        if not q:
            raise HttpError(400, "Frage fehlt")
        answer = svc.llm.ask(analysis.ai_context(svc.store, user["id"], svc.health(user["id"])), b.get("history") or [], q)
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
