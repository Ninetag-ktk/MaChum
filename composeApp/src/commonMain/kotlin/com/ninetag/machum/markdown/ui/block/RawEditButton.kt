package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ninetag.machum.theme.platformUsesTouchUi
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import kotlin.math.roundToInt

internal fun inlineLinkActionSlotStyle(textStyle: TextStyle, density: Density): SpanStyle {
    val style = normalizedMarkdownTextStyle(textStyle)
    // Match Compose's resolved default instead of changing an unspecified field's font size.
    val fontSize = style.fontSize.takeOrElse { 14.sp }
    return SpanStyle(
        fontSize = fontSize,
        letterSpacing = with(density) { (MarkdownEditorStyleTokens.inlineActionHorizontalPadding * 2).toSp() },
    )
}

/** Keeps the raw-edit affordance outside the measured width of structured block content. */
@Composable
internal fun RawEditableBlock(
    focused: Boolean,
    onRawEdit: () -> Unit,
    modifier: Modifier = Modifier,
    buttonEndPadding: Dp = 0.dp,
    showRawEditButton: Boolean = true,
    buttonSize: Dp = MarkdownEditorStyleTokens.blockActionSize,
    buttonTopPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var descendantFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = buttonSize)
            .hoverable(hoverInteraction)
            .onFocusChanged { descendantFocused = it.hasFocus },
    ) {
        content()
        if (showRawEditButton && (focused || descendantFocused || hovered)) {
            Box(Modifier.matchParentSize().zIndex(1f)) {
                RawEditButton(
                    onClick = onRawEdit,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = buttonTopPadding, end = buttonEndPadding),
                    buttonSize = buttonSize,
                )
            }
        }
    }
}

@Composable
internal fun RawEditButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = MarkdownEditorStyleTokens.blockActionSize,
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(RoundedCornerShape(MarkdownEditorStyleTokens.blockActionCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
            .semantics { contentDescription = "원문 편집" }
            .clickable(
                role = Role.Button,
                onClickLabel = "원문 편집",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MarkdownEditorStyleTokens.blockActionIconSize),
        )
    }
}

/** Overlays the affordance on its reserved slot while keeping it inside scroll-container clipping. */
@Composable
internal fun RawEditOverlay(
    slotBounds: Rect,
    buttonSize: Dp,
    horizontalGap: Dp = 0.dp,
    onClick: () -> Unit,
    onHoverChanged: (Boolean) -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    var wasHovered by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(hovered) {
        if (hovered) {
            wasHovered = true
            onHoverChanged(true)
        } else if (wasHovered) {
            onHoverChanged(false)
        }
    }
    DisposableEffect(Unit) {
        onDispose { onHoverChanged(false) }
    }

    val gapPx = with(LocalDensity.current) { (horizontalGap + MarkdownEditorStyleTokens.inlineActionHorizontalPadding).roundToPx() }
    InlineRawEditButton(
        onClick = onClick,
        modifier = Modifier
            .offset {
                IntOffset(
                    x = slotBounds.left.roundToInt() + gapPx,
                    y = slotBounds.top.roundToInt(),
                )
            }
            .hoverable(hoverInteraction)
            .zIndex(1f),
        buttonSize = buttonSize,
    )
}

@Composable
internal fun ExternalLinkIndicatorOverlay(
    slotBounds: Rect,
    buttonSize: Dp,
    onClick: () -> Unit,
    onHovered: () -> Unit,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(hovered) {
        if (hovered) onHovered()
    }
    val paddingPx = with(LocalDensity.current) { MarkdownEditorStyleTokens.inlineActionHorizontalPadding.roundToPx() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = slotBounds.left.roundToInt() + paddingPx,
                    y = slotBounds.top.roundToInt(),
                )
            }
            .size(buttonSize)
            .hoverable(hoverInteraction)
            .semantics { contentDescription = "외부 링크" }
            .then(
                if (platformUsesTouchUi) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "외부 링크 열기",
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .zIndex(1f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MarkdownEditorStyleTokens.blockActionIconSize),
        )
    }
}

@Composable
private fun InlineRawEditButton(
    onClick: () -> Unit,
    modifier: Modifier,
    buttonSize: Dp,
) {
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(RoundedCornerShape(MarkdownEditorStyleTokens.blockActionCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
            .semantics { contentDescription = "원문 편집" }
            .clickable(
                role = Role.Button,
                onClickLabel = "원문 편집",
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Code,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(MarkdownEditorStyleTokens.blockActionIconSize),
        )
    }
}
