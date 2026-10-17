package dev.idart.mangareader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Minimal chat-grade Markdown: headings, **bold**, *italic*, ~~strike~~,
 * `code`, ```fences```, - bullets, 1. lists, > quotes and [links](url).
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    /**
     * Herda a cor do conteúdo (LocalContentColor) em vez de `Color.Unspecified`:
     * repassar Unspecified para `style.copy(color = ...)` fazia o texto cair na
     * cor padrão de desenho, e as respostas do assistente saían escuras demais
     * para ler no tema escuro.
     */
    color: Color = LocalContentColor.current,
) {
    val blocks = remember(text) { parseBlocks(text) }
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(modifier = Modifier.height(6.dp))
            when (block) {
                is MdBlock.Heading -> {
                    val s = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    SimpleMdText(inline(block.text), style.merge(s), color, uriHandler::openUri)
                }
                is MdBlock.Code -> {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                    ) {
                        SimpleMdText(
                            AnnotatedString(block.code),
                            style.copy(fontFamily = FontFamily.Monospace),
                            color,
                            {},
                        )
                    }
                }
                is MdBlock.Quote -> {
                    Row {
                        Box(
                            modifier = Modifier.width(3.dp)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(vertical = 2.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        SimpleMdText(
                            inline(block.lines.joinToString("\n")),
                            style.copy(fontStyle = FontStyle.Italic),
                            color,
                            uriHandler::openUri,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MdBlock.Listing -> {
                    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                        block.items.forEach { item ->
                            Row {
                                SimpleMdText(
                                    AnnotatedString(item.marker),
                                    style,
                                    color,
                                    {},
                                    modifier = Modifier.width(20.dp),
                                )
                                SimpleMdText(
                                    inline(item.text),
                                    style,
                                    color,
                                    uriHandler::openUri,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                is MdBlock.Paragraph -> {
                    SimpleMdText(inline(block.text), style, color, uriHandler::openUri)
                }
            }
        }
    }
}

@Composable
private fun SimpleMdText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ClickableText(
        text = text,
        style = style.copy(color = color),
        modifier = modifier,
        onClick = { offset ->
            text.getStringAnnotations("url", offset, offset).firstOrNull()
                ?.let { onOpenUrl(it.item) }
        },
    )
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class Listing(val items: List<MdItem>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private data class MdItem(val marker: String, val text: String)

private fun parseBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                code.add(lines[i])
                i++
            }
            i++ // closing fence
            out.add(MdBlock.Code(code.joinToString("\n").trim('\n')))
            continue
        }
        val heading = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(line.trim())
        if (heading != null) {
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }
        if (line.trimStart().startsWith(">")) {
            val quoted = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoted.add(lines[i].trimStart().removePrefix(">").trimStart())
                i++
            }
            out.add(MdBlock.Quote(quoted))
            continue
        }
        val bullet = Regex("""^\s*([-*+])\s+(.*)$""").matchEntire(line)
        val ordered = Regex("""^\s*(\d+[.)])\s+(.*)$""").matchEntire(line)
        val marker = bullet?.groupValues?.get(1)?.let { "•" }
            ?: ordered?.groupValues?.get(1)
        val itemText = bullet?.groupValues?.get(2) ?: ordered?.groupValues?.get(2)
        if (marker != null && itemText != null) {
            val items = mutableListOf<MdItem>()
            while (i < lines.size) {
                val b = Regex("""^\s*([-*+])\s+(.*)$""").matchEntire(lines[i])
                val o = Regex("""^\s*(\d+[.)])\s+(.*)$""").matchEntire(lines[i])
                if (b != null) {
                    items.add(MdItem("•", b.groupValues[2]))
                    i++
                } else if (o != null) {
                    items.add(MdItem(o.groupValues[1], o.groupValues[2]))
                    i++
                } else {
                    break
                }
            }
            out.add(MdBlock.Listing(items))
            continue
        }
        if (line.isBlank()) {
            i++
            continue
        }
        val para = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank()) {
            para.add(lines[i])
            i++
        }
        out.add(MdBlock.Paragraph(para.joinToString("\n")))
    }
    return out
}

private fun inline(text: String): AnnotatedString {
    // Order matters: code first (protected), then links, bold, italic, strike.
    val codeSpans = mutableListOf<Triple<Int, Int, String>>()
    var stripped = Regex("`([^`]+)`").replace(text) { match ->
        codeSpans.add(Triple(match.range.first, match.range.first, match.groupValues[1]))
        "\u0000${codeSpans.lastIndex}\u0000"
    }
    return buildAnnotatedString {
        var pos = 0
        val token = Regex("""([^\]]+)\(([^)]+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*|~~([^~]+)~~|(\d+)""")
        for (match in token.findAll(stripped)) {
            append(stripped.substring(pos, match.range.first))
            val linkText = match.groups[1]?.value
            val linkUrl = match.groups[2]?.value
            val bold = match.groups[3]?.value
            val italic = match.groups[4]?.value
            val strike = match.groups[5]?.value
            val codeIdx = match.groups[6]?.value?.toIntOrNull()
            when {
                linkText != null && linkUrl != null -> {
                    pushStringAnnotation("url", linkUrl)
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                    pop()
                }
                bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
                strike != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(strike) }
                codeIdx != null -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(codeSpans.getOrNull(codeIdx)?.third ?: "")
                    }
                }
            }
            pos = match.range.last + 1
        }
        append(stripped.substring(pos).replace("\u0000", ""))
    }
}
