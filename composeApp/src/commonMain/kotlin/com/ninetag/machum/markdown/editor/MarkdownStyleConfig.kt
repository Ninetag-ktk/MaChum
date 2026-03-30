package com.ninetag.machum.markdown.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 마크다운 서식에 사용할 SpanStyle 설정.
 * 모든 필드에 기본값이 있으므로, 필요한 스타일만 오버라이드하여 사용 가능.
 */
data class MarkdownStyleConfig(
    // 마커 (투명 처리)
    val marker: SpanStyle = SpanStyle(fontSize = 0.01.sp, color = Color.Transparent),
    // 인라인 서식
    val bold: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
    val italic: SpanStyle = SpanStyle(fontStyle = FontStyle.Italic),
    val boldItalic: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
    val strikethrough: SpanStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
    val highlight: SpanStyle = SpanStyle(background = Color(0xFFFFEB3B)),
    val codeInline: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000), fontSize = 0.85.em),
    val codeBlock: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace),
    val link: SpanStyle = SpanStyle(color = Color(0xFF1565C0)),
    // 헤딩
    val h1: SpanStyle = SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    val h2: SpanStyle = SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    val h3: SpanStyle = SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    val h4: SpanStyle = SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
    val h5: SpanStyle = SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    val h6: SpanStyle = SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
) {
    fun headingStyle(level: Int): SpanStyle = when (level) {
        1 -> h1; 2 -> h2; 3 -> h3; 4 -> h4; 5 -> h5; else -> h6
    }
}
