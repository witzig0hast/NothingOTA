import csv
import json
import sqlite3
import struct
import zipfile

from cmfexport import gadgetbridge, healthconnect
from cmfexport.__main__ import main

T0 = 1_762_900_000  # 2025-11-11 ~22:26 UTC


def make_gb(path):
    c = sqlite3.connect(path)
    c.executescript("""
    CREATE TABLE "DEVICE" ("_id" INTEGER PRIMARY KEY, "NAME" TEXT, "MANUFACTURER" TEXT, "IDENTIFIER" TEXT, "TYPE" INTEGER, "MODEL" TEXT);
    CREATE TABLE "CMF_ACTIVITY_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL,
      "RAW_INTENSITY" INTEGER NOT NULL, "STEPS" INTEGER NOT NULL, "RAW_KIND" INTEGER NOT NULL, "HEART_RATE" INTEGER NOT NULL,
      "DISTANCE" INTEGER, "CALORIES" INTEGER, PRIMARY KEY ("TIMESTAMP","DEVICE_ID"));
    CREATE TABLE "CMF_HEART_RATE_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "HEART_RATE" INTEGER NOT NULL);
    CREATE TABLE "CMF_SPO2_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "SPO2" INTEGER NOT NULL);
    CREATE TABLE "CMF_STRESS_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "STRESS" INTEGER NOT NULL);
    CREATE TABLE "CMF_SLEEP_SESSION_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "WAKEUP_TIME" INTEGER, "METADATA" BLOB);
    CREATE TABLE "CMF_SLEEP_STAGE_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "DURATION" INTEGER NOT NULL, "STAGE" INTEGER NOT NULL);
    CREATE TABLE "CMF_WORKOUT_GPS_SAMPLE" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "LATITUDE" INTEGER, "LONGITUDE" INTEGER);
    CREATE TABLE "BASE_ACTIVITY_SUMMARY" ("_id" INTEGER PRIMARY KEY, "NAME" TEXT, "START_TIME" INTEGER NOT NULL, "END_TIME" INTEGER NOT NULL,
      "ACTIVITY_KIND" INTEGER NOT NULL, "BASE_LONGITUDE" INTEGER, "BASE_LATITUDE" INTEGER, "BASE_ALTITUDE" INTEGER, "GPX_TRACK" TEXT,
      "RAW_DETAILS_PATH" TEXT, "DEVICE_ID" INTEGER NOT NULL, "USER_ID" INTEGER NOT NULL, "SUMMARY_DATA" TEXT, "RAW_SUMMARY_DATA" BLOB);
    """)
    c.execute('CREATE TABLE "BATTERY_LEVEL" ("TIMESTAMP" INTEGER NOT NULL, "DEVICE_ID" INTEGER NOT NULL, "LEVEL" INTEGER NOT NULL, "BATTERY_INDEX" INTEGER NOT NULL)')
    c.execute("INSERT INTO BATTERY_LEVEL VALUES (?,1,81,0),(?,1,77,0),(?,1,5,1)", (T0 - 3600, T0, T0))
    c.execute("INSERT INTO DEVICE VALUES (1,'CMF Watch 3 Pro','Nothing','AA:BB:CC:DD:EE:FF',0,'')")
    c.execute("INSERT INTO DEVICE VALUES (2,'Other Band','X','11:22',0,'')")
    for i in range(10):
        c.execute("INSERT INTO CMF_ACTIVITY_SAMPLE VALUES (?,1,1,0,?,0,?,?,?)", (T0 - 7200 + i * 60, 100, 70, 80, 5))
    c.execute("INSERT INTO CMF_ACTIVITY_SAMPLE VALUES (?,2,1,0,9999,0,0,0,0)", (T0,))
    c.execute("INSERT INTO CMF_HEART_RATE_SAMPLE VALUES (?,1,1,58)", (T0 * 1000 + 600_000,))
    c.execute("INSERT INTO CMF_SPO2_SAMPLE VALUES (?,1,1,97)", (T0 * 1000 + 600_000,))
    c.execute("INSERT INTO CMF_STRESS_SAMPLE VALUES (?,1,1,23)", (T0 * 1000 + 600_000,))
    # night: 30min light, 60 deep, 20 rem, 10 awake
    c.execute("INSERT INTO CMF_SLEEP_SESSION_SAMPLE VALUES (?,1,1,?,?)", (T0 * 1000, (T0 + 7200) * 1000, b"\x01" * 10))
    t = T0
    for dur, st in ((1800, 2), (3600, 1), (1200, 3), (600, 4)):
        c.execute("INSERT INTO CMF_SLEEP_STAGE_SAMPLE VALUES (?,1,1,?,?)", (t * 1000, dur, st))
        t += dur
    w0 = T0 - 3600
    raw = struct.pack("<IhB", w0, 1800, 0x02) + b"\0" * 19 + struct.pack("<I", w0 + 1800) + b"\x01\x00" + b"\0" * 22
    c.execute("INSERT INTO BASE_ACTIVITY_SUMMARY VALUES (1,NULL,?,?,0,NULL,NULL,NULL,NULL,NULL,1,1,NULL,?)",
              (w0 * 1000, (w0 + 1800) * 1000, raw))
    for i in range(3):
        c.execute("INSERT INTO CMF_WORKOUT_GPS_SAMPLE VALUES (?,1,1,?,?)", ((w0 + i * 60) * 1000, 525200000 + i, 134050000 + i))
    c.commit()
    c.close()


def make_hc(path):
    c = sqlite3.connect(path)
    c.executescript("""
    CREATE TABLE application_info_table (row_id INTEGER PRIMARY KEY, package_name TEXT, app_name TEXT);
    CREATE TABLE sleep_session_record_table (row_id INTEGER PRIMARY KEY, uuid BLOB, start_time INTEGER, start_zone_offset INTEGER,
      end_time INTEGER, end_zone_offset INTEGER, app_info_id INTEGER, title TEXT, notes TEXT);
    CREATE TABLE sleep_stages_table (parent_key INTEGER, stage_start_time INTEGER, stage_end_time INTEGER, stage_type INTEGER);
    CREATE TABLE heart_rate_record_table (row_id INTEGER PRIMARY KEY, start_time INTEGER, end_time INTEGER, app_info_id INTEGER);
    CREATE TABLE heart_rate_record_series_table (parent_key INTEGER, beats_per_minute INTEGER, epoch_millis INTEGER);
    CREATE TABLE steps_record_table (row_id INTEGER PRIMARY KEY, start_time INTEGER, end_time INTEGER, count INTEGER, app_info_id INTEGER);
    """)
    c.execute("INSERT INTO application_info_table VALUES (1,'com.nothing.cmf.watch','CMF Watch'),(2,'com.other','Other')")
    ms = T0 * 1000
    c.execute("INSERT INTO sleep_session_record_table VALUES (1,x'00',?,3600,?,3600,1,NULL,NULL)", (ms, ms + 7_200_000))
    c.execute("INSERT INTO sleep_session_record_table VALUES (2,x'01',?,3600,?,3600,2,NULL,NULL)", (ms, ms + 1_000_000))
    for a, b, s in ((0, 30, 4), (30, 90, 5), (90, 110, 6), (110, 120, 1)):
        c.execute("INSERT INTO sleep_stages_table VALUES (1,?,?,?)", (ms + a * 60000, ms + b * 60000, s))
    c.execute("INSERT INTO sleep_stages_table VALUES (2,?,?,5)", (ms, ms + 1_000_000))
    c.execute("INSERT INTO heart_rate_record_table VALUES (1,?,?,1)", (ms, ms + 60000))
    c.execute("INSERT INTO heart_rate_record_series_table VALUES (1,61,?),(1,63,?)", (ms, ms + 30000))
    c.execute("INSERT INTO steps_record_table VALUES (1,?,?,500,1),(2,?,?,7,2)", (ms, ms + 60000, ms, ms + 60000))
    c.commit()
    c.close()


def read_csv(p):
    with open(p, encoding="utf-8") as fh:
        return list(csv.DictReader(fh))


def test_gadgetbridge(tmp_path):
    db = tmp_path / "Gadgetbridge.db"
    make_gb(db)
    z = tmp_path / "gb.zip"
    with zipfile.ZipFile(z, "w") as zf:
        zf.write(db, "database/Gadgetbridge")
    out = tmp_path / "out"
    main(["gadgetbridge", str(z), "-o", str(out), "--device", "CMF", "--raw"])

    s = read_csv(out / "sleep_sessions.csv")[0]
    assert (float(s["deep_min"]), float(s["light_min"]), float(s["rem_min"]), float(s["awake_min"])) == (60, 30, 20, 10)
    assert float(s["asleep_min"]) == 110 and float(s["time_in_bed_min"]) == 120
    assert len(read_csv(out / "sleep_stages.csv")) == 4
    act = read_csv(out / "activity.csv")
    assert len(act) == 10 and all(a["device_id"] == "1" for a in act)  # device filter works
    assert read_csv(out / "heart_rate.csv")[0]["heart_rate"] == "58"
    assert read_csv(out / "spo2.csv")[0]["spo2"] == "97"
    assert read_csv(out / "stress.csv")[0]["stress"] == "23"
    w = read_csv(out / "workouts.csv")[0]
    assert w["workout_type"] == "OUTDOOR_RUNNING" and w["active_seconds"] == "1800" and w["has_gps"] == "True"
    gpx = list((out / "gpx").glob("*.gpx"))
    assert len(gpx) == 1 and gpx[0].read_text().count("<trkpt") == 3
    daily = read_csv(out / "daily_summary.csv")
    assert sum(int(d["steps"]) for d in daily) == 1000
    assert (out / "raw" / "CMF_SLEEP_STAGE_SAMPLE.csv").exists()
    assert [b["level"] for b in read_csv(out / "battery.csv")] == ["81", "77"]
    assert json.loads((out / "all_data.json").read_text())["sleep_sessions"]
    assert "Schlaf" in (out / "report.html").read_text()


def test_healthconnect(tmp_path):
    db = tmp_path / "health_connect_export.db"
    make_hc(db)
    z = tmp_path / "Health Connect.zip"
    with zipfile.ZipFile(z, "w") as zf:
        zf.write(db, db.name)
    out = tmp_path / "out"
    res = healthconnect.export(z, out, app_filter="com.nothing.cmf")
    assert len(res["sessions"]) == 1
    s = res["sessions"][0]
    assert (s["light_min"], s["deep_min"], s["rem_min"], s["awake_min"], s["asleep_min"]) == (30, 60, 20, 10, 110)
    assert len(read_csv(out / "sleep_stages.csv")) == 4
    steps = read_csv(out / "records" / "steps.csv")
    assert len(steps) == 1 and steps[0]["count"] == "500" and steps[0]["app_package"] == "com.nothing.cmf.watch"
    hr = read_csv(out / "series" / "heart_rate_record_series.csv")
    assert [r["beats_per_minute"] for r in hr] == ["61", "63"] and hr[0]["epoch_millis_iso"]
    main(["healthconnect", str(z), "-o", str(tmp_path / "out2")])
    assert len(read_csv(tmp_path / "out2" / "sleep_sessions.csv")) == 2
