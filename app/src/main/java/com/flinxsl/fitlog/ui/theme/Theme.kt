package com.flinxsl.fitlog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val FitLogColors = darkColorScheme(
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    primary = Done,
    onPrimary = Ink,
    secondary = Accent,
    onSecondary = Ink,
    tertiary = Short,
    onTertiary = Ink,
    error = Failed,
    onError = Ink,
    outline = Outline,
)

/**
 * Everything is a step larger than Material's defaults. The smallest text here is
 * 14sp and body text is 18sp, because this gets read mid-set from arm's length.
 */
private val FitLogType = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

/** The log's own notation is monospaced so columns of numbers line up while scrolling. */
val LogTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 25.sp,
)

/**
 * Note there is no dynamicColor parameter. Material You would pull unpredictable
 * hues from the wallpaper and destroy the done / short / failed meanings, so this
 * app is always dark and always these colours.
 */
@Composable
fun FitlogTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = FitLogColors, typography = FitLogType, content = content)
}
