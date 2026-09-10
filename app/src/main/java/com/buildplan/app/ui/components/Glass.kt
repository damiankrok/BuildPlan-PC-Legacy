package com.buildplan.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.buildplan.app.ui.theme.GlassRimHigh
import com.buildplan.app.ui.theme.GlassRimLow
import com.buildplan.app.ui.theme.GlassTint
import com.buildplan.app.ui.theme.GlassTintOpaque

/**
 * The one material every piece of workspace chrome is made of: a sheet of
 * smoked glass over the house.
 *
 * It is a translucent tint with a hairline rim that catches light along its
 * top edge and fades out towards the bottom — enough for the eye to read a
 * pane sitting in front of the model. It deliberately does not blur what is
 * behind it. The model is drawn by Filament on its own surface, which the
 * window cannot sample, so a blur here would either be faked from nothing or
 * cost a second rendering of the scene; a clean tint over a moving model is
 * more honest than either, and cheaper than both.
 *
 * A pane is also solid to the finger. Compose lets a touch fall through any
 * area with no pointer handling of its own, and under every pane is the
 * canvas, whose handler would orbit the camera or pick a wall from behind a
 * panel title. The pane therefore takes part in hit testing over its whole
 * area, without consuming anything, so that its own buttons, lists and drag
 * handles keep working while the model behind it is left alone.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = GlassDefaults.PanelShape,
    tint: Color = GlassTint,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Present in the hit path, silent in every pass: the
                    // gesture is watched to its end and nothing is consumed.
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                }
            }
            .clip(shape)
            .background(tint)
            .border(
                width = GlassDefaults.RimWidth,
                brush = Brush.verticalGradient(listOf(GlassRimHigh, GlassRimLow)),
                shape = shape,
            ),
        content = content,
    )
}

object GlassDefaults {
    val RimWidth = 1.dp
    val PanelShape: Shape = RoundedCornerShape(18.dp)
    val RailShape: Shape = RoundedCornerShape(28.dp)
    val PillShape: Shape = CircleShape

    /**
     * The tint for a pane that sits over another pane.
     *
     * Glass is a one-layer material. Stack two and the text underneath keeps
     * showing through both; pass this to the upper one instead.
     */
    val OpaqueTint: Color = GlassTintOpaque
}
