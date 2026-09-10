package com.buildplan.app.ui.workspace

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext

/**
 * How the workspace chrome moves, decided once and read everywhere.
 *
 * Every transition in the shell asks this policy for its spec instead of
 * naming a duration of its own, so the whole surface speaks with one timing
 * — entrances a little slower than exits, both under a quarter of a second —
 * and so that a person who has asked Android to remove animations gets exactly
 * that: every spec collapses to a cut, and nothing in the shell has to know.
 *
 * Compose animates on its own clock and ignores the developer setting that
 * scales `Animator`s, so the "Remove animations" accessibility switch has to
 * be read and honoured here rather than inherited from the platform.
 */
data class MotionPolicy(val reduced: Boolean) {

    /** A transition that should be felt as arriving. */
    fun <T> enter(): FiniteAnimationSpec<T> = spec(ENTER_MILLIS, EmphasizedDecelerate)

    /**
     * A transition that should be felt as leaving — quicker than arriving,
     * and eased *out* like everything else: the closing pane is the thing
     * under the finger, and an ease-in that sits still for its first sixty
     * milliseconds feels slower than its duration says (AUDIT-2).
     */
    fun <T> exit(): FiniteAnimationSpec<T> = spec(EXIT_MILLIS, Standard)

    /** A change of state in place: a selection moving, a colour settling. */
    fun <T> settle(): FiniteAnimationSpec<T> = spec(SETTLE_MILLIS, Standard)

    /**
     * Arriving, but only after the thing it replaces has left.
     *
     * Two overlapping fades are the right answer for a photograph and the
     * wrong one for a paragraph: for the length of the overlap both texts are
     * drawn on the same lines and neither can be read. Waiting out the exit
     * costs the eye nothing and buys a clean substitution, so any content
     * swap where both sides are dense text uses this instead of a crossfade.
     */
    fun <T> enterDelayed(): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis = ENTER_MILLIS, delayMillis = EXIT_MILLIS, easing = EmphasizedDecelerate)

    /**
     * The camera travelling to a named view. The one transition allowed the
     * full three hundred milliseconds: it moves the whole picture, and a
     * shorter cut reads as a different house rather than the same one turned.
     *
     * Eased in and out, not only out — the camera is not arriving, it is a
     * thing already on screen that moves — and scaled by [share], how large
     * the move is (0 = nothing, 1 = half a turn or a full change of
     * distance): a ten-degree tweak takes the short end, a turn round the
     * house the long one, so neither idles.
     */
    fun <T> travel(share: Float = 1f): FiniteAnimationSpec<T> {
        val fraction = share.coerceIn(0f, 1f)
        val millis = TRAVEL_MIN_MILLIS + ((TRAVEL_MILLIS - TRAVEL_MIN_MILLIS) * fraction).toInt()
        return spec(millis, Travel)
    }

    private fun <T> spec(millis: Int, easing: Easing): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis = millis, easing = easing)

    /** A panel growing out of the control that opened it. */
    fun panelEnter(origin: TransformOrigin): EnterTransition =
        if (reduced) {
            EnterTransition.None
        } else {
            fadeIn(enter()) + scaleIn(enter(), initialScale = PANEL_REST_SCALE, transformOrigin = origin)
        }

    /** The same panel folding back into it. */
    fun panelExit(origin: TransformOrigin): ExitTransition =
        if (reduced) {
            ExitTransition.None
        } else {
            fadeOut(exit()) + scaleOut(exit(), targetScale = PANEL_REST_SCALE, transformOrigin = origin)
        }

    /** Content unfolding downwards from an edge that stays put. */
    fun unfoldEnter(): EnterTransition =
        if (reduced) EnterTransition.None else fadeIn(enter()) + expandVertically(enter(), Alignment.Top)

    fun unfoldExit(): ExitTransition =
        if (reduced) ExitTransition.None else fadeOut(exit()) + shrinkVertically(exit(), Alignment.Top)

    companion object {
        const val ENTER_MILLIS = 220
        const val EXIT_MILLIS = 150
        const val SETTLE_MILLIS = 180
        const val TRAVEL_MILLIS = 300
        const val TRAVEL_MIN_MILLIS = 150

        /** How far a panel starts short of its size: near enough to read as the same object. */
        const val PANEL_REST_SCALE = 0.92f

        /** Material 3 emphasized decelerate: fast to start, long to settle — for arrivals. */
        val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** Material 3 standard: an ease-out for exits and settling state. */
        val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** A symmetric ease-in-out for something that moves while already on screen. */
        val Travel: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

        val Full = MotionPolicy(reduced = false)
        val Reduced = MotionPolicy(reduced = true)

        /**
         * The policy Android's animator scale implies. Zero is what the
         * "Remove animations" accessibility setting writes; anything positive,
         * including a developer's slow-motion 10x, still animates here — the
         * request behind a slow scale is to see motion, not to lose it.
         */
        fun fromAnimatorScale(scale: Float): MotionPolicy = MotionPolicy(reduced = scale <= 0f)
    }
}

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy.Full }

/**
 * The policy the device currently asks for, kept current while the setting
 * changes underneath a running app.
 */
@Composable
fun rememberSystemMotionPolicy(): MotionPolicy {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read(): MotionPolicy = MotionPolicy.fromAnimatorScale(
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    )

    var policy by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                policy = read()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return policy
}
