"""CLI: python -m cmfexport {gadgetbridge|healthconnect} <db-or-zip> -o <out-dir>"""
from __future__ import annotations

import argparse
from pathlib import Path

from . import gadgetbridge, healthconnect, report


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(prog="cmfexport",
                                 description="Alle Gesundheitsdaten einer CMF Watch (Pro 3) exportieren.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("gadgetbridge", help="Gadgetbridge-Datenbank (Gadgetbridge.db / .zip) auswerten")
    g.add_argument("db")
    g.add_argument("-o", "--out", default="export")
    g.add_argument("--device", help="nur Gerät, dessen Name/MAC diesen Text enthält (z.B. 'CMF')")
    g.add_argument("--raw", action="store_true", help="zusätzlich alle CMF-Tabellen 1:1 als CSV")

    h = sub.add_parser("healthconnect", help="Health-Connect-Export (Health Connect.zip) auswerten")
    h.add_argument("db")
    h.add_argument("-o", "--out", default="export")
    h.add_argument("--app", help="nur Daten dieser App, z.B. com.nothing.cmf.watch")

    a = ap.parse_args(argv)
    out = Path(a.out)
    if a.cmd == "gadgetbridge":
        res = gadgetbridge.export(a.db, out, a.device, a.raw)
        gadgetbridge.print_summary(res)
        report.write(out / "report.html", res["daily"], res["sessions"], "Gadgetbridge")
    else:
        res = healthconnect.export(a.db, out, a.app)
        healthconnect.print_summary(res)
        report.write(out / "report.html", [], res["sessions"], "Health Connect")
    print(f"\nFertig. Dateien in: {out.resolve()}  (Übersicht: {out / 'report.html'})")


if __name__ == "__main__":
    main()
