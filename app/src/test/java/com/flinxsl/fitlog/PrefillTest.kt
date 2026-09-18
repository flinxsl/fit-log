package com.flinxsl.fitlog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrefillTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun corpus(): FitLog? {
        val f = File("../out/fitlog.json")
        return if (f.exists()) json.decodeFromString<FitLog>(f.readText()) else null
    }

    // --- the bug most likely to go unnoticed --------------------------------

    @Test
    fun `a heavy day prefills from heavy history, not from the last session`() {
        val log = corpus() ?: return println("skip: no corpus")
        val slot = Slot("deadlift", sets = 5, reps = 5.0,
            loadKind = LoadKind.BARBELL_TOTAL, tracks = listOf("heavy", "light"))

        val heavy = Prefill.entry(log, slot, "heavy")
        val light = Prefill.entry(log, slot, "light")

        val h = heavy.topLoad!!
        val l = light.topLoad!!
        // Heavy sits around 315 and light around 280 in the real data. The exact
        // numbers move as the log grows; the invariant is the gap.
        assertTrue("heavy $h should clearly exceed light $l", h - l >= 20.0)
        assertTrue("heavy $h looks wrong", h >= 300.0)
        assertTrue("light $l looks wrong", l in 260.0..295.0)
    }

    @Test
    fun `an untracked lift ignores tracks entirely`() {
        val log = corpus() ?: return println("skip: no corpus")
        val slot = Slot("row", sets = 5, reps = 5.0, loadKind = LoadKind.BARBELL_TOTAL)
        val e = Prefill.entry(log, slot, null)
        assertNull(e.track)
        assertEquals(5, e.sets.size)
        assertTrue("row should be near its recent range", e.topLoad!! in 150.0..185.0)
    }

    @Test
    fun `the due track is the one that has been waiting longer`() {
        val log = corpus() ?: return println("skip: no corpus")
        val slot = Slot("deadlift", tracks = listOf("heavy", "light"))
        val due = Prefill.defaultTrack(log, slot)
        val lastHeavy = log.sessions.filter { s -> s.entries.any { it.exerciseId == "deadlift" && it.track == "heavy" } }.maxOf { it.date }
        val lastLight = log.sessions.filter { s -> s.entries.any { it.exerciseId == "deadlift" && it.track == "light" } }.maxOf { it.date }
        assertEquals(if (lastHeavy < lastLight) "heavy" else "light", due)
    }

    @Test
    fun `the whole session prefills from the routine in order`() {
        val log = corpus() ?: return println("skip: no corpus")
        val s = Prefill.session(log, "A", "2026-09-21")
        assertTrue("day A should have exercises", s.entries.isNotEmpty())
        assertEquals("A", s.dayLabel)
        assertEquals(s.entries.indices.map { it + 1 }, s.entries.map { it.order })
        assertTrue("every exercise should be prescribed", s.entries.all { it.sets.isNotEmpty() })
    }

    @Test
    fun `the suggested day advances through the rotation`() {
        val log = corpus() ?: return println("skip: no corpus")
        val next = Prefill.suggestedDay(log)
        assertNotNull(next)
        assertTrue("got $next", next in listOf("A", "B", "C"))
    }

    // --- progression, on synthetic data so the rules are unambiguous --------

    private fun barSlot(id: String = "squat", sets: Int = 5, tracks: List<String> = emptyList()) =
        Slot(id, sets = sets, reps = 5.0, loadKind = LoadKind.BARBELL_TOTAL, tracks = tracks)

    private fun logWith(vararg entries: Pair<String, Entry>): FitLog = FitLog(
        exercises = listOf(Exercise("squat", "Squat"), Exercise("dips", "Dips", metric = Metric.REPS)),
        routines = listOf(Routine("r", activeTo = null,
            days = listOf(RoutineDay("A", slots = listOf(barSlot()))))),
        sessions = entries.mapIndexed { i, (date, e) ->
            Session("s$i", date, "r", "A", entries = listOf(e))
        }
    )

    private fun w(i: Int, v: Double, reps: Double, target: Double = 5.0) =
        SetRecord(i, Load(LoadKind.BARBELL_TOTAL, v, "lb"), reps, target, completed = reps >= target)

    @Test
    fun `a clean session suggests more next time`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = (1..5).map { w(it, 200.0, 5.0) }))
        assertEquals(205.0, Prefill.entry(log, barSlot(), null).topLoad!!, 0.001)
    }

    @Test
    fun `a missed rep holds the weight instead of adding to it`() {
        val log = logWith("2026-09-01" to Entry("squat",
            sets = listOf(w(1, 200.0, 5.0), w(2, 200.0, 5.0), w(3, 200.0, 5.0),
                w(4, 200.0, 5.0), w(5, 200.0, 4.0))))
        assertEquals(200.0, Prefill.entry(log, barSlot(), null).topLoad!!, 0.001)
    }

    @Test
    fun `a two-weight scheme reproduces itself`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = listOf(
            w(1, 155.0, 5.0), w(2, 155.0, 5.0), w(3, 155.0, 5.0),
            w(4, 145.0, 5.0), w(5, 145.0, 5.0))))
        val got = Prefill.entry(log, barSlot(), null).sets.map { it.load.value }
        assertEquals(listOf(160.0, 160.0, 160.0, 150.0, 150.0), got)
    }

    @Test
    fun `a deload sticks rather than springing back`() {
        val log = logWith(
            "2026-09-01" to Entry("squat", sets = (1..5).map { w(it, 250.0, 5.0) }),
            "2026-09-08" to Entry("squat", sets = (1..5).map { w(it, 185.0, 5.0) }),
        )
        // Prefill follows the most recent session, not the all-time best.
        assertEquals(190.0, Prefill.entry(log, barSlot(), null).topLoad!!, 0.001)
    }

    @Test
    fun `a failed set blocks progression`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = listOf(
            w(1, 200.0, 5.0), w(2, 200.0, 5.0),
            SetRecord(3, Load(LoadKind.BARBELL_TOTAL, 200.0, "lb"), 0.0, 5.0,
                completed = false, failed = true))))
        assertEquals(200.0, Prefill.entry(log, barSlot(), null).topLoad!!, 0.001)
    }

    @Test
    fun `bodyweight work progresses by a rep, since there is no load to add`() {
        val dipsSlot = Slot("dips", sets = 3, reps = null, loadKind = LoadKind.BODYWEIGHT)
        val log = FitLog(
            exercises = listOf(Exercise("dips", "Dips", metric = Metric.REPS)),
            sessions = listOf(Session("s", "2026-09-01", entries = listOf(
                Entry("dips", sets = (1..3).map {
                    SetRecord(it, Load(LoadKind.BODYWEIGHT), 12.0, null)
                })
            )))
        )
        assertEquals(listOf(13.0, 13.0, 13.0), Prefill.entry(log, dipsSlot, null).sets.map { it.reps })
    }

    @Test
    fun `last week's missed rep is not carried into today's plan`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = listOf(
            w(1, 200.0, 5.0), w(2, 200.0, 5.0), w(3, 200.0, 5.0),
            w(4, 200.0, 5.0), w(5, 200.0, 4.0))))
        val e = Prefill.entry(log, barSlot(), null)
        // Weight holds at 200 because the session was not clean, but the plan is
        // a full 5x5 - you do not set out to miss a rep.
        assertEquals(200.0, e.topLoad!!, 0.001)
        assertEquals(listOf(5.0, 5.0, 5.0, 5.0, 5.0), e.sets.map { it.reps })
        assertEquals("Squat 200", Format.entry(e, Exercise("squat", "Squat")))
    }

    @Test
    fun `a failed set is not carried into today's plan either`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = listOf(
            w(1, 200.0, 5.0), w(2, 200.0, 5.0),
            SetRecord(3, Load(LoadKind.BARBELL_TOTAL, 200.0, "lb"), 0.0, 5.0,
                completed = false, failed = true, failureReason = "back"))))
        val e = Prefill.entry(log, barSlot(sets = 3), null)
        assertTrue("nothing should arrive pre-failed", e.sets.none { it.failed })
        assertEquals(listOf(5.0, 5.0, 5.0), e.sets.map { it.reps })
    }

    @Test
    fun `a drop scheme still reproduces itself despite the rep reset`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = listOf(
            w(1, 155.0, 5.0), w(2, 155.0, 5.0), w(3, 155.0, 4.0),
            w(4, 145.0, 5.0), w(5, 145.0, 5.0))))
        val e = Prefill.entry(log, barSlot(), null)
        assertEquals(listOf(155.0, 155.0, 155.0, 145.0, 145.0), e.sets.map { it.load.value })
        assertEquals(listOf(5.0, 5.0, 5.0, 5.0, 5.0), e.sets.map { it.reps })
    }

    @Test
    fun `a first-ever exercise falls back to the bar`() {
        val e = Prefill.entry(FitLog(), barSlot("bench"), null)
        assertEquals(5, e.sets.size)
        assertEquals(45.0, e.topLoad!!, 0.001)
    }

    @Test
    fun `the routine set count wins when it changes`() {
        val log = logWith("2026-09-01" to Entry("squat", sets = (1..5).map { w(it, 200.0, 5.0) }))
        assertEquals(3, Prefill.entry(log, barSlot(sets = 3), null).sets.size)
        assertEquals(6, Prefill.entry(log, barSlot(sets = 6), null).sets.size)
    }
}
