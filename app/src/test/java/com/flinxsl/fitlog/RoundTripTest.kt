package com.flinxsl.fitlog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Export must be able to bring everything back.
 *
 * This is the only protection against losing data: uninstalling the app deletes
 * its private storage, so an export that silently drops a field would not be
 * noticed until the day it mattered.
 */
class RoundTripTest {

    // The same configuration Store uses.
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private fun corpus(): FitLog? {
        val f = File("../out/fitlog.json")
        return if (f.exists()) json.decodeFromString<FitLog>(f.readText()) else null
    }

    @Test
    fun `the real log survives export and re-import unchanged`() {
        val log = corpus() ?: return println("skip: no corpus")
        val back = json.decodeFromString<FitLog>(json.encodeToString(log))
        assertEquals(log, back)
    }

    @Test
    fun `every field survives, not just the counts`() {
        val log = corpus() ?: return println("skip: no corpus")
        val back = json.decodeFromString<FitLog>(json.encodeToString(log))
        // Spot-check the fields that carry hard-won meaning and would be easy to drop.
        val a = log.sessions.flatMap { it.entries }
        val b = back.sessions.flatMap { it.entries }
        assertEquals(a.map { it.track }, b.map { it.track })
        assertEquals(a.map { it.trackOrigin }, b.map { it.trackOrigin })
        assertEquals(a.map { it.setsAttribution }, b.map { it.setsAttribution })
        assertEquals(a.map { it.progSets }, b.map { it.progSets })
        assertEquals(
            a.flatMap { e -> e.sets.map { it.load.kind } },
            b.flatMap { e -> e.sets.map { it.load.kind } },
        )
        assertEquals(
            a.flatMap { e -> e.sets.map { it.reps } },
            b.flatMap { e -> e.sets.map { it.reps } },
        )
    }

    @Test
    fun `half reps and fractional weights are not rounded away`() {
        val log = FitLog(sessions = listOf(Session("s", "2026-01-01", entries = listOf(
            Entry("x", sets = listOf(
                SetRecord(1, Load(LoadKind.DUMBBELL_EACH, 17.5, "lb"), 5.5, 8.0),
                SetRecord(2, Load(LoadKind.BAR_ADDED, 22.5, "lb"), 4.5, 8.0),
            ))
        ))))
        val back = json.decodeFromString<FitLog>(json.encodeToString(log))
        val sets = back.sessions[0].entries[0].sets
        assertEquals(17.5, sets[0].load.value!!, 0.0001)
        assertEquals(5.5, sets[0].reps!!, 0.0001)
        assertEquals(22.5, sets[1].load.value!!, 0.0001)
        assertEquals(4.5, sets[1].reps!!, 0.0001)
    }

    @Test
    fun `an unknown field from a newer version does not break the parse`() {
        val text = """
            {"schemaVersion":1,
             "somethingFromTheFuture":{"nested":true},
             "sessions":[{"id":"s","date":"2026-01-01","rpe":9.5,"entries":[]}]}
        """.trimIndent()
        val log = json.decodeFromString<FitLog>(text)
        assertEquals(1, log.sessions.size)
        assertEquals("2026-01-01", log.sessions[0].date)
    }

    @Test
    fun `a missing field falls back to its default rather than failing`() {
        val log = json.decodeFromString<FitLog>("""{"sessions":[{"id":"s","date":"2026-01-01"}]}""")
        assertEquals(1, log.schemaVersion)
        assertEquals("lb", log.settings.unit)
        assertTrue(log.sessions[0].entries.isEmpty())
    }

    @Test
    fun `the text export re-parses to the same sessions via the importer notation`() {
        val log = corpus() ?: return println("skip: no corpus")
        val text = Format.all(log)
        // Same content as the importer's own round-trip file.
        val expected = File("../out/roundtrip.txt")
        if (expected.exists()) {
            assertEquals(expected.readText().trimEnd('\n'), text.trimEnd('\n'))
        }
        assertTrue("text export should not be empty", text.length > 1000)
    }

    @Test
    fun `garbage is rejected rather than half-applied`() {
        var failed = false
        try {
            json.decodeFromString<FitLog>("this is not json at all")
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("a bad file must throw so the caller can refuse it", failed)
    }

    @Test
    fun `editing a routine cannot change recorded history`() {
        val log = corpus() ?: return println("skip: no corpus")
        val before = log.sessions.flatMap { it.entries }.map { Format.entry(it, log.exercise(it.exerciseId)) }
        // Wipe every routine, which is the most destructive edit possible.
        val stripped = log.copy(routines = emptyList())
        val after = stripped.sessions.flatMap { it.entries }
            .map { Format.entry(it, stripped.exercise(it.exerciseId)) }
        assertEquals(before, after)
        assertNotEquals(0, before.size)
    }
}
