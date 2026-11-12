"""SQLite store that merges data from every upload (app ZIP, Gadgetbridge DB, CSV export)."""
from __future__ import annotations

import csv
import sqlite3
import threading
from datetime import date, datetime, timezone
from pathlib import Path

from cmfexport import sleep as sleeplib

SCHEMA = """
CREATE TABLE IF NOT EXISTS daily (
    date TEXT PRIMARY KEY,
    steps INTEGER, distance_m REAL, calories_kcal REAL,
    hr_avg REAL, hr_min INTEGER, hr_max INTEGER, resting_hr INTEGER,
    spo2_avg REAL, stress_avg REAL,
    sleep_min REAL, deep_min REAL, light_min REAL, rem_min REAL, awake_min REAL
);
CREATE TABLE IF NOT EXISTS sleep (
    start_ts INTEGER PRIMARY KEY, end_ts INTEGER, start TEXT, end TEXT,
    in_bed_min REAL, asleep_min REAL, deep_min REAL, light_min REAL, rem_min REAL, awake_min REAL,
    efficiency_pct REAL, source TEXT
);
CREATE TABLE IF NOT EXISTS sample (
    metric TEXT NOT NULL, ts INTEGER NOT NULL, time TEXT, value REAL,
    PRIMARY KEY (metric, ts)
);
CREATE TABLE IF NOT EXISTS workout (
    start_ts INTEGER PRIMARY KEY, start TEXT, end TEXT, duration_min REAL, type TEXT, source TEXT
);
CREATE TABLE IF NOT EXISTS upload (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, name TEXT, kind TEXT, summary TEXT
);
"""

WORKOUT_EXTRA = {"distance_km": "REAL", "avg_hr": "INTEGER", "max_hr": "INTEGER",
                 "elevation_gain_m": "REAL", "calories": "REAL"}

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


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.con = sqlite3.connect(path, check_same_thread=False)
        self.con.row_factory = sqlite3.Row
        self.con.executescript(SCHEMA)
        # migrations for databases created by older versions
        have = {r[1] for r in self.con.execute("PRAGMA table_info(workout)")}
        for col, typ in WORKOUT_EXTRA.items():
            if col not in have:
                self.con.execute(f"ALTER TABLE workout ADD COLUMN {col} {typ}")
        self.con.commit()

    # ------------------------------------------------------------------ ingest
    def ingest_dir(self, d: Path, *, windowed: bool, name: str, kind: str) -> dict:
        """Merge an export directory (CSV layout of cmfexport / the Android app)."""
        counts: dict[str, int] = {}
        with self.lock, self.con:
            counts["days"] = self._daily(read_csv(d / "daily_summary.csv"), windowed)
            counts["nights"] = self._sleep(read_csv(d / "sleep_sessions.csv"))
            for fname, (metric, cols) in SAMPLE_FILES.items():
                n = self._samples(read_csv(d / fname), metric, cols)
                if n:
                    counts[metric] = n
            counts["workouts"] = self._workouts(read_csv(d / "workouts.csv"))
            summary = ", ".join(f"{k}={v}" for k, v in counts.items() if v)
            self.con.execute("INSERT INTO upload (ts, name, kind, summary) VALUES (?,?,?,?)",
                             (int(datetime.now(timezone.utc).timestamp()), name, kind, summary))
        return counts

    def _daily(self, rows: list[dict], windowed: bool) -> int:
        if not rows:
            return 0
        rows = sorted(rows, key=lambda r: r.get("date", ""))
        # An upload covering "the last N days" starts mid-day: its first day is incomplete.
        if windowed and len(rows) > 1:
            rows = rows[1:]
        for r in rows:
            vals = {DAILY_ALIASES.get(k, k): num(v) for k, v in r.items() if k != "date"}
            vals = {k: vals.get(k) for k in DAILY_COLS}
            sets = ", ".join(
                f"{c} = MAX(COALESCE(excluded.{c}, 0), COALESCE(daily.{c}, 0))" if c in MONOTONIC
                else f"{c} = COALESCE(excluded.{c}, daily.{c})" for c in DAILY_COLS)
            self.con.execute(
                f"INSERT INTO daily (date, {', '.join(DAILY_COLS)}) VALUES (?{', ?' * len(DAILY_COLS)}) "
                f"ON CONFLICT(date) DO UPDATE SET {sets}",
                [r["date"], *[vals[c] for c in DAILY_COLS]])
        return len(rows)

    def _sleep(self, rows: list[dict]) -> int:
        n = 0
        for r in rows:
            start = parse_time(r.get("fell_asleep") or r.get("start"))
            end = parse_time(r.get("woke_up") or r.get("end"))
            if not start or not end:
                continue
            self.con.execute(
                "INSERT OR REPLACE INTO sleep VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (int(start.timestamp()), int(end.timestamp()), start.isoformat(), end.isoformat(),
                 num(r.get("time_in_bed_min")), num(r.get("asleep_min")), num(r.get("deep_min")),
                 num(r.get("light_min")), num(r.get("rem_min")), num(r.get("awake_min")),
                 num(r.get("efficiency_pct")), r.get("source") or r.get("app_package")))
            n += 1
        return n

    def _samples(self, rows: list[dict], metric: str, cols: list[str]) -> int:
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
            data.append((metric, int(t.timestamp()), t.isoformat(), v))
        self.con.executemany("INSERT OR REPLACE INTO sample VALUES (?,?,?,?)", data)
        return len(data)

    def _workouts(self, rows: list[dict]) -> int:
        n = 0
        for r in rows:
            start = parse_time(r.get("start"))
            if not start:
                continue
            wtype = r.get("workout_type") or r.get("title") or (
                f"exercise_{r['exercise_type']}" if r.get("exercise_type") else None)
            extra = {k: num(r.get(k)) for k in WORKOUT_EXTRA}
            self.con.execute(
                f"INSERT OR REPLACE INTO workout (start_ts, start, end, duration_min, type, source, {', '.join(extra)}) "
                f"VALUES (?,?,?,?,?,?{', ?' * len(extra)})",
                (int(start.timestamp()), start.isoformat(), r.get("end"), num(r.get("duration_min")), wtype,
                 r.get("app"), *extra.values()))
            n += 1
        return n

    # ------------------------------------------------------------------- query
    def latest_state(self, today: date) -> dict:
        """Current values for Home Assistant. Keys missing = unknown."""
        s: dict = {}
        attrs: dict[str, dict] = {}
        with self.lock:
            q = self.con.execute
            nights, naps = self.sleep_grouped(days=10)
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

            d = q("SELECT * FROM daily WHERE date = ?", (today.isoformat(),)).fetchone()
            s["steps_today"] = (d["steps"] if d and d["steps"] is not None else 0)
            s["distance_today"] = (d["distance_m"] if d and d["distance_m"] is not None else 0)
            s["calories_today"] = (d["calories_kcal"] if d and d["calories_kcal"] is not None else 0)
            if d:
                for k_state, k_db in (("hr_avg_today", "hr_avg"), ("hr_min_today", "hr_min"), ("hr_max_today", "hr_max")):
                    if d[k_db] is not None:
                        s[k_state] = d[k_db]
            prev = q("SELECT * FROM daily WHERE date < ? ORDER BY date DESC LIMIT 1", (today.isoformat(),)).fetchone()
            if prev and prev["steps"] is not None:
                s["steps_yesterday"] = prev["steps"]
                attrs["steps_yesterday"] = {"date": prev["date"]}

            for metric, key in (("heart_rate", "heart_rate"), ("spo2", "spo2"), ("stress", "stress"),
                                ("resting_hr", "resting_heart_rate"), ("hrv", "hrv"),
                                ("respiratory_rate", "respiratory_rate"), ("weight", "weight"),
                                ("battery", "watch_battery")):
                row = q("SELECT time, value FROM sample WHERE metric = ? ORDER BY ts DESC LIMIT 1", (metric,)).fetchone()
                if row:
                    s[key] = row["value"]
                    attrs[key] = {"measured_at": row["time"]}
            if "resting_heart_rate" not in s:
                row = q("SELECT date, resting_hr FROM daily WHERE resting_hr IS NOT NULL ORDER BY date DESC LIMIT 1").fetchone()
                if row:
                    s["resting_heart_rate"] = row["resting_hr"]
                    attrs["resting_heart_rate"] = {"date": row["date"]}

            w = q("SELECT * FROM workout ORDER BY start_ts DESC LIMIT 1").fetchone()
            if w:
                s["last_workout"] = w["type"] or "workout"
                attrs["last_workout"] = {k: w[k] for k in w.keys() if w[k] is not None}
                for key, col in (("last_workout_distance", "distance_km"), ("last_workout_duration", "duration_min"),
                                 ("last_workout_avg_hr", "avg_hr")):
                    if w[col] is not None:
                        s[key] = w[col]
                        attrs[key] = {"start": w["start"], "type": w["type"]}

            up = q("SELECT * FROM upload ORDER BY id DESC LIMIT 1").fetchone()
            if up:
                s["last_upload"] = datetime.fromtimestamp(up["ts"], timezone.utc).isoformat()
                attrs["last_upload"] = {"file": up["name"], "kind": up["kind"], "summary": up["summary"]}
        return {"state": {k: v for k, v in s.items() if v is not None}, "attributes": attrs}

    def sleep_grouped(self, days: int | None = None):
        """Stored sleep sessions -> (nights, naps), see cmfexport.sleep."""
        sql, args = "SELECT * FROM sleep", ()
        if days is not None:  # window before the newest stored session (works after a sync pause, too)
            newest = self.con.execute("SELECT MAX(end_ts) FROM sleep").fetchone()[0] or 0
            sql, args = sql + " WHERE end_ts >= ?", (newest - days * 86400,)
        rows = self.con.execute(sql + " ORDER BY start_ts", args).fetchall()
        sessions = []
        for r in rows:
            deep, light, rem = (r["deep_min"] or 0), (r["light_min"] or 0), (r["rem_min"] or 0)
            sessions.append(sleeplib.Session(
                datetime.fromtimestamp(r["start_ts"], timezone.utc), datetime.fromtimestamp(r["end_ts"], timezone.utc),
                deep, light, rem, r["awake_min"] or 0, has_stages=(deep + light + rem) > 0, source=r["source"]))
        return sleeplib.group(sessions)

    def history(self, metric: str, since_ts: int) -> list[dict]:
        with self.lock:
            if metric == "nights":
                nights, naps = self.sleep_grouped()
                cutoff = datetime.fromtimestamp(since_ts, timezone.utc)
                return [{"night_of": (n.start.astimezone().date() if n.nap else sleeplib.day_of(n)).isoformat(),
                         "type": "nap" if n.nap else "night", "start": n.start.isoformat(), "end": n.end.isoformat(),
                         "asleep_min": round(n.asleep, 1), "deep_min": n.deep, "light_min": n.light, "rem_min": n.rem,
                         "awake_min": round(n.awake, 1), "segments": n.segments}
                        for n in sorted(nights + naps, key=lambda x: x.start) if n.end >= cutoff]
            if metric == "sleep":
                rows = self.con.execute("SELECT * FROM sleep WHERE end_ts >= ? ORDER BY start_ts", (since_ts,))
            elif metric == "daily":
                since = datetime.fromtimestamp(since_ts, timezone.utc).date().isoformat()
                rows = self.con.execute("SELECT * FROM daily WHERE date >= ? ORDER BY date", (since,))
            else:
                rows = self.con.execute("SELECT time, value FROM sample WHERE metric = ? AND ts >= ? ORDER BY ts",
                                        (metric, since_ts))
            return [dict(r) for r in rows]

    def uploads(self, limit: int = 20) -> list[dict]:
        with self.lock:
            return [dict(r) for r in self.con.execute("SELECT * FROM upload ORDER BY id DESC LIMIT ?", (limit,))]
