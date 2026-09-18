package com.flinxsl.fitlog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogEditTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun corpus(): FitLog? {
        val f = File("../out/fitlog.json")
        return if (f.exists()) json.decodeFromString<FitLog>(f.readText()) else null
    }

    private fun sess(id: String, date: String) = Session(
        id, date, entries = listOf(
            Entry("squat", sets = listOf(
                SetRecord(1, Load(LoadKind.BARBELL_TOTAL, 200.0, "lb"), 5.0, 5.0)))
        )
    )

    private val base = FitLog(sessions = listOf(sess("a", "2026-01-01"), sess("b", "2026-01-03")))

    // --- editing and deleting past sessions ---------------------------------

    @Test
    fun `a new session is appended`() {
        val out = LogEdit.upsertSession(base, sess("c", "2026-01-05"), null)
        assertEquals(listOf("a", "b", "c"), out.sessions.map { it.id })
    }

    @Test
    fun `editing replaces in place and does not duplicate`() {
        val edited = sess("b", "2026-01-03").copy(notes = "felt strong")
        val out = LogEdit.upsertSession(base, edited, "b")
        assertEquals(2, out.sessions.size)
        assertEquals(listOf("a", "b"), out.sessions.map { it.id })
        assertEquals("felt strong", out.sessions.first { it.id == "b" }.notes)
    }

    @Test
    fun `editing preserves the order of the log`() {
        val out = LogEdit.upsertSession(base, sess("a", "2026-01-01").copy(notes = "x"), "a")
        assertEquals(listOf("a", "b"), out.sessions.map { it.id })
    }

    @Test
    fun `editing clears the needs-review flag on that session`() {
        val flagged = FitLog(sessions = listOf(sess("a", "2026-01-01").copy(needsReview = true)))
        val out = LogEdit.upsertSession(flagged, flagged.sessions[0], "a")
        assertFalse(out.sessions[0].needsReview)
    }

    @Test
    fun `deleting removes exactly one session`() {
        val out = LogEdit.deleteSession(base, "a")
        assertEquals(listOf("b"), out.sessions.map { it.id })
    }

    @Test
    fun `deleting an id that is not there changes nothing`() {
        assertEquals(base, LogEdit.deleteSession(base, "nope"))
    }

    // --- the review queue ---------------------------------------------------

    private fun flaggedLog() = FitLog(
        sessions = listOf(
            Session("s", "2026-04-08", entries = listOf(
                Entry("deadlift", track = "heavy", trackOrigin = "inferred",
                    trackConfidence = "low", needsReview = true,
                    sets = listOf(SetRecord(1, Load(LoadKind.BARBELL_TOTAL, 295.0, "lb"), 5.0, 5.0)),
                    source = Source(line = 646, raw = "Dead 295 (-1/0/-2) grip")),
            ))
        ),
        review = listOf(ReviewItem("rv-1", "TRACK_AMBIGUOUS", sourceLines = listOf(646))),
    )

    @Test
    fun `choosing a track records it as your decision, not an inference`() {
        val log = flaggedLog()
        val out = LogEdit.resolveReview(log, log.review[0], "light", today = "2026-09-17")
        val e = out.sessions[0].entries[0]
        assertEquals("light", e.track)
        // trackOrigin=manual is what stops a re-import overwriting the choice.
        assertEquals("manual", e.trackOrigin)
        assertNull(e.trackConfidence)
        assertFalse(e.needsReview)
    }

    @Test
    fun `choosing single-track clears the label rather than picking one`() {
        val log = flaggedLog()
        val out = LogEdit.resolveReview(log, log.review[0], "none", today = "2026-09-17")
        assertNull(out.sessions[0].entries[0].track)
        assertEquals("manual", out.sessions[0].entries[0].trackOrigin)
    }

    @Test
    fun `marking unknown keeps the session but bars it from records`() {
        val log = flaggedLog()
        val out = LogEdit.resolveReview(log, log.review[0], "unknown", today = "2026-09-17")
        val e = out.sessions[0].entries[0]
        assertTrue("sets should be cleared", e.sets.isEmpty())
        assertTrue("must not claim a record", e.excludeFromPr)
        // The entry itself stays, so the session is not silently shortened.
        assertEquals(1, out.sessions[0].entries.size)
    }

    @Test
    fun `accepting the importer's guess changes no data`() {
        val log = flaggedLog()
        val out = LogEdit.resolveReview(log, log.review[0], "accept", today = "2026-09-17")
        assertEquals(log.sessions, out.sessions)
        assertTrue(out.review[0].resolved)
    }

    @Test
    fun `a resolved item is kept as a record, not deleted`() {
        val log = flaggedLog()
        val out = LogEdit.resolveReview(log, log.review[0], "accept", "note here", "2026-09-17")
        assertEquals(1, out.review.size)
        assertTrue(out.review[0].resolved)
        assertEquals("accept", out.review[0].resolution?.optionId)
        assertEquals("note here", out.review[0].resolution?.note)
        assertEquals("2026-09-17", out.review[0].resolvedAt)
    }

    @Test
    fun `resolving one item leaves the others alone`() {
        val log = flaggedLog().let { it.copy(review = it.review + ReviewItem("rv-2", "OTHER")) }
        val out = LogEdit.resolveReview(log, log.review[0], "accept", today = "2026-09-17")
        assertTrue(out.review.first { it.id == "rv-1" }.resolved)
        assertFalse(out.review.first { it.id == "rv-2" }.resolved)
    }

    @Test
    fun `resolving only touches the entry on that source line`() {
        val log = FitLog(
            sessions = listOf(Session("s", "2026-01-01", entries = listOf(
                Entry("squat", track = "heavy", trackOrigin = "inferred",
                    source = Source(line = 100)),
                Entry("bench", track = "heavy", trackOrigin = "inferred",
                    source = Source(line = 101)),
            ))),
            review = listOf(ReviewItem("rv-1", sourceLines = listOf(100))),
        )
        val out = LogEdit.resolveReview(log, log.review[0], "light", today = "2026-09-17")
        assertEquals("light", out.sessions[0].entries[0].track)
        assertEquals("heavy", out.sessions[0].entries[1].track)
        assertEquals("inferred", out.sessions[0].entries[1].trackOrigin)
    }

    @Test
    fun `keeping the original date actually reverts it`() {
        val log = FitLog(
            sessions = listOf(Session("s", "2026-05-11",
                source = Source(line = 736, raw = "5/11/24 A"))),
            review = listOf(ReviewItem("rv-1", "DATE_YEAR_TYPO",
                sourceLines = listOf(736), sourceRaw = "5/11/24 A")),
        )
        val out = LogEdit.resolveReview(log, log.review[0], "revert", today = "2026-09-17")
        assertEquals("2024-05-11", out.sessions[0].date)
        assertTrue(out.review[0].resolved)
    }

    @Test
    fun `accepting the corrected date leaves it corrected`() {
        val log = FitLog(
            sessions = listOf(Session("s", "2026-05-11",
                source = Source(line = 736, raw = "5/11/24 A"))),
            review = listOf(ReviewItem("rv-1", "DATE_YEAR_TYPO",
                sourceLines = listOf(736), sourceRaw = "5/11/24 A")),
        )
        val out = LogEdit.resolveReview(log, log.review[0], "accept", today = "2026-09-17")
        assertEquals("2026-05-11", out.sessions[0].date)
    }

    @Test
    fun `reverting a line with no parseable date changes nothing`() {
        val log = FitLog(
            sessions = listOf(Session("s", "2026-05-11", source = Source(line = 9, raw = "Dips 12/"))),
            review = listOf(ReviewItem("rv-1", sourceLines = listOf(9), sourceRaw = "Dips 12/")),
        )
        val out = LogEdit.resolveReview(log, log.review[0], "revert", today = "2026-09-17")
        assertEquals("2026-05-11", out.sessions[0].date)
    }

    // --- against the real corpus --------------------------------------------

    @Test
    fun `every review item in the real import points at a findable session`() {
        val log = corpus() ?: return println("skip: no corpus")
        val withLines = log.review.filter { it.sourceLines.isNotEmpty() }
        assertTrue("expected review items with lines", withLines.isNotEmpty())
        val orphans = withLines.filter { LogEdit.sessionForLine(log, it.sourceLines.first()) == null }
        assertEquals("these cards could not open their session: ${orphans.map { it.code }}",
            emptyList<ReviewItem>(), orphans)
    }

    @Test
    fun `resolving everything in the real queue leaves the log loadable`() {
        val log = corpus() ?: return println("skip: no corpus")
        var out = log
        log.review.forEach { item ->
            val opt = item.options.firstOrNull { it.primary }?.id
                ?: item.options.firstOrNull()?.id ?: "accept"
            out = LogEdit.resolveReview(out, item, opt, today = "2026-09-17")
        }
        assertEquals(0, out.review.count { !it.resolved })
        assertEquals(log.sessions.size, out.sessions.size)
        // Still serialisable, which is what the app does immediately afterwards.
        val text = Json { encodeDefaults = true }.encodeToString(out)
        assertTrue(text.length > 1000)
    }
}
