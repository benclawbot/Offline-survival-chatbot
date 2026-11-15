package com.localchat.ui.chat

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.latex.JLatexMathTheme
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markwon-based рендер: markdown + GFM таблицы + HTML + autolinking + LaTeX
 * через JLatexMath. $$...$$ — block formula, $...$ — inline. Markwon ставит
 * формулы как ImageSpan'ы внутри обычного TextView, поэтому системное
 * выделение текста работает as-is.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val ctx = LocalContext.current
    val argb = color.toArgb()
    // Markwon-инстанс тяжёлый (LaTeX/Tables/HTML) — переиспользуем на ctx, цвет
    // меняется через TextView.setTextColor.
    val markwon = remember(ctx) { buildMarkwon(ctx) }
    val rendered = remember(text) { normalizeMarkdownMath(text) }
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) {
        style.fontSize.takeIf { it != TextUnit.Unspecified && it.type == TextUnitType.Sp }
            ?.toPx() ?: 16f
    }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextView(c).apply {
                setTextColor(argb)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                includeFontPadding = false
                setLineSpacing(0f, 1.08f)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
            }
        },
        update = { tv ->
            tv.setTextColor(argb)
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
            markwon.setMarkdown(tv, rendered)
        },
    )
}

private fun normalizeMarkdownMath(input: String): String = input
    .replace("\\[", "$$")
    .replace("\\]", "$$")
    .replace("\\(", "$")
    .replace("\\)", "$")

private fun buildMarkwon(context: Context): Markwon =
    Markwon.builder(context)
        // ВАЖНО: MarkwonInlineParserPlugin обязателен для JLatexMathPlugin (inlinesEnabled=true).
        // Без него JLatexMathPlugin.configure() кидает IllegalStateException.
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(HtmlPlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(
            JLatexMathPlugin.create(46f) { builder ->
                builder.inlinesEnabled(true)
                builder.theme()
                    .inlinePadding(JLatexMathTheme.Padding.symmetric(4, 0))
                    .blockPadding(JLatexMathTheme.Padding.symmetric(8, 8))
                    .blockHorizontalAlignment(android.view.Gravity.CENTER)
            }
        )
        .build()

