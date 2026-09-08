package com.buildplan.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A small pane of glass that says what something is: the inspector for a
 * picked element, or any other fact that is only true while something is
 * chosen. It has a name, an optional second line and a close, and then
 * whatever the caller has to add. It appears next to what it describes and
 * goes away with it.
 */
@Composable
fun ContextPanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    GlassSurface(modifier = modifier.widthIn(max = ContextPanelDefaults.MaxWidth)) {
        Column {
            PanelHeader(title = title, supporting = supporting, onClose = onClose)
            if (content != null) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    content()
                }
            }
        }
    }
}

object ContextPanelDefaults {
    val MaxWidth = 260.dp
}
