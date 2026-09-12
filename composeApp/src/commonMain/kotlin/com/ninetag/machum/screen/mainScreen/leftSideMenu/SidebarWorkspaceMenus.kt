package com.ninetag.machum.screen.mainScreen.leftSideMenu

import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.screen.common.DrawerMenuItem
import com.ninetag.machum.screen.common.AboveAnchorDropdownMenu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninetag.machum.theme.WorkspaceUiMetrics
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name

@Composable
internal fun SidebarWorkspaceSelector(
    currentProject: PlatformFile?,
    generalFolders: List<PlatformFile>,
    isManagedProject: Boolean,
    onProjectWorkspaceSelected: () -> Unit,
    onGeneralFolderSelected: (PlatformFile) -> Unit,
    modifier: Modifier = Modifier,
    menuWidth: Dp = WorkspaceUiMetrics.drawerMaxWidth - 24.dp,
) {
    SidebarSelectorMenu(currentProject?.toString(), isManagedProject, "작업 위치 선택", modifier, menuWidth,
        anchor = {
            Column(Modifier.weight(1f)) {
                Text(currentProject?.name ?: "작업 공간", style = WorkspaceUiMetrics.bodyTextStyle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (isManagedProject) "프로젝트" else "일반 폴더",
                    style = WorkspaceUiMetrics.secondaryTextStyle)
            }
        },
    ) { dismiss, selectedRequester ->
            DrawerMenuItem("프로젝트", {
                dismiss()
                if (!isManagedProject) onProjectWorkspaceSelected()
            }, selected = isManagedProject,
                bringIntoViewRequester = selectedRequester.takeIf { isManagedProject })
            if (generalFolders.isNotEmpty()) HorizontalDivider()
            generalFolders.forEach { folder ->
                val selected = !isManagedProject && folder.toString() == currentProject?.toString()
                DrawerMenuItem(folder.name, {
                    dismiss()
                    if (!selected) onGeneralFolderSelected(folder)
                }, selected = selected,
                    bringIntoViewRequester = selectedRequester.takeIf { selected })
            }
    }
}

@Composable
internal fun SidebarProjectSelector(
    projects: List<PlatformFile>,
    generalFolders: List<PlatformFile>,
    currentProject: PlatformFile?,
    isManagedProject: Boolean,
    onProjectSelected: (PlatformFile) -> Unit,
    modifier: Modifier = Modifier,
    menuWidth: Dp = WorkspaceUiMetrics.drawerMaxWidth - 24.dp,
) {
    val generalLocations = generalFolders.mapTo(mutableSetOf()) { it.toString() }
    val choices = projects.filterNot { it.toString() in generalLocations }
    SidebarSelectorMenu(currentProject?.toString(), isManagedProject, "프로젝트 선택", modifier, menuWidth,
        aboveAnchor = true,
        anchor = {
            Text(if (isManagedProject) currentProject?.name ?: "프로젝트 선택" else "프로젝트 선택",
                Modifier.weight(1f), style = WorkspaceUiMetrics.bodyTextStyle,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
    ) { dismiss, selectedRequester ->
            if (choices.isEmpty()) DrawerMenuItem("선택할 프로젝트가 없습니다", {}, enabled = false)
            choices.forEach { project ->
                val selected = isManagedProject && project.toString() == currentProject?.toString()
                DrawerMenuItem(project.name, {
                    dismiss()
                    if (!selected) onProjectSelected(project)
                }, selected = selected,
                    bringIntoViewRequester = selectedRequester.takeIf { selected })
            }
    }
}

/** Shared popup lifetime and anchor chrome; each selector owns its choices and selection policy. */
@Composable
private fun SidebarSelectorMenu(
    workspaceKey: String?,
    isManagedProject: Boolean,
    description: String,
    modifier: Modifier,
    menuWidth: Dp,
    aboveAnchor: Boolean = false,
    anchor: @Composable RowScope.() -> Unit,
    items: @Composable ColumnScope.(dismiss: () -> Unit, selectedRequester: BringIntoViewRequester) -> Unit,
) {
    var expanded by remember(workspaceKey, isManagedProject) { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    val selectedRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded, if (aboveAnchor) anchorBounds else null) {
        if (expanded) { withFrameNanos { }; selectedRequester.bringIntoView() }
    }
    Box(modifier.then(if (aboveAnchor) Modifier.onGloballyPositioned {
        val position = it.positionInWindow()
        anchorBounds = IntRect(IntOffset(position.x.roundToInt(), position.y.roundToInt()), it.size)
    } else Modifier)) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            anchor()
            Icon(Icons.Default.ArrowDropDown, description, Modifier.size(WorkspaceUiMetrics.iconSize))
        }
        if (aboveAnchor) AboveAnchorDropdownMenu(expanded, { expanded = false }, menuWidth, anchorBounds) {
            items({ expanded = false }, selectedRequester)
        } else DrawerDropdownMenu(expanded, { expanded = false }, menuWidth) {
            items({ expanded = false }, selectedRequester)
        }
    }
}

@Composable
internal fun SidebarFooterActions(onWorkspaceSelection: () -> Unit) {
    IconButton(onClick = onWorkspaceSelection, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.FolderOpen, "작업 공간 관리", Modifier.size(WorkspaceUiMetrics.iconSize))
    }
}
