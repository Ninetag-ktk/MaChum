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
 * Callout 블록의 시각 스타일.
 *
 * @param containerColor 배경색
 * @param accentColor    왼쪽 테두리 색상
 */
data class CalloutDecorationStyle(
    val containerColor: Color,
    val accentColor: Color,
)

/**
 * 마크다운 서식에 사용할 SpanStyle 및 블록 데코레이션 설정.
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
    // 블록 데코레이션
    val codeBlockBackground: Color = Color(0x11000000),
    val calloutStyles: Map<String, CalloutDecorationStyle> = defaultCalloutStyles(),
) {
    fun headingStyle(level: Int): SpanStyle = when (level) {
        1 -> h1; 2 -> h2; 3 -> h3; 4 -> h4; 5 -> h5; else -> h6
    }

    fun calloutDecorationStyle(type: String): CalloutDecorationStyle =
        calloutStyles[type.uppercase()] ?: calloutStyles["NOTE"]!!
}

fun defaultCalloutStyles(): Map<String, CalloutDecorationStyle> = mapOf(
    "NOTE" to CalloutDecorationStyle(Color(0x1A1565C0), Color(0xFF1565C0)),
    "TIP" to CalloutDecorationStyle(Color(0x1A00897B), Color(0xFF00897B)),
    "IMPORTANT" to CalloutDecorationStyle(Color(0x1A6A1B9A), Color(0xFF6A1B9A)),
    "WARNING" to CalloutDecorationStyle(Color(0x1AE65100), Color(0xFFE65100)),
    "DANGER" to CalloutDecorationStyle(Color(0x1AC62828), Color(0xFFC62828)),
    "CAUTION" to CalloutDecorationStyle(Color(0x1AC62828), Color(0xFFC62828)),
    "QUESTION" to CalloutDecorationStyle(Color(0x1A4527A0), Color(0xFF4527A0)),
    "SUCCESS" to CalloutDecorationStyle(Color(0x1A2E7D32), Color(0xFF2E7D32)),
)
