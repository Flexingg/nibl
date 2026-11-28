package com.bytelog.app.quick

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import com.bytelog.app.ByteLogApplication
import com.bytelog.app.MainActivity
import com.bytelog.app.widget.WidgetDestination
import com.bytelog.app.widget.WidgetLinks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where `nibl://` links and launcher intents land. It has no window: it hands the command to
 * [QuickCommandService] (or opens the right screen) and closes, so you stay in your launcher.
 */
class QuickCommandActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handle(intent) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val request = when {
            intent.action == Intent.ACTION_VIEW -> intent.dataString?.let { QuickCommands.parseUrl(it) }
            else -> QuickCommands.fromAction(
                intent.action,
                text = intent.getStringExtra(QuickCommands.EXTRA_QUERY)
                    ?: intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                key = intent.getStringExtra(QuickCommands.EXTRA_KEY),
                web = intent.getBooleanExtra(QuickCommands.EXTRA_WEB, false) ||
                    intent.getStringExtra(QuickCommands.EXTRA_WEB)?.lowercase() in setOf("1", "true", "yes")
            )
        } ?: return

        val app = application as ByteLogApplication
        // Only an ask checks the key; reading it is a quick look at the settings file
        val expected = if (request.command is QuickCommand.Ask) runBlocking { withTimeoutOrNull(2_000) { app.settingsRepository.quickCommandKey.first() } } else null
        when (val cmd = QuickCommands.authorize(request, expected)) {
            is QuickCommand.Ask -> QuickCommandService.ask(this, cmd.text, cmd.web)
            is QuickCommand.FindFood -> QuickCommandService.findFood(this, cmd.query)
            is QuickCommand.OpenSearch -> open(WidgetDestination.Search(runBlocking { QuickFoodActions.mealNow(app.foodRepository).name }, cmd.query))
            is QuickCommand.OpenChat -> open(WidgetDestination.Chat(cmd.draft))
        }
    }

    private fun open(dest: WidgetDestination) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = WidgetLinks.ACTION_OPEN
            putExtra(WidgetLinks.EXTRA_DEST, WidgetLinks.encode(dest))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}
