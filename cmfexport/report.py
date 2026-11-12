"""Tiny self-contained HTML report (no external dependencies)."""
from __future__ import annotations

from html import escape
from pathlib import Path

from .common import fmt_minutes

COLORS = {"deep": "#3b4cc0", "light": "#7aa6f0", "rem": "#b06ad9", "awake": "#f2a541"}


def _sleep_rows(sessions):
    if not sessions:
        return "<p>Keine Schlafdaten gefunden.</p>"
    longest = max((s["time_in_bed_min"] or 1) for s in sessions)
    rows = []
    sessions = sorted(sessions, key=lambda x: x.get("fell_asleep") or x.get("start") or "")
    for s in reversed(sessions[-60:]):
        bars = "".join(
            f'<span style="width:{100 * (s.get(k + "_min") or 0) / longest:.2f}%;background:{c}" '
            f'title="{k}: {fmt_minutes(s.get(k + "_min") or 0)}"></span>' for k, c in COLORS.items())
        asleep = s.get("asleep_min")
        start = s.get("fell_asleep") or s.get("start")
        end = s.get("woke_up") or s.get("end")
        rows.append(
            f"<tr><td>{escape(start[:16].replace('T', ' '))}{' · Nickerchen' if s.get('type') == 'nap' else ''}</td><td>{escape(end[11:16])}</td>"
            f"<td>{fmt_minutes(asleep) if asleep is not None else '?'}</td>"
            f"<td>{fmt_minutes(s['deep_min'])}</td><td>{fmt_minutes(s['light_min'])}</td>"
            f"<td>{fmt_minutes(s['rem_min'])}</td><td>{fmt_minutes(s['awake_min'])}</td>"
            f'<td class="bar">{bars}</td></tr>')
    return ("<table><tr><th>Eingeschlafen</th><th>Aufgewacht</th><th>Schlaf</th><th>Tief</th><th>Leicht</th>"
            "<th>REM</th><th>Wach</th><th></th></tr>" + "".join(rows) + "</table>")


def _daily_rows(daily):
    if not daily:
        return ""
    keys = ["date", "steps", "distance_m", "calories", "hr_avg", "hr_min", "hr_max", "spo2_avg", "stress_avg", "sleep_min"]
    head = "".join(f"<th>{k}</th>" for k in keys)
    body = "".join("<tr>" + "".join(f"<td>{'' if d.get(k) is None else escape(str(d[k]))}</td>" for k in keys) + "</tr>"
                   for d in reversed(daily[-90:]))
    return f"<h2>Tageswerte</h2><table><tr>{head}</tr>{body}</table>"


def write(path: Path, daily, sessions, source: str) -> None:
    legend = " ".join(f'<i style="background:{c}"></i>{k}' for k, c in COLORS.items())
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"""<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CMF Watch Daten</title>
<style>
:root{{--bg:#fff;--fg:#222;--line:#ddd}}
@media (prefers-color-scheme:dark){{:root{{--bg:#151515;--fg:#eee;--line:#333}}}}
body{{background:var(--bg);color:var(--fg);font:14px system-ui,sans-serif;margin:16px;max-width:1100px}}
table{{border-collapse:collapse;width:100%;margin-bottom:24px}}td,th{{border-bottom:1px solid var(--line);padding:4px 6px;text-align:left;white-space:nowrap}}
td.bar{{width:40%;white-space:normal}}td.bar span{{display:inline-block;height:12px}}
.legend i{{display:inline-block;width:10px;height:10px;margin:0 4px 0 12px}}
.wrap{{overflow-x:auto}}
</style></head><body>
<h1>CMF Watch – Datenexport</h1><p>Quelle: {escape(source)}</p>
<h2>Schlaf</h2><p class="legend">{legend}</p><div class="wrap">{_sleep_rows(sessions)}</div>
<div class="wrap">{_daily_rows(daily)}</div>
</body></html>""", encoding="utf-8")
