package com.bytelog.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import com.bytelog.app.ui.theme.fluidBackdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.bytelog.app.ui.diary.daySwipe
import androidx.compose.ui.graphics.graphicsLayer
import com.bytelog.app.ui.components.homeItem
import com.bytelog.app.ui.components.popIn
import com.bytelog.app.ui.components.pressBounce
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.ui.components.BodyweightCard
import com.bytelog.app.ui.components.CopyMealDialog
import com.bytelog.app.ui.components.DailyMacroHeader
import com.bytelog.app.ui.components.DeleteMealConfirmDialog
import com.bytelog.app.ui.components.EditFoodEntrySheet
import com.bytelog.app.ui.components.GoalAdjustDialog
import com.bytelog.app.ui.components.HydrationCard
import com.bytelog.app.ui.components.HydrationSheet
import com.bytelog.app.ui.components.MealTemplatesSheet
import com.bytelog.app.ui.components.MoveCopyItemsDialog
import com.bytelog.app.ui.components.MoveCopyMode
import com.bytelog.app.ui.components.RecipeBuilderSheet
import com.bytelog.app.ui.components.SaveMealTemplateDialog
import com.bytelog.app.ui.components.WeightLogDialog
import com.bytelog.app.ui.components.WeightVsCaloriesCard
import com.bytelog.app.ui.theme.ByteCream
import com.bytelog.app.ui.theme.blurSource
import com.bytelog.app.ui.theme.glass
import androidx.compose.material.icons.filled.Tune
import com.bytelog.app.ui.theme.ByteCreamMuted
import com.bytelog.app.ui.theme.ByteInkDark
import com.bytelog.app.ui.theme.ByteSurfaceDark
import com.bytelog.app.ui.theme.ByteSurfaceVariantDark
import com.bytelog.app.ui.theme.ByteTomato
import com.bytelog.app.ui.theme.ByteOnTomato
import com.bytelog.app.ui.theme.MacroCarbs
import com.bytelog.app.ui.theme.MacroFat
import com.bytelog.app.ui.theme.MacroProtein
import com.bytelog.app.ui.viewmodel.DiaryViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    viewModel: DiaryViewModel,
    recipesViewModel: com.bytelog.app.ui.viewmodel.RecipesViewModel,
    onOpenRecipe: (templateId: String) -> Unit,
    onImportRecipe: () -> Unit,
    onNavigateToSearch: (mealTypeId: String, mealName: String, date: String) -> Unit,
    onNavigateToBarcode: (mealTypeId: String, mealName: String, date: String) -> Unit,
    onNavigateToPhoto: (mealTypeId: String, mealName: String, date: String) -> Unit = { _, _, _ -> },
    onNavigateToChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenRecentMeals: () -> Unit,
    familyViewModel: com.bytelog.app.ui.viewmodel.FamilyViewModel? = null,
    onOpenFamily: () -> Unit = {},
    onOpenFamilyMember: (String) -> Unit = {},
    openShareFor: String? = null,
    onShareRequestHandled: () -> Unit = {},
    /** Opens the shopping list for planned food. */
    onOpenShoppingList: () -> Unit = {},
    /** Opens the meal planner. */
    onOpenPlanner: () -> Unit = {},
    /** Prices from a receipt. */
    onScanReceipt: () -> Unit = {},
    /** Foods ranked by what a dollar buys. */
    onBestValue: () -> Unit = {},
    fastingRepository: com.bytelog.app.data.fasting.FastingRepository? = null,
    caffeineRepository: com.bytelog.app.data.caffeine.CaffeineRepository? = null,
    entryPhotos: com.bytelog.app.data.remote.SparkyEntryPhotos? = null,
    /** Shown at the top when a new version of the app is available. */
    updateBanner: (@Composable (Modifier) -> Unit)? = null,
    onOpenFasting: () -> Unit = {},
    onOpenCaffeine: () -> Unit = {},
    onOpenReports: () -> Unit = {},
    onOpenProgressPhotos: () -> Unit = {},
    onOpenMealie: () -> Unit = {},
    onOpenHealthConnect: () -> Unit = {}
) {
    var copyPlanFrom by remember { mutableStateOf<List<com.bytelog.app.data.local.model.PlannedEntry>?>(null) }
    val family by (familyViewModel?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(com.bytelog.app.ui.viewmodel.FamilyState(loading = false)) }).collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val dailySummary by viewModel.dailySummary.collectAsState()
    val entries by viewModel.entriesForDate.collectAsState()
    val mealTypes by viewModel.mealTypes.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()

    val waterTotalOz by viewModel.waterTotalOz.collectAsState()
    val waterGoalOz by viewModel.targetWaterOz.collectAsState()
    val defaultContainerOz by viewModel.defaultContainerOz.collectAsState()
    val defaultContainerName by viewModel.defaultContainerName.collectAsState()
    val waterLogs by viewModel.waterLogsForDate.collectAsState()
    val waterContainers by viewModel.waterContainers.collectAsState()

    val currentWeight by viewModel.currentWeight.collectAsState()
    val previousWeight by viewModel.previousWeight.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val mealTemplates by viewModel.mealTemplates.collectAsState()
    val availableFoods by viewModel.availableFoods.collectAsState()

    val chartPoints by viewModel.weightVsCaloriesChartData.collectAsState()
    val adaptiveCalc by viewModel.adaptiveTdeeCalculation.collectAsState()
    val selectedChartDays by viewModel.selectedChartRangeDays.collectAsState()
    val autoAdjustEnabled by viewModel.autoAdjustCalories.collectAsState()
    val calorieGoalMode by viewModel.calorieGoalMode.collectAsState()
    val calorieGoalOffset by viewModel.calorieGoalOffset.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    copyPlanFrom?.let { from ->
        val fromDate = from.firstOrNull()?.entryDate
        com.bytelog.app.ui.components.PlanDaysDialog(
            title = "Copy this plan to…",
            note = "${from.size} planned food${if (from.size == 1) "" else "s"}, same meals and amounts, go on each day you pick.",
            initial = emptySet(),
            exclude = fromDate,
            confirmLabel = { n -> "Copy to $n day${if (n == 1) "" else "s"}" },
            onConfirm = { days ->
                copyPlanFrom = null
                if (days.isNotEmpty()) viewModel.copyPlanned(from, days) { n ->
                    scope.launch { snackbarHostState.showSnackbar("Copied $n food${if (n == 1) "" else "s"} to ${days.size} day${if (days.size == 1) "" else "s"}") }
                }
            },
            onDismiss = { copyPlanFrom = null }
        )
    }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        viewModel.syncMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(Unit) {
        recipesViewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // "Logged 3 to Lunch · Undo" after coming back from search, so a mis-tap is one tap to take back
    val pendingUndo by viewModel.pendingUndo.collectAsState()
    LaunchedEffect(pendingUndo) {
        val batch = pendingUndo ?: return@LaunchedEffect
        viewModel.consumePendingUndo(batch)
        snackbarHostState.currentSnackbarData?.dismiss()
        val r = snackbarHostState.showSnackbar(batch.label, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) viewModel.undoLogged(batch.entries)
    }
    val showLogged: (com.bytelog.app.data.repository.LoggedBatch) -> Unit = { batch ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val r = snackbarHostState.showSnackbar(batch.label, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) viewModel.undoLogged(batch.entries)
        }
    }
    val mealShortcuts by viewModel.mealShortcuts.collectAsState()
    val whatFits by viewModel.whatFits.collectAsState()
    val weeklyReview by viewModel.weeklyReview.collectAsState()
    val weekPlan by viewModel.weekPlan.collectAsState()
    val weekShopping by viewModel.weekShopping.collectAsState()
    val plannedEntries by viewModel.plannedEntries.collectAsState()
    val homeLayout by viewModel.homeLayout.collectAsState()
    val fabMenuLayout by viewModel.fabMenuLayout.collectAsState()

    // Fasting timer: time since food was last eaten, shown on today's diary once it's over an hour
    val lastMealAt by viewModel.lastMealAt.collectAsState()
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
    val fastText = lastMealAt?.let { clock - it }?.takeIf { selectedDate == LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) && it >= com.bytelog.app.data.repository.Fasting.SHOW_AFTER_MS }
        ?.let { "Fasting " + com.bytelog.app.data.repository.Fasting.format(it) }
    val waterSizes = remember(waterContainers, defaultContainerOz, defaultContainerName) {
        val out = LinkedHashMap<String, Float>()
        waterContainers.forEach { c -> if (c.volumeOz >= 1f && kotlin.math.abs(c.volumeOz - defaultContainerOz) >= 0.5f) out.putIfAbsent(c.name, c.volumeOz) }
        listOf("Small glass" to 8f, "Glass" to 12f, "Pint" to 16f, "Large bottle" to 24f, "Quart" to 32f).forEach { (name, oz) ->
            if (kotlin.math.abs(oz - defaultContainerOz) >= 0.5f && out.values.none { kotlin.math.abs(it - oz) < 0.5f }) out[name] = oz
        }
        out["2 × $defaultContainerName"] = defaultContainerOz * 2f
        out.entries.sortedBy { it.value }.map { it.key to it.value }
    }

    val activeBatches by recipesViewModel.activeBatches.collectAsState()
    val recipeViews by recipesViewModel.recipes.collectAsState()
    var openBatch by remember { mutableStateOf<com.bytelog.app.data.repository.BatchStatus?>(null) }
    // Not remembered: the right meal changes with the time of day while the app stays open.
    val quickLogMeal = com.bytelog.app.ui.viewmodel.RecipesViewModel.mealForNow(mealTypes)

    val isToday = remember(selectedDate) {
        selectedDate == LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }

    data class DisplayMealGroup(
        val mealTypeId: String,
        val mealName: String,
        val calories: Float,
        val entries: List<FoodEntry>
    )

    data class MoveCopyTarget(
        val mode: MoveCopyMode,
        val title: String,
        val subtitle: String? = null,
        val defaultMealName: String,
        val entries: List<FoodEntry>,
        val isEntireMeal: Boolean = false,
        val sourceMealName: String = defaultMealName
    )

    var editingEntry by remember { mutableStateOf<FoodEntry?>(null) }
    var activeMoveCopy by remember { mutableStateOf<MoveCopyTarget?>(null) }
    var mealToClear by remember { mutableStateOf<DisplayMealGroup?>(null) }
    var showHydrationSheet by remember { mutableStateOf(false) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var showHomeLayout by remember { mutableStateOf(false) }
    var showGoalAdjustDialog by remember { mutableStateOf(false) }
    var copyingMealName by remember { mutableStateOf<String?>(null) }
    var savingTemplateMealName by remember { mutableStateOf<String?>(null) }
    var showTemplatesSheet by remember { mutableStateOf(false) }
    var templateTargetMeal by remember { mutableStateOf<DisplayMealGroup?>(null) }
    var shareTarget by remember { mutableStateOf<DisplayMealGroup?>(null) }
    var showRecipeBuilderSheet by remember { mutableStateOf(false) }

    // Changing day: the new day's cards slide in from the side you moved towards and settle with a bounce.
    val reduceMotion = com.bytelog.app.ui.theme.LocalReduceMotion.current
    val dayShift = remember { androidx.compose.animation.core.Animatable(0f) }
    var shownDate by remember { mutableStateOf(selectedDate) }
    LaunchedEffect(selectedDate) {
        if (selectedDate != shownDate) {
            val direction = if (selectedDate > shownDate) 1f else -1f   // ISO dates sort as text
            shownDate = selectedDate
            if (!reduceMotion) {
                dayShift.snapTo(direction)
                dayShift.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 380f))
            }
        }
    }
    // Swiping sideways between days; the cards follow the finger before the day changes.
    val daySwipe = remember { com.bytelog.app.ui.diary.DaySwipeState() }
    val dayMotion = Modifier.graphicsLayer {
        val d = dayShift.value
        translationX = d * 64.dp.toPx() + daySwipe.offset.value
        alpha = 1f - kotlin.math.abs(d).coerceAtMost(1f) * 0.7f -
            (kotlin.math.abs(daySwipe.offset.value) / size.width.coerceAtLeast(1f)).coerceAtMost(0.4f)
    }
    val toolbarScroll = remember { com.bytelog.app.ui.diary.ToolbarScrollState() }
    var fabMenuOpen by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = fabMenuOpen) { fabMenuOpen = false }
    val allWeights by viewModel.allWeights.collectAsState()
    val recentWeights = remember(allWeights, selectedDate, weightUnit) {
        allWeights.filter { it.entryDate <= selectedDate }.sortedBy { it.entryDate }.takeLast(7)
            .map { com.bytelog.app.ui.components.displayWeight(it, weightUnit) }
    }
    val dayDate = remember(selectedDate) { runCatching { LocalDate.parse(selectedDate) }.getOrElse { LocalDate.now() } }
    val heroShape = com.bytelog.app.ui.diary.BlobShape.forDay(dayDate.toEpochDay())
    val weightShape = com.bytelog.app.ui.diary.BlobShape.CIRCLE

    val headerState = com.bytelog.app.ui.components.rememberHeaderCollapseState()
    val headerFraction = com.bytelog.app.ui.components.rememberHeaderFraction(headerState)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The title and the header cards sit above the list; a drag that starts on them scrolls the list too (folding
    // the header first, like a drag on the list), so the home screen scrolls wherever you touch it.
    val scrollsList = Modifier
        .nestedScroll(toolbarScroll.connection)
        .nestedScroll(headerState.connection)
        .scrollable(listState, androidx.compose.foundation.gestures.Orientation.Vertical, reverseDirection = true)
    val poppedKeys = remember { mutableSetOf<Any>() }

    val templatesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recipeBuilderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val loggedMealGroups: List<DisplayMealGroup> = remember(entries, mealTypes) {
        if (entries.isEmpty()) return@remember emptyList()

        val grouped = entries.groupBy { it.mealName.trim().lowercase() }
        grouped.map { (lowerMealKey, mealEntries) ->
            val matchedMealType = mealTypes.find {
                it.name.trim().equals(lowerMealKey, ignoreCase = true) ||
                it.id.equals(mealEntries.first().mealTypeId, ignoreCase = true)
            }
            val displayName = matchedMealType?.name?.trim()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                ?: mealEntries.first().mealName.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val mealId = matchedMealType?.id ?: mealEntries.first().mealTypeId

            DisplayMealGroup(
                mealTypeId = mealId,
                mealName = displayName,
                calories = mealEntries.sumOf { it.calories.toDouble() }.toFloat(),
                entries = mealEntries
            )
        }.sortedBy { group ->
            val sortIdx = mealTypes.indexOfFirst { it.name.trim().equals(group.mealName, ignoreCase = true) }
            if (sortIdx >= 0) sortIdx else 999
        }
    }

    Scaffold(
        modifier = Modifier.fluidBackdrop(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { paddingValues ->
      // The diary is the backdrop the floating toolbar and add button blur
      val haze = remember { dev.chrisbanes.haze.HazeState() }
      Box(Modifier.fillMaxSize().padding(paddingValues)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(Modifier.blurSource(haze))
                .padding(horizontal = 16.dp)
        ) {
            // The day, small, so the cards start near the top. Settings, Family and Sparky are in the + menu.
            Box(scrollsList) {
                com.bytelog.app.ui.diary.CompactDayLine(
                    date = selectedDate,
                    title = describeDay(selectedDate),
                    subtitle = dayDate.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                    badge = fastText?.let { t -> { com.bytelog.app.ui.diary.FastingPill(t) } }
                )
            }
            updateBanner?.invoke(Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))

          // Header and list: swipe sideways here to change day
          Column(
              Modifier
                  .fillMaxSize()
                  .daySwipe(daySwipe, enabled = !fabMenuOpen) { direction ->
                      haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                      viewModel.changeDay(direction.toLong())
                  }
          ) {
            // Calories, water and weight: full cards at the top, two narrow strips once the list scrolls
            com.bytelog.app.ui.diary.ExpressiveDiaryHeader(
                modifier = dayMotion.then(scrollsList),
                fraction = headerFraction,
                hero = {
                    com.bytelog.app.ui.diary.CalorieHero(
                        eaten = dailySummary.totalCalories.roundToInt(),
                        goal = dailySummary.targetCalories.roundToInt(),
                        progress = dailySummary.calorieProgress,
                        shape = heroShape,
                        fraction = headerFraction,
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    )
                },
                heroExpanded = { com.bytelog.app.ui.diary.HeroMacros(dailySummary) },
                heroCompact = { com.bytelog.app.ui.diary.HeroStrip(dailySummary) },
                waterExpanded = {
                    com.bytelog.app.ui.diary.WaterExpanded(
                        currentOz = waterTotalOz,
                        targetOz = waterGoalOz,
                        defaultOz = defaultContainerOz,
                        containerName = defaultContainerName,
                        onLogDefault = { viewModel.logDefaultDrink() },
                        onLogDouble = {
                            viewModel.quickAddWaterOz(amountOz = defaultContainerOz * 2f, containerName = "${defaultContainerName} x2")
                        },
                        onCustom = { showHydrationSheet = true },
                        onUndo = { viewModel.undoLastDrink() },
                        sizes = waterSizes,
                        onLogAmount = { oz, label -> viewModel.quickAddWaterOz(amountOz = oz, containerName = label) }
                    )
                },
                waterCompact = {
                    com.bytelog.app.ui.diary.WaterPill(
                        currentOz = waterTotalOz,
                        targetOz = waterGoalOz,
                        defaultOz = defaultContainerOz,
                        onLogDefault = { viewModel.logDefaultDrink() },
                        sizes = waterSizes,
                        onLogAmount = { oz, label -> viewModel.quickAddWaterOz(amountOz = oz, containerName = label) },
                        onCustom = { showHydrationSheet = true }
                    )
                },
                weightExpanded = {
                    com.bytelog.app.ui.diary.WeightExpanded(
                        weight = currentWeight?.let { com.bytelog.app.ui.components.displayWeight(it, weightUnit) },
                        unit = weightUnit,
                        delta = com.bytelog.app.ui.components.weightChange(currentWeight, previousWeight, weightUnit),
                        recent = recentWeights,
                        shape = weightShape,
                        onOpen = { showWeightDialog = true }
                    )
                },
                weightCompact = {
                    com.bytelog.app.ui.diary.WeightPill(
                        weight = (currentWeight ?: previousWeight)?.let { com.bytelog.app.ui.components.displayWeight(it, weightUnit) },
                        unit = weightUnit,
                        delta = com.bytelog.app.ui.components.weightChange(currentWeight, previousWeight, weightUnit),
                        shape = weightShape
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Meal Categories List (Shows only logged meals, plus quick meal chips to add new ones)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(dayMotion)
                    .nestedScroll(toolbarScroll.connection)
                    .nestedScroll(headerState.connection),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                homeLayout.visible.forEach { section ->
                    when (section) {
                        com.bytelog.app.data.repository.HomeSection.WEEKLY_REVIEW -> {
                        weeklyReview?.let { review ->
                            item(key = "weekly_review") {
                              Box(Modifier.homeItem(this, listState, "weekly_review")) {
                                com.bytelog.app.ui.diary.WeeklyReviewCard(review, weightUnit, onDismiss = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.dismissWeeklyReview(review)
                                })
                              }
                            }
                        }
                        }
                        com.bytelog.app.data.repository.HomeSection.FASTING -> {
                            if (fastingRepository != null) {
                                item(key = "fasting") {
                                    val activeFast by fastingRepository.activeFast.collectAsState()
                                    val fastPrefs by fastingRepository.preferences.collectAsState()
                                    LaunchedEffect(fastingRepository) { fastingRepository.refresh() }
                                    Box(Modifier.homeItem(this, listState, "fasting")) {
                                        com.bytelog.app.ui.fasting.FastingCard(
                                            activeFast = activeFast,
                                            defaultProtocolCode = fastPrefs.defaultProtocol,
                                            onStartFast = {
                                                val protocol = com.bytelog.app.data.fasting.FastingProtocol.fromCode(fastPrefs.defaultProtocol)
                                                val now = java.time.Instant.now()
                                                val target = now.plusSeconds((fastPrefs.targetFastingHours * 3600f).toLong())
                                                scope.launch {
                                                    fastingRepository.startFast(
                                                        startTimeIso = now.toString(),
                                                        targetEndTimeIso = target.toString(),
                                                        fastingType = protocol.code
                                                    )
                                                }
                                            },
                                            onEndFast = {
                                                activeFast?.let { fast ->
                                                    scope.launch {
                                                        fastingRepository.endFast(
                                                            id = fast.id,
                                                            endTimeIso = java.time.Instant.now().toString(),
                                                            startTimeIso = fast.startTime,
                                                            targetEndTimeIso = fast.targetEndTime,
                                                            fastingType = fast.fastingType
                                                        )
                                                    }
                                                }
                                            },
                                            onOpenDetails = onOpenFasting
                                        )
                                    }
                                }
                            }
                        }
                        com.bytelog.app.data.repository.HomeSection.CAFFEINE -> {
                            if (caffeineRepository != null) {
                                item(key = "caffeine") {
                                    val caffeineStatus by caffeineRepository.status.collectAsState()
                                    LaunchedEffect(selectedDate) { caffeineRepository.refresh(selectedDate) }
                                    Box(Modifier.homeItem(this, listState, "caffeine")) {
                                        com.bytelog.app.ui.caffeine.CaffeineCard(
                                            status = caffeineStatus,
                                            onQuickLogCoffee = {
                                                val nowHHmm = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                                                val mealId = mealTypes.firstOrNull()?.id.orEmpty()
                                                scope.launch {
                                                    caffeineRepository.logCaffeine("Drip Coffee", 95f, nowHHmm, selectedDate, mealId)
                                                    snackbarHostState.showSnackbar("Logged Drip Coffee (95 mg caffeine)")
                                                }
                                            },
                                            onOpenDetail = onOpenCaffeine
                                        )
                                    }
                                }
                            }
                        }
                        com.bytelog.app.data.repository.HomeSection.WELLNESS_HUB -> {
                            item(key = "wellness_hub") {
                                Box(Modifier.homeItem(this, listState, "wellness_hub")) {
                                    androidx.compose.foundation.lazy.LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                                    ) {
                                        item {
                                            androidx.compose.material3.AssistChip(
                                                onClick = onOpenReports,
                                                label = { Text("Reports") }
                                            )
                                        }
                                        item {
                                            androidx.compose.material3.AssistChip(
                                                onClick = onOpenProgressPhotos,
                                                label = { Text("Progress Photos") }
                                            )
                                        }
                                        item {
                                            androidx.compose.material3.AssistChip(
                                                onClick = onOpenMealie,
                                                label = { Text("Mealie") }
                                            )
                                        }
                                        item {
                                            androidx.compose.material3.AssistChip(
                                                onClick = onOpenHealthConnect,
                                                label = { Text("Health Connect") }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        com.bytelog.app.data.repository.HomeSection.LEFTOVERS -> {
                        if (activeBatches.isNotEmpty()) {
                            item(key = "fridge") {
                              Box(Modifier.homeItem(this, listState, "fridge")) {
                                com.bytelog.app.ui.recipes.FridgeStrip(
                                    batches = activeBatches,
                                    targetMealName = quickLogMeal?.name ?: "your diary",
                                    onQuickLog = { status ->
                                        quickLogMeal?.let { meal ->
                                            recipesViewModel.logFromBatch(status, selectedDate, meal, portions = 1f)
                                        }
                                    },
                                    onOpen = { openBatch = it }
                                )
                              }
                            }
                        }
                        }
                        com.bytelog.app.data.repository.HomeSection.FAMILY -> {
                        if (family.members.isNotEmpty()) {
                            item(key = "family") {
                              Box(Modifier.homeItem(this, listState, "family")) {
                                com.bytelog.app.ui.family.FamilyStrip(family.members, family.today, onOpenFamilyMember, onOpenFamily)
                              }
                            }
                        }
                        }
                        com.bytelog.app.data.repository.HomeSection.PLANNED -> {
                            if (plannedEntries.isNotEmpty()) {
                                item(key = "planned") {
                                    com.bytelog.app.ui.diary.PlannedCard(
                                        planned = plannedEntries,
                                        loggedCalories = dailySummary.totalCalories,
                                        calorieGoal = dailySummary.targetCalories,
                                        ahead = com.bytelog.app.data.repository.PlanRepository.isFuture(selectedDate),
                                        onEat = { items ->
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.eatPlanned(items)
                                        },
                                        onRemove = { p ->
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            viewModel.removePlanned(listOf(p))
                                            scope.launch {
                                                val r = snackbarHostState.showSnackbar("Removed ${p.foodName} from the plan", actionLabel = "Undo")
                                                if (r == SnackbarResult.ActionPerformed) viewModel.restorePlanned(listOf(p))
                                            }
                                        },
                                        modifier = Modifier.homeItem(this, listState, "planned"),
                                        onCopyToDays = { copyPlanFrom = plannedEntries },
                                        onShoppingList = onOpenShoppingList,
                                        onOpenPlanner = onOpenPlanner
                                    )
                                }
                            }
                        }
                        com.bytelog.app.data.repository.HomeSection.MEAL_PLAN -> {
                            item(key = "meal_plan") {
                                com.bytelog.app.ui.diary.MealPlanHomeCard(
                                    days = weekPlan, calorieGoal = dailySummary.targetCalories, onOpen = onOpenPlanner,
                                    modifier = Modifier.homeItem(this, listState, "meal_plan")
                                )
                            }
                        }
                        com.bytelog.app.data.repository.HomeSection.SHOPPING -> {
                            item(key = "shopping") {
                                com.bytelog.app.ui.diary.ShoppingHomeCard(
                                    list = weekShopping.first, checked = weekShopping.second, onOpen = onOpenShoppingList, onScanReceipt = onScanReceipt, onBestValue = onBestValue,
                                    modifier = Modifier.homeItem(this, listState, "shopping")
                                )
                            }
                        }
                        com.bytelog.app.data.repository.HomeSection.MEALS -> {
                        if (loggedMealGroups.isEmpty()) {
                            item(key = "meals_empty") {
                                Column(
                                    modifier = Modifier
                                        .homeItem(this, listState, "meals_empty")
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(ByteSurfaceDark.copy(alpha = 0.5f))
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "No foods logged for this date",
                                        color = ByteCream,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Choose a meal below to start logging. Entries from Sparky Fitness sync automatically.",
                                        color = ByteCreamMuted,
                                        fontSize = 13.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(MacroProtein.copy(alpha = 0.15f))
                                                .clickable {
                                                    templateTargetMeal = null
                                                    showTemplatesSheet = true
                                                }
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = "Templates & Recipes",
                                                color = MacroProtein,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(ByteTomato.copy(alpha = 0.15f))
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onOpenRecentMeals()
                                                }
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = "Copy Recent Meals",
                                                color = ByteTomato,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            items(loggedMealGroups, key = { it.mealName }) { group ->
                              Box(Modifier.homeItem(this, listState, group.mealName).popIn(group.mealName, poppedKeys)) {
                                MealCard(
                                    mealName = group.mealName,
                                    mealCalories = group.calories,
                                    entries = group.entries,
                                    onAddFood = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onNavigateToSearch(group.mealTypeId, group.mealName, selectedDate)
                                    },
                                    onAddFoodLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onNavigateToBarcode(group.mealTypeId, group.mealName, selectedDate)
                                    },
                                    onHeaderDoubleClick = {
                                        viewModel.copyMealFromYesterday(group.mealName)
                                    },
                                    onCopyMeal = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeMoveCopy = MoveCopyTarget(
                                            mode = MoveCopyMode.COPY,
                                            title = "Meal: ${group.mealName}",
                                            subtitle = "${group.entries.size} items • ${group.calories.roundToInt()} kcal",
                                            defaultMealName = group.mealName,
                                            entries = group.entries,
                                            isEntireMeal = true,
                                            sourceMealName = group.mealName
                                        )
                                    },
                                    onMoveMeal = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeMoveCopy = MoveCopyTarget(
                                            mode = MoveCopyMode.MOVE,
                                            title = "Meal: ${group.mealName}",
                                            subtitle = "${group.entries.size} items • ${group.calories.roundToInt()} kcal",
                                            defaultMealName = group.mealName,
                                            entries = group.entries,
                                            isEntireMeal = true,
                                            sourceMealName = group.mealName
                                        )
                                    },
                                    onClearMeal = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        mealToClear = group
                                    },
                                    onSaveTemplate = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        savingTemplateMealName = group.mealName
                                    },
                                    onShare = if (family.members.any { it.canWriteDiary }) ({
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        shareTarget = group
                                    }) else null,
                                    onOpenTemplates = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        templateTargetMeal = group
                                        showTemplatesSheet = true
                                    },
                                    onEditEntry = { entry ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        editingEntry = entry
                                    },
                                    inlineEditor = { entry, onClose, onDelete ->
                                        InlineEntryEditor(
                                            entry = entry,
                                            viewModel = viewModel,
                                            onDelete = onDelete,
                                            onMore = { onClose(); editingEntry = entry },
                                            onDone = onClose
                                        )
                                    },
                                    onMoveEntry = { entry ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeMoveCopy = MoveCopyTarget(
                                            mode = MoveCopyMode.MOVE,
                                            title = entry.foodName,
                                            subtitle = "${entry.quantity.roundToInt()}${entry.unit} • ${entry.calories.roundToInt()} kcal",
                                            defaultMealName = entry.mealName,
                                            entries = listOf(entry),
                                            isEntireMeal = false,
                                            sourceMealName = entry.mealName
                                        )
                                    },
                                    onCopyEntry = { entry ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        activeMoveCopy = MoveCopyTarget(
                                            mode = MoveCopyMode.COPY,
                                            title = entry.foodName,
                                            subtitle = "${entry.quantity.roundToInt()}${entry.unit} • ${entry.calories.roundToInt()} kcal",
                                            defaultMealName = entry.mealName,
                                            entries = listOf(entry),
                                            isEntireMeal = false,
                                            sourceMealName = entry.mealName
                                        )
                                    },
                                    onDuplicateEntry = { entry ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.duplicateEntryToNow(entry)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Duplicated ${entry.foodName}")
                                        }
                                    },
                                    onQuickIncrementEntry = { entry ->
                                        viewModel.quickIncrementServing(entry)
                                    },
                                    onDeleteEntry = { entry ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.deleteEntry(entry)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Deleted ${entry.foodName}",
                                                actionLabel = "Undo"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                viewModel.duplicateEntryToNow(entry)
                                            }
                                        }
                                    },
                                    onBatchMove = { selectedEntries ->
                                        activeMoveCopy = MoveCopyTarget(
                                            mode = MoveCopyMode.MOVE,
                                            title = "${selectedEntries.size} Items",
                                            subtitle = "${selectedEntries.sumOf { it.calories.toDouble() }.roundToInt()} kcal from ${group.mealName}",
                                            defaultMealName = group.mealName,
                                            entries = selectedEntries,
                                            isEntireMeal = false,
                                            sourceMealName = group.mealName
                                        )
                                    },
                                    onBatchCopy = { selectedEntries ->
                                        activeMoveCopy = MoveCopyTarget(
                                            mode = MoveCopyMode.COPY,
                                            title = "${selectedEntries.size} Items",
                                            subtitle = "${selectedEntries.sumOf { it.calories.toDouble() }.roundToInt()} kcal from ${group.mealName}",
                                            defaultMealName = group.mealName,
                                            entries = selectedEntries,
                                            isEntireMeal = false,
                                            sourceMealName = group.mealName
                                        )
                                    },
                                    onBatchDelete = { selectedEntries ->
                                        viewModel.deleteEntries(selectedEntries) { deleted ->
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Deleted ${deleted.size} items from ${group.mealName}",
                                                    actionLabel = "Undo"
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    viewModel.restoreEntries(deleted)
                                                }
                                            }
                                        }
                                    }
                                )
                              }
                            }
                        }
                        }
                        com.bytelog.app.data.repository.HomeSection.WHAT_FITS -> {
                        if (whatFits.isNotEmpty()) {
                            item(key = "what_fits") {
                                val fitMeal = quickLogMeal ?: mealTypes.firstOrNull() ?: com.bytelog.app.data.local.model.MealType.DEFAULT_MEAL_TYPES.first()
                                val fitMealName = fitMeal.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                com.bytelog.app.ui.diary.WhatFitsCard(
                                    caloriesLeft = (dailySummary.targetCalories - dailySummary.totalCalories).roundToInt(),
                                    proteinLeft = (dailySummary.targetProtein - dailySummary.totalProtein).roundToInt(),
                                    options = whatFits,
                                    mealName = fitMealName,
                                    onLog = { option ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.logFit(option, fitMeal.id, fitMealName, showLogged)
                                    },
                                    modifier = Modifier.homeItem(this, listState, "what_fits")
                                )
                            }
                        }
                        }
                        com.bytelog.app.data.repository.HomeSection.QUICK_LOG -> {
                        // Quick Meal Selection Action Bar
                        item(key = "quick_meals") {
                            val candidateMeals = remember(mealTypes) {
                                (if (mealTypes.isNotEmpty()) mealTypes else com.bytelog.app.data.local.model.MealType.DEFAULT_MEAL_TYPES)
                                    .distinctBy { it.name.trim().lowercase() }
                            }
                            Column(
                                modifier = Modifier
                                    .homeItem(this, listState, "quick_meals")
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = if (loggedMealGroups.isEmpty()) "START LOGGING" else "LOG TO ANOTHER MEAL",
                                    color = ByteCreamMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )

                                // One tap logs a whole meal: what you had yesterday, or your usual
                                val loggedKeys = loggedMealGroups.map { com.bytelog.app.data.repository.MealPatterns.mealKey(it.mealName) }.toSet()
                                candidateMeals.forEach { mt ->
                                    val key = com.bytelog.app.data.repository.MealPatterns.mealKey(mt.name)
                                    val sc = mealShortcuts[key]
                                    if (sc != null && key !in loggedKeys) {
                                        val cleanName = mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                        MealShortcutRow(
                                            mealName = cleanName,
                                            shortcuts = sc,
                                            onLog = { source ->
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                viewModel.logShortcut(source, mt.id, cleanName, showLogged)
                                            }
                                        )
                                    }
                                }

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(MacroProtein.copy(alpha = 0.15f))
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    templateTargetMeal = null
                                                    showTemplatesSheet = true
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                         ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.RestaurantMenu,
                                                    contentDescription = null,
                                                    tint = MacroProtein,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Templates",
                                                    color = MacroProtein,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    items(candidateMeals, key = { it.id }) { mt ->
                                        val cleanName = mt.name.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(ByteSurfaceDark)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onNavigateToSearch(mt.id, cleanName, selectedDate)
                                                }
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = null,
                                                    tint = ByteTomato,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = cleanName,
                                                    color = ByteCream,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                        com.bytelog.app.data.repository.HomeSection.TRENDS -> {
                        // Interactive Weight vs Calories Over Time Chart & Adaptive TDEE
                        item(key = "weight_chart") {
                            WeightVsCaloriesCard(
                                modifier = Modifier.homeItem(this, listState, "weight_chart"),
                                chartPoints = chartPoints,
                                calculation = adaptiveCalc,
                                selectedDaysRange = selectedChartDays,
                                weightUnit = weightUnit,
                                onSelectDaysRange = { days ->
                                    viewModel.setChartRange(days)
                                },
                                onOpenGoalAdjuster = {
                                    showGoalAdjustDialog = true
                                }
                            )
                        }
                        }
                    }
                }

                // Rearrange the sections above
                item(key = "customize_home") {
                    Box(Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null), contentAlignment = Alignment.Center) {
                        Row(
                            Modifier
                                .pressBounce()
                                .glass(com.bytelog.app.ui.theme.GlassLevel.Thin, RoundedCornerShape(50))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showHomeLayout = true
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = ByteCreamMuted, modifier = Modifier.size(16.dp))
                            Text("Customize home", color = ByteCreamMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(112.dp))
                }
            }
          }
        }

        // Which day a sideways swipe would open
        com.bytelog.app.ui.diary.DaySwipeHint(
            state = daySwipe,
            previousLabel = describeDay(dayDate.minusDays(1).toString()),
            nextLabel = describeDay(dayDate.plusDays(1).toString())
        )

        // Floating day toolbar (hides while scrolling down) and the add menu
        androidx.compose.runtime.CompositionLocalProvider(com.bytelog.app.ui.theme.LocalHazeState provides haze) {
        com.bytelog.app.ui.diary.DayToolbar(
            hidden = toolbarScroll.hidden && !fabMenuOpen,
            dayLabel = describeDay(selectedDate),
            onPrev = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.changeDay(-1)
            },
            onToday = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.setDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            },
            onNext = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.changeDay(1)
            },
            onRecent = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenRecentMeals()
            },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 18.dp)
        )
        com.bytelog.app.ui.diary.DiaryFabMenu(
            expanded = fabMenuOpen,
            onExpandedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                fabMenuOpen = it
            },
            items = com.bytelog.app.ui.diary.diaryMenuItems(
                layout = fabMenuLayout,
                handlers = buildMap {
                    val meal = { quickLogMeal ?: mealTypes.firstOrNull() ?: com.bytelog.app.data.local.model.MealType.DEFAULT_MEAL_TYPES.first() }
                    put(com.bytelog.app.data.repository.FabAction.SEARCH) { meal().let { onNavigateToSearch(it.id, it.name, selectedDate) } }
                    put(com.bytelog.app.data.repository.FabAction.SCAN) { meal().let { onNavigateToBarcode(it.id, it.name, selectedDate) } }
                    put(com.bytelog.app.data.repository.FabAction.PHOTO) { meal().let { onNavigateToPhoto(it.id, it.name, selectedDate) } }
                    put(com.bytelog.app.data.repository.FabAction.IMPORT, onImportRecipe)
                    put(com.bytelog.app.data.repository.FabAction.ASK, onNavigateToChat)
                    put(com.bytelog.app.data.repository.FabAction.SETTINGS, onNavigateToSettings)
                    if (familyViewModel != null) put(com.bytelog.app.data.repository.FabAction.FAMILY, onOpenFamily)
                }
            )
        )
        }
      }
    }

    // Edit Food Entry Modal Bottom Sheet
    editingEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { editingEntry = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            EditFoodEntrySheet(
                entry = entry,
                availableMealTypes = mealTypes,
                entryPhotos = entryPhotos,
                onSave = { updatedEntry ->
                    viewModel.updateEntry(updatedEntry)
                    editingEntry = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Updated ${updatedEntry.foodName}")
                    }
                },
                onDelete = { entryToDelete ->
                    viewModel.deleteEntry(entryToDelete)
                    editingEntry = null
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Deleted ${entryToDelete.foodName}",
                            actionLabel = "Undo"
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.duplicateEntryToNow(entryToDelete)
                        }
                    }
                },
                onCopy = { entryToCopy ->
                    activeMoveCopy = MoveCopyTarget(
                        mode = MoveCopyMode.COPY,
                        title = entryToCopy.foodName,
                        subtitle = "${entryToCopy.quantity.roundToInt()}${entryToCopy.unit} • ${entryToCopy.calories.roundToInt()} kcal",
                        defaultMealName = entryToCopy.mealName,
                        entries = listOf(entryToCopy),
                        isEntireMeal = false,
                        sourceMealName = entryToCopy.mealName
                    )
                    editingEntry = null
                },
                onDismiss = { editingEntry = null }
            )
        }
    }

    // Move / Copy Dialog (For Meals, Single Items, or Batch Selected Items)
    activeMoveCopy?.let { target ->
        MoveCopyItemsDialog(
            initialMode = target.mode,
            title = target.title,
            subtitle = target.subtitle,
            sourceDate = selectedDate,
            defaultMealName = target.defaultMealName,
            mealTypes = mealTypes,
            onDismiss = { activeMoveCopy = null },
            onConfirm = { chosenMode, targetDate, targetTypeId, targetMealName ->
                if (chosenMode == MoveCopyMode.MOVE) {
                    if (target.isEntireMeal) {
                        viewModel.moveMeal(selectedDate, target.sourceMealName, targetDate, targetMealName)
                    } else {
                        viewModel.moveEntries(target.entries, targetDate, targetTypeId, targetMealName)
                    }
                } else {
                    if (target.isEntireMeal) {
                        viewModel.copyMeal(selectedDate, target.sourceMealName, targetDate, targetMealName)
                    } else {
                        viewModel.copyEntries(target.entries, targetDate, targetTypeId, targetMealName)
                    }
                }
                activeMoveCopy = null
            }
        )
    }

    // Delete / Clear Meal Confirmation Dialog
    mealToClear?.let { group ->
        DeleteMealConfirmDialog(
            mealName = group.mealName,
            itemCount = group.entries.size,
            calories = group.calories,
            onDismiss = { mealToClear = null },
            onConfirmDelete = {
                val groupEntries = group.entries
                viewModel.deleteMeal(selectedDate, group.mealName) {
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = "Cleared ${group.mealName} (${groupEntries.size} items)",
                            actionLabel = "Undo"
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.restoreEntries(groupEntries)
                        }
                    }
                }
                mealToClear = null
            }
        )
    }

    // Customize home: reorder and hide the sections
    if (showHomeLayout) {
        ModalBottomSheet(
            onDismissRequest = { showHomeLayout = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            com.bytelog.app.ui.diary.HomeLayoutEditor(homeLayout, onChange = { viewModel.saveHomeLayout(it) })
        }
    }

    // Hydration Modal Bottom Sheet
    if (showHydrationSheet) {
        ModalBottomSheet(
            onDismissRequest = { showHydrationSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ByteSurfaceDark
        ) {
            HydrationSheet(
                todayLogs = waterLogs,
                containers = waterContainers,
                currentTotalOz = waterTotalOz,
                targetOz = waterGoalOz,
                defaultContainerOz = defaultContainerOz,
                defaultContainerName = defaultContainerName,
                onLogWaterOz = { amount, containerId, containerName ->
                    viewModel.quickAddWaterOz(amount, containerId, containerName)
                },
                onSetDefaultContainer = { oz, name ->
                    viewModel.setDefaultContainer(oz, name)
                },
                onDeleteLog = { log ->
                    viewModel.deleteWaterLog(log)
                },
                onDismiss = { showHydrationSheet = false }
            )
        }
    }

    // Bodyweight Log Dialog
    if (showWeightDialog) {
        WeightLogDialog(
            initialWeight = currentWeight?.weight ?: previousWeight?.weight,
            currentUnit = weightUnit,
            dateString = selectedDate,
            onDismiss = { showWeightDialog = false },
            onSaveWeight = { weight, unit ->
                viewModel.logWeight(weight = weight, unit = unit)
                viewModel.saveWeightUnit(unit)
                showWeightDialog = false
            }
        )
    }

    // Adaptive Calorie & Macro Goal Adjust Dialog
    // A widget asked to share a meal: open the sheet for it once that meal shows up in the day
    LaunchedEffect(openShareFor, loggedMealGroups) {
        val wanted = openShareFor ?: return@LaunchedEffect
        val group = loggedMealGroups.firstOrNull { it.mealName.equals(wanted, ignoreCase = true) }
        if (group != null) {
            shareTarget = group
            onShareRequestHandled()
        } else {
            kotlinx.coroutines.delay(2500)   // the day may still be loading; if the meal never appears there's nothing to share
            onShareRequestHandled()
            snackbarHostState.showSnackbar("Log $wanted first, then share it with family")
        }
    }

    // Share a meal with family: split by percent or copy
    shareTarget?.let { group ->
        com.bytelog.app.ui.family.ShareMealSheet(
            mealName = group.mealName,
            entries = group.entries,
            members = family.members,
            onDismiss = { shareTarget = null },
            onConfirm = { shares, mode ->
                shareTarget = null
                familyViewModel?.share(group.entries, shares, mode) { outcomes ->
                    scope.launch {
                        val msg = if (outcomes.all { it.ok }) {
                            outcomes.joinToString("; ") { "${it.member?.displayName}: ${it.sent} item${if (it.sent == 1) "" else "s"}" }.let { "Shared ${group.mealName} — $it" }
                        } else {
                            outcomes.filter { !it.ok }.joinToString("; ") { "${it.member?.displayName}: ${it.error?.take(90)}" }.let { "Couldn't share with $it" }
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                }
            }
        )
    }

    if (showGoalAdjustDialog) {
        GoalAdjustDialog(
            currentTdee = adaptiveCalc.tdee,
            isAutoAdjustEnabled = autoAdjustEnabled,
            currentGoalMode = calorieGoalMode,
            currentGoalOffset = calorieGoalOffset,
            confidence = adaptiveCalc.confidence,
            onDismiss = { showGoalAdjustDialog = false },
            onSaveGoal = { enabled, mode, offset ->
                viewModel.saveGoalSettings(enabled, mode, offset)
                showGoalAdjustDialog = false
            }
        )
    }

    // Copy Meal Dialog (Per-meal copy)
    copyingMealName?.let { mealName ->
        CopyMealDialog(
            sourceMealName = mealName,
            sourceDate = selectedDate,
            mealTypes = mealTypes,
            onDismiss = { copyingMealName = null },
            onCopyMeal = { targetDate, targetMeal ->
                viewModel.copyMeal(
                    sourceDate = selectedDate,
                    sourceMeal = mealName,
                    targetDate = targetDate,
                    targetMeal = targetMeal
                )
                copyingMealName = null
            }
        )
    }

    // Save Meal Template Dialog
    savingTemplateMealName?.let { mealName ->
        SaveMealTemplateDialog(
            mealName = mealName,
            dateString = selectedDate,
            onDismiss = { savingTemplateMealName = null },
            onSaveTemplate = { tName, desc ->
                viewModel.saveMealAsTemplate(mealName, desc)
                savingTemplateMealName = null
            }
        )
    }

    openBatch?.let { opened ->
        // Show live numbers: the batch may change while the sheet is open.
        val status = activeBatches.firstOrNull { it.batch.id == opened.batch.id } ?: opened
        com.bytelog.app.ui.recipes.BatchSheet(
            status = status,
            mealTypes = mealTypes,
            defaultMeal = quickLogMeal,
            onLog = { meal, portions, grams ->
                recipesViewModel.logFromBatch(status, selectedDate, meal, portions, grams)
                openBatch = null
            },
            onMove = { location, days -> recipesViewModel.moveBatch(status.batch.id, location, days); openBatch = null },
            onDiscard = { recipesViewModel.discardBatch(status.batch.id); openBatch = null },
            onArchive = { recipesViewModel.archiveBatch(status.batch.id); openBatch = null },
            onDelete = { recipesViewModel.deleteBatch(status.batch.id); openBatch = null },
            onDismiss = { openBatch = null }
        )
    }

    // Meal Templates & Recipes Sheet
    if (showTemplatesSheet) {
        val targetName = templateTargetMeal?.mealName ?: mealTypes.firstOrNull()?.name ?: "Meal"
        val targetId = templateTargetMeal?.mealTypeId ?: mealTypes.firstOrNull()?.id
        MealTemplatesSheet(
            sheetState = templatesSheetState,
            templates = mealTemplates,
            targetMealName = targetName,
            onDismiss = { showTemplatesSheet = false },
            onLogTemplate = { template ->
                viewModel.logTemplateToDiary(template, targetName, targetId)
                showTemplatesSheet = false
            },
            onDeleteTemplate = { templateId ->
                viewModel.deleteTemplate(templateId)
            },
            onOpenRecipeBuilder = {
                showTemplatesSheet = false
                showRecipeBuilderSheet = true
            },
            perServing = remember(recipeViews) { recipeViews.associate { it.template.id to it.perServing } },
            onOpenRecipe = { id ->
                showTemplatesSheet = false
                onOpenRecipe(id)
            },
            onImportRecipe = {
                showTemplatesSheet = false
                onImportRecipe()
            }
        )
    }

    // Recipe Builder Sheet
    if (showRecipeBuilderSheet) {
        RecipeBuilderSheet(
            sheetState = recipeBuilderSheetState,
            searchFoods = recipesViewModel::searchFoods,
            onDismiss = { showRecipeBuilderSheet = false },
            onSaveRecipe = { built ->
                recipesViewModel.saveBuiltRecipe(built)
                showRecipeBuilderSheet = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MealCard(
    mealName: String,
    mealCalories: Float,
    entries: List<FoodEntry>,
    onAddFood: () -> Unit,
    onAddFoodLongClick: () -> Unit,
    onHeaderDoubleClick: () -> Unit,
    onCopyMeal: () -> Unit,
    onMoveMeal: () -> Unit,
    onClearMeal: () -> Unit,
    onSaveTemplate: () -> Unit,
    onOpenTemplates: () -> Unit,
    onShare: (() -> Unit)? = null,
    onEditEntry: (FoodEntry) -> Unit,
    onMoveEntry: (FoodEntry) -> Unit,
    onCopyEntry: (FoodEntry) -> Unit,
    onDuplicateEntry: (FoodEntry) -> Unit,
    onQuickIncrementEntry: (FoodEntry) -> Unit,
    onDeleteEntry: (FoodEntry) -> Unit,
    onBatchMove: (List<FoodEntry>) -> Unit,
    onBatchCopy: (List<FoodEntry>) -> Unit,
    onBatchDelete: (List<FoodEntry>) -> Unit,
    /** Amount editor opened under a food by tapping it: (entry, close, delete). Null opens [onEditEntry] instead. */
    inlineEditor: (@Composable (FoodEntry, () -> Unit, () -> Unit) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var isSelectionMode by remember { mutableStateOf(false) }
    // Keyed by when the entry was made, which survives Sparky giving it a new id
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) {
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    // Expressive segmented group: the header and each food are separate rounded pieces with small gaps.
    val hasBottom = entries.isNotEmpty()
    val segOuter = 24.dp
    val segInner = 6.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
      Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                if (hasBottom) RoundedCornerShape(topStart = segOuter, topEnd = segOuter, bottomStart = segInner, bottomEnd = segInner)
                else RoundedCornerShape(segOuter)
            )
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
      ) {
        if (isSelectionMode) {
            // Selection Mode Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ByteSurfaceVariantDark)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedIds = if (selectedIds.size == entries.size) {
                                    emptySet()
                                } else {
                                    entries.map { it.id }.toSet()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (selectedIds.size == entries.size) "Deselect All" else "Select All",
                            color = ByteCream,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "${selectedIds.size} / ${entries.size} selected",
                        color = ByteCreamMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ByteTomato.copy(alpha = 0.2f))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Done",
                        color = ByteTomato,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            // Meal Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = onCopyMeal,
                            onDoubleClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onHeaderDoubleClick()
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuExpanded = true
                            }
                        )
                ) {
                    Text(
                        text = mealName,
                        color = ByteCream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${mealCalories.roundToInt()} kcal",
                        color = ByteCreamMuted,
                        fontSize = 12.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (entries.isNotEmpty() && onShare != null) {
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = "Share $mealName with family",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (entries.isNotEmpty()) {
                        IconButton(
                            onClick = onSaveTemplate,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = "Save $mealName as Template",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onCopyMeal,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy $mealName",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenTemplates,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestaurantMenu,
                            contentDescription = "Templates & Recipes",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ByteSurfaceVariantDark)
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAddFood()
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAddFoodLongClick()
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add food to $mealName (Hold for Barcode)",
                            tint = ByteTomato,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 3-dots Menu for Full Meal Actions
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Meal Options",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(ByteSurfaceDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Move Meal...", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                        contentDescription = null,
                                        tint = ByteTomato,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onMoveMeal()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Copy Meal...", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = MacroProtein,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onCopyMeal()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Copy Yesterday's $mealName", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = ByteCreamMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onHeaderDoubleClick()
                                }
                            )

                            if (entries.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Select Items...", color = ByteCream) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.SelectAll,
                                            contentDescription = null,
                                            tint = ByteCreamMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        isSelectionMode = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Save as Template", color = ByteCream) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.BookmarkAdd,
                                            contentDescription = null,
                                            tint = ByteCreamMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onSaveTemplate()
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Clear Meal", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onClearMeal()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

      }

        // Entries List
        if (entries.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                entries.forEachIndexed { index, entry ->
                    val isLast = index == entries.lastIndex && !isSelectionMode
                    val entryKey = "${entry.createdAt}|${entry.foodName}"
                    val expanded = inlineEditor != null && !isSelectionMode && expandedKey == entryKey
                    key(entry.id) {
                      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SwipeableFoodEntryRow(
                            shape = if (isLast && !expanded) RoundedCornerShape(topStart = segInner, topEnd = segInner, bottomStart = segOuter, bottomEnd = segOuter)
                                else RoundedCornerShape(segInner),
                            entry = entry,
                            isSelectionMode = isSelectionMode,
                            isSelected = selectedIds.contains(entry.id),
                            onToggleSelection = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedIds = if (selectedIds.contains(entry.id)) {
                                    selectedIds - entry.id
                                } else {
                                    selectedIds + entry.id
                                }
                            },
                            onStartSelection = {
                                isSelectionMode = true
                                selectedIds = setOf(entry.id)
                            },
                            onEdit = {
                                if (inlineEditor != null) expandedKey = if (expanded) null else entryKey else onEditEntry(entry)
                            },
                            onEditFull = { expandedKey = null; onEditEntry(entry) },
                            onMove = { onMoveEntry(entry) },
                            onCopy = { onCopyEntry(entry) },
                            onDuplicate = { onDuplicateEntry(entry) },
                            onQuickIncrement = { onQuickIncrementEntry(entry) },
                            onDelete = { onDeleteEntry(entry) }
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = expanded,
                            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                        ) {
                            // Scrolled into view as it opens, so it isn't hidden under the toolbar at the bottom
                            val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(220)
                                runCatching { bring.bringIntoView() }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(bring)
                                    .clip(
                                        if (isLast) RoundedCornerShape(topStart = segInner, topEnd = segInner, bottomStart = segOuter, bottomEnd = segOuter)
                                        else RoundedCornerShape(segInner)
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(14.dp)
                            ) {
                                inlineEditor?.invoke(entry, { expandedKey = null }, { expandedKey = null; onDeleteEntry(entry) })
                            }
                        }
                      }
                    }
                }
            }
        }

        // Batch Action Bar when in Selection Mode
        if (isSelectionMode && entries.isNotEmpty()) {
            val selectedEntries = entries.filter { selectedIds.contains(it.id) }
            val count = selectedEntries.size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = segInner, topEnd = segInner, bottomStart = segOuter, bottomEnd = segOuter))
                    .background(ByteSurfaceVariantDark)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Move button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (count > 0) ByteTomato else ByteSurfaceDark)
                        .clickable(enabled = count > 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBatchMove(selectedEntries)
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                            contentDescription = null,
                            tint = if (count > 0) ByteOnTomato else ByteCreamMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (count > 0) "Move ($count)" else "Move",
                            color = if (count > 0) ByteOnTomato else ByteCreamMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Copy button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (count > 0) MacroProtein else ByteSurfaceDark)
                        .clickable(enabled = count > 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onBatchCopy(selectedEntries)
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = if (count > 0) ByteInkDark else ByteCreamMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (count > 0) "Copy ($count)" else "Copy",
                            color = if (count > 0) ByteInkDark else ByteCreamMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Delete button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (count > 0) Color(0xFFEF4444) else ByteSurfaceDark)
                        .clickable(enabled = count > 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBatchDelete(selectedEntries)
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = if (count > 0) ByteCream else ByteCreamMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (count > 0) "Delete ($count)" else "Delete",
                            color = if (count > 0) ByteCream else ByteCreamMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeableFoodEntryRow(
    entry: FoodEntry,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onStartSelection: () -> Unit = {},
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onQuickIncrement: () -> Unit,
    onDelete: () -> Unit,
    /** The full edit sheet, from the item menu (a tap opens the amount editor in place). */
    onEditFull: () -> Unit = onEdit
) {
    val haptic = LocalHapticFeedback.current
    var itemMenuExpanded by remember { mutableStateOf(false) }

    if (isSelectionMode) {
        // Selection Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (isSelected) ByteTomato.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onToggleSelection() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) ByteTomato else ByteCreamMuted,
                    modifier = Modifier.size(20.dp)
                )

                Column {
                    Text(
                        text = entry.foodName,
                        color = ByteCream,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "${entry.quantity.roundToInt()}${entry.unit} • P: ${entry.protein.roundToInt()}g  C: ${entry.carbs.roundToInt()}g  F: ${entry.fat.roundToInt()}g",
                        color = ByteCreamMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Text(
                text = "${entry.calories.roundToInt()} kcal",
                color = ByteTomato,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        // Normal Swipeable Row with 3-dots Menu
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onDuplicate()
                        false
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        onDelete()
                        false
                    }
                    else -> false
                }
            }
        )

        LaunchedEffect(entry.id) {
            if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            }
        }

        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val color = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> ByteTomato
                    SwipeToDismissBoxValue.EndToStart -> Color(0xFFEF4444)
                    else -> Color.Transparent
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(color)
                        .padding(horizontal = 16.dp),
                    contentAlignment = if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd)
                        Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = ByteOnTomato)
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                }
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEdit()
                        },
                        onDoubleClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQuickIncrement()
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            itemMenuExpanded = true
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.foodName,
                        color = ByteCream,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Saved on this phone but not on Sparky yet; it is sent on the next sync
                    if (entry.syncStatus == FoodEntry.SYNC_STATUS_PENDING_INSERT || entry.syncStatus == FoodEntry.SYNC_STATUS_PENDING_UPDATE) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = "Not on Sparky yet",
                            tint = ByteCreamMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                  }
                    Text(
                        text = "${entry.quantity.roundToInt()}${entry.unit} • P: ${entry.protein.roundToInt()}g  C: ${entry.carbs.roundToInt()}g  F: ${entry.fat.roundToInt()}g",
                        color = ByteCreamMuted,
                        fontSize = 11.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${entry.calories.roundToInt()} kcal",
                        color = ByteTomato,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // 3-dots Menu for Food Item Actions
                    Box {
                        IconButton(
                            onClick = { itemMenuExpanded = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Item Options",
                                tint = ByteCreamMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = itemMenuExpanded,
                            onDismissRequest = { itemMenuExpanded = false },
                            modifier = Modifier.background(ByteSurfaceDark)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit details…", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = ByteCream,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onEditFull()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Move to...", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.DriveFileMove,
                                        contentDescription = null,
                                        tint = ByteTomato,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onMove()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Copy to...", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = MacroProtein,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onCopy()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Duplicate Here", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = ByteCreamMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onDuplicate()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("+1 Serving", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = ByteCreamMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onQuickIncrement()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Select Items...", color = ByteCream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.SelectAll,
                                        contentDescription = null,
                                        tint = ByteCreamMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onStartSelection()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                onClick = {
                                    itemMenuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One-tap shortcuts for an empty meal: yesterday's foods, and the usual when it differs. */
@Composable
private fun MealShortcutRow(
    mealName: String,
    shortcuts: com.bytelog.app.data.repository.MealShortcuts,
    onLog: (List<FoodEntry>) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mealName, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp), maxLines = 1)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            if (shortcuts.yesterday.isNotEmpty()) item(key = "y") {
                ShortcutChip(Icons.Default.History, "Same as yesterday", shortcuts.yesterday, cs.secondaryContainer, cs.onSecondaryContainer) { onLog(shortcuts.yesterday) }
            }
            if (shortcuts.showUsual) item(key = "u") {
                val usual = shortcuts.usual.orEmpty()
                ShortcutChip(Icons.Default.Star, "Your usual", usual, cs.tertiaryContainer, cs.onTertiaryContainer) { onLog(usual) }
            }
        }
    }
}

@Composable
private fun ShortcutChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    entries: List<FoodEntry>,
    container: Color,
    content: Color,
    onClick: () -> Unit
) {
    val kcal = entries.sumOf { it.calories.toDouble() }.roundToInt()
    val what = if (entries.size == 1) entries[0].foodName else "${entries.size} foods"
    Row(
        Modifier
            .pressBounce(0.95f)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .clickable(onClickLabel = "Log $label") { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Column {
            Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("$what · $kcal kcal", color = content.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
}

/**
 * The amount editor opened under a logged food: − / + , units and quick amounts, saved as you go (after a short
 * pause, and when it closes). "More" opens the full edit sheet.
 */
@Composable
private fun InlineEntryEditor(
    entry: FoodEntry,
    viewModel: DiaryViewModel,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit
) {
    val key = "${entry.createdAt}|${entry.foodName}"
    var food by remember(key) { mutableStateOf<com.bytelog.app.data.local.model.FoodItem?>(null) }
    LaunchedEffect(key) { food = viewModel.foodForEntry(entry) }
    var portion by remember(key) { mutableStateOf(com.bytelog.app.ui.logging.Portion(entry.quantity, entry.unit)) }
    var saved by remember(key) { mutableStateOf(portion) }
    val f = food
    if (f != null) {
        LaunchedEffect(portion) {
            if (portion != saved) {
                kotlinx.coroutines.delay(700)
                viewModel.setEntryPortion(entry, f, portion)
                saved = portion
            }
        }
    }
    // Closing straight after a change still saves it
    val latest = androidx.compose.runtime.rememberUpdatedState(Triple(portion, saved, f))
    androidx.compose.runtime.DisposableEffect(key) {
        onDispose {
            val (p, s, ff) = latest.value
            if (ff != null && p != s) viewModel.setEntryPortion(entry, ff, p)
        }
    }
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (f == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                com.bytelog.app.ui.diary.LoadingSpinner(modifier = Modifier.size(20.dp), color = cs.primary, strokeWidth = 2.dp)
            }
        } else {
            com.bytelog.app.ui.logging.QuickPortionEditor(
                food = f,
                portion = portion,
                units = com.bytelog.app.ui.logging.Portions.unitOptions(f),
                onChange = { portion = it },
                onRemove = onDelete
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = onMore) { Text("More options", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.FilledTonalButton(onClick = onDone) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    }
}
