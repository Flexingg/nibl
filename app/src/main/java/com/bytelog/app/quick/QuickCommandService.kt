package com.bytelog.app.quick

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bytelog.app.ByteLogApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a launcher command in the background (asking Sparky, finding a food) while Nibl stays closed, and shows
 * the result as a notification. A short foreground service keeps Android from stopping it mid-answer.
 */
class QuickCommandService : Service() {

    companion object {
        const val ACTION_ASK = "com.bytelog.app.quick.ASK"
        const val ACTION_REPLY = "com.bytelog.app.quick.REPLY"
        const val ACTION_FIND_FOOD = "com.bytelog.app.quick.FIND_FOOD"
        /** Runs a water, weight, fasting, caffeine or today command (see [QuickTasks]). */
        const val ACTION_RUN = "com.bytelog.app.quick.RUN"
        const val EXTRA_COMMAND = "command"

        fun run(context: Context, cmd: QuickCommand) = start(context, Intent(context, QuickCommandService::class.java).apply {
            action = ACTION_RUN
            putExtra(EXTRA_COMMAND, QuickTasks.encode(cmd))
        })

        fun ask(context: Context, text: String, web: Boolean) = start(context, Intent(context, QuickCommandService::class.java).apply {
            action = ACTION_ASK
            putExtra(QuickCommands.EXTRA_QUERY, text)
            putExtra(QuickCommands.EXTRA_WEB, web)
        })

        fun findFood(context: Context, query: String) = start(context, Intent(context, QuickCommandService::class.java).apply {
            action = ACTION_FIND_FOOD
            putExtra(QuickCommands.EXTRA_QUERY, query)
        })

        private fun start(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android refused a background start (it shouldn't from a launcher tap); say so rather than do nothing
                Toast.makeText(context, "Nibl couldn't run that in the background", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val running = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = when (intent?.action) {
            ACTION_FIND_FOOD -> "Finding foods…"
            ACTION_RUN -> "Working…"
            else -> "Asking Sparky…"
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        try {
            ServiceCompat.startForeground(this, QuickNotifications.ID_WORKING, QuickNotifications.working(this, label), type)
        } catch (e: Exception) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent == null) { finishOne(); return START_NOT_STICKY }

        running.incrementAndGet()
        val app = application as ByteLogApplication
        // The app's scope, so an answer that outlasts the service's time limit still arrives
        app.applicationScope.launch {
            try {
                when (intent.action) {
                    ACTION_ASK -> intent.getStringExtra(QuickCommands.EXTRA_QUERY)?.let { ask(app, it, intent.getBooleanExtra(QuickCommands.EXTRA_WEB, false), fromReply = false) }
                    ACTION_REPLY -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(QuickNotifications.KEY_REPLY)?.toString()
                        ?.let { QuickCommands.clean(it) }?.takeIf { it.isNotEmpty() }
                        ?.let { ask(app, it, intent.getBooleanExtra(QuickCommands.EXTRA_WEB, false), fromReply = true) }
                    ACTION_FIND_FOOD -> intent.getStringExtra(QuickCommands.EXTRA_QUERY)?.let { findFood(app, it) }
                    ACTION_RUN -> QuickTasks.decode(intent.getStringExtra(EXTRA_COMMAND))?.let { cmd ->
                        // A confirmation answered: its notification goes once the command runs
                        QuickNotifications.cancel(applicationContext, QuickTasks.ID_CONFIRM)
                        runCatching { QuickTasks.run(app, cmd) }.onFailure {
                            QuickNotifications.simple(applicationContext, QuickTasks.ID_UNCLEAR, "That didn't work", it.message ?: "Open Nibl to try again.", timeoutMs = 15_000)
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { finishOne() }
            }
        }
        return START_NOT_STICKY
    }

    private fun finishOne() {
        if (running.decrementAndGet() <= 0) {
            running.set(0)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Android 14+: a short service gets about three minutes. The work carries on in the app; the service steps aside. */
    override fun onTimeout(startId: Int) {
        running.set(0)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun ask(app: ByteLogApplication, text: String, web: Boolean, fromReply: Boolean) {
        val repo = app.chatRepository
        val ctx = applicationContext
        if (repo.isLoading.value) {
            QuickNotifications.simple(ctx, QuickNotifications.ID_SPARKY, "Sparky is still answering", "Try again in a moment.", timeoutMs = 10_000)
            return
        }
        if (web) {
            val config = app.settingsRepository.researchConfig.first()
            if (com.bytelog.app.research.ResearchFactory.provider(config, com.bytelog.app.research.ProviderHttp()) == null) {
                QuickNotifications.simple(ctx, QuickNotifications.ID_SPARKY, "Web research needs an API key",
                    "Add one in Nibl → Settings → Web research assistant, or ask without web=1.")
                return
            }
        }
        QuickNotifications.sparky(ctx, question = text, answer = null, web = web)
        // The chat loads its history when it starts; wait for that so the history doesn't replace this question
        withTimeoutOrNull(10_000) { repo.messages.first { it.isNotEmpty() } }
        val before = repo.messages.value.map { it.id }.toSet()

        // Changes the web assistant wants to make wait for Approve / Deny on a notification
        val approvals = app.applicationScope.launch {
            repo.pendingAction.collect { a ->
                if (a == null) QuickNotifications.cancel(ctx, QuickNotifications.ID_APPROVAL)
                else QuickNotifications.approval(ctx, a.summary, a.items)
            }
        }
        try {
            repo.sendMessage(text, null, webResearch = web)
        } finally {
            approvals.cancel()
            QuickNotifications.cancel(ctx, QuickNotifications.ID_APPROVAL)
        }
        val answer = repo.messages.value.lastOrNull { it.role == "assistant" && it.id !in before }
        val body = answer?.content?.let { QuickNotifications.plain(it) }?.ifBlank { null }
            ?: answer?.askUserPrompt?.let { p -> listOfNotNull(p.question, p.options.takeIf { it.isNotEmpty() }?.joinToString(" / ")).joinToString("\n") }?.ifBlank { null }
            ?: "Sparky didn't answer. Open the chat to try again."
        QuickNotifications.sparky(ctx, question = null, answer = body, web = web)
    }

    private suspend fun findFood(app: ByteLogApplication, query: String) {
        val ctx = applicationContext
        val (meal, results) = QuickFoodActions.find(app.foodRepository, query)
        QuickNotifications.foodMatches(ctx, query, meal.id, meal.name, results)
    }
}
