package com.flinxsl.fitlog

/**
 * Decides what today's workout should look like before you touch anything.
 *
 * The rule that makes this work: **prescribe from what you actually lifted last
 * time, not from the routine.** The routine says only which exercises, in what
 * order, loaded how. Every number comes from history.
 *
 * That one decision buys a lot for free. Two-weight schemes like "155/145 3/2"
 * reproduce themselves because the whole set list is copied. Deloads stick,
 * because after you drop the weight the new weight is what history says. A
 * missed week changes nothing. And light/heavy waves work, because the lookup
 * is per track.
 */
object Prefill {

    /** A whole session, ready to edit. Nothing is written until FINISH. */
    fun session(log: FitLog, dayLabel: String, date: String): Session {
        val routine = currentRoutine(log)
        val day = routine?.days?.firstOrNull { it.label == dayLabel }
        val entries = day?.slots?.mapIndexed { i, slot ->
            entry(log, slot, defaultTrack(log, slot))
                .copy(order = i + 1)
        } ?: emptyList()

        return Session(
            id = "s-$date-${(log.sessions.count { it.date == date }) + 1}",
            date = date,
            routineId = routine?.id ?: "",
            dayLabel = dayLabel,
            entries = entries,
        )
    }

    /** One exercise, prescribed from its own history on the given track. */
    fun entry(log: FitLog, slot: Slot, track: String?): Entry {
        val ex = log.exercise(slot.exerciseId)
        val last = lastEntry(log, slot.exerciseId, track)

        val sets: List<SetRecord> = when {
            last == null -> firstTimeSets(slot, ex)
            else -> {
                val base = last.sets
                val bumped = if (wasComplete(last)) base.map { bump(it, slot, ex) } else base
                resize(bumped, slot.sets)
            }
        }

        return Entry(
            exerciseId = slot.exerciseId,
            track = track,
            trackOrigin = if (track != null) "manual" else null,
            prescription = Prescription(
                sets = sets.size,
                reps = slot.reps,
                loadKind = slot.loadKind,
                load = sets.mapNotNull { it.load.value }.maxOrNull(),
                unit = log.settings.unit,
                origin = "routine",
                confidence = "certain",
            ),
            sets = sets.mapIndexed { i, s -> s.copy(i = i + 1) },
            setsAttribution = Attribution.POSITIONAL,
        )
    }

    /**
     * True when every set met what was asked of it **that day** - not what the
     * routine's baseline says. Measuring against the baseline would make a lift
     * that has already climbed past it progress forever: once Dips reached 13,
     * a 12-rep baseline would call every future session complete.
     */
    fun wasComplete(e: Entry): Boolean =
        e.performed && e.sets.isNotEmpty() && e.sets.all { it.completed && !it.failed }

    /** The most recent time this exercise was done on this track. */
    fun lastEntry(log: FitLog, exerciseId: String, track: String?): Entry? =
        log.sessions.sortedByDescending { it.date }
            .firstNotNullOfOrNull { s ->
                s.entries.firstOrNull {
                    it.exerciseId == exerciseId && it.sets.isNotEmpty() &&
                        (track == null || it.track == track)
                }
            }

    /**
     * Which track is due: the one whose last session is older. A suggestion only -
     * the real rotation drifts (deadlift ran 1:1, then 2:1, then 3:1), so this is
     * always one tap to override and the override is what gets stored.
     */
    fun defaultTrack(log: FitLog, slot: Slot): String? {
        if (slot.tracks.isEmpty()) return null
        val lastSeen = slot.tracks.associateWith { t ->
            log.sessions.filter { s -> s.entries.any { it.exerciseId == slot.exerciseId && it.track == t } }
                .maxOfOrNull { it.date } ?: ""
        }
        return lastSeen.minByOrNull { it.value }?.key ?: slot.tracks.first()
    }

    /** Day after the last one logged, cycling the routine. Suggested, never enforced. */
    fun suggestedDay(log: FitLog): String? {
        val routine = currentRoutine(log) ?: return null
        val labels = routine.days.map { it.label }.filter { it != "-" }
        if (labels.isEmpty()) return null
        val lastLabel = log.sessions.maxByOrNull { it.date }?.dayLabel
        val i = labels.indexOf(lastLabel)
        return if (i < 0) labels.first() else labels[(i + 1) % labels.size]
    }

    fun currentRoutine(log: FitLog): Routine? =
        log.routines.lastOrNull { it.activeTo == null } ?: log.routines.lastOrNull()

    // --- helpers ------------------------------------------------------------

    /**
     * How much to add after a clean session. Weight for loaded lifts, but reps
     * for bodyweight and seconds for a timed hold - one field, one meaning per
     * exercise kind, which is what the real log does.
     */
    fun increment(slot: Slot, ex: Exercise?): Double = when (slot.loadKind) {
        LoadKind.BARBELL_TOTAL -> 5.0
        LoadKind.BAR_ADDED, LoadKind.DUMBBELL_EACH, LoadKind.BODYWEIGHT_PLUS -> 2.5
        else -> 1.0   // a rep, or a second
    }

    private fun bump(s: SetRecord, slot: Slot, ex: Exercise?): SetRecord {
        val inc = increment(slot, ex)
        return when {
            s.isTimed -> s.copy(durationSec = (s.durationSec ?: 0.0) + inc)
            s.load.value != null -> s.copy(load = s.load.copy(value = s.load.value + inc))
            // Bodyweight: progress by adding a rep, since there is no load to add to.
            else -> s.copy(reps = (s.reps ?: 0.0) + inc)
        }
    }

    private fun firstTimeSets(slot: Slot, ex: Exercise?): List<SetRecord> {
        val kind = slot.loadKind
        return (1..slot.sets).map { i ->
            when {
                ex?.metric == Metric.TIME ->
                    SetRecord(i, Load(LoadKind.TIME_ONLY), durationSec = 30.0)
                kind == LoadKind.BODYWEIGHT ->
                    SetRecord(i, Load(LoadKind.BODYWEIGHT), reps = slot.reps, targetReps = slot.reps)
                else -> SetRecord(
                    i, Load(kind, 45.0, "lb"),
                    reps = slot.reps, targetReps = slot.reps,
                )
            }
        }
    }

    /** Trim to the routine's count, or extend by repeating the last set. */
    private fun resize(sets: List<SetRecord>, n: Int): List<SetRecord> = when {
        sets.isEmpty() || n <= 0 -> sets
        sets.size == n -> sets
        sets.size > n -> sets.take(n)
        else -> sets + List(n - sets.size) { sets.last() }
    }
}
