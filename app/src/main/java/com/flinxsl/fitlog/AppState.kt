package com.flinxsl.fitlog

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/**
 * The single place mutable state lives.
 *
 * A ViewModel rather than remember{} because rotating the phone or the system
 * reclaiming the activity destroys and recreates it, and remembered state dies
 * with it. This survives.
 *
 * Compose's own mutableStateOf rather than StateFlow: snapshot state already does
 * change observation, and StateFlow would drag in the whole Flow and coroutine
 * vocabulary to buy operator chaining this app never needs.
 */
class AppState(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    var log by mutableStateOf(FitLog())
        private set

    /** Non-null when the file was damaged and a backup was used. Shown as a banner. */
    var loadWarning by mutableStateOf<String?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    init {
        log = store.load()
        loadWarning = store.loadWarning
        loading = false
    }

    /** Every mutation goes through here, so nothing can change without being saved. */
    fun update(transform: (FitLog) -> FitLog) {
        val next = transform(log)
        store.save(next)
        log = next
    }

    fun dismissWarning() { loadWarning = null }

    val storePath: String get() = store.path
}
