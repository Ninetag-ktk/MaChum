package com.ninetag.machum.markdown.service

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
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
    val layout: CalloutLayout = CalloutLayout.Vertical,
)

enum class CalloutLayout {
    Vertical,
    Horizontal,
}

/** Inline code typography and decoration are kept together so text and outline cannot drift. */
data class InlineCodeStyle(
    val text: SpanStyle = SpanStyle(
        fontSize = MarkdownEditorStyleTokens.inlineCodeFontSize,
        color = Color(0xFF1565C0),
    ),
    val borderColor: Color = Color(0xFF1565C0),
    val borderWidth: Dp = MarkdownEditorStyleTokens.inlineCodeBorderWidth,
    val horizontalPadding: Dp = MarkdownEditorStyleTokens.inlineCodeHorizontalPadding,
    val cornerRadius: Dp = MarkdownEditorStyleTokens.inlineCodeCornerRadius,
)

/** Fenced code block typography and container decoration. */
data class CodeBlockStyle(
    val text: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace),
    val backgroundColor: Color = Color(0x11000000),
    val padding: Dp = MarkdownEditorStyleTokens.codeBlockPadding,
    val cornerRadius: Dp = MarkdownEditorStyleTokens.codeBlockCornerRadius,
)

/**
 * 마크다운 서식에 사용할 인라인·블록 시각 설정.
 * 모든 필드에 기본값이 있으므로, 필요한 스타일만 오버라이드하여 사용 가능.
 */
data class MarkdownStyleConfig(
    // preview 마커는 OutputTransformation에서 제거한다. 스타일은 raw 전환 시 글꼴 메트릭을 바꾸지 않는다.
    val marker: SpanStyle = SpanStyle(),
    // preview에서 실제 출력 문자열에서도 제거할 링크 문법·목록 선행 공백
    val hiddenSyntax: SpanStyle = SpanStyle(fontSize = 0.01.sp, color = Color.Transparent),
    // 인라인 서식
    val bold: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
    val italic: SpanStyle = SpanStyle(fontStyle = FontStyle.Italic),
    val boldItalic: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
    val strikethrough: SpanStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
    val highlight: SpanStyle = SpanStyle(background = Color(0xFFFFEB3B)),
    val inlineCode: InlineCodeStyle = InlineCodeStyle(),
    val codeBlock: CodeBlockStyle = CodeBlockStyle(),
    val link: SpanStyle = SpanStyle(color = Color(0xFF1565C0)),
    val unresolvedLink: SpanStyle = SpanStyle(color = Color(0xFF6F7890)),
    val checkedTaskBody: SpanStyle = SpanStyle(textDecoration = TextDecoration.LineThrough),
    // 헤딩
    val h1: SpanStyle = SpanStyle(fontSize = MarkdownEditorStyleTokens.heading1FontSize, fontWeight = FontWeight.Bold),
    val h2: SpanStyle = SpanStyle(fontSize = MarkdownEditorStyleTokens.heading2FontSize, fontWeight = FontWeight.Bold),
    val h3: SpanStyle = SpanStyle(fontSize = MarkdownEditorStyleTokens.heading3FontSize, fontWeight = FontWeight.Bold),
    val h4: SpanStyle = SpanStyle(fontSize = MarkdownEditorStyleTokens.heading4FontSize, fontWeight = FontWeight.Bold),
    val h5: SpanStyle = SpanStyle(fontSize = MarkdownEditorStyleTokens.heading5FontSize, fontWeight = FontWeight.Bold),
    val h6: SpanStyle = SpanStyle(fontSize = MarkdownEditorStyleTokens.heading6FontSize, fontWeight = FontWeight.Bold),
    // 블록 prefix (리스트 마커 — 숨기지 않고 연한 색으로 표시)
    val bulletPrefix: SpanStyle = SpanStyle(color = Color(0x66000000)),
    val orderedPrefix: SpanStyle = SpanStyle(
        color = Color(0x66000000),
        fontFeatureSettings = MarkdownEditorStyleTokens.orderedListFontFeatures,
    ),
    val blockquoteAccent: Color = Color(0xFF9E9E9E),
    val horizontalRuleColor: Color = Color(0x33000000),
    // 블록 데코레이션
    val blockTransparent: SpanStyle = SpanStyle(color = Color.Transparent),
    val calloutStyles: Map<String, CalloutDecorationStyle> = defaultCalloutStyles(),
    /** Cross-block selection 의 atomic 블록 배경. M3 테마에서는 primary.copy(alpha=0.2) 등으로 덮어씀 */
    val selectionAccent: Color = Color(0x33458EFF),
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
    "DL" to CalloutDecorationStyle(
        containerColor = Color(0x1A1565C0),
        accentColor = Color(0xFF1565C0),
        layout = CalloutLayout.Horizontal,
    ),
)
