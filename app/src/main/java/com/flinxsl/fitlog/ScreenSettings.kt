package com.flinxsl.fitlog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flinxsl.fitlog.ui.theme.Accent
import com.flinxsl.fitlog.ui.theme.Done
import com.flinxsl.fitlog.ui.theme.Failed
import com.flinxsl.fitlog.ui.theme.Ink
import com.flinxsl.fitlog.ui.theme.Short
import com.flinxsl.fitlog.ui.theme.Surface as SurfaceColor
import com.flinxsl.fitlog.ui.theme.TextFaint
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary
import java.time.LocalDate

/**
 * Settings, and more importantly the way data gets off the phone.
 *
 * Uninstalling the app deletes its private storage, so without export the phone
 * holds the only copy of every session logged since the last build. Export uses
 * the storage access framework, which needs no permissions at all - you pick
 * where the file goes and the system hands back a writable handle.
 */
@Composable
fun ScreenSettings(vm: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun note(text: String, error: Boolean = false) { message = text; isError = error }

    val saveJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.exportJson().toByteArray()) }
        }.onSuccess { note("Exported ${vm.log.sessions.size} sessions.") }
            .onFailure { note("Export failed: ${it.message}", true) }
    }

    val saveText = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.exportText().toByteArray()) }
        }.onSuccess { note("Exported in text_log format.") }
            .onFailure { note("Export failed: ${it.message}", true) }
    }

    val openJson = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        }.onSuccess { text ->
            val result = vm.importJson(text)
            note(result, result.startsWith("Could not") || result.startsWith("That file"))
        }.onFailure { note("Could not open that file: ${it.message}", true) }
    }

    val stamp = LocalDate.now().toString()

    LazyColumn(
        modifier.fillMaxSize().background(Ink),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenTitle("Settings", onBack = { vm.back() }) }

        message?.let {
            item {
                Surface(
                    color = (if (isError) Failed else Done).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(it, Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) Failed else Done)
                }
            }
        }

        item {
            SettingsGroup("Your data") {
                ActionRow(
                    "Export as JSON",
                    "Everything, in the format the app reads. This is the backup that matters.",
                ) { saveJson.launch("fitlog-$stamp.json") }
                ActionRow(
                    "Export as text",
                    "The same sessions in your original notation, so the format is never a dead end.",
                ) { saveText.launch("fit-log-$stamp.txt") }
                ActionRow(
                    "Import JSON",
                    "Replaces everything currently in the app.",
                    tint = Short,
                ) { openJson.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
        }

        item {
            SettingsGroup("Units") {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("lb", "kg").forEach { u ->
                        Choice(u, vm.log.settings.unit == u) { vm.setUnit(u) }
                    }
                }
                Text(
                    "Display only. Every set already stores its own unit, so switching " +
                        "never reinterprets anything you have logged.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = TextFaint,
                )
            }
        }

        item {
            SettingsGroup("Rest timer") {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90, 120, 180, 240, 300).forEach { sec ->
                        Choice("${sec / 60}:${"%02d".format(sec % 60)}",
                            vm.log.settings.restSeconds == sec) { vm.setRestSeconds(sec) }
                    }
                }
            }
        }

        item {
            SettingsGroup("About") {
                InfoRow("Sessions", "${vm.log.sessions.size}")
                InfoRow("Exercises", "${vm.log.exercises.size}")
                InfoRow("Needing review", "${vm.log.openReviewCount}")
                InfoRow("Schema version", "${vm.log.schemaVersion}")
                vm.log.meta.import?.sourceFile?.let { InfoRow("Imported from", it) }
                InfoRow("File", vm.storePath, small = true)
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScopeShim.() -> Unit) {
    Surface(color = SurfaceColor, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = TextFaint)
            ColumnScopeShim.content()
        }
    }
}

/** Compose needs a receiver for the trailing lambda; nothing more than that. */
object ColumnScopeShim

@Composable
private fun ColumnScopeShim.ActionRow(
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color = Accent,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp)
            .clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}

@Composable
private fun ColumnScopeShim.InfoRow(label: String, value: String, small: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            value,
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            color = if (small) TextFaint else TextPrimary,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Accent.copy(alpha = 0.25f) else Ink,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Text(
            label, Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Accent else TextFaint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
