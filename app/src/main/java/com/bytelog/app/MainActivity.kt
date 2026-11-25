package com.bytelog.app

import com.bytelog.app.ui.navigation.page
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
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

    /** Where a home screen widget tap asked to go (see WidgetLinks), until the nav host has acted on it. */
    private val widgetDestination = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readSharedText(intent)
        readWidgetDestination(intent)
    }

    private fun readWidgetDestination(intent: Intent?) {
        intent?.getStringExtra(com.bytelog.app.widget.WidgetLinks.EXTRA_DEST)?.let {
            widgetDestination.value = it
            intent.removeExtra(com.bytelog.app.widget.WidgetLinks.EXTRA_DEST)
        }
    }

    private fun readSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { sharedText.value = it }
        }
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate()
        // Health Connect is written while Nibl is open (on opening, and as the diary changes)
        (application as ByteLogApplication).apply { inForeground = true; requestHealthSync(delayMs = 500) }
    }

    override fun onPause() {
        (application as ByteLogApplication).inForeground = false
        super.onPause()
    }

    /** Looks for a new version on GitHub when the user lets it, at most every half hour. */
    private fun checkForUpdate() {
        val app = application as ByteLogApplication
        app.applicationScope.launch {
            val settings = app.settingsRepository
            if (!settings.autoCheckUpdates.first()) return@launch
            if (app.appUpdater.checkIfDue(settings.lastUpdateCheck.first())) settings.saveLastUpdateCheck(System.currentTimeMillis())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ System Splash Screen hook. It stays up until the saved theme is known, so the app never
        // draws a frame in the phone's light/dark mode when the user chose the other one.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val appearance = (application as ByteLogApplication).settingsRepository
        if (lastAppearance == null) {
            splash.setKeepOnScreenCondition { lastAppearance == null }
            (application as ByteLogApplication).applicationScope.launch {
                lastAppearance = Appearance(appearance.themeMode.first(), appearance.useDeviceColors.first(), appearance.reduceTransparency.first(), appearance.uiSize.first())
            }
        }
        enableEdgeToEdge()

        val app = application as ByteLogApplication
        val foodRepo = app.foodRepository
        val settingsRepo = app.settingsRepository
        if (savedInstanceState == null) {
            readSharedText(intent)
            readWidgetDestination(intent)
        }

        setContent {
            // Start from the last known appearance (kept across activity re-creation), never from a guess: a
            // screen rebuilt after rotation or a return from the background would otherwise flash, or stay, in
            // the phone's light/dark mode instead of the one chosen in Settings.
            val known = lastAppearance ?: Appearance()
            val themeMode by settingsRepo.themeMode.collectAsState(initial = known.themeMode)
            val deviceColors by settingsRepo.useDeviceColors.collectAsState(initial = known.deviceColors)
            val reduceTransparency by settingsRepo.reduceTransparency.collectAsState(initial = known.reduceTransparency)
            val uiSize by settingsRepo.uiSize.collectAsState(initial = known.uiSize)
            androidx.compose.runtime.SideEffect { lastAppearance = Appearance(themeMode, deviceColors, reduceTransparency, uiSize) }
            val dark = when (themeMode) {
                com.bytelog.app.ui.theme.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                com.bytelog.app.ui.theme.ThemeMode.LIGHT -> false
                com.bytelog.app.ui.theme.ThemeMode.DARK -> true
            }
            // The window behind the app follows too (its XML theme is light), so nothing light shows through
            // the see-through pages or during transitions.
            val windowColor = if (dark) android.graphics.Color.BLACK else android.graphics.Color.rgb(0xF2, 0xF3, 0xF5)
            androidx.compose.runtime.SideEffect { window.decorView.setBackgroundColor(windowColor) }
            ByteLogTheme(darkTheme = dark, dynamicColor = deviceColors, reduceTransparency = reduceTransparency) {
            // The chosen size scales everything drawn below (and the phone's own font size still applies on top)
            com.bytelog.app.ui.theme.UiScale(uiSize) {
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
                            onSharedTextConsumed = { sharedText.value = null },
                            widgetDestination = widgetDestination.value,
                            onWidgetDestinationConsumed = { widgetDestination.value = null }
                        )
                    }
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
    onSharedTextConsumed: () -> Unit = {},
    widgetDestination: String? = null,
    onWidgetDestinationConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    // A widget's "share this meal with family" tap: the Diary opens the share sheet for that meal
    var shareRequest by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(widgetDestination) {
        val dest = com.bytelog.app.widget.WidgetLinks.parse(widgetDestination) ?: return@LaunchedEffect
        onWidgetDestinationConsumed()
        val date = java.time.LocalDate.now().toString()
        suspend fun mealRoute(base: String, meal: String) {
            val types = foodRepo.getAllMealTypes().first()
            val type = types.firstOrNull { it.name.trim().equals(meal, ignoreCase = true) } ?: types.firstOrNull()
            val id = type?.id ?: meal.lowercase()
            val name = type?.name ?: meal
            navController.navigate("$base/${android.net.Uri.encode(id)}/${android.net.Uri.encode(name)}/$date") { launchSingleTop = true }
        }
        when (dest) {
            com.bytelog.app.widget.WidgetDestination.Diary -> navController.popBackStack("diary", false)
            com.bytelog.app.widget.WidgetDestination.Family -> navController.navigate("family") { launchSingleTop = true }
            is com.bytelog.app.widget.WidgetDestination.Search -> mealRoute("search", dest.meal)
            is com.bytelog.app.widget.WidgetDestination.Scan -> mealRoute("barcode", dest.meal)
            is com.bytelog.app.widget.WidgetDestination.Share -> { shareRequest = dest.meal; navController.popBackStack("diary", false) }
        }
    }
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
    val familyRepo = app.familyRepository
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
        page("diary") {
            val diaryViewModel: DiaryViewModel = viewModel {
                DiaryViewModel(foodRepo, app.waterRepository, settingsRepo, app.weightRepository, app.mealTemplateRepository, app.sparkyApiClient, app.planRepository, app.priceRepository)
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
                onNavigateToPhoto = { mealId, mealName, date ->
                    navController.navigate("search/$mealId/$mealName/$date?photo=true")
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
                onOpenFamilyMember = { id -> navController.navigate("family/${android.net.Uri.encode(id)}") { launchSingleTop = true } },
                openShareFor = shareRequest,
                onShareRequestHandled = { shareRequest = null },
                onOpenShoppingList = { navController.navigate("shopping") { launchSingleTop = true } },
                onOpenPlanner = { navController.navigate("planner") { launchSingleTop = true } },
                onScanReceipt = { navController.navigate("receipt") { launchSingleTop = true } },
                onBestValue = { navController.navigate("value") { launchSingleTop = true } },
                fastingRepository = app.fastingRepository,
                caffeineRepository = app.caffeineRepository,
                entryPhotos = app.entryPhotos,
                onOpenFasting = { navController.navigate("fasting") { launchSingleTop = true } },
                onOpenCaffeine = { navController.navigate("caffeine") { launchSingleTop = true } },
                onOpenReports = { navController.navigate("reports") { launchSingleTop = true } },
                onOpenProgressPhotos = { navController.navigate("progress_photos") { launchSingleTop = true } },
                onOpenMealie = { navController.navigate("mealie") { launchSingleTop = true } },
                onOpenHealthConnect = { navController.navigate("health_connect_detail") { launchSingleTop = true } },
                updateBanner = { modifier -> com.bytelog.app.ui.components.UpdateBanner(app.appUpdater, settingsRepo, modifier) },
                progressPhotos = app.progressPhotoRepository.photos,
                loadPhoto = { item -> app.progressPhotoRepository.loadBitmap(item, maxDim = 320) },
                mealieHost = remember {
                    app.mealieRepository.config.map { c: com.bytelog.app.data.mealie.MealieConfig -> c.baseUrl.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it).host ?: it } }
                },
                healthSummary = remember {
                    kotlinx.coroutines.flow.combine(settingsRepo.healthConnectWrite, app.healthConnectSync.status) { on, st ->
                        com.bytelog.app.ui.dashboard.HealthSummary(on, st.lastRun, st.written, st.leftToSparky, st.error)
                    }
                }
            )
        }

        // 1d. The meal planner: the days ahead, everything planned editable
        page("planner") {
            val vm: com.bytelog.app.ui.viewmodel.MealPlannerViewModel = viewModel {
                com.bytelog.app.ui.viewmodel.MealPlannerViewModel(app.planRepository, foodRepo, settingsRepo, prices = app.priceRepository)
            }
            com.bytelog.app.ui.screens.MealPlannerScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onAddFood = { date, mealId, mealName -> navController.navigate("search/$mealId/$mealName/$date?plan=true") },
                onShoppingList = { navController.navigate("shopping") { launchSingleTop = true } },
                onAskChat = { navController.navigate(NavMotion.CHAT) { launchSingleTop = true } }
            )
        }

        // 1c. What to buy for the planned food
        page("shopping") {
            val vm: com.bytelog.app.ui.viewmodel.ShoppingListViewModel = viewModel {
                com.bytelog.app.ui.viewmodel.ShoppingListViewModel(app.planRepository, app.priceRepository, foodRepo)
            }
            com.bytelog.app.ui.screens.ShoppingListScreen(
                viewModel = vm, onBack = { navController.popBackStack() },
                onScanReceipt = { navController.navigate("receipt") { launchSingleTop = true } },
                onBestValue = { navController.navigate("value") { launchSingleTop = true } }
            )
        }

        // 1f. Bang for your buck: priced foods ranked by a nutrient per dollar
        page("value") {
            val vm: com.bytelog.app.ui.viewmodel.BestValueViewModel = viewModel {
                com.bytelog.app.ui.viewmodel.BestValueViewModel(app.priceRepository, foodRepo)
            }
            com.bytelog.app.ui.screens.BestValueScreen(
                viewModel = vm, onBack = { navController.popBackStack() },
                onOpenFood = { id -> navController.navigate("food/${android.net.Uri.encode(id)}") { launchSingleTop = true } },
                onScanReceipt = { navController.navigate("receipt") { launchSingleTop = true } }
            )
        }

        // 1e. Prices from a receipt, read on the phone and matched to the shopping list and foods
        page("receipt") {
            val vm: com.bytelog.app.ui.viewmodel.ReceiptViewModel = viewModel {
                com.bytelog.app.ui.viewmodel.ReceiptViewModel(
                    com.bytelog.app.data.receipts.MlKitReceiptReader(app), app.priceRepository, app.planRepository, foodRepo
                )
            }
            com.bytelog.app.ui.screens.ReceiptScreen(
                viewModel = vm, onBack = { navController.popBackStack() },
                onShoppingList = {
                    if (!navController.popBackStack("shopping", inclusive = false)) {
                        navController.popBackStack()
                        navController.navigate("shopping") { launchSingleTop = true }
                    }
                }
            )
        }

        // 1b. Copy recent meals into the day open in the Diary (shares the Diary's view model)
        page(NavMotion.RECENT_MEALS) { entry ->
            val diaryEntry = remember(entry) { navController.getBackStackEntry("diary") }
            val diaryViewModel: DiaryViewModel = viewModel(diaryEntry) {
                DiaryViewModel(foodRepo, app.waterRepository, settingsRepo, app.weightRepository, app.mealTemplateRepository, app.sparkyApiClient, app.planRepository, app.priceRepository)
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
        page(
            route = com.bytelog.app.ui.navigation.SEARCH_ROUTE,
            arguments = listOf(
                navArgument("mealTypeId") { type = NavType.StringType },
                navArgument("mealName") { type = NavType.StringType },
                navArgument("date") { type = NavType.StringType },
                navArgument("photo") { type = NavType.BoolType; defaultValue = false },
                // From the meal planner: plan on the day (even today) instead of logging
                navArgument("plan") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val startWithPhoto = backStackEntry.arguments?.getBoolean("photo") ?: false
            val planOnly = backStackEntry.arguments?.getBoolean("plan") ?: false
            val mealTypeId = backStackEntry.arguments?.getString("mealTypeId") ?: "breakfast"
            val mealName = backStackEntry.arguments?.getString("mealName") ?: "Breakfast"
            val date = backStackEntry.arguments?.getString("date") ?: ""

            val fastSearchViewModel: FastSearchViewModel = viewModel {
                FastSearchViewModel(foodRepo, app.mealTemplateRepository.templates, app.planRepository, app.sparkyExternalProviders).apply {
                    initContext(mealTypeId, mealName, date)
                    if (planOnly && date.isNotBlank()) setPlanDays(setOf(date))
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
                onEditFood = { id -> navController.navigate("food/${android.net.Uri.encode(id)}") { launchSingleTop = true } },
                startWithPhoto = startWithPhoto
            )
        }

        // 3. Continuous Burst Barcode Scanner
        page(
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

            // The scanner adds to the same tray as the search it was opened from, so scanning and searching mix freely
            // (opened on its own, e.g. from a widget, it has a tray of its own)
            val fastSearchViewModel: FastSearchViewModel = viewModel(viewModelStoreOwner = com.bytelog.app.ui.navigation.rememberTrayOwner(navController, backStackEntry)) {
                FastSearchViewModel(foodRepo, app.mealTemplateRepository.templates, app.planRepository, app.sparkyExternalProviders).apply {
                    initContext(mealTypeId, mealName, date)
                }
            }

            BarcodeScannerScreen(
                viewModel = fastSearchViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // 4. Settings Screen
        page("settings") {
            SettingsScreen(
                settingsRepository = settingsRepo,
                foodRepository = foodRepo,
                waterRepository = app.waterRepository,
                weightRepository = app.weightRepository,
                mealTemplateRepository = app.mealTemplateRepository,
                onNavigateBack = { navController.popBackStack() },
                onOpenMyFoods = { navController.navigate("my_foods") { launchSingleTop = true } },
                onOpenFamily = { navController.navigate("family") { launchSingleTop = true } },
                onOpenMealie = { navController.navigate("mealie") { launchSingleTop = true } },
                onOpenHealthConnectDetail = { navController.navigate("health_connect_detail") { launchSingleTop = true } },
                onOpenReports = { navController.navigate("reports") { launchSingleTop = true } },
                onOpenProgressPhotos = { navController.navigate("progress_photos") { launchSingleTop = true } },
                onOpenFasting = { navController.navigate("fasting") { launchSingleTop = true } },
                onOpenCaffeine = { navController.navigate("caffeine") { launchSingleTop = true } },
                appUpdater = app.appUpdater
            )
        }

        // 4d. Family: everyone sharing their diary, and one person's diary
        page("family") {
            com.bytelog.app.ui.family.FamilyScreen(
                viewModel = familyViewModel,
                onBack = { navController.popBackStack() },
                onOpenMember = { id -> navController.navigate("family/${android.net.Uri.encode(id)}") { launchSingleTop = true } }
            )
        }
        page(
            route = "family/{userId}",
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { entry ->
            val userId = entry.arguments?.getString("userId").orEmpty()
            val vm: MemberViewModel = viewModel { MemberViewModel(familyRepo, foodRepo, userId) }
            com.bytelog.app.ui.family.MemberDiaryScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        // 4b. The user's own foods on Sparky
        page("my_foods") { entry ->
            val vm: MyFoodsViewModel = viewModel { MyFoodsViewModel(foodRepo) }
            val foodEdit by entry.savedStateHandle.getStateFlow<String?>(FOOD_EDIT_RESULT, null).collectAsState()
            LaunchedEffect(foodEdit) {
                if (foodEdit != null) { vm.refresh(); entry.savedStateHandle[FOOD_EDIT_RESULT] = null }
            }
            MyFoodsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenFood = { id -> navController.navigate("food/${android.net.Uri.encode(id)}") { launchSingleTop = true } },
                onBestValue = { navController.navigate("value") { launchSingleTop = true } }
            )
        }

        // 4c. Edit one food (saved to Sparky)
        page(
            route = "food/{foodId}",
            arguments = listOf(navArgument("foodId") { type = NavType.StringType })
        ) { entry ->
            val foodId = entry.arguments?.getString("foodId").orEmpty()
            val vm: FoodEditorViewModel = viewModel { FoodEditorViewModel(foodRepo, foodId, app.priceRepository) }
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
        page("import") {
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
        page(
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
        page(
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
        page(NavMotion.CHAT) {
            val chatViewModel: SparkyChatViewModel = viewModel {
                SparkyChatViewModel(app.chatRepository)
            }
            SparkyChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenShoppingList = { navController.navigate("shopping") { launchSingleTop = true } },
                onOpenPlanner = { navController.navigate("planner") { launchSingleTop = true } }
            )
        }

        // 9. Full Mealie Integration
        page("mealie") {
            val vm: com.bytelog.app.ui.mealie.MealieViewModel = viewModel {
                com.bytelog.app.ui.mealie.MealieViewModel(app.mealieRepository)
            }
            com.bytelog.app.ui.mealie.MealieScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenRecipe = { id -> navController.navigate("recipe/$id") { launchSingleTop = true } }
            )
        }

        // 10. Caffeine Tracking & Decay Curve
        page("caffeine") {
            val vm: com.bytelog.app.ui.caffeine.CaffeineViewModel = viewModel(
                factory = com.bytelog.app.ui.caffeine.CaffeineViewModel.factory(app.caffeineRepository)
            )
            com.bytelog.app.ui.caffeine.CaffeineScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        // 11. Progress Photos (Gallery, Side-by-Side Compare, Time-Lapse)
        page("progress_photos") {
            val vm: com.bytelog.app.ui.photos.ProgressPhotoViewModel = viewModel(
                factory = com.bytelog.app.ui.photos.ProgressPhotoViewModel.factory(app.progressPhotoRepository, settingsRepo)
            )
            com.bytelog.app.ui.photos.ProgressPhotosScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onOpenCamera = { pose ->
                    navController.navigate("progress_photos_camera/${pose.apiValue}")
                }
            )
        }
        page(
            route = "progress_photos_camera/{pose}",
            arguments = listOf(navArgument("pose") { type = NavType.StringType })
        ) { entry ->
            val poseArg = entry.arguments?.getString("pose")
            val vm: com.bytelog.app.ui.photos.ProgressPhotoViewModel = viewModel(
                factory = com.bytelog.app.ui.photos.ProgressPhotoViewModel.factory(app.progressPhotoRepository, settingsRepo)
            )
            com.bytelog.app.ui.photos.ProgressPhotoCameraScreen(
                viewModel = vm,
                initialPose = com.bytelog.app.data.photos.PhotoPose.fromApiValue(poseArg) ?: com.bytelog.app.data.photos.PhotoPose.FRONT,
                onNavigateBack = { navController.popBackStack() },
                onPhotoSaved = { navController.popBackStack() }
            )
        }

        // 12. Active Fasting
        page("fasting") {
            val vm: com.bytelog.app.ui.fasting.FastingViewModel = viewModel(
                factory = com.bytelog.app.ui.fasting.FastingViewModel.Factory(app.fastingRepository)
            )
            com.bytelog.app.ui.fasting.FastingScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        // 13. Detailed Health Connect & Historical Backfill
        page("health_connect_detail") {
            com.bytelog.app.ui.health.HealthConnectDetailScreen(
                extendedSync = app.healthConnectExtendedSync,
                backfill = app.healthConnectBackfill,
                onBack = { navController.popBackStack() }
            )
        }

        // 14. Detailed & Customizable Reports
        page("reports") {
            val vm: com.bytelog.app.ui.reports.ReportsViewModel = viewModel(
                factory = com.bytelog.app.ui.reports.ReportsViewModel.Factory(app.reportsRepository, app.reportConfigStore)
            )
            com.bytelog.app.ui.reports.ReportsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/** The appearance settings that pick the theme. */
private data class Appearance(
    val themeMode: com.bytelog.app.ui.theme.ThemeMode = com.bytelog.app.ui.theme.ThemeMode.SYSTEM,
    val deviceColors: Boolean = true,
    val reduceTransparency: Boolean = false,
    val uiSize: com.bytelog.app.ui.theme.UiSize = com.bytelog.app.ui.theme.UiSize.DEFAULT
)

/** The last appearance the app drew with, kept for as long as the process lives. */
@Volatile private var lastAppearance: Appearance? = null
