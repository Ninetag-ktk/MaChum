package com.ninetag.machum.markdown.ui

import com.ninetag.machum.markdown.service.*
import com.ninetag.machum.markdown.state.*

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em

/**
 * Material3 테마 값을 사용하는 마크다운 편집 컴포지션.
 *
 * [MaterialTheme] 의 색상·타이포그래피를 자동으로 적용한다.
 * 테마 없이 사용하려면 [MarkdownBasicTextField] 를 직접 사용.
 */
@Composable
fun MarkdownTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    styleConfig: MarkdownStyleConfig = defaultMaterialStyleConfig(),
) {
    MarkdownBasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        styleConfig = styleConfig,
    )
}

@Composable
private fun defaultMaterialStyleConfig(): MarkdownStyleConfig {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val codeBlockBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    // Material3 테마 기반 Callout 색상
    val scheme = MaterialTheme.colorScheme
    val calloutStyles = remember(scheme) {
        mapOf(
            "NOTE" to CalloutDecorationStyle(
                scheme.primaryContainer.copy(alpha = 0.4f), scheme.primary
            ),
            "TIP" to CalloutDecorationStyle(
                scheme.tertiaryContainer.copy(alpha = 0.4f), scheme.tertiary
            ),
            "IMPORTANT" to CalloutDecorationStyle(
                scheme.secondaryContainer.copy(alpha = 0.4f), scheme.secondary
            ),
            "WARNING" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.3f), scheme.error.copy(alpha = 0.7f)
            ),
            "DANGER" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.4f), scheme.error
            ),
            "CAUTION" to CalloutDecorationStyle(
                scheme.errorContainer.copy(alpha = 0.4f), scheme.error
            ),
            "QUESTION" to CalloutDecorationStyle(
                scheme.surfaceVariant.copy(alpha = 0.5f), scheme.onSurfaceVariant
            ),
            "SUCCESS" to CalloutDecorationStyle(
                scheme.tertiaryContainer.copy(alpha = 0.4f), scheme.tertiary
            ),
        )
    }

    return remember(linkColor, codeBackground, highlightColor, codeBlockBg, calloutStyles) {
        MarkdownStyleConfig(
            link = SpanStyle(color = linkColor),
            highlight = SpanStyle(background = highlightColor),
            codeInline = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = codeBackground,
                fontSize = 0.85.em,
            ),
            codeBlockBackground = codeBlockBg,
            calloutStyles = calloutStyles,
        )
    }
}
