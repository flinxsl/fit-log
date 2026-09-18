package com.flinxsl.fitlog

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.time.LocalDate

/** Where we are. A sealed type rather than Navigation-Compose: seven screens,
 *  no deep links, and this carries typed arguments without route strings. */
sealed interface Screen {
    data object Home : Screen
    data object Session : Screen
    data object History : Screen
    data object Routines : Screen
    data object Settings : Screen
    data object Review : Screen
    data class DayEditor(val label: String) : Screen
    data class ExerciseEditor(val label: String, val exerciseId: String?) : Screen
}

/**
 * The single place mutable state lives.
 *
 * A ViewModel rather than remember{} because rotating the phone destroys and
 * recreates the activity, and remembered state dies with it.
 *
 * Compose's mutableStateOf rather than StateFlow: snapshot state already does
 * change observation, and StateFlow would drag in the whole Flow vocabulary to
 * buy operator chaining this app never needs.
 */
class AppState(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    var log by mutableStateOf(FitLog())
        private set

    var loadWarning by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    private var backStack = mutableListOf<Screen>()

    /**
     * The workout in progress. Held apart from `log` on purpose: editing it in
     * place would mean rebuilding the whole session list on every tap, and it
     * keeps "nothing is written until you finish" true by construction.
     */
    var draft by mutableStateOf<Session?>(null)
        private set

    /** The pristine starting point. Tapping a set down past zero wraps back to this. */
    private var draftOriginal: Session? = null

    /**
     * Non-null when the draft is an EDIT of a session already in the log, rather
     * than a new one. Editing a past session and resolving a flagged import are
     * the same operation, so both load into the draft and reuse the whole
     * session screen instead of needing a second editor.
     */
    var editingId by mutableStateOf<String?>(null)
        private set

    /** Which exercise is open. Only ever one, so the list stays scannable. */
    var expanded by mutableStateOf<Int?>(null)
        private set

    init {
        log = store.load()
        loadWarning = store.loadWarning
        loading = false
    }

    // --- navigation ---------------------------------------------------------

    fun go(s: Screen) {
        backStack.add(screen)
        screen = s
    }

    /** True if it handled the back press; false means let the system exit. */
    fun back(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        screen = prev
        return true
    }

    // --- the workout --------------------------------------------------------

    fun suggestedDay(): String? = Prefill.suggestedDay(log)

    fun availableDays(): List<String> =
        Prefill.currentRoutine(log)?.days?.map { it.label }?.filter { it != "-" } ?: emptyList()

    fun startSession(dayLabel: String, today: String = LocalDate.now().toString()) {
        val s = Prefill.session(log, dayLabel, today)
        draft = s
        draftOriginal = s
        editingId = null
        expanded = null
        go(Screen.Session)
    }

    /** Open a session already in the log for correction. */
    fun editSession(id: String) {
        val s = log.sessions.firstOrNull { it.id == id } ?: return
        draft = s
        draftOriginal = s
        editingId = id
        expanded = null
        go(Screen.Session)
    }

    /** Jump straight to whichever session a review item refers to. */
    fun editSessionForLine(line: Int): Boolean {
        val s = LogEdit.sessionForLine(log, line) ?: return false
        editSession(s.id)
        expanded = s.entries.indexOfFirst { it.source?.line == line }.takeIf { it >= 0 }
        return true
    }

    fun deleteSession(id: String) {
        update { LogEdit.deleteSession(it, id) }
        draft = null
        draftOriginal = null
        editingId = null
        screen = Screen.Home
        backStack.clear()
    }

    fun toggleExpanded(index: Int) {
        expanded = if (expanded == index) null else index
    }

    fun discardSession() {
        draft = null
        draftOriginal = null
        editingId = null
        expanded = null
        back()
    }

    /** The only path by which a session enters or changes in the log. */
    fun finishSession() {
        val s = draft ?: return
        val id = editingId
        update { LogEdit.upsertSession(it, s, id) }
        draft = null
        editingId = null
        draftOriginal = null
        expanded = null
        screen = Screen.Home
        backStack.clear()
    }

    // --- editing the draft --------------------------------------------------

    // Edits delegate to SessionEdit, which is pure and JVM-testable.

    private fun edit(f: (Session) -> Session) { draft = draft?.let(f) }

    fun setTopWeight(index: Int, value: Double) = edit { SessionEdit.topWeight(it, index, value) }
    fun nudgeWeight(index: Int, delta: Double) = edit { SessionEdit.nudge(it, index, delta) }
    fun setSkipped(index: Int, skipped: Boolean, reason: String? = null) =
        edit { SessionEdit.skip(it, index, skipped, reason) }
    fun tapSet(ei: Int, si: Int) = edit { SessionEdit.tap(it, draftOriginal, ei, si) }
    fun setReps(ei: Int, si: Int, reps: Double) = edit { SessionEdit.reps(it, ei, si, reps) }
    fun setDuration(ei: Int, si: Int, sec: Double) = edit { SessionEdit.duration(it, ei, si, sec) }
    fun failSet(ei: Int, si: Int, reason: String?) = edit { SessionEdit.fail(it, ei, si, reason) }
    fun setWeightFrom(ei: Int, si: Int, v: Double) = edit { SessionEdit.weightFrom(it, ei, si, v) }

    /** Switching track re-prescribes from that track's own history. */
    fun setTrack(index: Int, track: String) {
        val s = draft ?: return
        val e = s.entries.getOrNull(index) ?: return
        val slot = slotFor(s, e.exerciseId) ?: return
        val fresh = Prefill.entry(log, slot, track).copy(order = e.order)
        draft = s.copy(entries = s.entries.toMutableList().also { it[index] = fresh })
    }

    /** Confirm everything as prescribed, for anyone who prefers a positive tap. */
    fun markAllAsPlanned() {
        draft = draftOriginal?.copy(notes = draft?.notes ?: "")
        expanded = null
    }

    /** True when nothing was touched, so FINISH can ask before inventing a workout. */
    fun draftUntouched(): Boolean {
        val a = draft ?: return true
        val b = draftOriginal ?: return true
        return a.entries == b.entries
    }

    fun setSessionNote(note: String) {
        draft = draft?.copy(notes = note)
    }

    fun slotFor(s: Session, exerciseId: String): Slot? =
        log.routines.firstOrNull { it.id == s.routineId }
            ?.days?.firstOrNull { it.label == s.dayLabel }
            ?.slots?.firstOrNull { it.exerciseId == exerciseId }

    fun increment(s: Session, e: Entry): Double {
        val slot = slotFor(s, e.exerciseId)
        return if (slot != null) Prefill.increment(slot, log.exercise(e.exerciseId))
        else if (e.prescription.loadKind == LoadKind.BARBELL_TOTAL) 5.0 else 2.5
    }

    // --- persistence --------------------------------------------------------

    fun update(transform: (FitLog) -> FitLog) {
        val next = transform(log)
        store.save(next)
        log = next
    }

    fun dismissWarning() { loadWarning = null }

    // --- routine editing ----------------------------------------------------

    /** The routine currently in force. All edits below apply to it. */
    fun routine(): Routine? = Prefill.currentRoutine(log)

    fun day(label: String): RoutineDay? = routine()?.days?.firstOrNull { it.label == label }

    private fun editRoutine(f: (Routine) -> Routine) {
        val r = routine() ?: return
        update { l -> l.copy(routines = l.routines.map { if (it.id == r.id) f(it) else it }) }
    }

    private fun editDay(label: String, f: (RoutineDay) -> RoutineDay) {
        editRoutine { r -> r.copy(days = r.days.map { if (it.label == label) f(it) else it }) }
    }

    fun addDay(label: String) {
        val r = routine()
        if (r == null) {
            update { it.copy(routines = it.routines + Routine(
                id = "routine-1", name = "My routine", activeFrom = LocalDate.now().toString(),
                days = listOf(RoutineDay(label, label)))) }
        } else {
            editRoutine { it.copy(days = it.days + RoutineDay(label, label)) }
        }
    }

    fun renameDay(label: String, newLabel: String, name: String) {
        editDay(label) { it.copy(label = newLabel, name = name) }
    }

    fun deleteDay(label: String) {
        editRoutine { r -> r.copy(days = r.days.filterNot { it.label == label }) }
    }

    /** Arrow buttons rather than drag: Compose has no first-party reorderable list. */
    fun moveSlot(label: String, from: Int, to: Int) {
        editDay(label) { d ->
            if (from !in d.slots.indices || to !in d.slots.indices) d
            else d.copy(slots = d.slots.toMutableList().also { it.add(to, it.removeAt(from)) })
        }
    }

    fun deleteSlot(label: String, exerciseId: String) {
        editDay(label) { d -> d.copy(slots = d.slots.filterNot { it.exerciseId == exerciseId }) }
    }

    /** Creates or updates both the catalog entry and the slot, in one save. */
    fun saveExercise(
        label: String, originalId: String?, displayName: String, metric: String,
        loadKind: String, sets: Int, reps: Double?, tracked: Boolean,
        increment: Double?, autoProgress: Boolean, startWeight: Double?,
    ) {
        val id = originalId ?: slugFor(displayName)
        val tracks = if (tracked) listOf("heavy", "light") else emptyList()
        update { l ->
            val exists = l.exercises.any { it.id == id }
            val catalog = if (exists) {
                l.exercises.map {
                    if (it.id == id) it.copy(displayName = displayName, metric = metric,
                        defaultLoadKind = loadKind, tracks = tracks) else it
                }
            } else {
                l.exercises + Exercise(id, displayName, metric = metric,
                    defaultLoadKind = loadKind, tracks = tracks,
                    firstSeen = LocalDate.now().toString())
            }
            val slot = Slot(id, sets, reps, loadKind, tracks, increment, autoProgress, startWeight)
            val routines = l.routines.map { r ->
                if (r.id != routine()?.id) r
                else r.copy(days = r.days.map { d ->
                    if (d.label != label) d
                    else if (d.slots.any { it.exerciseId == id })
                        d.copy(slots = d.slots.map { if (it.exerciseId == id) slot else it })
                    else d.copy(slots = d.slots + slot)
                })
            }
            l.copy(exercises = catalog, routines = routines)
        }
    }

    private fun slugFor(name: String): String {
        val base = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "exercise" }
        if (log.exercises.none { it.id == base }) return base
        var n = 2
        while (log.exercises.any { it.id == "$base-$n" }) n++
        return "$base-$n"
    }

    // --- settings, export and import ----------------------------------------

    fun setUnit(unit: String) = update { it.copy(settings = it.settings.copy(unit = unit)) }
    fun setRestSeconds(sec: Int) = update { it.copy(settings = it.settings.copy(restSeconds = sec)) }

    // --- import review queue ------------------------------------------------

    fun openReviews(): List<ReviewItem> = log.review.filter { !it.resolved }
        .sortedBy { listOf("error", "warn", "info").indexOf(it.severity) }

    /**
     * Record a decision. The importer already applied its best guess, so most
     * options are a confirmation; the ones that change data say so explicitly.
     * Resolved items are kept, not deleted - they are the record of which parts
     * of this history were reconstructed rather than written down.
     */
    fun resolveReview(item: ReviewItem, optionId: String, note: String? = null) {
        update { LogEdit.resolveReview(it, item, optionId, note, LocalDate.now().toString()) }
    }

    fun exportJson(): String = store.exportJson(log)
    fun exportText(): String = store.exportText(log)

    /**
     * Replace everything from a file. Refuses rather than half-applying, and the
     * previous state is still on disk as .bak either way.
     */
    fun importJson(text: String): String {
        return try {
            val parsed = store.parse(text)
            if (parsed.sessions.isEmpty() && parsed.exercises.isEmpty()) {
                "That file has no sessions or exercises in it. Nothing was changed."
            } else {
                update { parsed }
                "Imported ${parsed.sessions.size} sessions and ${parsed.exercises.size} exercises."
            }
        } catch (e: Exception) {
            "Could not read that file: ${e.message?.take(120)}. Nothing was changed."
        }
    }

    val storePath: String get() = store.path
}
