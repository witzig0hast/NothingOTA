"""Exporter for a Gadgetbridge database that contains CMF Watch data.

Gadgetbridge (open source, F-Droid) talks to the CMF Watch Pro / Pro 2 / Pro 3
directly over Bluetooth and stores *everything* the watch hands out in its
SQLite database. Settings -> Data management -> "Export database" produces
``Gadgetbridge.db`` (or a .zip with "Export as zip"), which this module reads.

Table layout (greenDAO naming, see Gadgetbridge GBDaoGenerator.java):

  CMF_ACTIVITY_SAMPLE       TIMESTAMP[s], STEPS, DISTANCE[m], CALORIES, HEART_RATE, RAW_KIND, RAW_INTENSITY
  CMF_HEART_RATE_SAMPLE     TIMESTAMP[ms], HEART_RATE[bpm]
  CMF_SPO2_SAMPLE           TIMESTAMP[ms], SPO2[%]
  CMF_STRESS_SAMPLE         TIMESTAMP[ms], STRESS[0-100]
  CMF_SLEEP_SESSION_SAMPLE  TIMESTAMP[ms] (fell asleep), WAKEUP_TIME[ms], METADATA[10 bytes]
  CMF_SLEEP_STAGE_SAMPLE    TIMESTAMP[ms], DURATION[s], STAGE (1=deep 2=light 3=REM, other=awake)
  CMF_WORKOUT_GPS_SAMPLE    TIMESTAMP[ms], LATITUDE/LONGITUDE [deg * 1e7]
  BASE_ACTIVITY_SUMMARY     workouts; RAW_SUMMARY_DATA holds the watch's binary record
"""
from __future__ import annotations

import struct
from collections import defaultdict
from pathlib import Path
from xml.sax.saxutils import escape

from .common import columns, fmt_minutes, iso, open_db, tables, write_csv, write_json
from .workout_types import WORKOUT_TYPES

SLEEP_STAGES = {1: "deep", 2: "light", 3: "rem"}  # everything else -> awake

SIMPLE_TABLES = {
    # table: (output name, value columns)
    "CMF_HEART_RATE_SAMPLE": ("heart_rate", ["HEART_RATE"]),
    "CMF_SPO2_SAMPLE": ("spo2", ["SPO2"]),
    "CMF_STRESS_SAMPLE": ("stress", ["STRESS"]),
}


def _devices(con) -> dict[int, dict]:
    if "DEVICE" not in tables(con):
        return {}
    out = {}
    for r in con.execute("SELECT * FROM DEVICE"):
        d = dict(r)
        out[d.get("_id")] = {k.lower(): v for k, v in d.items() if k in
                             ("_id", "NAME", "MANUFACTURER", "IDENTIFIER", "MODEL", "TYPE", "TYPE_NAME", "ALIAS")}
    return out


def _cmf_device_ids(con, devices: dict[int, dict], device_filter: str | None) -> set[int] | None:
    """Device ids to include. None = no filtering possible/needed."""
    if device_filter:
        f = device_filter.lower()
        ids = {i for i, d in devices.items()
               if any(f in str(v).lower() for v in d.values() if v is not None)}
        if not ids:
            raise SystemExit(f"no device matches {device_filter!r}; known: {list(devices.values())}")
        return ids
    return None


def _where(ids: set[int] | None) -> str:
    if ids is None:
        return ""
    return " WHERE DEVICE_ID IN (%s)" % ",".join(str(int(i)) for i in ids)


def _activity(con, ids):
    if "CMF_ACTIVITY_SAMPLE" not in tables(con):
        return []
    cols = columns(con, "CMF_ACTIVITY_SAMPLE")
    want = [c for c in ("STEPS", "DISTANCE", "CALORIES", "HEART_RATE", "RAW_KIND", "RAW_INTENSITY") if c in cols]
    rows = []
    for r in con.execute(f"SELECT * FROM CMF_ACTIVITY_SAMPLE{_where(ids)} ORDER BY TIMESTAMP"):
        row = {"time": iso(r["TIMESTAMP"]), "timestamp": r["TIMESTAMP"], "device_id": r["DEVICE_ID"]}
        for c in want:
            row[c.lower()] = r[c]
        rows.append(row)
    return rows


def _simple(con, table, values, ids):
    if table not in tables(con):
        return []
    rows = []
    for r in con.execute(f"SELECT * FROM {table}{_where(ids)} ORDER BY TIMESTAMP"):
        row = {"time": iso(r["TIMESTAMP"], "ms"), "timestamp_ms": r["TIMESTAMP"], "device_id": r["DEVICE_ID"]}
        for v in values:
            row[v.lower()] = r[v]
        rows.append(row)
    return rows


def _sleep(con, ids):
    t = tables(con)
    sessions, stages = [], []
    if "CMF_SLEEP_STAGE_SAMPLE" in t:
        for r in con.execute(f"SELECT * FROM CMF_SLEEP_STAGE_SAMPLE{_where(ids)} ORDER BY TIMESTAMP"):
            stages.append({
                "start": iso(r["TIMESTAMP"], "ms"),
                "end": iso(r["TIMESTAMP"] + r["DURATION"] * 1000, "ms"),
                "timestamp_ms": r["TIMESTAMP"],
                "duration_s": r["DURATION"],
                "stage_raw": r["STAGE"],
                "stage": SLEEP_STAGES.get(r["STAGE"], "awake"),
                "device_id": r["DEVICE_ID"],
            })
    if "CMF_SLEEP_SESSION_SAMPLE" in t:
        for r in con.execute(f"SELECT * FROM CMF_SLEEP_SESSION_SAMPLE{_where(ids)} ORDER BY TIMESTAMP"):
            start, end = r["TIMESTAMP"], r["WAKEUP_TIME"] or r["TIMESTAMP"]
            mins = defaultdict(float)
            for s in stages:
                if s["device_id"] == r["DEVICE_ID"] and start <= s["timestamp_ms"] < end:
                    mins[s["stage"]] += s["duration_s"] / 60
            asleep = mins["deep"] + mins["light"] + mins["rem"]
            in_bed = (end - start) / 60000
            meta = r["METADATA"]
            sessions.append({
                "night_of": iso(start, "ms")[:10],
                "fell_asleep": iso(start, "ms"),
                "woke_up": iso(end, "ms"),
                "time_in_bed_min": round(in_bed, 1),
                "asleep_min": round(asleep, 1),
                "deep_min": round(mins["deep"], 1),
                "light_min": round(mins["light"], 1),
                "rem_min": round(mins["rem"], 1),
                "awake_min": round(mins["awake"], 1),
                "efficiency_pct": round(100 * asleep / in_bed, 1) if in_bed > 0 else None,
                "metadata_hex": meta.hex() if isinstance(meta, (bytes, bytearray)) else meta,
                "device_id": r["DEVICE_ID"],
            })
    return sessions, stages


def _decode_workout(raw: bytes | None) -> dict:
    """Decode the CMF binary workout summary (32 or 54 bytes, little endian)."""
    if not raw or len(raw) < 30:
        return {}
    start, duration, wtype = struct.unpack_from("<IhB", raw, 0)
    end = struct.unpack_from("<I", raw, 26)[0]
    return {
        "workout_type_code": wtype,
        "workout_type": WORKOUT_TYPES.get(wtype, f"UNKNOWN_0x{wtype:02X}"),
        "active_seconds": duration,
        "has_gps": len(raw) > 30 and raw[30] == 1,
        "raw_start": iso(start),
        "raw_end": iso(end),
    }


def _workouts(con, ids, gps_rows):
    if "BASE_ACTIVITY_SUMMARY" not in tables(con):
        return []
    out = []
    for r in con.execute(f"SELECT * FROM BASE_ACTIVITY_SUMMARY{_where(ids)} ORDER BY START_TIME"):
        d = dict(r)
        w = {
            "id": d.get("_id"),
            "name": d.get("NAME"),
            "start": iso(d["START_TIME"], "ms"),
            "end": iso(d["END_TIME"], "ms"),
            "duration_min": round((d["END_TIME"] - d["START_TIME"]) / 60000, 1),
            "gb_activity_kind": d.get("ACTIVITY_KIND"),
            "device_id": d.get("DEVICE_ID"),
        }
        raw = d.get("RAW_SUMMARY_DATA")
        w.update(_decode_workout(raw))
        if raw:
            w["raw_summary_hex"] = bytes(raw).hex()
        if d.get("SUMMARY_DATA"):
            w["summary_data"] = d["SUMMARY_DATA"]
        if d.get("GPX_TRACK"):
            w["gpx_track_on_phone"] = d["GPX_TRACK"]
        w["gps_points"] = [g for g in gps_rows
                           if g["device_id"] == w["device_id"]
                           and d["START_TIME"] <= g["timestamp_ms"] <= d["END_TIME"]]
        out.append(w)
    return out


def _battery(con, ids):
    """Watch battery history (Gadgetbridge BATTERY_LEVEL: TIMESTAMP[s], LEVEL[%], BATTERY_INDEX)."""
    if "BATTERY_LEVEL" not in tables(con):
        return []
    has_index = "BATTERY_INDEX" in columns(con, "BATTERY_LEVEL")
    rows = []
    for r in con.execute(f"SELECT * FROM BATTERY_LEVEL{_where(ids)} ORDER BY TIMESTAMP"):
        if has_index and r["BATTERY_INDEX"]:
            continue  # only the main battery
        rows.append({"time": iso(r["TIMESTAMP"]), "level": r["LEVEL"], "device_id": r["DEVICE_ID"]})
    return rows


def _gps(con, ids):
    if "CMF_WORKOUT_GPS_SAMPLE" not in tables(con):
        return []
    return [{
        "time": iso(r["TIMESTAMP"], "ms"),
        "timestamp_ms": r["TIMESTAMP"],
        "lat": r["LATITUDE"] / 1e7,
        "lon": r["LONGITUDE"] / 1e7,
        "device_id": r["DEVICE_ID"],
    } for r in con.execute(f"SELECT * FROM CMF_WORKOUT_GPS_SAMPLE{_where(ids)} ORDER BY TIMESTAMP")]


def _write_gpx(path: Path, w: dict) -> None:
    pts = "\n".join(
        f'      <trkpt lat="{p["lat"]:.7f}" lon="{p["lon"]:.7f}"><time>{p["time"]}</time></trkpt>'
        for p in w["gps_points"])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="cmfexport" xmlns="http://www.topografix.com/GPX/1/1">\n'
        f'  <trk><name>{escape(str(w.get("workout_type") or w.get("name") or "workout"))} {w["start"]}</name>\n'
        f'    <trkseg>\n{pts}\n    </trkseg>\n  </trk>\n</gpx>\n', encoding="utf-8")


def _daily(activity, hr, spo2, stress, sessions):
    days: dict[str, dict] = defaultdict(lambda: defaultdict(list))
    for a in activity:
        d = days[a["time"][:10]]
        d["steps"].append(a.get("steps") or 0)
        d["distance"].append(a.get("distance") or 0)
        d["calories"].append(a.get("calories") or 0)
        if a.get("heart_rate") and 0 < a["heart_rate"] < 255:
            d["hr"].append(a["heart_rate"])
    for name, rows, key in (("hr", hr, "heart_rate"), ("spo2", spo2, "spo2"), ("stress", stress, "stress")):
        for r in rows:
            v = r[key]
            if v and 0 < v < 255:
                days[r["time"][:10]][name].append(v)
    sleep_by_day = {}
    for s in sessions:  # attribute a night to the day you woke up
        sleep_by_day.setdefault(s["woke_up"][:10], []).append(s)

    out = []
    for day in sorted(set(days) | set(sleep_by_day)):
        d = days[day]
        avg = lambda xs: round(sum(xs) / len(xs), 1) if xs else None
        sl = sleep_by_day.get(day, [])
        out.append({
            "date": day,
            "steps": sum(d["steps"]),
            "distance_m": sum(d["distance"]),
            "calories": sum(d["calories"]),
            "hr_avg": avg(d["hr"]), "hr_min": min(d["hr"], default=None), "hr_max": max(d["hr"], default=None),
            "spo2_avg": avg(d["spo2"]), "spo2_min": min(d["spo2"], default=None),
            "stress_avg": avg(d["stress"]), "stress_max": max(d["stress"], default=None),
            "sleep_min": round(sum(s["asleep_min"] for s in sl), 1) if sl else None,
            "deep_min": round(sum(s["deep_min"] for s in sl), 1) if sl else None,
            "light_min": round(sum(s["light_min"] for s in sl), 1) if sl else None,
            "rem_min": round(sum(s["rem_min"] for s in sl), 1) if sl else None,
            "awake_min": round(sum(s["awake_min"] for s in sl), 1) if sl else None,
        })
    return out


def export(db_path, out_dir, device_filter: str | None = None, dump_raw: bool = False) -> dict:
    con = open_db(db_path, ["Gadgetbridge.db", "Gadgetbridge"])
    out = Path(out_dir)
    devices = _devices(con)
    ids = _cmf_device_ids(con, devices, device_filter)

    activity = _activity(con, ids)
    simple = {name: _simple(con, t, vals, ids) for t, (name, vals) in SIMPLE_TABLES.items()}
    sessions, stages = _sleep(con, ids)
    gps = _gps(con, ids)
    workouts = _workouts(con, ids, gps)
    daily = _daily(activity, simple["heart_rate"], simple["spo2"], simple["stress"], sessions)

    counts = {
        "activity_minutes": write_csv(out / "activity.csv", activity),
        "heart_rate": write_csv(out / "heart_rate.csv", simple["heart_rate"]),
        "spo2": write_csv(out / "spo2.csv", simple["spo2"]),
        "stress": write_csv(out / "stress.csv", simple["stress"]),
        "sleep_sessions": write_csv(out / "sleep_sessions.csv", sessions),
        "sleep_stages": write_csv(out / "sleep_stages.csv", stages),
        "gps_points": write_csv(out / "workout_gps.csv", gps),
        "battery": write_csv(out / "battery.csv", _battery(con, ids)),
        "workouts": write_csv(out / "workouts.csv",
                              [{k: v for k, v in w.items() if k != "gps_points"} for w in workouts]),
        "days": write_csv(out / "daily_summary.csv", daily),
    }
    for w in workouts:
        if w["gps_points"]:
            _write_gpx(out / "gpx" / f"workout_{w['id']}_{w['start'][:19].replace(':', '-')}.gpx", w)

    if dump_raw:  # every table in the DB, untouched, for anything not covered above
        for t in tables(con):
            if t.startswith("CMF_") or t in ("DEVICE", "BASE_ACTIVITY_SUMMARY", "BATTERY_LEVEL", "USER",
                                             "DEVICE_ATTRIBUTES", "USER_ATTRIBUTES"):
                rows = [{k: (v.hex() if isinstance(v, bytes) else v) for k, v in dict(r).items()}
                        for r in con.execute(f'SELECT * FROM "{t}"')]
                write_csv(out / "raw" / f"{t}.csv", rows)

    write_json(out / "all_data.json", {
        "source": "gadgetbridge",
        "devices": list(devices.values()),
        "daily_summary": daily,
        "sleep_sessions": sessions,
        "sleep_stages": stages,
        "workouts": workouts,
        "heart_rate": simple["heart_rate"],
        "spo2": simple["spo2"],
        "stress": simple["stress"],
        "activity": activity,
    })
    return {"devices": list(devices.values()), "counts": counts, "sessions": sessions, "daily": daily}


def print_summary(res: dict) -> None:
    print("Geräte in der DB:")
    for d in res["devices"]:
        print("  -", d)
    print("\nExportierte Datensätze:")
    for k, v in res["counts"].items():
        print(f"  {k:16s} {v}")
    if res["sessions"]:
        print("\nLetzte Nächte:")
        for s in res["sessions"][-7:]:
            print(f"  {s['fell_asleep'][:16]} -> {s['woke_up'][11:16]}  Schlaf {fmt_minutes(s['asleep_min'])}"
                  f"  (Tief {fmt_minutes(s['deep_min'])}, Leicht {fmt_minutes(s['light_min'])},"
                  f" REM {fmt_minutes(s['rem_min'])}, Wach {fmt_minutes(s['awake_min'])})")
