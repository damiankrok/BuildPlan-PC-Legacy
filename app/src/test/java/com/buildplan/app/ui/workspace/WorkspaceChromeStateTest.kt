package com.buildplan.app.ui.workspace

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workspace has one surface open at a time, and back closes it.
 * These are the rules the rail, the timeline and the back gesture rely on.
 */
class WorkspaceChromeStateTest {

    @Test
    fun startsWithNothingOpen() {
        val state = WorkspaceChromeState()

        assertEquals(WorkspaceSurface.None, state.surface)
        assertFalse(state.timelineExpanded)
        assertNull(state.openTool)
    }

    @Test
    fun aToolPanelTogglesOnItsOwnKey() {
        val state = WorkspaceChromeState()

        state.toggleTool("views")
        assertEquals("views", state.openTool)
        assertTrue(state.isToolOpen("views"))

        state.toggleTool("views")
        assertNull(state.openTool)
        assertEquals(WorkspaceSurface.None, state.surface)
    }

    @Test
    fun openingAnotherToolReplacesTheOpenOneInPlace() {
        val state = WorkspaceChromeState()
        state.toggleTool("layers")

        state.toggleTool("style")

        assertEquals("style", state.openTool)
        assertFalse(state.isToolOpen("layers"))
    }

    @Test
    fun theTimelineAndAToolPanelAreNeverOpenTogether() {
        val state = WorkspaceChromeState()

        state.toggleTool("views")
        state.toggleTimeline()
        assertTrue(state.timelineExpanded)
        assertNull(state.openTool)

        state.toggleTool("views")
        assertFalse(state.timelineExpanded)
        assertEquals("views", state.openTool)
    }

    @Test
    fun setTimelineExpandedOnlyClosesTheTimelineItself() {
        val state = WorkspaceChromeState()
        state.toggleTool("views")

        // A downward swipe on the rail while a tool is open leaves the tool alone.
        state.setTimelineExpanded(false)
        assertEquals("views", state.openTool)

        state.setTimelineExpanded(true)
        assertTrue(state.timelineExpanded)
        state.setTimelineExpanded(true)
        assertTrue(state.timelineExpanded)
        state.setTimelineExpanded(false)
        assertEquals(WorkspaceSurface.None, state.surface)
    }

    @Test
    fun dismissReportsWhetherItClosedAnything() {
        val state = WorkspaceChromeState()

        assertFalse(state.dismiss())

        state.toggleTimeline()
        assertTrue(state.dismiss())
        assertEquals(WorkspaceSurface.None, state.surface)

        state.toggleTool("source")
        assertTrue(state.dismiss())
        assertFalse(state.dismiss())
    }

    @Test
    fun theOpenSurfaceSurvivesRecreation() {
        val scope = SaverScope { true }
        fun roundTrip(surface: WorkspaceSurface): WorkspaceSurface {
            val saved = with(WorkspaceChromeState.Saver) { scope.save(WorkspaceChromeState(surface)) }
            return WorkspaceChromeState.Saver.restore(requireNotNull(saved))!!.surface
        }

        assertEquals(WorkspaceSurface.None, roundTrip(WorkspaceSurface.None))
        assertEquals(WorkspaceSurface.Timeline, roundTrip(WorkspaceSurface.Timeline))
        assertEquals(WorkspaceSurface.Tool("views"), roundTrip(WorkspaceSurface.Tool("views")))
    }
}
