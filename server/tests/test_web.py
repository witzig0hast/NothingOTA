"""Accounts, sessions, tokens, website API, analysis and the AI endpoint."""
import http.cookiejar
import json
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT), str(ROOT / "server"), str(ROOT / "server" / "tests")]

from cmfserver.app import Service, make_handler  # noqa: E402
from cmfserver.config import Config  # noqa: E402
from demo_data import demo_zip  # noqa: E402


class FakePub:
    def __init__(self):
        self.calls = []

    def publish(self, p, user=None):
        self.calls.append((user["username"] if user else None, p))

    def close(self):
        pass


class FakeLLM(BaseHTTPRequestHandler):
    """OpenAI-compatible /v1/chat/completions that echoes what it received."""
    seen: list = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeLLM.seen.append((self.path, self.headers.get("Authorization"), body))
        answer = "## Kurzfazit\nDeine **Sauerstoffwerte** waren an zwei Nächten niedrig.\n- Tipp 1" if "Wochenbericht" in body["messages"][-1]["content"] \
            else f"Antwort auf: {body['messages'][-1]['content']}"
        data = json.dumps({"choices": [{"message": {"role": "assistant", "content": answer}}]}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


@pytest.fixture
def env(tmp_path):
    llm = ThreadingHTTPServer(("127.0.0.1", 0), FakeLLM)
    threading.Thread(target=llm.serve_forever, daemon=True).start()
    cfg = Config(data_dir=tmp_path / "data", import_dir=None, api_token=None,
                 llm_base_url=f"http://127.0.0.1:{llm.server_port}", llm_model="test-model", llm_api_key="sk-test")
    pub = FakePub()
    svc = Service(cfg, publishers=[pub])
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(svc))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield svc, pub, f"http://127.0.0.1:{httpd.server_port}"
    httpd.shutdown(); llm.shutdown()


class Browser:
    """Keeps cookies like a browser and sends the CSRF header the website sends."""

    def __init__(self, base):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, data=None, method=None, csrf=True, raw=None, headers=None):
        body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
        r = urllib.request.Request(self.base + path, data=body, method=method or ("POST" if body is not None else "GET"))
        if data is not None:
            r.add_header("Content-Type", "application/json")
        if csrf:
            r.add_header("X-Requested-With", "cmf")
        for k, v in (headers or {}).items():
            r.add_header(k, v)
        try:
            with self.opener.open(r) as resp:
                ct = resp.headers.get("Content-Type", "")
                b = resp.read()
                return resp.status, (json.loads(b) if "json" in ct else b.decode()), resp.headers
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}"), e.headers


def test_signup_login_token_and_isolation(env):
    svc, pub, base = env
    a = Browser(base)
    assert a.call("/api/auth/status")[1]["first_user"] is True
    # first account becomes admin and is logged in right away
    code, _, hdr = a.call("/api/auth/register", {"username": "karim", "password": "sehrgeheim"})
    assert code == 200 and "HttpOnly" in hdr["Set-Cookie"] and "SameSite=Lax" in hdr["Set-Cookie"]
    me = a.call("/api/me")[1]
    assert me["is_admin"] and me["token"].startswith("cmf-") and len(me["token"]) == 16
    assert me["connect"]["export"].startswith("cmfexport://connect?server=")

    # signup is closed after the first account ("first" mode)
    b = Browser(base)
    assert b.call("/api/auth/register", {"username": "fremd", "password": "sehrgeheim"})[0] == 403
    # CSRF: state changing requests without the header are rejected
    assert a.call("/api/me/token", {}, csrf=False)[0] == 403
    # admin creates a second account
    assert a.call("/api/admin/users", {"username": "lena", "password": "auchgeheim"})[0] == 200
    assert b.call("/api/auth/login", {"username": "lena", "password": "falsch"})[0] == 401
    assert b.call("/api/auth/login", {"username": "LENA", "password": "auchgeheim"})[0] == 200
    lena = b.call("/api/me")[1]
    assert not lena["is_admin"] and b.call("/api/admin/users")[0] == 403

    # the apps upload with the short token; data stays with its owner
    code, res, _ = Browser(base).call("/api/upload", raw=demo_zip(10), csrf=False, headers={"Authorization": f"Bearer {me['token']}"})
    assert code == 200, res
    assert res["counts"]["stages"] > 50 and res["counts"]["track_points"] > 100
    assert len(a.call("/api/workouts")[1]) >= 3
    assert b.call("/api/workouts")[1] == []
    assert b.call("/api/dashboard")[1]["last_night"] is None
    # Home Assistant: one device per user
    assert pub.calls[-1][0] == "karim"

    # token rotation invalidates the old token
    new = a.call("/api/me/token", {})[1]["token"]
    assert new != me["token"]
    assert Browser(base).call("/api/latest", csrf=False, headers={"Authorization": f"Bearer {me['token']}"})[0] == 401
    assert Browser(base).call("/api/latest", csrf=False, headers={"Authorization": f"Bearer {new}"})[0] == 200
    # tokens cannot do website-only things
    assert Browser(base).call("/api/me/token", {}, headers={"Authorization": f"Bearer {new}"})[0] == 403

    # logout
    a.call("/api/auth/logout", {})
    assert a.call("/api/me")[0] == 401


def test_login_rate_limit(env):
    _, _, base = env
    a = Browser(base)
    a.call("/api/auth/register", {"username": "karim", "password": "sehrgeheim"})
    x = Browser(base)
    codes = [x.call("/api/auth/login", {"username": "karim", "password": f"nope{i}"})[0] for i in range(11)]
    assert codes[:10] == [401] * 10 and codes[10] == 429


def test_dashboard_insights_and_pages(env):
    svc, _, base = env
    a = Browser(base)
    a.call("/api/auth/register", {"username": "karim", "password": "sehrgeheim"})
    token = a.call("/api/me")[1]["token"]
    Browser(base).call("/api/upload", raw=demo_zip(30), csrf=False, headers={"Authorization": f"Bearer {token}"})

    d = a.call("/api/dashboard")[1]
    assert d["last_night"]["asleep_min"] > 300 and len(d["stages"]) > 10
    assert d["insights"]["recovery"] is not None
    flags = {f["title"] for f in d["insights"]["flags"]}
    # the demo data contains two "sick" days with oxygen dips, high resting HR and fast breathing
    assert any("Sauerstoff" in t for t in flags), flags
    assert "Ruhepuls deutlich erhöht" in flags or any(f["area"] == "Herz" for f in d["insights"]["flags"])
    sleep = a.call("/api/sleep?days=14")[1]["nights"]
    assert sleep[-1]["stages"] and sleep[-1]["spo2"]
    w = a.call("/api/workouts")[1][0]
    detail = a.call(f"/api/workouts/{w['start_ts']}")[1]
    assert len(detail["track"]) > 50 and detail["heart_rate"]
    assert len(a.call("/api/series?metric=heart_rate&days=30")[1]["points"]) <= 1500  # downsampled

    # website itself, with security headers
    code, html, hdr = a.call("/")
    assert code == 200 and "CMF Health" in html and "default-src 'self'" in hdr["Content-Security-Policy"]
    assert a.call("/static/app.js")[0] == 200
    assert a.call("/static/../app.py")[0] == 404
    export = a.call("/api/export")[1]
    assert export["sleep"] and export["track"]


def test_ai_report_and_chat(env):
    svc, _, base = env
    a = Browser(base)
    a.call("/api/auth/register", {"username": "karim", "password": "sehrgeheim"})
    token = a.call("/api/me")[1]["token"]
    Browser(base).call("/api/upload", raw=demo_zip(14), csrf=False, headers={"Authorization": f"Bearer {token}"})

    code, rep, _ = a.call("/api/ai/report", {})
    assert code == 200 and "Sauerstoffwerte" in rep["content"]
    path, auth, body = FakeLLM.seen[-1]
    assert path == "/v1/chat/completions" and auth == "Bearer sk-test" and body["model"] == "test-model"
    assert len(json.dumps(body)) < 20000  # compact summary, not raw samples
    assert a.call("/api/dashboard")[1]["report"]["content"] == rep["content"]

    code, ans, _ = a.call("/api/ai/chat", {"message": "Wie schlafe ich?", "history": [{"role": "user", "content": "Hallo"}]})
    assert code == 200 and ans["answer"] == "Antwort auf: Wie schlafe ich?"
    assert FakeLLM.seen[-1][2]["messages"][1] == {"role": "user", "content": "Hallo"}


def test_ai_not_configured(tmp_path):
    svc = Service(Config(data_dir=tmp_path, import_dir=None, llm_base_url=None, llm_model=None), publishers=[])
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(svc))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    a = Browser(f"http://127.0.0.1:{httpd.server_port}")
    a.call("/api/auth/register", {"username": "karim", "password": "sehrgeheim"})
    assert a.call("/api/me")[1]["ai"] is False
    code, err, _ = a.call("/api/ai/report", {})
    assert code == 503 and "Admin" in err["error"]
    httpd.shutdown()
