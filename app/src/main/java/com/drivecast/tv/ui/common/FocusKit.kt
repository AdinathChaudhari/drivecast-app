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
    this.focusRestorer {
        // A lane fallback points at the first item of a Lazy* layout (or a tab subtree
        // disposing during the AnimatedContent crossfade). If that node is recycled/detached
        // at restore time, focusRestorer's internal requestFocus() throws
        // "FocusRequester is not initialized" straight out of dispatchKeyEvent and kills the
        // process. Request focus ourselves under a guard, then return Cancel so the restorer
        // does not request again. Invariant preserved (a fallback is still supplied); in the
        // normal case focus still lands on the lane's first item, and in the rare detached
        // race the press is a harmless no-op instead of a crash.
        val target = onRestoreFailed?.invoke() ?: FocusRequester.Default
        if (target == FocusRequester.Default || target == FocusRequester.Cancel) {
            target
        } else {
            runCatching { target.requestFocus() }
            FocusRequester.Cancel
        }
    }.focusGroup()

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
                // Already fully within the viewport -> don't re-pin; matches foundation's default
                // minimal bring-into-view and keeps the focused card from animating on every step.
                if (offset >= 0f && offset + size <= containerSize) return 0f
                val initial = parentFraction * containerSize
                val target = if (size <= containerSize && (containerSize - initial) < size) containerSize - size else initial
                return offset - target
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
