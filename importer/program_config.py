"""
fit-log importer configuration.

THIS IS THE FILE YOU EDIT.

The text log records only deviations from a plan that lived in your head, so the
importer cannot infer what the plan was. Target reps appear exactly ONCE in all
1047 lines ("Overhead 95 3x5", line 874). Everything else below is input.

Every line tagged  # ASSUMPTION  is a guess. Change it and re-run; the importer
is deterministic, so re-running is free.
"""

# ---------------------------------------------------------------------------
# Eras. The program changed on 2025-11-24 from a 2-day alternation to A/B/C.
# ---------------------------------------------------------------------------

ERAS = [
    # StrongLifts 5x5. Two alternating days, no A/B/C label in the log.
    {"id": "stronglifts-5x5", "from": "2025-08-08", "to": "2025-11-21"},
    # StrongLifts 5x5 Intermediate. Three-day A/B/C with pause variants on C.
    {"id": "stronglifts-int", "from": "2025-11-24", "to": None},
]


# ---------------------------------------------------------------------------
# Exercise catalog.
#
#   id            stable forever; never rename, never reuse
#   names         every literal spelling in text_log (lowercased, ws-collapsed)
#   metric        weight_reps | reps | time
#   load          barbell_total | bar_added | dumbbell_each | bodyweight | none
#
# load semantics, because this is the ambiguity that corrupts everything:
#   barbell_total  the number is bar + plates          "Row 170"
#   bar_added      the number is PLATES ONLY, EZ bar   "B curl 25"
#   dumbbell_each  the number is ONE dumbbell          "D curl 25"
# ---------------------------------------------------------------------------

EXERCISES = [
    # id                display        names                       metric        load
    ("squat",           "Squat",       ["squat"],                  "weight_reps", "barbell_total"),
    ("bench",           "Bench",       ["bench"],                  "weight_reps", "barbell_total"),
    ("row",             "Row",         ["row"],                    "weight_reps", "barbell_total"),
    ("deadlift",        "Dead",        ["dead"],                   "weight_reps", "barbell_total"),
    ("overhead-press",  "Overhead",    ["overhead"],               "weight_reps", "barbell_total"),
    ("incline-bench",   "Incline",     ["incline"],                "weight_reps", "barbell_total"),
    ("feet-up-bench",   "Feet up",     ["feet up"],                "weight_reps", "barbell_total"),
    ("pause-squat",     "P squat",     ["p squat"],                "weight_reps", "barbell_total"),
    ("pause-bench",     "P bench",     ["p bench"],                "weight_reps", "barbell_total"),
    ("pause-deadlift",  "P dead",      ["p dead"],                 "weight_reps", "barbell_total"),

    # "Curl" and "Skull" both switch from a barbell to dumbbells in Sep 2025.
    # See LOAD_OVERRIDES below - the exercise id does NOT fork.
    ("curl",            "Curl",        ["curl"],                   "weight_reps", "barbell_total"),
    ("skullcrusher",    "Skull",       ["skull"],                  "weight_reps", "barbell_total"),

    # B curl is an EZ curl bar; the number is the plates you add, bar excluded.
    ("barbell-curl",    "B curl",      ["b curl"],                 "weight_reps", "bar_added"),
    ("dumbbell-curl",   "D curl",      ["d curl"],                 "weight_reps", "dumbbell_each"),
    ("hammer-curl",     "Hammer",      ["hammer"],                 "weight_reps", "dumbbell_each"),

    ("pull-up",         "Pull-up",     ["pull-up", "pullup"],      "reps",        "bodyweight"),
    ("dips",            "Dips",        ["dips"],                   "reps",        "bodyweight"),
    ("leg-raise",       "Leg raise",   ["leg raise"],              "reps",        "bodyweight"),
    ("knee-raise",      "Knee raise",  ["knee raise"],             "reps",        "bodyweight"),
    ("plank",           "Plank",       ["plank"],                  "time",        "none"),
]


# ---------------------------------------------------------------------------
# Targets:  (era, exercise) -> (sets, reps)
#
# reps = None means AMRAP: taken near failure, no target, so no shortfall is
# possible and the reps are always written out explicitly in the log.
# ---------------------------------------------------------------------------

_MAIN_5x5 = ["squat", "bench", "row", "incline-bench", "feet-up-bench"]
_ACCESSORY = ["curl", "skullcrusher", "barbell-curl", "dumbbell-curl", "hammer-curl"]
_BODYWEIGHT = ["pull-up", "dips", "leg-raise", "knee-raise"]

PROGRAM = {}
for _era in ("stronglifts-5x5", "stronglifts-int"):
    for _ex in _MAIN_5x5:
        PROGRAM[(_era, _ex)] = (5, 5)
    for _ex in _ACCESSORY:
        PROGRAM[(_era, _ex)] = (3, 8)          # confirmed: accessories are 3x8
    for _ex in _BODYWEIGHT:
        PROGRAM[(_era, _ex)] = (3, None)       # AMRAP
    PROGRAM[(_era, "plank")] = (3, None)       # three timed holds

    # Pause work is heavy and low-rep, not 5x5. "P" is a paused rep.
    PROGRAM[(_era, "pause-squat")] = (5, 3)
    PROGRAM[(_era, "pause-bench")] = (5, 3)     # "P bench 185/175 3/2" proves 5 sets
    PROGRAM[(_era, "pause-deadlift")] = (2, 3)

# Deadlift is 1x5 in StrongLifts 5x5 and 5x5 in the Intermediate program.
# This is what makes "Dead 155 x3" mean THREE SETS rather than three reps.
PROGRAM[("stronglifts-5x5", "deadlift")] = (1, 5)
PROGRAM[("stronglifts-int", "deadlift")] = (5, 5)

# Overhead press: 5 sets in the StrongLifts era, 3x5 later.
# The lone explicit "Overhead 95 3x5" (line 874) is in the Intermediate era.
PROGRAM[("stronglifts-5x5", "overhead-press")] = (5, 5)
PROGRAM[("stronglifts-int", "overhead-press")] = (3, 5)

# Set counts PROVEN by explicit count notation somewhere in the log. Anything
# not listed here is an assumption and gets prescription.confidence="assumed".
PROVEN_SET_COUNTS = {
    "bench", "squat", "row", "incline-bench", "feet-up-bench",
    "pause-squat", "pause-bench", "pause-deadlift",   # confirmed by Scott
    "plank",
    "curl", "barbell-curl", "dumbbell-curl",          # 3-set splits appear
    "pull-up", "dips", "leg-raise", "knee-raise",     # reps written out
}


# ---------------------------------------------------------------------------
# Silent equipment switches. Same exercise, same id, different meaning of the
# number. Every SET stores its own load kind, so nothing is re-inferred on read.
# ---------------------------------------------------------------------------

LOAD_OVERRIDES = [
    {"exercise": "skullcrusher", "from": "2025-09-19", "load": "dumbbell_each",
     "note": "60 -> 15. Barbell to dumbbells. NOT a strength regression."},
    {"exercise": "curl", "from": "2025-09-22", "load": "dumbbell_each",
     "note": "65 -> 17.5. Barbell to dumbbells."},
]


# ---------------------------------------------------------------------------
# Light/heavy progression tracks.
#
# The log NEVER records which track a session was. These are inferred, and every
# inferred value is stamped trackOrigin="inferred" so you can tell later which
# assignments were yours and which were the importer's.
# ---------------------------------------------------------------------------

# Lifts you have told me run two tracks. Detection still runs; this is the
# universe it is allowed to consider.
TRACKED_LIFTS = {"squat", "bench", "deadlift"}

TRACK_DETECT = {
    "enabled": True,
    "window": 8,               # rolling window, sessions
    "min_run": 3,              # consecutive passing windows required
    "min_alternation": 0.35,   # tracks INTERLEAVE; deloads are contiguous runs
    "min_gap_ratio": 1.5,      # largest gap vs 2nd largest, in sorted values
    "min_gap_abs": 5.0,        # 2 x rounding increment; one plate is noise
    "min_gap_rel": 0.035,      # squat lands 0.038, bench 0.026 <- TIGHT
    "min_balance": 0.25,       # minority track share
    "exclude_injury_sessions": True,   # a (back) day is a bad day, not a track
}

# Force a result, bypassing detection entirely. Sets trackOrigin="manual".
# Bench is expected to land here: you say it is tracked, but its 185/190 split
# is 2.6% of median, under the 3.5% gate, so detection will not claim it. The
# importer leaves historical bench entries track=null rather than inventing 60
# sessions of labels from a 5 lb signal.
TRACK_OVERRIDES = {
    # ("deadlift", "B"): {"tracks": ["heavy", "light"], "tracked_from": "2026-04-15"},
    # ("squat",    "A"): {"tracks": ["heavy", "light"], "tracked_from": "2026-05-18"},
}


# ---------------------------------------------------------------------------
# Rule-level ambiguity. Each of these covers many entries, so it belongs here
# rather than as hundreds of cards in the app's review queue.
# ---------------------------------------------------------------------------

RULES = {
    # Where to put shortfalls when the source lists only how many sets fell
    # short, not which ones: "Row 170 (-1/-2)" = two of five sets, positions
    # unknown. Fatigue makes later sets the likely ones, so trailing is the
    # maximum-likelihood placement. ~130 entries; all marked "unordered" so no
    # per-set analysis mistakes the convention for data.
    "sparse_shortfall_placement": "trailing",

    # "Dips 12" / "Leg raise 16" -> three sets of 12. Confirmed: a bare number
    # on a bodyweight lift means every set hit it, same as the barbell lifts.
    "bw_single_number": "n_sets_of_x",
}


# ---------------------------------------------------------------------------
# Per-entry corrections. Each still emits a review item recording the original;
# corrections are never silent.
# ---------------------------------------------------------------------------

DATE_FIXES = {
    # line: corrected ISO date
    736: "2026-05-11",   # was 5/11/24. Monday, and sits between 5/8/26 and 5/13/26.
    947: "2026-08-10",   # was 7/10/26. Monday. Collides with the real 7/10/26 C.
    954: "2026-08-13",   # was 7/13/26. Thursday - slightly off M/W/F, lower confidence.
}

VALUE_FIXES = {
    62: {"from": 164, "to": 165},   # only weight in the file not on the 2.5 grid
}


# ---------------------------------------------------------------------------
# Settings written into the output file.
# ---------------------------------------------------------------------------

SETTINGS = {
    "unit": "lb",
    "barWeight": 45,
    "roundingIncrement": 2.5,
    "restSeconds": 180,
    "keepScreenOn": True,
}

# Body-part words that mean "this failed because of X" rather than a free note.
FAILURE_REASONS = {"back", "grip", "forearm", "forarm", "shoulder", "knee", "wrist", "elbow"}

# Typo normalisations, reported rather than applied silently.
SPELLING_FIXES = {"forarm": "forearm"}
