package com.flinxsl.fitlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flinxsl.fitlog.ui.theme.Accent
import com.flinxsl.fitlog.ui.theme.Done
import com.flinxsl.fitlog.ui.theme.Failed
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.Surface as SurfaceColor
import com.flinxsl.fitlog.ui.theme.SurfaceHigh
import com.flinxsl.fitlog.ui.theme.TextFaint
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary

// ---------------------------------------------------------------------------
// Routine: the list of days
// ---------------------------------------------------------------------------

@Composable
fun ScreenRoutines(vm: AppState, modifier: Modifier = Modifier) {
    val days = vm.routine()?.days.orEmpty()
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Routine", onBack = { vm.back() }) }

        if (days.isEmpty()) {
            item {
                Surface(color = SurfaceColor, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "A day is one workout — the exercises you do in one gym " +
                                "session, in order. Most people have two to four.",
                            style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
                        )
                    }
                }
            }
        }

        items@ for (d in days) {
            item(key = d.label) {
                Surface(
                    color = SurfaceColor, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.go(Screen.DayEditor(d.label)) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.label, style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary, fontWeight = FontWeight.Bold)
                            Box(Modifier.weight(1f))
                            Text("${d.slots.size} exercises  ›",
                                style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                        }
                        Text(
                            d.slots.joinToString(" · ") {
                                vm.log.exercise(it.exerciseId)?.displayName ?: it.exerciseId
                            },
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = { adding = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = Accent),
            ) { Text("+ ADD A DAY", style = MaterialTheme.typography.titleMedium) }
        }

        item {
            Text(
                "Days are suggested in order and repeat. You can always start any " +
                    "day you want.",
                style = MaterialTheme.typography.bodyMedium, color = TextFaint,
            )
        }
    }

    if (adding) {
        TextPrompt("New day", "Label, e.g. A or Push", "") { label ->
            adding = false
            if (!label.isNullOrBlank()) vm.addDay(label.trim())
        }
    }
}

// ---------------------------------------------------------------------------
// One day: its exercises, in order
// ---------------------------------------------------------------------------

@Composable
fun ScreenDayEditor(vm: AppState, label: String, modifier: Modifier = Modifier) {
    val day = vm.day(label) ?: return
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle("Day ${day.label}", "${day.slots.size} exercises", onBack = { vm.back() })
        }

        itemsIndexed(day.slots, key = { _, s -> s.exerciseId }) { i, slot ->
            val ex = vm.log.exercise(slot.exerciseId)
            Surface(color = SurfaceColor, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .clickable { vm.go(Screen.ExerciseEditor(label, slot.exerciseId)) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(ex?.displayName ?: slot.exerciseId,
                            style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(slotSummary(slot, vm.log.settings.unit),
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                    IconBtn("▲", enabled = i > 0) { vm.moveSlot(label, i, i - 1) }
                    IconBtn("▼", enabled = i < day.slots.size - 1) { vm.moveSlot(label, i, i + 1) }
                    IconBtn("✕", tint = Failed) { vm.deleteSlot(label, slot.exerciseId) }
                }
            }
        }

        item {
            Button(
                onClick = { vm.go(Screen.ExerciseEditor(label, null)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = Accent),
            ) { Text("+ ADD EXERCISE", style = MaterialTheme.typography.titleMedium) }
        }

        item {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete day ${day.label}", color = Failed)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = SurfaceHigh,
            title = { Text("Delete day ${day.label}?", color = TextPrimary) },
            text = {
                Text(
                    "Sessions you have already logged are not affected — history is " +
                        "never changed by editing a routine.",
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteDay(label); vm.back() }) {
                    Text("Delete", color = Failed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextSecondary) }
            },
        )
    }
}

private fun slotSummary(s: Slot, unit: String): String {
    val scheme = if (s.reps == null) "${s.sets} sets to failure" else "${s.sets}×${Format.num(s.reps)}"
    val load = Format.loadSuffix(s.loadKind, unit).trim().ifBlank { "bodyweight" }
    val tracks = if (s.tracks.isEmpty()) "" else " · light/heavy"
    return "$scheme · $load$tracks"
}

@Composable
private fun IconBtn(
    glyph: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = Accent,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium,
            color = if (enabled) tint else TextFaint.copy(alpha = 0.4f))
    }
}

// ---------------------------------------------------------------------------
// One exercise. The only place the five weight semantics get explained.
// ---------------------------------------------------------------------------

private data class LoadChoice(val kind: String, val metric: String, val title: String, val example: String)

private val LOAD_CHOICES = listOf(
    LoadChoice(LoadKind.BARBELL_TOTAL, Metric.WEIGHT_REPS,
        "Barbell or machine — one weight", "\"Row 170\" — the bar plus the plates"),
    LoadChoice(LoadKind.BAR_ADDED, Metric.WEIGHT_REPS,
        "Added to the bar — plates only", "\"B curl 25\" — 25 lb on the EZ bar, bar not counted"),
    LoadChoice(LoadKind.DUMBBELL_EACH, Metric.WEIGHT_REPS,
        "Dumbbells — weight in EACH hand", "\"D curl 25\" — 25 lb per hand"),
    LoadChoice(LoadKind.BODYWEIGHT, Metric.REPS,
        "Bodyweight — just count reps", "\"Pull-up 8/8/5\""),
    LoadChoice(LoadKind.BODYWEIGHT_PLUS, Metric.WEIGHT_REPS,
        "Bodyweight plus added weight", "\"Dips +25\""),
    LoadChoice(LoadKind.TIME_ONLY, Metric.TIME,
        "Timed hold — seconds", "\"Plank 85\""),
)

@Composable
fun ScreenExerciseEditor(vm: AppState, label: String, exerciseId: String?, modifier: Modifier = Modifier) {
    val existing = exerciseId?.let { vm.log.exercise(it) }
    val slot = vm.day(label)?.slots?.firstOrNull { it.exerciseId == exerciseId }

    var name by remember { mutableStateOf(existing?.displayName ?: "") }
    var kind by remember { mutableStateOf(slot?.loadKind ?: existing?.defaultLoadKind ?: LoadKind.BARBELL_TOTAL) }
    var sets by remember { mutableStateOf(slot?.sets ?: 5) }
    var reps by remember { mutableStateOf(slot?.reps) }
    var amrap by remember { mutableStateOf(slot?.reps == null && slot != null) }
    var tracked by remember { mutableStateOf((slot?.tracks ?: existing?.tracks).orEmpty().isNotEmpty()) }
    var autoProgress by remember { mutableStateOf(slot?.autoProgress ?: true) }
    var increment by remember { mutableStateOf(slot?.increment) }
    var startWeight by remember { mutableStateOf(slot?.startWeight) }

    val metric = LOAD_CHOICES.first { it.kind == kind }.metric
    val step = increment ?: Prefill.increment(Slot("x", loadKind = kind), null)

    LazyColumn(
        modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.back() },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", style = MaterialTheme.typography.headlineMedium, color = TextSecondary) }
                Text(if (existing == null) "New exercise" else "Exercise",
                    Modifier.padding(start = 4.dp).weight(1f),
                    style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        vm.saveExercise(
                            label, exerciseId, name.trim(), metric, kind, sets,
                            if (amrap || metric != Metric.WEIGHT_REPS && metric != Metric.REPS) null
                            else if (metric == Metric.REPS && amrap) null else reps,
                            tracked, increment, autoProgress, startWeight,
                        )
                        vm.back()
                    },
                ) { Text("SAVE", color = if (name.isBlank()) TextFaint else Done, fontWeight = FontWeight.Bold) }
            }
        }

        item {
            Card("NAME") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        item {
            Card("HOW IS IT LOADED?") {
                LOAD_CHOICES.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp)).clickable { kind = c.kind },
                    ) {
                        Text(if (kind == c.kind) "●" else "○",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (kind == c.kind) Accent else TextFaint)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge,
                                color = if (kind == c.kind) TextPrimary else TextSecondary)
                            Text(c.example, style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                        }
                    }
                }
            }
        }

        item {
            Card("HOW MANY") {
                Stepper("Sets", sets.toString(), { sets = (sets - 1).coerceAtLeast(1) }, { sets += 1 })
                if (metric != Metric.TIME) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Reps", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        Toggle("to failure", amrap) { amrap = !amrap }
                    }
                    if (!amrap) {
                        Stepper("Reps per set", Format.num(reps ?: 5.0),
                            { reps = ((reps ?: 5.0) - 1).coerceAtLeast(1.0) },
                            { reps = (reps ?: 5.0) + 1 })
                    }
                }
            }
        }

        item {
            Card("PROGRESSION") {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("When every set is complete, suggest more next time",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Toggle("on", autoProgress) { autoProgress = !autoProgress }
                }
                if (autoProgress) {
                    Stepper("Add", Format.num(step),
                        { increment = (step - 0.5).coerceAtLeast(0.5) },
                        { increment = step + 0.5 })
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Two tracks (light and heavy days)", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Toggle("on", tracked) { tracked = !tracked }
                }
            }
        }

        item {
            Card("PREVIEW") {
                Text(previewText(name, kind, sets, if (amrap) null else reps, tracked, autoProgress, step,
                    vm.log.settings.unit),
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            }
        }

        if (exerciseId != null) {
            item {
                TextButton(
                    onClick = { vm.deleteSlot(label, exerciseId); vm.back() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove from day $label", color = Failed) }
            }
        }
    }
}

private fun previewText(
    name: String, kind: String, sets: Int, reps: Double?, tracked: Boolean,
    auto: Boolean, step: Double, unit: String,
): String {
    val n = name.ifBlank { "This exercise" }
    val scheme = if (reps == null) "$sets sets to failure" else "$sets×${Format.num(reps)}"
    val unitWord = when (kind) {
        LoadKind.DUMBBELL_EACH -> "$unit per hand"
        LoadKind.BAR_ADDED -> "$unit on the bar"
        LoadKind.BODYWEIGHT -> "reps"
        LoadKind.BODYWEIGHT_PLUS -> "$unit added"
        LoadKind.TIME_ONLY -> "seconds"
        else -> unit
    }
    val lines = mutableListOf("$n — $scheme, measured in $unitWord.")
    if (tracked) lines += "Light and heavy days progress separately."
    lines += if (auto) {
        "Hit everything and next time suggests ${Format.num(step)} more."
    } else {
        "It will always suggest whatever you did last time."
    }
    lines += "After the first session it always starts from what you actually did."
    return lines.joinToString(" ")
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = TextFaint)
            content()
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        IconBtn("−") { onMinus() }
        Text(value, Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        IconBtn("+") { onPlus() }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (on) Done.copy(alpha = 0.22f) else SurfaceHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Text(
            if (on) "✓ $label" else label,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (on) Done else TextFaint,
        )
    }
}

@Composable
fun TextPrompt(title: String, hint: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = SurfaceHigh,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text(hint, color = TextFaint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }) {
                Text("Add", color = Done, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel", color = TextSecondary) } },
    )
}
