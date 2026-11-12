import json
import sqlite3
import sys
import threading
import urllib.request
import zipfile
from datetime import date
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT), str(ROOT / "server"), str(ROOT / "tests")]

from cmfserver.app import Service, make_handler  # noqa: E402
from cmfserver.config import Config  # noqa: E402
from test_export import make_gb  # noqa: E402  (synthetic Gadgetbridge DB from the cmfexport tests)


class FakePub:
    def __init__(self):
        self.payloads = []

    def publish(self, p):
        self.payloads.append(p)
        return True

    def close(self):
        pass


def app_zip(path: Path, day: str, steps: int, first_day_steps: int = 5):
    """ZIP in the layout written by the Android app."""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("daily_summary.csv",
                   "date,steps,distance_m,calories_kcal,hr_avg,hr_min,hr_max,resting_hr,spo2_avg,stress_avg,sleep_min,deep_min,light_min,rem_min,awake_min\n"
                   f"2020-01-01,{first_day_steps},,,,,,,,,,,,,\n"
                   f"{day},{steps},1000,250,70,50,120,55,97,,420,90,250,80,20\n")
        z.writestr("sleep_sessions.csv",
                   "night_of,fell_asleep,woke_up,time_in_bed_min,asleep_min,deep_min,light_min,rem_min,awake_min,efficiency_pct,source\n"
                   f"{day},{day}T00:10:00+01:00,{day}T07:10:00+01:00,420,400,90,230,80,20,95.2,com.nothing.cmf.watch\n")
        z.writestr("heart_rate.csv", f"time,bpm,app,device,record_id\n{day}T08:00:00+01:00,64,x,,\n{day}T09:00:00+01:00,66,x,,\n")
        z.writestr("spo2.csv", f"time,spo2_pct,app,device,record_id\n{day}T03:00:00+01:00,96.0,x,,\n")
        z.writestr("workouts.csv", f"start,end,duration_min,exercise_type,title,notes,route,app,device,record_id\n{day}T18:00:00+01:00,{day}T18:30:00+01:00,30,56,Laufen,,,x,,\n")


@pytest.fixture
def server(tmp_path):
    cfg = Config(api_token="secret", data_dir=tmp_path / "data", import_dir=tmp_path / "import")
    pub = FakePub()
    svc = Service(cfg, publishers=[pub])
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(svc))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield svc, pub, f"http://127.0.0.1:{httpd.server_port}"
    httpd.shutdown()


def req(url, data=None, token="secret", method=None):
    r = urllib.request.Request(url, data=data, method=method)
    if token:
        r.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def test_auth(server):
    _, _, base = server
    assert req(base + "/healthz", token=None)[0] == 200
    assert req(base + "/api/latest", token=None)[0] == 401
    assert req(base + "/api/latest", token="wrong")[0] == 401
    assert req(base + "/api/latest")[0] == 200


def test_app_zip_upload(server, tmp_path):
    svc, pub, base = server
    today = date.today().isoformat()
    z = tmp_path / "a.zip"
    app_zip(z, today, 4321)
    code, res = req(base + "/api/upload?name=a.zip", z.read_bytes())
    assert code == 200, res
    st = res["state"]
    assert st["steps_today"] == 4321 and st["sleep_duration"] == 400 and st["sleep_deep"] == 90
    assert st["heart_rate"] == 66 and st["spo2"] == 96 and st["resting_heart_rate"] == 55
    assert st["last_workout"] == "Laufen" and "last_upload" in st
    # windowed upload: the (partial) first day is ignored
    assert svc.store.history("daily", 0)[0]["date"] == today
    assert pub.payloads and pub.payloads[-1]["state"]["steps_today"] == 4321

    # a later upload with a partial window must not lower today's steps
    app_zip(z, today, 1000)
    req(base + "/api/upload", z.read_bytes())
    assert req(base + "/api/latest")[1]["state"]["steps_today"] == 4321


def test_gadgetbridge_upload_and_import_dir(server, tmp_path):
    svc, pub, base = server
    db = tmp_path / "Gadgetbridge"
    make_gb(db)
    code, res = req(base + "/api/upload", db.read_bytes())
    assert code == 200, res
    assert res["kind"] == "gadgetbridge"
    st = res["state"]
    assert st["sleep_deep"] == 60 and st["sleep_rem"] == 20 and st["stress"] == 23 and st["spo2"] == 97
    assert st["last_workout"] == "OUTDOOR_RUNNING"
    hist = req(base + "/api/history?metric=heart_rate&days=36500")[1]
    assert [h["value"] for h in hist] == [58]

    # unsupported file
    assert req(base + "/api/upload", b"hello world")[0] == 415

    # import folder (Gadgetbridge auto-export via Syncthing etc.)
    svc.cfg.watch_interval = 0
    imp = svc.cfg.import_dir
    imp.mkdir(parents=True)
    zp = imp / "gb.zip"
    with zipfile.ZipFile(zp, "w") as z:
        z.write(db, "database/Gadgetbridge")
    import os, time
    old = time.time() - 60
    os.utime(zp, (old, old))
    t = threading.Thread(target=svc.watch_import_dir)
    t.start()
    time.sleep(1.5)
    svc.stop.set()
    t.join(5)
    assert any(u["name"] == "gb.zip" for u in svc.store.uploads())


def test_multipart_form(server, tmp_path):
    _, _, base = server
    z = tmp_path / "a.zip"
    app_zip(z, date.today().isoformat(), 77)
    boundary = "XyZ"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.zip\"\r\n"
            "Content-Type: application/zip\r\n\r\n").encode() + z.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    r = urllib.request.Request(base + "/api/upload", data=body, method="POST")
    r.add_header("Authorization", "Bearer secret")
    r.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    with urllib.request.urlopen(r) as resp:
        assert json.loads(resp.read())["state"]["steps_today"] == 77
