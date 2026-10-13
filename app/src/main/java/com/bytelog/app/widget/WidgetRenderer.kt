package com.bytelog.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import com.bytelog.app.ui.theme.ByteExtraColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The widgets, with the size (in dp) their artwork is drawn at; other sizes scale it. */
enum class WidgetKind(val baseW: Float, val baseH: Float) {
    TODAY(358f, 170f), CALORIES(170f, 170f), WATER(170f, 170f), QUICK_LOG(358f, 84f), FAMILY(358f, 170f)
}

/** Colours a widget is drawn with, taken from the app's colour scheme (device colours when allowed). */
class WidgetColors(
    val bg: Int, val text: Int, val muted: Int, val primary: Int, val onPrimary: Int, val pc: Int, val onPc: Int,
    val track: Int, val hi: Int, val water: Int, val onWater: Int, val glass: Int, val onGlass: Int,
    val scan: Int, val onScan: Int, val protein: Int, val carbs: Int, val fat: Int, val avatarAccents: List<Int>
) {
    companion object {
        fun from(cs: ColorScheme, x: ByteExtraColors, dark: Boolean): WidgetColors = WidgetColors(
            bg = cs.surfaceContainerHigh.toArgb(), text = cs.onSurface.toArgb(), muted = cs.onSurfaceVariant.toArgb(),
            primary = cs.primary.toArgb(), onPrimary = cs.onPrimary.toArgb(),
            pc = cs.primaryContainer.toArgb(), onPc = cs.onPrimaryContainer.toArgb(),
            track = cs.surfaceContainerHighest.toArgb(), hi = cs.surfaceContainerHighest.toArgb(),
            water = x.water.toArgb(), onWater = (if (dark) Color(0xFF003549) else Color.White).toArgb(),
            glass = x.waterContainer.toArgb(), onGlass = x.onWaterContainer.toArgb(),
            scan = x.waterContainer.toArgb(), onScan = x.onWaterContainer.toArgb(),
            protein = x.protein.toArgb(), carbs = x.carbs.toArgb(), fat = x.fat.toArgb(),
            avatarAccents = listOf(cs.primary.toArgb(), x.protein.toArgb(), x.carbs.toArgb(), x.fat.toArgb())
        )
    }
}

/** Draws the home screen widgets as bitmaps, so they look the same as the app's own cards on any launcher. */
object WidgetRenderer {
    private const val CORNER = 28f

    fun render(kind: WidgetKind, data: WidgetData, c: WidgetColors, widthPx: Int, heightPx: Int): Bitmap {
        val w = max(1, widthPx); val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val sc = min(w / kind.baseW, h / kind.baseH)
        val dx = (w - kind.baseW * sc) / 2f
        val dy = (h - kind.baseH * sc) / 2f
        val radius = CORNER * sc

        // The tile itself; everything after is clipped to its rounded corners
        val tile = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) }
        canvas.clipPath(tile)
        canvas.drawColor(if (kind == WidgetKind.WATER) c.glass else c.bg)

        if (kind == WidgetKind.WATER) drawWaterFill(canvas, data, c, w, h, sc)
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(sc, sc)
        when (kind) {
            WidgetKind.TODAY -> today(canvas, data, c)
            WidgetKind.CALORIES -> calories(canvas, data, c)
            WidgetKind.WATER -> water(canvas, data, c)
            WidgetKind.QUICK_LOG -> quickLog(canvas, data, c)
            WidgetKind.FAMILY -> family(canvas, data, c)
        }
        canvas.restore()
        return bmp
    }

    // ----- Today 4x2 -----

    private fun today(cv: Canvas, d: WidgetData, c: WidgetColors) {
        val frac = fraction(d.eaten, d.goal)
        ring(cv, 70f, 66f, 46f, 7f, 34f, frac, 14, 2f, c)
        cv.text(number(d.eaten), 70f, 68f, 24f, 900, c.onPc, Paint.Align.CENTER)
        cv.text("OF ${number(d.goal)} KCAL", 70f, 82f, 9f, 700, c.onPc, Paint.Align.CENTER, 0.5f)

        val rows = listOf(Triple("PROTEIN", d.protein to d.proteinGoal, c.protein), Triple("CARBS", d.carbs to d.carbsGoal, c.carbs), Triple("FAT", d.fat to d.fatGoal, c.fat))
        rows.forEachIndexed { i, (label, vg, color) ->
            val y0 = 12f + i * 34f
            cv.text(label, 140f, y0 + 10f, 10f, 700, c.muted, Paint.Align.LEFT, 0.6f)
            val goalText = " / ${vg.second} g"
            cv.text(goalText, 344f, y0 + 11f, 11f, 400, c.muted, Paint.Align.RIGHT)
            cv.text("${vg.first}", 344f - textWidth(goalText, 11f, 400), y0 + 11f, 11f, 700, c.text, Paint.Align.RIGHT)
            val by = y0 + 20f
            cv.line(140f, by, 344f, by, 4f, c.track)
            cv.wavyBar(140f, by, 204f, 1.6f, 14f, fraction(vg.first, vg.second), 4f, color)
        }

        // water: one pill that fills as you drink, with the add button built in
        val pill = RectF(14f, 120f, 204f, 158f)
        val pillPath = Path().apply { addRoundRect(pill, 19f, 19f, Path.Direction.CW) }
        cv.save(); cv.clipPath(pillPath)
        cv.drawRect(pill, fill(c.glass))
        cv.drawRect(RectF(pill.left, pill.top, pill.left + pill.width() * fraction(d.waterOz.toInt(), d.waterGoalOz.toInt(), floor = 0f), pill.bottom), fill(withAlpha(c.water, 0.38f)))
        cv.restore()
        cv.icon(DROP, 34f, 139f, 16f, c.onGlass, 2.2f)
        cv.text("${d.waterOz.toInt()} / ${d.waterGoalOz.toInt()} oz", 48f, 144f, 13f, 700, c.onGlass)
        cv.drawCircle(185f, 139f, 14f, fill(c.water))
        cv.icon(PLUS, 185f, 139f, 14f, c.onWater, 2.6f)

        val add = RectF(212f, 120f, 344f, 158f)
        cv.drawRoundRect(add, 19f, 19f, fill(c.primary))
        val tw = textWidth("Add food", 14f, 700)
        val gx = add.centerX() - (16f + 6f + tw) / 2f
        cv.icon(PLUS, gx + 8f, 139f, 16f, c.onPrimary, 2.6f)
        cv.text("Add food", gx + 22f, 144f, 14f, 700, c.onPrimary)
    }

    // ----- Calories 2x2 -----

    private fun calories(cv: Canvas, d: WidgetData, c: WidgetColors) {
        ring(cv, 85f, 76f, 55f, 8f, 42f, fraction(d.eaten, d.goal), 16, 2.2f, c)
        cv.text(number(d.eaten), 85f, 83f, 30f, 900, c.onPc, Paint.Align.CENTER)
        cv.text("OF ${number(d.goal)} KCAL", 85f, 98f, 10f, 700, c.onPc, Paint.Align.CENTER, 0.5f)
        cv.text("${number(d.left)} left", 85f, 156f, 13f, 700, c.text, Paint.Align.CENTER)
    }

    // ----- Water 2x2: the whole tile is the glass -----

    private fun level(d: WidgetData) = if (d.waterGoalOz > 0f) (d.waterOz / d.waterGoalOz).coerceIn(0f, 1f) else 0f

    /** Water rising from the bottom of the tile, drawn in device pixels so it fills any size of widget. */
    private fun drawWaterFill(cv: Canvas, d: WidgetData, c: WidgetColors, w: Int, h: Int, sc: Float) {
        val top = h - level(d) * h
        val p = Path().apply {
            moveTo(0f, top)
            var x = 0f
            while (x <= w) { lineTo(x, top + 3f * sc * sin(x / w * 2 * 2 * PI).toFloat()); x += 3f }
            lineTo(w + 2f, top); lineTo(w + 2f, h + 2f); lineTo(0f, h + 2f); close()
        }
        cv.drawPath(p, fill(c.water))
    }

    private fun water(cv: Canvas, d: WidgetData, c: WidgetColors) {
        val lv = level(d)
        val numberColor = if (lv > 0.5f) c.onWater else c.onGlass
        val labelColor = if (lv > 0.9f) c.onWater else c.onGlass
        cv.text("WATER", 16f, 24f, 10f, 900, labelColor, Paint.Align.LEFT, 1f)
        cv.text("${d.waterOz.toInt()}", 85f, 96f, 40f, 900, numberColor, Paint.Align.CENTER)
        cv.text("OF ${d.waterGoalOz.toInt()} OZ", 85f, 114f, 11f, 700, numberColor, Paint.Align.CENTER, 0.4f)
        val badge = if (lv > 0.2f) c.onWater else c.water
        val icon = if (lv > 0.2f) c.water else c.onWater
        cv.drawCircle(138f, 138f, 20f, fill(badge))
        cv.icon(PLUS, 138f, 138f, 20f, icon, 2.8f)
    }

    // ----- Quick log 4x1 -----

    private fun quickLog(cv: Canvas, d: WidgetData, c: WidgetColors) {
        val items = listOf("Breakfast" to BREAKFAST, "Lunch" to LUNCH, "Dinner" to DINNER, "Snack" to SNACK, "Scan" to SCAN)
        val gap = (338f - items.size * 62f) / items.size
        items.forEachIndexed { i, (label, icon) ->
            val x = 10f + gap / 2f + i * (62f + gap)
            val hot = label == d.suggestedMeal
            val scan = label == "Scan"
            val bg = if (hot) c.primary else if (scan) c.scan else c.hi
            val fg = if (hot) c.onPrimary else if (scan) c.onScan else c.text
            val r = if (hot) 16f else 22f
            cv.drawRoundRect(RectF(x + 9f, 11f, x + 53f, 55f), r, r, fill(bg))
            cv.icon(icon, x + 31f, 33f, 22f, fg, 2f)
            cv.text(label, x + 31f, 70f, 10.5f, if (hot) 700 else 500, c.text, Paint.Align.CENTER)
        }
    }

    // ----- Family 4x2 -----

    private fun family(cv: Canvas, d: WidgetData, c: WidgetColors) {
        cv.text("FAMILY TODAY", 18f, 23f, 11f, 900, c.primary, Paint.Align.LEFT, 1f)
        cv.icon(CHEVRON, 335f, 21f, 18f, c.muted, 2.4f)
        val people = d.family.take(4)
        val gap = (338f - 4 * 78f) / 4f
        people.forEachIndexed { i, p ->
            val cx = 10f + gap / 2f + i * (78f + gap) + 39f
            val accent = c.avatarAccents[i % c.avatarAccents.size]
            val cy = 36f + 27f
            val rect = RectF(cx - 23f, cy - 23f, cx + 23f, cy + 23f)
            cv.drawArc(rect, 0f, 360f, false, stroke(c.track, 4.5f))
            cv.drawArc(rect, -90f, 360f * fraction(p.kcal, p.goal, floor = 0f), false, stroke(accent, 4.5f))
            cv.drawCircle(cx, cy, 20f, fill(ColorUtils.blendARGB(c.bg, accent, 0.28f)))
            cv.text(p.name.take(1).uppercase(Locale.getDefault()), cx, cy + 6f, 16f, 900, c.text, Paint.Align.CENTER)
            cv.text(p.name, cx, 104f, 11f, 700, c.text, Paint.Align.CENTER)
            cv.text("${number(p.kcal)} kcal", cx, 118f, 10.5f, 400, c.muted, Paint.Align.CENTER)
        }
        val btn = RectF(14f, 124f, 344f, 158f)
        cv.drawRoundRect(btn, 17f, 17f, fill(c.pc))
        val label = "Split ${d.suggestedMeal.lowercase(Locale.getDefault())} with family"
        val tw = textWidth(label, 13f, 700)
        val gx = btn.centerX() - (18f + 7f + tw) / 2f
        cv.icon(PEOPLE, gx + 9f, 141f, 18f, c.onPc, 2.2f)
        cv.text(label, gx + 25f, 146f, 13f, 700, c.onPc)
    }

    // ----- shared drawing -----

    /** A progress ring: plain track, a solid inner disc, and a wavy arc for the progress. */
    private fun ring(cv: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, discR: Float, frac: Float, waves: Int, amp: Float, c: WidgetColors) {
        cv.drawCircle(cx, cy, r, stroke(c.track, stroke))
        cv.drawCircle(cx, cy, discR, fill(c.pc))
        val n = max(2, (120 * frac).toInt())
        val path = Path()
        for (i in 0..n) {
            val a = i.toFloat() / n * frac
            val t = -PI / 2 + a * 2 * PI
            val rr = r + amp * sin(a * waves * 2 * PI)
            val x = (cx + rr * cos(t)).toFloat(); val y = (cy + rr * sin(t)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        cv.drawPath(path, stroke(c.primary, stroke))
    }

    private fun Canvas.wavyBar(x: Float, cy: Float, width: Float, amp: Float, wl: Float, frac: Float, stroke: Float, color: Int) {
        val end = max(2f, width * frac)
        val p = Path()
        var px = 0f
        while (px <= end) {
            val y = cy + amp * sin(px / wl * 2 * PI).toFloat()
            if (px == 0f) p.moveTo(x + px, y) else p.lineTo(x + px, y)
            px += 2f
        }
        drawPath(p, stroke(color, stroke))
    }

    private fun Canvas.line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, color: Int) = drawLine(x1, y1, x2, y2, stroke(color, w))

    private fun fraction(v: Int, goal: Int, floor: Float = 0.02f): Float = if (goal <= 0) floor else (v.toFloat() / goal).coerceIn(floor, 1f)

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun withAlpha(color: Int, a: Float) = ColorUtils.setAlphaComponent(color, (a * 255).toInt())
    private fun number(n: Int) = String.format(Locale.getDefault(), "%,d", n)

    private fun typeface(weight: Int): Typeface = when {
        weight >= 900 -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        weight >= 700 -> Typeface.create("sans-serif", Typeface.BOLD)
        weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun textPaint(size: Float, weight: Int, color: Int, align: Paint.Align, spacing: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; textAlign = align; typeface = typeface(weight); letterSpacing = spacing / max(1f, size)
    }

    private fun textWidth(s: String, size: Float, weight: Int) = textPaint(size, weight, 0, Paint.Align.LEFT, 0f).measureText(s)

    private fun Canvas.text(s: String, x: Float, y: Float, size: Float, weight: Int, color: Int, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) =
        drawText(s, x, y, textPaint(size, weight, color, align, spacing))

    /** A 24-unit stroke icon (the same drawings as the mockups) centred at ([cx], [cy]) at [size]. */
    private fun Canvas.icon(pathData: String, cx: Float, cy: Float, size: Float, color: Int, strokeWidth: Float) {
        val path = PathParser.createPathFromPathData(pathData)
        save(); translate(cx - size / 2f, cy - size / 2f); scale(size / 24f, size / 24f)
        drawPath(path, stroke(color, strokeWidth))
        restore()
    }

    private const val PLUS = "M12 5v14M5 12h14"
    private const val DROP = "M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z"
    private const val CHEVRON = "M9 6l6 6-6 6"
    private const val BREAKFAST = "M5 9h11v5a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4V9zM16 10h1.5a2.5 2.5 0 0 1 0 5H16M8 3v3M12 3v3"
    private const val LUNCH = "M7 3v7a2 2 0 0 0 2 2v9M5 3v5M9 3v5M17 3c-2 1.5-3 4-3 7h3v11"
    private const val DINNER = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"
    private const val SNACK = "M12 7c-2-1.5-6-1-6 4 0 4 2.5 9 6 9s6-5 6-9c0-5-4-5.5-6-4zM12 7c0-2 1-3.5 3-4"
    private const val SCAN = "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M7 12h10"
    private const val PEOPLE = "M12 8a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM17 9a2.4 2.4 0 1 1-4.8 0A2.4 2.4 0 0 1 17 9zM3 19c0-3.2 2.7-5.5 6-5.5s6 2.3 6 5.5M16 14c2.6 0 5 1.6 5 4.5"
}
