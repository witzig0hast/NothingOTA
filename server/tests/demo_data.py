"""Realistic demo data in the upload layout of the Android apps (for tests and screenshots)."""
from __future__ import annotations

import io
import math
import random
import zipfile
from datetime import datetime, timedelta


def _iso(dt: datetime) -> str:
    return dt.astimezone().isoformat(timespec="seconds")


def demo_zip(days: int = 30, seed: int = 7, spo2_dips: bool = True) -> bytes:
    rnd = random.Random(seed)
    now = datetime.now().astimezone().replace(second=0, microsecond=0)
    today = now.date()
    daily = ["date,steps,distance_m,calories_kcal,hr_avg,hr_min,hr_max,resting_hr,spo2_avg,stress_avg,sleep_min,deep_min,light_min,rem_min,awake_min"]
    sessions = ["fell_asleep,woke_up,time_in_bed_min,asleep_min,deep_min,light_min,rem_min,awake_min,efficiency_pct,source"]
    stages = ["start,end,duration_min,stage"]
    hr = ["time,bpm"]
    spo2 = ["time,spo2_pct"]
    rr = ["time,breaths_per_min"]
    stress = ["time,stress"]
    hrv = ["time,rmssd_ms"]
    rest = ["time,bpm"]
    workouts = ["start,end,duration_min,workout_type,distance_km,avg_hr,max_hr,elevation_gain_m,calories,app"]
    track = ["workout_start,time,lat,lon,alt"]

    for k in range(days, -1, -1):
        d = today - timedelta(days=k)
        sick = k in (1, 2)  # a couple of "bad" days: higher resting HR, oxygen dips
        resting = 54 + rnd.randint(0, 3) + (8 if sick else 0)
        rest.append(f"{_iso(datetime.combine(d, datetime.min.time()).astimezone() + timedelta(hours=7))},{resting}")
        hrv.append(f"{_iso(datetime.combine(d, datetime.min.time()).astimezone() + timedelta(hours=6))},{(48 + rnd.randint(-5, 6)) * (0.7 if sick else 1):.0f}")
        # night ending this morning
        start = datetime.combine(d - timedelta(days=1), datetime.min.time()).astimezone() + timedelta(hours=22, minutes=50 + rnd.randint(-40, 50))
        t = start
        mins = {"deep": 0, "light": 0, "rem": 0, "awake": 0}
        cycle = 0
        while t < start + timedelta(minutes=430 + rnd.randint(-40, 50)):
            for st, dur in (("light", 25), ("deep", 30 if cycle < 3 else 10), ("light", 15), ("rem", 15 + 6 * cycle), ("awake", 3 if rnd.random() < 0.5 else 0)):
                if dur <= 0:
                    continue
                dur += rnd.randint(-4, 4)
                end = t + timedelta(minutes=dur)
                stages.append(f"{_iso(t)},{_iso(end)},{dur},{st}")
                mins[st] += dur
                t = end
            cycle += 1
        end = t
        asleep = mins["deep"] + mins["light"] + mins["rem"]
        in_bed = (end - start).total_seconds() / 60
        sessions.append(f"{_iso(start)},{_iso(end)},{in_bed:.0f},{asleep},{mins['deep']},{mins['light']},{mins['rem']},{mins['awake']},{100 * asleep / in_bed:.1f},com.nothing.cmf.watch")
        # SpO2 every 10 min during the night, respiratory rate every 30 min
        x = start
        while x < end:
            v = 96 + rnd.random() * 2.5
            if spo2_dips and sick and rnd.random() < 0.25:
                v = 87 + rnd.random() * 3
            spo2.append(f"{_iso(x)},{v:.0f}")
            if (x - start).total_seconds() % 1800 == 0:
                rr.append(f"{_iso(x)},{(14.5 + rnd.random() + (4.5 if sick else 0)):.1f}")
            x += timedelta(minutes=10)
        # daytime HR every 10 min + stress every 30 min
        day_start = datetime.combine(d, datetime.min.time()).astimezone() + timedelta(hours=7)
        x = day_start
        hrs = []
        while x < min(day_start + timedelta(hours=15), now):
            if k % 3 == 1 and 18 <= x.hour < 19:  # the evening run has its own heart rate below
                x += timedelta(minutes=10)
                continue
            v = resting + 18 + 10 * math.sin(x.hour / 3) + rnd.randint(-6, 8)
            hrs.append(v)
            hr.append(f"{_iso(x)},{v:.0f}")
            if x.minute % 30 == 0:
                stress.append(f"{_iso(x)},{max(5, min(95, 32 + rnd.randint(-15, 25) + (20 if sick else 0)))}")
            x += timedelta(minutes=10)
        steps = 0 if d > today else (4000 + rnd.randint(0, 9000) if d < today else 5230)
        daily.append(f"{d},{steps},{steps * 0.75:.0f},{1900 + steps / 30:.0f},{(sum(hrs) / len(hrs)) if hrs else '':.1f},"
                     f"{min(hrs, default='')},{max(hrs, default='')},{resting},97,,{asleep},{mins['deep']},{mins['light']},{mins['rem']},{mins['awake']}"
                     if hrs else f"{d},{steps},,,,,,{resting},,,{asleep},{mins['deep']},{mins['light']},{mins['rem']},{mins['awake']}")
        # a run every 3 days with a GPS loop through the Tiergarten
        if k % 3 == 1:
            ws = datetime.combine(d, datetime.min.time()).astimezone() + timedelta(hours=18, minutes=10)
            n = 330
            for i in range(0, n, 3):
                a = 2 * math.pi * i / n
                lat = 52.5145 + 0.0075 * math.sin(a) + 0.0012 * math.sin(3 * a)
                lon = 13.3600 + 0.0140 * math.cos(a)
                track.append(f"{_iso(ws)},{_iso(ws + timedelta(seconds=i * 6))},{lat:.6f},{lon:.6f},{34 + 3 * math.sin(a):.1f}")
                hr.append(f"{_iso(ws + timedelta(seconds=i * 6))},{140 + 15 * math.sin(i / 60) + rnd.randint(-3, 3):.0f}")
            workouts.append(f"{_iso(ws)},{_iso(ws + timedelta(minutes=33))},33,Laufen,{6.1 + rnd.random() * 0.4:.2f},148,171,24,430,cmftrack")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, rows in (("daily_summary.csv", daily), ("sleep_sessions.csv", sessions), ("sleep_stages.csv", stages),
                           ("heart_rate.csv", hr), ("spo2.csv", spo2), ("respiratory_rate.csv", rr), ("stress.csv", stress),
                           ("hrv_rmssd.csv", hrv), ("resting_heart_rate.csv", rest), ("workouts.csv", workouts), ("track.csv", track)):
            z.writestr(name, "\n".join(rows) + "\n")
    return buf.getvalue()
