package com.flinxsl.fitlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flinxsl.fitlog.ui.theme.Accent
import com.flinxsl.fitlog.ui.theme.Done
import com.flinxsl.fitlog.ui.theme.FitlogTheme
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.LogTextStyle
import com.flinxsl.fitlog.ui.theme.Short
import com.flinxsl.fitlog.ui.theme.Surface as SurfaceColor
import com.flinxsl.fitlog.ui.theme.TextFaint
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary

/**
 * What to do today, and what you did recently.
 *
 * The suggested day is the next one in the rotation, but every other day is one
 * tap away. The real schedule drifts - illness, travel, a bad back - and an app
 * that insists on the rotation would be wrong more often than it is right.
 */
@Composable
fun ScreenHome(vm: AppState, modifier: Modifier = Modifier) {
    val suggested = vm.suggestedDay()
    val days = vm.availableDays()
    val recent = vm.log.sessionsNewestFirst.take(3)

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("fit-log", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        }

        vm.loadWarning?.let { w ->
            item {
                Surface(color = Short.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
                    Text(w, Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium, color = Short)
                }
            }
        }

        item { NextUpCard(vm, suggested, days) }

        if (recent.isNotEmpty()) {
            item {
                Text("Recent", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            }
            items@ for (s in recent) {
                item(key = s.id) { RecentCard(s, vm.log) }
            }
        }

        item {
            Surface(
                color = SurfaceColor, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { vm.go(Screen.History) },
            ) {
                Row(
                    Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("All history", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text("${vm.log.sessions.size} sessions  ›",
                        style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                }
            }
        }

        item { NavRow("Routine", "${vm.routine()?.days?.size ?: 0} days") { vm.go(Screen.Routines) } }
        item { NavRow("Settings", "export · import · units") { vm.go(Screen.Settings) } }

        if (vm.log.openReviewCount > 0) {
            item {
                Surface(
                    color = Short.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { vm.go(Screen.Review) },
                ) {
                    Row(
                        Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${vm.log.openReviewCount} entries need review",
                            style = MaterialTheme.typography.titleMedium, color = Short)
                        Text("resolve  ›", style = MaterialTheme.typography.bodyMedium, color = Short)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Surface(
        color = SurfaceColor, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text("$detail  ›", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
        }
    }
}

@Composable
private fun NextUpCard(vm: AppState, suggested: String?, days: List<String>) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Next up", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

            if (days.isEmpty()) {
                Text("No routine yet", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text("Add one in Routines to get started.",
                    style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                return@Column
            }

            val target = suggested ?: days.first()
            Text("Day $target", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            ExerciseSummary(vm, target)

            Button(
                onClick = { vm.startSession(target) },
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(top = 14.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Done, contentColor = Ink),
            ) {
                Text("START", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            val others = days.filter { it != target }
            if (others.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("or", style = MaterialTheme.typography.bodyMedium, color = TextFaint)
                    others.forEach { d -> DayButton(d) { vm.startSession(d) } }
                }
            }
        }
    }
}

@Composable
private fun ExerciseSummary(vm: AppState, dayLabel: String) {
    val slots = Prefill.currentRoutine(vm.log)?.days?.firstOrNull { it.label == dayLabel }?.slots.orEmpty()
    if (slots.isEmpty()) return
    Text(
        slots.joinToString(" · ") { vm.log.exercise(it.exerciseId)?.displayName ?: it.exerciseId },
        style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DayButton(label: String, onClick: () -> Unit) {
    Surface(
        color = Accent.copy(alpha = 0.18f), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
    ) {
        Text(
            label, Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium, color = Accent, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RecentCard(s: Session, log: FitLog) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Format.longDate(s.date),
                    style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                s.dayLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Accent,
                        fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                s.entries.forEach {
                    Text(Format.entry(it, log.exercise(it.exerciseId)),
                        style = LogTextStyle, color = TextSecondary)
                }
            }
        }
    }
}
