# ByteLog: Engineering & Product Development Specification
**Platform:** Native Android (Kotlin, Jetpack Compose)  
**Primary Backend:** Sparky Fitness Server  
**Design Baseline:** MacroFactor (High-speed UX, multi-add workflows, context-aware input)  
**Target Performance:** Sub-10-second end-to-end multi-item meal logging, 120 FPS UI, zero-latency offline-first architecture

---

## 1. Executive Summary & Vision

### 1.1 Purpose & Problem Statement
Traditional food tracking applications (MyFitnessPal, LoseIt, Cronometer) are hindered by high-friction UX: deep navigation hierarchies, sluggish network-dependent searches, jarring soft-keyboard animations, and fragmented single-item logging flows. Users spend 30–60 seconds per meal just clicking buttons and waiting for loaders.

**ByteLog** is a native Android application engineered with a single guiding principle: **Speed is the feature.**

ByteLog utilizes **Sparky Fitness** as its self-hosted / cloud backend ecosystem, replacing the generic or hybrid mobile experience with a hyper-optimized native Android client. Benchmarked directly against **MacroFactor**—widely regarded as the industry standard for logging velocity—ByteLog adopts and improves upon MacroFactor's multi-add tray, continuous barcode scanning, zero-latency custom keypads, and time-contextual food suggestions, while adding next-generation features such as direct Bluetooth Low Energy (BLE) kitchen scale integration and Android-native quick-access shortcuts.

### 1.2 Core Benchmarks: MacroFactor vs. ByteLog

| Feature | Standard Apps (e.g. MFP) | MacroFactor Baseline | ByteLog Specification |
| :--- | :--- | :--- | :--- |
| **Logging Paradigm** | 1 item at a time (Search $\to$ Tap $\to$ Adjust $\to$ Save $\to$ Repeat) | Multi-Add Staging Tray (Search $\to$ Tap $\to$ Next Search $\to$ Log All) | **Enhanced Multi-Add Tray** + Quick-Scrub quantities + Gesture pin |
| **Search Latency** | 400ms – 1500ms (Network roundtrip) | Instant local cache + debounced server fetch | **< 16ms Instant Local FTS5 Cache** + Background Sparky API pre-fetch |
| **Number & Unit Input** | System soft keyboard (layout shift, typing delay) | Custom embedded numpad with fractional chips | **Custom In-App Zero-Jitter Numpad** + Rotary Haptic Scrubbers |
| **Barcode Scanning** | Single scan $\to$ View details $\to$ Confirm $\to$ Back to camera | Continuous stream scanner with rapid queueing | **Continuous CameraX + ML Kit Burst Scanner** with instant haptic queue |
| **Portion Measurement** | Manual numeric typing only | Smart unit conversion picker | **Live BLE Smart Scale Streaming** (auto-fills grams in real-time) |
| **Offline Reliability** | Often broken or displays stale spinners | Offline cached | **100% Offline-First (Room DB)** with optimistic background sync |
| **Quick Add** | Hidden behind sub-menus | Fast 4-macro manual entry | **Instant 1-Tap Quick-Add** (Cal/P/C/F) with auto-balancing math |
| **Voice / Natural Text** | Primitive or absent | AI-assisted text transcription | **On-Device Natural Language Food Parser** + Sparky AI Photo Estimation |

---

## 2. Technical Architecture & Tech Stack

ByteLog is built as a pure, native modern Android application following Google’s official Android Architecture Guidelines and Clean Architecture principles.

```
                      +---------------------------------------+
                      |           Presentation Layer          |
                      |   (Jetpack Compose + Material 3)      |
                      |   - Diary Screen                      |
                      |   - Fast-Search & Multi-Add Sheet     |
                      |   - Continuous Barcode Scanner        |
                      |   - Embedded Custom Numpad            |
                      +-------------------+-------------------+
                                          |
                                 UI State / Intents (MVI)
                                          v
                      +---------------------------------------+
                      |            ViewModel Layer            |
                      |  - StateFlow / SharedFlow             |
                      |  - Coroutines Execution Scope         |
                      |  - UI Business Logic & Fast-Math      |
                      +-------------------+-------------------+
                                          |
                                          v
                      +---------------------------------------+
                      |              Domain Layer             |
                      |  - Use Cases (LogMeal, SearchFoods)   |
                      |  - Food Scoring & Ranking Algorithm   |
                      |  - Serving Unit Converter Engine      |
                      +-------------------+-------------------+
                                          |
                                          v
                      +---------------------------------------+
                      |              Data Layer               |
                      |  Repository Pattern (Single Source)   |
                      +---------+-------------------+---------+
                                |                   |
                                v                   v
               +----------------------+   +----------------------+
               |   Local Data Source  |   |  Remote Data Source  |
               | - Room DB (SQLite)   |   | - Sparky REST Client |
               | - SQLite FTS5 Search |   | - Better-Auth Bearer |
               | - BLE Scale Manager  |   | - WorkManager Sync   |
               +----------------------+   +----------------------+
```

### 2.1 Technology Matrix
- **Language:** Kotlin 2.1+ (100% Coroutines & Flow)
- **UI Toolkit:** Jetpack Compose (BOM 2024.x+), Material 3 Design Tokens, Edge-to-Edge display
- **Architecture:** MVI (Model-View-Intent) / Clean Architecture with Unidirectional Data Flow (UDF)
- **Dependency Injection:** Hilt / Dagger
- **Local Persistence & Search:**
  - **Room Database** (SQLite with FTS5 virtual tables for full-text search)
  - **DataStore Preferences** for user configuration and fast UI flags
- **Networking & API:**
  - **Ktor Client** or **Retrofit 2 + OkHttp 4** (HTTP/2, connection pooling, Brotli/Gzip compression)
  - **KotlinX Serialization** for high-performance zero-reflection JSON parsing
- **Camera & Machine Learning:**
  - **CameraX** (Analysis use case)
  - **Google ML Kit Barcode Scanning** (client-side, 0ms network latency, 60fps frame analyzer)
- **Hardware Integration:**
  - Android **Bluetooth Low Energy (BLE)** GATT client for digital kitchen scale communication (Decent, SmartChef, generic BLE kitchen scale protocols)
- **Background Synchronization:**
  - **Android Jetpack WorkManager** for reliable, battery-aware, exponential-backoff sync queueing
- **Audio & Haptics:**
  - **Vibrator / CombinedVibration API** with custom tactile tick waveforms (`HapticFeedbackConstants`) for mechanical-feeling wheel scrubbing and scan confirmations

---

## 3. Speed-First UX Paradigms & MacroFactor Benchmark Improvements

### 3.1 The Multi-Add "Staging Tray" (The Basket)
In MacroFactor, users never commit one item at a time. ByteLog takes this paradigm and streamlines it:
1. **Persistent Bottom Tray:** An interactive tray floats at the bottom of the search, barcode, and favorites screens showing `[ X items | Total Cals & Macros | LOG MEAL ]`.
2. **One-Tap Staging:** Tapping any search item or barcode match immediately stages it into the tray with its default serving (e.g. 100g, 1 container, or user’s last-used portion for that item).
3. **In-Line Quantity Adjuster:** Without leaving search, items in the tray can be tapped or scrubbed horizontally to tweak weights immediately.
4. **Batch Commit:** A single tap on "Log (N items)" atomically saves all entries to the local Room database and triggers an asynchronous background sync to Sparky Fitness.

### 3.2 Instant In-App Custom Numpad & Rotary Scrubber
System soft keyboards trigger viewport resizes, layout jank, and take 150–250ms to open/close.
- **Embedded Numpad:** ByteLog uses a dedicated, highly responsive Compose-rendered numeric keypad directly inside the food detail drawer.
- **Portion Multiplication Quick-Chips:** Buttons for `.25`, `.5`, `1`, `2`, `x`, `+10g`, `+25g`, `+50g`.
- **Haptic Rotary/Horizontal Scrubber:** Dragging finger horizontally across the portion input increments/decrements quantity in single grams with tight mechanical tick haptics.

### 3.3 Contextual & Predictive Search Engine
1. **Zero-Character Display (Smart Recents):** Before typing a single character, the search screen presents:
   - **Time-of-Day Affinity:** Foods logged between 06:00 and 10:30 appear at breakfast; afternoon snacks appear in the afternoon; evening meals at dinner.
   - **Frequent Pairs / Meal Groupings:** Logging "Greek Yogurt" automatically bubbles up "Blueberries" and "Honey" as 1-tap quick adds.
2. **Sub-16ms Local FTS5 Search:**
   - User's historical foods, custom foods, and favorites are indexed in a local SQLite FTS5 table.
   - Keystroke latency is non-blocking; search updates on every single key press without debounce lag for local items.
3. **Background Sparky Federated Query:**
   - If local search has < 5 exact matches or user pauses for 250ms, the remote Sparky Fitness API (`/api/foods/foods-paginated` & external providers like OpenFoodFacts / USDA) is queried in parallel and appended cleanly without jumping the list.

### 3.4 Continuous "Burst" Barcode Scanner
1. **Never-Leave Viewfinder:** MacroFactor pioneered scanning multiple items back-to-back without leaving camera mode.
2. **ByteLog Burst Flow:**
   - Point camera at Barcode 1 $\to$ BEEP & sharp haptic click $\to$ Floating chip displays "Oat Milk - 1 cup (120 kcal) added to tray" $\to$ Immediately point at Barcode 2 $\to$ BEEP $\to$ "Protein Powder - 1 scoop (130 kcal) added to tray".
   - A mini tray stays docked at the bottom of the camera viewfinder with real-time running calorie/macro totals.
   - Tap "Done" or "Review" to finalize or log directly.

### 3.5 Bluetooth Digital Kitchen Scale Live Streaming
*A major leap beyond MacroFactor:*
- ByteLog connects to standard BLE digital food scales.
- When the Food Detail or Multi-Add drawer is active, the app displays live weight readout from the scale:
  - Put bowl on scale $\to$ Tap Tare on screen (or physical tare).
  - Pour food $\to$ The quantity field updates in real-time ($0g \to 45g \to 82g \to 125g$).
  - Once stable for 1.2s or user taps "Next", the exact weight locks in.
  - Zero manual typing required.

### 3.6 1-Tap Quick-Add (Calorie & Macro Shorthand)
- Floating action button or swipe shortcut opens a rapid 4-field calculator:
  - `Calories`, `Protein`, `Carbs`, `Fat`.
- **Auto-Balancing Engine:** Entering P, C, and F automatically calculates and populates calories ($4P + 4C + 9F$). Entering Calories and Protein offers automatic 50/50 Carb/Fat distribution or custom ratios.

### 3.7 Natural Language Dictation / Paste Parser
- Tap microphone icon or paste text snippet (e.g., *"3 large eggs, 2 slices sourdough toast with 15 grams butter, and a cup of black coffee"*).
- Parsed via local Regex/NLP rule set or Sparky AI service into structured entities:
  - Eggs: 3 large
  - Sourdough: 2 slices
  - Butter: 15 g
  - Coffee: 240 ml
- Drops into the Multi-Add Tray ready for review in under 1 second.

---

## 4. Sparky Fitness Backend Integration

ByteLog integrates with the existing **SparkyFitnessServer** API contract, respecting its data models, authentication methods, and database conventions.

### 4.1 Authentication & Session Management
- **Primary Auth:** Better-Auth Session Cookie / Bearer Token via `/api/auth/sign-in/email` or token exchange.
- **Alternative / Self-Hosted:** Personal API Key (`X-API-Key` or Bearer token header) for single-user self-hosted instances.
- **Multi-User / Family Mode Support:** Passes `user_id` in requests when acting on behalf of allowed family accounts (`/api/family-access`).

### 4.2 Endpoint Mapping Matrix

| Feature in ByteLog | Sparky Fitness Endpoint | Method | Payload / Response |
| :--- | :--- | :--- | :--- |
| **User Sign-in** | `/api/auth/sign-in/email` | `POST` | `{ email, password }` $\to$ session/bearer token |
| **Get Diary Entries** | `/api/foods/food-entries/:date` or `/api/food-entries/by-date/:date` | `GET` | Returns `FoodEntryResponse[]` with macro snapshots |
| **Fast Local Cache Preload** | `/api/foods` & `/api/favorites` | `GET` | Fetches user's top, recent, and favorite food catalog |
| **Remote Search** | `/api/foods/foods-paginated?searchTerm=...` | `GET` | Paginated search across custom, library, and linked providers |
| **Barcode Lookup** | `/api/foods/barcode/:barcode` | `GET` | Returns `Food` object + matching `FoodVariant[]` |
| **Create Food Entry** | `/api/food-entries/` | `POST` | `CreateFoodEntryPayload` (servings, nutrients, date, time) |
| **Bulk Create (Multi-Add)** | `/api/food-entries/import-from-csv` or batch `/api/food-entries` | `POST` | Batch creation of staged basket items |
| **Update Entry** | `/api/food-entries/:id` | `PUT` | `UpdateFoodEntryPayload` |
| **Delete Entry** | `/api/food-entries/:id` | `DELETE` | Removes log entry |
| **Fetch Meal Types** | `/api/meal-types` | `GET` | User configured meals (Breakfast, Lunch, Dinner, Snacks, etc.) |
| **Food Photo AI Estimation** | `/api/foods/scan-label` / photo endpoint | `POST` | Multipart image upload $\to$ estimated nutritional breakdown |

### 4.3 Offline Sync & Conflict Resolution Protocol
1. **Optimistic Local Execution:**
   - Every creation, modification, or deletion generates a client UUID (`id`) and is committed immediately to Room DB.
   - The UI updates instantly with zero network wait time.
2. **Sync Outbox Table (`sync_queue`):**
   - Each mutation logs an operation: `INSERT`, `UPDATE`, `DELETE`, `TIMESTAMP`, `PAYLOAD`.
3. **Background Worker (WorkManager):**
   - Triggers when network connectivity is restored (`NetworkType.CONNECTED`).
   - Replays operations in strict chronological sequence.
   - Conflict strategy: **Client-Win for diary entries** (user's latest device modification is authoritative); **Server-Win for master food catalog revisions**.

---

## 5. Local Database Schema & Room Entities

To achieve < 16ms query response times, all frequently used data is stored locally in SQLite with Full-Text Search 5 (FTS5).

```
 +--------------------+       1:N       +------------------------+
 |   meal_types       |----------------<|   food_entries         |
 | - id (PK)          |                 | - id (UUID PK)         |
 | - name             |                 | - meal_type_id (FK)    |
 | - sort_order       |                 | - food_id (FK, opt)    |
 +--------------------+                 | - entry_date, time     |
                                        | - quantity, unit       |
 +--------------------+       1:N       | - calories, p, c, f    |
 |   foods            |----------------<| - sync_status          |
 | - id (PK)          |                 +------------------------+
 | - name, brand      |                             |
 | - barcode          |       1:N                   | (FTS Lookup)
 | - serving_size     |----------------+            v
 | - serving_unit     |                |   +------------------------+
 | - calories, p, c, f|                |   |   foods_fts (FTS5)     |
 | - is_favorite      |                |   | - rowid                |
 | - last_used_at     |                |   | - name                 |
 | - use_count        |                |   | - brand                |
 +--------------------+                |   +------------------------+
           | 1:N                       |
           v                           v
 +--------------------+   +------------------------+
 |   food_variants    |   |   sync_queue           |
 | - id (PK)          |   | - id (Auto Inc PK)     |
 | - food_id (FK)     |   | - entity_type, action  |
 | - serving_size     |   | - payload_json         |
 | - calories, p, c, f|   | - created_at, retries  |
 +--------------------+   +------------------------+
```

### 5.1 SQLite FTS5 Configuration
```sql
CREATE VIRTUAL TABLE IF NOT EXISTS foods_fts USING fts5(
    name,
    brand,
    content='foods',
    content_rowid='rowid',
    tokenize='porter unicode61'
);

-- Triggers keep FTS5 synchronized with foods table automatically on INSERT, UPDATE, DELETE
```

### 5.2 Frequency & Recency Ranking Algorithm
When rendering search results, local items are sorted using a blended relevance formula:
$$\text{Score} = (\text{BM25\_FTS\_Match} \times 0.4) + (\text{RecencyWeight} \times 0.3) + (\text{FrequencyWeight} \times 0.2) + (\text{TimeOfDayAffinity} \times 0.1)$$
This ensures that when a user types "ch", "Chicken Breast" appears at dinner time, while "Chia Seeds" or "Cheerios" appears at breakfast time if that matches their past habits.

---

## 6. Detailed Screen Flow & User Interface Specs

### 6.1 Screen Architecture & Navigation Flow

```
[ Dashboard / Diary ] 
   │
   ├── (Tap "+" on Meal or Bottom Nav) ──> [ Fast-Search & Multi-Add Screen ]
   │                                          │
   │                                          ├── [ Embedded Numpad / Portions Drawer ]
   │                                          ├── [ Continuous Barcode Scanner (CameraX) ]
   │                                          ├── [ 1-Tap Quick-Add Sheet (Macros) ]
   │                                          └── [ Voice / Natural Text Dictation ]
   │                                          │
   │                                          └── (Tap "Log N Items") ──> [ Instant Commit & Return ]
   │
   ├── (Swipe Left on Logged Item) ───────> [ Quick Delete with Undo ]
   ├── (Swipe Right on Logged Item) ──────> [ Quick Duplicate to Current Time ]
   └── (Tap on Meal Header) ──────────────> [ Meal Actions: Copy Meal, Save as Recipe ]
```

### 6.2 Key Screen Specifications

#### Screen A: The Daily Nutrition Dashboard & Timeline
- **Header:**
  - Macro progress bars (Calories, Protein, Carbs, Fat) with real-time remaining / target metrics.
  - Date switcher with swipe-to-change-day gestures.
- **Meal Sections:**
  - Grouped by user-configured `meal_types` (Breakfast, Lunch, Dinner, Snacks) OR a continuous chronological 24h timeline (user preference toggle).
  - Each item displays: Name, Brand, Serving (e.g. `150 g`), Calories, and Macro Pill (`P: 32g | C: 0g | F: 5g`).
- **Interaction Speed:**
  - Swipe right: Instant duplicate to current meal/time.
  - Swipe left: Delete with instant haptic snackbar undo.
  - Long press: Multi-select mode for bulk copy, move, or delete.

#### Screen B: Fast-Search & Multi-Add Canvas
- **Top Bar:** Persistent search input, auto-focused on entry.
- **Context Row:** Meal target selector (e.g. `Lunch [v]`), Date selector, Barcode shortcut, Voice shortcut, Quick-Add shortcut.
- **Body:**
  - Tab 1: **Smart Recents** (Instant zero-query list sorted by time-of-day affinity).
  - Tab 2: **Favorites & Frequent Pairs**.
  - Tab 3: **Custom Foods & Recipes**.
- **Bottom Staging Tray (MacroFactor Style):**
  - Displays count badge, thumbnail avatars, aggregated calories and macros.
  - Expandable to view individual items, delete items, or tap to edit portions.
  - Primary button: **"Log 3 Items (680 kcal)"** (Commits everything in 1 frame).

#### Screen C: Portions & Numeric Keypad Drawer
- **Header:** Food Name, Brand, and Dynamic Macro Breakdown (Cal, P, C, F update instantaneously as quantity changes).
- **Unit Selector Carousel:** Grams (`g`), Milliliters (`ml`), Ounces (`oz`), Serving (`srv`), Slices, Cups, etc.
- **Custom Touch Numpad:**
  - Big mechanical touch targets (4x3 grid + fractional presets).
  - Zero layout bounce.
  - Live Scale indicator: if BLE scale is connected, a glowing scale badge shows `Weight: 142g [USE]`.

#### Screen D: Continuous Barcode Scanner
- Full-screen CameraX preview running at 60 FPS.
- Center target reticle with dynamic corner bracket animation.
- Flash toggle and manual barcode number fallback.
- Bottom floating Tray: stays docked while scanning. Scan 5 products in 5 seconds without touching the screen.

---

## 7. Performance Targets & Quality Attributes

| Metric | Target SLA | Verification Method |
| :--- | :--- | :--- |
| **App Cold Start Time** | < 450 ms | Android Jetpack Benchmark / Macrobenchmark |
| **App Warm Start Time** | < 150 ms | Macrobenchmark test |
| **Local Search Input-to-Render**| < 16 ms (1 frame) | Systrace / Perfetto trace on 120Hz display |
| **Barcode Recognition Latency** | < 180 ms | ML Kit frame analyzer benchmark |
| **Touch to Staged-Item in Tray**| < 50 ms | Frame timing instrumentation |
| **Memory Footprint** | < 90 MB baseline | Android Studio Memory Profiler |
| **Offline Capability** | 100% features work except remote library queries | Network link conditioner / Airplane mode tests |
| **Jank / Dropped Frames** | < 0.1% frame drops | Android Vitals / JankStats API |

---

## 8. Android Native Integrations & Hardware Acceleration

### 8.1 BLE Smart Kitchen Scale Integration
- Utilizes Android `BluetoothLeScanner` with low-latency scanning filters.
- Connects to standard GATT weight measurement services (e.g., standard BLE scale UUID `0x181D` and vendor-specific services for SmartChef / Decent Scale).
- Reads weight packets in real-time ($10\text{Hz}$ sample rate), filters scale jitter with moving average, and binds directly to the active Compose quantity state.

### 8.2 Home Screen Widgets & Shortcuts
- **App Shortcuts (Long-press app icon):**
  - "Barcode Scan" $\to$ Launches directly into CameraX scanner.
  - "Quick Add" $\to$ Opens instant macro numpad dialog.
  - "Log Current Meal" $\to$ Opens Fast-Search with time-contextual meal pre-selected.
- **Quick Settings Tile:**
  - Android Quick Settings Tile: "Quick Food Log" callable from notification shade from any screen.
- **Glance App Widget:**
  - Modern Jetpack Glance interactive home screen widget displaying daily macro rings with 1-tap "+" shortcuts.

---

## 9. Implementation Roadmap & Milestones

### Phase 1: Foundation & Project Scaffolding
- [ ] Initialize Android native Gradle project (Kotlin DSL, Version Catalogs `libs.versions.toml`).
- [ ] Configure Jetpack Compose BOM, Material 3, Navigation Compose, Hilt DI.
- [ ] Implement Network layer (Ktor/Retrofit, Better-Auth token storage in EncryptedSharedPreferences).
- [ ] Implement Room Database, initial entities, DAOs, and FTS5 virtual table.
- [ ] Implement Background Sync Engine using WorkManager.

### Phase 2: Core Diary & Sparky Server Sync
- [ ] Build Daily Diary screen with macro progress rings and meal lists.
- [ ] Implement Sparky Fitness API client endpoints for diary fetching and food CRUD.
- [ ] Build bidirectional optimistic sync pipeline with outbox queue.
- [ ] Add swipe-to-delete and swipe-to-duplicate gestures with haptic feedback.

### Phase 3: Fast-Search Engine & Multi-Add Staging Tray
- [ ] Implement Local FTS5 instant search engine with scoring and recency algorithms.
- [ ] Build the Multi-Add Staging Tray floating component.
- [ ] Create Custom In-App Numpad with unit conversion and fraction buttons.
- [ ] Build time-of-day predictive search suggestions on zero-character input.

### Phase 4: Continuous Barcode Scanner
- [ ] Implement CameraX viewfinder with ML Kit Barcode Analyzer.
- [ ] Build continuous burst mode with audio/haptic feedback and tray queueing.
- [ ] Wire up barcode lookup to local DB cache with fallback to Sparky Fitness OpenFoodFacts/Nutritionix API.

### Phase 5: Fast-Input Extras (Quick-Add, Voice, AI)
- [ ] Implement 1-Tap Quick-Add dialog with macro auto-balancer.
- [ ] Implement Natural Language Voice/Text input parser.
- [ ] Integrate Sparky AI Label Scan and Photo Estimation endpoints.
- [ ] Meal templates, copy meal from yesterday, and bulk multi-select operations.

### Phase 6: Hardware & Android System Integration
- [ ] Implement BLE Smart Kitchen Scale scanner and GATT stream receiver.
- [ ] Build Android App Shortcuts and Quick Settings Tile.
- [ ] Build Jetpack Glance Home Screen Widget.
- [ ] Fine-tune Android 14+ / 15+ Predictive Back animations and Edge-to-Edge display.

### Phase 7: Optimization, Performance Profiling & Hardening
- [ ] Run Macrobenchmark tests to guarantee < 16ms search and 120 FPS rendering.
- [ ] Memory profiling and baseline profile generation (`androidx.profileinstaller`).
- [ ] Comprehensive offline fault-injection testing.
- [ ] Final end-to-end integration verification with production Sparky Fitness backend.
