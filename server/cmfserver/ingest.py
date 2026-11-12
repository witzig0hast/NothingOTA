"""Turn an uploaded file into the CSV export layout and merge it into the store."""
from __future__ import annotations

import logging
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

from cmfexport import gadgetbridge

from .store import Store

log = logging.getLogger(__name__)
SQLITE_MAGIC = b"SQLite format 3\x00"


class UnsupportedFile(ValueError):
    pass


def _safe_extract(zf: zipfile.ZipFile, dest: Path) -> None:
    """Extract without allowing paths outside dest (zip slip)."""
    root = dest.resolve()
    for info in zf.infolist():
        if info.is_dir():
            continue
        target = (dest / PurePosixPath(info.filename)).resolve()
        if root not in target.parents:
            raise UnsupportedFile(f"unsafe path in zip: {info.filename}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info) as src, target.open("wb") as dst:
            shutil.copyfileobj(src, dst)


def _find_export_root(d: Path) -> Path | None:
    hit = next(d.rglob("daily_summary.csv"), None) or next(d.rglob("sleep_sessions.csv"), None)
    return hit.parent if hit else None


def _find_gadgetbridge_db(d: Path) -> Path | None:
    for p in sorted(d.rglob("*"), key=lambda p: (p.name not in ("Gadgetbridge", "Gadgetbridge.db"), str(p))):
        if p.is_file():
            with p.open("rb") as fh:
                if fh.read(16) == SQLITE_MAGIC:
                    return p
    return None


def ingest_file(store: Store, path: Path, name: str | None = None) -> dict:
    """Detects the file type, converts and merges it. Returns a summary dict."""
    name = name or path.name
    with tempfile.TemporaryDirectory(prefix="cmfserver-") as tmp:
        tmp = Path(tmp)
        with path.open("rb") as fh:
            head = fh.read(16)

        if head == SQLITE_MAGIC:
            db, src = path, None
        elif zipfile.is_zipfile(path):
            src = tmp / "src"
            with zipfile.ZipFile(path) as zf:
                _safe_extract(zf, src)
            root = _find_export_root(src)
            if root is not None:
                # ZIP from the Android app or a cmfexport output directory
                counts = store.ingest_dir(root, windowed=True, name=name, kind="export")
                return {"kind": "export", "counts": counts}
            db = _find_gadgetbridge_db(src)
            if db is None:
                raise UnsupportedFile("ZIP enthält weder einen CMF-Export noch eine Gadgetbridge-Datenbank")
        else:
            raise UnsupportedFile("Unbekanntes Format – erwartet ZIP der CMF-Export-App oder Gadgetbridge-Datenbank")

        out = tmp / "out"
        try:
            gadgetbridge.export(db, out)
        except Exception as e:  # noqa: BLE001 - surface the reason to the uploader
            raise UnsupportedFile(f"Gadgetbridge-Datenbank konnte nicht gelesen werden: {e}") from e
        counts = store.ingest_dir(out, windowed=False, name=name, kind="gadgetbridge")
        return {"kind": "gadgetbridge", "counts": counts}
