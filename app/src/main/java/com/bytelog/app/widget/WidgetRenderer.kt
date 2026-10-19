package com.bytelog.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Build
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
    val scan: Int, val onScan: Int, val protein: Int, val carbs: Int, val fat: Int, val avatarAccents: List<Int>,
    /** Living Flow: the tile's soft gradient, the blooms of light in it, frosted panels and their luminous edge. */
    val bgTop: Int = bg, val bgBottom: Int = bg, val bloom: Int = primary, val bloom2: Int = water,
    val panel: Int = hi, val edgeTop: Int = hi, val edgeBottom: Int = hi, val primaryLight: Int = primary,
    val waterLight: Int = water, val dark: Boolean = false
) {
    companion object {
        fun from(cs: ColorScheme, x: ByteExtraColors, dark: Boolean): WidgetColors = WidgetColors(
            bg = cs.surfaceContainerHigh.toArgb(), text = cs.onSurface.toArgb(), muted = cs.onSurfaceVariant.toArgb(),
            primary = cs.primary.toArgb(), onPrimary = cs.onPrimary.toArgb(),
            pc = cs.primaryContainer.toArgb(), onPc = cs.onPrimaryContainer.toArgb(),
            track = (if (dark) Color(0x24FFFFFF) else Color(0x14000000)).toArgb(),
            hi = (if (dark) Color(0x1FFFFFFF) else Color(0xB3FFFFFF)).toArgb(),
            water = x.water.toArgb(), onWater = (if (dark) Color(0xFF003549) else Color.White).toArgb(),
            glass = (if (dark) Color(0xFF14212A) else Color(0xFFEAF4FB)).toArgb(), onGlass = x.onWaterContainer.toArgb(),
            scan = x.waterContainer.copy(alpha = 0.85f).toArgb(), onScan = x.onWaterContainer.toArgb(),
            protein = x.protein.toArgb(), carbs = x.carbs.toArgb(), fat = x.fat.toArgb(),
            avatarAccents = listOf(cs.primary.toArgb(), x.protein.toArgb(), x.carbs.toArgb(), x.fat.toArgb()),
            bgTop = (if (dark) Color(0xFF1B191D) else Color(0xFFFFF6F2)).toArgb(),
            bgBottom = (if (dark) Color(0xFF111216) else Color(0xFFEEF1F7)).toArgb(),
            bloom = cs.primary.copy(alpha = if (dark) 0.26f else 0.22f).toArgb(),
            bloom2 = x.water.copy(alpha = if (dark) 0.20f else 0.18f).toArgb(),
            panel = (if (dark) Color(0x17FFFFFF) else Color(0x9EFFFFFF)).toArgb(),
            edgeTop = (if (dark) Color(0x38FFFFFF) else Color(0xE6FFFFFF)).toArgb(),
            edgeBottom = (if (dark) Color(0x0AFFFFFF) else Color(0x40FFFFFF)).toArgb(),
            primaryLight = ColorUtils.blendARGB(cs.primary.toArgb(), Color.White.toArgb(), if (dark) 0.18f else 0.22f),
            waterLight = ColorUtils.blendARGB(x.water.toArgb(), Color.White.toArgb(), 0.35f),
            dark = dark
        )
    }
}

/**
 * Draws the home screen widgets as bitmaps, so they look the same as the app's own cards on any launcher: a
 * soft gradient tile with blooms of light, frosted panels with a luminous edge, smooth glowing progress and
 * gradient accent buttons (the app's Living Flow look).
 */
object WidgetRenderer {
    private const val CORNER = 24f
    /** Just short of opaque, so the wallpaper tints the tile a little, like frosted glass. */
    private const val TILE_ALPHA = 0.94f

    fun render(kind: WidgetKind, data: WidgetData, c: WidgetColors, widthPx: Int, heightPx: Int): Bitmap {
        val w = max(1, widthPx); val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val sc = min(w / kind.baseW, h / kind.baseH)
        val dx = (w - kind.baseW * sc) / 2f
        val dy = (h - kind.baseH * sc) / 2f
        val radius = CORNER * sc

        // The tile itself; everything after is clipped to its rounded corners
        val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val tile = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(tile)
        backdrop(canvas, kind, c, w.toFloat(), h.toFloat())
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
        canvas.restore()
        // The luminous edge, brighter at the top
        val e = sc.coerceAtLeast(1f)
        canvas.drawRoundRect(RectF(e / 2f, e / 2f, w - e / 2f, h - e / 2f), radius - e / 2f, radius - e / 2f, edgePaint(c, h.toFloat(), e))
        return bmp
    }

    /** The tile: a soft vertical gradient with two blooms of light (accent and water), slightly see-through. */
    private fun backdrop(cv: Canvas, kind: WidgetKind, c: WidgetColors, w: Float, h: Float) {
        val a = (TILE_ALPHA * 255).toInt()
        if (kind == WidgetKind.WATER) {
            cv.drawColor(ColorUtils.setAlphaComponent(c.glass, a))
            bloom(cv, w * 0.1f, h * 0.05f, w * 0.9f, ColorUtils.setAlphaComponent(Color.White.toArgb(), if (c.dark) 18 else 120))
            return
        }
        cv.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, ColorUtils.setAlphaComponent(c.bgTop, a), ColorUtils.setAlphaComponent(c.bgBottom, a), Shader.TileMode.CLAMP)
        })
        val r = max(w, h)
        bloom(cv, w * 0.02f, h * 0.0f, r * 0.62f, c.bloom)
        bloom(cv, w * 1.0f, h * 1.0f, r * 0.58f, c.bloom2)
    }

    private fun bloom(cv: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        if (r <= 0f) return
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r, color, ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP)
        })
    }

    private fun edgePaint(c: WidgetColors, h: Float, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = width
        shader = LinearGradient(0f, 0f, 0f, h, c.edgeTop, c.edgeBottom, Shader.TileMode.CLAMP)
    }

    /** A frosted panel: translucent white (or a faint light in dark mode) with a luminous edge. */
    private fun Canvas.glassPanel(r: RectF, radius: Float, c: WidgetColors, tint: Int? = null) {
        drawRoundRect(r, radius, radius, fill(tint ?: c.panel))
        val e = 1f
        drawRoundRect(RectF(r.left + e / 2, r.top + e / 2, r.right - e / 2, r.bottom - e / 2), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = e
            shader = LinearGradient(0f, r.top, 0f, r.bottom, c.edgeTop, c.edgeBottom, Shader.TileMode.CLAMP)
        })
    }

    /** The accent button: a diagonal gradient with a soft glow under it and a bright top edge. */
    private fun Canvas.accentPill(r: RectF, radius: Float, c: WidgetColors, from: Int = c.primaryLight, to: Int = c.primary) {
        drawRoundRect(RectF(r.left + 4f, r.top + 6f, r.right - 4f, r.bottom + 3f), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.Transparent.toArgb()
            setShadowLayer(9f, 0f, 2f, ColorUtils.setAlphaComponent(to, if (c.dark) 110 else 90))
        })
        drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.bottom, from, to, Shader.TileMode.CLAMP)
        })
        drawRoundRect(RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f
            shader = LinearGradient(0f, r.top, 0f, r.bottom, ColorUtils.setAlphaComponent(Color.White.toArgb(), 110), ColorUtils.setAlphaComponent(Color.White.toArgb(), 0), Shader.TileMode.CLAMP)
        })
    }

    // ----- Today 4x2 -----

    private fun today(cv: Canvas, d: WidgetData, c: WidgetColors) {
        val frac = fraction(d.eaten, d.goal)
        ring(cv, 70f, 64f, 46f, 9f, frac, c.primary, c)
        cv.text(number(d.eaten), 70f, 68f, 25f, 600, c.text, Paint.Align.CENTER)
        cv.text("of ${number(d.goal)} kcal", 70f, 82f, 9.5f, 500, c.muted, Paint.Align.CENTER)

        val rows = listOf(Triple("Protein", d.protein to d.proteinGoal, c.protein), Triple("Carbs", d.carbs to d.carbsGoal, c.carbs), Triple("Fat", d.fat to d.fatGoal, c.fat))
        rows.forEachIndexed { i, (label, vg, color) ->
            val y0 = 12f + i * 33f
            cv.text(label, 140f, y0 + 10f, 11f, 600, c.muted, Paint.Align.LEFT)
            val goalText = " / ${vg.second} g"
            cv.text(goalText, 344f, y0 + 11f, 11f, 400, c.muted, Paint.Align.RIGHT)
            cv.text("${vg.first}", 344f - textWidth(goalText, 11f, 400), y0 + 11f, 11f, 600, c.text, Paint.Align.RIGHT)
            cv.flowBar(140f, y0 + 20f, 204f, 6f, fraction(vg.first, vg.second), color, c)
        }

        // How long since food was last eaten, under the macros
        d.fastingLabel?.let { cv.text(it, 344f, 112f, 10.5f, 600, c.muted, Paint.Align.RIGHT) }

        // water: one frosted pill that fills as you drink, with the add button built in
        val pill = RectF(14f, 120f, 204f, 158f)
        cv.glassPanel(pill, 19f, c)
        val pillPath = Path().apply { addRoundRect(pill, 19f, 19f, Path.Direction.CW) }
        cv.save(); cv.clipPath(pillPath)
        val wf = fraction(d.waterOz.toInt(), d.waterGoalOz.toInt(), floor = 0f)
        if (wf > 0f) cv.drawRect(RectF(pill.left, pill.top, pill.left + pill.width() * wf, pill.bottom), Paint().apply {
            shader = LinearGradient(pill.left, 0f, pill.left + pill.width() * wf, 0f, withAlpha(c.water, 0.12f), withAlpha(c.water, 0.42f), Shader.TileMode.CLAMP)
        })
        cv.restore()
        cv.icon(DROP, 34f, 139f, 16f, c.water, 2.2f)
        cv.text("${d.waterOz.toInt()} / ${d.waterGoalOz.toInt()} oz", 48f, 144f, 13f, 600, c.text)
        cv.accentPill(RectF(171f, 125f, 199f, 153f), 14f, c, c.waterLight, c.water)
        cv.icon(PLUS, 185f, 139f, 14f, c.onWater, 2.6f)

        val add = RectF(212f, 120f, 344f, 158f)
        cv.accentPill(add, 19f, c)
        val tw = textWidth("Add food", 14f, 600)
        val gx = add.centerX() - (16f + 6f + tw) / 2f
        cv.icon(PLUS, gx + 8f, 139f, 16f, c.onPrimary, 2.6f)
        cv.text("Add food", gx + 22f, 144f, 14f, 600, c.onPrimary)
    }

    // ----- Calories 2x2 -----

    private fun calories(cv: Canvas, d: WidgetData, c: WidgetColors) {
        ring(cv, 85f, 72f, 54f, 10f, fraction(d.eaten, d.goal), c.primary, c)
        cv.text(number(d.eaten), 85f, 78f, 30f, 600, c.text, Paint.Align.CENTER)
        cv.text("of ${number(d.goal)} kcal", 85f, 94f, 10f, 500, c.muted, Paint.Align.CENTER)
        val label = "${number(d.left)} left"
        val tw = textWidth(label, 12.5f, 600)
        cv.glassPanel(RectF(85f - tw / 2f - 14f, 138f, 85f + tw / 2f + 14f, 162f), 12f, c)
        cv.text(label, 85f, 154.5f, 12.5f, 600, c.text, Paint.Align.CENTER)
    }

    // ----- Water 2x2: the whole tile is the glass -----

    private fun level(d: WidgetData) = if (d.waterGoalOz > 0f) (d.waterOz / d.waterGoalOz).coerceIn(0f, 1f) else 0f

    /** Water rising from the bottom of the tile, drawn in device pixels so it fills any size of widget. */
    private fun drawWaterFill(cv: Canvas, d: WidgetData, c: WidgetColors, w: Int, h: Int, sc: Float) {
        val lv = level(d)
        if (lv <= 0f) return
        val top = h - lv * h
        val surface = Path()
        val p = Path().apply {
            moveTo(0f, top)
            var x = 0f
            while (x <= w) {
                val y = top + 3f * sc * sin(x / w * 2 * 2 * PI).toFloat()
                lineTo(x, y); if (x == 0f) surface.moveTo(x, y) else surface.lineTo(x, y)
                x += 3f
            }
            lineTo(w + 2f, top); lineTo(w + 2f, h + 2f); lineTo(0f, h + 2f); close()
        }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, h.toFloat(), c.waterLight, c.water, Shader.TileMode.CLAMP)
        })
        // A bright line of light along the surface
        cv.drawPath(surface, stroke(ColorUtils.setAlphaComponent(Color.White.toArgb(), 120), 1.6f * sc))
    }

    private fun water(cv: Canvas, d: WidgetData, c: WidgetColors) {
        val lv = level(d)
        val numberColor = if (lv > 0.5f) c.onWater else c.onGlass
        val labelColor = if (lv > 0.9f) c.onWater else c.onGlass
        cv.icon(DROP, 22f, 20f, 13f, labelColor, 2.2f)
        cv.text("Water", 32f, 24.5f, 12f, 600, labelColor, Paint.Align.LEFT)
        cv.text("${d.waterOz.toInt()}", 85f, 94f, 42f, 600, numberColor, Paint.Align.CENTER)
        cv.text("of ${d.waterGoalOz.toInt()} oz", 85f, 113f, 11.5f, 500, numberColor, Paint.Align.CENTER)
        // A frosted button floating on the water
        val badge = RectF(118f, 118f, 158f, 158f)
        if (lv > 0.2f) cv.glassPanel(badge, 20f, c, ColorUtils.setAlphaComponent(Color.White.toArgb(), if (c.dark) 60 else 150))
        else cv.accentPill(badge, 20f, c, c.waterLight, c.water)
        cv.icon(PLUS, 138f, 138f, 20f, if (lv > 0.2f) c.onGlass else c.onWater, 2.8f)
    }

    // ----- Quick log 4x1 -----

    private fun quickLog(cv: Canvas, d: WidgetData, c: WidgetColors) {
        val items = listOf("Breakfast" to BREAKFAST, "Lunch" to LUNCH, "Dinner" to DINNER, "Snack" to SNACK, "Scan" to SCAN)
        val gap = (338f - items.size * 62f) / items.size
        items.forEachIndexed { i, (label, icon) ->
            val x = 10f + gap / 2f + i * (62f + gap)
            val hot = label == d.suggestedMeal
            val scan = label == "Scan"
            val r = RectF(x + 9f, 11f, x + 53f, 55f)
            when {
                hot -> cv.accentPill(r, 22f, c)
                scan -> cv.glassPanel(r, 16f, c, c.scan)
                else -> cv.glassPanel(r, 16f, c, c.hi)
            }
            val fg = if (hot) c.onPrimary else if (scan) c.onScan else c.text
            cv.icon(icon, x + 31f, 33f, 22f, fg, 2f)
            cv.text(label, x + 31f, 71f, 10.5f, if (hot) 600 else 500, if (hot) c.text else c.muted, Paint.Align.CENTER)
        }
    }

    // ----- Family 4x2 -----

    private fun family(cv: Canvas, d: WidgetData, c: WidgetColors) {
        cv.text("Family today", 18f, 24f, 12.5f, 600, c.text, Paint.Align.LEFT)
        cv.icon(CHEVRON, 335f, 20f, 18f, c.muted, 2.4f)
        val people = d.family.take(4)
        val gap = (338f - 4 * 78f) / 4f
        people.forEachIndexed { i, p ->
            val cx = 10f + gap / 2f + i * (78f + gap) + 39f
            val accent = c.avatarAccents[i % c.avatarAccents.size]
            val cy = 36f + 27f
            ring(cv, cx, cy, 23f, 4.5f, fraction(p.kcal, p.goal, floor = 0f), accent, c)
            cv.drawCircle(cx, cy, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(cx, cy - 18f, cx, cy + 18f, withAlpha(accent, 0.30f), withAlpha(accent, 0.14f), Shader.TileMode.CLAMP)
            })
            cv.text(p.name.take(1).uppercase(Locale.getDefault()), cx, cy + 6f, 16f, 600, c.text, Paint.Align.CENTER)
            cv.text(p.name, cx, 104f, 11f, 600, c.text, Paint.Align.CENTER)
            cv.text("${number(p.kcal)} kcal", cx, 118f, 10.5f, 400, c.muted, Paint.Align.CENTER)
        }
        val btn = RectF(14f, 124f, 344f, 158f)
        cv.glassPanel(btn, 17f, c, ColorUtils.blendARGB(c.panel, withAlpha(c.primary, 1f), if (c.dark) 0.16f else 0.10f))
        val label = "Split ${d.suggestedMeal.lowercase(Locale.getDefault())} with family"
        val tw = textWidth(label, 13f, 600)
        val gx = btn.centerX() - (18f + 7f + tw) / 2f
        cv.icon(PEOPLE, gx + 9f, 141f, 18f, c.primary, 2.2f)
        cv.text(label, gx + 25f, 146f, 13f, 600, c.primary)
    }

    // ----- shared drawing -----

    /** Living Flow progress ring: a soft track, a smooth arc brightening towards its head, and a glow at the head. */
    private fun ring(cv: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, frac: Float, color: Int, c: WidgetColors) {
        cv.drawCircle(cx, cy, r, stroke(c.track, stroke))
        if (frac <= 0.001f) return
        val sweep = 360f * frac
        val paint = stroke(color, stroke).apply {
            // Start the gradient just behind the arc so its rounded start cap is the faint end, not the bright one
            val cap = (stroke / (2f * PI.toFloat() * r)) * 360f
            shader = SweepGradient(cx, cy, intArrayOf(withAlpha(color, 0.45f), color), floatArrayOf(0f, ((sweep + cap) / 360f).coerceIn(0.01f, 1f))).apply {
                setLocalMatrix(Matrix().apply { setRotate(-90f - cap, cx, cy) })
            }
        }
        cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false, paint)
        val a = Math.toRadians((sweep - 90f).toDouble())
        cv.glow(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), stroke * 1.7f, color)
    }

    /** A smooth bar with a soft track, a gradient fill and a glowing head. */
    private fun Canvas.flowBar(x: Float, cy: Float, width: Float, thickness: Float, frac: Float, color: Int, c: WidgetColors) {
        drawLine(x, cy, x + width, cy, stroke(c.track, thickness))
        val end = x + max(thickness, width * frac)
        drawLine(x, cy, end, cy, stroke(color, thickness).apply {
            shader = LinearGradient(x, 0f, end, 0f, withAlpha(color, 0.55f), color, Shader.TileMode.CLAMP)
        })
        glow(end, cy, thickness * 1.6f, color)
    }

    /** A soft light at the head of a progress fill. */
    private fun Canvas.glow(x: Float, y: Float, r: Float, color: Int) {
        drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r, withAlpha(color, 0.45f), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        })
        drawCircle(x, y, r * 0.18f, fill(ColorUtils.setAlphaComponent(Color.White.toArgb(), 150)))
    }


    private fun fraction(v: Int, goal: Int, floor: Float = 0.02f): Float = if (goal <= 0) floor else (v.toFloat() / goal).coerceIn(floor, 1f)

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun withAlpha(color: Int, a: Float) = ColorUtils.setAlphaComponent(color, (a * 255).toInt())
    private fun number(n: Int) = String.format(Locale.getDefault(), "%,d", n)

    private fun typeface(weight: Int): Typeface = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(Typeface.SANS_SERIF, weight, false)
        weight >= 600 -> Typeface.create("sans-serif-medium", Typeface.BOLD)
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
