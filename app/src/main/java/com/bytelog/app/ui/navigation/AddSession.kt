package com.bytelog.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/** The search screen's route; the scanner and others opened from it share its tray. */
const val SEARCH_ROUTE = "search/{mealTypeId}/{mealName}/{date}?photo={photo}&plan={plan}&q={q}"

/**
 * Whose view model holds the tray for [entry]: the search screen it was opened from if there is one (so searching and
 * scanning add to one tray), otherwise the screen's own.
 */
@Composable
fun rememberTrayOwner(navController: NavController, entry: NavBackStackEntry): ViewModelStoreOwner =
    remember(entry) { runCatching { navController.getBackStackEntry(SEARCH_ROUTE) }.getOrNull() } ?: entry
