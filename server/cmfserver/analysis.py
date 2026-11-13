"""Rule-based health insights (no AI needed, cheap enough to run on every page view).

Everything is compared with the person's own baseline (last 30 days) instead of population
norms. These are hints, not diagnoses - the texts say so.
"""
from __future__ import annotations

import statistics
from datetime import date, datetime, timedelta, timezone

from .store import Store, now_ts

DISCLAIMER = ("Hinweise basieren auf den Messwerten deiner Uhr und ersetzen keine ärztliche Untersuchung. "
              "Bei Atemnot, Brustschmerzen oder anhaltenden Beschwerden bitte ärztlich abklären lassen.")


def _avg(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def night_spo2(store: Store, uid: int, nights: list[dict]) -> list[dict]:
    """Blood oxygen during each night: average, minimum, number of readings below 92 % and 90 %."""
    out = []
    for n in nights:
        if n["type"] != "night":
            continue
        vals = [v for _, v in store.series(uid, "spo2", n["start_ts"], n["end_ts"], max_points=100000) if 50 < v <= 100]
        if not vals:
            continue
        out.append({"night_of": n["night_of"], "avg": round(sum(vals) / len(vals), 1), "min": min(vals),
                    "below92": sum(v < 92 for v in vals), "below90": sum(v < 90 for v in vals), "samples": len(vals)})
    return out


def night_metric(store: Store, uid: int, metric: str, nights: list[dict]) -> list[tuple[str, float]]:
    out = []
    for n in nights:
        if n["type"] != "night":
            continue
        vals = [v for _, v in store.series(uid, metric, n["start_ts"], n["end_ts"], max_points=100000)]
        if vals:
            out.append((n["night_of"], sum(vals) / len(vals)))
    return out


def daily_metric(store: Store, uid: int, metric: str, days: int = 35) -> dict[str, float]:
    """Average per local day of a sample metric."""
    by: dict[str, list[float]] = {}
    for ts, v in store.series(uid, metric, now_ts() - days * 86400, max_points=100000):
        by.setdefault(datetime.fromtimestamp(ts, timezone.utc).astimezone().date().isoformat(), []).append(v)
    return {d: sum(v) / len(v) for d, v in by.items()}


def resting_hr_by_day(store: Store, uid: int, daily: list[dict]) -> dict[str, float]:
    r = {d["date"]: d["resting_hr"] for d in daily if d.get("resting_hr")}
    if len(r) < 3:
        # Without a resting HR from the watch: lowest 5-min level of each day is a good proxy
        by: dict[str, list[float]] = {}
        for ts, v in store.series(uid, "heart_rate", now_ts() - 35 * 86400, max_points=100000):
            if 35 < v < 120:
                by.setdefault(datetime.fromtimestamp(ts, timezone.utc).astimezone().date().isoformat(), []).append(v)
        for d, vals in by.items():
            if len(vals) >= 20 and d not in r:
                vals.sort()
                r[d] = sum(vals[: max(3, len(vals) // 20)]) / max(3, len(vals) // 20)
    return r


def insights(store: Store, uid: int, today: date | None = None) -> dict:
    today = today or datetime.now().astimezone().date()
    daily = store.daily(uid, 40)
    nights = store.nights(uid, 35)
    real_nights = [n for n in nights if n["type"] == "night"]
    flags: list[dict] = []

    def flag(level: str, area: str, title: str, detail: str, value=None):
        flags.append({"level": level, "area": area, "title": title, "detail": detail, "value": value})

    def recent(d: str, days: int) -> bool:
        return (today - date.fromisoformat(d)).days < days

    # --- breathing: blood oxygen during sleep --------------------------------------------------
    spo2 = night_spo2(store, uid, real_nights)
    last7 = [x for x in spo2 if recent(x["night_of"], 7)]
    if last7:
        low_nights = [x for x in last7 if x["min"] < 90 or x["below92"] >= 3]
        very_low = [x for x in last7 if x["min"] < 88]
        if len(very_low) >= 2:
            flag("alert", "Atmung", "Wiederholt sehr niedriger Sauerstoff im Schlaf",
                 f"In {len(very_low)} der letzten {len(last7)} Nächte fiel die Sauerstoffsättigung unter 88 % "
                 f"(tiefster Wert {min(x['min'] for x in very_low):.0f} %). Das kann auf Atemaussetzer (Schlafapnoe) oder "
                 "eine nächtliche Verschlechterung bei Asthma hindeuten – bitte ärztlich abklären.", min(x["min"] for x in very_low))
        elif low_nights:
            flag("warn", "Atmung", "Sauerstoffabfälle in der Nacht",
                 f"{len(low_nights)} von {len(last7)} Nächten mit Werten unter 92 %. Einzelne Ausreißer entstehen oft durch "
                 "Liegen auf dem Arm; wiederholt sich das, lohnt ein Gespräch mit der Ärztin oder dem Arzt.", min(x["min"] for x in low_nights))
        else:
            flag("good", "Atmung", "Sauerstoff im Schlaf unauffällig",
                 f"Ø {_avg([x['avg'] for x in last7]):.1f} % in den letzten {len(last7)} Nächten, nie unter 92 %.")
    else:
        flag("info", "Atmung", "Keine Sauerstoffwerte aus dem Schlaf",
             "Schalte in der CMF-App die automatische SpO₂-Messung (im Schlaf) ein – das ist der wichtigste Wert "
             "für Atmung und Asthma-Verlauf.")

    # --- breathing: respiratory rate -------------------------------------------------------------
    rr = night_metric(store, uid, "respiratory_rate", real_nights)
    if len(rr) >= 5:
        base = statistics.median(v for _, v in rr[:-3] or rr)
        # short episodes matter (infection, asthma flare): any of the last 3 nights counts
        high = [(d, v) for d, v in rr if recent(d, 3) and (v > base + 3 or v > 20)]
        if high:
            d, v = max(high, key=lambda x: x[1])
            flag("warn", "Atmung", "Atemfrequenz im Schlaf erhöht",
                 f"{v:.1f} Atemzüge/min in der Nacht zum {date.fromisoformat(d):%d.%m.} (sonst {base:.1f}). Häufig bei Infekten, "
                 "Fieber oder wenn die Atemwege gereizt sind – bei Asthma ein frühes Warnzeichen.", round(v, 1))

    # --- heart: resting heart rate & HRV -------------------------------------------------------
    rhr = resting_hr_by_day(store, uid, daily)
    if len(rhr) >= 7:
        days_sorted = sorted(rhr)
        base = statistics.median(rhr[d] for d in days_sorted[:-3])
        cur = _avg([rhr[d] for d in days_sorted[-3:]])
        peak_day = max(days_sorted[-3:], key=lambda d: rhr[d])
        if rhr[peak_day] >= base + 7:
            flag("warn", "Herz", "Ruhepuls deutlich erhöht",
                 f"{rhr[peak_day]:.0f} bpm am {date.fromisoformat(peak_day):%d.%m.} statt sonst {base:.0f} bpm. Typisch bei beginnendem Infekt, "
                 "zu wenig Schlaf, Alkohol oder Übertraining – heute eher locker machen.", round(rhr[peak_day]))
        elif cur and cur <= base - 3:
            flag("good", "Herz", "Ruhepuls niedriger als sonst", f"Ø {cur:.0f} bpm (sonst {base:.0f} bpm) – gute Erholung.")
    hrv = daily_metric(store, uid, "hrv")
    if len(hrv) >= 7:
        ds = sorted(hrv)
        base = statistics.median(hrv[d] for d in ds[:-3])
        low_day = min(ds[-3:], key=lambda d: hrv[d])
        if hrv[low_day] < base * 0.8:
            flag("warn", "Herz", "Herzratenvariabilität gesunken",
                 f"HRV {hrv[low_day]:.0f} ms am {date.fromisoformat(low_day):%d.%m.} statt sonst {base:.0f} ms – "
                 "der Körper ist gestresst oder nicht erholt.", round(hrv[low_day]))

    # --- stress -------------------------------------------------------------------------------
    stress = daily_metric(store, uid, "stress", 7)
    if stress:
        cur = _avg(list(stress.values()))
        if cur and cur >= 60:
            flag("warn", "Stress", "Viel Stress in den letzten Tagen",
                 f"Ø Stresswert {cur:.0f} von 100. Kurze Atempausen (4 s ein, 6 s aus) senken ihn messbar.", round(cur))

    # --- sleep --------------------------------------------------------------------------------
    week = [n for n in real_nights if recent(n["night_of"], 7)]
    if week:
        avg_sleep = _avg([n["asleep_min"] for n in week])
        if avg_sleep < 360:
            flag("warn", "Schlaf", "Zu wenig Schlaf",
                 f"Ø {avg_sleep / 60:.1f} h pro Nacht in den letzten 7 Tagen. Erwachsene brauchen meist 7–9 h.", round(avg_sleep))
        elif avg_sleep >= 420:
            flag("good", "Schlaf", "Genug Schlaf", f"Ø {avg_sleep / 60:.1f} h pro Nacht in den letzten 7 Tagen.")
        awake = _avg([n["awake_min"] for n in week])
        if awake and awake > 60:
            flag("warn", "Schlaf", "Unruhige Nächte",
                 f"Ø {awake:.0f} min wach pro Nacht. Häufiges nächtliches Aufwachen kann auch mit Atembeschwerden "
                 "(z. B. nächtliches Asthma) zusammenhängen – achte darauf, ob du dabei hustest oder schlecht Luft bekommst.", round(awake))
        starts = [(datetime.fromisoformat(n["start"]).astimezone().hour * 60 + datetime.fromisoformat(n["start"]).astimezone().minute + 720) % 1440
                  for n in week]
        if len(starts) >= 4 and statistics.pstdev(starts) > 75:
            flag("info", "Schlaf", "Unregelmäßige Schlafenszeiten",
                 f"Deine Einschlafzeit schwankt um ±{statistics.pstdev(starts):.0f} min. Feste Zeiten verbessern die Schlafqualität.")

    # --- activity -----------------------------------------------------------------------------
    steps7 = [d["steps"] for d in daily if d.get("steps") and recent(d["date"], 7) and d["date"] != today.isoformat()]
    if steps7:
        s = _avg(steps7)
        if s < 4000:
            flag("info", "Aktivität", "Wenig Bewegung", f"Ø {s:,.0f} Schritte pro Tag. Schon 7.000 Schritte senken nachweislich das Krankheitsrisiko.".replace(",", "."))
        elif s >= 10000:
            flag("good", "Aktivität", "Sehr aktiv", f"Ø {s:,.0f} Schritte pro Tag in der letzten Woche.".replace(",", "."))

    # --- recovery score (0-100) ---------------------------------------------------------------
    # Each part 0..1; for the body signals the worse of the last two days counts, so that a bad
    # day yesterday still shows today (recovery lags behind).
    score_parts = []
    last_night = real_nights[-1] if real_nights else None
    if last_night:
        score_parts.append(min(1.0, last_night["asleep_min"] / 480))
        if last_night.get("efficiency"):
            score_parts.append(min(1.0, last_night["efficiency"] / 92))
    if len(rhr) >= 7:
        ds = sorted(rhr); base = statistics.median(rhr[d] for d in ds[:-2])
        worst = max(rhr[d] for d in ds[-2:])
        score_parts.append(max(0.0, min(1.0, 1 - (worst - base) / 12)))
    if len(hrv) >= 7:
        ds = sorted(hrv); base = statistics.median(hrv[d] for d in ds[:-2])
        worst = min(hrv[d] for d in ds[-2:])
        score_parts.append(max(0.0, min(1.0, worst / base)))
    if last7:
        worst = min(x["min"] for x in last7[-2:])
        score_parts.append(1.0 if worst >= 92 else 0.7 if worst >= 90 else 0.4)
    recovery = round(100 * sum(score_parts) / len(score_parts)) if score_parts else None
    if recovery is not None and any(f["level"] == "alert" for f in flags):
        recovery = min(recovery, 60)  # never "fully recovered" while an alert is open

    order = {"alert": 0, "warn": 1, "info": 2, "good": 3}
    flags.sort(key=lambda f: order[f["level"]])
    return {"flags": flags, "recovery": recovery, "spo2_nights": spo2[-14:],
            "respiratory_nights": [{"night_of": d, "avg": round(v, 1)} for d, v in rr[-14:]],
            "resting_hr": [{"date": d, "value": round(v, 1)} for d, v in sorted(rhr.items())[-30:]],
            "disclaimer": DISCLAIMER}


def ai_context(store: Store, uid: int) -> dict:
    """Compact summary for the language model (few tokens, no raw samples)."""
    ins = insights(store, uid)
    daily = store.daily(uid, 14)
    nights = [n for n in store.nights(uid, 14)]
    return {
        "heute": date.today().isoformat(),
        "tageswerte": [{k: d.get(k) for k in ("date", "steps", "hr_avg", "resting_hr", "spo2_avg", "stress_avg")} for d in daily],
        "schlaf": [{k: n[k] for k in ("night_of", "type", "asleep_min", "deep_min", "rem_min", "awake_min", "efficiency")} for n in nights],
        "spo2_naechte": ins["spo2_nights"],
        "atemfrequenz_naechte": ins["respiratory_nights"],
        "ruhepuls": ins["resting_hr"][-14:],
        "erholung_0_100": ins["recovery"],
        "auffaelligkeiten": [{k: f[k] for k in ("level", "area", "title", "detail")} for f in ins["flags"]],
        "trainings": [{k: w.get(k) for k in ("start", "type", "duration_min", "distance_km", "avg_hr")} for w in store.workouts(uid, 10)],
    }
