package com.buildplan.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A small neutral scale plus one cool accent. Deliberately narrow: the app is
 * meant to read as a technical tool, not as a colourful dashboard.
 *
 * The accent is the same blue the renderer paints a picked element in, so a
 * selected control and a selected wall say "chosen" in one voice.
 */
internal val Canvas = Color(0xFF0C0D0F)
internal val Panel = Color(0xFF141619)
internal val PanelHigh = Color(0xFF1E2124)
internal val Line = Color(0xFF26292D)
internal val Ink = Color(0xFFEDEFF1)
internal val InkMuted = Color(0xFFA4A8AD)
internal val Accent = Color(0xFF65B6E0)
internal val OnAccent = Color(0xFF04212E)
internal val Danger = Color(0xFFE0705F)
internal val OnDanger = Color(0xFF2A0B06)

/**
 * The workspace chrome's glass: a dark tint the house stays visible through,
 * and the two ends of the rim gradient that make it read as a pane.
 *
 * The alpha is set for text, not for effect: over the light study backdrop
 * the tint composites to about #2E2F31, and the muted ink above still reads
 * at better than 4.5:1. A thinner glass looked more like glass and made
 * every unselected label fail.
 */
internal val GlassTint = Color(0xFF0F1113).copy(alpha = 0.84f)
internal val GlassRimHigh = Color.White.copy(alpha = 0.16f)
internal val GlassRimLow = Color.White.copy(alpha = 0.03f)

/** The scrims that settle the top and bottom edges of the canvas for text. */
internal val Scrim = Color(0xFF07080A)
