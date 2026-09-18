package com.flinxsl.fitlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flinxsl.fitlog.ui.theme.Accent
import com.flinxsl.fitlog.ui.theme.Done
import com.flinxsl.fitlog.ui.theme.Failed
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.LogTextStyle
import com.flinxsl.fitlog.ui.theme.Short
import com.flinxsl.fitlog.ui.theme.Surface as SurfaceColor
import com.flinxsl.fitlog.ui.theme.SurfaceHigh
import com.flinxsl.fitlog.ui.theme.TextFaint
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary

/** The reasons that actually appear in the log, so they are one tap rather than typing. */
private val REASONS = listOf("back", "grip", "forearm", "shoulder", "knee")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenSession(vm: AppState, modifier: Modifier = Modifier) {
    val s = vm.draft ?: return
    var weightDialog by remember { mutableStateOf<Int?>(null) }
    var setSheet by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val editing = vm.editingId != null

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().background(Ink)) {
        SessionTopBar(
            s,
            editing = editing,
            onBack = { vm.discardSession() },
            onAllAsPlanned = { vm.markAllAsPlanned() },
            onNote = { noteDialog = true },
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(s.entries, key = { _, e -> e.exerciseId }) { i, e ->
                ExerciseCard(
                    entry = e,
                    exercise = vm.log.exercise(e.exerciseId),
                    expanded = vm.expanded == i,
                    increment = vm.increment(s, e),
                    onToggle = { vm.toggleExpanded(i) },
                    onNudge = { d -> vm.nudgeWeight(i, d) },
                    onTypeWeight = { weightDialog = i },
                    onTrack = { t -> vm.setTrack(i, t) },
                    onToggleSkip = { vm.setSkipped(i, e.performed) },
                    onTapSet = { si -> vm.tapSet(i, si) },
                    onHoldSet = { si -> setSheet = i to si },
                )
            }
            item {
                Text(
                    "Tap a set to take off a rep. Hold one for exact reps, a failed " +
                        "set, or a weight change from there on.",
                    style = MaterialTheme.typography.bodyMedium, color = TextFaint,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        Button(
            onClick = { confirmFinish = true },
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(62.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Done, contentColor = Ink),
        ) {
            Text(if (editing) "SAVE CHANGES" else "FINISH",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (editing) {
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) { Text("Delete this session", color = Failed) }
        }
    }

    weightDialog?.let { i ->
        NumberDialog(
            title = vm.log.exercise(s.entries[i].exerciseId)?.displayName ?: "",
            current = s.entries[i].topLoad ?: 0.0,
            onDismiss = { weightDialog = null },
            onSet = { v -> vm.setTopWeight(i, v); weightDialog = null },
        )
    }

    if (noteDialog) {
        TextDialog(
            title = "Session note",
            current = s.notes,
            onDismiss = { noteDialog = false },
            onSet = { vm.setSessionNote(it); noteDialog = false },
        )
    }

    setSheet?.let { (ei, si) ->
        val entry = s.entries[ei]
        ModalBottomSheet(
            onDismissRequest = { setSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SurfaceHigh,
        ) {
            SetSheet(
                name = vm.log.exercise(entry.exerciseId)?.displayName ?: entry.exerciseId,
                entry = entry,
                setIndex = si,
                step = vm.increment(s, entry),
                onReps = { r -> vm.setReps(ei, si, r); setSheet = null },
                onDuration = { d -> vm.setDuration(ei, si, d); setSheet = null },
                onFail = { reason -> vm.failSet(ei, si, reason); setSheet = null },
                onWeightFrom = { v -> vm.setWeightFrom(ei, si, v) },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = SurfaceHigh,
            title = { Text("Delete this session?", color = TextPrimary) },
            text = {
                Column {
                    Text(
                        "${Format.longDate(s.date)} will be removed from your history. " +
                            "Export first if you are not sure.",
                        color = TextSecondary,
                    )
                    Column(Modifier.padding(top = 10.dp)) {
                        s.entries.forEach {
                            Text(Format.entry(it, vm.log.exercise(it.exerciseId)),
                                style = LogTextStyle, color = TextFaint)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.editingId?.let { vm.deleteSession(it) }
                }) { Text("Delete", color = Failed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextSecondary) }
            },
        )
    }

    if (confirmFinish) {
        val untouched = vm.draftUntouched()
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = SurfaceHigh,
            title = {
                Text(
                    if (editing) "Save changes?"
                    else if (untouched) "Log all as planned?"
                    else "Log this session?",
                    color = TextPrimary,
                )
            },
            text = {
                Column {
                    if (untouched && !editing) {
                        Text(
                            "Nothing was changed, so every set will be recorded as completed.",
                            color = TextSecondary,
                        )
                    }
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        s.entries.forEach {
                            Text(Format.entry(it, vm.log.exercise(it.exerciseId)),
                                style = LogTextStyle, color = TextSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finishSession() }) {
                    Text("Save", color = Done, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text("Back", color = TextSecondary) }
            },
        )
    }
}

@Composable
private fun SessionTopBar(
    s: Session,
    editing: Boolean,
    onBack: () -> Unit,
    onAllAsPlanned: () -> Unit,
    onNote: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("‹", style = MaterialTheme.typography.headlineMedium, color = TextSecondary) }

        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                if (editing) "Editing · Day ${s.dayLabel ?: ""}" else "Day ${s.dayLabel ?: ""}",
                style = MaterialTheme.typography.titleLarge,
                color = if (editing) Short else TextPrimary,
            )
            Text(
                if (s.notes.isBlank()) Format.longDate(s.date) else "${Format.longDate(s.date)} · ${s.notes}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.notes.isBlank()) TextSecondary else Short,
            )
        }

        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onNote),
            contentAlignment = Alignment.Center,
        ) { Text("✎", style = MaterialTheme.typography.titleMedium, color = TextSecondary) }

        Surface(
            color = Done.copy(alpha = 0.18f), shape = RoundedCornerShape(9.dp),
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onAllAsPlanned),
        ) {
            Text(
                "✓ ALL", Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge, color = Done, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ExerciseCard(
    entry: Entry,
    exercise: Exercise?,
    expanded: Boolean,
    increment: Double,
    onToggle: () -> Unit,
    onNudge: (Double) -> Unit,
    onTypeWeight: () -> Unit,
    onTrack: (String) -> Unit,
    onToggleSkip: () -> Unit,
    onTapSet: (Int) -> Unit,
    onHoldSet: (Int) -> Unit,
) {
    val skipped = !entry.performed
    Surface(
        color = if (expanded) SurfaceHigh else SurfaceColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Collapsed head: reads as the log line it will become.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        exercise?.displayName ?: entry.exerciseId,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (skipped) TextFaint else TextPrimary,
                    )
                    Text(
                        payload(entry, exercise),
                        style = LogTextStyle,
                        color = when {
                            skipped -> Failed
                            entry.sets.any { it.failed } -> Failed
                            entry.sets.any { !it.completed } -> Short
                            else -> TextSecondary
                        },
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleSkip),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (skipped) "↺" else "✕",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (skipped) Accent else TextFaint)
                }
            }

            if (expanded && !skipped) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    if (exercise?.isTracked == true) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            exercise.tracks.forEach { t ->
                                TrackChip(t, entry.track == t) { onTrack(t) }
                            }
                        }
                    }
                    SetChips(entry, onTapSet, onHoldSet)
                    if (entry.topLoad != null) {
                        WeightStepper(
                            value = entry.topLoad!!,
                            unitLabel = Format.loadSuffix(
                                entry.prescription.loadKind, entry.prescription.unit ?: "lb").trim(),
                            step = increment,
                            onNudge = onNudge,
                            onType = onTypeWeight,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One chip per set. Weight is printed only when it differs from the chip before,
 * so "190/185 3/2" reads as [5|190][5][5][5|185][5] with no extra chrome.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SetChips(entry: Entry, onTap: (Int) -> Unit, onHold: (Int) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entry.sets.forEachIndexed { i, s ->
            val prev = entry.sets.getOrNull(i - 1)
            val showWeight = s.load.value != null && s.load.value != prev?.load?.value
            val tint = when {
                s.failed -> Failed
                !s.completed -> Short
                else -> Done
            }
            Surface(
                color = tint.copy(alpha = 0.16f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(width = 66.dp, height = 66.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { onTap(i) }, onLongClick = { onHold(i) }),
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (s.failed) "X" else if (s.isTimed) Format.num(s.durationSec) else Format.num(s.reps),
                        style = MaterialTheme.typography.headlineMedium, color = tint,
                    )
                    if (showWeight) {
                        Text(Format.num(s.load.value),
                            style = MaterialTheme.typography.labelMedium, color = TextFaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun SetSheet(
    name: String,
    entry: Entry,
    setIndex: Int,
    step: Double,
    onReps: (Double) -> Unit,
    onDuration: (Double) -> Unit,
    onFail: (String?) -> Unit,
    onWeightFrom: (Double) -> Unit,
) {
    val set = entry.sets[setIndex]
    val target = set.targetReps
    var weight by remember { mutableStateOf(set.load.value ?: 0.0) }
    var writeIn by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
        Text(
            "$name · set ${setIndex + 1}" + (set.load.value?.let { " · ${Format.num(it)}" } ?: ""),
            style = MaterialTheme.typography.titleLarge, color = TextPrimary,
        )

        if (set.isTimed) {
            Text("SECONDS", Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.labelMedium, color = TextFaint)
            NumberRow((0..12).map { (set.durationSec ?: 0.0) - 6 + it }.filter { it >= 0 },
                selected = set.durationSec, onPick = onDuration)
        } else {
            Text("REPS", Modifier.padding(top = 18.dp),
                style = MaterialTheme.typography.labelMedium, color = TextFaint)
            val top = (target ?: set.reps ?: 8.0).toInt() + 2
            NumberRow((0..top).map { it.toDouble() }, selected = set.reps, target = target, onPick = onReps)
        }

        Text("FAILED THE SET", Modifier.padding(top = 22.dp),
            style = MaterialTheme.typography.labelMedium, color = TextFaint)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("✗ failed", Failed) { onFail(null) }
        }
        FlowReasons(onFail = onFail, onWriteIn = { writeIn = true })

        if (writeIn) {
            TextDialog(
                title = "Why did the set fail?",
                current = "",
                onDismiss = { writeIn = false },
                onSet = { r -> writeIn = false; onFail(r.trim().ifBlank { null }) },
            )
        }

        if (set.load.value != null) {
            Text("WEIGHT — this set and the rest", Modifier.padding(top = 22.dp),
                style = MaterialTheme.typography.labelMedium, color = TextFaint)
            WeightStepper(
                value = weight, unitLabel = "", step = step,
                onNudge = { d -> weight = maxOf(0.0, weight + d); onWeightFrom(weight) },
                onType = {},
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowReasons(onFail: (String?) -> Unit, onWriteIn: () -> Unit) {
    FlowRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        REASONS.forEach { r -> PillButton(r, TextSecondary) { onFail(r) } }
        // The presets come from the real log, but they will never cover everything.
        PillButton("write in…", Accent, onWriteIn)
    }
}

@Composable
private fun NumberRow(
    options: List<Double>,
    selected: Double?,
    target: Double? = null,
    onPick: (Double) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { i ->
            val v = options[i]
            val isTarget = target != null && v == target
            val isSel = selected != null && v == selected
            Surface(
                color = when {
                    isSel -> Done.copy(alpha = 0.3f)
                    isTarget -> Accent.copy(alpha = 0.18f)
                    else -> SurfaceColor
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(58.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { onPick(v) },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(Format.num(v), style = MaterialTheme.typography.titleLarge,
                        color = if (isSel) Done else TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        color = tint.copy(alpha = 0.16f), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun TrackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Accent.copy(alpha = 0.25f) else SurfaceColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Text(
            label.uppercase(), Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Accent else TextFaint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun WeightStepper(
    value: Double,
    unitLabel: String,
    step: Double,
    onNudge: (Double) -> Unit,
    onType: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepButton("−${Format.num(step)}") { onNudge(-step) }
        Surface(
            color = SurfaceColor, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onType),
        ) {
            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.num(value), style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                if (unitLabel.isNotBlank()) {
                    Text(unitLabel, style = MaterialTheme.typography.labelMedium, color = TextFaint)
                }
            }
        }
        StepButton("+${Format.num(step)}") { onNudge(step) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    // fillMaxSize() here would expand to the whole Row and push the other controls
    // off screen: a Row child has no width constraint of its own.
    Surface(
        color = SurfaceHigh, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(56.dp).widthIn(min = 76.dp)
            .clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = Accent)
        }
    }
}

@Composable
private fun NumberDialog(title: String, current: Double, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(Format.num(current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { text.toDoubleOrNull()?.let(onSet) ?: onDismiss() }) {
                Text("Set", color = Done, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

@Composable
private fun TextDialog(title: String, current: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSet(text) }) { Text("Set", color = Done, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

/** The part of the log line after the exercise name. */
private fun payload(e: Entry, ex: Exercise?): String {
    val full = Format.entry(e, ex)
    val name = ex?.displayName ?: e.exerciseId
    return full.removePrefix(name).trim().ifBlank { "—" }
}
