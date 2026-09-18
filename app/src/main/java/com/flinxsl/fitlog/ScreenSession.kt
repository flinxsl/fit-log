package com.flinxsl.fitlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import com.flinxsl.fitlog.ui.theme.Accent
import com.flinxsl.fitlog.ui.theme.Done
import com.flinxsl.fitlog.ui.theme.Failed
import com.flinxsl.fitlog.ui.theme.FitlogTheme
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.Outline
import com.flinxsl.fitlog.ui.theme.Short
import com.flinxsl.fitlog.ui.theme.Surface as SurfaceColor
import com.flinxsl.fitlog.ui.theme.SurfaceHigh
import com.flinxsl.fitlog.ui.theme.TextFaint
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary

/**
 * Logging by exception: the whole session arrives pre-filled from what you
 * actually lifted last time, and a clean day is one tap on FINISH.
 *
 * Weight is always editable in place, never buried behind a menu, because the
 * real log shows constant backing off. Per-set rep deviations land next.
 */
@Composable
fun ScreenSession(vm: AppState, modifier: Modifier = Modifier) {
    val s = vm.draft ?: return
    var editingWeight by remember { mutableStateOf<Int?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }

    // The phone must not sleep between sets.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier.fillMaxSize().background(Ink)) {
        SessionTopBar(s, onBack = { vm.discardSession() })

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(s.entries, key = { _, e -> e.exerciseId }) { i, e ->
                ExerciseRow(
                    entry = e,
                    exercise = vm.log.exercise(e.exerciseId),
                    increment = vm.increment(s, e),
                    onNudge = { d -> vm.nudgeWeight(i, d) },
                    onTypeWeight = { editingWeight = i },
                    onTrack = { t -> vm.setTrack(i, t) },
                    onToggleSkip = { vm.setSkipped(i, e.performed) },
                )
            }
            item {
                Text(
                    "Everything is pre-filled from last time. Adjust what changed, " +
                        "then finish.",
                    style = MaterialTheme.typography.bodyMedium, color = TextFaint,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        Button(
            onClick = { confirmFinish = true },
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(62.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Done, contentColor = Ink),
        ) {
            Text("FINISH", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }

    editingWeight?.let { i ->
        WeightDialog(
            current = s.entries[i].topLoad ?: 0.0,
            name = vm.log.exercise(s.entries[i].exerciseId)?.displayName ?: "",
            onDismiss = { editingWeight = null },
            onSet = { v -> vm.setTopWeight(i, v); editingWeight = null },
        )
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = SurfaceHigh,
            title = { Text("Log this session?", color = TextPrimary) },
            text = {
                Text(
                    "${s.entries.count { it.performed }} exercises will be saved to " +
                        Format.longDate(s.date) + ".",
                    color = TextSecondary,
                )
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
private fun SessionTopBar(s: Session, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("‹", style = MaterialTheme.typography.headlineMedium, color = TextSecondary) }
        Column {
            Text("Day ${s.dayLabel ?: ""}",
                style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(Format.longDate(s.date),
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}

@Composable
private fun ExerciseRow(
    entry: Entry,
    exercise: Exercise?,
    increment: Double,
    onNudge: (Double) -> Unit,
    onTypeWeight: () -> Unit,
    onTrack: (String) -> Unit,
    onToggleSkip: () -> Unit,
) {
    val skipped = !entry.performed
    Surface(
        color = if (skipped) SurfaceColor.copy(alpha = 0.5f) else SurfaceColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exercise?.displayName ?: entry.exerciseId,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (skipped) TextFaint else TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    scheme(entry, exercise),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Box(
                    Modifier.padding(start = 10.dp).size(40.dp)
                        .clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleSkip),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (skipped) "↺" else "✕",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (skipped) Accent else TextFaint)
                }
            }

            if (exercise?.isTracked == true && !skipped) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    exercise.tracks.forEach { t ->
                        TrackChip(t, selected = entry.track == t) { onTrack(t) }
                    }
                }
            }

            if (!skipped && entry.topLoad != null) {
                WeightStepper(
                    value = entry.topLoad!!,
                    unitLabel = Format.loadSuffix(entry.prescription.loadKind, entry.prescription.unit ?: "lb").trim(),
                    step = increment,
                    onNudge = onNudge,
                    onType = onTypeWeight,
                )
            } else if (skipped) {
                Text("Skipped", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = Failed)
            } else {
                Text(bodyweightSummary(entry), Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun TrackChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Accent.copy(alpha = 0.25f) else SurfaceHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Text(
            label.uppercase(),
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Accent else TextFaint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Always visible, never behind a menu: minus, the number, plus. Tap the number to type. */
@Composable
private fun WeightStepper(
    value: Double,
    unitLabel: String,
    step: Double,
    onNudge: (Double) -> Unit,
    onType: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepButton("−${Format.num(step)}") { onNudge(-step) }
        Surface(
            color = SurfaceHigh, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onType),
        ) {
            Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Format.num(value),
                    style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
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
    // fillMaxSize() here would expand to the whole Row and push the weight and the
    // other button off screen: a Row child has no width constraint of its own.
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
private fun WeightDialog(current: Double, name: String, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(Format.num(current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceHigh,
        title = { Text(name, color = TextPrimary) },
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

private fun scheme(e: Entry, ex: Exercise?): String = when {
    ex?.metric == Metric.TIME -> "hold"
    e.prescription.reps == null -> "${e.sets.size} sets"
    else -> "${e.sets.size}×${Format.num(e.prescription.reps)}"
}

private fun bodyweightSummary(e: Entry): String =
    if (e.sets.isEmpty()) "—"
    else if (e.sets.first().isTimed) "${Format.num(e.sets.first().durationSec)} seconds"
    else e.sets.joinToString(" / ") { Format.num(it.reps) }
