package com.bytelog.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * Page transitions. Pages arrive on springs with a slight overshoot, matching the Diary's motion:
 *  - most pages slide in from the right while the page underneath recedes;
 *  - Sparky chat grows out of the sparkle button in the top bar;
 *  - recent meals rises from the bottom.
 * With "Remove animations" on, every transition is instant.
 */
object NavMotion {
    const val CHAT = "chat"
    const val RECENT_MEALS = "recent_meals"

    private val slide = spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    private val slideOut = spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = IntOffset.VisibilityThreshold)
    private val grow = spring<Float>(dampingRatio = 0.62f, stiffness = 380f)
    private val shrink = spring<Float>(dampingRatio = 1f, stiffness = 520f)
    private val recede = spring<Float>(dampingRatio = 0.85f, stiffness = 420f)

    /** Where the sparkle button sits in the Diary's top bar, as a fraction of the screen. */
    private val sparkleOrigin = TransformOrigin(0.86f, 0.04f)

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.targetRoute() = targetState.destination.route
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.initialRoute() = initialState.destination.route

    // ----- the page that opens -----

    fun enter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, reduce: Boolean): EnterTransition = when {
        reduce -> EnterTransition.None
        scope.targetRoute() == CHAT ->
            scaleIn(grow, initialScale = 0.6f, transformOrigin = sparkleOrigin) + fadeIn(tween(140))
        scope.targetRoute() == RECENT_MEALS ->
            slideInVertically(slide) { it } + fadeIn(tween(160))
        else -> slideInHorizontally(slide) { it } + fadeIn(tween(160))
    }

    fun popExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, reduce: Boolean): ExitTransition = when {
        reduce -> ExitTransition.None
        scope.initialRoute() == CHAT ->
            scaleOut(shrink, targetScale = 0.6f, transformOrigin = sparkleOrigin) + fadeOut(tween(160))
        scope.initialRoute() == RECENT_MEALS ->
            slideOutVertically(slideOut) { it } + fadeOut(tween(200))
        else -> slideOutHorizontally(slideOut) { it } + fadeOut(tween(200))
    }

    // ----- the page underneath -----

    fun exit(scope: AnimatedContentTransitionScope<NavBackStackEntry>, reduce: Boolean): ExitTransition = when {
        reduce -> ExitTransition.None
        // A page sliding in from the right nudges this one left; the others leave it in place.
        scope.targetRoute() == CHAT || scope.targetRoute() == RECENT_MEALS ->
            scaleOut(recede, targetScale = 0.94f) + fadeOut(tween(260), targetAlpha = 0.35f)
        else -> slideOutHorizontally(slideOut) { -it / 5 } + scaleOut(recede, targetScale = 0.94f) + fadeOut(tween(260), targetAlpha = 0.35f)
    }

    fun popEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>, reduce: Boolean): EnterTransition = when {
        reduce -> EnterTransition.None
        scope.initialRoute() == CHAT || scope.initialRoute() == RECENT_MEALS ->
            scaleIn(grow, initialScale = 0.94f) + fadeIn(tween(220), initialAlpha = 0.35f)
        else -> slideInHorizontally(slide) { -it / 5 } + scaleIn(grow, initialScale = 0.94f) + fadeIn(tween(220), initialAlpha = 0.35f)
    }
}
