package com.flinxsl.fitlog.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Built for a phone held at arm's length in a gym, not for a desk.
 *
 * True black rather than dark grey: maximum contrast under bad overhead lighting,
 * and on the OLED panel most phones have, black pixels are simply off.
 *
 * The three state colours are reinforcement, never the only signal. A short set
 * shows a different number and a failed set shows an X, so the screen still reads
 * correctly through glare, through sweat, and for colourblind eyes.
 */

val Ink = Color(0xFF000000)          // page
val Surface = Color(0xFF141414)      // cards and rows
val SurfaceHigh = Color(0xFF1F1F1F)  // the row you are working on
val Outline = Color(0xFF3A3A3A)

val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB4B4B4)
val TextFaint = Color(0xFF8E8E8E)   // 5.6:1 on a card; 7A7A7A was 4.3 and failed AA

val Done = Color(0xFF00E5A0)         // hit the target
val Short = Color(0xFFFFB300)        // fell short by some reps
val Failed = Color(0xFFFF5252)       // abandoned the set
val Accent = Color(0xFF4DA3FF)       // progression suggestions, links
