package com.bytelog.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bytelog.app.ui.theme.LocalReduceMotion

/**
 * Page transitions. Pages arrive on springs with a slight overshoot, matching the Diary's motion:
 *  - most pages slide in from the right while the page underneath recedes;
 *  - Sparky chat grows out of the sparkle button in the top bar;
 *  - recent meals rises from the bottom.
 * With "Remove animations" on, every transition is instant.
 *
 * Going back shrinks the page into a rounded card that follows the back gesture (predictive back), so you can
 * peek at the page underneath and change your mind.
 */
object NavMotion {
    const val CHAT = "chat"
    const val RECENT_MEALS = "recent_meals"

    private val slide = spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = IntOffset.VisibilityThreshold)
    private val slideOut = spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = IntOffset.VisibilityThreshold)
    private val grow = spring<Float>(dampingRatio = 0.62f, stiffness = 380f)
    private val shrink = spring<Float>(dampingRatio = 1f, stiffness = 520f)
    private val recede = spring<Float>(dampingRatio = 0.85f, stiffness = 420f)
    private const val BACK_MS = 320

    /** Where the sparkle button sits in the Diary's top bar, as a fraction of the screen. */
    private val sparkleOrigin = TransformOrigin(0.78f, 0.06f)

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
        // Going back (and the predictive back gesture, which plays this as the finger moves): the page shrinks
        // into a rounded card and drifts towards the edge while the page underneath comes forward
        // (on one timeline, so halfway through the gesture is halfway through the motion: the card stays solid
        // until late and only fades as it lets go)
        else -> scaleOut(tween(BACK_MS, easing = FastOutSlowInEasing), targetScale = 0.86f) +
            slideOutHorizontally(tween(BACK_MS, easing = FastOutSlowInEasing)) { it / 6 } +
            fadeOut(tween(BACK_MS / 3, delayMillis = BACK_MS * 2 / 3))
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

/** Corner radius of a page while it moves in or out; square once it settles. */
private val PAGE_CORNER = 32.dp

/**
 * A destination whose page is a rounded card while it animates (opening, closing, and following the predictive
 * back gesture) and square at rest.
 */
fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(route, arguments) { entry ->
    val scope = this
    val reduce = LocalReduceMotion.current
    val corner by transition.animateDp(label = "page_corner") { if (it == EnterExitState.Visible || reduce) 0.dp else PAGE_CORNER }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner))) { scope.content(entry) }
}
