package com.bytelog.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Markdown the assistant writes, drawn as it's meant to look: headings, **bold**, *italic*, `code`, links,
 * bullet and numbered lists, quotes, code blocks, rules and tables. Anything it doesn't recognise is plain text, so a
 * half-written answer (while it streams) still reads fine.
 */
object Markdown {
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class ListItem(val marker: String?, val depth: Int, val text: String) : Block   // marker null = bullet
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
        data class Table(val header: List<String>, val rows: List<List<String>>, val alignRight: List<Boolean>) : Block
        data object Rule : Block
    }

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim()); para.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    out += Block.Code(code.toString().trimEnd())
                }
                trimmed.isEmpty() -> flush()
                isTableRow(trimmed) && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    val aligns = cells(lines[i + 1].trim())
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && isTableRow(lines[i].trim())) { rows += cells(lines[i].trim()); i++ }
                    i--
                    val n = header.size
                    val body = rows.map { r -> (r + List((n - r.size).coerceAtLeast(0)) { "" }).take(n) }
                    // Right-align a column of numbers, or one the table marks ---:
                    val right = (0 until n).map { c ->
                        aligns.getOrNull(c)?.trim()?.let { it.endsWith(":") && !it.startsWith(":") } == true ||
                            (body.isNotEmpty() && body.all { looksNumeric(it[c]) })
                    }
                    out += Block.Table(header, body, right)
                }
                rule.matches(line) -> { flush(); out += Block.Rule }
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.find(trimmed)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    out += Block.ListItem(null, depth(m.groupValues[1]), m.groupValues[2])
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.find(line)!!
                    out += Block.ListItem(m.groupValues[2] + ".", depth(m.groupValues[1]), m.groupValues[3])
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = StringBuilder(trimmed.removePrefix(">").trim())
                    while (i + 1 < lines.size && lines[i + 1].trim().startsWith(">")) { i++; quote.append('\n').append(lines[i].trim().removePrefix(">").trim()) }
                    out += Block.Quote(quote.toString())
                }
                // A line under a list item that's indented continues it
                line.startsWith("  ") && para.isEmpty() && out.lastOrNull() is Block.ListItem -> {
                    val last = out.removeAt(out.lastIndex) as Block.ListItem
                    out += last.copy(text = last.text + " " + trimmed)
                }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(trimmed) }
            }
            i++
        }
        flush()
        return out
    }

    private fun depth(indent: String) = (indent.replace("\t", "    ").length / 2).coerceAtMost(3)
    private fun isTableRow(s: String) = s.startsWith("|") && s.count { it == '|' } >= 2
    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
    private fun looksNumeric(s: String): Boolean {
        val t = s.replace(Regex("""[*_`]"""), "").trim()
        return t.isEmpty() || Regex("""^[~≈<>+−-]?\s*[$€£]?\s*[\d.,½¼¾⅓⅔]+\s*[a-zA-Z%µ]{0,5}$""").matches(t)
    }

    /** Inline styles: **bold**, __bold__, *italic*, _italic_, ~~strike~~, `code` and [links](url). */
    fun inline(text: String, codeBackground: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        fun closes(marker: String, from: Int): Int {
            val end = text.indexOf(marker, from)
            return if (end > from) end else -1
        }
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") || rest.startsWith("__") -> {
                    val m = rest.substring(0, 2); val end = closes(m, i + 2)
                    if (end < 0) { append(m); i += 2 } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, end), codeBackground, linkColor)) }
                        i = end + 2
                    }
                }
                rest.startsWith("~~") -> {
                    val end = closes("~~", i + 2)
                    if (end < 0) { append("~~"); i += 2 } else {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    }
                }
                rest.startsWith("`") -> {
                    val end = closes("`", i + 1)
                    if (end < 0) { append('`'); i++ } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, fontSize = 0.92.em())) {
                            append("\u2009"); append(text.substring(i + 1, end)); append("\u2009")
                        }
                        i = end + 1
                    }
                }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 1 && !rest[1].isWhitespace() &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                    val m = rest.substring(0, 1); val end = closes(m, i + 1)
                    if (end < 0 || text[end - 1].isWhitespace()) { append(m); i++ } else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, end), codeBackground, linkColor)) }
                        i = end + 1
                    }
                }
                rest.startsWith("[") -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > i) text.indexOf(')', close) else -1
                    if (close < 0 || end < 0 || text.substring(i + 1, close).contains('\n')) { append('['); i++ } else {
                        val label = text.substring(i + 1, close); val url = text.substring(close + 2, end).trim()
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(label) }
                        i = end + 1
                    }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

    private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

    /** Plain text with the Markdown taken out, for copying. */
    fun plain(text: String): String = parse(text).joinToString("\n") { b ->
        fun strip(s: String) = inline(s, Color.Transparent, Color.Transparent).text
        when (b) {
            is Block.Heading -> strip(b.text)
            is Block.Paragraph -> strip(b.text)
            is Block.ListItem -> "  ".repeat(b.depth) + (b.marker ?: "•") + " " + strip(b.text)
            is Block.Quote -> strip(b.text)
            is Block.Code -> b.text
            is Block.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString("  ·  ") { strip(it) } }
            Block.Rule -> ""
        }
    }
}

@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier, fontSize: Float = 14.5f) {
    val cs = MaterialTheme.colorScheme
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBg = cs.onSurface.copy(alpha = 0.08f)
    val link = cs.primary
    val line = (fontSize * 1.45f).sp
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEachIndexed { index, b ->
                when (b) {
                    is Markdown.Block.Heading -> Text(
                        Markdown.inline(b.text, codeBg, link), color = color,
                        fontSize = when (b.level) { 1 -> fontSize + 5; 2 -> fontSize + 3; else -> fontSize + 1 }.sp,
                        fontWeight = FontWeight.Bold, lineHeight = (fontSize * 1.6f).sp,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp)
                    )
                    is Markdown.Block.Paragraph -> Text(Markdown.inline(b.text, codeBg, link), color = color, fontSize = fontSize.sp, lineHeight = line)
                    is Markdown.Block.ListItem -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                        Text(
                            b.marker ?: if (b.depth == 0) "•" else "◦", color = cs.primary, fontSize = fontSize.sp, lineHeight = line,
                            fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = if (b.marker != null) 22.dp else 14.dp)
                        )
                        Text(Markdown.inline(b.text, codeBg, link), color = color, fontSize = fontSize.sp, lineHeight = line)
                    }
                    is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(cs.primary.copy(alpha = 0.6f)))
                        Spacer(Modifier.width(10.dp))
                        Text(Markdown.inline(b.text, codeBg, link), color = color.copy(alpha = 0.85f), fontSize = fontSize.sp, lineHeight = line, fontStyle = FontStyle.Italic)
                    }
                    is Markdown.Block.Code -> Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(12.dp)
                    ) { Text(b.text, color = color, fontSize = (fontSize - 1.5f).sp, fontFamily = FontFamily.Monospace, lineHeight = (fontSize * 1.35f).sp) }
                    is Markdown.Block.Table -> MarkdownTable(b, color, fontSize, codeBg, link)
                    Markdown.Block.Rule -> HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(t: Markdown.Block.Table, color: Color, fontSize: Float, codeBg: Color, link: Color) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    // The first column (names) gets more room; numbers share the rest
    val weights = t.header.indices.map { c -> if (c == 0 && t.header.size > 1) 1.6f else 1f }
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), shape)
    ) {
        Row(Modifier.fillMaxWidth().background(cs.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            t.header.forEachIndexed { c, h ->
                Text(
                    Markdown.inline(h, codeBg, link), color = cs.primary, fontSize = (fontSize - 2f).sp, fontWeight = FontWeight.Bold,
                    textAlign = if (t.alignRight[c]) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.weight(weights[c]).padding(horizontal = 3.dp)
                )
            }
        }
        t.rows.forEachIndexed { r, row ->
            val total = r == t.rows.lastIndex && row.firstOrNull()?.let { Regex("""(?i)\*{0,2}\s*total""").containsMatchIn(it) } == true
            Row(
                Modifier.fillMaxWidth()
                    .background(if (r % 2 == 1) cs.onSurface.copy(alpha = 0.035f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEachIndexed { c, cell ->
                    Text(
                        Markdown.inline(cell, codeBg, link), color = color, fontSize = (fontSize - 1f).sp, lineHeight = (fontSize * 1.3f).sp,
                        fontWeight = if (total || (c == 0 && t.header.size > 1)) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = if (t.alignRight[c]) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.weight(weights[c]).padding(horizontal = 3.dp)
                    )
                }
            }
            if (r < t.rows.lastIndex) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.25f))
        }
    }
}
