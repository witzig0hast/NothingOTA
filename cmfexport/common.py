"""Shared helpers: DB opening (incl. zip archives), time conversion, writers."""
from __future__ import annotations

import csv
import json
import os
import sqlite3
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Sequence


def open_db(path: str | os.PathLike, candidates: Sequence[str]) -> sqlite3.Connection:
    """Open a SQLite DB read-only. ``path`` may be the DB itself or a .zip export
    containing one of ``candidates`` (matched by file name)."""
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(path)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
            member = next(
                (n for c in candidates for n in names if Path(n).name == c), None
            ) or next((n for n in names if n.endswith(".db")), None)
            if member is None:
                raise ValueError(f"no database found in {path}: {names}")
            tmp = Path(tempfile.mkdtemp(prefix="cmfexport-")) / Path(member).name
            tmp.write_bytes(zf.read(member))
            path = tmp
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    return con


def tables(con: sqlite3.Connection) -> list[str]:
    return [r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]


def columns(con: sqlite3.Connection, table: str) -> list[str]:
    return [r[1] for r in con.execute(f'PRAGMA table_info("{table}")')]


def iso(ts: float | int | None, unit: str = "s") -> str | None:
    """Unix timestamp (seconds or milliseconds) -> local ISO-8601 string."""
    if ts is None:
        return None
    sec = ts / 1000 if unit == "ms" else ts
    return datetime.fromtimestamp(sec, tz=timezone.utc).astimezone().isoformat(timespec="seconds")


def write_csv(path: Path, rows: Iterable[dict]) -> int:
    rows = list(rows)
    path.parent.mkdir(parents=True, exist_ok=True)
    fields: list[str] = []
    for r in rows:
        for k in r:
            if k not in fields:
                fields.append(k)
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    return len(rows)


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False, default=str), encoding="utf-8")


def fmt_minutes(m: float) -> str:
    m = int(round(m))
    return f"{m // 60}h{m % 60:02d}"
