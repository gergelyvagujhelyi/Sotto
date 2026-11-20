package com.sotto.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Swallows every touch on this element and its children while [block] is true.
 * Content that is animating in or out is still drawn; this keeps it from acting
 * on taps the user meant for what replaces it.
 */
fun Modifier.blockTouches(block: Boolean): Modifier =
    if (!block) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Initial pass runs parent-first, so children only see consumed events.
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }

/** This content is animating out: it still draws but must no longer take input. */
@OptIn(ExperimentalAnimationApi::class)
val AnimatedVisibilityScope.isExiting: Boolean
    get() = transition.targetState == EnterExitState.PostExit

/** This content has finished animating in and is not on its way out. */
@OptIn(ExperimentalAnimationApi::class)
val AnimatedVisibilityScope.isSettled: Boolean
    get() = transition.currentState == EnterExitState.Visible &&
        transition.targetState == EnterExitState.Visible
