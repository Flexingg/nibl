package com.bytelog.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Settings
import com.bytelog.app.data.remote.ChatMessageOutput
import com.bytelog.app.ui.components.ChatToolChips
import com.bytelog.app.ui.components.CollapsibleCard
import com.bytelog.app.ui.components.Markdown
import com.bytelog.app.ui.screens.ChatMessageBubble
import com.bytelog.app.ui.theme.ByteLogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The assistant's Markdown, its tool chips and the settings' collapsible cards. Keep PNGs with -Pbytelog.screenshots=<dir>. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1100dp-xhdpi")
class ChatFormattingRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val answer = """
        I logged all four for dinner today, but the Daddy Toast calories may be too low.

        | Food | Amount | Calories |
        |---|---|---|
        | Orange Chicken | ½ serving | 280 kcal |
        | Beijing Beef | ½ serving | 240 kcal |
        | Fried Rice | ½ serving | 305 kcal |
        | Daddy Toast | 3 servings | 396 kcal |
        | **Total** | | **1,221 kcal** |

        **Daddy Toast:** your saved food is set as "2 servings = 264 kcal", so I logged *3 servings* at 132 kcal each.
        - 1 slice is sourdough with peanut butter and grape jelly (~260 kcal)
        - 3 slices would be about **792 kcal**, not 396

        Want me to change it to 792 kcal and set the serving to `1 slice`?
    """.trimIndent()

    private fun shot(name: String) {
        val dir = System.getProperty("bytelog.screenshots") ?: return
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun parsesTablesListsAndInlineStyles() {
        val blocks = Markdown.parse(answer)
        val table = blocks.filterIsInstance<Markdown.Block.Table>().single()
        assertEquals(listOf("Food", "Amount", "Calories"), table.header)
        assertEquals(5, table.rows.size)
        assertEquals("numbers sit right", listOf(false, false, true), table.alignRight)
        assertEquals(2, blocks.count { it is Markdown.Block.ListItem })
        val inline = Markdown.inline("**Daddy Toast:** *3* `1 slice` [x](https://a.b)", androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue)
        assertEquals("Daddy Toast: 3  1 slice  x", inline.text)
        assertFalse(Markdown.plain(answer).contains("**"))
        assertTrue(Markdown.plain(answer).contains("Orange Chicken  ·  ½ serving  ·  280 kcal"))
        // Half-written while streaming: stays readable
        assertEquals("**Daddy", Markdown.inline("**Daddy", androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue).text)
        assertEquals("2 * 3 = 6", Markdown.inline("2 * 3 = 6", androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue).text)
    }

    @Test fun toolChipsGroupWhatWasDone() {
        val chips = ChatToolChips.summarize(listOf(
            "sparky_manage_food#search_food", "sparky_manage_food#log_food", "sparky_manage_food#log_food",
            "sparky_manage_food#log_food", "sparky_manage_food#log_food", "sparky_ask_user", "create_foods", "sparky_ai:sparky_manage_food#log_water"
        ))
        assertEquals(listOf(
            ChatToolChips.Chip("Logged food", 4, true), ChatToolChips.Chip("Created foods", 1, true),
            ChatToolChips.Chip("Sparky AI · logged water", 1, true), ChatToolChips.Chip("Checked foods", 1, false)
        ), chips)
        assertEquals("Logged food" to true, ChatToolChips.label("sparky_manage_food"))
        assertEquals("Checked foods" to false, ChatToolChips.label("sparky_search_foods"))
    }

    @Test fun anAnswerWithATableAndChips() {
        compose.setContent {
            ByteLogTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatMessageBubble(
                            ChatMessageOutput(role = "user", content = "let's log about half a serving of that orange chicken, Beijing beef, and fried rice for dinner. then add 3 slices of daddy toast"),
                            onCopy = {}, onEdit = {}
                        )
                        ChatMessageBubble(
                            ChatMessageOutput(role = "assistant", content = answer, executedTools = listOf(
                                "sparky_manage_food#search_food", "sparky_manage_food#log_food", "sparky_manage_food#log_food",
                                "sparky_manage_food#log_food", "sparky_manage_food#log_food"
                            )),
                            onCopy = {}, onEdit = {}
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Logged food ×4"), true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Orange Chicken"), true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no raw table pipes", compose.onAllNodes(hasText("|", substring = true), true).fetchSemanticsNodes().isEmpty())
        shot("40-chat-markdown")
    }

    @Test fun settingsCardsTuckAway() {
        compose.setContent {
            ByteLogTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CollapsibleCard("Appearance", androidx.compose.material.icons.Icons.Default.Settings, summary = "Dark · device colours") {
                            androidx.compose.material3.Text("Theme choices")
                        }
                        CollapsibleCard("Reminders", androidx.compose.material.icons.Icons.Default.Settings, summary = "Daily at 8:00 PM") {
                            androidx.compose.material3.Text("Reminder time")
                        }
                    }
                }
            }
        }
        assertTrue(compose.onAllNodes(hasText("Theme choices"), true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Appearance").performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(hasText("Theme choices"), true).fetchSemanticsNodes().isNotEmpty() }
        shot("41-settings-collapsible")
        compose.onNodeWithText("Appearance").performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(hasText("Theme choices"), true).fetchSemanticsNodes().isEmpty() }
    }
}
