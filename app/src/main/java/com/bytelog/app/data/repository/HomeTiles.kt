package com.bytelog.app.data.repository

/** How wide a home tile is, in quarters of the screen. */
enum class TileSize(val code: String, val span: Int, val label: String) {
    FULL("f", 4, "Full width"),
    HALF("h", 2, "Half width"),
    SMALL("s", 1, "Small");

    companion object {
        fun fromCode(code: String?): TileSize? = values().firstOrNull { it.code == code }
    }
}

/** Everything that can go on the home screen, in its default order. */
enum class HomeTile(
    val key: String,
    val title: String,
    val description: String,
    val defaultSize: TileSize = TileSize.FULL,
    /** Meals is the diary itself: it stays, at full width, wherever it's moved. */
    val fixed: Boolean = false
) {
    CALORIES("calories", "Calories", "Calories and macros eaten against your goals"),
    WATER("water", "Water", "What you've drunk, with one-tap logging"),
    WEIGHT("weight", "Weight", "Today's weight and the recent trend"),
    WEEKLY_REVIEW("weekly_review", "Your week", "Monday's look back at the last seven days"),
    FASTING("fasting", "Fasting", "Active fast timer and quick start/end"),
    CAFFEINE("caffeine", "Caffeine", "Active caffeine and quick log"),
    REPORTS("reports", "Reports", "Nutrition, exercise and body reports", TileSize.SMALL),
    PROGRESS_PHOTOS("progress_photos", "Photos", "Progress photos", TileSize.SMALL),
    MEALIE("mealie", "Mealie", "Recipes from your Mealie server", TileSize.SMALL),
    HEALTH_CONNECT("health_connect", "Health", "Health Connect sync", TileSize.SMALL),
    LEFTOVERS("fridge", "Leftovers", "Batches in the fridge; one tap logs a portion"),
    FAMILY("family", "Family", "What everyone has eaten today"),
    PLANNED("planned", "Planned", "Food planned for the day"),
    MEALS("meals", "Meals", "The day's logged meals", fixed = true),
    MEAL_PLAN("meal_plan", "Meal plan", "The days ahead at a glance"),
    SHOPPING("shopping", "Shopping", "What to buy for the planned week"),
    WHAT_FITS("what_fits", "What fits?", "Foods that fit what's left today"),
    QUICK_LOG("quick_meals", "Log a meal", "Yesterday's and usual meals, templates"),
    TRENDS("weight_chart", "Trends", "Weight against calories, and TDEE");

    val sizes: List<TileSize> get() = if (fixed) listOf(TileSize.FULL) else TileSize.values().toList()
    val removable: Boolean get() = !fixed

    /** The next size a tap in edit mode picks: full, half, small, then back round. */
    fun nextSize(current: TileSize): TileSize {
        val all = sizes
        return all[(all.indexOf(current).coerceAtLeast(0) + 1) % all.size]
    }

    companion object {
        fun fromKey(key: String): HomeTile? = values().firstOrNull { it.key == key }
    }
}

/** A tile on the home screen at a size. */
data class PlacedTile(val tile: HomeTile, val size: TileSize)

/**
 * The home dashboard: the tiles in order with their sizes, and which are hidden (kept with their size, so adding one
 * back brings it back as it was). Stored as "calories:f,water:h,-family:f" ("-" = hidden).
 */
data class HomeTiles(val order: List<PlacedTile>, val hidden: Set<HomeTile> = emptySet()) {
    val visible: List<PlacedTile> get() = order.filter { it.tile !in hidden }
    val hiddenTiles: List<PlacedTile> get() = order.filter { it.tile in hidden }

    fun sizeOf(tile: HomeTile): TileSize = order.firstOrNull { it.tile == tile }?.size ?: tile.defaultSize

    /** Moves [tile] to where [target] is among the visible tiles. */
    fun moveTo(tile: HomeTile, target: HomeTile): HomeTiles {
        if (tile == target) return this
        val list = order.toMutableList()
        val from = list.indexOfFirst { it.tile == tile }
        val to = list.indexOfFirst { it.tile == target }
        if (from < 0 || to < 0) return this
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    /** Moves the visible tile at [from] to [to] (indices among the visible tiles). */
    fun moveVisible(from: Int, to: Int): HomeTiles {
        val v = visible
        if (from !in v.indices || to !in v.indices || from == to) return this
        return moveTo(v[from].tile, v[to].tile)
    }

    fun resize(tile: HomeTile, size: TileSize): HomeTiles =
        if (size !in tile.sizes) this else copy(order = order.map { if (it.tile == tile) it.copy(size = size) else it })

    fun remove(tile: HomeTile): HomeTiles = if (!tile.removable) this else copy(hidden = hidden + tile)

    /** Shows a hidden tile again, at the end of the visible ones. */
    fun add(tile: HomeTile): HomeTiles {
        if (tile !in hidden) return this
        val placed = order.first { it.tile == tile }
        val rest = order.filter { it.tile != tile }
        val lastVisible = rest.indexOfLast { it.tile !in hidden }
        val list = rest.toMutableList().apply { add(lastVisible + 1, placed) }
        return HomeTiles(list, hidden - tile)
    }

    fun encode(): String = order.joinToString(",") { (if (it.tile in hidden) "-" else "") + it.tile.key + ":" + it.size.code }

    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = HomeTiles(HomeTile.values().map { PlacedTile(it, it.defaultSize) })

        /** Reads stored tiles; unknown keys are dropped and tiles added since they were saved go in at their default spot, hidden. */
        fun parse(raw: String?): HomeTiles {
            if (raw.isNullOrBlank()) return DEFAULT
            val order = mutableListOf<PlacedTile>()
            val hidden = mutableSetOf<HomeTile>()
            raw.split(',').forEach { token ->
                val t = token.trim()
                val tile = HomeTile.fromKey(t.removePrefix("-").substringBefore(':')) ?: return@forEach
                if (order.any { it.tile == tile }) return@forEach
                val size = TileSize.fromCode(t.substringAfter(':', "")).takeIf { it != null && it in tile.sizes } ?: tile.defaultSize
                order += PlacedTile(tile, size)
                if (t.startsWith("-") && tile.removable) hidden += tile
            }
            if (order.isEmpty()) return DEFAULT
            addMissing(order, hidden)
            return HomeTiles(order, hidden)
        }

        /**
         * The tiles for someone who arranged the old home sections: calories, water and weight on top as before,
         * then the sections in their order. "Reports & tools" becomes its four small tiles.
         */
        fun fromLayout(layout: HomeLayout): HomeTiles {
            val order = mutableListOf(
                PlacedTile(HomeTile.CALORIES, TileSize.FULL), PlacedTile(HomeTile.WATER, TileSize.FULL), PlacedTile(HomeTile.WEIGHT, TileSize.FULL)
            )
            val hidden = mutableSetOf<HomeTile>()
            layout.order.forEach { section ->
                val tiles = when (section) {
                    HomeSection.WELLNESS_HUB -> listOf(HomeTile.REPORTS, HomeTile.PROGRESS_PHOTOS, HomeTile.MEALIE, HomeTile.HEALTH_CONNECT)
                    else -> listOfNotNull(HomeTile.fromKey(section.key))
                }
                tiles.forEach { t ->
                    order += PlacedTile(t, t.defaultSize)
                    if (section in layout.hidden && t.removable) hidden += t
                }
            }
            addMissing(order, hidden)
            return HomeTiles(order, hidden)
        }

        private fun addMissing(order: MutableList<PlacedTile>, hidden: MutableSet<HomeTile>) {
            HomeTile.values().forEachIndexed { i, t ->
                if (order.none { it.tile == t }) {
                    order.add(i.coerceAtMost(order.size), PlacedTile(t, t.defaultSize))
                    if (t.removable) hidden += t
                }
            }
        }
    }
}

/** Tiles packed into rows four quarters wide, in order: a tile that doesn't fit what's left of a row starts the next. */
object TileGrid {
    const val COLUMNS = 4

    fun rows(tiles: List<PlacedTile>): List<List<PlacedTile>> {
        val rows = mutableListOf<MutableList<PlacedTile>>()
        var used = COLUMNS
        tiles.forEach { t ->
            if (used + t.size.span > COLUMNS) { rows += mutableListOf<PlacedTile>(); used = 0 }
            rows.last() += t
            used += t.size.span
        }
        return rows
    }

    /**
     * How the folding tiles at the top sit once folded into slim strips: all in one row when they already fit one,
     * otherwise the first keeps its width and the rest are at most half width (a full card and two halves).
     */
    fun foldedSpans(spans: List<Int>): List<Int> =
        if (spans.sum() <= COLUMNS) spans else spans.mapIndexed { i, s -> if (i == 0) s else minOf(s, 2) }

    /** [spans] packed into rows, as (row, first column) for each. */
    fun place(spans: List<Int>): List<Pair<Int, Int>> {
        var row = -1; var used = COLUMNS
        return spans.map { s ->
            if (used + s > COLUMNS) { row++; used = 0 }
            val at = row to used
            used += s
            at
        }
    }

    /** How many tiles at the top fold into strips: up to three, stopping at Meals. */
    fun foldCount(tiles: List<PlacedTile>): Int = tiles.take(3).indexOfFirst { it.tile.fixed }.let { if (it < 0) minOf(3, tiles.size) else it }
}
