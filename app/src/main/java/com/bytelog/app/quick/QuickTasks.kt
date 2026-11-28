package com.bytelog.app.quick

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.R
import com.bytelog.app.data.fasting.FastingLog
import com.bytelog.app.data.fasting.FastingProtocol
import com.bytelog.app.widget.WidgetDataLoader
import com.bytelog.app.widget.WidgetDestination
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Runs the launcher commands that change or show your day (water, weight, fasting, caffeine, today) and shows
 * the result as a notification, with a button to undo or do the next thing.
 */
internal object QuickTasks {
    const val ID_WATER = 4105
    const val ID_WEIGHT = 4106
    const val ID_FAST = 4107
    const val ID_CAFFEINE = 4108
    const val ID_TODAY = 4109
    const val ID_CONFIRM = 4110
    const val ID_UNCLEAR = 4111

    private val json = Json { ignoreUnknownKeys = true }
    fun encode(cmd: QuickCommand): String = json.encodeToString<QuickCommand>(cmd)
    fun decode(raw: String?): QuickCommand? = raw?.let { runCatching { json.decodeFromString<QuickCommand>(it) }.getOrNull() }

    /** A button that runs [cmd] (already confirmed: it comes from Nibl's own notification). */
    fun button(context: Context, label: String, cmd: QuickCommand): NotificationCompat.Action {
        val intent = Intent(context, QuickCommandService::class.java).apply {
            action = QuickCommandService.ACTION_RUN
            putExtra(QuickCommandService.EXTRA_COMMAND, encode(cmd))
        }
        val code = 4300 + (encode(cmd).hashCode() and 0xfff)
        val pi = PendingIntent.getForegroundService(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, pi).build()
    }

    /** What [cmd] will do, for the confirmation asked when a command comes without the key. */
    fun describe(cmd: QuickCommand): String = when (cmd) {
        is QuickCommand.AddWater -> cmd.oz?.let { "Log ${fmt(it)} oz of water" } ?: "Log a drink of water"
        QuickCommand.UndoWater -> "Remove today's last drink"
        is QuickCommand.LogWeight -> "Log your weight as ${fmt(cmd.weight)}${cmd.unit?.let { " $it" } ?: ""}" +
            (cmd.bodyFat?.let { " at ${fmt(it)}% body fat" } ?: "")
        is QuickCommand.StartFast -> "Start a ${cmd.protocol?.let { FastingProtocol.fromCode(it).label } ?: ""} fast".replace("  ", " ")
        QuickCommand.EndFast -> "End your fast"
        is QuickCommand.LogCaffeine -> "Log ${cmd.name}, ${fmt(cmd.mg)} mg caffeine"
        else -> "Run this command"
    }

    fun confirm(context: Context, cmd: QuickCommand) {
        val text = describe(cmd)
        post(context, ID_CONFIRM, builder(context, "$text?", "Sent without your key, so Nibl checks first.")
            .addAction(button(context, "Yes", cmd))
            .setTimeoutAfter(120_000))
    }

    suspend fun run(app: ByteLogApplication, cmd: QuickCommand) {
        val ctx = app.applicationContext
        val today = LocalDate.now().toString()
        when (cmd) {
            is QuickCommand.AddWater -> {
                val oz = cmd.oz ?: app.settingsRepository.defaultContainerOz.first()
                val name = if (cmd.oz == null) app.settingsRepository.defaultContainerName.first() else null
                val before = app.database.waterLogDao().getTotalWaterForDate(today).first() / ML_PER_OZ
                app.waterRepository.quickAddWaterOz(today, oz, containerName = name)
                val goal = app.settingsRepository.targetWaterOz.first()
                post(ctx, ID_WATER, builder(ctx, "💧 +${fmt(oz)} oz water", "${(before + oz).roundToInt()} of ${goal.roundToInt()} oz today")
                    .setProgress(goal.roundToInt().coerceAtLeast(1), (before + oz).roundToInt().coerceAtMost(goal.roundToInt().coerceAtLeast(1)), false)
                    .addAction(button(ctx, "Undo", QuickCommand.UndoWater))
                    .addAction(button(ctx, "+${fmt(oz)} oz more", QuickCommand.AddWater(oz)))
                    .setTimeoutAfter(20_000))
                refreshWidgets(app)
            }
            QuickCommand.UndoWater -> {
                val last = app.database.waterLogDao().getLatestLogForDate(today)
                if (last == null) {
                    post(ctx, ID_WATER, builder(ctx, "No water logged today", "Nothing to take back.").setTimeoutAfter(8_000))
                } else {
                    val total = app.database.waterLogDao().getTotalWaterForDate(today).first()
                    app.waterRepository.undoLastDrink(today)
                    val left = ((total - last.waterMl) / ML_PER_OZ).coerceAtLeast(0f)
                    post(ctx, ID_WATER, builder(ctx, "Removed ${fmt(last.waterMl / ML_PER_OZ)} oz water", "${left.roundToInt()} oz today").setTimeoutAfter(8_000))
                }
                refreshWidgets(app)
            }
            is QuickCommand.LogWeight -> {
                val unit = app.settingsRepository.weightUnit.first()
                val given = cmd.unit ?: unit
                // Weigh-ins are kept in your unit; convert one given in the other
                val w = when {
                    given.equals(unit, ignoreCase = true) -> cmd.weight
                    given == "kg" -> cmd.weight * 2.20462f
                    else -> cmd.weight / 2.20462f
                }.let { Math.round(it * 10f) / 10f }
                val previous = app.weightRepository.getLatestWeightOnOrBefore(LocalDate.now().minusDays(1).toString()).first()
                app.weightRepository.logWeight(today, w, unit, cmd.bodyFat)
                val change = previous?.let { p ->
                    val d = w - p.weight
                    val sign = if (d > 0) "+" else if (d < 0) "−" else "±"
                    "$sign${fmt(kotlin.math.abs(d))} $unit since ${dayLabel(p.entryDate)}"
                }
                val fat = cmd.bodyFat?.let { " · ${fmt(it)}% body fat" }.orEmpty()
                post(ctx, ID_WEIGHT, builder(ctx, "⚖️ Weight logged: ${fmt(w)} $unit$fat", change ?: "Your first weigh-in in Nibl")
                    .setContentIntent(QuickNotifications.openIntent(ctx, WidgetDestination.Diary, 4260))
                    .setTimeoutAfter(20_000))
            }
            is QuickCommand.StartFast -> {
                val repo = app.fastingRepository
                runCatching { repo.refresh() }
                val active = repo.activeFast.value
                if (active != null && active.isActive) {
                    fastNotification(ctx, active, title = "You're already fasting")
                } else {
                    val type = cmd.protocol ?: repo.preferences.value.defaultProtocol
                    val log = repo.startFast(fastingType = type).getOrNull()
                    if (log != null) fastNotification(ctx, log, title = "⏱️ Fast started · ${FastingProtocol.fromCode(log.fastingType).label}")
                    else post(ctx, ID_FAST, builder(ctx, "Couldn't start the fast", "Open Nibl to try again."))
                }
            }
            QuickCommand.EndFast -> {
                val repo = app.fastingRepository
                if (repo.activeFast.value == null) runCatching { repo.refresh() }
                val active = repo.activeFast.value
                if (active == null || !active.isActive) {
                    post(ctx, ID_FAST, builder(ctx, "You're not fasting", "Nothing to end.")
                        .addAction(button(ctx, "Start a fast", QuickCommand.StartFast(null))).setTimeoutAfter(15_000))
                } else {
                    val done = repo.endFast(active.id).getOrNull()
                    val took = duration(done?.elapsedMillis() ?: active.elapsedMillis())
                    val goal = active.targetDurationMillis()
                    val hit = (done?.elapsedMillis() ?: active.elapsedMillis()) >= goal
                    post(ctx, ID_FAST, builder(ctx, "🍽️ Fast ended · $took", if (hit) "You made your ${duration(goal)} goal." else "Goal was ${duration(goal)}.")
                        .setTimeoutAfter(30_000))
                }
            }
            QuickCommand.FastStatus -> {
                val repo = app.fastingRepository
                runCatching { repo.refresh() }
                val active = repo.activeFast.value
                if (active != null && active.isActive) fastNotification(ctx, active, title = "⏱️ Fasting ${duration(active.elapsedMillis())}")
                else post(ctx, ID_FAST, builder(ctx, "You're not fasting", "Default plan: ${FastingProtocol.fromCode(repo.preferences.value.defaultProtocol).label}")
                    .addAction(button(ctx, "Start a fast", QuickCommand.StartFast(null))).setTimeoutAfter(20_000))
            }
            is QuickCommand.LogCaffeine -> {
                val r = app.caffeineRepository.logCaffeine(cmd.name, cmd.mg, timeHHmm = "")
                val status = app.caffeineRepository.status.value
                val body = when {
                    r.isFailure -> "Saved on the phone; it goes to Sparky next time it's reachable."
                    status != null -> "${status.totalTodayMg.roundToInt()} mg today · about ${status.activeMgNow.roundToInt()} mg in your system now, " +
                        "${status.atBedtimeMg.roundToInt()} mg at bedtime"
                    else -> "Logged"
                }
                post(ctx, ID_CAFFEINE, builder(ctx, "☕ ${cmd.name} · ${fmt(cmd.mg)} mg", body).setTimeoutAfter(20_000))
            }
            QuickCommand.Today -> today(app)
            is QuickCommand.Unclear -> post(ctx, ID_UNCLEAR, builder(ctx, "Nibl didn't catch that", cmd.hint).setTimeoutAfter(15_000))
            else -> {}
        }
    }

    private suspend fun today(app: ByteLogApplication) {
        val ctx = app.applicationContext
        val d = WidgetDataLoader.load(app)
        val unit = app.settingsRepository.weightUnit.first()
        val weight = app.weightRepository.getLatestWeightOnOrBefore(d.date).first()
        val fast = app.fastingRepository.activeFast.value?.takeIf { it.isActive }
        val lines = listOfNotNull(
            "🍽️ ${d.eaten} of ${d.goal} kcal · ${d.left} left",
            "P ${d.protein}/${d.proteinGoal} g · C ${d.carbs}/${d.carbsGoal} g · F ${d.fat}/${d.fatGoal} g",
            "💧 ${d.waterOz.roundToInt()} of ${d.waterGoalOz.roundToInt()} oz water",
            fast?.let { "⏱️ Fasting ${duration(it.elapsedMillis())} of ${duration(it.targetDurationMillis())}" } ?: d.fastingLabel?.let { "⏱️ $it since your last meal" },
            weight?.let { "⚖️ ${fmt(it.weight)} $unit (${dayLabel(it.entryDate)})" }
        )
        val style = NotificationCompat.InboxStyle().setBigContentTitle("Today in Nibl")
        lines.forEach { style.addLine(it) }
        val b = builder(ctx, "${d.left} kcal left today", lines.drop(1).take(2).joinToString(" · "))
            .setStyle(style)
            .addAction(button(ctx, "+${fmt(d.bottleOz)} oz water", QuickCommand.AddWater(null)))
            .addAction(if (fast != null) button(ctx, "End fast", QuickCommand.EndFast) else button(ctx, "Start fast", QuickCommand.StartFast(null)))
            .setContentIntent(QuickNotifications.openIntent(ctx, WidgetDestination.Diary, 4261))
        post(ctx, ID_TODAY, b)
    }

    private fun fastNotification(ctx: Context, log: FastingLog, title: String) {
        val end = log.targetEndInstant() ?: log.startInstant()?.plusMillis(log.targetDurationMillis())
        val endText = end?.let { "Goal ${duration(log.targetDurationMillis())}, done at ${time(it)}" } ?: "Goal ${duration(log.targetDurationMillis())}"
        val b = builder(ctx, title, endText)
            .addAction(button(ctx, "End fast", QuickCommand.EndFast))
            .setContentIntent(QuickNotifications.openIntent(ctx, WidgetDestination.Diary, 4262))
        val start = log.startInstant()
        if (start != null) {
            // A running timer from the start of the fast
            b.setWhen(start.toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
        }
        post(ctx, ID_FAST, b)
    }

    private fun builder(ctx: Context, title: String, text: String): NotificationCompat.Builder {
        QuickNotifications.channels(ctx)
        return NotificationCompat.Builder(ctx, QuickNotifications.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(QuickNotifications.openIntent(ctx, WidgetDestination.Diary, 4263))
    }

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: SecurityException) { }
    }

    private suspend fun refreshWidgets(app: ByteLogApplication) {
        runCatching { com.bytelog.app.widget.WidgetUpdater.updateAll(app) }
    }

    private const val ML_PER_OZ = 29.5735f

    fun fmt(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

    fun duration(ms: Long): String {
        val mins = (ms / 60_000L).coerceAtLeast(0)
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    private fun time(i: Instant): String =
        i.atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private fun dayLabel(date: String): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val today = LocalDate.now()
        return when (d) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault()))
        }
    }
}
