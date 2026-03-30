package com.ninetag.machum.markdown.editor

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * [spans] 목록을 순회하며 BasicTextField 의 출력 텍스트에 SpanStyle 을 적용한다.
 * Composable 이 [spans] 를 읽는 시점에 생성되므로, spans 변경 → recomposition → 새 인스턴스로 교체된다.
 */
internal class MarkdownEditorOutputTransformation(
    private val spans: List<MarkupStyleRange>,
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        spans.forEach { span ->
            val start = span.start.coerceIn(0, length)
            val end   = span.end.coerceIn(start, length)
            if (start >= end) return@forEach

            val style = span.style.toSpanStyle() ?: return@forEach
            addStyle(style, start, end)
        }
    }
}

private fun MarkupStyle.toSpanStyle(): SpanStyle? = when (this) {
    MarkupStyle.Bold          -> SpanStyle(fontWeight = FontWeight.Bold)
    MarkupStyle.Italic        -> SpanStyle(fontStyle = FontStyle.Italic)
    MarkupStyle.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    MarkupStyle.Highlight     -> SpanStyle(background = Color(0xFFFFFF00).copy(alpha = 0.4f))
    MarkupStyle.InlineCode    -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color(0x22888888),
    )
    MarkupStyle.H1 -> SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
    MarkupStyle.H2 -> SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
    MarkupStyle.H3 -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    MarkupStyle.H4 -> SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    MarkupStyle.H5 -> SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    MarkupStyle.H6 -> SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    MarkupStyle.BulletList   -> SpanStyle(color = Color(0xFF888888))
    MarkupStyle.OrderedList  -> SpanStyle(color = Color(0xFF888888))
    MarkupStyle.Blockquote   -> SpanStyle(color = Color(0xFF888888))
    MarkupStyle.WikiLink     -> SpanStyle(
        color = Color(0xFF4488FF),
        textDecoration = TextDecoration.Underline,
    )
    MarkupStyle.ExternalLink -> SpanStyle(
        color = Color(0xFF4488FF),
        textDecoration = TextDecoration.Underline,
    )
}
