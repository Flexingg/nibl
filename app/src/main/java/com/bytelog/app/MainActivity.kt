package com.bytelog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bytelog.app.ui.screens.BarcodeScannerScreen
import com.bytelog.app.ui.screens.DiaryScreen
import com.bytelog.app.ui.screens.FastSearchScreen
import com.bytelog.app.ui.screens.SettingsScreen
import com.bytelog.app.ui.splash.AnimatedSplashScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import com.bytelog.app.ui.viewmodel.FastSearchViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ System Splash Screen hook
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ByteLogApplication
        val foodRepo = app.foodRepository
        val settingsRepo = app.settingsRepository

        setContent {
            ByteLogTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showAnimatedSplash by remember { mutableStateOf(true) }

                    if (showAnimatedSplash) {
                        AnimatedSplashScreen(
                            onSplashFinished = {
                                showAnimatedSplash = false
                            }
                        )
                    } else {
                        ByteLogNavHost(
                            app = app,
                            foodRepo = foodRepo,
                            settingsRepo = settingsRepo
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ByteLogNavHost(
    app: ByteLogApplication,
    foodRepo: com.bytelog.app.data.repository.FoodRepository,
    settingsRepo: com.bytelog.app.data.repository.SettingsRepository
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "diary"
    ) {
        // 1. Daily Diary / Timeline
        composable("diary") {
            val diaryViewModel: DiaryViewModel = viewModel {
                DiaryViewModel(foodRepo, settingsRepo)
            }
            DiaryScreen(
                viewModel = diaryViewModel,
                onNavigateToSearch = { mealId, mealName, date ->
                    navController.navigate("search/$mealId/$mealName/$date")
                },
                onNavigateToBarcode = { mealId, mealName, date ->
                    navController.navigate("barcode/$mealId/$mealName/$date")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        // 2. Fast-Search & Multi-Add Staging
        composable(
            route = "search/{mealTypeId}/{mealName}/{date}",
            arguments = listOf(
                navArgument("mealTypeId") { type = NavType.StringType },
                navArgument("mealName") { type = NavType.StringType },
                navArgument("date") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val mealTypeId = backStackEntry.arguments?.getString("mealTypeId") ?: "breakfast"
            val mealName = backStackEntry.arguments?.getString("mealName") ?: "Breakfast"
            val date = backStackEntry.arguments?.getString("date") ?: ""

            val fastSearchViewModel: FastSearchViewModel = viewModel {
                FastSearchViewModel(foodRepo).apply {
                    initContext(mealTypeId, mealName, date)
                }
            }

            FastSearchScreen(
                viewModel = fastSearchViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBarcode = {
                    navController.navigate("barcode/$mealTypeId/$mealName/$date")
                }
            )
        }

        // 3. Continuous Burst Barcode Scanner
        composable(
            route = "barcode/{mealTypeId}/{mealName}/{date}",
            arguments = listOf(
                navArgument("mealTypeId") { type = NavType.StringType },
                navArgument("mealName") { type = NavType.StringType },
                navArgument("date") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val mealTypeId = backStackEntry.arguments?.getString("mealTypeId") ?: "breakfast"
            val mealName = backStackEntry.arguments?.getString("mealName") ?: "Breakfast"
            val date = backStackEntry.arguments?.getString("date") ?: ""

            val fastSearchViewModel: FastSearchViewModel = viewModel {
                FastSearchViewModel(foodRepo).apply {
                    initContext(mealTypeId, mealName, date)
                }
            }

            BarcodeScannerScreen(
                viewModel = fastSearchViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 4. Settings Screen
        composable("settings") {
            SettingsScreen(
                settingsRepository = settingsRepo,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
