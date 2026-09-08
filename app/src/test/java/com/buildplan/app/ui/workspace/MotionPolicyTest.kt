package com.buildplan.app.ui.workspace

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.graphics.TransformOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell's one timing policy: entrances slower than exits, everything
 * under a quarter of a second, and a cut for everything when the system asks
 * for animations to be removed.
 */
class MotionPolicyTest {

    @Test
    fun theRemoveAnimationsSettingReducesMotionAndSlowMotionDoesNot() {
        assertTrue(MotionPolicy.fromAnimatorScale(0f).reduced)
        assertFalse(MotionPolicy.fromAnimatorScale(1f).reduced)
        assertFalse(MotionPolicy.fromAnimatorScale(0.5f).reduced)
        // A developer's 10x slow motion asks to see motion, not to lose it.
        assertFalse(MotionPolicy.fromAnimatorScale(10f).reduced)
    }

    @Test
    fun fullMotionUsesTweensWithExitsQuickerThanEntrances() {
        val enter = MotionPolicy.Full.enter<Float>()
        val exit = MotionPolicy.Full.exit<Float>()

        assertTrue(enter is TweenSpec)
        assertTrue(exit is TweenSpec)
        assertTrue((exit as TweenSpec).durationMillis < (enter as TweenSpec).durationMillis)
        assertTrue(enter.durationMillis <= 300)
        assertTrue(MotionPolicy.Full.settle<Float>() is TweenSpec)
        val travel = MotionPolicy.Full.travel<Float>()
        assertTrue(travel is TweenSpec)
        assertTrue((travel as TweenSpec).durationMillis <= 300)
        // A small move takes the short end; the scale is clamped at both ends.
        val nudge = MotionPolicy.Full.travel<Float>(share = 0f) as TweenSpec
        val turn = MotionPolicy.Full.travel<Float>(share = 5f) as TweenSpec
        assertTrue(nudge.durationMillis < travel.durationMillis)
        assertEquals(MotionPolicy.TRAVEL_MIN_MILLIS, nudge.durationMillis)
        assertEquals(MotionPolicy.TRAVEL_MILLIS, turn.durationMillis)
    }

    @Test
    fun reducedMotionCollapsesEverySpecToACut() {
        val policy = MotionPolicy.Reduced

        assertTrue(policy.enter<Float>() is SnapSpec)
        assertTrue(policy.exit<Float>() is SnapSpec)
        assertTrue(policy.settle<Float>() is SnapSpec)
        assertTrue(policy.travel<Float>() is SnapSpec)

        val origin = TransformOrigin(1f, 0f)
        assertEquals(EnterTransition.None, policy.panelEnter(origin))
        assertEquals(ExitTransition.None, policy.panelExit(origin))
        assertEquals(EnterTransition.None, policy.unfoldEnter())
        assertEquals(ExitTransition.None, policy.unfoldExit())
    }

    @Test
    fun fullMotionPanelsActuallyMove() {
        val origin = TransformOrigin(1f, 0f)

        assertNotEquals(EnterTransition.None, MotionPolicy.Full.panelEnter(origin))
        assertNotEquals(ExitTransition.None, MotionPolicy.Full.panelExit(origin))
        assertNotEquals(EnterTransition.None, MotionPolicy.Full.unfoldEnter())
        assertNotEquals(ExitTransition.None, MotionPolicy.Full.unfoldExit())
    }
}
