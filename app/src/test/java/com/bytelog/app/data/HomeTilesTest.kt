package com.bytelog.app.data

import com.bytelog.app.data.repository.HomeLayout
import com.bytelog.app.data.repository.HomeSection
import com.bytelog.app.data.repository.HomeTile
import com.bytelog.app.data.repository.HomeTiles
import com.bytelog.app.data.repository.PlacedTile
import com.bytelog.app.data.repository.TileGrid
import com.bytelog.app.data.repository.TileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTilesTest {
    private fun t(tile: HomeTile, size: TileSize) = PlacedTile(tile, size)

    @Test fun tilesSurviveSavingAndLoading() {
        val tiles = HomeTiles.DEFAULT
            .resize(HomeTile.WATER, TileSize.HALF)
            .resize(HomeTile.WEIGHT, TileSize.SMALL)
            .moveTo(HomeTile.TRENDS, HomeTile.CALORIES)
            .remove(HomeTile.FAMILY)
        assertEquals(tiles, HomeTiles.parse(tiles.encode()))
        assertTrue(tiles.encode().startsWith("weight_chart:f,calories:f,water:h,weight:s"))
        assertTrue("-family:f" in tiles.encode())
    }

    @Test fun nothingOrNonsenseStoredIsTheDefault() {
        assertEquals(HomeTiles.DEFAULT, HomeTiles.parse(null))
        assertEquals(HomeTiles.DEFAULT, HomeTiles.parse(""))
        assertEquals(HomeTiles.DEFAULT, HomeTiles.parse("nope,,also:x"))
        assertTrue(HomeTiles.DEFAULT.isDefault)
    }

    @Test fun mealsCannotBeRemovedOrResized() {
        val tiles = HomeTiles.DEFAULT.remove(HomeTile.MEALS).resize(HomeTile.MEALS, TileSize.HALF)
        assertEquals(HomeTiles.DEFAULT, tiles)
        assertEquals(TileSize.FULL, HomeTiles.parse("-meals:s").sizeOf(HomeTile.MEALS))
        assertTrue(HomeTiles.parse("-meals:s").visible.any { it.tile == HomeTile.MEALS })
        assertEquals(TileSize.FULL, HomeTile.MEALS.nextSize(TileSize.FULL))
    }

    @Test fun tappingCyclesFullHalfSmall() {
        assertEquals(TileSize.HALF, HomeTile.WATER.nextSize(TileSize.FULL))
        assertEquals(TileSize.SMALL, HomeTile.WATER.nextSize(TileSize.HALF))
        assertEquals(TileSize.FULL, HomeTile.WATER.nextSize(TileSize.SMALL))
    }

    @Test fun aRemovedTileComesBackAtTheEndWithItsSize() {
        val tiles = HomeTiles.DEFAULT.resize(HomeTile.FASTING, TileSize.SMALL).remove(HomeTile.FASTING)
        assertFalse(tiles.visible.any { it.tile == HomeTile.FASTING })
        val back = tiles.add(HomeTile.FASTING)
        assertEquals(t(HomeTile.FASTING, TileSize.SMALL), back.visible.last())
    }

    @Test fun tilesAddedInALaterVersionArriveHidden() {
        val tiles = HomeTiles.parse("meals:f,calories:f")
        assertEquals(listOf(HomeTile.MEALS, HomeTile.CALORIES), tiles.visible.map { it.tile })
        assertEquals(HomeTile.values().size, tiles.order.size)
    }

    @Test fun theOldHomeLayoutBecomesTiles() {
        assertEquals(HomeTiles.DEFAULT, HomeTiles.fromLayout(HomeLayout.DEFAULT))
        val old = HomeLayout.DEFAULT.move(HomeSection.values().indexOf(HomeSection.TRENDS), 0)
            .toggle(HomeSection.WELLNESS_HUB, visible = false).toggle(HomeSection.FAMILY, visible = false)
        val tiles = HomeTiles.fromLayout(old)
        assertEquals(listOf(HomeTile.CALORIES, HomeTile.WATER, HomeTile.WEIGHT, HomeTile.TRENDS), tiles.visible.take(4).map { it.tile })
        listOf(HomeTile.REPORTS, HomeTile.PROGRESS_PHOTOS, HomeTile.MEALIE, HomeTile.HEALTH_CONNECT, HomeTile.FAMILY).forEach {
            assertFalse(tiles.visible.any { v -> v.tile == it })
        }
    }

    @Test fun tilesPackIntoRowsInOrder() {
        val rows = TileGrid.rows(listOf(
            t(HomeTile.CALORIES, TileSize.FULL), t(HomeTile.WATER, TileSize.HALF), t(HomeTile.REPORTS, TileSize.SMALL),
            t(HomeTile.MEALIE, TileSize.SMALL), t(HomeTile.WEIGHT, TileSize.HALF), t(HomeTile.FASTING, TileSize.FULL), t(HomeTile.CAFFEINE, TileSize.SMALL)
        ))
        assertEquals(listOf(1, 3, 1, 1, 1), rows.map { it.size })
        assertEquals(HomeTile.WEIGHT, rows[2].single().tile)        // a half that doesn't fit starts a new row
    }

    @Test fun theTopFoldsIntoAFullStripAndTwoHalves() {
        assertEquals(listOf(4, 2, 2), TileGrid.foldedSpans(listOf(4, 4, 4)))
        assertEquals(listOf(2, 2, 2), TileGrid.foldedSpans(listOf(2, 2, 4)))
        assertEquals(listOf(1, 1, 2), TileGrid.foldedSpans(listOf(1, 1, 2)))   // already one row: stays as it is
        assertEquals(listOf((0 to 0), (1 to 0), (1 to 2)), TileGrid.place(listOf(4, 2, 2)))
    }

    @Test fun onlyTilesAboveMealsFold() {
        val tiles = listOf(t(HomeTile.CALORIES, TileSize.FULL), t(HomeTile.MEALS, TileSize.FULL), t(HomeTile.WATER, TileSize.FULL))
        assertEquals(1, TileGrid.foldCount(tiles))
        assertEquals(0, TileGrid.foldCount(listOf(t(HomeTile.MEALS, TileSize.FULL))))
        assertEquals(3, TileGrid.foldCount(HomeTiles.DEFAULT.visible))
    }
}
