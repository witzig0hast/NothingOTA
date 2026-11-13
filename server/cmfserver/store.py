"""SQLite store: users, sessions and all health data (merged from every upload)."""
from __future__ import annotations

import csv
import hashlib
import hmac
import secrets
import sqlite3
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from cmfexport import sleep as sleeplib

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL COLLATE NOCASE,
    pw_hash TEXT NOT NULL, token TEXT UNIQUE NOT NULL, is_admin INTEGER DEFAULT 0,
    created INTEGER, publish_ha INTEGER DEFAULT 1
);
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, created INTEGER, expires INTEGER
);
CREATE TABLE IF NOT EXISTS daily (
    user_id INTEGER NOT NULL, date TEXT NOT NULL,
    steps INTEGER, distance_m REAL, calories_kcal REAL,
    hr_avg REAL, hr_min INTEGER, hr_max INTEGER, resting_hr INTEGER,
    spo2_avg REAL, stress_avg REAL,
    sleep_min REAL, deep_min REAL, light_min REAL, rem_min REAL, awake_min REAL,
    PRIMARY KEY (user_id, date)
);
CREATE TABLE IF NOT EXISTS sleep (
    user_id INTEGER NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER, start TEXT, end TEXT,
    in_bed_min REAL, asleep_min REAL, deep_min REAL, light_min REAL, rem_min REAL, awake_min REAL,
    efficiency_pct REAL, source TEXT,
    PRIMARY KEY (user_id, start_ts)
);
CREATE TABLE IF NOT EXISTS stage (
    user_id INTEGER NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER, stage TEXT,
    PRIMARY KEY (user_id, start_ts)
);
CREATE TABLE IF NOT EXISTS sample (
    user_id INTEGER NOT NULL, metric TEXT NOT NULL, ts INTEGER NOT NULL, time TEXT, value REAL,
    PRIMARY KEY (user_id, metric, ts)
);
CREATE TABLE IF NOT EXISTS workout (
    user_id INTEGER NOT NULL, start_ts INTEGER NOT NULL, start TEXT, end TEXT, duration_min REAL, type TEXT, source TEXT,
    distance_km REAL, avg_hr INTEGER, max_hr INTEGER, elevation_gain_m REAL, calories REAL,
    PRIMARY KEY (user_id, start_ts)
);
CREATE TABLE IF NOT EXISTS track (
    user_id INTEGER NOT NULL, workout_ts INTEGER NOT NULL, ts INTEGER NOT NULL, lat REAL, lon REAL, alt REAL,
    PRIMARY KEY (user_id, workout_ts, ts)
);
CREATE TABLE IF NOT EXISTS upload (
    id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, ts INTEGER, name TEXT, kind TEXT, summary TEXT
);
CREATE TABLE IF NOT EXISTS report (
    user_id INTEGER NOT NULL, day TEXT NOT NULL, kind TEXT NOT NULL, created INTEGER, content TEXT,
    PRIMARY KEY (user_id, day, kind)
);
CREATE INDEX IF NOT EXISTS sample_ts ON sample(user_id, metric, ts);
"""

WORKOUT_EXTRA = ["distance_km", "avg_hr", "max_hr", "elevation_gain_m", "calories"]

DAILY_COLS = ["steps", "distance_m", "calories_kcal", "hr_avg", "hr_min", "hr_max", "resting_hr",
              "spo2_avg", "stress_avg", "sleep_min", "deep_min", "light_min", "rem_min", "awake_min"]
# columns that only ever grow during a day -> keep the larger value when merging
MONOTONIC = {"steps", "distance_m", "calories_kcal"}
DAILY_ALIASES = {"calories": "calories_kcal"}

# file name -> (metric, candidate value columns)
SAMPLE_FILES = {
    "heart_rate.csv": ("heart_rate", ["heart_rate", "bpm"]),
    "spo2.csv": ("spo2", ["spo2", "spo2_pct"]),
    "stress.csv": ("stress", ["stress"]),
    "resting_heart_rate.csv": ("resting_hr", ["bpm"]),
    "hrv_rmssd.csv": ("hrv", ["rmssd_ms"]),
    "respiratory_rate.csv": ("respiratory_rate", ["breaths_per_min"]),
    "weight.csv": ("weight", ["kg"]),
    "battery.csv": ("battery", ["level", "battery_pct"]),
}

SESSION_DAYS = 30


def parse_time(s: str | None) -> datetime | None:
    if not s:
        return None
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def num(s):
    if s is None or s == "":
        return None
    try:
        f = float(s)
    except ValueError:
        return None
    return int(f) if f.is_integer() else f


def read_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def now_ts() -> int:
    return int(datetime.now(timezone.utc).timestamp())


# ---------------------------------------------------------------------------------- passwords & tokens
def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.scrypt(pw.encode(), salt=salt, n=2 ** 14, r=8, p=1, dklen=32)
    return f"scrypt${salt.hex()}${dk.hex()}"


def check_password(pw: str, stored: str) -> bool:
    try:
        _, salt, dk = stored.split("$")
        test = hashlib.scrypt(pw.encode(), salt=bytes.fromhex(salt), n=2 ** 14, r=8, p=1, dklen=32)
        return hmac.compare_digest(test.hex(), dk)
    except (ValueError, TypeError):
        return False


def new_token() -> str:
    """Short, easy to type token: cmf- + 12 characters (~71 bits)."""
    alphabet = "abcdefghjkmnpqrstuvwxyz23456789"  # no 0/o/1/l/i
    return "cmf-" + "".join(secrets.choice(alphabet) for _ in range(12))


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.lock = threading.RLock()       # serialises writes
        self._local = threading.local()     # one connection per thread (the web server is multi-threaded)
        self.con.execute("PRAGMA journal_mode=WAL")
        self._migrate_single_user()
        self.con.executescript(SCHEMA)
        self.con.commit()

    @property
    def con(self) -> sqlite3.Connection:
        c = getattr(self._local, "con", None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=30)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA busy_timeout=30000")
            self._local.con = c
        return c

    def _migrate_single_user(self) -> None:
        """Databases from cmfserver 1.x had no users: move their rows to user 1."""
        cols = {r[1] for r in self.con.execute("PRAGMA table_info(daily)")}
        if not cols or "user_id" in cols:
            return
        old = {t: f"old_{t}" for t in ("daily", "sleep", "sample", "workout", "upload")}
        for t, o in old.items():
            if self.con.execute("SELECT 1 FROM sqlite_master WHERE name=?", (t,)).fetchone():
                self.con.execute(f"ALTER TABLE {t} RENAME TO {o}")
        self.con.executescript(SCHEMA)
        for t, o in old.items():
            if not self.con.execute("SELECT 1 FROM sqlite_master WHERE name=?", (o,)).fetchone():
                continue
            src = [r[1] for r in self.con.execute(f"PRAGMA table_info({o})")]
            dst = {r[1] for r in self.con.execute(f"PRAGMA table_info({t})")}
            keep = [c for c in src if c in dst and c != "id"]
            self.con.execute(f"INSERT OR IGNORE INTO {t} (user_id, {', '.join(keep)}) SELECT 1, {', '.join(keep)} FROM {o}")
            self.con.execute(f"DROP TABLE {o}")
        self.con.commit()
        self.migrated_legacy = True

    # ------------------------------------------------------------------------------- users
    def user_count(self) -> int:
        return self.con.execute("SELECT COUNT(*) FROM users").fetchone()[0]

    def create_user(self, username: str, password: str, is_admin: bool = False) -> dict:
        with self.lock, self.con:
            cur = self.con.execute(
                "INSERT INTO users (username, pw_hash, token, is_admin, created) VALUES (?,?,?,?,?)",
                (username.strip(), hash_password(password), new_token(), 1 if is_admin else 0, now_ts()))
        return self.user(cur.lastrowid)

    def user(self, uid: int) -> dict | None:
        r = self.con.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        return dict(r) if r else None

    def users(self) -> list[dict]:
        return [dict(r) for r in self.con.execute("SELECT id, username, is_admin, created, publish_ha FROM users ORDER BY id")]

    def authenticate(self, username: str, password: str) -> dict | None:
        r = self.con.execute("SELECT * FROM users WHERE username=?", (username.strip(),)).fetchone()
        if r and check_password(password, r["pw_hash"]):
            return dict(r)
        check_password(password, hash_password("x"))  # same timing for unknown users
        return None

    def user_by_token(self, token: str) -> dict | None:
        if not token:
            return None
        for r in self.con.execute("SELECT * FROM users"):
            if hmac.compare_digest(r["token"].encode(), token.encode()):
                return dict(r)
        return None

    def rotate_token(self, uid: int) -> str:
        t = new_token()
        with self.lock, self.con:
            self.con.execute("UPDATE users SET token=? WHERE id=?", (t, uid))
        return t

    def set_password(self, uid: int, pw: str) -> None:
        with self.lock, self.con:
            self.con.execute("UPDATE users SET pw_hash=? WHERE id=?", (hash_password(pw), uid))
            self.con.execute("DELETE FROM sessions WHERE user_id=?", (uid,))

    def set_publish_ha(self, uid: int, on: bool) -> None:
        with self.lock, self.con:
            self.con.execute("UPDATE users SET publish_ha=? WHERE id=?", (1 if on else 0, uid))

    def delete_user(self, uid: int) -> None:
        with self.lock, self.con:
            for t in ("daily", "sleep", "stage", "sample", "workout", "track", "upload", "report", "sessions"):
                self.con.execute(f"DELETE FROM {t} WHERE user_id=?", (uid,))
            self.con.execute("DELETE FROM users WHERE id=?", (uid,))

    # ----------------------------------------------------------------------------- sessions
    def create_session(self, uid: int) -> str:
        sid = secrets.token_urlsafe(32)
        with self.lock, self.con:
            self.con.execute("INSERT INTO sessions VALUES (?,?,?,?)", (sid, uid, now_ts(), now_ts() + SESSION_DAYS * 86400))
            self.con.execute("DELETE FROM sessions WHERE expires < ?", (now_ts(),))
        return sid

    def session_user(self, sid: str) -> dict | None:
        if not sid:
            return None
        r = self.con.execute("SELECT u.* FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.id=? AND s.expires > ?",
                             (sid, now_ts())).fetchone()
        return dict(r) if r else None

    def delete_session(self, sid: str) -> None:
        with self.lock, self.con:
            self.con.execute("DELETE FROM sessions WHERE id=?", (sid,))

    # ------------------------------------------------------------------------------- ingest
    def ingest_dir(self, uid: int, d: Path, *, windowed: bool, name: str, kind: str) -> dict:
        """Merge an export directory (CSV layout of cmfexport / the Android apps)."""
        counts: dict[str, int] = {}
        with self.lock, self.con:
            counts["days"] = self._daily(uid, read_csv(d / "daily_summary.csv"), windowed)
            counts["nights"] = self._sleep(uid, read_csv(d / "sleep_sessions.csv"))
            counts["stages"] = self._stages(uid, read_csv(d / "sleep_stages.csv"))
            for fname, (metric, cols) in SAMPLE_FILES.items():
                n = self._samples(uid, read_csv(d / fname), metric, cols)
                if n:
                    counts[metric] = n
            counts["workouts"] = self._workouts(uid, read_csv(d / "workouts.csv"))
            counts["track_points"] = self._tracks(uid, read_csv(d / "track.csv"), read_csv(d / "workout_gps.csv"))
            summary = ", ".join(f"{k}={v}" for k, v in counts.items() if v)
            self.con.execute("INSERT INTO upload (user_id, ts, name, kind, summary) VALUES (?,?,?,?,?)",
                             (uid, now_ts(), name, kind, summary))
        return counts

    def _daily(self, uid: int, rows: list[dict], windowed: bool) -> int:
        if not rows:
            return 0
        rows = sorted(rows, key=lambda r: r.get("date", ""))
        # An upload covering "the last N days" starts mid-day: its first day is incomplete.
        if windowed and len(rows) > 1:
            rows = rows[1:]
        sets = ", ".join(
            f"{c} = MAX(COALESCE(excluded.{c}, 0), COALESCE(daily.{c}, 0))" if c in MONOTONIC
            else f"{c} = COALESCE(excluded.{c}, daily.{c})" for c in DAILY_COLS)
        for r in rows:
            vals = {DAILY_ALIASES.get(k, k): num(v) for k, v in r.items() if k != "date"}
            self.con.execute(
                f"INSERT INTO daily (user_id, date, {', '.join(DAILY_COLS)}) VALUES (?, ?{', ?' * len(DAILY_COLS)}) "
                f"ON CONFLICT(user_id, date) DO UPDATE SET {sets}",
                [uid, r["date"], *[vals.get(c) for c in DAILY_COLS]])
        return len(rows)

    def _sleep(self, uid: int, rows: list[dict]) -> int:
        n = 0
        for r in rows:
            start = parse_time(r.get("fell_asleep") or r.get("start"))
            end = parse_time(r.get("woke_up") or r.get("end"))
            if not start or not end:
                continue
            self.con.execute(
                "INSERT OR REPLACE INTO sleep VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (uid, int(start.timestamp()), int(end.timestamp()), start.isoformat(), end.isoformat(),
                 num(r.get("time_in_bed_min")), num(r.get("asleep_min")), num(r.get("deep_min")),
                 num(r.get("light_min")), num(r.get("rem_min")), num(r.get("awake_min")),
                 num(r.get("efficiency_pct")), r.get("source") or r.get("app_package")))
            n += 1
        return n

    def _stages(self, uid: int, rows: list[dict]) -> int:
        data = []
        for r in rows:
            a, b = parse_time(r.get("start")), parse_time(r.get("end"))
            stage = (r.get("stage") or "").lower()
            if a and b and stage:
                stage = {"sleeping": "light", "awake_in_bed": "awake", "out_of_bed": "awake"}.get(stage, stage)
                data.append((uid, int(a.timestamp()), int(b.timestamp()), stage))
        self.con.executemany("INSERT OR REPLACE INTO stage VALUES (?,?,?,?)", data)
        return len(data)

    def _samples(self, uid: int, rows: list[dict], metric: str, cols: list[str]) -> int:
        if not rows:
            return 0
        col = next((c for c in cols if c in rows[0]), None)
        if col is None:
            return 0
        data = []
        for r in rows:
            t = parse_time(r.get("time"))
            v = num(r.get(col))
            if t is None or v is None:
                continue
            data.append((uid, metric, int(t.timestamp()), t.isoformat(), v))
        self.con.executemany("INSERT OR REPLACE INTO sample VALUES (?,?,?,?,?)", data)
        return len(data)

    def _workouts(self, uid: int, rows: list[dict]) -> int:
        n = 0
        for r in rows:
            start = parse_time(r.get("start"))
            if not start:
                continue
            wtype = r.get("workout_type") or r.get("title") or (
                f"exercise_{r['exercise_type']}" if r.get("exercise_type") else None)
            self.con.execute(
                f"INSERT OR REPLACE INTO workout (user_id, start_ts, start, end, duration_min, type, source, {', '.join(WORKOUT_EXTRA)}) "
                f"VALUES (?,?,?,?,?,?,?{', ?' * len(WORKOUT_EXTRA)})",
                (uid, int(start.timestamp()), start.isoformat(), r.get("end"), num(r.get("duration_min")), wtype,
                 r.get("app"), *[num(r.get(k)) for k in WORKOUT_EXTRA]))
            n += 1
        return n

    def _tracks(self, uid: int, track: list[dict], gps: list[dict]) -> int:
        """track.csv (CMF Track: workout_start,time,lat,lon,alt) or workout_gps.csv (Gadgetbridge: time,lat,lon)."""
        data = []
        for r in track:
            ws, t = parse_time(r.get("workout_start")), parse_time(r.get("time"))
            if ws and t and r.get("lat") and r.get("lon"):
                data.append((uid, int(ws.timestamp()), int(t.timestamp()), float(r["lat"]), float(r["lon"]), num(r.get("alt"))))
        if gps:
            workouts = [(w["start_ts"], parse_time(w["end"])) for w in
                        self.con.execute("SELECT start_ts, end FROM workout WHERE user_id=?", (uid,))]
            for r in gps:
                t = parse_time(r.get("time"))
                if not t or not r.get("lat"):
                    continue
                ts = int(t.timestamp())
                for ws, we in workouts:
                    if ws <= ts <= (int(we.timestamp()) if we else ws + 86400):
                        data.append((uid, ws, ts, float(r["lat"]), float(r["lon"]), None))
                        break
        self.con.executemany("INSERT OR REPLACE INTO track VALUES (?,?,?,?,?,?)", data)
        return len(data)

    # -------------------------------------------------------------------------------- query
    def latest_state(self, uid: int, today: date) -> dict:
        """Current values for Home Assistant and the dashboard. Keys missing = unknown."""
        s: dict = {}
        attrs: dict[str, dict] = {}
        with self.lock:
            q = self.con.execute
            nights, naps = self.sleep_grouped(uid, days=10)
            if nights:
                n = nights[-1]
                s.update(sleep_duration=round(n.asleep, 1), sleep_deep=round(n.deep, 1),
                         sleep_light=round(n.light, 1), sleep_rem=round(n.rem, 1),
                         sleep_awake=round(n.awake, 1),
                         sleep_efficiency=round(n.efficiency, 1) if n.efficiency is not None else None,
                         sleep_start=n.start.isoformat(), sleep_end=n.end.isoformat(),
                         sleep_in_bed=round(n.in_bed, 1))
                attrs["sleep_duration"] = {"night_of": sleeplib.day_of(n).isoformat(), "segments": n.segments,
                                           "start": n.start.isoformat(), "end": n.end.isoformat()}
                week = [x for x in nights if (today - sleeplib.day_of(x)).days < 7]
                if week:
                    s["sleep_avg_7d"] = round(sum(x.asleep for x in week) / len(week), 1)
                    attrs["sleep_avg_7d"] = {"nights": len(week)}
            todays = [x for x in naps if x.start.astimezone().date() == today]
            s["nap_today"] = round(sum(x.asleep for x in todays), 1)
            if todays:
                attrs["nap_today"] = {"naps": [{"start": x.start.isoformat(), "end": x.end.isoformat(),
                                                "minutes": round(x.asleep, 1)} for x in todays]}

            d = q("SELECT * FROM daily WHERE user_id=? AND date = ?", (uid, today.isoformat())).fetchone()
            s["steps_today"] = (d["steps"] if d and d["steps"] is not None else 0)
            s["distance_today"] = (d["distance_m"] if d and d["distance_m"] is not None else 0)
            s["calories_today"] = (d["calories_kcal"] if d and d["calories_kcal"] is not None else 0)
            if d:
                for k_state, k_db in (("hr_avg_today", "hr_avg"), ("hr_min_today", "hr_min"), ("hr_max_today", "hr_max")):
                    if d[k_db] is not None:
                        s[k_state] = d[k_db]
            prev = q("SELECT * FROM daily WHERE user_id=? AND date < ? ORDER BY date DESC LIMIT 1", (uid, today.isoformat())).fetchone()
            if prev and prev["steps"] is not None:
                s["steps_yesterday"] = prev["steps"]
                attrs["steps_yesterday"] = {"date": prev["date"]}

            for metric, key in (("heart_rate", "heart_rate"), ("spo2", "spo2"), ("stress", "stress"),
                                ("resting_hr", "resting_heart_rate"), ("hrv", "hrv"),
                                ("respiratory_rate", "respiratory_rate"), ("weight", "weight"),
                                ("battery", "watch_battery")):
                row = q("SELECT ts, time, value FROM sample WHERE user_id=? AND metric = ? ORDER BY ts DESC LIMIT 1", (uid, metric)).fetchone()
                if row and metric == "battery" and row["ts"] < now_ts() - 2 * 86400:
                    continue  # a battery level older than 2 days is misleading
                if row:
                    s[key] = row["value"]
                    attrs[key] = {"measured_at": row["time"]}
            if "resting_heart_rate" not in s:
                row = q("SELECT date, resting_hr FROM daily WHERE user_id=? AND resting_hr IS NOT NULL ORDER BY date DESC LIMIT 1", (uid,)).fetchone()
                if row:
                    s["resting_heart_rate"] = row["resting_hr"]
                    attrs["resting_heart_rate"] = {"date": row["date"]}

            w = q("SELECT * FROM workout WHERE user_id=? ORDER BY start_ts DESC LIMIT 1", (uid,)).fetchone()
            if w:
                s["last_workout"] = w["type"] or "workout"
                attrs["last_workout"] = {k: w[k] for k in w.keys() if w[k] is not None and k != "user_id"}
                for key, col in (("last_workout_distance", "distance_km"), ("last_workout_duration", "duration_min"),
                                 ("last_workout_avg_hr", "avg_hr")):
                    if w[col] is not None:
                        s[key] = w[col]
                        attrs[key] = {"start": w["start"], "type": w["type"]}

            up = q("SELECT * FROM upload WHERE user_id=? ORDER BY id DESC LIMIT 1", (uid,)).fetchone()
            if up:
                s["last_upload"] = datetime.fromtimestamp(up["ts"], timezone.utc).isoformat()
                attrs["last_upload"] = {"file": up["name"], "kind": up["kind"], "summary": up["summary"]}
        return {"state": {k: v for k, v in s.items() if v is not None}, "attributes": attrs}

    def sleep_grouped(self, uid: int, days: int | None = None):
        """Stored sleep sessions -> (nights, naps), see cmfexport.sleep."""
        sql, args = "SELECT * FROM sleep WHERE user_id=?", [uid]
        if days is not None:  # window before the newest stored session (works after a sync pause, too)
            newest = self.con.execute("SELECT MAX(end_ts) FROM sleep WHERE user_id=?", (uid,)).fetchone()[0] or 0
            sql += " AND end_ts >= ?"
            args.append(newest - days * 86400)
        rows = self.con.execute(sql + " ORDER BY start_ts", args).fetchall()
        sessions = []
        for r in rows:
            deep, light, rem = (r["deep_min"] or 0), (r["light_min"] or 0), (r["rem_min"] or 0)
            sessions.append(sleeplib.Session(
                datetime.fromtimestamp(r["start_ts"], timezone.utc), datetime.fromtimestamp(r["end_ts"], timezone.utc),
                deep, light, rem, r["awake_min"] or 0, has_stages=(deep + light + rem) > 0, source=r["source"]))
        return sleeplib.group(sessions)

    @staticmethod
    def night_dict(n) -> dict:
        return {"night_of": (n.start.astimezone().date() if n.nap else sleeplib.day_of(n)).isoformat(),
                "type": "nap" if n.nap else "night", "start": n.start.isoformat(), "end": n.end.isoformat(),
                "start_ts": int(n.start.timestamp()), "end_ts": int(n.end.timestamp()),
                "asleep_min": round(n.asleep, 1), "in_bed_min": round(n.in_bed, 1),
                "deep_min": round(n.deep, 1), "light_min": round(n.light, 1), "rem_min": round(n.rem, 1),
                "awake_min": round(n.awake, 1), "segments": n.segments,
                "efficiency": round(n.efficiency, 1) if n.efficiency is not None else None}

    def nights(self, uid: int, days: int = 30) -> list[dict]:
        nights, naps = self.sleep_grouped(uid)
        cutoff = datetime.now(timezone.utc) - timedelta(days=days)
        return [self.night_dict(n) for n in sorted(nights + naps, key=lambda x: x.start) if n.end >= cutoff]

    def stages(self, uid: int, start_ts: int, end_ts: int) -> list[dict]:
        return [dict(r) for r in self.con.execute(
            "SELECT start_ts, end_ts, stage FROM stage WHERE user_id=? AND start_ts >= ? AND start_ts < ? ORDER BY start_ts",
            (uid, start_ts - 60, end_ts))]

    def series(self, uid: int, metric: str, since_ts: int, until_ts: int | None = None, max_points: int = 1500) -> list[list]:
        """[[ts, value], ...]; long ranges are averaged into buckets so the browser stays fast."""
        until_ts = until_ts or now_ts() + 86400
        rows = self.con.execute("SELECT ts, value FROM sample WHERE user_id=? AND metric=? AND ts BETWEEN ? AND ? ORDER BY ts",
                                (uid, metric, since_ts, until_ts)).fetchall()
        if len(rows) <= max_points:
            return [[r[0], r[1]] for r in rows]
        bucket = (until_ts - since_ts) / max_points
        out: dict[int, list] = {}
        for ts, v in rows:
            out.setdefault(int((ts - since_ts) // bucket), []).append((ts, v))
        return [[int(sum(t for t, _ in b) / len(b)), round(sum(v for _, v in b) / len(b), 1)] for _, b in sorted(out.items())]

    def daily(self, uid: int, days: int = 90) -> list[dict]:
        since = (date.today() - timedelta(days=days)).isoformat()
        return [{k: r[k] for k in r.keys() if k != "user_id"} for r in
                self.con.execute("SELECT * FROM daily WHERE user_id=? AND date >= ? ORDER BY date", (uid, since))]

    def workouts(self, uid: int, limit: int = 100) -> list[dict]:
        rows = self.con.execute(
            "SELECT w.*, (SELECT COUNT(*) FROM track t WHERE t.user_id=w.user_id AND t.workout_ts=w.start_ts) AS points "
            "FROM workout w WHERE user_id=? ORDER BY start_ts DESC LIMIT ?", (uid, limit))
        return [{k: r[k] for k in r.keys() if k != "user_id"} for r in rows]

    def workout(self, uid: int, start_ts: int) -> dict | None:
        r = self.con.execute("SELECT * FROM workout WHERE user_id=? AND start_ts=?", (uid, start_ts)).fetchone()
        if not r:
            return None
        w = {k: r[k] for k in r.keys() if k != "user_id"}
        w["track"] = [[t["ts"], t["lat"], t["lon"], t["alt"]] for t in self.con.execute(
            "SELECT ts, lat, lon, alt FROM track WHERE user_id=? AND workout_ts=? ORDER BY ts", (uid, start_ts))]
        end = parse_time(w.get("end"))
        w["heart_rate"] = self.series(uid, "heart_rate", start_ts, int(end.timestamp()) if end else start_ts + 3 * 3600)
        return w

    def uploads(self, uid: int, limit: int = 20) -> list[dict]:
        return [{k: r[k] for k in r.keys() if k != "user_id"} for r in
                self.con.execute("SELECT * FROM upload WHERE user_id=? ORDER BY id DESC LIMIT ?", (uid, limit))]

    def save_report(self, uid: int, day: str, kind: str, content: str) -> None:
        with self.lock, self.con:
            self.con.execute("INSERT OR REPLACE INTO report VALUES (?,?,?,?,?)", (uid, day, kind, now_ts(), content))

    def report(self, uid: int, day: str, kind: str) -> dict | None:
        r = self.con.execute("SELECT * FROM report WHERE user_id=? AND day=? AND kind=?", (uid, day, kind)).fetchone()
        return dict(r) if r else None

    def export_all(self, uid: int) -> dict:
        """Everything stored for one user (DSGVO export)."""
        out = {}
        for t in ("daily", "sleep", "stage", "sample", "workout", "track", "upload", "report"):
            out[t] = [{k: r[k] for k in r.keys() if k != "user_id"} for r in self.con.execute(f"SELECT * FROM {t} WHERE user_id=?", (uid,))]
        return out
