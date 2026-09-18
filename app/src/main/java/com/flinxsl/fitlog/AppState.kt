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
        draft = Prefill.session(log, dayLabel, today)
        go(Screen.Session)
    }

    fun discardSession() {
        draft = null
        back()
    }

    /** The only path by which a session enters the log. */
    fun finishSession() {
        val s = draft ?: return
        update { it.copy(sessions = it.sessions + s) }
        draft = null
        screen = Screen.Home
        backStack.clear()
    }

    // --- editing the draft --------------------------------------------------

    private fun editEntry(index: Int, transform: (Entry) -> Entry) {
        val s = draft ?: return
        if (index !in s.entries.indices) return
        draft = s.copy(entries = s.entries.toMutableList().also { it[index] = transform(it[index]) })
    }

    /** Set every set's load, keeping any per-set offsets from a drop scheme. */
    fun setTopWeight(index: Int, value: Double) {
        editEntry(index) { e ->
            val top = e.topLoad ?: return@editEntry e
            val shift = value - top
            e.copy(
                sets = e.sets.map { s ->
                    s.load.value?.let { v -> s.copy(load = s.load.copy(value = maxOf(0.0, v + shift))) } ?: s
                },
                prescription = e.prescription.copy(load = value),
            )
        }
    }

    fun nudgeWeight(index: Int, delta: Double) {
        val e = draft?.entries?.getOrNull(index) ?: return
        val top = e.topLoad ?: return
        setTopWeight(index, maxOf(0.0, top + delta))
    }

    /** Switching track re-prescribes from that track's own history. */
    fun setTrack(index: Int, track: String) {
        val s = draft ?: return
        val e = s.entries.getOrNull(index) ?: return
        val slot = slotFor(s, e.exerciseId) ?: return
        val fresh = Prefill.entry(log, slot, track).copy(order = e.order)
        draft = s.copy(entries = s.entries.toMutableList().also { it[index] = fresh })
    }

    fun setSkipped(index: Int, skipped: Boolean, reason: String? = null) {
        editEntry(index) { it.copy(performed = !skipped, failureReason = if (skipped) reason else null) }
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

    val storePath: String get() = store.path
}
