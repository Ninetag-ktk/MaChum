package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import com.ninetag.machum.markdown.service.CalloutDecorationStyle
import com.ninetag.machum.markdown.service.CalloutLayout
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.markdown.service.MarkdownStyleConfig

internal fun normalizedMarkdownTextStyle(style: TextStyle): TextStyle = style.copy(
    lineHeight = style.lineHeight.takeOrElse { MarkdownEditorStyleTokens.bodyLineHeight },
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
        mode = LineHeightStyle.Mode.Fixed,
    ),
)

/** Only visual layout is shared; the slots own input or static text. */
@Composable
internal fun CalloutVisualLayout(
    type: String,
    decoration: CalloutDecorationStyle,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    title: @Composable (TextStyle, Modifier) -> Unit,
    body: @Composable (TextStyle, Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val titleStyle = textStyle.merge(TextStyle(fontWeight = FontWeight.Bold))
    if (decoration.layout == CalloutLayout.Horizontal) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .background(decoration.containerColor, shape)
                .heightIn(min = MarkdownEditorStyleTokens.blockActionSize)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            title(
                titleStyle,
                Modifier.width(textStyle.fontSize.value.dp * 5)
                    .padding(end = 4.dp),
            )
            body(textStyle, Modifier.weight(1f))
        }
    } else {
        Column(
            modifier.fillMaxWidth().background(decoration.containerColor, shape)
                .heightIn(min = MarkdownEditorStyleTokens.blockActionSize)
                .border(1.dp, decoration.accentColor, shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(calloutIcon(type), type, tint = decoration.accentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                title(titleStyle, Modifier.weight(1f))
            }
            body(
                textStyle.merge(TextStyle(
                    fontSize = textStyle.fontSize * 0.9f,
                    lineHeight = textStyle.lineHeight.takeOrElse { MarkdownEditorStyleTokens.bodyLineHeight } * 0.9f,
                )),
                Modifier,
            )
        }
    }
}

internal fun Modifier.codeBlockVisualLayout(config: MarkdownStyleConfig): Modifier =
    fillMaxWidth()
        .background(config.codeBlock.backgroundColor, RoundedCornerShape(config.codeBlock.cornerRadius))
        .heightIn(min = MarkdownEditorStyleTokens.blockActionSize)
        .padding(config.codeBlock.padding)
