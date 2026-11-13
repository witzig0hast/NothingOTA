"""Asthma / breathing diary: daily summary, control assessment (based on GINA questions), peak-flow zones.

Orientation only - the texts say so. Medication plans belong to the doctor.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from .store import SYMPTOMS, TRIGGERS, Store

NIGHT, LIMIT = "nacht", "eingeschraenkt"


def _day(ts: int) -> str:
    return datetime.fromtimestamp(ts, timezone.utc).astimezone().date().isoformat()


def by_day(entries: list[dict]) -> dict[str, dict]:
    days: dict[str, dict] = {}
    for e in entries:
        d = days.setdefault(_day(e["ts"]), {"reliever": 0, "controller": 0, "symptoms": set(), "severity": 0,
                                           "peakflow": None, "triggers": set(), "notes": []})
        k, v, det = e["kind"], e["value"], e.get("detail") or ""
        if k in ("reliever", "controller"):
            d[k] += int(v or 1)
        elif k == "symptom":
            d["symptoms"].update(s for s in det.split(",") if s)
            d["severity"] = max(d["severity"], int(v or 1))
        elif k == "peakflow" and v:
            d["peakflow"] = max(d["peakflow"] or 0, v)
        elif k == "trigger":
            d["triggers"].update(s for s in det.split(",") if s)
        elif k == "note" and det:
            d["notes"].append(det)
    return days


def summary(store: Store, uid: int, today: date | None = None, best: float | None = None) -> dict:
    today = today or datetime.now().astimezone().date()
    entries = store.diary(uid, 90)
    days = by_day(entries)
    last28 = {d: v for d, v in days.items() if (today - date.fromisoformat(d)).days < 28}
    weeks = 4 if last28 and (today - date.fromisoformat(min(last28))).days >= 21 else max(1, len({
        (today - date.fromisoformat(d)).days // 7 for d in last28}))
    day_sym = sum(1 for v in last28.values() if v["symptoms"] - {NIGHT, LIMIT})
    nights = sum(1 for v in last28.values() if NIGHT in v["symptoms"])
    relief = sum(1 for v in last28.values() if v["reliever"])
    limited = sum(1 for v in last28.values() if LIMIT in v["symptoms"])
    questions = [
        {"q": "Beschwerden tagsüber öfter als 2× pro Woche", "yes": day_sym / weeks > 2, "value": round(day_sym / weeks, 1)},
        {"q": "Nachts aufgewacht wegen Asthma", "yes": nights > 0, "value": nights},
        {"q": "Notfallspray öfter als 2× pro Woche", "yes": relief / weeks > 2, "value": round(relief / weeks, 1)},
        {"q": "Bei Aktivitäten eingeschränkt", "yes": limited > 0, "value": limited},
    ]
    yes = sum(q["yes"] for q in questions)
    control = None
    if entries:
        control = "kontrolliert" if yes == 0 else "teilweise kontrolliert" if yes <= 2 else "nicht kontrolliert"

    pf = [(d, v["peakflow"]) for d, v in sorted(days.items()) if v["peakflow"]]
    best = best or (max(v for _, v in pf) if pf else None)
    latest_pf = pf[-1] if pf else None
    zone = None
    if latest_pf and best:
        pct = latest_pf[1] / best * 100
        zone = {"value": latest_pf[1], "date": latest_pf[0], "best": best, "pct": round(pct),
                "zone": "grün" if pct >= 80 else "gelb" if pct >= 50 else "rot"}

    t = today.isoformat()
    trig: dict[str, int] = {}
    for v in last28.values():
        for x in v["triggers"]:
            trig[x] = trig.get(x, 0) + 1
    timeline = []
    for i in range(29, -1, -1):
        d = (today - timedelta(days=i)).isoformat()
        v = days.get(d)
        timeline.append({"date": d, "reliever": v["reliever"] if v else 0, "controller": v["controller"] if v else 0,
                         "symptoms": sorted(v["symptoms"]) if v else [], "severity": v["severity"] if v else 0,
                         "peakflow": v["peakflow"] if v else None, "triggers": sorted(v["triggers"]) if v else []})
    week = [x for x in timeline[-7:]]
    prev = [x for x in timeline[-14:-7]]
    return {
        "today": {"reliever": days.get(t, {}).get("reliever", 0), "controller": days.get(t, {}).get("controller", 0),
                  "symptoms": sorted(days.get(t, {}).get("symptoms", set()))},
        "control": control, "control_score": yes, "questions": questions, "peakflow": zone,
        "reliever_7d": sum(x["reliever"] for x in week), "reliever_prev_7d": sum(x["reliever"] for x in prev),
        "reliever_days_7d": sum(1 for x in week if x["reliever"]),
        "triggers": sorted(({"key": k, "name": TRIGGERS.get(k, k), "days": n} for k, n in trig.items()), key=lambda x: -x["days"]),
        "timeline": timeline, "entries": entries[-60:], "has_data": bool(entries),
        "symptom_names": SYMPTOMS, "trigger_names": TRIGGERS,
    }


def flags(s: dict) -> list[dict]:
    out = []
    if not s["has_data"]:
        return out
    if s["control"] == "nicht kontrolliert":
        out.append(("alert", "Asthma", "Asthma laut Tagebuch nicht gut kontrolliert",
                    f"{s['control_score']} von 4 Kontrollfragen treffen zu (letzte 4 Wochen). Das ist ein Grund, die "
                    "Behandlung mit der Ärztin oder dem Arzt zu besprechen."))
    elif s["control"] == "teilweise kontrolliert":
        out.append(("warn", "Asthma", "Asthma teilweise kontrolliert",
                    f"{s['control_score']} von 4 Kontrollfragen treffen zu. Achte auf Auslöser und nimm die Dauertherapie regelmäßig."))
    else:
        out.append(("good", "Asthma", "Asthma gut kontrolliert", "Keine der Kontrollfragen trifft in den letzten 4 Wochen zu."))
    if s["reliever_7d"] >= 4 and s["reliever_7d"] >= 2 * max(1, s["reliever_prev_7d"]):
        out.append(("warn", "Asthma", "Mehr Notfallspray als sonst",
                    f"{s['reliever_7d']} Hübe in den letzten 7 Tagen (Woche davor: {s['reliever_prev_7d']}). "
                    "Steigender Bedarf ist ein frühes Zeichen für eine Verschlechterung."))
    pf = s["peakflow"]
    if pf and pf["zone"] == "rot":
        out.append(("alert", "Asthma", "Peak-Flow im roten Bereich",
                    f"{pf['value']:.0f} l/min = {pf['pct']} % deines Bestwerts. Folge deinem Notfallplan und hol dir ärztliche Hilfe."))
    elif pf and pf["zone"] == "gelb":
        out.append(("warn", "Asthma", "Peak-Flow im gelben Bereich",
                    f"{pf['value']:.0f} l/min = {pf['pct']} % deines Bestwerts (grün ab 80 %)."))
    return [{"level": a, "area": b, "title": c, "detail": d, "value": None} for a, b, c, d in out]
