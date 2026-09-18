package com.flinxsl.fitlog

/**
 * Every edit you can make to a workout in progress, as pure functions.
 *
 * Deliberately free of Android: AppState is a thin wrapper that holds the current
 * Session and calls in here. That keeps the rules testable on the JVM, which
 * matters because this is where a wrong answer is silent - a set recorded one rep
 * off looks exactly like a set recorded correctly.
 */
object SessionEdit {

    fun entry(s: Session, ei: Int, f: (Entry) -> Entry): Session =
        if (ei !in s.entries.indices) s
        else s.copy(entries = s.entries.toMutableList().also { it[ei] = f(it[ei]) })

    private fun set(s: Session, ei: Int, si: Int, f: (SetRecord) -> SetRecord): Session =
        entry(s, ei) { e ->
            if (si !in e.sets.indices) e
            else e.copy(
                sets = e.sets.toMutableList().also { it[si] = f(it[si]) },
                // Editing a specific set means we know exactly which one it was, so
                // the importer's trailing-placement convention no longer applies.
                setsAttribution = Attribution.POSITIONAL,
            )
        }

    /**
     * The primary gesture: one tap takes a rep off. Of ~250 deviations in the real
     * log, 77 are exactly -1 and 45 are -2, so this covers most of them in one or
     * two taps. Going below zero wraps back to the prefilled value, so you can
     * never get stuck needing a menu to undo.
     */
    fun tap(s: Session, original: Session?, ei: Int, si: Int): Session {
        val start = original?.entries?.getOrNull(ei)?.sets?.getOrNull(si)
        return set(s, ei, si) { x ->
            if (x.isTimed) {
                val base = start?.durationSec ?: x.durationSec ?: 0.0
                val next = (x.durationSec ?: 0.0) - 1.0
                x.copy(durationSec = if (next < 0) base else next, failed = false, completed = true)
            } else {
                val base = start?.reps ?: x.targetReps ?: 0.0
                val next = if (x.failed) base else (x.reps ?: 0.0) - 1.0
                val reps = if (next < 0) base else next
                x.copy(
                    reps = reps, failed = false, failureReason = null,
                    completed = x.targetReps == null || reps >= x.targetReps,
                )
            }
        }
    }

    fun reps(s: Session, ei: Int, si: Int, reps: Double): Session =
        set(s, ei, si) { x ->
            x.copy(
                reps = reps, failed = false, failureReason = null,
                completed = x.targetReps == null || reps >= x.targetReps,
            )
        }

    fun duration(s: Session, ei: Int, si: Int, seconds: Double): Session =
        set(s, ei, si) { it.copy(durationSec = seconds, failed = false, completed = true) }

    /** Abandoned, not merely short: zero reps, failed flag, and an optional reason. */
    fun fail(s: Session, ei: Int, si: Int, reason: String?): Session =
        set(s, ei, si) { it.copy(reps = 0.0, completed = false, failed = true, failureReason = reason) }

    /**
     * Change the load from this set onward. That is precisely what "155/145 3/2"
     * means, and it appears in most recent B days, so it is a first-class action.
     */
    fun weightFrom(s: Session, ei: Int, si: Int, value: Double): Session =
        entry(s, ei) { e ->
            e.copy(
                sets = e.sets.mapIndexed { i, x ->
                    if (i >= si && x.load.value != null) x.copy(load = x.load.copy(value = value)) else x
                },
                setsAttribution = Attribution.POSITIONAL,
            )
        }

    /** Move every set's load, preserving the gaps in a drop scheme. */
    fun topWeight(s: Session, ei: Int, value: Double): Session =
        entry(s, ei) { e ->
            val top = e.topLoad ?: return@entry e
            val shift = value - top
            e.copy(
                sets = e.sets.map { x ->
                    x.load.value?.let { v -> x.copy(load = x.load.copy(value = maxOf(0.0, v + shift))) } ?: x
                },
                prescription = e.prescription.copy(load = value),
            )
        }

    fun nudge(s: Session, ei: Int, delta: Double): Session {
        val top = s.entries.getOrNull(ei)?.topLoad ?: return s
        return topWeight(s, ei, maxOf(0.0, top + delta))
    }

    /**
     * Give an entry some sets to edit.
     *
     * The importer deliberately leaves truncated and unquantified lines with zero
     * sets rather than inventing data - but that also means there is nothing on
     * screen to correct. This builds the sets so they can be edited, using a
     * starting load the caller worked out from the surrounding sessions.
     */
    fun materialize(
        s: Session, ei: Int, count: Int, reps: Double?, loadKind: String,
        load: Double?, unit: String, seconds: Double?,
    ): Session = entry(s, ei) { e ->
        if (e.sets.isNotEmpty()) e
        else e.copy(
            sets = (1..count.coerceAtLeast(1)).map { i ->
                if (seconds != null) {
                    SetRecord(i, Load(LoadKind.TIME_ONLY), durationSec = seconds, completed = true)
                } else {
                    SetRecord(
                        i,
                        if (load == null) Load(loadKind) else Load(loadKind, load, unit),
                        reps = reps, targetReps = reps, completed = true,
                    )
                }
            },
            setsAttribution = Attribution.POSITIONAL,
            // It is being filled in by hand now, so it is no longer the importer's guess.
            prescription = e.prescription.copy(
                sets = count, reps = reps, load = load, origin = "manual", confidence = "certain",
            ),
            excludeFromPr = false,
        )
    }

    fun skip(s: Session, ei: Int, skipped: Boolean, reason: String? = null): Session =
        entry(s, ei) { it.copy(performed = !skipped, failureReason = if (skipped) reason else null) }
}
