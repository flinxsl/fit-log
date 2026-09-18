#!/usr/bin/env python3
"""
fit-log importer: text_log -> fitlog.json

Converts 13 months of terse hand-written workout notation into structured JSON,
flagging everything it cannot resolve confidently rather than guessing.

Standard library only. Python 3.11+.

    python3 fitlog_import.py ../text_log --out ../out/
    python3 fitlog_import.py ../text_log --explain 257     # one line's parse tree
    python3 fitlog_import.py ../text_log --tracks          # track assignments

Exit code 0 only if all four validation layers pass.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field, asdict
from datetime import date, datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import program_config as cfg

IMPORTER_VERSION = "1.1.0"

# A slot must appear in at least this share of the most recent sessions of its day
# to be prescribed. Keeps retired movements out without hard-coding a cutoff date.
ROUTINE_WINDOW = 10
ROUTINE_MIN_SHARE = 0.5
SCHEMA_VERSION = 1


# ===========================================================================
# Model
# ===========================================================================

@dataclass
class Load:
    kind: str
    value: float | None = None
    unit: str | None = None

    def to_json(self):
        d = {"kind": self.kind}
        if self.value is not None:
            d["value"] = _num(self.value)
            d["unit"] = self.unit or "lb"
        return d


@dataclass
class Set:
    i: int
    load: Load
    reps: float | None = None
    targetReps: float | None = None
    durationSec: float | None = None
    targetDurationSec: float | None = None
    completed: bool = True
    failed: bool = False
    failureReason: str | None = None

    def to_json(self):
        d = {"i": self.i, "load": self.load.to_json()}
        if self.durationSec is not None:
            d["durationSec"] = _num(self.durationSec)
            d["targetDurationSec"] = _num(self.targetDurationSec)
        else:
            d["reps"] = _num(self.reps)
            d["targetReps"] = _num(self.targetReps)
        d["completed"] = self.completed
        d["failed"] = self.failed
        d["failureReason"] = self.failureReason
        return d


@dataclass
class Prescription:
    sets: int
    reps: float | None
    loadKind: str
    load: float | None
    unit: str | None
    origin: str = "import_config"
    confidence: str = "assumed"      # certain | assumed | unknown

    def to_json(self):
        return {
            "sets": self.sets,
            "reps": _num(self.reps),
            "loadKind": self.loadKind,
            "load": _num(self.load),
            "unit": self.unit,
            "origin": self.origin,
            "confidence": self.confidence,
        }


@dataclass
class Entry:
    exerciseId: str
    order: int
    prescription: Prescription
    sets: list[Set] = field(default_factory=list)
    setsAttribution: str = "uniform"     # positional | uniform | unordered
    track: str | None = None
    trackOrigin: str | None = None      # inferred | manual | single_track_era
    trackConfidence: str | None = None  # high | medium | low
    performed: bool = True
    failureReason: str | None = None
    notes: str = ""
    excludeFromPr: bool = False
    needsReview: bool = False
    progSets: int = 0        # program default, so the emitter knows when to write "xN"
    srcLine: int = 0
    srcRaw: str = ""

    def to_json(self):
        return {
            "exerciseId": self.exerciseId,
            "order": self.order,
            "track": self.track,
            "trackOrigin": self.trackOrigin,
            "trackConfidence": self.trackConfidence,
            "prescription": self.prescription.to_json(),
            "sets": [s.to_json() for s in self.sets],
            "setsAttribution": self.setsAttribution,
            "performed": self.performed,
            "failureReason": self.failureReason,
            "notes": self.notes,
            "excludeFromPr": self.excludeFromPr,
            "needsReview": self.needsReview,
            "source": {"line": self.srcLine, "raw": self.srcRaw},
        }


@dataclass
class Session:
    id: str
    date: str
    routineId: str
    dayLabel: str | None
    notes: str = ""
    tags: list[str] = field(default_factory=list)
    entries: list[Entry] = field(default_factory=list)
    needsReview: bool = False
    srcLine: int = 0
    srcRaw: str = ""

    def to_json(self):
        return {
            "id": self.id,
            "date": self.date,
            "routineId": self.routineId,
            "dayLabel": self.dayLabel,
            "notes": self.notes,
            "tags": self.tags,
            "bodyweightLb": None,
            "entries": [e.to_json() for e in self.entries],
            "needsReview": self.needsReview,
            "source": {"file": "text_log", "line": self.srcLine, "raw": self.srcRaw},
        }


@dataclass
class ReviewItem:
    id: str
    code: str
    severity: str        # error | warn | info
    scope: str           # session | entry | exercise | global
    title: str
    detail: str
    sourceLines: list[int] = field(default_factory=list)
    sourceRaw: str = ""
    assumption: str = ""
    options: list[dict] = field(default_factory=list)

    def to_json(self):
        return {
            "id": self.id, "code": self.code, "severity": self.severity,
            "scope": self.scope, "title": self.title, "detail": self.detail,
            "sourceLines": self.sourceLines, "sourceRaw": self.sourceRaw,
            "assumption": self.assumption, "options": self.options,
            "resolved": False, "resolution": None, "resolvedAt": None,
        }


def _num(v):
    """Emit 5 rather than 5.0, but keep 5.5. Keeps the JSON readable."""
    if v is None:
        return None
    if isinstance(v, float) and v.is_integer():
        return int(v)
    return v


# ===========================================================================
# Catalog lookup
# ===========================================================================

BY_ID = {e[0]: {"id": e[0], "display": e[1], "names": e[2], "metric": e[3], "load": e[4]}
         for e in cfg.EXERCISES}
ALIAS = {}
for _e in cfg.EXERCISES:
    for _n in _e[2]:
        ALIAS[_n] = _e[0]


def era_for(d: str) -> str:
    for e in cfg.ERAS:
        if d >= e["from"] and (e["to"] is None or d <= e["to"]):
            return e["id"]
    return cfg.ERAS[-1]["id"]


def load_kind_for(ex_id: str, d: str) -> str:
    kind = BY_ID[ex_id]["load"]
    for ov in cfg.LOAD_OVERRIDES:
        if ov["exercise"] == ex_id and d >= ov["from"]:
            kind = ov["load"]
    return kind


def target_for(ex_id: str, d: str) -> tuple[int, float | None]:
    return cfg.PROGRAM.get((era_for(d), ex_id), (3, None))


# ===========================================================================
# Lexer
# ===========================================================================

DATE_RE = re.compile(r"^(\d{1,2})/(\d{1,2})/(\d{2})\s*(.*)$")


class ParseError(Exception):
    pass


def split_tokens(s: str) -> list[str]:
    """Split on whitespace, but never inside parentheses."""
    toks, buf, depth = [], [], 0
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch.isspace() and depth == 0:
            if buf:
                toks.append("".join(buf))
                buf = []
        else:
            buf.append(ch)
    if buf:
        toks.append("".join(buf))
    return toks


def split_slash(s: str) -> list[str]:
    """Split on '/' but never inside parentheses: '145(-1/-2)' is ONE group."""
    out, buf, depth = [], [], 0
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        if ch == "/" and depth == 0:
            out.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    out.append("".join(buf))
    return out


NUM = r"\d+(?:\.\d+)?"
SHORT = rf"(?:-?{NUM}|[Xx])"
SLIST = rf"{SHORT}(?:/{SHORT})*"
WGROUP = rf"{NUM}(?:\({SLIST}\))?"
WGROUPS = rf"{WGROUP}(?:/{WGROUP})*"

RE_WGROUPS = re.compile(rf"^{WGROUPS}$")
RE_WRANGE = re.compile(rf"^({NUM})-({NUM})$")
RE_XSPEC = re.compile(rf"^(?:x({NUM})|({NUM})x({NUM}))$", re.I)
RE_PAREN = re.compile(r"^\((.*)\)$", re.S)
RE_WGROUP_ONE = re.compile(rf"^({NUM})(?:\(({SLIST})\))?$")


def parse_short(tok: str) -> tuple[float | None, bool]:
    """A shortfall element. Returns (delta, failed). X means the set died."""
    if tok in ("X", "x"):
        return None, True
    return float(tok), False


def parse_slist(s: str) -> list[tuple[float | None, bool]]:
    return [parse_short(t) for t in s.split("/")]


def is_count_spec(tok: str, n_wgroups: int) -> bool:
    """
    Distinguish "(4/1)" (set counts) from "(-1/-2)" (rep shortfalls).
    Counts are all bare positive integers and there is one per weight group.
    """
    body = tok
    m = RE_PAREN.match(tok)
    if m:
        body = m.group(1)
    if not body or any(c in body for c in "-Xx"):
        return False
    parts = body.split("/")
    if not all(re.fullmatch(NUM, p) for p in parts):
        return False
    return len(parts) == n_wgroups or n_wgroups == 1


# ===========================================================================
# Payload parsing
# ===========================================================================

@dataclass
class ParsedPayload:
    """Intermediate result: enough to build sets, plus what was ambiguous."""
    weights: list[float] = field(default_factory=list)       # per weight group
    inline_short: list[list] = field(default_factory=list)   # per weight group
    counts: list[int] | None = None                          # sets per group
    count_short: list[list] = field(default_factory=list)    # per count group
    tail: list | None = None                                 # trailing shortfalls
    tail_positional: bool = False
    n_sets_override: int | None = None
    reps_override: float | None = None
    bw_reps: list | None = None                              # bodyweight rep list
    duration: float | None = None
    performed: bool = True
    failure_reason: str | None = None
    note: str = ""
    flags: list[tuple[str, str]] = field(default_factory=list)
    attribution: str = "uniform"
    ramp: tuple[float, float] | None = None


def parse_payload(ex_id: str, payload: str, metric: str) -> ParsedPayload:
    p = ParsedPayload()
    payload = payload.strip()
    low = payload.lower()

    # --- time -------------------------------------------------------------
    if metric == "time":
        if low in ("no", "x"):
            p.performed = False
            return p
        m = re.match(rf"^({NUM})\b(.*)$", payload)
        if not m:
            raise ParseError(f"unparsed time payload: {payload!r}")
        p.duration = float(m.group(1))
        p.note = m.group(2).strip()
        return p

    # --- whole-exercise failure ------------------------------------------
    if low == "x" or low.startswith("x "):
        p.performed = False
        rest = payload[1:].strip()
        p.failure_reason = _normalise_reason(rest) if rest else None
        return p

    # --- performed but unquantified --------------------------------------
    if low.startswith("yes"):
        p.performed = True
        m = re.search(r"\((.*)\)", payload)
        p.note = m.group(1).strip() if m else ""
        p.flags.append(("UNQUANTIFIED_ENTRY", "Performed but no sets or reps recorded."))
        return p

    toks = split_tokens(payload)
    if not toks:
        raise ParseError("empty payload")

    # --- bodyweight rep lists --------------------------------------------
    if metric == "reps":
        return _parse_bodyweight(p, toks, payload)

    return _parse_weight(p, toks, payload)


def _parse_bodyweight(p: ParsedPayload, toks: list[str], payload: str) -> ParsedPayload:
    first = toks[0]
    parts = first.split("/")
    if not all(re.fullmatch(rf"{NUM}|[Xx]", x) for x in parts):
        raise ParseError(f"unparsed bodyweight payload: {payload!r}")

    reps = []
    for x in parts:
        if x in ("X", "x"):
            reps.append((0.0, True))
        else:
            reps.append((float(x), False))

    if len(reps) == 1:
        # "Dips 12" -> three sets of 12 (RULES.bw_single_number)
        p.bw_reps = reps
        p.attribution = "uniform"
    else:
        p.bw_reps = reps
        p.attribution = "positional"

    rest = " ".join(toks[1:]).strip()
    if rest:
        r = _normalise_reason(rest)
        if r in cfg.FAILURE_REASONS or r in cfg.SPELLING_FIXES.values():
            p.failure_reason = r
        else:
            p.note = rest.strip("()")
    return p


def _parse_weight(p: ParsedPayload, toks: list[str], payload: str) -> ParsedPayload:
    i = 0
    first = toks[i]

    # ramp: "135-140"
    m = RE_WRANGE.match(first)
    if m:
        lo, hi = float(m.group(1)), float(m.group(2))
        p.ramp = (lo, hi)
        p.weights = [lo]
        p.inline_short = [[]]
        p.attribution = "positional"
        p.flags.append(("UNKNOWN_NOTATION",
                        f"Weight range {first!r} read as a ramp across the sets."))
        i += 1
    elif RE_WGROUPS.match(first):
        for g in split_slash(first):
            gm = RE_WGROUP_ONE.match(g)
            if not gm:
                raise ParseError(f"bad weight group {g!r}")
            p.weights.append(float(gm.group(1)))
            p.inline_short.append(parse_slist(gm.group(2)) if gm.group(2) else [])
        i += 1
    else:
        raise ParseError(f"unparsed weight spec: {first!r}")

    # bare "X" right after a weight: "Feet up 155 X"
    if i < len(toks) and toks[i].lower() == "x":
        p.performed = False
        rest = " ".join(toks[i + 1:]).strip()
        p.failure_reason = _normalise_reason(rest) if rest else None
        return p

    # counts: "3/2" or "(4/1)"
    if i < len(toks) and is_count_spec(toks[i], len(p.weights)):
        body = toks[i]
        pm = RE_PAREN.match(body)
        if pm:
            body = pm.group(1)
        p.counts = [int(float(c)) for c in body.split("/")]
        p.count_short = [[] for _ in p.counts]
        i += 1
    elif i < len(toks) and RE_WGROUPS.match(toks[i]) and "(" in toks[i]:
        # counts with inline shortfalls: "3(-1)/2(-1/-1)"
        groups = split_slash(toks[i])
        counts, shorts = [], []
        ok = True
        for g in groups:
            gm = RE_WGROUP_ONE.match(g)
            if not gm:
                ok = False
                break
            counts.append(int(float(gm.group(1))))
            shorts.append(parse_slist(gm.group(2)) if gm.group(2) else [])
        if ok and (len(counts) == len(p.weights) or len(p.weights) == 1):
            p.counts, p.count_short = counts, shorts
            i += 1

    # xspec: "x3" or "3x5"
    if i < len(toks):
        xm = RE_XSPEC.match(toks[i])
        if xm:
            if xm.group(1):
                p.n_sets_override = int(float(xm.group(1)))
            else:
                p.n_sets_override = int(float(xm.group(2)))
                p.reps_override = float(xm.group(3))
            i += 1

    # tail group and trailing words
    words = []
    while i < len(toks):
        t = toks[i]
        pm = RE_PAREN.match(t)
        if pm:
            body = pm.group(1).strip()
            _parse_tail(p, body)
        else:
            words.append(t)
        i += 1

    if words:
        rest = " ".join(words)
        r = _normalise_reason(rest)
        if r in cfg.FAILURE_REASONS or r in cfg.SPELLING_FIXES.values():
            p.failure_reason = r
        else:
            p.note = (p.note + " " + rest).strip()
    return p


def _parse_tail(p: ParsedPayload, body: str):
    low = body.lower()
    if low == "all":
        p.tail = []
        p.attribution = "uniform"
        return
    if low == "||":
        p.flags.append(("UNKNOWN_NOTATION", "The marker (||) has no known meaning."))
        return
    # "(-2 back)" : shortfalls plus a reason
    mm = re.match(rf"^({SLIST})\s+(.*)$", body)
    if mm:
        p.tail = parse_slist(mm.group(1))
        p.failure_reason = _normalise_reason(mm.group(2))
        p.tail_positional = "0" in mm.group(1).split("/") or "-0" in mm.group(1).split("/")
        return
    if re.fullmatch(SLIST, body):
        p.tail = parse_slist(body)
        parts = body.split("/")
        p.tail_positional = any(x in ("0", "-0") for x in parts)
        return
    # bare reason or free note
    r = _normalise_reason(body)
    if r in cfg.FAILURE_REASONS or r in cfg.SPELLING_FIXES.values():
        p.failure_reason = r
    else:
        p.note = (p.note + " " + body).strip()


def _normalise_reason(s: str) -> str:
    s = s.strip().strip("()").lower()
    return cfg.SPELLING_FIXES.get(s, s)


# ===========================================================================
# Set construction
# ===========================================================================

def build_sets(ex_id: str, d: str, p: ParsedPayload) -> tuple[list[Set], str, int, str]:
    """Returns (sets, attribution, n_sets, confidence)."""
    metric = BY_ID[ex_id]["metric"]
    kind = load_kind_for(ex_id, d)
    unit = cfg.SETTINGS["unit"]
    prog_sets, prog_reps = target_for(ex_id, d)
    confidence = "certain" if ex_id in cfg.PROVEN_SET_COUNTS else "assumed"

    if metric == "time":
        if not p.performed:
            return [], "uniform", prog_sets, confidence
        s = Set(i=1, load=Load("none"), durationSec=p.duration,
                targetDurationSec=None, completed=True)
        return [s], "uniform", 1, "certain"

    if not p.performed:
        return [], "uniform", prog_sets, confidence

    if metric == "reps":
        reps = p.bw_reps or []
        if len(reps) == 1 and cfg.RULES["bw_single_number"] == "n_sets_of_x":
            reps = reps * prog_sets
        out = []
        for idx, (r, failed) in enumerate(reps, 1):
            out.append(Set(i=idx, load=Load("bodyweight"), reps=r, targetReps=None,
                           completed=not failed, failed=failed,
                           failureReason=p.failure_reason if failed else None))
        return out, p.attribution, len(out), "certain"

    # ---- weight/reps ----
    # Performed but unquantified ("Row yes"). Record that it happened; invent nothing.
    if not p.weights:
        return [], "uniform", prog_sets, "unknown"

    target = p.reps_override if p.reps_override is not None else prog_reps
    if target is None:
        target = 5.0

    # how many sets
    if p.counts:
        n = sum(p.counts)
    elif p.n_sets_override is not None:
        n = p.n_sets_override
    else:
        n = prog_sets

    # The notation can imply more sets than the program prescribes: "Dead 225/235"
    # is two weights in a StrongLifts era where deadlift is 1x5. Trust the source.
    if len(p.weights) > n:
        p.flags.append(("SET_COUNT_MISMATCH",
                        f"The line lists {len(p.weights)} weights but the program "
                        f"prescribes {n} set(s) here; imported as {len(p.weights)} sets."))
        n = len(p.weights)

    # weight per set
    weights: list[float] = []
    short_by_set: dict[int, tuple[float | None, bool]] = {}
    attribution = p.attribution

    if p.ramp:
        lo, hi = p.ramp
        step = (hi - lo) / max(1, n - 1)
        inc = cfg.SETTINGS["roundingIncrement"]
        weights = [round((lo + step * k) / inc) * inc for k in range(n)]
        attribution = "positional"
    elif p.counts and len(p.counts) == len(p.weights):
        idx = 0
        for gi, c in enumerate(p.counts):
            for k in range(c):
                weights.append(p.weights[gi])
                for si, (delta, failed) in enumerate(p.count_short[gi] or []):
                    pass
                idx += 1
        attribution = "positional" if len(p.weights) > 1 else "uniform"
        # inline shortfalls on count groups
        base = 0
        for gi, c in enumerate(p.counts):
            sl = p.count_short[gi] or []
            if len(sl) == 1:
                for k in range(c):
                    short_by_set[base + k] = sl[0]
            else:
                for k, v in enumerate(sl[:c]):
                    short_by_set[base + k] = v
            base += c
    elif len(p.weights) == 1:
        weights = [p.weights[0]] * n
        attribution = "uniform"
    else:
        # multiple weights, no counts: distribute evenly, first group gets extra
        g = len(p.weights)
        base, extra = divmod(n, g)
        for gi, w in enumerate(p.weights):
            weights.extend([w] * (base + (1 if gi < extra else 0)))
        attribution = "unordered"
        p.flags.append(("UNKNOWN_NOTATION",
                        f"Weights {'/'.join(str(_num(w)) for w in p.weights)} with no "
                        f"set counts; split {base + (1 if extra else 0)}/{base} by convention."))

    while len(weights) < n:
        weights.append(weights[-1] if weights else 0.0)
    weights = weights[:n]

    # inline shortfalls attached to weight groups
    if p.counts and len(p.counts) == len(p.weights):
        base = 0
        for gi, c in enumerate(p.counts):
            sl = p.inline_short[gi] or []
            if len(sl) == 1:
                for k in range(c):
                    short_by_set.setdefault(base + k, sl[0])
            else:
                for k, v in enumerate(sl[:c]):
                    short_by_set.setdefault(base + k, v)
            base += c
    elif len(p.weights) == 1 and p.inline_short and p.inline_short[0]:
        sl = p.inline_short[0]
        if len(sl) == 1:
            for k in range(n):
                short_by_set.setdefault(k, sl[0])
        else:
            start = n - len(sl)
            for k, v in enumerate(sl):
                short_by_set.setdefault(start + k, v)
            attribution = "unordered"

    # tail shortfalls
    if p.tail:
        sl = p.tail
        if p.tail_positional or len(sl) == n:
            for k, v in enumerate(sl[:n]):
                short_by_set[k] = v
            attribution = "positional"
        else:
            start = max(0, n - len(sl))
            for k, v in enumerate(sl):
                if start + k < n:
                    short_by_set[start + k] = v
            attribution = "unordered"

    out = []
    for k in range(n):
        delta, failed = short_by_set.get(k, (0.0, False))
        if failed:
            reps, completed = 0.0, False
        else:
            reps = max(0.0, target + (delta or 0.0))
            completed = reps >= target
        out.append(Set(i=k + 1, load=Load(kind, weights[k], unit),
                       reps=reps, targetReps=target,
                       completed=completed, failed=failed,
                       failureReason=p.failure_reason if failed else None))
    return out, attribution, n, confidence


# ===========================================================================
# File parsing
# ===========================================================================

def parse_file(path: Path):
    raw = path.read_text()
    lines = raw.split("\n")
    sessions: list[Session] = []
    reviews: list[ReviewItem] = []
    unparsed: list[tuple[int, str, str]] = []
    blank_lines: set[int] = set()
    seen_dates: dict[str, int] = defaultdict(int)
    rid = [0]

    def new_review(code, sev, scope, title, detail, lines_, raw_, assumption="", options=None):
        rid[0] += 1
        reviews.append(ReviewItem(f"rv-{rid[0]:03d}", code, sev, scope, title, detail,
                                  lines_, raw_, assumption, options or []))

    cur: Session | None = None
    order = 0

    for n, text in enumerate(lines, 1):
        stripped = text.strip()
        if not stripped:
            blank_lines.add(n)
            continue

        dm = DATE_RE.match(stripped)
        if dm:
            mo, da, yy, rest = int(dm.group(1)), int(dm.group(2)), int(dm.group(3)), dm.group(4).strip()
            iso = cfg.DATE_FIXES.get(n)
            orig_iso = f"20{yy:02d}-{mo:02d}-{da:02d}"
            if iso:
                new_review("DATE_YEAR_TYPO" if orig_iso[:4] != iso[:4] else "DATE_OUT_OF_SEQUENCE",
                           "error", "session",
                           f"Date corrected: {stripped}",
                           f"Imported as {iso}. The original read {orig_iso}, which is out of "
                           f"sequence with the surrounding entries.",
                           [n], stripped, f"Imported as {iso}.",
                           [{"id": "accept", "label": f"Use {iso}", "primary": True},
                            {"id": "revert", "label": f"Keep {orig_iso}"}])
            else:
                iso = orig_iso

            label, note = None, ""
            if rest:
                parts = rest.split(None, 1)
                if parts[0] in ("A", "B", "C"):
                    label = parts[0]
                    note = parts[1].strip() if len(parts) > 1 else ""
                else:
                    note = rest

            seen_dates[iso] += 1
            sid = f"s-{iso}-{seen_dates[iso]}"
            cur = Session(id=sid, date=iso, routineId=era_for(iso),
                          dayLabel=label, notes=note, srcLine=n, srcRaw=stripped)
            sessions.append(cur)
            order = 0
            continue

        if cur is None:
            unparsed.append((n, text, "exercise line before any date line"))
            continue

        order += 1
        try:
            entry = parse_exercise_line(n, text, cur, order)
            cur.entries.append(entry)
            for code, msg in entry_flags.get(id(entry), []):
                new_review(code, "warn", "entry",
                           f"{BY_ID[entry.exerciseId]['display']} on {cur.date}",
                           msg, [n], text.strip(), "")
                entry.needsReview = True
        except ParseError as exc:
            truncated = text.rstrip().endswith("/") or text.count("(") != text.count(")")
            code = "TRUNCATED_LINE" if truncated else "UNPARSED_LINE"
            unparsed.append((n, text, str(exc)))
            ex_id = resolve_name(text)
            pr_sets, pr_reps = target_for(ex_id, cur.date) if ex_id else (0, None)
            e = Entry(exerciseId=ex_id or "unknown", order=order,
                      prescription=Prescription(pr_sets, pr_reps,
                                                load_kind_for(ex_id, cur.date) if ex_id else "none",
                                                None, cfg.SETTINGS["unit"],
                                                confidence="unknown"),
                      sets=[], performed=True, needsReview=True,
                      excludeFromPr=True, srcLine=n, srcRaw=text.strip())
            cur.entries.append(e)
            cur.needsReview = True
            new_review(code, "error", "entry",
                       "Line is cut off" if truncated else "Line could not be read",
                       f"{'The line appears truncated in the source file.' if truncated else str(exc)} "
                       f"Imported with no set data so it cannot distort any totals.",
                       [n], text.strip(), "Imported with 0 sets.",
                       [{"id": "edit", "label": "Enter what you did", "primary": True},
                        {"id": "unknown", "label": "Leave it unknown"}])

    return raw, lines, sessions, reviews, unparsed, blank_lines


entry_flags: dict[int, list] = {}


def resolve_name(text: str) -> str | None:
    s = re.sub(r"\s+", " ", text.strip())
    m = re.match(r"^([A-Za-z][A-Za-z \-]*?)(?=\s+\S*\d|\s+[Xx]\b|\s+yes\b|\s+no\b|$)", s)
    if not m:
        return None
    return ALIAS.get(m.group(1).strip().lower())


def parse_exercise_line(n: int, text: str, sess: Session, order: int) -> Entry:
    s = re.sub(r"\s+", " ", text.strip())
    ex_id = resolve_name(s)
    if ex_id is None:
        raise ParseError(f"unknown exercise name in {s!r}")

    name_len = 0
    for nm in sorted(BY_ID[ex_id]["names"], key=len, reverse=True):
        if s.lower().startswith(nm):
            name_len = len(nm)
            break
    payload = s[name_len:].strip()
    if not payload:
        raise ParseError("no payload")

    # apply VALUE_FIXES
    if n in cfg.VALUE_FIXES:
        f = cfg.VALUE_FIXES[n]
        payload = payload.replace(str(f["from"]), str(f["to"]), 1)

    # A truncated line must NOT be half-parsed: dropping a trailing "(-3/" would
    # silently record a clean session that never happened.
    if payload.rstrip().endswith("/") or payload.count("(") != payload.count(")"):
        raise ParseError("line appears truncated in the source file")

    metric = BY_ID[ex_id]["metric"]
    p = parse_payload(ex_id, payload, metric)
    sets, attribution, n_sets, confidence = build_sets(ex_id, sess.date, p)

    kind = load_kind_for(ex_id, sess.date)
    top = max((x.load.value for x in sets if x.load.value is not None), default=None)
    prog_sets, prog_reps = target_for(ex_id, sess.date)

    e = Entry(
        exerciseId=ex_id, order=order,
        prescription=Prescription(
            sets=n_sets if sets else prog_sets,
            reps=p.reps_override if p.reps_override is not None else prog_reps,
            loadKind=kind, load=top, unit=cfg.SETTINGS["unit"],
            confidence=confidence),
        sets=sets, setsAttribution=attribution,
        performed=p.performed, failureReason=p.failure_reason,
        notes=p.note.strip(), progSets=prog_sets, srcLine=n, srcRaw=text.strip(),
    )
    if p.flags:
        entry_flags[id(e)] = p.flags
    return e


# ===========================================================================
# Track inference
# ===========================================================================

def infer_tracks(sessions: list[Session], reviews: list[ReviewItem]):
    """
    Split a lift's weight series into light/heavy tracks where the data shows
    two interleaved progressions. Deloads are contiguous runs; tracks alternate,
    which is the primary discriminator.
    """
    det = cfg.TRACK_DETECT
    results = {}
    if not det.get("enabled", True):
        return results

    series = defaultdict(list)   # (ex_id, dayLabel) -> [(date, top, entry)]
    for s in sessions:
        for e in s.entries:
            if e.exerciseId not in cfg.TRACKED_LIFTS or not e.sets:
                continue
            top = max((x.load.value for x in e.sets if x.load.value is not None), default=None)
            if top is None:
                continue
            series[(e.exerciseId, s.dayLabel)].append((s.date, top, e))

    for (ex_id, day), pts in sorted(series.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
        pts.sort(key=lambda t: t[0])
        clean = [t for t in pts
                 if not (det["exclude_injury_sessions"] and
                         (t[2].failureReason or any(x.failed for x in t[2].sets)))]
        if len(clean) < det["window"]:
            continue

        best, run, first_idx = None, 0, None
        for i in range(len(clean) - det["window"] + 1):
            win = [t[1] for t in clean[i:i + det["window"]]]
            g = _gate(win, det)
            if g:
                run += 1
                if first_idx is None:
                    first_idx = i
                if best is None or g["gap_rel"] > best[0]["gap_rel"]:
                    best = (g, i)
            else:
                if run >= det["min_run"]:
                    break
                run, first_idx, best = 0, None, None

        if run < det["min_run"] or best is None:
            continue

        g, _ = best
        boundary = g["boundary"]

        # PERSISTENCE GATE. A steady linear climb also shows a gap and local
        # zig-zag, but its "light" cluster stops appearing once the lift moves
        # past it. Real two-track programming keeps using BOTH to the end.
        tail = [top for d, top, _ in pts if d >= clean[first_idx][0]][-10:]
        if tail:
            share = min(sum(1 for v in tail if v >= boundary),
                        sum(1 for v in tail if v < boundary)) / len(tail)
            if share < det["min_balance"]:
                continue
        half = (g["heavy"] - g["light"]) / 2.0

        # Two-track programming starts at the first REVERSAL - the first session
        # that drops back below the boundary after one above it. The first passing
        # window starts earlier than that, because a window only needs the pattern
        # somewhere inside it.
        tracked_from = clean[first_idx][0]
        seen_heavy = False
        for d, top in [(d, t) for d, t, _ in pts]:
            if top >= boundary:
                seen_heavy = True
            elif seen_heavy:
                tracked_from = d
                break

        low_conf = []
        for d, top, e in pts:
            if d < tracked_from:
                e.track = None
                e.trackOrigin = "single_track_era"
                continue
            e.track = "heavy" if top >= boundary else "light"
            e.trackOrigin = "inferred"
            margin = abs(top - boundary)
            if margin >= 0.50 * half:
                e.trackConfidence = "high"
            elif margin >= 0.25 * half:
                e.trackConfidence = "medium"
            else:
                e.trackConfidence = "low"
                low_conf.append((d, top, e))

        results[(ex_id, day)] = {
            "tracked_from": tracked_from, "boundary": boundary,
            "light": g["light"], "heavy": g["heavy"], "half_sep": half,
            "n": sum(1 for d, _, _ in pts if d >= tracked_from),
            "low": low_conf, "gap_rel": g["gap_rel"], "alt": g["alt"],
        }

        rid = len(reviews) + 1
        reviews.append(ReviewItem(
            f"rv-{rid:03d}", "TRACK_TRANSITION", "warn", "exercise",
            f"{BY_ID[ex_id]['display']}: light/heavy split detected",
            f"Through {tracked_from} this lift climbed as a single progression. From "
            f"{tracked_from} it separates into two: light around {_num(round(g['light'], 1))} lb and "
            f"heavy around {_num(round(g['heavy'], 1))} lb. I will track records separately so light "
            f"days do not read as regressions.",
            [], "", f"Split at {tracked_from}; boundary {_num(boundary)} lb.",
            [{"id": "accept", "label": f"Correct - split at {tracked_from}", "primary": True},
             {"id": "other", "label": "Split somewhere else"},
             {"id": "none", "label": "Not two tracks"}]))

        for d, top, e in low_conf:
            rid = len(reviews) + 1
            reviews.append(ReviewItem(
                f"rv-{rid:03d}", "TRACK_AMBIGUOUS", "warn", "entry",
                f"{BY_ID[ex_id]['display']} {_num(top)} lb on {d} - which track?",
                f"This sits {_num(abs(top - boundary))} lb from the {_num(boundary)} lb "
                f"boundary, inside the transition zone. It could be the tail of the old "
                f"single progression rather than a {e.track} day.",
                [e.srcLine], e.srcRaw, f"Imported as {e.track}.",
                [{"id": "heavy", "label": "Heavy"}, {"id": "light", "label": "Light"},
                 {"id": "none", "label": "Still single-track", "primary": True}]))
            e.needsReview = True

    return results


def post_validate(sessions, reviews):
    """Checks that need the whole history, not one line: sudden load-scale changes
    (equipment swaps) and single-session outliers that would claim a false PR."""
    rid = [len(reviews)]

    def add(code, sev, scope, title, detail, lines_, raw_, assumption, options):
        rid[0] += 1
        reviews.append(ReviewItem(f"rv-{rid[0]:03d}", code, sev, scope, title,
                                  detail, lines_, raw_, assumption, options))

    series = defaultdict(list)
    for s in sessions:
        for e in s.entries:
            top = max((x.load.value for x in e.sets if x.load.value is not None), default=None)
            if top is not None:
                series[e.exerciseId].append((s.date, top, e))

    for ex_id, pts in sorted(series.items()):
        pts.sort(key=lambda t: t[0])
        vals = [v for _, v, _ in pts]

        # Equipment swap: a sustained jump in the scale of the numbers.
        for i in range(1, len(pts)):
            prev, cur = vals[i - 1], vals[i]
            if prev <= 0 or cur <= 0:
                continue
            ratio = max(prev, cur) / min(prev, cur)
            after = vals[i:i + 3]
            if ratio >= 3.0 and len(after) >= 3 and all(
                    max(v, cur) / max(min(v, cur), 0.01) < 1.6 for v in after):
                add("LOAD_SEMANTICS_CHANGE", "warn", "exercise",
                    f"{BY_ID[ex_id]['display']}: the numbers change scale on {pts[i][0]}",
                    f"{_num(prev)} on {pts[i-1][0]} then {_num(cur)} on {pts[i][0]}, and it "
                    f"stays at the new scale. That is an equipment change, not a "
                    f"{ratio:.1f}x strength drop. Imported as "
                    f"{load_kind_for(ex_id, pts[i][0])} from this date.",
                    [pts[i][2].srcLine], pts[i][2].srcRaw,
                    "Load kind switched per program_config.LOAD_OVERRIDES.",
                    [{"id": "accept", "label": "Correct", "primary": True},
                     {"id": "date", "label": "Different date"},
                     {"id": "no", "label": "Not an equipment change"}])
                break

        # One-off outliers: would otherwise claim a false personal record.
        for i, (d, v, e) in enumerate(pts):
            window = [x for _, x, _ in pts[max(0, i - 3):i]]
            if len(window) < 3:
                continue
            med = sorted(window)[len(window) // 2]
            if med > 0 and v / med >= 2.0:
                e.excludeFromPr = True
                e.needsReview = True
                add("WEIGHT_OUTLIER", "warn", "entry",
                    f"{BY_ID[ex_id]['display']} {_num(v)} on {d} is well outside its range",
                    f"The three sessions before this averaged around {_num(med)}. "
                    f"Kept out of record calculations until you confirm it.",
                    [e.srcLine], e.srcRaw, "Imported as written, excluded from PRs.",
                    [{"id": "ok", "label": "It is correct", "primary": True},
                     {"id": "other", "label": "Different exercise or equipment"},
                     {"id": "typo", "label": "It is a typo"}])


def _gate(win: list[float], det) -> dict | None:
    vals = sorted(set(win))
    if len(vals) < 2:
        return None
    gaps = sorted(((vals[i + 1] - vals[i], i) for i in range(len(vals) - 1)), reverse=True)
    gap, gi = gaps[0]
    second = gaps[1][0] if len(gaps) > 1 else 0.001
    boundary = (vals[gi] + vals[gi + 1]) / 2.0
    lo = [v for v in win if v < boundary]
    hi = [v for v in win if v >= boundary]
    if not lo or not hi:
        return None
    med = sorted(win)[len(win) // 2]
    alt = sum(1 for i in range(len(win) - 1)
              if (win[i] < boundary) != (win[i + 1] < boundary)) / (len(win) - 1)
    balance = min(len(lo), len(hi)) / len(win)
    if (gap < det["min_gap_abs"] or gap / max(second, 0.001) < det["min_gap_ratio"]
            or gap / med < det["min_gap_rel"] or alt < det["min_alternation"]
            or balance < det["min_balance"]):
        return None
    return {"boundary": boundary, "light": sum(lo) / len(lo), "heavy": sum(hi) / len(hi),
            "gap_rel": gap / med, "alt": alt}


# ===========================================================================
# Emitter: model -> canonical terse notation
#
# Reads ONLY the parsed model, never source.raw. That is what makes the
# round-trip check meaningful rather than circular.
# ===========================================================================

def emit_entry(e: Entry) -> str:
    name = BY_ID[e.exerciseId]["display"]
    metric = BY_ID[e.exerciseId]["metric"]

    if not e.performed:
        if metric == "time":
            return f"{name} no"
        base = f"{name} {_num(e.prescription.load)} X" if e.prescription.load else f"{name} X"
        return base + (f" {e.failureReason}" if e.failureReason else "")

    if not e.sets:
        return f"{name} yes" + (f" ({e.notes})" if e.notes else "")

    if metric == "time":
        return f"{name} {_num(e.sets[0].durationSec)}"

    if metric == "reps":
        parts = ["X" if x.failed else str(_num(x.reps)) for x in e.sets]
        out = f"{name} {'/'.join(parts)}"
        return out + (f" {e.failureReason}" if e.failureReason else "")

    # weight/reps: collapse consecutive equal loads into groups
    groups: list[list] = []
    for x in e.sets:
        if groups and groups[-1][0] == x.load.value:
            groups[-1][1].append(x)
        else:
            groups.append([x.load.value, [x]])

    out = f"{name} " + "/".join(str(_num(g[0])) for g in groups)
    if len(groups) > 1:
        out += " " + "/".join(str(len(g[1])) for g in groups)
    elif e.progSets and len(e.sets) != e.progSets:
        out += f" x{len(e.sets)}"        # non-default set count must survive the round trip

    devs = []
    for x in e.sets:
        if x.failed:
            devs.append("X")
        elif x.targetReps is not None and x.reps != x.targetReps:
            devs.append(str(_num(x.reps - x.targetReps)))
        else:
            devs.append(None)

    if any(d is not None for d in devs):
        # A sparse list is re-read as applying to the TRAILING sets, so it may only
        # be emitted when the deviations really are trailing. Otherwise write the
        # full positional list, zeros included - that is what makes it unambiguous.
        idxs = [i for i, d in enumerate(devs) if d is not None]
        trailing = idxs == list(range(len(devs) - len(idxs), len(devs)))
        if trailing and e.setsAttribution == "unordered":
            lst = [devs[i] for i in idxs]
        else:
            lst = [d if d is not None else "0" for d in devs]
        if lst:
            out += f" ({'/'.join(lst)})"

    if e.failureReason:
        out += f" {e.failureReason}"
    elif e.notes:
        out += f" {e.notes}"
    return out


def emit_session(s: Session) -> list[str]:
    d = datetime.strptime(s.date, "%Y-%m-%d").date()
    head = f"{d.month}/{d.day}/{d.strftime('%y')}"
    if s.dayLabel:
        head += f" {s.dayLabel}"
    if s.notes:
        head += f" {s.notes}"
    return [head] + [emit_entry(e) for e in s.entries]


def emit_all(sessions: list[Session]) -> list[str]:
    out: list[str] = []
    for i, s in enumerate(sessions):
        if i:
            out.append("")
        out.extend(emit_session(s))
    return out


# ===========================================================================
# Validation
# ===========================================================================

def projection(sessions):
    """Canonical comparable view. Excludes track: it is inferred, not recorded,
    so re-parsing emitted text can never reproduce it."""
    out = []
    for s in sessions:
        ents = []
        for e in s.entries:
            ents.append((
                e.exerciseId, e.performed, e.failureReason,
                tuple((x.load.kind, x.load.value, _num(x.reps), _num(x.durationSec),
                       x.completed, x.failed) for x in e.sets),
            ))
        out.append((s.date, s.dayLabel, tuple(ents)))
    return out


def validate(raw, lines, sessions, unparsed, blanks, tmpdir: Path):
    results = []
    ok = True

    # --- Layer 1: line bijection ---------------------------------------
    sess_lines = {s.srcLine for s in sessions}
    entry_lines = {e.srcLine for s in sessions for e in s.entries}
    total = len(lines)
    accounted = sess_lines | entry_lines | blanks
    expected = set(range(1, total + 1))
    missing = expected - accounted
    dupes = len(sess_lines) + len(entry_lines) - len(sess_lines | entry_lines)
    l1 = (not missing) and dupes == 0 and len(sess_lines) == len(sessions)
    results.append(("L1 line bijection",
                    f"{len(sess_lines)} session + {len(entry_lines)} entry + {len(blanks)} blank "
                    f"= {len(accounted)}/{total}"
                    + (f"  MISSING {sorted(missing)[:8]}" if missing else ""), l1))
    ok &= l1

    # --- Layer 2: semantic round trip ----------------------------------
    emitted = emit_all(sessions)
    import tempfile
    tmp = Path(tempfile.mkdtemp()) / "_roundtrip.txt"
    tmp.write_text("\n".join(emitted) + "\n")
    _, _, s2, _, unp2, _ = parse_file(tmp)
    a, b = projection(sessions), projection(s2)
    diffs = [i for i, (x, y) in enumerate(zip(a, b)) if x != y]
    l2 = len(a) == len(b) and not diffs
    results.append(("L2 semantic round trip",
                    f"{len(a) - len(diffs)}/{len(a)} sessions identical"
                    + (f"  first differing: {a[diffs[0]][0]}" if diffs else ""), l2))
    ok &= l2

    # --- Layer 3: literal diff, classified -----------------------------
    import difflib
    orig = [ln.rstrip() for ln in raw.split("\n")]
    while orig and not orig[-1]:
        orig.pop()
    emit = [ln.rstrip() for ln in emitted]
    sm = difflib.SequenceMatcher(None, orig, emit, autojunk=False)
    hunks = [(t, i1, orig[i1:i2], emit[j1:j2])
             for t, i1, i2, j1, j2 in sm.get_opcodes() if t != "equal"]
    line_date = {}
    for sess in sessions:
        line_date[sess.srcLine] = sess.date
        for ent in sess.entries:
            line_date[ent.srcLine] = sess.date

    classes, unclassified = defaultdict(int), []
    for t, i1, o, n in hunks:
        cls = classify_hunk(o, n, line_date.get(i1 + 1, "2026-01-01"))
        if cls:
            classes[cls] += 1
        else:
            unclassified.append((o, n))
    l3 = not unclassified
    results.append(("L3 literal diff classified",
                    f"{sum(classes.values())} hunks classified, {len(unclassified)} unclassified", l3))
    ok &= l3

    # --- Layer 4: aggregate invariants ---------------------------------
    inv = []
    n_sess, n_ent = len(sessions), sum(len(s.entries) for s in sessions)
    inv.append(("152 sessions", n_sess == 152, n_sess))
    inv.append(("743 entries", n_ent == 743, n_ent))
    inv.append(("3 unparsed (known truncations)", len(unparsed) == 3, len(unparsed)))

    off_grid = [(e.srcLine, x.load.value) for s in sessions for e in s.entries for x in e.sets
                if x.load.value is not None and round(x.load.value / 2.5, 6) % 1 != 0]
    inv.append(("all weights on the 2.5 lb grid", not off_grid, off_grid[:3]))

    bad_bw = [e.srcLine for s in sessions for e in s.entries
              if BY_ID[e.exerciseId]["metric"] == "reps"
              for x in e.sets if x.targetReps is not None]
    inv.append(("bodyweight lifts carry no rep target (AMRAP)", not bad_bw, bad_bw[:3]))

    bad_ref = [e.exerciseId for s in sessions for e in s.entries if e.exerciseId not in BY_ID]
    inv.append(("every exerciseId is in the catalog", not bad_ref, bad_ref[:3]))

    future = [s.date for s in sessions if s.date > "2027-01-01"]
    inv.append(("no absurd dates", not future, future[:3]))

    for label, good, detail in inv:
        results.append((f"L4 {label}", "" if good else f"got {detail}", good))
        ok &= good

    return ok, results, classes


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip()).lower()


def _label(a: str, b: str) -> str:
    """Name the surface change, for the report."""
    na, nb = _norm(a), _norm(b)
    if re.match(r"^\d+/\d+/\d+", na) and re.match(r"^\d+/\d+/\d+", nb):
        return "DATE_FIX"
    if re.sub(r"\s+", "", na) == re.sub(r"\s+", "", nb):
        return "WHITESPACE"
    if re.sub(r"[()\s]", "", na) == re.sub(r"[()\s]", "", nb):
        return "PAREN_OPTIONAL"
    for bad, good in cfg.SPELLING_FIXES.items():
        if bad in na and good in nb:
            return "TYPO_NORMALISED"
    if "(all)" in na or "(||)" in na:
        return "MARKER_DROPPED"
    if re.search(r"\d-\d", na) and "/" in nb:
        return "RANGE_EXPANDED"
    if re.search(r"\bx\d", na):
        return "XSPEC_NORMALISED"
    if na.replace("x", "X") == nb or na.upper() == nb.upper():
        return "CASE_NORMALISED"
    if re.search(r"\(-?[\d./X-]+\)", na) and re.search(r"\(-?[\d./X-]+\)", nb):
        return "SHORTFALL_MADE_POSITIONAL"
    return "SET_COUNTS_MADE_EXPLICIT"


def _same_meaning(a: str, b: str, when: str = "2026-01-01") -> bool:
    """Parse both surface forms under the SAME era and compare what they say.
    The era matters: deadlift is 1x5 in StrongLifts and 5x5 later."""
    probe = Session(id="p", date=when, routineId=era_for(when), dayLabel=None)
    try:
        ea = parse_exercise_line(0, a, probe, 1)
        eb = parse_exercise_line(0, b, probe, 1)
    except ParseError:
        return False
    key = lambda e: (e.exerciseId, e.performed, e.failureReason,
                     [(x.load.kind, x.load.value, _num(x.reps), _num(x.durationSec),
                       x.completed, x.failed) for x in e.sets])
    return key(ea) == key(eb)


def classify_hunk(old: list[str], new: list[str], when: str = "2026-01-01") -> str | None:
    """
    Every literal difference must be semantically empty. Proven by parsing both
    surface forms, not by matching strings - a string rule could hide a real change.
    """
    o, n = [x for x in old if x.strip()], [x for x in new if x.strip()]
    if len(o) != len(n):
        joined = " ".join(o)
        if joined.rstrip().endswith("/") or joined.count("(") != joined.count(")"):
            return "TRUNCATED"
        return None
    labels = []
    for a, b in zip(o, n):
        if _norm(a) == _norm(b):
            continue
        if a.rstrip().endswith("/") or a.count("(") != a.count(")"):
            labels.append("TRUNCATED")
            continue
        if re.match(r"^\d+/\d+/\d+", _norm(a)) and re.match(r"^\d+/\d+/\d+", _norm(b)):
            labels.append("DATE_FIX")
            continue
        if not _same_meaning(a, b, when):
            return None
        labels.append(_label(a, b))
    return labels[0] if labels else "WHITESPACE"


# ===========================================================================
# Output
# ===========================================================================

def build_document(sessions, reviews, src_path: Path, raw: str, tracks):
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    cfg_raw = (Path(__file__).parent / "program_config.py").read_bytes()

    catalog = []
    used = {e.exerciseId for s in sessions for e in s.entries}
    for ex in cfg.EXERCISES:
        ex_id = ex[0]
        if ex_id not in used:
            continue
        dates = [s.date for s in sessions for e in s.entries if e.exerciseId == ex_id]
        trk = sorted({e.track for s in sessions for e in s.entries
                      if e.exerciseId == ex_id and e.track})
        kinds = sorted({x.load.kind for s in sessions for e in s.entries
                        if e.exerciseId == ex_id for x in e.sets})
        catalog.append({
            "id": ex_id, "displayName": ex[1], "aliases": ex[2],
            "metric": ex[3], "defaultLoadKind": ex[4], "defaultUnit": cfg.SETTINGS["unit"],
            "firstSeen": min(dates), "lastSeen": max(dates),
            "active": max(dates) >= "2026-06-01",
            "loadKinds": kinds, "tracks": trk,
        })

    # Routines drive what the app prescribes, so a slot must reflect what you
    # CURRENTLY do. Taking the union of everything ever seen on a day label keeps
    # retired movements forever: Curl was replaced by B curl and Hammer by D curl
    # in Dec 2025, but both would still be prescribed today. Use recent frequency.
    routines = []
    for era in cfg.ERAS:
        days = {}
        era_sessions = [s for s in sessions if s.routineId == era["id"]]
        labels = sorted({(s.dayLabel or "-") for s in era_sessions})
        for label in labels:
            of_day = sorted([s for s in era_sessions if (s.dayLabel or "-") == label],
                            key=lambda s: s.date)
            recent = of_day[-ROUTINE_WINDOW:]
            if not recent:
                continue
            seen = defaultdict(list)          # exercise -> positions it appeared at
            for s in recent:
                for e in s.entries:
                    seen[e.exerciseId].append(e.order)
            keep = [(ex_id, sorted(pos)[len(pos) // 2])
                    for ex_id, pos in seen.items()
                    if len(pos) / len(recent) >= ROUTINE_MIN_SHARE]
            keep.sort(key=lambda t: t[1])     # median position in the session
            slots = []
            for ex_id, _ in keep:
                sets_, reps_ = target_for(ex_id, recent[-1].date)
                slots.append({
                    "exerciseId": ex_id, "sets": sets_, "reps": _num(reps_),
                    "loadKind": load_kind_for(ex_id, recent[-1].date),
                    "tracks": sorted({x.track for ss in sessions for x in ss.entries
                                      if x.exerciseId == ex_id and x.track}),
                })
            days[label] = slots
        routines.append({
            "id": era["id"], "name": era["id"],
            "activeFrom": era["from"], "activeTo": era["to"],
            "days": [{"label": k, "name": k, "slots": v} for k, v in sorted(days.items())],
        })

    return {
        "schemaVersion": SCHEMA_VERSION,
        "meta": {
            "createdAt": now, "lastModifiedAt": now,
            "appVersion": None, "generator": f"fitlog_import.py {IMPORTER_VERSION}",
            "import": {
                "importerVersion": IMPORTER_VERSION, "importedAt": now,
                "sourceFile": src_path.name,
                "sourceSha256": hashlib.sha256(raw.encode()).hexdigest(),
                "sourceLineCount": len(raw.split("\n")),
                "programConfigSha256": hashlib.sha256(cfg_raw).hexdigest(),
                "counts": {
                    "sessions": len(sessions),
                    "entries": sum(len(s.entries) for s in sessions),
                    "sets": sum(len(e.sets) for s in sessions for e in s.entries),
                    "reviewItems": len(reviews),
                },
            },
        },
        "settings": cfg.SETTINGS,
        "exercises": catalog,
        "routines": routines,
        "sessions": [s.to_json() for s in sessions],
        "review": [r.to_json() for r in reviews],
    }


def write_report(path: Path, sessions, reviews, unparsed, results, tracks, doc, classes):
    L = []
    c = doc["meta"]["import"]["counts"]
    L.append("# Import report\n")
    L.append(f"Source `text_log` sha256 `{doc['meta']['import']['sourceSha256'][:16]}...` "
             f"({doc['meta']['import']['sourceLineCount']} lines)  ")
    L.append(f"Config `program_config.py` sha256 "
             f"`{doc['meta']['import']['programConfigSha256'][:16]}...`\n")
    L.append(f"**{c['sessions']} sessions · {c['entries']} entries · {c['sets']} sets · "
             f"{len(doc['exercises'])} exercises · {len(reviews)} to review**\n")

    L.append("## Validation\n")
    L.append("| Layer | Result | |")
    L.append("|---|---|---|")
    for name, detail, good in results:
        L.append(f"| {name} | {detail or 'ok'} | {'PASS' if good else '**FAIL**'} |")
    L.append("")

    L.append("## Assumptions applied\n")
    L.append("Inputs, not facts from the log. Change them in `program_config.py` and re-run.\n")
    n_assumed = sum(1 for s in sessions for e in s.entries
                    if e.prescription.confidence == "assumed")
    n_bw1 = sum(1 for s in sessions for e in s.entries
                if BY_ID[e.exerciseId]["metric"] == "reps" and e.setsAttribution == "uniform")
    n_unord = sum(1 for s in sessions for e in s.entries if e.setsAttribution == "unordered")
    n_acc = sum(1 for s in sessions for e in s.entries if e.exerciseId in
                ("curl", "skullcrusher", "barbell-curl", "dumbbell-curl", "hammer-curl"))
    n_dl = sum(1 for s in sessions for e in s.entries
               if e.exerciseId == "deadlift" and era_for(s.date) == "stronglifts-5x5")
    L.append("| Assumption | Value | Entries |")
    L.append("|---|---|---|")
    L.append(f"| Accessory target | 3 x 8 | {n_acc} |")
    L.append(f"| Deadlift, StrongLifts era | 1 x 5 | {n_dl} |")
    L.append(f"| Bare bodyweight number means all sets | `Dips 12` = 12/12/12 | {n_bw1} |")
    L.append(f"| Sparse shortfalls placed on trailing sets | convention | {n_unord} |")
    L.append(f"| Set count not proven by the source | taken from program | {n_assumed} |")
    L.append("")

    if tracks:
        L.append("## Progression tracks\n")
        L.append("The log never records these. Every value here is inferred.\n")
        L.append("| Lift | Day | Tracked from | Light | Heavy | Boundary | Labelled | Low conf |")
        L.append("|---|---|---|---|---|---|---|---|")
        for (ex, day), t in sorted(tracks.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
            L.append(f"| {BY_ID[ex]['display']} | {day or '-'} | {t['tracked_from']} | "
                     f"{_num(round(t['light'], 1))} | {_num(round(t['heavy'], 1))} | "
                     f"{_num(t['boundary'])} | {t['n']} | {len(t['low'])} |")
        L.append("")

    if classes:
        L.append("## Round-trip diff classification\n")
        L.append("Every literal difference below was proven semantically empty by parsing "
                 "both forms and comparing the result.\n")
        L.append("| Class | Hunks |")
        L.append("|---|---|")
        for k, v in sorted(classes.items(), key=lambda kv: -kv[1]):
            L.append(f"| {k} | {v} |")
        L.append("")

    by_sev = defaultdict(list)
    for r in reviews:
        by_sev[r.severity].append(r)
    L.append(f"## Review queue ({len(reviews)})\n")
    for sev in ("error", "warn", "info"):
        if not by_sev[sev]:
            continue
        L.append(f"### {sev.upper()} ({len(by_sev[sev])})\n")
        for r in by_sev[sev]:
            loc = f"L{r.sourceLines[0]}" if r.sourceLines else "-"
            L.append(f"- **[{r.code}]** {loc} — {r.title}")
            if r.sourceRaw:
                L.append(f"  - source: `{r.sourceRaw}`")
            L.append(f"  - {r.detail}")
        L.append("")

    if unparsed:
        L.append(f"## Unparsed lines ({len(unparsed)})\n")
        for n, t, why in unparsed:
            L.append(f"- L{n} `{t.strip()}` — {why}; imported with 0 sets")
        L.append("")

    L.append("## Per-exercise summary\n")
    L.append("| Exercise | n | first | last | load kinds | min | max |")
    L.append("|---|---|---|---|---|---|---|")
    agg = defaultdict(lambda: {"n": 0, "d": [], "k": set(), "w": []})
    for s in sessions:
        for e in s.entries:
            a = agg[e.exerciseId]
            a["n"] += 1
            a["d"].append(s.date)
            for x in e.sets:
                a["k"].add(x.load.kind)
                if x.load.value is not None:
                    a["w"].append(x.load.value)
    for ex_id, a in sorted(agg.items(), key=lambda kv: -kv[1]["n"]):
        w = f"{_num(min(a['w']))} | {_num(max(a['w']))}" if a["w"] else "- | -"
        L.append(f"| {BY_ID[ex_id]['display']} | {a['n']} | {min(a['d'])} | {max(a['d'])} | "
                 f"{', '.join(sorted(a['k']))} | {w} |")

    path.write_text("\n".join(L) + "\n")


def main():
    ap = argparse.ArgumentParser(description="Import text_log into fitlog.json")
    ap.add_argument("source", type=Path)
    ap.add_argument("--out", type=Path, default=Path("../out"))
    ap.add_argument("--explain", type=int, help="dump one line's parse tree")
    ap.add_argument("--tracks", action="store_true", help="print track assignments only")
    ap.add_argument("--no-roundtrip", action="store_true")
    a = ap.parse_args()

    raw, lines, sessions, reviews, unparsed, blanks = parse_file(a.source)

    if a.explain:
        for s in sessions:
            for e in s.entries:
                if e.srcLine == a.explain:
                    print(f"line {a.explain}: {e.srcRaw!r}")
                    print(f"  session   {s.date} {s.dayLabel or ''}  era {s.routineId}")
                    print(f"  exercise  {e.exerciseId} ({BY_ID[e.exerciseId]['metric']})")
                    print(f"  presc     {e.prescription.to_json()}")
                    print(f"  attrib    {e.setsAttribution}")
                    print(f"  emitted   {emit_entry(e)!r}")
                    for x in e.sets:
                        print(f"    set {x.i}  {x.load.to_json()}  reps={_num(x.reps)}"
                              f"/{_num(x.targetReps)}  completed={x.completed} failed={x.failed}")
                    return 0
        print(f"no entry on line {a.explain}", file=sys.stderr)
        return 1

    tracks = infer_tracks(sessions, reviews)
    post_validate(sessions, reviews)

    if a.tracks:
        for (ex, day), t in sorted(tracks.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
            print(f"\n{BY_ID[ex]['display']}  (day {day or '-'})  from {t['tracked_from']}  "
                  f"boundary {_num(t['boundary'])}  light {_num(round(t['light'], 1))}  "
                  f"heavy {_num(round(t['heavy'], 1))}")
            for s in sessions:
                for e in s.entries:
                    if e.exerciseId == ex and s.dayLabel == day and e.sets:
                        top = max((x.load.value for x in e.sets
                                   if x.load.value is not None), default=None)
                        if top is None:
                            continue
                        m = abs(top - t["boundary"])
                        print(f"   {s.date}  {_num(top):>6}  {str(e.track or '-'):6s} "
                              f"{e.trackConfidence or '':6s} margin {_num(round(m, 1))}")
        return 0

    a.out.mkdir(parents=True, exist_ok=True)
    ok, results, classes = validate(raw, lines, sessions, unparsed, blanks, a.out)

    doc = build_document(sessions, reviews, a.source, raw, tracks)
    (a.out / "fitlog.json").write_text(json.dumps(doc, indent=2) + "\n")
    write_report(a.out / "import_report.md", sessions, reviews, unparsed,
                 results, tracks, doc, classes)
    (a.out / "roundtrip.txt").write_text("\n".join(emit_all(sessions)) + "\n")

    c = doc["meta"]["import"]["counts"]
    print(f"{c['sessions']} sessions · {c['entries']} entries · {c['sets']} sets · "
          f"{len(reviews)} to review")
    for name, detail, good in results:
        print(f"  {'PASS' if good else 'FAIL'}  {name}  {detail}")
    print(f"\nwrote {a.out / 'fitlog.json'}  and  {a.out / 'import_report.md'}")
    if not ok:
        print("\nVALIDATION FAILED - not safe to copy to the phone.", file=sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
