package com.ninetag.machum.markdown.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.token.MarkdownBlock

@Composable
fun CalloutRenderer(block: MarkdownBlock.Callout, modifier: Modifier = Modifier) {
    val style = calloutStyleFor(block.type)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(style.containerColor, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = block.type,
                    tint = style.contentColor,
                    modifier = Modifier.size(18.dp),
                )
                if (block.title.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = block.title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = style.contentColor,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
            block.body.forEach { childBlock ->
                BlockRenderer(childBlock, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
