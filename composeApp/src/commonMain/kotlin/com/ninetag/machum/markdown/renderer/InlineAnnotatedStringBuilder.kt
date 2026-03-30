package com.ninetag.machum.markdown.renderer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.ninetag.machum.markdown.token.InlineToken

fun buildInlineAnnotatedString(
    tokens: List<InlineToken>,
    linkColor: Color = Color(0xFF1565C0),
    highlightColor: Color = Color(0xFFFFEB3B),
    codeBackground: Color = Color(0x22000000),
): AnnotatedString = buildAnnotatedString {
    appendTokens(tokens, linkColor, highlightColor, codeBackground)
}

private fun AnnotatedString.Builder.appendTokens(
    tokens: List<InlineToken>,
    linkColor: Color,
    highlightColor: Color,
    codeBackground: Color,
) {
    for (token in tokens) {
        when (token) {
            is InlineToken.Text -> append(token.value)

            is InlineToken.LineBreak -> append("\n")

            is InlineToken.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendTokens(token.children, linkColor, highlightColor, codeBackground)
            }

            is InlineToken.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendTokens(token.children, linkColor, highlightColor, codeBackground)
            }

            is InlineToken.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendTokens(token.children, linkColor, highlightColor, codeBackground)
            }

            is InlineToken.Highlight -> withStyle(SpanStyle(background = highlightColor)) {
                appendTokens(token.children, linkColor, highlightColor, codeBackground)
            }

            is InlineToken.InlineCode -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
            ) {
                append(token.value)
            }

            is InlineToken.WikiLink -> {
                val display = token.alias ?: token.target
                withStyle(SpanStyle(color = linkColor)) {
                    pushStringAnnotation("WIKI_LINK", token.target)
                    append(display)
                    pop()
                }
            }

            is InlineToken.EmbedLink -> withStyle(SpanStyle(color = linkColor.copy(alpha = 0.7f))) {
                pushStringAnnotation("EMBED_LINK", token.fileName)
                append("![[${token.fileName}]]")
                pop()
            }

            is InlineToken.ExternalLink -> withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            ) {
                pushStringAnnotation("URL", token.url)
                append(token.text)
                pop()
            }
        }
    }
}
