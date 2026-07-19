package com.drivecast.tv.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer

/**
 * One wrapper around [Modifier.focusRestorer] so the Compose 1.8 signature
 * change (when we eventually bump past Compose 1.7) is a one-line fix here
 * instead of a sweep across every call site. [Modifier.focusGroup] shrinks
 * the D-pad focus search space to this subtree.
 *
 * [onRestoreFailed] is the reason this wrapper exists at all: without it, a
 * restorer whose remembered child left composition (tab switch rebuilt the
 * lane, or a lazy layout recycled the card) fails the restore and SWALLOWS
 * the key event — the "dead D-pad press". Every lane must pass a fallback
 * (usually a FocusRequester on its first item) so entering the lane always
 * lands somewhere.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tvFocusRestorer(onRestoreFailed: (() -> FocusRequester)? = null): Modifier =
    this.focusRestorer(onRestoreFailed).focusGroup()

/**
 * Pins the pivot fraction used when bringing a focused child of a lazy
 * layout into view, so the focused card holds a stable keyline near the
 * overscan-safe zone instead of drifting to wherever foundation's default
 * pivot lands.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PositionFocusedItemInLazyLayout(
    parentFraction: Float = 0.10f,
    content: @Composable () -> Unit,
) {
    val spec = remember(parentFraction) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val initial = parentFraction * containerSize
                val target = if (size <= containerSize && (containerSize - initial) < size) containerSize - size else initial
                return offset - target
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
