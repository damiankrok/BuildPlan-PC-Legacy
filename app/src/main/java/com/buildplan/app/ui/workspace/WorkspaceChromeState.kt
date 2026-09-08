package com.buildplan.app.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The one surface the workspace may have open over the house at a time.
 *
 * The house is the screen; everything else is chrome that opens on request
 * and closes again. Two things open at once — a tool panel and the timeline
 * detail — would cover most of the model and fight for the same thumb, so
 * the state is a single value and opening one surface closes the other.
 */
sealed interface WorkspaceSurface {
    /** Nothing open: the house, the edge chrome and the collapsed timeline. */
    data object None : WorkspaceSurface

    /** The timeline detail unfolded from the bottom rail. */
    data object Timeline : WorkspaceSurface

    /** One edge tool's panel, keyed by the tool that opened it. */
    data class Tool(val key: String) : WorkspaceSurface
}

/**
 * Which surface the workspace has open. Pure state, no Compose UI: it is the
 * thing the back gesture, the rail, the timeline and the tests all agree on.
 */
@Stable
class WorkspaceChromeState(initial: WorkspaceSurface = WorkspaceSurface.None) {

    var surface: WorkspaceSurface by mutableStateOf(initial)
        private set

    val timelineExpanded: Boolean get() = surface == WorkspaceSurface.Timeline

    /** The tool whose panel is open, or null when none is. */
    val openTool: String? get() = (surface as? WorkspaceSurface.Tool)?.key

    fun isToolOpen(key: String): Boolean = openTool == key

    fun toggleTimeline() {
        surface = if (timelineExpanded) WorkspaceSurface.None else WorkspaceSurface.Timeline
    }

    fun setTimelineExpanded(expanded: Boolean) {
        if (expanded) {
            surface = WorkspaceSurface.Timeline
        } else if (timelineExpanded) {
            surface = WorkspaceSurface.None
        }
    }

    /** Opens [key]'s panel, or closes it when it is the one already open. */
    fun toggleTool(key: String) {
        surface = if (isToolOpen(key)) WorkspaceSurface.None else WorkspaceSurface.Tool(key)
    }

    /**
     * Closes whatever is open. Returns whether anything was, so a back gesture
     * knows if it has been consumed or should leave the screen.
     */
    fun dismiss(): Boolean {
        if (surface == WorkspaceSurface.None) return false
        surface = WorkspaceSurface.None
        return true
    }

    companion object {
        private const val SAVED_NONE = ""
        private const val SAVED_TIMELINE = "timeline"
        private const val SAVED_TOOL_PREFIX = "tool:"

        /** Survives recreation as one string: which surface, and for a tool, which one. */
        val Saver: Saver<WorkspaceChromeState, String> = Saver(
            save = { state ->
                when (val open = state.surface) {
                    WorkspaceSurface.None -> SAVED_NONE
                    WorkspaceSurface.Timeline -> SAVED_TIMELINE
                    is WorkspaceSurface.Tool -> SAVED_TOOL_PREFIX + open.key
                }
            },
            restore = { saved ->
                WorkspaceChromeState(
                    when {
                        saved == SAVED_TIMELINE -> WorkspaceSurface.Timeline
                        saved.startsWith(SAVED_TOOL_PREFIX) ->
                            WorkspaceSurface.Tool(saved.removePrefix(SAVED_TOOL_PREFIX))
                        else -> WorkspaceSurface.None
                    },
                )
            },
        )
    }
}

@Composable
fun rememberWorkspaceChromeState(): WorkspaceChromeState =
    rememberSaveable(saver = WorkspaceChromeState.Saver) { WorkspaceChromeState() }
