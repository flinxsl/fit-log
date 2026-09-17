package com.flinxsl.fitlog

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders stored sets back into the terse notation the log has always used.
 *
 * This is the app's display language. History, the collapsed rows on the session
 * screen, and text export all go through here, so "Row 170 (-1/-2)" looks the
 * same everywhere and matches what you would have written on paper.
 *
 * It is a port of emit_entry() in importer/fitlog_import.py and must agree with
 * it. FormatTest checks that against the real log.
 */
object Format {

    /** 265 not 265.0, but 17.5 stays 17.5 and 5.5 reps stay 5.5. */
    fun num(v: Double?): String {
        if (v == null) return ""
        return if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString()
        else v.toString().trimEnd('0').trimEnd('.')
    }

    fun num(v: Int?): String = v?.toString() ?: ""

    /**
     * One exercise line, e.g. "Feet up 155/145 3/2 (-1/-2)".
     *
     * @param progSets the routine's set count, used only to decide whether a
     *   non-default count needs writing out as "xN". Pass null to omit that.
     */
    fun entry(e: Entry, ex: Exercise?, progSets: Int? = null): String {
        val name = ex?.displayName ?: e.exerciseId
        val metric = ex?.metric ?: Metric.WEIGHT_REPS

        if (!e.performed) {
            if (metric == Metric.TIME) return "$name no"
            val head = e.prescription.load?.let { "$name ${num(it)} X" } ?: "$name X"
            return head + (e.failureReason?.let { " $it" } ?: "")
        }

        // Performed but nothing recorded: "Row yes (need form check)"
        if (e.sets.isEmpty()) {
            return "$name yes" + (if (e.notes.isNotBlank()) " (${e.notes})" else "")
        }

        if (metric == Metric.TIME) return "$name ${num(e.sets[0].durationSec)}"

        if (metric == Metric.REPS) {
            val parts = e.sets.joinToString("/") { if (it.failed) "X" else num(it.reps) }
            return "$name $parts" + (e.failureReason?.let { " $it" } ?: "")
        }

        // Weight work: collapse consecutive equal loads into groups, so five sets
        // at one weight print as "190" and a drop set prints as "190/185 3/2".
        val groups = mutableListOf<Pair<Double?, MutableList<SetRecord>>>()
        for (s in e.sets) {
            val last = groups.lastOrNull()
            if (last != null && last.first == s.load.value) last.second.add(s)
            else groups.add(s.load.value to mutableListOf(s))
        }

        val sb = StringBuilder(name).append(' ')
        sb.append(groups.joinToString("/") { num(it.first) })
        if (groups.size > 1) {
            sb.append(' ').append(groups.joinToString("/") { it.second.size.toString() })
        } else if (progSets != null && e.sets.size != progSets) {
            sb.append(" x").append(e.sets.size)
        }

        // Deviations. null means the set went to plan and prints nothing.
        val devs: List<String?> = e.sets.map { s ->
            when {
                s.failed -> "X"
                s.targetReps != null && s.reps != null && s.reps != s.targetReps ->
                    num(s.reps - s.targetReps)
                else -> null
            }
        }

        if (devs.any { it != null }) {
            val idxs = devs.indices.filter { devs[it] != null }
            val trailing = idxs == (devs.size - idxs.size until devs.size).toList()
            // A sparse list is read back as applying to the TRAILING sets, so it is
            // only honest when the deviations really are trailing. Otherwise write
            // the full positional list, zeros included.
            val list = if (trailing && e.setsAttribution == Attribution.UNORDERED) {
                idxs.map { devs[it]!! }
            } else {
                devs.map { it ?: "0" }
            }
            if (list.isNotEmpty()) sb.append(" (").append(list.joinToString("/")).append(')')
        }

        when {
            e.failureReason != null -> sb.append(' ').append(e.failureReason)
            e.notes.isNotBlank() -> sb.append(' ').append(e.notes)
        }
        return sb.toString()
    }

    /** The date line, e.g. "9/14/26 A" or "8/27/25 sick". */
    fun sessionHeader(s: Session): String {
        val d = LocalDate.parse(s.date)
        val sb = StringBuilder("${d.monthValue}/${d.dayOfMonth}/${d.format(YY)}")
        if (!s.dayLabel.isNullOrBlank()) sb.append(' ').append(s.dayLabel)
        if (s.notes.isNotBlank()) sb.append(' ').append(s.notes)
        return sb.toString()
    }

    /** A whole session as it appeared in text_log: date line, then one line per exercise. */
    fun session(s: Session, log: FitLog): List<String> =
        listOf(sessionHeader(s)) + s.entries.map { entry(it, log.exercise(it.exerciseId), progSets(s, it, log)) }

    /** The entire log in text_log format. Used by the text export. */
    fun all(log: FitLog): String =
        log.sessions.sortedBy { it.date }.joinToString("\n\n") { session(it, log).joinToString("\n") }

    private fun progSets(s: Session, e: Entry, log: FitLog): Int? =
        log.routines.firstOrNull { it.id == s.routineId }
            ?.days?.firstOrNull { it.label == (s.dayLabel ?: "-") }
            ?.slots?.firstOrNull { it.exerciseId == e.exerciseId }
            ?.sets

    // --- human-facing variants, for places the raw notation is too terse -----

    /** "5x5 @ 265 lb", "3x8 @ 25 lb per hand", "3 sets to failure", "85 seconds". */
    fun prescriptionSummary(e: Entry, ex: Exercise?): String {
        val p = e.prescription
        if (ex?.metric == Metric.TIME) return "${num(e.sets.firstOrNull()?.durationSec)} seconds"
        val scheme = if (p.reps == null) "${p.sets} sets to failure" else "${p.sets}x${num(p.reps)}"
        val load = p.load ?: return scheme
        return "$scheme @ ${num(load)}${loadSuffix(p.loadKind, p.unit ?: "lb")}"
    }

    fun loadSuffix(kind: String, unit: String): String = when (kind) {
        LoadKind.DUMBBELL_EACH -> " $unit per hand"
        LoadKind.BAR_ADDED -> " $unit on the bar"
        LoadKind.BODYWEIGHT_PLUS -> " $unit added"
        LoadKind.BODYWEIGHT, LoadKind.TIME_ONLY -> ""
        else -> " $unit"
    }

    /** "Wed 16 Sep 2026" */
    fun longDate(iso: String): String =
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))

    private val YY = DateTimeFormatter.ofPattern("yy", Locale.US)
}
