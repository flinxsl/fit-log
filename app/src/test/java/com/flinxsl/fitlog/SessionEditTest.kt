package com.flinxsl.fitlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEditTest {

    private val squat = Exercise("squat", "Squat")
    private val bench = Exercise("bench", "Bench")
    private val row = Exercise("row", "Row")
    private val bcurl = Exercise("barbell-curl", "B curl", defaultLoadKind = LoadKind.BAR_ADDED)
    private val legRaise = Exercise("leg-raise", "Leg raise", metric = Metric.REPS)
    private val catalog = FitLog(exercises = listOf(squat, bench, row, bcurl, legRaise))

    private fun bar(n: Int, w: Double, reps: Double) = (1..n).map {
        SetRecord(it, Load(LoadKind.BARBELL_TOTAL, w, "lb"), reps, reps, completed = true)
    }

    private fun added(n: Int, w: Double, reps: Double) = (1..n).map {
        SetRecord(it, Load(LoadKind.BAR_ADDED, w, "lb"), reps, reps, completed = true)
    }

    private fun bw(vararg reps: Double) = reps.mapIndexed { i, r ->
        SetRecord(i + 1, Load(LoadKind.BODYWEIGHT), r, null, completed = true)
    }

    /** Exactly what Prefill would hand you for that day A. */
    private fun prefilled() = Session(
        id = "t", date = "2026-09-14", dayLabel = "A",
        entries = listOf(
            Entry("squat", 1, sets = bar(5, 265.0, 5.0), progSets = 5,
                prescription = Prescription(5, 5.0, LoadKind.BARBELL_TOTAL, 265.0, "lb")),
            Entry("bench", 2, sets = bar(5, 190.0, 5.0), progSets = 5,
                prescription = Prescription(5, 5.0, LoadKind.BARBELL_TOTAL, 190.0, "lb")),
            Entry("row", 3, sets = bar(5, 170.0, 5.0), progSets = 5,
                prescription = Prescription(5, 5.0, LoadKind.BARBELL_TOTAL, 170.0, "lb")),
            Entry("barbell-curl", 4, sets = added(3, 25.0, 8.0), progSets = 3,
                prescription = Prescription(3, 8.0, LoadKind.BAR_ADDED, 25.0, "lb")),
            Entry("leg-raise", 5, sets = bw(12.0, 12.0, 12.0), progSets = 3,
                prescription = Prescription(3, null, LoadKind.BODYWEIGHT, null, "lb")),
        ),
    )

    private fun render(s: Session) = s.entries.map { Format.entry(it, catalog.exercise(it.exerciseId)) }

    /**
     * The acceptance test for the whole logging interaction: take a real session
     * off the page and reproduce it by tapping, then check it renders back
     * character for character.
     */
    @Test
    fun `reproduce a real session by tapping and render it identically`() {
        val original = prefilled()
        var s = original
        var taps = 0

        // Row 170 (-1/-2): set 4 short by one, set 5 short by two.
        s = SessionEdit.tap(s, original, 2, 3); taps++
        s = SessionEdit.tap(s, original, 2, 4); taps++
        s = SessionEdit.tap(s, original, 2, 4); taps++

        // B curl 25 (-3): the third set fell three short. Hold and pick, one action.
        s = SessionEdit.reps(s, 3, 2, 5.0); taps++

        // Leg raise 12/12/6: the third set got six. Hold and pick.
        s = SessionEdit.reps(s, 4, 2, 6.0); taps++

        assertEquals(
            listOf(
                "Squat 265",
                "Bench 190",
                "Row 170 (0/0/0/-1/-2)",
                "B curl 25 (0/0/-3)",
                "Leg raise 12/12/6",
            ),
            render(s),
        )
        assertTrue("took $taps edits", taps <= 9)
    }

    /**
     * The same data written the terse way. Once positions are known the app
     * records them, which is MORE information than the paper log carried -
     * "(-1/-2)" never said which two sets fell short.
     */
    @Test
    fun `an unordered entry still renders the terse way`() {
        val e = Entry(
            "row",
            sets = listOf(
                SetRecord(1, Load(LoadKind.BARBELL_TOTAL, 170.0, "lb"), 5.0, 5.0),
                SetRecord(2, Load(LoadKind.BARBELL_TOTAL, 170.0, "lb"), 5.0, 5.0),
                SetRecord(3, Load(LoadKind.BARBELL_TOTAL, 170.0, "lb"), 5.0, 5.0),
                SetRecord(4, Load(LoadKind.BARBELL_TOTAL, 170.0, "lb"), 4.0, 5.0, completed = false),
                SetRecord(5, Load(LoadKind.BARBELL_TOTAL, 170.0, "lb"), 3.0, 5.0, completed = false),
            ),
            setsAttribution = Attribution.UNORDERED,
        )
        assertEquals("Row 170 (-1/-2)", Format.entry(e, row))
    }

    // --- individual gestures ------------------------------------------------

    @Test
    fun `one tap removes one rep`() {
        val o = prefilled()
        val s = SessionEdit.tap(o, o, 0, 0)
        assertEquals(4.0, s.entries[0].sets[0].reps)
        assertFalse(s.entries[0].sets[0].completed)
    }

    @Test
    fun `tapping past zero wraps back so you cannot get stuck`() {
        val o = prefilled()
        var s = o
        repeat(6) { s = SessionEdit.tap(s, o, 0, 0) }   // 5,4,3,2,1,0 then wrap
        assertEquals(5.0, s.entries[0].sets[0].reps)
        assertTrue(s.entries[0].sets[0].completed)
    }

    @Test
    fun `a failed set is not the same as a short one`() {
        val o = prefilled()
        val short = SessionEdit.reps(o, 0, 0, 3.0).entries[0].sets[0]
        val dead = SessionEdit.fail(o, 0, 0, "back").entries[0].sets[0]
        assertFalse("3 of 5 is incomplete, not failed", short.failed)
        assertTrue(dead.failed)
        assertEquals(0.0, dead.reps)
        assertEquals("back", dead.failureReason)
    }

    @Test
    fun `a failed set renders as X`() {
        val o = prefilled()
        val s = SessionEdit.fail(o, 0, 4, null)
        assertEquals("Squat 265 (0/0/0/0/X)", Format.entry(s.entries[0], squat))
    }

    @Test
    fun `changing weight from a set makes a drop scheme`() {
        val o = prefilled()
        val s = SessionEdit.weightFrom(o, 2, 3, 160.0)
        assertEquals("Row 170/160 3/2", Format.entry(s.entries[2], row))
    }

    @Test
    fun `changing one set leaves the others alone`() {
        val o = prefilled()
        val s = SessionEdit.weightAt(o, 2, 2, 160.0)
        assertEquals(listOf(170.0, 170.0, 160.0, 170.0, 170.0), s.entries[2].sets.map { it.load.value })
        assertEquals("Row 170/160/170 2/1/2", Format.entry(s.entries[2], row))
    }

    @Test
    fun `from-here and this-set-only differ where it matters`() {
        val o = prefilled()
        val rest = SessionEdit.weightFrom(o, 2, 3, 160.0).entries[2].sets.map { it.load.value }
        val one = SessionEdit.weightAt(o, 2, 3, 160.0).entries[2].sets.map { it.load.value }
        assertEquals(listOf(170.0, 170.0, 170.0, 160.0, 160.0), rest)
        assertEquals(listOf(170.0, 170.0, 170.0, 160.0, 170.0), one)
    }

    @Test
    fun `changing one bodyweight set is a no-op, there being no load`() {
        val o = prefilled()
        val s = SessionEdit.weightAt(o, 4, 0, 25.0)
        assertEquals(o.entries[4].sets, s.entries[4].sets)
    }

    @Test
    fun `nudging the weight moves every set and keeps the gaps`() {
        val o = prefilled()
        var s = SessionEdit.weightFrom(o, 2, 3, 160.0)   // 170/160
        s = SessionEdit.nudge(s, 2, -5.0)                 // both down 5
        assertEquals(listOf(165.0, 165.0, 165.0, 155.0, 155.0), s.entries[2].sets.map { it.load.value })
    }

    @Test
    fun `skipping an exercise records the reason`() {
        val o = prefilled()
        val s = SessionEdit.skip(o, 0, true, "back")
        assertEquals("Squat 265 X back", Format.entry(s.entries[0], squat))
    }

    @Test
    fun `bar-added and barbell loads never mix in one entry`() {
        val o = prefilled()
        assertEquals(LoadKind.BAR_ADDED, o.entries[3].sets.first().load.kind)
        assertEquals(LoadKind.BARBELL_TOTAL, o.entries[0].sets.first().load.kind)
    }

    @Test
    fun `editing a set marks the entry positional`() {
        val o = prefilled().let { it.copy(entries = it.entries.map { e -> e.copy(setsAttribution = Attribution.UNORDERED) }) }
        val s = SessionEdit.tap(o, o, 0, 2)
        assertEquals(Attribution.POSITIONAL, s.entries[0].setsAttribution)
    }

    // --- rep values the preset row cannot reach -----------------------------

    @Test
    fun `reps above the target are accepted and count as complete`() {
        val o = prefilled()
        // An AMRAP-ish day: 12 reps on a lift whose target is 5.
        val s = SessionEdit.reps(o, 0, 0, 12.0)
        val set = s.entries[0].sets[0]
        assertEquals(12.0, set.reps!!, 0.001)
        assertTrue("beating the target is completing it", set.completed)
        assertEquals("Squat 265 (7/0/0/0/0)", Format.entry(s.entries[0], squat))
    }

    @Test
    fun `half reps can be recorded`() {
        val o = prefilled()
        val s = SessionEdit.reps(o, 0, 4, 4.5)
        assertEquals(4.5, s.entries[0].sets[4].reps!!, 0.001)
        assertEquals("Squat 265 (-0.5)", Format.entry(
            s.entries[0].copy(setsAttribution = Attribution.UNORDERED), squat))
    }

    @Test
    fun `a large bodyweight count is recorded as-is`() {
        val o = prefilled()
        val s = SessionEdit.reps(o, 4, 0, 25.0)
        assertEquals(25.0, s.entries[4].sets[0].reps!!, 0.001)
        assertEquals("Leg raise 25/12/12", Format.entry(s.entries[4], legRaise))
    }

    @Test
    fun `zero reps is not the same as a failed set`() {
        val o = prefilled()
        val zero = SessionEdit.reps(o, 0, 0, 0.0).entries[0].sets[0]
        assertEquals(0.0, zero.reps!!, 0.001)
        assertFalse("zero reps entered is not an abandoned set", zero.failed)
    }

    // --- filling in an entry the importer left empty ------------------------

    @Test
    fun `an entry with no sets can be given sets to edit`() {
        val empty = Session("t", "2025-12-15", entries = listOf(
            Entry("bench", 1, sets = emptyList(), excludeFromPr = true,
                prescription = Prescription(5, 5.0, LoadKind.BARBELL_TOTAL, null, "lb",
                    origin = "import_config", confidence = "unknown"),
                source = Source(line = 368, raw = "Bench 180/175 3(-1)/")),
        ))
        val out = SessionEdit.materialize(empty, 0, 5, 5.0, LoadKind.BARBELL_TOTAL, 180.0, "lb", null)
        val e = out.entries[0]
        assertEquals(5, e.sets.size)
        assertEquals(listOf(5.0, 5.0, 5.0, 5.0, 5.0), e.sets.map { it.reps })
        assertEquals(180.0, e.topLoad!!, 0.001)
        assertEquals("Bench 180", Format.entry(e, bench))
        // Filled in by hand, so no longer the importer's guess, and eligible for records.
        assertEquals("manual", e.prescription.origin)
        assertEquals("certain", e.prescription.confidence)
        assertEquals(false, e.excludeFromPr)
    }

    @Test
    fun `filling in never overwrites sets that already exist`() {
        val o = prefilled()
        val out = SessionEdit.materialize(o, 0, 3, 8.0, LoadKind.DUMBBELL_EACH, 25.0, "lb", null)
        assertEquals(o.entries[0].sets, out.entries[0].sets)
    }

    @Test
    fun `a timed entry fills in as holds, not reps`() {
        val empty = Session("t", "2026-01-01", entries = listOf(Entry("plank", sets = emptyList())))
        val out = SessionEdit.materialize(empty, 0, 3, null, LoadKind.TIME_ONLY, null, "lb", 80.0)
        assertEquals(3, out.entries[0].sets.size)
        assertEquals(80.0, out.entries[0].sets[0].durationSec!!, 0.001)
        assertEquals(null, out.entries[0].sets[0].reps)
    }

    @Test
    fun `a bodyweight entry fills in with no load`() {
        val empty = Session("t", "2026-01-01", entries = listOf(Entry("dips", sets = emptyList())))
        val out = SessionEdit.materialize(empty, 0, 3, 12.0, LoadKind.BODYWEIGHT, null, "lb", null)
        assertEquals(3, out.entries[0].sets.size)
        assertEquals(null, out.entries[0].sets[0].load.value)
        assertEquals(LoadKind.BODYWEIGHT, out.entries[0].sets[0].load.kind)
    }

    @Test
    fun `bodyweight sets have no target so they never read as short`() {
        val o = prefilled()
        val s = SessionEdit.reps(o, 4, 2, 6.0)
        assertTrue("AMRAP sets cannot fall short", s.entries[4].sets[2].completed)
    }
}
