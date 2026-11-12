"""Night vs. nap: the same rules as the Android app (Export.kt, object Sleep).

* nap: starts between 10:00 and 19:59 local time and is shorter than 4 h
* night: all other sessions, grouped by the day you wake up (sessions ending at/after 18:00
  belong to the next day); time awake between the segments of one night counts as awake
* overlapping duplicates (two apps writing the same night) are dropped, preferring
  sessions with stage data and longer ones
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta

NAP_MAX_MIN = 240.0


@dataclass(frozen=True)
class Session:
    start: datetime          # timezone aware
    end: datetime
    deep: float = 0.0
    light: float = 0.0
    rem: float = 0.0
    awake: float = 0.0
    has_stages: bool = True
    source: str | None = None
    segments: int = 1
    nap: bool = False

    @property
    def in_bed(self) -> float:
        return (self.end - self.start).total_seconds() / 60

    @property
    def asleep(self) -> float:
        return self.deep + self.light + self.rem if self.has_stages else self.in_bed - self.awake

    @property
    def efficiency(self) -> float | None:
        return 100 * self.asleep / self.in_bed if self.in_bed > 0 else None


def is_nap(s: Session) -> bool:
    return 10 <= s.start.astimezone().hour <= 19 and s.in_bed < NAP_MAX_MIN


def day_of(s: Session) -> date:
    e = s.end.astimezone()
    return e.date() + timedelta(days=1) if e.hour >= 18 else e.date()


def _dedupe(sessions: list[Session]) -> list[Session]:
    kept: list[Session] = []
    for s in sorted(sessions, key=lambda x: (not x.has_stages, -x.in_bed)):
        dup = False
        for k in kept:
            overlap = (min(k.end, s.end) - max(k.start, s.start)).total_seconds() / 60
            if overlap > 0 and overlap > 0.5 * min(k.in_bed, s.in_bed):
                dup = True
                break
        if not dup:
            kept.append(s)
    return sorted(kept, key=lambda x: x.start)


def merge(segs: list[Session]) -> Session:
    segs = sorted(segs, key=lambda x: x.start)
    if len(segs) == 1:
        return segs[0]
    gaps = sum(max(0.0, (b.start - a.end).total_seconds() / 60) for a, b in zip(segs, segs[1:]))
    return Session(segs[0].start, max(s.end for s in segs), sum(s.deep for s in segs), sum(s.light for s in segs),
                   sum(s.rem for s in segs), sum(s.awake for s in segs) + gaps,
                   all(s.has_stages for s in segs), segs[0].source, len(segs))


def group(sessions: list[Session]) -> tuple[list[Session], list[Session]]:
    """Returns (nights sorted by day, naps sorted by start)."""
    clean = _dedupe(sessions)
    naps = [replace(s, nap=True) for s in clean if is_nap(s)]
    by_day: dict[date, list[Session]] = {}
    for s in clean:
        if not is_nap(s):
            by_day.setdefault(day_of(s), []).append(s)
    return [merge(v) for _, v in sorted(by_day.items())], naps
