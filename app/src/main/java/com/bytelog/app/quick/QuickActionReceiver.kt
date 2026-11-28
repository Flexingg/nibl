package com.bytelog.app.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.ui.logging.Portions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The buttons on the launcher commands' notifications: Log, Undo, and Approve / Deny for Sparky's changes. */
class QuickActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_LOG_FOOD = "com.bytelog.app.quick.LOG_FOOD"
        const val ACTION_UNDO = "com.bytelog.app.quick.UNDO"
        const val ACTION_APPROVE = "com.bytelog.app.quick.APPROVE"
        const val ACTION_DENY = "com.bytelog.app.quick.DENY"
        const val EXTRA_FOOD = "food"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_MEAL_ID = "meal_id"
        const val EXTRA_MEAL_NAME = "meal_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ByteLogApplication ?: return
        when (intent.action) {
            ACTION_APPROVE, ACTION_DENY -> {
                app.chatRepository.resolvePendingAction(intent.action == ACTION_APPROVE)
                QuickNotifications.cancel(context, QuickNotifications.ID_APPROVAL)
            }
            ACTION_LOG_FOOD -> {
                val food = QuickFoodCodec.decode(intent.getStringExtra(EXTRA_FOOD)) ?: return
                val mealId = intent.getStringExtra(EXTRA_MEAL_ID) ?: return
                val mealName = intent.getStringExtra(EXTRA_MEAL_NAME) ?: return
                work {
                    val (entry, portion) = QuickFoodActions.log(app.foodRepository, food, mealId, mealName)
                    QuickNotifications.logged(context, entry, Portions.describe(food, portion))
                    runCatching { com.bytelog.app.widget.WidgetUpdater.updateAll(context) }
                }
            }
            ACTION_UNDO -> {
                val entry = QuickFoodCodec.decodeLogged(intent.getStringExtra(EXTRA_ENTRY)) ?: return
                work {
                    app.foodRepository.undoLogged(listOf(entry))
                    runCatching { com.bytelog.app.widget.WidgetUpdater.updateAll(context) }
                    QuickNotifications.simple(context, QuickNotifications.ID_FOOD, "Removed ${entry.foodName}", "Taken off ${entry.mealName}.", timeoutMs = 5_000)
                }
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { block() } catch (_: Exception) { } finally { pending.finish() }
        }
    }
}
