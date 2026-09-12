package com.ninetag.machum.screen.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ninetag.machum.theme.WorkspaceUiMetrics

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DrawerDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    MotionDropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
    ) {
        DrawerMenuSurface(width, PopupUiMetrics.MenuMaxHeight, scrollState, content)
    }
}

@Composable
internal fun DrawerMenuSurface(
    width: Dp,
    maxHeight: Dp,
    scrollState: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
        Surface(
            modifier = Modifier.width(width),
            shape = MaterialTheme.shapes.extraLarge,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(0.dp),
                    content = content,
                )
                PolicyVerticalScrollbar(
                    state = scrollState,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
}

@Composable
internal fun DrawerMenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean? = null,
    leadingIcon: ImageVector? = null,
    danger: Boolean = false,
    bringIntoViewRequester: BringIntoViewRequester? = null,
) {
    val selectedModifier = selected?.let { isSelected ->
        Modifier.semantics { this.selected = isSelected }
    } ?: Modifier
    val bringIntoViewModifier = bringIntoViewRequester?.let { requester ->
        Modifier.bringIntoViewRequester(requester)
    } ?: Modifier

    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = WorkspaceUiMetrics.bodyTextStyle,
                color = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
        },
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PopupUiMetrics.RowMinHeight)
            .background(
                if (selected == true) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .then(selectedModifier)
            .then(bringIntoViewModifier),
        enabled = enabled,
        leadingIcon = leadingIcon?.let { imageVector ->
            {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    modifier = Modifier.size(PopupUiMetrics.IconSize),
                    tint = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current,
                )
            }
        },
        trailingIcon = if (selected == true) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(PopupUiMetrics.IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        contentPadding = PaddingValues(horizontal = PopupUiMetrics.ListPadding),
    )
}

