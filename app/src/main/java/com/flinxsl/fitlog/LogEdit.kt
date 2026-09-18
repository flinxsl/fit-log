package com.flinxsl.fitlog

/**
 * Changes to the log as a whole, as pure functions.
 *
 * Same reasoning as SessionEdit: these are the operations that can quietly
 * destroy thirteen months of history - replacing the wrong session, resolving a
 * review onto the wrong entry - and none of them need Android, so they belong
 * where the JVM tests can reach them.
 */
object LogEdit {

    /**
     * Add a new session, or replace one already there. The editingId is what
     * makes correcting a past session and logging a new one the same code path.
     */
    fun upsertSession(log: FitLog, session: Session, editingId: String?): FitLog =
        if (editingId == null) {
            log.copy(sessions = log.sessions + session)
        } else {
            log.copy(sessions = log.sessions.map {
                if (it.id == editingId) session.copy(needsReview = false) else it
            })
        }

    fun deleteSession(log: FitLog, id: String): FitLog =
        log.copy(sessions = log.sessions.filterNot { it.id == id })

    /**
     * Record a decision on an imported oddity.
     *
     * The importer already applied a best guess and said so, so most options are
     * a confirmation and change nothing. The ones that do change data are listed
     * explicitly below rather than inferred from the label.
     *
     * Resolved items are kept, never deleted: they are the record of which parts
     * of this history were reconstructed rather than written down at the time.
     */
    fun resolveReview(
        log: FitLog,
        item: ReviewItem,
        optionId: String,
        note: String? = null,
        today: String,
    ): FitLog {
        val line = item.sourceLines.firstOrNull()
        val withData = when (optionId) {
            "heavy", "light" -> setTrack(log, line, optionId)
            "none" -> setTrack(log, line, null)
            "unknown" -> markUnknown(log, line)
            // The card offers to keep the date as originally written, so it has to
            // actually do that. The original is recoverable from the verbatim line.
            "revert" -> revertDate(log, line, item.sourceRaw)
            else -> log
        }
        return withData.copy(review = withData.review.map {
            if (it.id != item.id) it
            else it.copy(
                resolved = true,
                // Named, not positional: ReviewResolution has a `value` field between
                // these two, so a positional note would land in the wrong key.
                resolution = ReviewResolution(optionId = optionId, note = note),
                resolvedAt = today,
            )
        })
    }

    /** A decision you made outranks anything a re-run of the importer would infer. */
    fun setTrack(log: FitLog, line: Int?, track: String?): FitLog {
        if (line == null) return log
        return log.copy(sessions = log.sessions.map { s ->
            s.copy(entries = s.entries.map { e ->
                if (e.source?.line != line) e
                else e.copy(
                    track = track, trackOrigin = "manual", trackConfidence = null,
                    needsReview = false,
                )
            })
        })
    }

    /**
     * The honest escape hatch for a line nobody can decode: it happened, the
     * detail is gone, and it must not claim a record.
     */
    fun markUnknown(log: FitLog, line: Int?): FitLog {
        if (line == null) return log
        return log.copy(sessions = log.sessions.map { s ->
            s.copy(entries = s.entries.map { e ->
                if (e.source?.line != line) e
                else e.copy(sets = emptyList(), excludeFromPr = true, needsReview = false)
            })
        })
    }

    /**
     * Restore the date exactly as it was written, undoing a DATE_FIXES correction.
     * Returns the log unchanged if the line does not start with a parseable date,
     * rather than guessing.
     */
    fun revertDate(log: FitLog, line: Int?, sourceRaw: String): FitLog {
        if (line == null) return log
        val m = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2})""").find(sourceRaw.trim()) ?: return log
        val (mo, da, yy) = m.destructured
        val iso = "20%02d-%02d-%02d".format(yy.toInt(), mo.toInt(), da.toInt())
        return log.copy(sessions = log.sessions.map { s ->
            if (s.source?.line != line) s else s.copy(date = iso)
        })
    }

    /** The session a review item points at, by source line. */
    fun sessionForLine(log: FitLog, line: Int): Session? = log.sessions.firstOrNull { s ->
        s.source?.line == line || s.entries.any { it.source?.line == line }
    }
}
