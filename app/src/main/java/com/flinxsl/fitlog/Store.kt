package com.flinxsl.fitlog

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Reads and writes the one JSON file that is the whole app's state.
 *
 * The data is tiny by phone standards, so the entire file is held in memory and
 * rewritten whenever something changes. ~150 sessions today, growing maybe 30 KB
 * a month; a full rewrite is single-digit milliseconds. That is why there is no
 * database here: Room would add entities, DAOs, an annotation processor and a
 * migration class per change, to query a dataset that fits in a fraction of RAM.
 *
 * Saves run on the calling thread on purpose. At roughly forty writes per workout
 * it is imperceptible, and it keeps coroutines off the critical path. If it ever
 * does feel slow, the fix is one line at the call site:
 *     viewModelScope.launch(Dispatchers.IO) { store.save(log) }
 */
class Store(private val ctx: Context) {

    private val dir: File get() = ctx.filesDir
    private val file: File get() = File(dir, NAME)
    private val backup: File get() = File(dir, "$NAME.bak")
    private val temp: File get() = File(dir, "$NAME.tmp")

    /** Set when the main file was unreadable and the backup was used instead. */
    var loadWarning: String? = null
        private set

    private val json = Json {
        prettyPrint = true
        // A human may hand-edit this file, and an older build must tolerate a file
        // written by a newer one. Between them these cover almost every schema
        // change without needing a migration at all.
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Never returns silently-empty data when something went wrong. An empty screen
     * after thirteen months of training is indistinguishable from "the app works,
     * your data is gone", so a failure sets loadWarning and the UI says so.
     */
    fun load(): FitLog {
        loadWarning = null

        readOrNull(file)?.let { return it }

        if (file.exists()) {
            loadWarning = "The log file could not be read. Restored the previous save."
            Log.w(TAG, "primary unreadable, trying backup")
        }

        readOrNull(backup)?.let {
            if (loadWarning == null) loadWarning = "Restored the previous save."
            return it
        }

        seedFromAssets()?.let {
            Log.i(TAG, "seeded from bundled asset: ${it.sessions.size} sessions")
            save(it)
            return it
        }

        if (file.exists() || backup.exists()) {
            loadWarning = "The log file is damaged and could not be restored. " +
                "Nothing has been overwritten; import a backup from Settings."
        }
        return FitLog()
    }

    private fun readOrNull(f: File): FitLog? = try {
        if (!f.exists() || f.length() == 0L) null
        else json.decodeFromString<FitLog>(f.readText())
    } catch (e: Exception) {
        Log.w(TAG, "failed to read ${f.name}: ${e.message}")
        null
    }

    /** Debug builds ship the real log as an asset; release builds ship nothing. */
    private fun seedFromAssets(): FitLog? = try {
        ctx.assets.open(NAME).use { json.decodeFromString<FitLog>(it.readBytes().decodeToString()) }
    } catch (e: Exception) {
        null   // no asset is normal, not an error
    }

    /**
     * Atomic write. The ordering matters and each step earns its place:
     *   1. serialise fully in memory, so a serialisation error cannot truncate the file
     *   2. write to a temp file, flush, then fsync - without the fsync a power loss
     *      can leave a renamed-but-empty file, which is the one outcome worse than a crash
     *   3. move the current file aside as .bak
     *   4. rename temp over the real name; rename within one filesystem is atomic
     */
    fun save(log: FitLog) {
        val text = json.encodeToString(log)

        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }

        if (file.exists()) {
            backup.delete()
            if (!file.renameTo(backup)) file.copyTo(backup, overwrite = true)
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    /** Serialised form, for the export flow and for `adb shell run-as ... cat`. */
    fun exportJson(log: FitLog): String = json.encodeToString(log)

    /** The whole log in text_log notation, so the original format is never a dead end. */
    fun exportText(log: FitLog): String = Format.all(log)

    val path: String get() = file.absolutePath

    companion object {
        const val NAME = "fitlog.json"
        private const val TAG = "fitlog.Store"
    }
}
