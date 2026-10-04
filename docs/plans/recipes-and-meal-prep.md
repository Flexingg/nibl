# Recipes, Cook Mode & Meal Prep Batches — Implementation Plan

Status: proposal · Supersedes the "Meal Prep" feature draft (Pillars 1–3)

## 0. Review of the original draft

The vision is right: import → cook → batch → 1-tap log is the workflow people actually follow. On-device
JSON-LD parsing, Share-to-ByteLog, cooked-weight batching and the "virtual fridge" are the right ideas and
are kept. Several assumptions don't match how ByteLog works today, though, and the rollout order puts the
riskiest piece first.

| Draft assumption | What the code says | Consequence |
| :--- | :--- | :--- |
| "Expand `MealTemplate` in Room" | `MealTemplateRepository.refreshTemplates()` re-fetches `/api/meals` and `insertTemplates()` uses `OnConflictStrategy.REPLACE` | Any local-only column on `meal_templates` is **wiped on every refresh**. Recipe details need their own table. |
| Room migrations are safe | `ByteLogDatabase` uses `.fallbackToDestructiveMigration()` with `exportSchema = false` | A missed migration silently deletes the user's recipes, batches and unsynced diary entries. Fix this before adding user-authored local data. |
| "LLM returns grams, we're done" | `FoodItem.caloriesFor(quantity, unit)` **ignores `unit`** and just does `quantity / servingSize` | "1.5 lb chicken" against a 100 g food logs 1.5 % of a serving. A unit converter is a prerequisite, not a detail. |
| "Use Sparky's LLM via `SparkyApiClient`" | The only text-LLM path is `/api/chat`, an agent that **executes tools** (logs food, records weight) and **persists to chat history** | Sending ingredient lists through it can log food by accident and clutters the user's chat. Needs a stateless endpoint (see §3.4). |
| LLM output = trackable food | The draft's JSON has name/qty/grams but no food match | Parsing is the easy part. The hard part is **matching each line to a food with nutrition**, and that has to be confirmable and remembered. |
| Batch logs are "1 serving of the template" | `logMealTemplate()` is online-only (no Room write) and scales raw ingredients | Doesn't fit the offline-first principle, and it can't represent cooked weight or a cook-day ingredient swap. |
| Cooklang | — | Low value for this user base. Deferred; maybe an export format later. |

## 1. Guiding decisions

1. **Sparky owns the ingredient list and totals; ByteLog owns the recipe extras.** Directions, times,
   source URL, image and storage notes live in a new local `recipe_details` table keyed by `templateId`.
   Template refresh never touches them. Server sync of these fields comes later (Phase 5), once SparkyFitness
   has columns for them.
2. **One batch model, not two.** Every batch has *total nutrients*. It can also have a portion count, a
   cooked weight, or both. Containers vs. weighed is a choice made when logging, not a different kind of batch.
3. **A batch is a frozen snapshot.** Starting a batch copies the template's totals at that moment, with any
   cook-day edits ("used 600 g chicken instead of 500"). Editing the recipe later never changes what you
   already cooked or logged.
4. **Deterministic first, AI second.** Fractions, units and JSON-LD are parsed with plain code. AI only
   handles the lines the code can't resolve, and the user confirms every match before it is saved.
5. **Remember every decision.** Once a user maps "boneless skinless chicken breast" to their food, the next
   import maps it automatically.

## 2. Phase 0 — Foundations (prerequisites, small)

- **Room safety**: turn on `exportSchema = true`, commit schemas under `app/schemas/`, add a
  `MigrationTestHelper` test, and drop `fallbackToDestructiveMigration()` (or limit it to
  `fallbackToDestructiveMigrationFrom(1)`).
- **`UnitConverter`** (pure Kotlin, unit-tested):
  - Mass: g, kg, oz, lb → g (exact).
  - Volume: tsp, tbsp, cup, fl oz, ml, l → ml, then to grams via a density table for ~60 common ingredients
    (oil 0.92, flour 0.53, sugar 0.85, rice 0.85, milk 1.03, water 1.0 …). With no density known, the line is
    flagged as unresolved instead of guessed.
  - Count units (clove, can, egg, slice) → the food's own serving unit when it matches, otherwise unresolved.
  - Change `FoodItem.*For(quantity, unit)` to go through the converter. This also fixes existing logging paths.
- **Ingredient storage format**: always save `TemplateIngredient` in the food's native serving unit (usually
  g) after conversion, so Sparky never has to interpret "lb".

## 3. Phase 1 — Meal Prep Batches ("the fridge")

Why first: it delivers the main value (fast daily logging of prepped food) using templates that already
exist, needs no scraping or AI, and is where ByteLog's speed principle matters most.

### 3.1 Data model (Room, local)

```kotlin
@Entity(tableName = "prep_batches")
data class PrepBatch(
    @PrimaryKey val id: String,
    val templateId: String?,            // null for an ad-hoc batch
    val name: String,                   // "Turkey Chili"
    val cookedAt: Long,
    val bestBy: Long?,                  // cookedAt + storage days (fridge/freezer)
    val location: String = "fridge",    // fridge | freezer
    // Frozen totals for the whole batch
    val calories: Float, val protein: Float, val carbs: Float, val fat: Float, val fiber: Float?,
    val portions: Int?,                 // e.g. 5 containers
    val cookedWeightG: Float?,          // e.g. 1450 g (pot tare already subtracted)
    val ingredientsJson: String,        // snapshot incl. cook-day edits
    val sparkyFoodId: String?,          // see 3.3
    val status: String = "active"       // active | finished | discarded
)

@Entity(tableName = "batch_consumptions")
data class BatchConsumption(
    @PrimaryKey val foodEntryId: String,  // 1:1 with the diary entry it created
    val batchId: String,
    val portions: Float?,                 // 1.0, 0.5 …
    val grams: Float?
)
```

Remaining amount is **derived, never stored**:
`remaining = total − Σ consumptions` (grams when a weight is known, otherwise portions). Deleting or editing
the diary entry automatically gives the food back. No counter can drift.

### 3.2 UX

- **Start batch** (from a template, from Cook Mode's "Done", or from the diary): yield as portions and/or
  cooked weight, with a **pot tare helper** (choose a saved container with its empty weight; a "weigh pot
  empty first" reminder) and fridge/freezer. Shelf life is prefilled from the recipe's storage notes or a
  default of 4 days in the fridge / 60 in the freezer.
- **Fridge strip** at the top of the Diary, plus a section in the FastSearch sheet: one card per active
  batch showing "3 of 5 left · 2 days old · best by Thu".
  - Tap → log 1 portion to the **current meal context** (the same time-of-day logic FastSearch already uses).
    Haptic feedback plus an Undo snackbar.
  - Long-press → portion editor with the custom numpad: portions (½, 1, 1½ chips) or grams. When BLE scale
    support lands, grams fill in live from the scale.
  - Batch items can go into the **multi-add tray** with other foods ("chili + sour cream + cheese").
- **Finishing**: at 0 remaining, the card shows "Finished ✓" with Undo for the rest of the day, then
  archives. When a weighed batch has < 5 % left, offer "Log the rest" or "Toss the rest" (waste is recorded,
  not logged), because weights never come out exact.
- **Expiry**: one WorkManager reminder on the morning of `bestBy` ("Turkey Chili: 2 portions left, eat or
  freeze today"). Moving it to the freezer pushes `bestBy` out. Notifications can be turned off in Settings.

### 3.3 How a batch log becomes a diary entry

Recommended: when a batch starts, create a **batch snapshot food** in Sparky via the existing
`createCustomFood()`:
- `name = "Turkey Chili"`, `brand = "Batch · Oct 4"`
- weighed: `servingSize = cookedWeightG`, `servingUnit = "g"`, nutrients = batch totals, so the existing
  `quantity / servingSize` math is exactly right for grams.
- portions only: `servingSize = 1`, `servingUnit = "portion"`, nutrients = totals ÷ portions.

Logging then goes through the normal offline-first `FoodEntry` path (Room first, background sync). If
creating the food fails offline, the entry stores `foodId = null` with denormalised macros (already
supported) and the food is created when the device reconnects. Batch foods are left out of local
search/recents after they are archived, so they don't clutter the food list.

Rejected alternative: `logMealTemplate(quantity = fraction)`. It needs a network connection, scales raw
ingredients instead of the cooked batch, and can't capture cook-day changes.

## 4. Phase 2 — Recipe details & Cook Mode

### 4.1 Data model

```kotlin
@Entity(tableName = "recipe_details")
data class RecipeDetails(
    @PrimaryKey val templateId: String,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val sourceUrl: String? = null,
    val imageUrl: String? = null,
    val stepsJson: String = "[]",         // List<RecipeStep>
    val notes: String? = null,
    val fridgeDays: Int? = null,          // structured, so batches can compute bestBy
    val freezerDays: Int? = null,
    val storageNotes: String? = null,     // free text
    val rawIngredientLinesJson: String = "[]", // original import text, for re-matching later
    val siteNutritionJson: String? = null,     // schema.org nutrition, for sanity checks
    val updatedAt: Long
)

@Serializable
data class RecipeStep(
    val text: String,
    val section: String? = null,          // "For the marinade" (replaces the isHeader flag)
    val timerSeconds: Int? = null         // parsed at save time, user-editable
)
```

Changes from the draft: storage shelf life is **structured** (`fridgeDays`/`freezerDays`) so batches can
use it. Section headers become a property of each step rather than fake steps. Step numbers are implied by
list order. The raw ingredient lines are kept so a bad match can be fixed later without re-importing.

Orphans: when a template is deleted (or vanishes from the server on refresh), its `recipe_details` row is
kept for 30 days so an accidental delete can be undone, then removed.

### 4.2 Recipe Builder

Add a second tab to `RecipeBuilderSheet`, **Directions & Notes**: an ordered step list (add, reorder by
drag, paste a block of text to split it into steps), prep/cook time, fridge/freezer days and notes. The
existing ingredients tab is unchanged apart from going through the unit converter.

### 4.3 Recipe Detail & Cook Mode

- **Detail screen**: hero image, macros per serving and per batch, times, ingredients, steps, and
  "Start cooking" / "Start batch" / "Log 1 serving" buttons.
- **Cook Mode** (full screen, large type, high contrast):
  - **Scale selector** first (½×, 1×, 2×, custom). Ingredient quantities rescale; the scale carries over
    to "Start batch".
  - **Mise en place** checklist of ingredients, then the step checklist. Tap anywhere on a row to check it,
    because hands are messy.
  - Keep the screen on with `View.keepScreenOn` scoped to the composable. No wake-lock permission is needed.
  - **Timer pills** from the regex `(\d+)(?:\s*[-–to]+\s*(\d+))?\s*(min|minute|hr|hour|sec)`; for ranges
    the default is the lower bound, with the upper bound shown. Several timers can run at once, backed by
    `AlarmManager` exact alarms plus a notification, so they still fire when the app is in the background or
    the screen is off.
  - Cook Mode's progress is held in memory for the session, so a phone call doesn't reset it.
  - **Done** → "Start a batch from this?" with the scale already applied. This links Phases 1 and 2.

## 5. Phase 3 — Online import (on-device)

### 5.1 Entry points

- An "Import from URL" field in the Meal Templates sheet that auto-fills from the clipboard when it holds
  a URL.
- **Share target**: an `ACTION_SEND` `text/plain` intent-filter on a lightweight `ShareReceiverActivity`.
  The URL is pulled out of the shared text, since Chrome shares "Title\nURL".
- Later: "Paste recipe text" for recipes from messages or notes, which skips straight to §5.3.

### 5.2 Extraction (`RecipeExtractor`, pure Kotlin + Jsoup, unit-tested against saved HTML fixtures)

1. Fetch with the existing OkHttp client, a normal mobile browser User-Agent, a 10 s timeout and a 3 MB cap.
   Fetches only happen when the user asks for one.
2. Read every `script[type=application/ld+json]` block and handle the real-world variants: top-level arrays,
   `@graph`, `@type` given as an array (`["Recipe","NewsArticle"]`), and HTML entities inside strings.
3. Normalise:
   - `recipeInstructions`: plain string, `HowToStep[]`, or `HowToSection` → `itemListElement` (section name
     becomes `RecipeStep.section`).
   - `prepTime`/`cookTime`/`totalTime`: ISO-8601 durations (`PT1H30M`).
   - `recipeYield`: number, string ("4 servings", "Makes 12 muffins") or array → portions plus a unit label.
   - `image`: string, array or `ImageObject` → choose the largest.
   - `nutrition` (`NutritionInformation`) → kept for the sanity check.
4. Fallback 1: Microdata (`itemprop="recipeIngredient"` etc.). Fallback 2 (Phase 4): send the page's main
   text to the AI parser. If nothing works: "Couldn't read this page. Paste the recipe text instead?"

### 5.3 Ingredient line parsing (deterministic)

The `IngredientLineParser` handles:
- quantities: `1 1/2`, `1½`, `¼`, `0.5`, ranges `2-3` (use the midpoint and show the range), "a pinch" or
  "to taste" (0, flagged as optional)
- package sizes: `1 (14.5 oz) can diced tomatoes` → 411 g
- dual units: `200 g (7 oz) flour` → prefer the metric value
- unit aliases and plurals (`T`, `Tbsp`, `tablespoons`, `c.`, `lbs`, `#`)
- name cleanup: strip prep descriptors (`, diced`, `finely chopped`, `divided`) into a `note` field, but keep
  descriptors that change nutrition (`boneless skinless`, `lean 93%`, `cooked`, `dry`)

Output per line: `ParsedIngredient(raw, quantity, unit, name, note, grams?, confidence)`.

### 5.4 Matching & the Review screen

For each parsed line, in order:
1. **Alias table** (`ingredient_aliases`: normalised name → foodId, preferred unit, grams per unit).
   An exact hit is auto-accepted.
2. Local food cache (favourites/recents first) → Sparky search → OpenFoodFacts.
3. Nothing found → the "Generate with AI" action (the existing `GenerateFoodAiDialog`).

**Review screen**: one row per ingredient showing the raw line, the matched food, quantity in grams and a
calorie contribution, colour-coded by confidence. A tap swaps the food or edits the amount with the numpad.
The rows that need attention are sorted to the top. A live totals bar shows **"Per serving: 470 kcal ·
site says 450"**; a gap of more than 20 % gets a warning, which catches mis-matches like raw vs. cooked rice
or a whole chicken vs. breast. Saving writes the template (Sparky), `recipe_details` (local), and an
`ingredient_aliases` row for every line the user confirmed or changed.

## 6. Phase 4 — AI assist

- **Server work (SparkyFitness PR)**: a stateless `POST /api/foods/parse-ingredients`, built like the
  existing `/api/foods/estimate-food-photo`. Input: raw lines plus optional recipe title and yield. Output:
  `[{raw, name, quantity, unit, grams, confidence}]` with a JSON-schema-constrained response. No tools, no
  chat history.
- In the client, only lines left **unresolved or low-confidence** after §5.3 are sent, and the results go
  back into the same Review screen. AI never saves anything without the user seeing it.
- Until the endpoint exists the feature stays off and the deterministic parser carries the load. Do **not**
  route through `/api/chat`.
- Same endpoint, multimodal: **photo of a cookbook page or recipe card** → lines plus steps. The image
  plumbing already exists for Sparky Chat.

## 7. Phase 5 — Server sync & polish

- Propose SparkyFitness columns or a `meal_recipe_details` table matching `RecipeDetails`, so directions
  reach the web app and other devices. Until then, include `recipe_details` and `prep_batches` in a local
  export/backup (JSON) so a reinstall doesn't lose them.
- Possibly later: Cooklang `.cook` export, a "cook again" shortcut that copies an archived batch, and
  per-recipe history ("cooked 6 times, usually 1,420 g").

## 8. Rollout summary

| Phase | Scope | Depends on | Server change |
| :--- | :--- | :--- | :--- |
| 0 | Room migration safety, UnitConverter, unit-aware food math | — | No |
| 1 | Prep batches, fridge strip, 1-tap/weighed logging, expiry reminder | 0 | No (uses `/api/foods`) |
| 2 | RecipeDetails, Builder directions tab, Detail screen, Cook Mode + timers | 0 | No |
| 3 | URL/share import, JSON-LD extractor, line parser, matching + alias memory | 0, 2 | No |
| 4 | AI ingredient parsing, photo-of-recipe import | 3 | **Yes** (`parse-ingredients`) |
| 5 | Sync recipe details to Sparky, backup/export | 2 | **Yes** |

Phases 1 and 2 are independent and can be built in parallel.

## 9. Testing

- Unit: `UnitConverter`, `IngredientLineParser` (a table of 150+ real lines), `RecipeExtractor` against
  ~20 saved HTML fixtures from the major sites, ISO duration and yield parsing, timer regex, batch remaining
  math (including delete/edit of a consumption).
- Room: `MigrationTestHelper` for every new version.
- Instrumented: log a portion from the fridge offline → entry is in Room → syncs; delete the entry →
  the portion returns.

## 10. Open questions

1. Does `/api/food-entry-meals` `quantity` mean servings or a multiplier of `total_servings`? This only
   matters if template logging stays in use for non-batch recipe logs.
2. Should batch snapshot foods be deleted from Sparky after archiving, or kept so historic entries keep a
   food reference? Proposal: keep them, hidden locally.
3. Is a SparkyFitness PR (Phases 4–5) in scope for this team, or should those phases stay client-only?
