package com.bytelog.app

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.bytelog.app.ui.screens.SparkyChatScreen
import com.bytelog.app.ui.splash.AnimatedSplashScreen
import com.bytelog.app.ui.theme.ByteLogTheme
import com.bytelog.app.ui.theme.LocalReduceMotion
import com.bytelog.app.ui.navigation.NavMotion
import com.bytelog.app.ui.screens.RecentMealsScreen
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import com.bytelog.app.ui.viewmodel.FastSearchViewModel
import com.bytelog.app.ui.viewmodel.FamilyViewModel
import com.bytelog.app.ui.viewmodel.MemberViewModel
import com.bytelog.app.ui.viewmodel.FoodEditorViewModel
import com.bytelog.app.ui.viewmodel.MyFoodsViewModel
import com.bytelog.app.ui.foods.FoodEditorScreen
import com.bytelog.app.ui.foods.MyFoodsScreen
import com.bytelog.app.ui.viewmodel.SparkyChatViewModel
import com.bytelog.app.ui.viewmodel.RecipesViewModel
import com.bytelog.app.ui.recipes.CookModeScreen
import com.bytelog.app.ui.recipes.RecipeDetailScreen
import com.bytelog.app.ui.recipes.RecipeImportScreen

class MainActivity : ComponentActivity() {

    /** Text shared into the app (usually a recipe page URL). Consumed by the nav host. */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSharedText(intent)
    }

    private fun readSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { sharedText.value = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ System Splash Screen hook
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ByteLogApplication
        val foodRepo = app.foodRepository
        val settingsRepo = app.settingsRepository
        if (savedInstanceState == null) readSharedText(intent)

        setContent {
            val themeMode by settingsRepo.themeMode.collectAsState(initial = com.bytelog.app.ui.theme.ThemeMode.SYSTEM)
            val deviceColors by settingsRepo.useDeviceColors.collectAsState(initial = true)
            val dark = when (themeMode) {
                com.bytelog.app.ui.theme.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                com.bytelog.app.ui.theme.ThemeMode.LIGHT -> false
                com.bytelog.app.ui.theme.ThemeMode.DARK -> true
            }
            ByteLogTheme(darkTheme = dark, dynamicColor = deviceColors) {
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
                            settingsRepo = settingsRepo,
                            sharedText = sharedText.value,
                            onSharedTextConsumed = { sharedText.value = null }
                        )
                    }
                }
            }
        }
    }
}

/** Key a page's saved state receives "<old id>|<new id>" under when the food editor it opened finishes. */
const val FOOD_EDIT_RESULT = "food_edit_result"

@Composable
fun ByteLogNavHost(
    app: ByteLogApplication,
    foodRepo: com.bytelog.app.data.repository.FoodRepository,
    settingsRepo: com.bytelog.app.data.repository.SettingsRepository,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    // Activity-scoped so the diary, recipe pages and cook mode share one recipes/batches state.
    val recipesViewModel: RecipesViewModel = viewModel {
        RecipesViewModel(app.recipeRepository, app.mealPrepRepository, app.mealTemplateRepository, foodRepo)
    }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            recipesViewModel.onSharedText(sharedText)
            onSharedTextConsumed()
            recipesViewModel.resetImport()
            navController.navigate("import") { launchSingleTop = true }
        }
    }

    // Family (people who share their Sparky diary with the user), shared by the Diary and the family pages
    val familyRepo = remember { com.bytelog.app.data.family.FamilyRepository(app.sparkyApiClient, foodRepo) }
    val familyViewModel: FamilyViewModel = viewModel { FamilyViewModel(familyRepo) }

    val reduceMotion = LocalReduceMotion.current
    NavHost(
        navController = navController,
        startDestination = "diary",
        enterTransition = { NavMotion.enter(this, reduceMotion) },
        exitTransition = { NavMotion.exit(this, reduceMotion) },
        popEnterTransition = { NavMotion.popEnter(this, reduceMotion) },
        popExitTransition = { NavMotion.popExit(this, reduceMotion) }
    ) {
        // 1. Daily Diary / Timeline
        composable("diary") {
            val diaryViewModel: DiaryViewModel = viewModel {
                DiaryViewModel(foodRepo, app.waterRepository, settingsRepo, app.weightRepository, app.mealTemplateRepository, app.sparkyApiClient)
            }
            DiaryScreen(
                viewModel = diaryViewModel,
                recipesViewModel = recipesViewModel,
                onOpenRecipe = { id -> navController.navigate("recipe/$id") },
                onImportRecipe = {
                    recipesViewModel.resetImport()
                    navController.navigate("import")
                },
                onNavigateToSearch = { mealId, mealName, date ->
                    navController.navigate("search/$mealId/$mealName/$date")
                },
                onNavigateToBarcode = { mealId, mealName, date ->
                    navController.navigate("barcode/$mealId/$mealName/$date")
                },
                onNavigateToChat = {
                    navController.navigate(NavMotion.CHAT) { launchSingleTop = true }
                },
                onNavigateToSettings = {
                    navController.navigate("settings") { launchSingleTop = true }
                },
                onOpenRecentMeals = {
                    navController.navigate(NavMotion.RECENT_MEALS) { launchSingleTop = true }
                },
                familyViewModel = familyViewModel,
                onOpenFamily = { navController.navigate("family") { launchSingleTop = true } },
                onOpenFamilyMember = { id -> navController.navigate("family/${android.net.Uri.encode(id)}") { launchSingleTop = true } }
            )
        }

        // 1b. Copy recent meals into the day open in the Diary (shares the Diary's view model)
        composable(NavMotion.RECENT_MEALS) { entry ->
            val diaryEntry = remember(entry) { navController.getBackStackEntry("diary") }
            val diaryViewModel: DiaryViewModel = viewModel(diaryEntry) {
                DiaryViewModel(foodRepo, app.waterRepository, settingsRepo, app.weightRepository, app.mealTemplateRepository, app.sparkyApiClient)
            }
            val targetDate by diaryViewModel.selectedDate.collectAsState()
            val recentMeals by diaryViewModel.recentMealsHistory.collectAsState()
            RecentMealsScreen(
                targetDate = targetDate,
                recentMeals = recentMeals,
                onBack = { navController.popBackStack() },
                onCopyMeals = { selected, date ->
                    diaryViewModel.copyMultipleMeals(selected, date)
                    navController.popBackStack()
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

            // The food editor reports back here so staged entries of an edited food follow the edit
            val foodEdit by backStackEntry.savedStateHandle.getStateFlow<String?>(FOOD_EDIT_RESULT, null).collectAsState()
            LaunchedEffect(foodEdit) {
                val parts = foodEdit?.split('|') ?: return@LaunchedEffect
                fastSearchViewModel.onFoodEdited(parts[0], parts.getOrNull(1)?.ifBlank { null })
                backStackEntry.savedStateHandle[FOOD_EDIT_RESULT] = null
            }

            FastSearchScreen(
                viewModel = fastSearchViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBarcode = {
                    navController.navigate("barcode/$mealTypeId/$mealName/$date")
                },
                onEditFood = { id -> navController.navigate("food/${android.net.Uri.encode(id)}") { launchSingleTop = true } }
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
                foodRepository = foodRepo,
                waterRepository = app.waterRepository,
                weightRepository = app.weightRepository,
                mealTemplateRepository = app.mealTemplateRepository,
                onNavigateBack = { navController.popBackStack() },
                onOpenMyFoods = { navController.navigate("my_foods") { launchSingleTop = true } },
                onOpenFamily = { navController.navigate("family") { launchSingleTop = true } }
            )
        }

        // 4d. Family: everyone sharing their diary, and one person's diary
        composable("family") {
            com.bytelog.app.ui.family.FamilyScreen(
                viewModel = familyViewModel,
                onBack = { navController.popBackStack() },
                onOpenMember = { id -> navController.navigate("family/${android.net.Uri.encode(id)}") { launchSingleTop = true } }
            )
        }
        composable(
            route = "family/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { entry ->
            val userId = entry.arguments?.getString("userId").orEmpty()
            val vm: MemberViewModel = viewModel { MemberViewModel(familyRepo, foodRepo, userId) }
            com.bytelog.app.ui.family.MemberDiaryScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        // 4b. The user's own foods on Sparky
        composable("my_foods") { entry ->
            val vm: MyFoodsViewModel = viewModel { MyFoodsViewModel(foodRepo) }
            val foodEdit by entry.savedStateHandle.getStateFlow<String?>(FOOD_EDIT_RESULT, null).collectAsState()
            LaunchedEffect(foodEdit) {
                if (foodEdit != null) { vm.refresh(); entry.savedStateHandle[FOOD_EDIT_RESULT] = null }
            }
            MyFoodsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenFood = { id -> navController.navigate("food/${android.net.Uri.encode(id)}") { launchSingleTop = true } }
            )
        }

        // 4c. Edit one food (saved to Sparky)
        composable(
            route = "food/{foodId}",
            arguments = listOf(navArgument("foodId") { type = NavType.StringType })
        ) { entry ->
            val foodId = entry.arguments?.getString("foodId").orEmpty()
            val vm: FoodEditorViewModel = viewModel { FoodEditorViewModel(foodRepo, foodId) }
            FoodEditorScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onFinished = { saved ->
                    // "<old id>|<new id>" (new id blank after a delete) for whichever page opened the editor
                    navController.previousBackStackEntry?.savedStateHandle?.set(FOOD_EDIT_RESULT, "$foodId|${saved?.id.orEmpty()}")
                    navController.popBackStack()
                }
            )
        }

        // 5. Recipe import (URL, shared link or pasted text)
        composable("import") {
            val initial = remember { recipesViewModel.consumeSharedText() }
            RecipeImportScreen(
                viewModel = recipesViewModel,
                initialInput = initial,
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate("recipe/$id") {
                        popUpTo("import") { inclusive = true }
                    }
                }
            )
        }

        // 6. Recipe page: ingredients, directions, batch & log actions
        composable(
            route = "recipe/{templateId}",
            arguments = listOf(navArgument("templateId") { type = NavType.StringType })
        ) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getString("templateId").orEmpty()
            RecipeDetailScreen(
                viewModel = recipesViewModel,
                templateId = templateId,
                onBack = { navController.popBackStack() },
                onCook = { navController.navigate("cook/$templateId") }
            )
        }

        // 7. Cook mode
        composable(
            route = "cook/{templateId}",
            arguments = listOf(navArgument("templateId") { type = NavType.StringType })
        ) { backStackEntry ->
            CookModeScreen(
                viewModel = recipesViewModel,
                templateId = backStackEntry.arguments?.getString("templateId").orEmpty(),
                onClose = { navController.popBackStack() }
            )
        }

        // 8. Sparky AI Chatbot Screen
        composable(NavMotion.CHAT) {
            val chatViewModel: SparkyChatViewModel = viewModel {
                SparkyChatViewModel(app.chatRepository)
            }
            SparkyChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
