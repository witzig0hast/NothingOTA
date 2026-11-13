"""Admin server settings (MQTT client), asthma diary, environment, goals, doctor report."""
import socket
import socketserver
import threading
import time
from datetime import date, timedelta

import pytest

from test_web import Browser  # noqa: F401  (also puts the paths in place)
from cmfserver.app import Service, make_handler  # noqa: E402
from cmfserver.config import Config  # noqa: E402
from cmfserver import environment  # noqa: E402
from http.server import ThreadingHTTPServer  # noqa: E402


class FakeBroker(socketserver.ThreadingTCPServer):
    """Just enough MQTT 3.1.1: CONNACK (optionally rejecting a password), SUBACK, PINGRESP, records PUBLISH."""
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, password=None):
        self.password = password
        self.published: dict[str, bytes] = {}
        self.logins: list = []
        super().__init__(("127.0.0.1", 0), BrokerHandler)
        threading.Thread(target=self.serve_forever, daemon=True).start()

    @property
    def port(self):
        return self.server_address[1]


class BrokerHandler(socketserver.BaseRequestHandler):
    def read_packet(self):
        hdr = self.request.recv(1)
        if not hdr:
            return None, b""
        mult, length = 1, 0
        while True:
            b = self.request.recv(1)[0]
            length += (b & 127) * mult
            mult *= 128
            if not b & 128:
                break
        data = b""
        while len(data) < length:
            chunk = self.request.recv(length - len(data))
            if not chunk:
                break
            data += chunk
        return hdr[0], data

    def handle(self):
        srv = self.server
        while True:
            try:
                t, data = self.read_packet()
            except (OSError, IndexError):
                return
            if t is None:
                return
            kind = t >> 4
            if kind == 1:  # CONNECT
                flags = data[7]
                pos = 10
                def s():
                    nonlocal pos
                    n = int.from_bytes(data[pos:pos + 2], "big"); v = data[pos + 2:pos + 2 + n]; pos += 2 + n; return v
                s()  # client id
                if flags & 4:
                    s(); s()  # will topic + message
                user = s().decode() if flags & 128 else None
                pw = s().decode() if flags & 64 else None
                srv.logins.append((user, pw))
                ok = srv.password is None or pw == srv.password
                self.request.sendall(bytes([0x20, 2, 0, 0 if ok else 5]))
                if not ok:
                    return
            elif kind == 3:  # PUBLISH (QoS 0)
                n = int.from_bytes(data[:2], "big")
                srv.published[data[2:2 + n].decode()] = data[2 + n:]
            elif kind == 8:  # SUBSCRIBE
                self.request.sendall(bytes([0x90, 3]) + data[:2] + b"\x00")
            elif kind == 12:
                self.request.sendall(bytes([0xD0, 0]))
            elif kind == 14:
                return


def wait(cond, timeout=8):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.1)
    return False


def fake_fetch(url, params, timeout=15):
    if "geocoding" in url:
        return {"results": [{"name": "Berlin", "latitude": 52.52, "longitude": 13.41, "country": "Deutschland", "admin1": "Berlin"}]}
    if "air-quality" in url:
        today = date.today()
        times, birch, aqi = [], [], []
        for i in range(-31, 2):
            d = today + timedelta(days=i)
            for h in (6, 12, 18):
                times.append(f"{d.isoformat()}T{h:02d}:00")
                birch.append(180.0 if i >= -12 and i % 2 == 0 else 2.0)  # high every second day recently
                aqi.append(30)
        hourly = {"time": times, "european_aqi": aqi, "birch_pollen": birch}
        for k in ("alder", "grass", "mugwort", "olive", "ragweed"):
            hourly[f"{k}_pollen"] = [0.0] * len(times)
        return {"current": {"time": f"{today}T12:00", "european_aqi": 72, "pm2_5": 20.5, "pm10": 30.1, "ozone": 90,
                            "nitrogen_dioxide": 12, "birch_pollen": 150.0, "grass_pollen": 0.0}, "hourly": hourly}
    return {"current": {"temperature_2m": 14.2, "relative_humidity_2m": 60, "apparent_temperature": 13.0, "weather_code": 2}}


@pytest.fixture
def server(tmp_path):
    cfg = Config(data_dir=tmp_path / "data", import_dir=None, api_token=None, mqtt_host=None, mqtt_enabled=False,
                 ha_url=None, ha_token=None, llm_base_url=None, llm_model=None)
    svc = Service(cfg, env=environment.Environment(fetch=fake_fetch))
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(svc))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_port}"
    admin = Browser(base)
    admin.call("/api/auth/register", {"username": "karim", "password": "sehrgeheim"})
    yield svc, base, admin
    httpd.shutdown()
    for p in svc.publishers:
        p.close()


def test_admin_configures_mqtt_client(server):
    svc, base, admin = server
    assert svc.publishers == []
    st = admin.call("/api/admin/settings")[1]
    assert st["status"] == [] and st["settings"]["mqtt_password"] == {"set": False, "source": "default"}

    broker = FakeBroker(password="geheim")
    # connection test with a wrong password -> broker refuses
    r = admin.call("/api/admin/mqtt/test", {"mqtt_host": "127.0.0.1", "mqtt_port": broker.port, "mqtt_user": "ha", "mqtt_password": "falsch"})[1]
    assert r["ok"] is False and "lehnt" in r["error"]
    r = admin.call("/api/admin/mqtt/test", {"mqtt_host": "127.0.0.1", "mqtt_port": 1, "mqtt_user": "ha", "mqtt_password": "x"})[1]
    assert r["ok"] is False
    r = admin.call("/api/admin/mqtt/test", {"mqtt_host": "127.0.0.1", "mqtt_port": broker.port, "mqtt_user": "ha", "mqtt_password": "geheim"})[1]
    assert r["ok"] is True

    # invalid values are rejected
    assert admin.call("/api/admin/settings", {"mqtt_port": 70000})[0] == 400
    assert admin.call("/api/admin/settings", {"mqtt_host": "mqtt://x/y"})[0] == 400
    assert admin.call("/api/admin/settings", {"unknown": 1})[0] == 400

    code, st, _ = admin.call("/api/admin/settings", {"mqtt_enabled": True, "mqtt_host": "127.0.0.1", "mqtt_port": broker.port,
                                                       "mqtt_user": "ha", "mqtt_password": "geheim", "base_topic": "uhr"})
    assert code == 200
    assert "geheim" not in str(st) and st["settings"]["mqtt_password"] == {"set": True, "source": "ui"}
    assert wait(lambda: "uhr/steps_today" in broker.published), broker.published.keys()
    assert "homeassistant/sensor/cmf_watch/steps_today/config" in broker.published
    assert broker.published["uhr/status"] == b"online"
    st = admin.call("/api/admin/settings")[1]
    assert st["status"][0]["connected"] is True and st["status"][0]["type"] == "mqtt"

    # saving without the password keeps it; switching off disconnects
    admin.call("/api/admin/settings", {"device_name": "Meine Uhr", "mqtt_password": None})
    assert svc.cfg.mqtt_password == "geheim" and svc.cfg.device_name == "Meine Uhr"
    assert wait(lambda: svc.connection_status() and svc.connection_status()[0]["connected"])
    admin.call("/api/admin/settings", {"mqtt_enabled": False})
    assert svc.publishers == [] and wait(lambda: broker.published["uhr/status"] == b"offline")

    # settings survive a restart (stored in the database, override the environment)
    svc2 = Service(svc.base_cfg)
    assert svc2.cfg.base_topic == "uhr" and svc2.cfg.mqtt_password == "geheim" and svc2.publishers == []
    broker.shutdown()


def test_settings_admin_only_and_user_management(server):
    svc, base, admin = server
    admin.call("/api/admin/users", {"username": "anna", "password": "annapasswort"})
    anna = Browser(base)
    assert anna.call("/api/auth/login", {"username": "anna", "password": "annapasswort"})[0] == 200
    assert anna.call("/api/admin/settings")[0] == 403
    assert anna.call("/api/admin/settings", {"signup": "open"})[0] == 403
    assert anna.call("/api/admin/mqtt/test", {})[0] == 403
    # signup mode from the UI
    assert Browser(base).call("/api/auth/status")[1]["signup"] is False
    admin.call("/api/admin/settings", {"signup": "open"})
    assert Browser(base).call("/api/auth/status")[1]["signup"] is True
    # admin resets anna's password, makes her admin, deletes her
    uid = next(u["id"] for u in admin.call("/api/admin/users")[1] if u["username"] == "anna")
    admin.call(f"/api/admin/users/{uid}", {"password": "neuespasswort"})
    assert Browser(base).call("/api/auth/login", {"username": "anna", "password": "neuespasswort"})[0] == 200
    users = admin.call(f"/api/admin/users/{uid}", {"is_admin": True})[1]
    assert next(u for u in users if u["id"] == uid)["is_admin"] == 1
    assert admin.call("/api/admin/users/1", method="DELETE")[0] == 400  # not yourself
    users = admin.call(f"/api/admin/users/{uid}", method="DELETE")[1]
    assert [u["username"] for u in users] == ["karim"]


def test_asthma_diary_control_and_sensors(server):
    svc, base, admin = server
    now = int(time.time())
    entries = []
    for i in range(10):  # spray on most of the last 10 days, night symptoms twice
        entries.append({"kind": "reliever", "value": 2, "ts": now - i * 86400})
    entries.append({"kind": "symptom", "detail": ["husten", "nacht"], "value": 2, "ts": now - 86400})
    entries.append({"kind": "trigger", "detail": "pollen", "ts": now - 86400})
    assert admin.call("/api/diary", {"entries": entries})[0] == 200
    for v, ago in ((520, 20), (500, 10), (380, 0)):
        admin.call("/api/diary", {"kind": "peakflow", "value": v, "ts": now - ago * 86400})
    # validation
    assert admin.call("/api/diary", {"kind": "peakflow", "value": 5})[0] == 400
    assert admin.call("/api/diary", {"kind": "foo"})[0] == 400
    assert admin.call("/api/diary", {"kind": "reliever", "value": 99})[0] == 400

    d = admin.call("/api/diary")[1]
    assert d["today"]["reliever"] == 2 and d["reliever_7d"] == 14
    assert d["control"] == "teilweise kontrolliert" and d["control_score"] == 2  # spray >2x/week + night
    assert d["peakflow"]["zone"] == "gelb" and d["peakflow"]["pct"] == 73
    assert d["triggers"][0]["name"] == "Pollen"
    assert len(d["timeline"]) == 30

    ins = admin.call("/api/insights")[1]
    titles = [f["title"] for f in ins["flags"]]
    assert "Asthma teilweise kontrolliert" in titles and "Peak-Flow im gelben Bereich" in titles
    s = svc.state(1)["state"]
    assert s["inhaler_today"] == 2 and s["asthma_control"] == "teilweise kontrolliert" and s["peak_flow"] == 380
    assert s["health_status"] == "Hinweis" and s["steps_goal"] == 0

    # delete an entry; other users can't see or delete it
    eid = d["entries"][-1]["id"]
    admin.call("/api/admin/users", {"username": "anna", "password": "annapasswort"})
    anna = Browser(base)
    anna.call("/api/auth/login", {"username": "anna", "password": "annapasswort"})
    assert anna.call("/api/diary")[1]["has_data"] is False
    assert anna.call(f"/api/diary/{eid}", method="DELETE")[0] == 404
    assert admin.call(f"/api/diary/{eid}", method="DELETE")[0] == 200


def test_environment_goals_and_report(server):
    svc, base, admin = server
    assert admin.call("/api/geo?q=Berl")[1][0]["name"] == "Berlin"
    me = admin.call("/api/me/settings", {"goal_steps": 8000, "location": {"lat": 52.52, "lon": 13.41, "name": "Berlin"}})[1]
    assert me["goal_steps"] == 8000 and me["location"]["name"] == "Berlin"
    assert admin.call("/api/me/settings", {"location": {"lat": 200, "lon": 0}})[0] == 400
    env = admin.call("/api/environment")[1]
    assert env["aqi"] == 72 and env["aqi_label"] == "schlecht" and env["pollen"][0]["name"] == "Birke"
    assert env["pollen_max"] == "hoch" and env["weather"]["text"] == "teils bewölkt" and env["place"] == "Berlin"

    # symptoms on the high-birch days -> correlation hint
    now = int(time.time())
    admin.call("/api/diary", {"entries": [{"kind": "reliever", "value": 1, "ts": now - i * 86400} for i in range(0, 12, 2)]})
    titles = [f["title"] for f in admin.call("/api/insights")[1]["flags"]]
    assert "Birke-Pollen heute hoch" in titles and "Luftqualität schlecht" in titles
    assert "Beschwerden passen zu Birke-Pollen" in titles
    s = svc.state(1)["state"]
    assert s["pollen"] == "hoch" and s["air_quality"] == 72

    dash = admin.call("/api/dashboard")[1]
    assert dash["goals"]["steps"] == 8000 and dash["environment"]["pollen_top"] == "Birke"
    rep = admin.call("/api/report?days=14")[1]
    assert rep["days"] == 14 and rep["asthma"]["reliever_7d"] >= 3 and "summary" in rep
    # the app shell serves the printable report page and the PWA files
    assert Browser(base).call("/bericht")[0] == 200
    code, mf, _ = Browser(base).call("/static/manifest.webmanifest")
    assert code == 200
    assert Browser(base).call("/sw.js")[0] == 200
