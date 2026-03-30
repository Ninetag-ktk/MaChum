package com.ninetag.machum.markdown.editor

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
    return remember(linkColor, codeBackground, highlightColor) {
        MarkdownStyleConfig(
            link = SpanStyle(color = linkColor),
            highlight = SpanStyle(background = highlightColor),
            codeInline = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = codeBackground,
                fontSize = 0.85.em,
            ),
        )
    }
}
