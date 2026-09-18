package com.flinxsl.fitlog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Format.kt is a port of the Python emitter, so the two must agree exactly.
 *
 * The strong check compares every one of the 743 rendered lines against
 * out/roundtrip.txt, which the importer generated from the same data. If these
 * ever diverge, the History screen is lying about what was actually logged.
 *
 * Both files are gitignored personal data, so these tests skip on a fresh clone
 * and the inline cases below carry the load there.
 */
class FormatTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun loadOrNull(): FitLog? {
        val f = File("../out/fitlog.json")
        if (!f.exists()) return null
        return json.decodeFromString<FitLog>(f.readText())
    }

    // --- the real corpus ----------------------------------------------------

    @Test
    fun `every rendered line matches the importer byte for byte`() {
        val log = loadOrNull() ?: return println("skip: no out/fitlog.json")
        val expected = File("../out/roundtrip.txt")
        if (!expected.exists()) return println("skip: no out/roundtrip.txt")

        val want = expected.readText().trimEnd('\n').lines()
        val got = Format.all(log).trimEnd('\n').lines()

        assertEquals("line count", want.size, got.size)
        val diffs = want.indices.filter { want[it] != got[it] }
            .map { "line ${it + 1}:\n  python: ${want[it]}\n  kotlin: ${got[it]}" }
        assertTrue(
            "${diffs.size} of ${want.size} lines differ\n\n" + diffs.take(10).joinToString("\n\n"),
            diffs.isEmpty()
        )
    }

    @Test
    fun `the corpus is the size we expect`() {
        val log = loadOrNull() ?: return println("skip: no out/fitlog.json")
        assertEquals(152, log.sessions.size)
        assertEquals(743, log.sessions.sumOf { it.entries.size })
        assertEquals(20, log.exercises.size)
    }

    @Test
    fun `tracked lifts are exactly squat and deadlift`() {
        val log = loadOrNull() ?: return println("skip: no out/fitlog.json")
        assertEquals(
            setOf("squat", "deadlift"),
            log.exercises.filter { it.isTracked }.map { it.id }.toSet()
        )
    }

    @Test
    fun `records never merge two load kinds or two tracks`() {
        val log = loadOrNull() ?: return println("skip: no out/fitlog.json")
        val keys = log.sessions.flatMap { it.entries }.filter { it.sets.isNotEmpty() }
            .map { it.prKey() }.toSet()
        // The curl switched barbell -> dumbbell, so it must appear under two keys.
        val curl = keys.filter { it.first == "curl" }.map { it.second }.toSet()
        assertEquals(setOf(LoadKind.BARBELL_TOTAL, LoadKind.DUMBBELL_EACH), curl)
        // Deadlift must appear under both tracks plus its untracked era.
        val dl = keys.filter { it.first == "deadlift" }.map { it.third }.toSet()
        assertTrue("deadlift tracks: $dl", dl.containsAll(setOf("heavy", "light", null)))
    }

    // --- inline cases, which run anywhere ----------------------------------

    private val squat = Exercise("squat", "Squat", metric = Metric.WEIGHT_REPS)
    private val pullup = Exercise("pull-up", "Pull-up", metric = Metric.REPS)
    private val plank = Exercise("plank", "Plank", metric = Metric.TIME)

    private fun wSet(i: Int, w: Double, reps: Double, target: Double = 5.0, failed: Boolean = false) =
        SetRecord(i, Load(LoadKind.BARBELL_TOTAL, w, "lb"), reps, target,
            completed = reps >= target, failed = failed)

    @Test
    fun `a clean session prints as a bare weight`() {
        val e = Entry("squat", sets = (1..5).map { wSet(it, 265.0, 5.0) })
        assertEquals("Squat 265", Format.entry(e, squat))
    }

    @Test
    fun `trailing shortfalls print sparsely`() {
        val e = Entry(
            "squat",
            sets = listOf(wSet(1, 170.0, 5.0), wSet(2, 170.0, 5.0), wSet(3, 170.0, 5.0),
                wSet(4, 170.0, 4.0), wSet(5, 170.0, 3.0)),
            setsAttribution = Attribution.UNORDERED
        )
        assertEquals("Squat 170 (-1/-2)", Format.entry(e, squat))
    }

    @Test
    fun `non-trailing shortfalls print positionally so they cannot move`() {
        val e = Entry(
            "squat",
            sets = listOf(wSet(1, 145.0, 4.0), wSet(2, 145.0, 4.0), wSet(3, 145.0, 4.0),
                wSet(4, 135.0, 5.0), wSet(5, 135.0, 3.0)),
            setsAttribution = Attribution.POSITIONAL
        )
        assertEquals("Squat 145/135 3/2 (-1/-1/-1/0/-2)", Format.entry(e, squat))
    }

    @Test
    fun `a weight drop prints as two groups with counts`() {
        val e = Entry(
            "squat",
            sets = listOf(wSet(1, 155.0, 5.0), wSet(2, 155.0, 5.0), wSet(3, 155.0, 5.0),
                wSet(4, 145.0, 5.0), wSet(5, 145.0, 5.0))
        )
        assertEquals("Squat 155/145 3/2", Format.entry(e, squat))
    }

    @Test
    fun `a failed set prints as X`() {
        val e = Entry(
            "squat",
            sets = listOf(wSet(1, 160.0, 5.0), wSet(2, 160.0, 4.0),
                wSet(3, 160.0, 0.0, failed = true)),
            setsAttribution = Attribution.POSITIONAL
        )
        assertEquals("Squat 160 (0/-1/X)", Format.entry(e, squat))
    }

    @Test
    fun `bodyweight reps print in order with no target`() {
        val e = Entry("pull-up", sets = listOf(
            SetRecord(1, Load(LoadKind.BODYWEIGHT), 8.0, null),
            SetRecord(2, Load(LoadKind.BODYWEIGHT), 8.0, null),
            SetRecord(3, Load(LoadKind.BODYWEIGHT), 5.0, null)))
        assertEquals("Pull-up 8/8/5", Format.entry(e, pullup))
    }

    @Test
    fun `half reps survive`() {
        val e = Entry("pull-up", sets = listOf(
            SetRecord(1, Load(LoadKind.BODYWEIGHT), 7.0, null),
            SetRecord(2, Load(LoadKind.BODYWEIGHT), 6.0, null),
            SetRecord(3, Load(LoadKind.BODYWEIGHT), 5.5, null)))
        assertEquals("Pull-up 7/6/5.5", Format.entry(e, pullup))
    }

    @Test
    fun `a timed hold prints its seconds`() {
        val e = Entry("plank", sets = listOf(
            SetRecord(1, Load(LoadKind.TIME_ONLY), durationSec = 85.0)))
        assertEquals("Plank 85", Format.entry(e, plank))
    }

    @Test
    fun `a skipped exercise keeps its reason`() {
        val e = Entry("squat", performed = false, failureReason = "back")
        assertEquals("Squat X back", Format.entry(e, squat))
    }

    @Test
    fun `dumbbell and bar-added loads read differently in the summary`() {
        val db = Entry("d", prescription = Prescription(3, 8.0, LoadKind.DUMBBELL_EACH, 25.0, "lb"))
        val bar = Entry("b", prescription = Prescription(3, 8.0, LoadKind.BAR_ADDED, 25.0, "lb"))
        val ex = Exercise("x", "X")
        assertEquals("3x8 @ 25 lb per hand", Format.prescriptionSummary(db, ex))
        assertEquals("3x8 @ 25 lb on the bar", Format.prescriptionSummary(bar, ex))
    }

    @Test
    fun `numbers print without trailing zeros`() {
        assertEquals("265", Format.num(265.0))
        assertEquals("17.5", Format.num(17.5))
        assertEquals("5.5", Format.num(5.5))
        assertEquals("-1", Format.num(-1.0))
    }

    @Test
    fun `the rest clock always pads seconds to two digits`() {
        assertEquals("3:00", Format.clock(180))
        assertEquals("2:59", Format.clock(179))
        assertEquals("1:05", Format.clock(65))
        assertEquals("0:09", Format.clock(9))
        assertEquals("0:00", Format.clock(0))
        assertEquals("5:00", Format.clock(300))
    }

    @Test
    fun `a rest clock that has run past zero reads zero, not a negative`() {
        // restRemaining() clamps too, but the display must not be the only
        // thing standing between a late frame and "-1:-1" on screen.
        assertEquals("0:00", Format.clock(-1))
        assertEquals("0:00", Format.clock(-90))
    }
}
