package com.flinxsl.fitlog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

/**
 * The oddities the importer refused to guess about.
 *
 * Every card shows the original line verbatim, because Scott is the only person
 * who can decrypt it. The importer already applied a best guess and said so, so
 * most cards are a confirmation rather than data entry - which is the only
 * reason a thirty-item queue is finishable.
 *
 * Nothing is ever forced: Skip leaves an item open and moves on.
 */
@Composable
fun ScreenReview(vm: AppState, modifier: Modifier = Modifier) {
    val open = vm.openReviews()
    val total = vm.log.review.size
    var writeIn by remember { mutableStateOf<ReviewItem?>(null) }

    LazyColumn(
        modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenTitle(
                "Review",
                "${total - open.size} of $total resolved",
                onBack = { vm.back() },
            )
        }

        if (open.isEmpty()) {
            item {
                Surface(color = Done.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("All clear", style = MaterialTheme.typography.titleLarge, color = Done)
                        Text(
                            "Every imported oddity has been looked at. Resolved items are " +
                                "kept as a record of what was reconstructed rather than logged.",
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                        )
                    }
                }
            }
        }

        items@ for (item in open) {
            item(key = item.id) {
                ReviewCard(
                    item = item,
                    onChoose = { opt ->
                        if (opt == "edit" || opt == "other") {
                            val line = item.sourceLines.firstOrNull()
                            if (line != null && vm.editSessionForLine(line)) Unit
                            else writeIn = item
                        } else {
                            vm.resolveReview(item, opt)
                        }
                    },
                    onEditSets = {
                        item.sourceLines.firstOrNull()?.let { vm.editSessionForLine(it) }
                    },
                )
            }
        }

        if (open.isNotEmpty()) {
            item {
                Text(
                    "Until an item is resolved its entry still shows in history and " +
                        "counts toward volume, but stays out of record calculations.",
                    style = MaterialTheme.typography.bodyMedium, color = TextFaint,
                )
            }
        }
    }

    writeIn?.let { item ->
        TextPrompt("Note for this entry", "what actually happened", "") { note ->
            writeIn = null
            if (note != null) vm.resolveReview(item, "note", note)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(item: ReviewItem, onChoose: (String) -> Unit, onEditSets: () -> Unit) {
    val tint = when (item.severity) {
        "error" -> Failed
        "info" -> Accent
        else -> Short
    }
    Surface(color = SurfaceColor, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (item.severity) { "error" -> "!"; "info" -> "i"; else -> "⚠" },
                    style = MaterialTheme.typography.titleMedium, color = tint,
                )
                Text(
                    item.code.replace('_', ' ').lowercase(),
                    Modifier.padding(start = 8.dp).weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = tint,
                )
                item.sourceLines.firstOrNull()?.let {
                    Text("line $it", style = MaterialTheme.typography.labelMedium, color = TextFaint)
                }
            }

            Text(item.title, Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleMedium, color = TextPrimary)

            // The verbatim source. This is the part only you can interpret.
            if (item.sourceRaw.isNotBlank()) {
                Surface(
                    color = Ink, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(item.sourceRaw, Modifier.padding(12.dp),
                        style = LogTextStyle, color = TextPrimary)
                }
            }

            Text(item.detail, Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary)

            if (item.assumption.isNotBlank()) {
                Text(item.assumption, Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium, color = TextFaint)
            }

            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.options.forEach { opt ->
                    Pill(
                        label = opt.label.ifBlank { opt.id },
                        tint = if (opt.primary) Done else TextSecondary,
                        bold = opt.primary,
                    ) { onChoose(opt.id) }
                }
                if (item.options.none { it.id == "edit" } && item.sourceLines.isNotEmpty()) {
                    Pill("Edit sets", Accent) { onEditSets() }
                }
                Pill("Skip", TextFaint) { /* leave open */ }
            }
        }
    }
}

@Composable
private fun Pill(label: String, tint: Color, bold: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = tint.copy(alpha = 0.16f), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Text(
            label, Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.labelLarge, color = tint,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
