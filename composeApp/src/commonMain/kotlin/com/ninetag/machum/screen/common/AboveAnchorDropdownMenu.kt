package com.ninetag.machum.screen.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** The footer selector has an explicit upward direction; other menus keep Material positioning. */
@Composable
internal fun AboveAnchorDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    anchorBounds: IntRect?,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = rememberMenuMotion(expanded)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val safe = WindowInsets.safeDrawing
    val bounds = IntRect(safe.getLeft(density, direction), safe.getTop(density),
        windowSize.width - safe.getRight(density, direction), windowSize.height - safe.getBottom(density))
    val gap = with(density) { 4.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    val maxHeight = with(density) { PopupUiMetrics.MenuMaxHeight.roundToPx() }
    if (!motion.present || anchorBounds == null) return
    val height = aboveAnchorAvailableHeight(anchorBounds, bounds, gap, margin, maxHeight)
    val availableWidth = (bounds.width - margin * 2).coerceAtLeast(0)
    if (height == 0 || availableWidth == 0) return
    val provider = remember(gap, margin, bounds) { AboveAnchorPositionProvider(gap, margin, bounds) }
    Popup(popupPositionProvider = provider, onDismissRequest = { if (expanded) onDismissRequest() },
        properties = PopupProperties(focusable = true)) {
        MenuMotionContent(expanded, motion) {
        val focusManager = LocalFocusManager.current
        val inputModeManager = LocalInputModeManager.current
        val initialFocus = remember { FocusRequester() }
        var initialFocusAllowed by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { initialFocus.requestFocus() }
        Box(Modifier.onKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionDown -> FocusDirection.Next
                Key.DirectionUp -> FocusDirection.Previous
                else -> null
            }
            if (event.type == KeyEventType.KeyDown && direction != null) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                focusManager.moveFocus(direction)
                true
            } else false
        }.focusRequester(initialFocus)
            .focusProperties { canFocus = initialFocusAllowed }
            .onFocusChanged { if (it.hasFocus && !it.isFocused) initialFocusAllowed = false }
            .focusable()) {
        DrawerMenuSurface(
            width = with(density) { minOf(width.roundToPx(), availableWidth).toDp() },
            maxHeight = with(density) { height.toDp() }, scrollState = scrollState, content = content,
        )
        }
        }
    }
}

internal fun aboveAnchorAvailableHeight(anchor: IntRect, safeBounds: IntRect, gap: Int, margin: Int, maxHeight: Int): Int =
    (minOf(anchor.top - gap, safeBounds.bottom - margin) - safeBounds.top - margin)
        .coerceIn(0, maxHeight)

/** Position uses Popup's actual parent bounds, not a guessed screen/mirror coordinate. */
internal class AboveAnchorPositionProvider(
    private val gap: Int,
    private val margin: Int,
    private val safeBounds: IntRect,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val left = safeBounds.left.coerceAtLeast(0) + margin
        val top = safeBounds.top.coerceAtLeast(0) + margin
        // Android's windowSize may describe a visible frame with its origin stripped.
        // Both safeBounds (containerSize/insets) and anchorBounds use window coordinates.
        val right = safeBounds.right - margin
        val bottom = safeBounds.bottom - margin
        val desiredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val x = desiredX.coerceIn(left, maxOf(left, right - popupContentSize.width))
        val y = (anchorBounds.top - gap - popupContentSize.height)
            .coerceIn(top, maxOf(top, bottom - popupContentSize.height))
        return IntOffset(x, y)
    }
}
