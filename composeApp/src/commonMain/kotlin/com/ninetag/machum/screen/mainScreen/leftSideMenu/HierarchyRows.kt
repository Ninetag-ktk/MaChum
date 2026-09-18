package com.ninetag.machum.screen.mainScreen.leftSideMenu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.screen.common.PopupUiMetrics

/** Plot stages and General sources share presentation; their data operations stay with their callers. */
@Composable
internal fun HierarchyGroupRow(
    label: String,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCreate: (() -> Unit)?,
    hasChildren: Boolean,
    createEnabled: Boolean = true,
    highlighted: Boolean = false,
    dropAvailable: Boolean = false,
    dropHovered: Boolean = false,
    selected: Boolean = false,
    onContextMenu: (() -> Unit)? = null,
    nameEditor: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    Box(modifier.fillMaxWidth().hierarchyDepthGuides(depth, guide)
        .then(if (expanded && hasChildren) Modifier.groupConnectionGuide(depth, guide, header = true) else Modifier)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = WorkspaceUiMetrics.hierarchyIndentStep * depth)
                .testTag("hierarchy-group:$label")
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || (it.key == Key.F10 && it.isShiftPressed)) && onContextMenu != null) {
                        onContextMenu(); true
                    } else false
                }
                .semantics {
                    stateDescription = buildString {
                        append(if (!hasChildren) "빈 구분" else if (expanded) "펼쳐짐" else "접힘")
                        if (dropHovered) append(", 파일 이동 위치")
                        else if (dropAvailable) append(", 파일 이동 대상")
                    }
                    this.selected = selected
                    onContextMenu?.let { open -> customActions = listOf(CustomAccessibilityAction("구분 메뉴") { open(); true }) }
                }
                .then(if (nameEditor == null && (hasChildren || onContextMenu != null)) Modifier.hierarchyClickable(
                    onClick = { if (hasChildren) onToggle() }, onContextMenu = onContextMenu,
                ) else Modifier),
            color = when {
                dropHovered -> MaterialTheme.colorScheme.secondaryContainer
                highlighted || dropAvailable -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                selected -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> Color.Transparent
            },
            tonalElevation = if (dropHovered) 2.dp else 0.dp,
            shape = MaterialTheme.shapes.small,
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight), verticalAlignment = Alignment.CenterVertically) {
                if (nameEditor != null) HierarchyIconButton(
                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "$label 구분", onClick = null,
                    iconModifier = Modifier.testTag("hierarchy-group-icon:$label"),
                ) else HierarchyToggleIcon(label, expanded, hasChildren, Icons.AutoMirrored.Filled.FormatListBulleted, onToggle,
                    iconTag = "hierarchy-group-icon:$label")
                Row(
                    Modifier.weight(1f).heightIn(min = WorkspaceUiMetrics.hierarchyActionSize),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (nameEditor != null) Box(Modifier.weight(1f).padding(start = 2.dp)) { nameEditor() }
                    else Text(label, Modifier.weight(1f).padding(start = 2.dp, end = 8.dp).testTag("hierarchy-label:$label"), style = WorkspaceUiMetrics.labelTextStyle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (nameEditor == null && onCreate != null) HierarchyIconButton(
                    imageVector = Icons.Default.Add,
                    contentDescription = "$label 새 파일",
                    onClick = onCreate,
                    enabled = createEnabled,
                    iconModifier = Modifier.testTag("hierarchy-create-icon:$label"),
                ) else if (nameEditor == null) Spacer(Modifier.size(WorkspaceUiMetrics.hierarchyFolderRowHeight))
            }
        }
    }
}

@Composable
internal fun HierarchyDocumentRow(
    label: String,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
    @Suppress("ModifierParameter") dragModifier: Modifier = Modifier,
    dragDescription: String? = null,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    groupChild: Boolean = false,
    onTrashRequested: (() -> Unit)? = null,
    @Suppress("ModifierParameter") bodyDragModifier: Modifier = Modifier,
    showMenuAction: Boolean = false,
    contextMenuOnLongPress: Boolean = true,
    moveDragging: Boolean = false,
) {
    var menuExpanded by remember(label) { mutableStateOf(false) }
    val openMenu: (() -> Unit)? = onTrashRequested?.let { { menuExpanded = true } }
    val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    val start = WorkspaceUiMetrics.hierarchyIndentStep * depth
    val guides = if (groupChild) Modifier.hierarchyDepthGuides((depth - 1).coerceAtLeast(0), guide)
        .groupConnectionGuide((depth - 1).coerceAtLeast(0), guide, header = false)
        else Modifier.hierarchyDepthGuides(depth, guide)
    Box(modifier.fillMaxWidth().then(guides)) {
        Surface(
            Modifier.fillMaxWidth().padding(start = start).testTag("hierarchy-document:$label")
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || (it.key == Key.F10 && it.isShiftPressed)) && openMenu != null) {
                        openMenu(); true
                    } else false
                }.semantics {
                    this.selected = selected
                    if (moveDragging) stateDescription = "파일 이동 중"
                    openMenu?.let { open -> customActions = listOf(CustomAccessibilityAction("파일 메뉴") { open(); true }) }
                }.hierarchyClickable(
                    onClick = onClick,
                    onContextMenu = openMenu,
                    onLongClick = openMenu.takeIf { contextMenuOnLongPress },
                ),
            color = when {
                moveDragging -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
                selected -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> Color.Transparent
            },
            shape = MaterialTheme.shapes.small,
            border = if (moveDragging) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
            tonalElevation = if (moveDragging) 2.dp else 0.dp,
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).then(bodyDragModifier), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(WorkspaceUiMetrics.hierarchyActionSize).then(dragModifier), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Description, dragDescription, Modifier.size(WorkspaceUiMetrics.hierarchyIconSize).testTag("hierarchy-file-icon:$label"),
                            tint = if (selected || moveDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f).padding(start = 2.dp, end = 8.dp)) {
                        Text(label, modifier = Modifier.testTag("hierarchy-label:$label"), style = WorkspaceUiMetrics.secondaryTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        supportingText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (trailingAction != null || (showMenuAction && openMenu != null)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        trailingAction?.invoke()
                        if (showMenuAction && openMenu != null) HierarchyIconButton(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "$label 파일 메뉴",
                            onClick = openMenu,
                        )
                    }
                } else {
                    Spacer(Modifier.size(WorkspaceUiMetrics.hierarchyFolderRowHeight))
                }
            }
        }
        DrawerDropdownMenu(menuExpanded && onTrashRequested != null, { menuExpanded = false }, PopupUiMetrics.MenuWidth) {
            DropdownMenuItem(
                text = { Text("휴지통으로 이동…", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menuExpanded = false; onTrashRequested?.invoke() },
            )
        }
    }
}

internal data class HierarchyFileMoveDestination(
    val label: String,
    val folderKey: FolderKey,
    val plotStage: PlotStage?,
)

/** Owns hierarchy action geometry and icon rendering on both Desktop and touch UI. */
@Composable
internal fun HierarchyIconButton(
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
    contentDescription: String,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    tint: Color? = null,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val iconTint = tint ?: LocalContentColor.current
    val action = if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = interactions,
        indication = LocalIndication.current,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    )
    Box(
        modifier = modifier.size(WorkspaceUiMetrics.hierarchyActionSize).then(action),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(WorkspaceUiMetrics.hierarchyIconSize).then(iconModifier),
            tint = iconTint.copy(alpha = iconTint.alpha * if (enabled) 1f else 0.38f),
        )
    }
}

/** Branch coordinates use the real group icon slot, not the folder expand-button column. */
private fun Modifier.groupConnectionGuide(parentDepth: Int, color: Color, header: Boolean): Modifier = drawWithContent {
    drawContent()
    val slot = WorkspaceUiMetrics.hierarchyActionSize.toPx()
    val indent = WorkspaceUiMetrics.hierarchyIndentStep.toPx()
    val icon = WorkspaceUiMetrics.hierarchyIconSize.toPx()
    val parentX = parentDepth * indent + slot / 2f
    val stroke = WorkspaceUiMetrics.hierarchyGuideStrokeWidth.toPx()
    drawLine(color, Offset(parentX, if (header) size.height / 2f + icon / 2f else 0f), Offset(parentX, size.height), stroke)
    if (!header) {
        val childIconLeft = (parentDepth + 1) * indent + (slot - icon) / 2f
        drawLine(color, Offset(parentX, size.height / 2f), Offset(childIconLeft - 4.dp.toPx(), size.height / 2f), stroke)
    }
}

/** A single slot carries both identity and the toggle affordance; empty rows keep identity only. */
@Composable
internal fun HierarchyToggleIcon(
    label: String,
    expanded: Boolean,
    hasChildren: Boolean,
    restingIcon: ImageVector,
    onToggle: () -> Unit,
    iconTag: String? = null,
    showChevronOnInteraction: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val inputModeManager = LocalInputModeManager.current
    var pointerFocus by remember { mutableStateOf(false) }
    val icon = if (hasChildren && showChevronOnInteraction && (hovered || (focused && !pointerFocus) || pressed)) {
        if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight
    } else restingIcon
    HierarchyIconButton(
        imageVector = icon,
        contentDescription = if (hasChildren) "$label ${if (expanded) "접기" else "펼치기"}" else "$label 빈 구분",
        onClick = onToggle.takeIf { hasChildren },
        interactionSource = interactions,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) pointerFocus = true
                    }
                }
            }
            .onFocusChanged {
                if (!it.isFocused || inputModeManager.inputMode == InputMode.Keyboard) pointerFocus = false
            }
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) pointerFocus = false; false }
            .semantics {
                stateDescription = if (!hasChildren) "빈 구분" else if (expanded) "펼쳐짐" else "접힘"
                this[HierarchyToggleGlyph] = icon.name
            },
        iconModifier = if (iconTag == null) Modifier else Modifier.testTag(iconTag),
    )
}

/** Non-user-facing geometry/interaction test observation of the glyph actually rendered. */
internal val HierarchyToggleGlyph = SemanticsPropertyKey<String>("HierarchyToggleGlyph")
