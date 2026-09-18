package com.flinxsl.fitlog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The entire fitlog.json schema. Every other file in the app is downstream of this one.
 *
 * Three rules this file follows deliberately:
 *
 *  1. Reps and weights are Double, never Int. The real log contains "Pull-up 7/6/5.5"
 *     and "(-0.5)" and 17.5 lb dumbbells. Int here would be a painful migration later.
 *  2. Every field has a default. Combined with ignoreUnknownKeys, an old build can open
 *     a file written by a newer one and vice versa, so most schema changes need no
 *     migration at all.
 *  3. Enum-ish values stay as String. Kotlin enums fail the whole parse on an unknown
 *     value, which is the opposite of what you want for a file you may hand-edit.
 */

@Serializable
data class FitLog(
    val schemaVersion: Int = 1,
    val meta: Meta = Meta(),
    val settings: Settings = Settings(),
    val exercises: List<Exercise> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val review: List<ReviewItem> = emptyList(),
) {
    /** Catalog lookup by id. Sessions carry ids, not names. */
    val byId: Map<String, Exercise> get() = exercises.associateBy { it.id }

    fun exercise(id: String): Exercise? = byId[id]

    /** Newest first, which is the order every screen wants. */
    val sessionsNewestFirst: List<Session> get() = sessions.sortedByDescending { it.date }

    val openReviewCount: Int get() = review.count { !it.resolved }
}

@Serializable
data class Meta(
    val createdAt: String? = null,
    val lastModifiedAt: String? = null,
    val appVersion: String? = null,
    val generator: String? = null,
    val import: ImportMeta? = null,
)

@Serializable
data class ImportMeta(
    val importerVersion: String? = null,
    val importedAt: String? = null,
    val sourceFile: String? = null,
    val sourceSha256: String? = null,
    val sourceLineCount: Int = 0,
    val programConfigSha256: String? = null,
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class Settings(
    val unit: String = "lb",
    val barWeight: Double = 45.0,
    val roundingIncrement: Double = 2.5,
    val restSeconds: Int = 180,
    val keepScreenOn: Boolean = true,
)

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

@Serializable
data class Exercise(
    val id: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    /** weight_reps | reps | time */
    val metric: String = Metric.WEIGHT_REPS,
    val defaultLoadKind: String = LoadKind.BARBELL_TOTAL,
    val defaultUnit: String = "lb",
    val firstSeen: String? = null,
    val lastSeen: String? = null,
    val active: Boolean = true,
    val loadKinds: List<String> = emptyList(),
    /** Empty for a single-progression lift; ["heavy","light"] where two are run. */
    val tracks: List<String> = emptyList(),
) {
    val isTracked: Boolean get() = tracks.isNotEmpty()
}

object Metric {
    const val WEIGHT_REPS = "weight_reps"
    const val REPS = "reps"
    const val TIME = "time"
}

/**
 * What the number on a set actually means. This is the distinction that silently
 * corrupts a training log if you get it wrong: "B curl 17.5" is 17.5 lb of plates
 * on an EZ bar, and "D curl 25" is 25 lb in each hand. They are not comparable,
 * and neither is comparable to a barbell total.
 */
object LoadKind {
    const val BARBELL_TOTAL = "barbell_total"   // bar + plates
    const val BAR_ADDED = "bar_added"           // plates only; the bar is excluded
    const val DUMBBELL_EACH = "dumbbell_each"   // one dumbbell
    const val BODYWEIGHT = "bodyweight"
    const val BODYWEIGHT_PLUS = "bodyweight_plus"
    const val TIME_ONLY = "none"
}

/** How much of the per-set detail is real, and how much is the importer's convention. */
object Attribution {
    /** The source stated each set in order. Exact. */
    const val POSITIONAL = "positional"
    /** One value applied to a whole group. Exact. */
    const val UNIFORM = "uniform"
    /**
     * The source said how many sets fell short but not WHICH. "Row 170 (-1/-2)" is
     * two of five sets. The importer placed them on the trailing sets by convention,
     * so per-set analysis must exclude these rather than read a fatigue curve into
     * what is really just a placement rule.
     */
    const val UNORDERED = "unordered"
}

// ---------------------------------------------------------------------------
// Routines. These only ever pre-fill the next session; nothing that reads
// history may consult them, so editing one can never rewrite the past.
// ---------------------------------------------------------------------------

@Serializable
data class Routine(
    val id: String,
    val name: String = "",
    val activeFrom: String? = null,
    val activeTo: String? = null,
    val days: List<RoutineDay> = emptyList(),
)

@Serializable
data class RoutineDay(
    val label: String,
    val name: String = "",
    val slots: List<Slot> = emptyList(),
)

@Serializable
data class Slot(
    val exerciseId: String,
    val sets: Int = 3,
    /** null means AMRAP: taken near failure, so no shortfall is possible. */
    val reps: Double? = null,
    val loadKind: String = LoadKind.BARBELL_TOTAL,
    val tracks: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Sessions
// ---------------------------------------------------------------------------

@Serializable
data class Session(
    val id: String,
    /** ISO yyyy-MM-dd. The only date format stored anywhere. */
    val date: String,
    val routineId: String = "",
    val dayLabel: String? = null,
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val bodyweightLb: Double? = null,
    val entries: List<Entry> = emptyList(),
    val needsReview: Boolean = false,
    val source: Source? = null,
)

@Serializable
data class Entry(
    val exerciseId: String,
    val order: Int = 0,
    /** null = untracked lift, or the single-track era of a tracked one. */
    val track: String? = null,
    /** inferred | manual | single_track_era. History was never recorded, only reconstructed. */
    val trackOrigin: String? = null,
    val trackConfidence: String? = null,
    val prescription: Prescription = Prescription(),
    val sets: List<SetRecord> = emptyList(),
    val setsAttribution: String = Attribution.UNIFORM,
    /** The program's set count, so rendering never has to consult a routine. */
    val progSets: Int = 0,
    val performed: Boolean = true,
    val failureReason: String? = null,
    val notes: String = "",
    val excludeFromPr: Boolean = false,
    val needsReview: Boolean = false,
    val source: Source? = null,
) {
    /** Heaviest load in the entry, for "what did I lift" lookups. */
    val topLoad: Double? get() = sets.mapNotNull { it.load.value }.maxOrNull()

    /**
     * Records and graphs group by this. Comparing across load kinds says a 25 lb
     * dumbbell beat a 75 lb barbell; comparing across tracks says a light deadlift
     * day is a regression. Both are wrong.
     */
    fun prKey(): Triple<String, String, String?> =
        Triple(exerciseId, sets.firstOrNull()?.load?.kind ?: prescription.loadKind, track)
}

@Serializable
data class Prescription(
    val sets: Int = 0,
    val reps: Double? = null,
    val loadKind: String = LoadKind.BARBELL_TOTAL,
    val load: Double? = null,
    val unit: String? = "lb",
    /** routine | import_config | manual | inferred */
    val origin: String = "manual",
    /** certain | assumed | unknown */
    val confidence: String = "certain",
)

@Serializable
data class SetRecord(
    val i: Int = 1,
    val load: Load = Load(),
    val reps: Double? = null,
    val targetReps: Double? = null,
    val durationSec: Double? = null,
    val targetDurationSec: Double? = null,
    val completed: Boolean = true,
    /** Distinct from !completed: 4 of 5 reps is incomplete, a set you abandoned is failed. */
    val failed: Boolean = false,
    val failureReason: String? = null,
) {
    val isTimed: Boolean get() = durationSec != null

    /** Signed shortfall against target, or null when there is nothing to fall short of. */
    val delta: Double?
        get() = if (targetReps == null || reps == null) null else reps - targetReps
}

@Serializable
data class Load(
    val kind: String = LoadKind.BODYWEIGHT,
    val value: Double? = null,
    val unit: String? = null,
)

@Serializable
data class Source(
    val file: String? = null,
    val line: Int = 0,
    /** The verbatim original line. Makes the JSON a strict superset of text_log. */
    val raw: String = "",
)

// ---------------------------------------------------------------------------
// Import review queue
// ---------------------------------------------------------------------------

@Serializable
data class ReviewItem(
    val id: String,
    val code: String = "",
    /** error | warn | info */
    val severity: String = "warn",
    val scope: String = "entry",
    val title: String = "",
    val detail: String = "",
    val sourceLines: List<Int> = emptyList(),
    val sourceRaw: String = "",
    val assumption: String = "",
    val options: List<ReviewOption> = emptyList(),
    val resolved: Boolean = false,
    val resolution: ReviewResolution? = null,
    val resolvedAt: String? = null,
)

@Serializable
data class ReviewOption(
    val id: String,
    val label: String = "",
    val primary: Boolean = false,
    @SerialName("input") val inputType: String? = null,
)

@Serializable
data class ReviewResolution(
    val optionId: String,
    val value: String? = null,
    val note: String? = null,
)
