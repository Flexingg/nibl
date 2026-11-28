package com.bytelog.app.quick

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.bytelog.app.MainActivity
import com.bytelog.app.R
import com.bytelog.app.data.local.model.FoodEntry
import com.bytelog.app.data.local.model.FoodItem
import com.bytelog.app.ui.logging.Portion
import com.bytelog.app.ui.logging.Portions
import com.bytelog.app.widget.WidgetDestination
import com.bytelog.app.widget.WidgetLinks
import kotlin.math.roundToInt

/** The notifications behind the launcher commands: Sparky's answers, food matches, "Logged ✓" and approvals. */
internal object QuickNotifications {
    const val CHANNEL_RESULTS = "quick_results"
    const val CHANNEL_WORKING = "quick_working"

    const val ID_WORKING = 4100
    const val ID_SPARKY = 4101
    const val ID_FOOD = 4102
    const val ID_APPROVAL = 4104

    const val KEY_REPLY = "reply_text"

    private val you = Person.Builder().setName("You").build()
    private val sparky = Person.Builder().setName("Sparky").setBot(true).build()

    /** The conversation shown on Sparky's notification (while Nibl is running), oldest first. */
    private val thread = ArrayDeque<Pair<Boolean, String>>()
    private const val THREAD_KEEP = 8

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, "Search bar results", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sparky's answers and food matches for commands from your launcher or search bar"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WORKING, "Search bar progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Nibl works on a command in the background"
            setShowBadge(false)
        })
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun working(context: Context, text: String): android.app.Notification {
        channels(context)
        return NotificationCompat.Builder(context, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nibl")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ----- Opening the app -----

    fun openIntent(context: Context, dest: WidgetDestination, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = WidgetLinks.ACTION_OPEN
            putExtra(WidgetLinks.EXTRA_DEST, WidgetLinks.encode(dest))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // ----- Sparky -----

    /** Shows the conversation with Sparky: [question] just asked and [answer] once it's in (null while thinking). */
    fun sparky(context: Context, question: String?, answer: String?, web: Boolean) {
        synchronized(thread) {
            if (question != null) thread.addLast(true to question)
            if (answer != null) thread.addLast(false to answer)
            while (thread.size > THREAD_KEEP) thread.removeFirst()
        }
        channels(context)
        val style = NotificationCompat.MessagingStyle(you).setConversationTitle(if (web) "Sparky · web research" else "Sparky")
        val now = System.currentTimeMillis()
        synchronized(thread) { thread.toList() }.forEachIndexed { i, (mine, text) ->
            style.addMessage(text, now - (thread.size - i), if (mine) null else sparky)
        }
        if (answer == null) style.addMessage("Thinking…", now, sparky)

        val reply = Intent(context, QuickCommandService::class.java).apply {
            action = QuickCommandService.ACTION_REPLY
            putExtra(QuickCommands.EXTRA_WEB, web)
        }
        val replyPi = PendingIntent.getForegroundService(context, 4201, reply,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        val replyAction = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Ask Sparky").build())
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(answer == null)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, WidgetDestination.Chat(""), 4202))
            .apply { if (answer != null) addAction(replyAction) }
            .build()
        post(context, ID_SPARKY, n)
    }

    fun approval(context: Context, summary: String, items: List<String>) {
        channels(context)
        fun act(approve: Boolean, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, QuickActionReceiver::class.java).apply {
                action = if (approve) QuickActionReceiver.ACTION_APPROVE else QuickActionReceiver.ACTION_DENY
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = (listOf(summary) + items.take(6).map { "• $it" }).joinToString("\n")
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sparky wants to make a change")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification, "Approve", act(true, 4211))
            .addAction(R.drawable.ic_notification, "Deny", act(false, 4212))
            .setContentIntent(openIntent(context, WidgetDestination.Chat(""), 4213))
            .build()
        post(context, ID_APPROVAL, n)
    }

    // ----- Food -----

    /** The best matches for [query], each with a Log button that logs it to [mealName]. */
    fun foodMatches(context: Context, query: String, mealId: String, mealName: String, foods: List<Pair<FoodItem, Portion>>) {
        channels(context)
        val open = openIntent(context, WidgetDestination.Search(mealName, query), 4221)
        val b = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (foods.isEmpty()) {
            b.setContentTitle("No foods found for “$query”").setContentText("Tap to search in Nibl")
        } else {
            val lines = foods.map { (f, p) -> line(f, p) }
            val style = NotificationCompat.InboxStyle().setBigContentTitle("“$query” · log to $mealName")
            lines.forEach { style.addLine(it) }
            style.setSummaryText("Tap for more results")
            b.setContentTitle("“$query” · log to $mealName").setContentText(lines.first()).setStyle(style)
            foods.forEachIndexed { i, (f, _) ->
                val intent = Intent(context, QuickActionReceiver::class.java).apply {
                    action = QuickActionReceiver.ACTION_LOG_FOOD
                    putExtra(QuickActionReceiver.EXTRA_FOOD, QuickFoodCodec.encode(f))
                    putExtra(QuickActionReceiver.EXTRA_MEAL_ID, mealId)
                    putExtra(QuickActionReceiver.EXTRA_MEAL_NAME, mealName)
                }
                val pi = PendingIntent.getBroadcast(context, 4230 + i, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                b.addAction(R.drawable.ic_notification, "Log ${shortName(f.name)}", pi)
            }
        }
        post(context, ID_FOOD, b.build())
    }

    fun logged(context: Context, entry: FoodEntry, portion: String) {
        channels(context)
        val undo = PendingIntent.getBroadcast(context, 4240, Intent(context, QuickActionReceiver::class.java).apply {
            action = QuickActionReceiver.ACTION_UNDO
            putExtra(QuickActionReceiver.EXTRA_ENTRY, QuickFoodCodec.encodeLogged(entry))
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Logged ✓ ${entry.foodName}")
            .setContentText("$portion · ${entry.calories.roundToInt()} kcal to ${entry.mealName}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .addAction(R.drawable.ic_notification, "Undo", undo)
            .setContentIntent(openIntent(context, WidgetDestination.Diary, 4241))
            .build()
        post(context, ID_FOOD, n)
    }

    fun simple(context: Context, id: Int, title: String, text: String, timeoutMs: Long = 0) {
        channels(context)
        val b = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, WidgetDestination.Diary, 4250))
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs)
        post(context, id, b.build())
    }

    fun line(f: FoodItem, p: Portion): String {
        val kcal = Portions.macros(f, p).calories.roundToInt()
        val name = listOfNotNull(f.name, f.brand?.takeIf { it.isNotBlank() }).joinToString(" · ")
        return "$name — ${Portions.describe(f, p)} · $kcal kcal"
    }

    private fun shortName(name: String): String = name.trim().let { if (it.length <= 16) it else it.take(15).trimEnd() + "…" }

    /** Sparky's Markdown as plain text for a notification. */
    fun plain(markdown: String): String = markdown
        .replace(Regex("```[a-zA-Z]*\\n?"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace(Regex("(?<![*\\w])[*_]([^*_\\n]+)[*_](?![*\\w])"), "$1")
        .replace("`", "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
