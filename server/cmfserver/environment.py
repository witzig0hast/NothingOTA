"""Air quality, pollen and weather for the user's location (Open-Meteo, free, no key).

https://open-meteo.com/en/docs/air-quality-api - pollen only for Europe (CAMS).
"""
from __future__ import annotations

import json
import logging
import threading
import time
import urllib.parse
import urllib.request
from datetime import date, datetime

log = logging.getLogger(__name__)
AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"
WEATHER = "https://api.open-meteo.com/v1/forecast"
GEO = "https://geocoding-api.open-meteo.com/v1/search"

POLLEN = {  # key -> (name, moderate from, high from) in grains/m³
    "alder": ("Erle", 10, 100), "birch": ("Birke", 10, 100), "grass": ("Gräser", 5, 50),
    "mugwort": ("Beifuß", 5, 30), "olive": ("Olive", 10, 100), "ragweed": ("Ambrosia", 3, 15),
}
LEVELS = ["keine", "gering", "mittel", "hoch"]
EAQI = [(20, "gut"), (40, "ordentlich"), (60, "mäßig"), (80, "schlecht"), (100, "sehr schlecht"), (10**9, "extrem schlecht")]
WEATHER_CODES = {0: "klar", 1: "überwiegend klar", 2: "teils bewölkt", 3: "bewölkt", 45: "Nebel", 48: "Reifnebel",
                 51: "Niesel", 53: "Niesel", 55: "starker Niesel", 61: "leichter Regen", 63: "Regen", 65: "starker Regen",
                 71: "leichter Schnee", 73: "Schnee", 75: "starker Schnee", 80: "Schauer", 81: "Schauer",
                 82: "heftige Schauer", 95: "Gewitter", 96: "Gewitter mit Hagel", 99: "Gewitter mit Hagel"}


def pollen_level(key: str, v: float | None) -> int:
    if not v or v < 1:
        return 0
    _, mid, high = POLLEN[key]
    return 3 if v >= high else 2 if v >= mid else 1


def eaqi_label(v: float | None) -> str | None:
    if v is None:
        return None
    return next(label for lim, label in EAQI if v <= lim)


def _get(url: str, params: dict, timeout: int = 15) -> dict:
    req = urllib.request.Request(f"{url}?{urllib.parse.urlencode(params)}", headers={"User-Agent": "cmfserver"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


class Environment:
    def __init__(self, fetch=_get, ttl: int = 1800):
        self.fetch = fetch
        self.ttl = ttl
        self.cache: dict[tuple, tuple[float, dict]] = {}
        self.lock = threading.Lock()

    def search(self, q: str) -> list[dict]:
        data = self.fetch(GEO, {"name": q, "count": 8, "language": "de", "format": "json"})
        return [{"name": r["name"], "lat": r["latitude"], "lon": r["longitude"],
                 "region": ", ".join(x for x in (r.get("admin1"), r.get("country")) if x)} for r in data.get("results") or []]

    def get(self, lat: float, lon: float) -> dict | None:
        key = (round(lat, 2), round(lon, 2))
        with self.lock:
            hit = self.cache.get(key)
            if hit and time.time() - hit[0] < self.ttl:
                return hit[1]
        try:
            res = self._load(*key)
        except Exception as e:  # noqa: BLE001 - offline, rate limit ... keep the old value
            log.warning("Open-Meteo nicht erreichbar: %s", e)
            return hit[1] if hit else None
        with self.lock:
            self.cache[key] = (time.time(), res)
        return res

    def _load(self, lat: float, lon: float) -> dict:
        pollen_vars = [f"{k}_pollen" for k in POLLEN]
        air = self.fetch(AIR, {"latitude": lat, "longitude": lon, "timezone": "auto", "past_days": 31, "forecast_days": 2,
                               "current": ",".join(["european_aqi", "pm2_5", "pm10", "ozone", "nitrogen_dioxide", *pollen_vars]),
                               "hourly": ",".join(["european_aqi", *pollen_vars])})
        try:
            wx = self.fetch(WEATHER, {"latitude": lat, "longitude": lon, "timezone": "auto",
                                      "current": "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code"})
        except Exception:  # noqa: BLE001
            wx = {}
        cur = air.get("current") or {}
        hourly = air.get("hourly") or {}
        # daily maxima (history for correlation with the diary + today/tomorrow forecast)
        days: dict[str, dict] = {}
        for i, t in enumerate(hourly.get("time") or []):
            d = days.setdefault(t[:10], {})
            for var in ["european_aqi", *pollen_vars]:
                v = (hourly.get(var) or [None] * (i + 1))[i]
                if v is not None:
                    d[var] = max(d.get(var, 0), v)
        today = date.today().isoformat()
        pollen = []
        for k, (name, _, _) in POLLEN.items():
            v = cur.get(f"{k}_pollen")
            dmax = days.get(today, {}).get(f"{k}_pollen")
            tomorrow = [d for dd, d in sorted(days.items()) if dd > today][:1]
            tmax = tomorrow[0].get(f"{k}_pollen") if tomorrow else None
            pollen.append({"key": k, "name": name, "now": v, "today_max": dmax, "tomorrow_max": tmax,
                           "level": pollen_level(k, dmax if dmax is not None else v),
                           "level_tomorrow": pollen_level(k, tmax)})
        pollen.sort(key=lambda p: (-p["level"], -(p["today_max"] or 0)))
        top = pollen[0] if pollen and pollen[0]["level"] else None
        history = [{"date": d, "aqi": v.get("european_aqi"),
                    "pollen": max((pollen_level(k, v.get(f"{k}_pollen")) for k in POLLEN), default=0),
                    **{k: v.get(f"{k}_pollen") for k in POLLEN}} for d, v in sorted(days.items())]
        w = wx.get("current") or {}
        return {
            "time": cur.get("time"), "aqi": cur.get("european_aqi"), "aqi_label": eaqi_label(cur.get("european_aqi")),
            "pm2_5": cur.get("pm2_5"), "pm10": cur.get("pm10"), "ozone": cur.get("ozone"), "no2": cur.get("nitrogen_dioxide"),
            "pollen": pollen, "pollen_max": LEVELS[top["level"]] if top else "keine",
            "pollen_top": top["name"] if top else None, "history": history,
            "weather": {"temp": w.get("temperature_2m"), "feels": w.get("apparent_temperature"),
                        "humidity": w.get("relative_humidity_2m"),
                        "text": WEATHER_CODES.get(w.get("weather_code"), None)} if w else None,
            "fetched": datetime.now().astimezone().isoformat(timespec="minutes"),
        }


def flags(env: dict | None, diary: dict | None) -> list[dict]:
    if not env:
        return []
    out = []
    sensitive = bool(diary and diary.get("has_data"))
    top = next((p for p in env["pollen"] if p["level"] >= 2), None)
    if top:
        lvl = LEVELS[top["level"]]
        extra = " Nimm dein Spray mit und lüfte eher spätabends." if sensitive else ""
        out.append({"level": "warn" if top["level"] == 3 else "info", "area": "Umwelt",
                    "title": f"{top['name']}-Pollen heute {lvl}",
                    "detail": f"Bis {top['today_max']:.0f} Pollen/m³ erwartet.{extra}", "value": top["today_max"]})
    aqi = env.get("aqi")
    if aqi is not None and aqi > 60:
        out.append({"level": "warn", "area": "Umwelt", "title": f"Luftqualität {env['aqi_label']}",
                    "detail": f"Europäischer Luftqualitätsindex {aqi:.0f}. Anstrengenden Sport draußen heute besser verschieben.",
                    "value": aqi})
    # which pollen go together with the diary? (days with spray/symptoms vs. days without)
    if diary and diary.get("has_data"):
        hist = {h["date"]: h for h in env.get("history") or []}
        bad = [x["date"] for x in diary["timeline"] if x["reliever"] or x["symptoms"]]
        good = [x["date"] for x in diary["timeline"] if not x["reliever"] and not x["symptoms"]]
        for k, (name, mid, _) in POLLEN.items():
            b = [hist[d][k] for d in bad if d in hist and hist[d].get(k) is not None]
            g = [hist[d][k] for d in good if d in hist and hist[d].get(k) is not None]
            if len(b) >= 3 and len(g) >= 3:
                ab, ag = sum(b) / len(b), sum(g) / len(g)
                if ab >= mid and ab > 2 * max(ag, 1):
                    out.append({"level": "info", "area": "Umwelt", "title": f"Beschwerden passen zu {name}-Pollen",
                                "detail": f"An Tagen mit Beschwerden oder Spray waren im Schnitt {ab:.0f} {name}-Pollen/m³ in der Luft, "
                                          f"sonst {ag:.0f}. Ein Allergietest kann das klären.", "value": round(ab)})
    return out
