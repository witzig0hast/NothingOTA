"""Exporter for an Android Health Connect export (``Health Connect.zip``).

Path: official CMF Watch app -> Settings -> Health Connect sync ON ->
Android Settings -> Security & privacy -> Privacy -> Health Connect ->
"Backup and restore"/"Export data" -> "Export now". The zip contains one
SQLite database with one ``*_record_table`` per data type.

The schema is not public API and varies between Android versions, so this
module discovers columns at runtime instead of hard-coding them:

* every ``*_record_table`` is dumped to CSV (millisecond epochs -> ISO time)
* rows are tagged with the writing app (``application_info_table``), so
  ``--app com.nothing.cmf.watch`` keeps only data written by the CMF app
* series tables (heart rate etc.) and sleep stages are joined to their
  parent record through ``parent_key``
* sleep nights are summarised (deep / light / REM / awake minutes)
"""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from .common import columns, fmt_minutes, iso, open_db, tables, write_csv, write_json

# android.health.connect.datatypes.SleepSessionRecord.StageType
HC_STAGES = {0: "unknown", 1: "awake", 2: "sleeping", 3: "out_of_bed",
             4: "light", 5: "deep", 6: "rem", 7: "awake_in_bed"}
ASLEEP = {"sleeping", "light", "deep", "rem"}

TIME_HINTS = ("time", "epoch_millis", "instant")
CMF_PACKAGES = ("com.nothing.cmf.watch", "com.nothing.smartcenter", "com.nothing.cmf")


def _apps(con) -> dict[int, str]:
    t = tables(con)
    name = next((x for x in t if x.startswith("application_info")), None)
    if not name:
        return {}
    cols = columns(con, name)
    key = "row_id" if "row_id" in cols else cols[0]
    pkg = "package_name" if "package_name" in cols else next(c for c in cols if "package" in c)
    return {r[key]: r[pkg] for r in con.execute(f'SELECT * FROM "{name}"')}


def _is_time_col(col: str) -> bool:
    c = col.lower()
    return any(h in c for h in TIME_HINTS) and "zone" not in c and "offset" not in c


def _convert(row: dict, apps: dict[int, str]) -> dict:
    out = {}
    for k, v in row.items():
        if isinstance(v, bytes):
            v = v.hex()
        out[k] = v
        if _is_time_col(k) and isinstance(v, int) and v > 10**11:  # ms epoch
            out[k + "_iso"] = iso(v, "ms")
    if "app_info_id" in row:
        out["app_package"] = apps.get(row["app_info_id"])
    return out


def _sleep(con, apps, app_filter):
    t = tables(con)
    sess_t = next((x for x in t if x.startswith("sleep_session")), None)
    stage_t = next((x for x in t if x.startswith("sleep_stage")), None)
    if not sess_t:
        return [], []
    scols = columns(con, sess_t)
    key = "row_id" if "row_id" in scols else scols[0]

    stages_by_parent = defaultdict(list)
    stage_rows = []
    if stage_t:
        cols = columns(con, stage_t)
        start_c = next((c for c in cols if "start" in c and "time" in c), None)
        end_c = next((c for c in cols if "end" in c and "time" in c), None)
        type_c = next((c for c in cols if "type" in c or c == "stage"), None)
        parent_c = next((c for c in cols if "parent" in c), None)
        if start_c and end_c and type_c and parent_c:
            for r in con.execute(f'SELECT * FROM "{stage_t}" ORDER BY "{start_c}"'):
                st = {"parent": r[parent_c], "start_ms": r[start_c], "end_ms": r[end_c],
                      "start": iso(r[start_c], "ms"), "end": iso(r[end_c], "ms"),
                      "stage_raw": r[type_c], "stage": HC_STAGES.get(r[type_c], "unknown"),
                      "duration_min": round((r[end_c] - r[start_c]) / 60000, 1)}
                stages_by_parent[r[parent_c]].append(st)
                stage_rows.append(st)

    sessions, kept = [], set()
    for r in con.execute(f'SELECT * FROM "{sess_t}" ORDER BY start_time'):
        pkg = apps.get(r["app_info_id"]) if "app_info_id" in scols else None
        if app_filter and (pkg is None or app_filter not in pkg):
            continue
        kept.add(r[key])
        mins = defaultdict(float)
        for st in stages_by_parent.get(r[key], []):
            mins[st["stage"]] += st["duration_min"]
        in_bed = (r["end_time"] - r["start_time"]) / 60000
        asleep = sum(v for k, v in mins.items() if k in ASLEEP)
        sessions.append({
            "night_of": iso(r["start_time"], "ms")[:10],
            "start": iso(r["start_time"], "ms"),
            "end": iso(r["end_time"], "ms"),
            "time_in_bed_min": round(in_bed, 1),
            "asleep_min": round(asleep, 1) if mins else None,
            "deep_min": round(mins["deep"], 1),
            "light_min": round(mins["light"] + mins["sleeping"], 1),
            "rem_min": round(mins["rem"], 1),
            "awake_min": round(mins["awake"] + mins["awake_in_bed"] + mins["out_of_bed"], 1),
            "title": r["title"] if "title" in scols else None,
            "notes": r["notes"] if "notes" in scols else None,
            "app_package": pkg,
        })
    return sessions, [st for st in stage_rows if st["parent"] in kept]


def export(db_path, out_dir, app_filter: str | None = None) -> dict:
    con = open_db(db_path, ["health_connect_export.db", "healthconnect.db"])
    out = Path(out_dir)
    apps = _apps(con)
    all_tables = tables(con)

    # 1) parent records (every data type)
    parent_rows: dict[str, dict] = {}   # table -> {row_id: converted row}
    counts = {}
    for t in all_tables:
        if not t.endswith("_record_table"):
            continue
        cols = columns(con, t)
        key = "row_id" if "row_id" in cols else None
        rows, by_id = [], {}
        for r in con.execute(f'SELECT * FROM "{t}"'):
            d = _convert(dict(r), apps)
            if app_filter and "app_info_id" in cols and (not d.get("app_package") or app_filter not in d["app_package"]):
                continue
            rows.append(d)
            if key:
                by_id[r[key]] = d
        parent_rows[t] = by_id
        if rows:
            counts[t.removesuffix("_record_table")] = write_csv(out / "records" / f"{t.removesuffix('_record_table')}.csv", rows)

    # 2) series / child tables (heart rate samples, speed, power, ...)
    for t in all_tables:
        if t.endswith("_record_table") or t.startswith("sleep_stage"):
            continue
        cols = columns(con, t)
        parent_c = next((c for c in cols if c == "parent_key"), None)
        if not parent_c:
            continue
        matches = [p for p in parent_rows if t.startswith(p.removesuffix("_table").removesuffix("_record") + "_")]
        parent_t = max(matches, key=len, default=None)
        rows = []
        for r in con.execute(f'SELECT * FROM "{t}"'):
            d = _convert(dict(r), apps)
            if parent_t:
                parent = parent_rows[parent_t].get(r[parent_c])
                if parent is None and app_filter:
                    continue
                if parent:
                    d["app_package"] = parent.get("app_package")
            rows.append(d)
        if rows:
            counts[t.removesuffix("_table")] = write_csv(out / "series" / f"{t.removesuffix('_table')}.csv", rows)

    # 3) sleep
    sessions, stages = _sleep(con, apps, app_filter)
    counts["sleep_sessions"] = write_csv(out / "sleep_sessions.csv", sessions)
    if stages:
        counts["sleep_stages"] = write_csv(out / "sleep_stages.csv", stages)

    write_json(out / "summary.json", {"source": "health_connect", "apps": apps, "counts": counts,
                                       "sleep_sessions": sessions})
    return {"apps": apps, "counts": counts, "sessions": sessions}


def print_summary(res: dict) -> None:
    print("Apps, die in Health Connect geschrieben haben:")
    for pkg in sorted(set(res["apps"].values())):
        mark = "  <- CMF" if any(p in pkg for p in CMF_PACKAGES) else ""
        print(f"  - {pkg}{mark}")
    print("\nExportierte Datensätze:")
    for k, v in sorted(res["counts"].items()):
        print(f"  {k:32s} {v}")
    if res["sessions"]:
        print("\nLetzte Nächte:")
        for s in res["sessions"][-7:]:
            asleep = fmt_minutes(s["asleep_min"]) if s["asleep_min"] is not None else "?"
            print(f"  {s['start'][:16]} -> {s['end'][11:16]}  Schlaf {asleep}  (Tief {fmt_minutes(s['deep_min'])},"
                  f" Leicht {fmt_minutes(s['light_min'])}, REM {fmt_minutes(s['rem_min'])},"
                  f" Wach {fmt_minutes(s['awake_min'])})  [{s['app_package']}]")
