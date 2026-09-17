"""
Parser tests, driven by real lines lifted from text_log.

Run:  python3 -m pytest importer/test_parse.py -q
      python3 importer/test_parse.py          (no pytest needed)
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import fitlog_import as fi


def parse(line, when="2026-01-01"):
    s = fi.Session(id="t", date=when, routineId=fi.era_for(when), dayLabel=None)
    return fi.parse_exercise_line(1, line, s, 1)


def sets_of(line, when="2026-01-01"):
    return [(x.load.value, fi._num(x.reps), x.failed) for x in parse(line, when).sets]


# --- the plain case: a bare weight means every set hit the target -----------

def test_bare_weight_is_all_sets_complete():
    assert sets_of("Squat 265") == [(265.0, 5, False)] * 5


def test_accessory_is_three_by_eight():
    assert sets_of("D curl 25") == [(25.0, 8, False)] * 3


# --- shortfalls ------------------------------------------------------------

def test_sparse_shortfalls_land_on_trailing_sets():
    assert sets_of("Row 170 (-1/-2)") == [
        (170.0, 5, False), (170.0, 5, False), (170.0, 5, False),
        (170.0, 4, False), (170.0, 3, False)]


def test_explicit_zero_makes_the_list_positional():
    assert sets_of("Feet up 145/135 3/2 (-2/0/0)")[0] == (145.0, 3, False)


def test_half_reps_survive():
    assert sets_of("Feet up 145/135 2/3 (-0.5)")[-1] == (135.0, 4.5, False)


def test_x_inside_a_shortfall_list_is_a_failed_set():
    assert sets_of("Bench 160 (-1/-2/x)")[-1] == (160.0, 0, True)


# --- multi-weight schemes --------------------------------------------------

def test_three_weight_groups_zip_with_their_counts():
    assert sets_of("Bench 175/165/155 1/3/1") == [
        (175.0, 5, False), (165.0, 5, False), (165.0, 5, False),
        (165.0, 5, False), (155.0, 5, False)]


def test_counts_with_inline_shortfalls():
    assert sets_of("Feet up 155/145 3(-1)/2(-1/-1)") == [
        (155.0, 4, False), (155.0, 4, False), (155.0, 4, False),
        (145.0, 4, False), (145.0, 4, False)]


def test_inline_shortfall_without_a_space_is_one_group():
    # "145(-1/-2)" must NOT split on the inner slash
    assert sets_of("Feet up 145(-1/-2)") == [
        (145.0, 5, False), (145.0, 5, False), (145.0, 5, False),
        (145.0, 4, False), (145.0, 3, False)]


def test_all_three_positions_at_once():
    assert sets_of("Feet up 145(-1)/135 3/2 (-2)") == [
        (145.0, 4, False), (145.0, 4, False), (145.0, 4, False),
        (135.0, 5, False), (135.0, 3, False)]


# --- weight semantics ------------------------------------------------------

def test_barbell_curl_is_plates_added_to_the_ez_bar():
    assert parse("B curl 25").sets[0].load.kind == "bar_added"


def test_dumbbell_curl_is_per_hand():
    assert parse("D curl 25").sets[0].load.kind == "dumbbell_each"


def test_skullcrusher_switches_to_dumbbells_in_sept_2025():
    assert parse("Skull 60", "2025-09-01").sets[0].load.kind == "barbell_total"
    assert parse("Skull 15", "2025-10-01").sets[0].load.kind == "dumbbell_each"


# --- bodyweight and time ---------------------------------------------------

def test_bodyweight_reps_are_positional_and_have_no_target():
    e = parse("Pull-up 8/8/5")
    assert [fi._num(x.reps) for x in e.sets] == [8, 8, 5]
    assert all(x.targetReps is None for x in e.sets)


def test_bare_bodyweight_number_means_every_set():
    assert [fi._num(x.reps) for x in parse("Dips 12").sets] == [12, 12, 12]


def test_fractional_bodyweight_reps():
    assert [fi._num(x.reps) for x in parse("Pull-up 7/6/5.5").sets] == [7, 6, 5.5]


def test_plank_is_time():
    e = parse("Plank 85")
    assert fi._num(e.sets[0].durationSec) == 85 and e.sets[0].load.kind == "none"


def test_plank_no_means_not_performed():
    assert parse("Plank no").performed is False


# --- failures and notes ----------------------------------------------------

def test_whole_exercise_failure_keeps_its_reason():
    e = parse("Feet up X back")
    assert e.performed is False and e.failureReason == "back"


def test_typo_in_reason_is_normalised():
    assert parse("B curl X forarm").failureReason == "forearm"


def test_reason_inside_the_parens():
    e = parse("Squat 215 (-2 back)")
    assert e.failureReason == "back" and fi._num(e.sets[-1].reps) == 3


def test_performed_but_unquantified_keeps_the_note_and_invents_no_sets():
    e = parse("Row yes (need form check)")
    assert e.sets == [] and "form" in e.notes


# --- era-sensitive ---------------------------------------------------------

def test_deadlift_is_one_by_five_in_the_stronglifts_era():
    assert len(parse("Dead 185", "2025-10-01").sets) == 1


def test_deadlift_is_five_by_five_later():
    assert len(parse("Dead 305", "2026-06-01").sets) == 5


def test_xn_overrides_the_program_set_count():
    assert len(parse("Dead 155 x3", "2025-09-01").sets) == 3


def test_weight_groups_are_never_dropped_to_fit_the_program():
    # "Dead 225/235" in a 1x5 era still records both weights
    assert sets_of("Dead 225/235", "2025-10-15") == [(225.0, 5, False), (235.0, 5, False)]


# --- truncation ------------------------------------------------------------

def test_truncated_lines_are_refused_rather_than_half_read():
    for bad in ("Bench 180/175 3(-1)/", "Dips 12/", "Incline 155/145 4/1 (-3/"):
        try:
            parse(bad)
            raise AssertionError(f"should have refused: {bad!r}")
        except fi.ParseError:
            pass


# --- round trip over the whole real file -----------------------------------

def test_whole_file_round_trips():
    src = Path(__file__).parent.parent / "text_log"
    if not src.exists():
        print("  skip  no text_log present (it is gitignored)")
        return
    raw, lines, sessions, reviews, unparsed, blanks = fi.parse_file(src)
    assert len(sessions) == 152
    assert sum(len(s.entries) for s in sessions) == 743
    assert len(unparsed) == 3

    import tempfile
    tmp = Path(tempfile.mkdtemp()) / "rt.txt"
    tmp.write_text("\n".join(fi.emit_all(sessions)) + "\n")
    _, _, s2, _, _, _ = fi.parse_file(tmp)
    assert fi.projection(sessions) == fi.projection(s2)


if __name__ == "__main__":
    fns = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_")]
    bad = 0
    for n, f in fns:
        try:
            f()
            print(f"  pass  {n}")
        except Exception as exc:
            bad += 1
            print(f"  FAIL  {n}: {exc}")
    print(f"\n{len(fns) - bad}/{len(fns)} passed")
    sys.exit(1 if bad else 0)
