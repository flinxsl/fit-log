package com.flinxsl.fitlog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.flinxsl.fitlog.ui.theme.TextPrimary
import com.flinxsl.fitlog.ui.theme.TextSecondary

/**
 * A screen title with a back affordance.
 *
 * System back already works everywhere via BackHandler, but a visible target
 * matters when one hand is holding a bar.
 */
@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", style = MaterialTheme.typography.headlineMedium, color = TextSecondary) }
        }
        Column(Modifier.padding(start = if (onBack != null) 4.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }
    }
}
