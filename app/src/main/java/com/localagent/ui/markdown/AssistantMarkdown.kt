package com.localagent.ui.markdown

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.localagent.R
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val displayMath = Regex("""(?m)\$\$([\s\S]*?)\$\$""")
private val inlineMath = Regex("""(?<!\\)\$(?!\$)((?:[^$\\]|\\.)+?)\$(?!\$)""")

@Composable
fun AssistantMarkdown(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { splitDisplay(text) }
    val inlineBudget = rememberSaveable(text) { mutableIntStateOf(0) }
    Column(modifier = modifier.fillMaxWidth()) {
        segments.forEach { seg ->
            when (seg) {
                is Seg.Math -> KatexWebView(seg.latex.trim(), displayMode = true)
                is Seg.Markdown -> {
                    val doc = remember(seg.src) { parser().parse(seg.src.trim()) }
                    RenderBlocks(doc.firstChild, inlineBudget)
                }
            }
        }
    }
}

private sealed class Seg {
    data class Markdown(val src: String) : Seg()

    data class Math(val latex: String) : Seg()
}

private fun splitDisplay(src: String): List<Seg> {
    if ("$$" !in src) return listOf(Seg.Markdown(src))
    val out = ArrayList<Seg>()
    var i = 0
    displayMath.findAll(src).forEach { m ->
        if (m.range.first > i) out.add(Seg.Markdown(src.substring(i, m.range.first)))
        out.add(Seg.Math(m.groupValues[1]))
        i = m.range.last + 1
    }
    if (i < src.length) out.add(Seg.Markdown(src.substring(i)))
    return out
}

private fun parser(): Parser =
    Parser.builder()
        .extensions(setOf(TablesExtension.create()))
        .build()

@Composable
private fun RenderBlocks(node: Node?, inlineBudget: androidx.compose.runtime.MutableIntState) {
    var n = node
    while (n != null) {
        when (n) {
            is Paragraph -> {
                val p = n
                SelectionContainer(
                    Modifier.padding(vertical = 2.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        ParagraphInlines(p.firstChild, inlineBudget)
                    }
                }
            }
            is Heading ->
                Text(
                    buildHeadingAnnotated(n.firstChild),
                    style =
                        when (n.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            is FencedCodeBlock -> CodeCard(n.info ?: "", n.literal)
            is BulletList -> ListBlock(n, bullet = true, inlineBudget)
            is OrderedList -> ListBlock(n, bullet = false, inlineBudget)
            is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
            is TableBlock -> GfmTable(n)
            else -> Unit
        }
        n = n.next
    }
}

@Composable
private fun ListBlock(root: Node, bullet: Boolean, inlineBudget: androidx.compose.runtime.MutableIntState) {
    var item = root.firstChild
    var ix = 1
    while (item is ListItem) {
        val prefix = if (bullet) "• " else "$ix. "
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        ) {
            Text(prefix, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
            val inner = item.firstChild
            if (inner is Paragraph) {
                Column(Modifier.weight(1f)) {
                    ParagraphInlines(inner.firstChild, inlineBudget)
                }
            }
        }
        ix++
        item = item.next
    }
}

@Composable
private fun ParagraphInlines(first: Node?, inlineBudget: androidx.compose.runtime.MutableIntState) {
    val linkColor = MaterialTheme.colorScheme.primary
    var cur = first
    while (cur != null) {
        when (cur) {
            is Text -> InlineMathPieces(cur.literal, inlineBudget)
            is Code ->
                Text(
                    cur.literal,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            is StrongEmphasis ->
                Text(
                    annotatedChildren(cur.firstChild, linkColor),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            is Emphasis ->
                Text(
                    annotatedChildren(cur.firstChild, linkColor),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                )
            is Link ->
                Text(
                    annotatedChildren(cur.firstChild, linkColor),
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                    color = linkColor,
                )
            is SoftLineBreak -> Text(" ", style = MaterialTheme.typography.bodyLarge)
            else -> Unit
        }
        cur = cur.next
    }
}

@Composable
private fun InlineMathPieces(raw: String, inlineBudget: androidx.compose.runtime.MutableIntState) {
    if ('$' !in raw) {
        Text(raw, style = MaterialTheme.typography.bodyLarge)
        return
    }
    var cursor = 0
    inlineMath.findAll(raw).forEach { m ->
        if (m.range.first > cursor) {
            Text(raw.substring(cursor, m.range.first), style = MaterialTheme.typography.bodyLarge)
        }
        val tex = m.groupValues[1]
        if (inlineBudget.intValue < 32) {
            inlineBudget.intValue++
            KatexWebView(tex, displayMode = false)
        } else {
            Text(
                "$$tex$",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        cursor = m.range.last + 1
    }
    if (cursor < raw.length) {
        Text(raw.substring(cursor), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun annotatedChildren(first: Node?, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var n = first
        while (n != null) {
            when (n) {
                is Text -> {
                    val t = n
                    append(t.literal)
                }
                is Code -> {
                    val c = n
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(c.literal)
                    }
                }
                is SoftLineBreak -> append(' ')
                is StrongEmphasis -> {
                    val s = n
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(annotatedChildren(s.firstChild, linkColor))
                    pop()
                }
                is Emphasis -> {
                    val e = n
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(annotatedChildren(e.firstChild, linkColor))
                    pop()
                }
                is Link -> {
                    val l = n
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(annotatedChildren(l.firstChild, linkColor))
                    pop()
                }
                else -> Unit
            }
            n = n.next
        }
    }

private fun buildHeadingAnnotated(first: Node?): AnnotatedString {
    val sb = StringBuilder()
    var n = first
    while (n != null) {
        when (n) {
            is Text -> sb.append(n.literal)
            is Code -> sb.append('`').append(n.literal).append('`')
            is SoftLineBreak, is HardLineBreak -> sb.append(' ')
            is StrongEmphasis, is Emphasis -> sb.append(recursePlain(n.firstChild))
            else -> Unit
        }
        n = n.next
    }
    return AnnotatedString(sb.toString())
}

private fun recursePlain(first: Node?): String {
    val sb = StringBuilder()
    var n = first
    while (n != null) {
        when (n) {
            is Text -> sb.append(n.literal)
            is Code -> sb.append(n.literal)
            is StrongEmphasis, is Emphasis -> sb.append(recursePlain(n.firstChild))
            is SoftLineBreak -> sb.append(' ')
            else -> Unit
        }
        n = n.next
    }
    return sb.toString()
}

@Composable
private fun CodeCard(info: String, body: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(160)) + expandVertically(animationSpec = tween(200)),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(10.dp)) {
                val label =
                    info.trim().ifBlank {
                        ctx.getString(R.string.markdown_code_plain)
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(body.trimEnd('\n')))
                            android.widget.Toast
                                .makeText(ctx, ctx.getString(R.string.markdown_code_copied), android.widget.Toast.LENGTH_SHORT)
                                .show()
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = ctx.getString(R.string.markdown_copy_code))
                    }
                }
                SelectionContainer {
                    Text(
                        body.trimEnd('\n'),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GfmTable(table: TableBlock) {
    val lines =
        remember(table) {
            val rows = mutableListOf<String>()
            var p = table.firstChild
            while (p != null) {
                if (p is TableHead || p is TableBody) {
                    var r = p.firstChild
                    while (r is TableRow) {
                        val cells = mutableListOf<String>()
                        var c = r.firstChild
                        while (c is TableCell) {
                            cells.add(tableCellText(c))
                            c = c.next
                        }
                        if (cells.isNotEmpty()) rows.add(cells.joinToString("  │  "))
                        r = r.next
                    }
                }
                p = p.next
            }
            rows
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style =
                        if (i == 0) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                if (i == 0 && lines.size > 1) HorizontalDivider()
            }
        }
    }
}

private fun tableCellText(cell: TableCell): String {
    val sb = StringBuilder()
    var n = cell.firstChild
    while (n != null) {
        if (n is Text) sb.append(n.literal)
        n = n.next
    }
    return sb.toString().trim()
}

@SuppressLint("SetJavaScriptEnabled", "DEPRECATION")
@Composable
fun KatexWebView(latex: String, displayMode: Boolean) {
    val escaped =
        remember(latex) {
            latex
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r\n", " ")
                .replace("\n", " ")
        }
    val html =
        remember(escaped, displayMode) {
            val dm = if (displayMode) "true" else "false"
            """
            <!DOCTYPE html><html><head>
            <meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
            <link rel="stylesheet" href="katex.min.css"/>
            <script src="katex.min.js"></script>
            </head><body style="margin:0;padding:4px;background:transparent;">
            <div id="k"></div>
            <script>
            (function(){
              function r(){
                try { katex.render('$escaped', document.getElementById('k'), { displayMode: $dm, throwOnError: false }); } catch(e){}
              }
              if (document.readyState === 'complete') r(); else window.addEventListener('load', r);
            })();
            </script>
            </body></html>
            """.trimIndent()
        }
    AndroidView(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (displayMode) 48.dp else 36.dp, max = if (displayMode) 480.dp else 160.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "UTF-8", null)
            }
        },
        update = { it.loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "UTF-8", null) },
    )
}
