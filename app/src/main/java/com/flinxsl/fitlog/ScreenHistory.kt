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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flinxsl.fitlog.ui.theme.Accent
import com.flinxsl.fitlog.ui.theme.Failed
import com.flinxsl.fitlog.ui.theme.FitlogTheme
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.LogTextStyle
import com.flinxsl.fitlog.ui.theme.Short
import com.flinxsl.fitlog.ui.theme.Surface as SurfaceColor
import com.flinxsl.fitlog.ui.theme.TextFaint
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary
import java.time.LocalDate

/**
 * Every session, newest first, in the notation it was always written in.
 *
 * Deliberately read-only for now. Rendering thirteen months of real training is
 * how Format.kt gets proven against all 743 lines before any code is trusted to
 * write data.
 */
@Composable
fun ScreenHistory(
    log: FitLog,
    warning: String?,
    onBack: (() -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sessions = log.sessionsNewestFirst
    LazyColumn(
        modifier = modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { HistoryHeader(log, onBack) }
        warning?.let { item { WarningBanner(it) } }
        items(sessions, key = { it.id }) { SessionCard(it, log, onEdit) }
        if (sessions.isEmpty()) item { EmptyState() }
    }
}

@Composable
private fun HistoryHeader(log: FitLog, onBack: (() -> Unit)?) {
    val entries = log.sessions.sumOf { it.entries.size }
    val sets = log.sessions.sumOf { s -> s.entries.sumOf { it.sets.size } }
    Column(Modifier.padding(bottom = 4.dp)) {
        ScreenTitle(
            "History",
            "${log.sessions.size} sessions · $entries exercises · $sets sets",
            onBack,
        )
        if (log.openReviewCount > 0) {
            Text(
                "${log.openReviewCount} imported entries need review",
                style = MaterialTheme.typography.bodyMedium, color = Short,
            )
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Surface(color = Failed.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
        Text(
            text, Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium, color = Failed,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No sessions yet", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
        Text(
            "Import a log from Settings, or start a workout.",
            style = MaterialTheme.typography.bodyMedium, color = TextFaint,
        )
    }
}

@Composable
private fun SessionCard(s: Session, log: FitLog, onEdit: ((String) -> Unit)?) {
    Surface(
        color = SurfaceColor, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().let {
            if (onEdit == null) it
            else it.clip(RoundedCornerShape(12.dp)).clickable { onEdit(s.id) }
        },
    ) {
        Column(Modifier.padding(14.dp)) {
            SessionHeaderRow(s)
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                s.entries.forEach { e ->
                    Text(
                        entryLine(e, log),
                        style = LogTextStyle,
                        color = if (e.needsReview) Short else TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionHeaderRow(s: Session) {
    val d = LocalDate.parse(s.date)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            Format.longDate(s.date),
            style = MaterialTheme.typography.titleMedium, color = TextPrimary,
        )
        s.dayLabel?.takeIf { it.isNotBlank() }?.let { DayChip(it) }
        if (s.notes.isNotBlank()) {
            Text(s.notes, style = MaterialTheme.typography.bodyMedium, color = Short)
        }
        Box(Modifier.weight(1f))
        Text(
            "${d.monthValue}/${d.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium, color = TextFaint,
        )
    }
}

@Composable
private fun DayChip(label: String) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Accent.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Accent, fontWeight = FontWeight.Bold)
    }
}

/**
 * The exercise line, with any parenthesised deviation tinted so a bad set is
 * visible while scrolling. The glyphs carry the meaning; colour only reinforces.
 */
private fun entryLine(e: Entry, log: FitLog): AnnotatedString {
    val text = Format.entry(e, log.exercise(e.exerciseId))
    val open = text.indexOf(" (")
    val hasX = Regex("\\bX\\b").containsMatchIn(text)
    return buildAnnotatedString {
        if (open < 0) {
            append(text)
        } else {
            append(text.substring(0, open))
            withStyle(SpanStyle(color = if (hasX) Failed else Short)) {
                append(text.substring(open))
            }
        }
        if (open < 0 && hasX) {
            // "Feet up X back" - the whole exercise was skipped
            addStyle(SpanStyle(color = Failed), 0, length)
        }
    }
}

// --- previews -------------------------------------------------------------

/** Invented numbers, not real sessions: previews compile into the release APK. */
private fun demo(): FitLog {
    val squat = Exercise("squat", "Squat", tracks = listOf("heavy", "light"))
    val row = Exercise("row", "Row")
    val curl = Exercise("barbell-curl", "B curl", defaultLoadKind = LoadKind.BAR_ADDED)
    val legRaise = Exercise("leg-raise", "Leg raise", metric = Metric.REPS)
    fun w(i: Int, v: Double, r: Double, t: Double = 5.0) =
        SetRecord(i, Load(LoadKind.BARBELL_TOTAL, v, "lb"), r, t, completed = r >= t)
    return FitLog(
        exercises = listOf(squat, row, curl, legRaise),
        sessions = listOf(
            Session(
                "s1", "2026-03-04", dayLabel = "A",
                entries = listOf(
                    Entry("squat", track = "heavy", sets = (1..5).map { w(it, 200.0, 5.0) }),
                    Entry("row", setsAttribution = Attribution.UNORDERED, sets = listOf(
                        w(1, 130.0, 5.0), w(2, 130.0, 5.0), w(3, 130.0, 5.0),
                        w(4, 130.0, 4.0), w(5, 130.0, 3.0))),
                    Entry("barbell-curl", setsAttribution = Attribution.UNORDERED, sets = listOf(
                        SetRecord(1, Load(LoadKind.BAR_ADDED, 20.0, "lb"), 8.0, 8.0),
                        SetRecord(2, Load(LoadKind.BAR_ADDED, 20.0, "lb"), 8.0, 8.0),
                        SetRecord(3, Load(LoadKind.BAR_ADDED, 20.0, "lb"), 5.0, 8.0, completed = false))),
                    Entry("leg-raise", sets = listOf(
                        SetRecord(1, Load(LoadKind.BODYWEIGHT), 12.0),
                        SetRecord(2, Load(LoadKind.BODYWEIGHT), 12.0),
                        SetRecord(3, Load(LoadKind.BODYWEIGHT), 6.0))),
                )
            ),
            Session(
                "s2", "2026-03-02", dayLabel = "B", notes = "sick",
                entries = listOf(
                    Entry("squat", performed = false, failureReason = "back"),
                    Entry("row", sets = listOf(w(1, 125.0, 5.0), w(2, 125.0, 5.0), w(3, 125.0, 5.0))),
                )
            ),
        )
    )
}

@Preview(showSystemUi = true, device = "id:pixel_7")
@Composable
private fun HistoryPreview() {
    FitlogTheme { ScreenHistory(demo(), null) }
}

@Preview(showSystemUi = true, device = "id:pixel_7")
@Composable
private fun HistoryWarningPreview() {
    FitlogTheme { ScreenHistory(demo(), "The log file could not be read. Restored the previous save.") }
}
