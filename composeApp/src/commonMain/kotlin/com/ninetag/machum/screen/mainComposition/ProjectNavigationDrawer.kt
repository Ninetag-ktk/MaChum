package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.PlotOrderAssignment
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.ProjectFolder
import com.ninetag.machum.external.ProjectFolderDeletionPreview
import com.ninetag.machum.external.numberedPrefix
import com.ninetag.machum.theme.WorkspaceUiMetrics
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectNavigationDrawer(
    vault: PlatformFile?,
    projects: List<PlatformFile>,
    currentProject: PlatformFile?,
    folders: List<ProjectFolder>,
    folderConfigs: Map<String, FolderConfig>,
    currentFolder: ProjectFolder?,
    folderContents: Map<FolderKey, HierarchyFolderContent>,
    currentFile: ProjectFile?,
    onVaultChange: () -> Unit,
    onProjectSelected: (PlatformFile) -> Unit,
    onRenameProject: (String) -> Unit,
    projectRenameState: ProjectRenameUiState,
    onProjectRenameResultConsumed: () -> Unit,
    onFolderSelected: (FolderKey) -> Unit,
    onFileSelected: (ProjectFile) -> Unit,
    onCreateFile: (FolderKey) -> Unit,
    onCreatePlotFile: (FolderKey, PlotStage) -> Unit,
    onSaveDefaultOrder: suspend (FolderKey, List<FileKey>) -> Boolean,
    onSavePlotOrder: suspend (FolderKey, List<PlotOrderAssignment>) -> Boolean,
    onCreateDirectory: (String, FolderConfig) -> Unit,
    onUpdateDirectory: (FolderKey, String, FolderConfig) -> Unit,
    pendingFolderDeletion: ProjectFolderDeletionPreview?,
    onDeleteDirectoryRequested: (FolderKey) -> Unit,
    onDeleteDirectoryDismissed: () -> Unit,
    onDeleteDirectoryConfirmed: () -> Unit,
    onClose: () -> Unit,
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var showCreateDirectoryDialog by remember { mutableStateOf(false) }
    var showRenameProjectDialog by remember { mutableStateOf(false) }
    var editingFolderKey by remember { mutableStateOf<FolderKey?>(null) }
    var contextMenuFolderKey by remember { mutableStateOf<FolderKey?>(null) }
    var expandedFolderKeys by remember(currentProject?.toString()) {
        mutableStateOf(emptySet<FolderKey>())
    }
    var knownFolderKeys by remember(currentProject?.toString()) {
        mutableStateOf(emptySet<FolderKey>())
    }
    var orderDrafts by remember(currentProject?.toString()) {
        mutableStateOf<Map<FolderKey, HierarchyOrderDraft>>(emptyMap())
    }
    var orderSavingFolders by remember(currentProject?.toString()) {
        mutableStateOf(emptySet<FolderKey>())
    }
    var orderErrors by remember(currentProject?.toString()) {
        mutableStateOf<Map<FolderKey, String>>(emptyMap())
    }
    val scope = rememberCoroutineScope()
    val currentOnSaveDefaultOrder by rememberUpdatedState(onSaveDefaultOrder)
    val currentOnSavePlotOrder by rememberUpdatedState(onSavePlotOrder)

    fun beginOrderDrag(
        folderKey: FolderKey,
        folderConfig: FolderConfig,
        content: HierarchyFolderContent,
    ) {
        if (folderKey in orderSavingFolders) return
        val draft = when {
            folderConfig.isPlot -> plotHierarchyOrderDraft(folderKey, content.plotEntries)
            folderConfig.type == FolderType.DEFAULT -> defaultHierarchyOrderDraft(folderKey, content.files)
            else -> return
        }
        orderErrors = orderErrors - folderKey
        orderDrafts = orderDrafts + (folderKey to draft)
    }

    fun moveOrderDraft(folderKey: FolderKey, fileKey: FileKey, direction: Int) {
        val draft = orderDrafts[folderKey] ?: return
        val moved = draft.move(fileKey, direction)
        if (moved != draft) orderDrafts = orderDrafts + (folderKey to moved)
    }

    fun cancelOrderDrag(folderKey: FolderKey) {
        orderDrafts = orderDrafts - folderKey
    }

    fun finishOrderDrag(folderKey: FolderKey) {
        val draft = orderDrafts[folderKey] ?: return
        if (!draft.hasChanges) {
            orderDrafts = orderDrafts - folderKey
            return
        }

        orderSavingFolders += folderKey
        scope.launch {
            val saved = try {
                when (draft) {
                    is DefaultHierarchyOrderDraft -> currentOnSaveDefaultOrder(folderKey, draft.currentKeys)
                    is PlotHierarchyOrderDraft -> currentOnSavePlotOrder(folderKey, draft.assignments())
                }
            } catch (cancellation: CancellationException) {
                orderDrafts = orderDrafts - folderKey
                orderSavingFolders -= folderKey
                throw cancellation
            } catch (_: Exception) {
                false
            }
            orderDrafts = orderDrafts - folderKey
            orderSavingFolders -= folderKey
            orderErrors = if (saved) {
                orderErrors - folderKey
            } else {
                orderErrors + (
                    folderKey to "순서를 저장하지 못해 원래 순서로 복원했습니다."
                )
            }
        }
    }
    LaunchedEffect(folders.map(ProjectFolder::key)) {
        val availableKeys = folders
            .asSequence()
            .map(ProjectFolder::key)
            .filterNot { it == FolderKey.Base }
            .toMutableSet()
        expandedFolderKeys = (expandedFolderKeys intersect availableKeys) +
            (availableKeys - knownFolderKeys)
        knownFolderKeys = availableKeys
    }

    LaunchedEffect(currentFolder?.key) {
        currentFolder?.key
            ?.takeUnless { it == FolderKey.Base }
            ?.let { key -> expandedFolderKeys += key }
    }

    LaunchedEffect(projectRenameState.completedName) {
        if (projectRenameState.completedName != null) {
            showRenameProjectDialog = false
            onProjectRenameResultConsumed()
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().widthIn(max = WorkspaceUiMetrics.drawerMaxWidth),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WorkspaceUiMetrics.hierarchyToolbarHeight),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "하이라키",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (
                        folderConfigs[FolderKey.Base.relativePath]?.isPlot != true &&
                        FolderKey.Base !in orderSavingFolders
                    ) {
                        CompactIconAction(
                            imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                            contentDescription = "프로젝트 루트에 새 파일",
                            onClick = { onCreateFile(FolderKey.Base) },
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompactIconAction(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = "루트 디렉터리 추가",
                        onClick = { showCreateDirectoryDialog = true },
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactIconAction(
                        imageVector = Icons.Default.Close,
                        contentDescription = "사이드바 닫기",
                        onClick = onClose,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                val baseFolderConfig = folderConfigs[FolderKey.Base.relativePath]
                    ?: DEFAULT_BASE_FOLDER_CONFIG
                val baseContent = folderContents[FolderKey.Base] ?: HierarchyFolderContent()
                hierarchyFolderContentItems(
                    folderKey = FolderKey.Base,
                    contentDepth = 0,
                    folderConfig = baseFolderConfig,
                    content = baseContent,
                    orderDraft = orderDrafts[FolderKey.Base],
                    orderSaving = FolderKey.Base in orderSavingFolders,
                    currentFile = currentFile,
                    onFileSelected = onFileSelected,
                    onCreatePlotFile = onCreatePlotFile,
                    onOrderDragStart = {
                        beginOrderDrag(FolderKey.Base, baseFolderConfig, baseContent)
                    },
                    onOrderMove = { fileKey, direction ->
                        moveOrderDraft(FolderKey.Base, fileKey, direction)
                    },
                    onOrderDragEnd = { finishOrderDrag(FolderKey.Base) },
                    onOrderDragCancel = { cancelOrderDrag(FolderKey.Base) },
                )
                hierarchyOrderErrorItem(
                    folderKey = FolderKey.Base,
                    depth = 0,
                    message = orderErrors[FolderKey.Base],
                )

                folders.filterNot { it.key == FolderKey.Base }.forEach { folder ->
                    val folderConfig = folderConfigs[folder.key.relativePath] ?: FolderConfig()
                    item(key = "folder:${folder.key.relativePath}") {
                        FolderHierarchyRow(
                            folder = folder,
                            selected = folder.key == currentFolder?.key,
                            expanded = folder.key in expandedFolderKeys,
                            contextMenuExpanded = contextMenuFolderKey == folder.key,
                            isPlot = folderConfig.isPlot,
                            orderSaving = folder.key in orderSavingFolders,
                            onToggleExpanded = {
                                expandedFolderKeys = expandedFolderKeys.toggle(folder.key)
                            },
                            onSelected = { onFolderSelected(folder.key) },
                            onCreateFile = { onCreateFile(folder.key) },
                            onEdit = { editingFolderKey = folder.key },
                            onContextMenu = { contextMenuFolderKey = folder.key },
                            onContextMenuDismissed = { contextMenuFolderKey = null },
                            onDelete = { onDeleteDirectoryRequested(folder.key) },
                        )
                    }
                    if (folder.key in expandedFolderKeys) {
                        val content = folderContents[folder.key] ?: HierarchyFolderContent()
                        hierarchyFolderContentItems(
                            folderKey = folder.key,
                            contentDepth = 1,
                            folderConfig = folderConfig,
                            content = content,
                            orderDraft = orderDrafts[folder.key],
                            orderSaving = folder.key in orderSavingFolders,
                            currentFile = currentFile,
                            onFileSelected = onFileSelected,
                            onCreatePlotFile = onCreatePlotFile,
                            onOrderDragStart = {
                                beginOrderDrag(folder.key, folderConfig, content)
                            },
                            onOrderMove = { fileKey, direction ->
                                moveOrderDraft(folder.key, fileKey, direction)
                            },
                            onOrderDragEnd = { finishOrderDrag(folder.key) },
                            onOrderDragCancel = { cancelOrderDrag(folder.key) },
                        )
                    }
                    hierarchyOrderErrorItem(
                        folderKey = folder.key,
                        depth = 1,
                        message = orderErrors[folder.key],
                    )
                }

            }

            HorizontalDivider()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(
                            onClick = { projectMenuExpanded = true },
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().height(WorkspaceUiMetrics.navigationRowHeight),
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                            )
                            Text(
                                text = currentProject?.name ?: "Project 선택",
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                            )
                        }
                        DropdownMenu(
                            expanded = projectMenuExpanded,
                            onDismissRequest = { projectMenuExpanded = false },
                        ) {
                            if (projects.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("선택할 프로젝트가 없습니다") },
                                    enabled = false,
                                    onClick = {},
                                )
                            } else {
                                projects.forEach { project ->
                                    val selected = project.toString() == currentProject?.toString()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = project.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        onClick = {
                                            projectMenuExpanded = false
                                            onProjectSelected(project)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Folder, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            if (selected) Icon(Icons.Default.Check, contentDescription = null)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { settingsMenuExpanded = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "설정",
                                modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                            )
                        }
                        DropdownMenu(
                            expanded = settingsMenuExpanded,
                            onDismissRequest = { settingsMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = vault?.name ?: "선택된 Vault 없음",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                enabled = false,
                                onClick = {},
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("프로젝트 기본 설정") },
                                enabled = currentProject != null,
                                onClick = {
                                    settingsMenuExpanded = false
                                    editingFolderKey = FolderKey.Base
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("프로젝트 이름 변경") },
                                enabled = currentProject != null,
                                onClick = {
                                    settingsMenuExpanded = false
                                    showRenameProjectDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Vault 다시 선택") },
                                onClick = {
                                    settingsMenuExpanded = false
                                    onVaultChange()
                                },
                                leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDirectoryDialog) {
        CreateProjectDirectoryDialog(
            existingDirectoryNames = folders
                .filterNot { it.key == FolderKey.Base }
                .mapTo(mutableSetOf()) { it.key.relativePath },
            onDismissRequest = { showCreateDirectoryDialog = false },
            onCreate = onCreateDirectory,
        )
    }

    if (showRenameProjectDialog && currentProject != null) {
        RenameProjectDialog(
            currentName = currentProject.name,
            existingProjectNames = projects.mapTo(mutableSetOf()) { it.name },
            isRenaming = projectRenameState.isRenaming,
            errorMessage = projectRenameState.errorMessage,
            onDismissRequest = {
                showRenameProjectDialog = false
                onProjectRenameResultConsumed()
            },
            onRename = onRenameProject,
        )
    }

    editingFolderKey?.let { folderKey ->
        EditProjectDirectoryDialog(
            directoryName = if (folderKey == FolderKey.Base) {
                currentProject?.name ?: "프로젝트 기본 설정"
            } else {
                folderKey.relativePath
            },
            existingDirectoryNames = folders
                .filterNot { it.key == FolderKey.Base }
                .mapTo(mutableSetOf()) { it.key.relativePath },
            allowRename = folderKey != FolderKey.Base,
            initialConfig = folderConfigs[folderKey.relativePath]
                ?: if (folderKey == FolderKey.Base) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig(),
            onDismissRequest = { editingFolderKey = null },
            onSave = { updatedName, config -> onUpdateDirectory(folderKey, updatedName, config) },
            onDeleteRequest = folderKey
                .takeUnless { it == FolderKey.Base }
                ?.let { { onDeleteDirectoryRequested(it) } },
        )
    }

    pendingFolderDeletion?.let { preview ->
        DeleteProjectDirectoryDialog(
            preview = preview,
            onDismissRequest = onDeleteDirectoryDismissed,
            onConfirm = onDeleteDirectoryConfirmed,
        )
    }
}

private fun LazyListScope.hierarchyFolderContentItems(
    folderKey: FolderKey,
    contentDepth: Int,
    folderConfig: FolderConfig,
    content: HierarchyFolderContent,
    orderDraft: HierarchyOrderDraft?,
    orderSaving: Boolean,
    currentFile: ProjectFile?,
    onFileSelected: (ProjectFile) -> Unit,
    onCreatePlotFile: (FolderKey, PlotStage) -> Unit,
    onOrderDragStart: () -> Unit,
    onOrderMove: (FileKey, Int) -> Unit,
    onOrderDragEnd: () -> Unit,
    onOrderDragCancel: () -> Unit,
) {
    if (!folderConfig.isPlot) {
        val defaultDraft = orderDraft as? DefaultHierarchyOrderDraft
        val displayedFiles = defaultDraft?.reorder(content.files) ?: content.files
        val managedFileCount = content.files.count { it.numberedPrefix() != null }
        displayedFiles.forEach { file ->
            item(key = "file:${file.key.relativePath}") {
                HierarchyFileRow(
                    file = file,
                    displayName = defaultDraft?.displayName(file),
                    depth = contentDepth,
                    draggable = !orderSaving &&
                        folderConfig.type == FolderType.DEFAULT &&
                        file.numberedPrefix() != null &&
                        managedFileCount > 1,
                    selected = file.key == currentFile?.key,
                    onClick = { onFileSelected(file) },
                    onDragStart = onOrderDragStart,
                    onDragMove = { direction -> onOrderMove(file.key, direction) },
                    onDragEnd = onOrderDragEnd,
                    onDragCancel = onOrderDragCancel,
                )
            }
        }
        return
    }

    val plotDraft = orderDraft as? PlotHierarchyOrderDraft
    val displayedPlotEntries = plotDraft?.reorder(content.plotEntries) ?: content.plotEntries
    PlotStage.entries.forEach { stage ->
        val stageEntries = displayedPlotEntries.filter { it.stage == stage }
        item(key = "plot-stage:${folderKey.relativePath}:${stage.name}") {
            PlotStageRow(
                label = stage.frontmatterValue,
                depth = contentDepth,
                hasChildren = stageEntries.isNotEmpty(),
                onCreateFile = if (orderSaving) {
                    null
                } else {
                    { onCreatePlotFile(folderKey, stage) }
                },
            )
        }
        stageEntries.forEach { entry ->
                item(key = "file:${entry.projectFile.key.relativePath}") {
                    HierarchyFileRow(
                        file = entry.projectFile,
                        displayName = entry.order
                            ?.let { order -> "$order. ${entry.title}" }
                            ?: entry.title,
                        depth = contentDepth + 1,
                        draggable = !orderSaving,
                        selected = entry.projectFile.key == currentFile?.key,
                        onClick = { onFileSelected(entry.projectFile) },
                        onDragStart = onOrderDragStart,
                        onDragMove = { direction ->
                            onOrderMove(entry.projectFile.key, direction)
                        },
                        onDragEnd = onOrderDragEnd,
                        onDragCancel = onOrderDragCancel,
                    )
                }
            }
    }

    val unclassifiedEntries = displayedPlotEntries.filter { it.stage == null }
    if (unclassifiedEntries.isNotEmpty()) {
        item(key = "plot-stage:${folderKey.relativePath}:unclassified") {
            PlotStageRow(
                label = "미분류",
                depth = contentDepth,
                hasChildren = true,
            )
        }
        unclassifiedEntries.forEach { entry ->
            item(key = "file:${entry.projectFile.key.relativePath}") {
                HierarchyFileRow(
                    file = entry.projectFile,
                    displayName = entry.title,
                    depth = contentDepth + 1,
                    draggable = !orderSaving,
                    selected = entry.projectFile.key == currentFile?.key,
                    onClick = { onFileSelected(entry.projectFile) },
                    onDragStart = onOrderDragStart,
                    onDragMove = { direction -> onOrderMove(entry.projectFile.key, direction) },
                    onDragEnd = onOrderDragEnd,
                    onDragCancel = onOrderDragCancel,
                )
            }
        }
    }
}

private fun LazyListScope.hierarchyOrderErrorItem(
    folderKey: FolderKey,
    depth: Int,
    message: String?,
) {
    if (message == null) return
    item(key = "order-error:${folderKey.relativePath}") {
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = WorkspaceUiMetrics.hierarchyIndentStep * depth +
                        WorkspaceUiMetrics.hierarchyActionSize,
                    end = 8.dp,
                    top = 2.dp,
                    bottom = 4.dp,
                ),
        )
    }
}

@Composable
private fun FolderHierarchyRow(
    folder: ProjectFolder,
    selected: Boolean,
    expanded: Boolean,
    contextMenuExpanded: Boolean,
    isPlot: Boolean,
    orderSaving: Boolean,
    onToggleExpanded: () -> Unit,
    onSelected: () -> Unit,
    onCreateFile: () -> Unit,
    onEdit: () -> Unit,
    onContextMenu: () -> Unit,
    onContextMenuDismissed: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkspaceUiMetrics.hierarchyFolderRowHeight),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(WorkspaceUiMetrics.hierarchyFolderRowHeight),
            color = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                Color.Transparent
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactIconAction(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                    contentDescription = if (expanded) {
                        "${folder.key.relativePath} 접기"
                    } else {
                        "${folder.key.relativePath} 펼치기"
                    },
                    onClick = onToggleExpanded,
                    iconTint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .hierarchyClickable(
                            onClick = onSelected,
                            onContextMenu = onContextMenu,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = folder.key.relativePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!isPlot && !orderSaving) {
                    CompactIconAction(
                        imageVector = Icons.Default.Add,
                        contentDescription = "${folder.key.relativePath}에 새 파일",
                        onClick = onCreateFile,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        iconSize = WorkspaceUiMetrics.hierarchySecondaryIconSize,
                    )
                }
            }
        }
        FolderContextMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = onContextMenuDismissed,
            onCreateFile = if (isPlot || orderSaving) {
                null
            } else {
                {
                    onContextMenuDismissed()
                    onCreateFile()
                }
            },
            onEdit = {
                onContextMenuDismissed()
                onEdit()
            },
            onDelete = {
                onContextMenuDismissed()
                onDelete()
            },
        )
    }
}

@Composable
private fun PlotStageRow(
    label: String,
    depth: Int,
    hasChildren: Boolean,
    onCreateFile: (() -> Unit)? = null,
) {
    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkspaceUiMetrics.hierarchyStageRowHeight)
            .hierarchyDepthGuides(
                depth = depth,
                guideColor = guideColor,
                continuesToChild = hasChildren,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(WorkspaceUiMetrics.hierarchyStageRowHeight)
                .padding(
                    start = WorkspaceUiMetrics.hierarchyIndentStep * depth + 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.FormatListNumbered,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(WorkspaceUiMetrics.hierarchySecondaryIconSize),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onCreateFile != null) {
                CompactIconAction(
                    imageVector = Icons.Default.Add,
                    contentDescription = "$label 단계에 새 파일",
                    onClick = onCreateFile,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    iconSize = WorkspaceUiMetrics.hierarchySecondaryIconSize,
                )
            }
        }
    }
}

@Composable
private fun HierarchyFileRow(
    file: ProjectFile,
    displayName: String? = null,
    depth: Int,
    draggable: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (Int) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val fileName = displayName ?: file.key.fileName.let { name ->
        if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
    }

    val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragMove by rememberUpdatedState(onDragMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val dragModifier = if (draggable) {
        Modifier.pointerInput(file.key) {
            val stepThreshold = WorkspaceUiMetrics.hierarchyFileRowHeight.toPx()
            var dragDistance = 0f
            detectDragGestures(
                onDragStart = {
                    dragDistance = 0f
                    currentOnDragStart()
                },
                onDragEnd = {
                    dragDistance = 0f
                    currentOnDragEnd()
                },
                onDragCancel = {
                    dragDistance = 0f
                    currentOnDragCancel()
                },
                onDrag = { change, amount ->
                    change.consume()
                    dragDistance += amount.y
                    while (dragDistance >= stepThreshold) {
                        currentOnDragMove(1)
                        dragDistance -= stepThreshold
                    }
                    while (dragDistance <= -stepThreshold) {
                        currentOnDragMove(-1)
                        dragDistance += stepThreshold
                    }
                },
            )
        }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WorkspaceUiMetrics.hierarchyFileRowHeight)
            .hierarchyDepthGuides(depth = depth, guideColor = guideColor),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(WorkspaceUiMetrics.hierarchyFileRowHeight)
                .padding(start = WorkspaceUiMetrics.hierarchyIndentStep * depth),
            color = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                Color.Transparent
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(WorkspaceUiMetrics.hierarchyActionSize)
                        .then(dragModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = if (draggable) "$fileName 순서 변경 핸들" else null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else if (draggable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                        modifier = Modifier.size(WorkspaceUiMetrics.hierarchyIconSize),
                    )
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .hierarchyClickable(onClick = onClick)
                        .padding(start = 2.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactIconAction(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color? = null,
    iconSize: androidx.compose.ui.unit.Dp = WorkspaceUiMetrics.hierarchyIconSize,
) {
    Box(
        modifier = Modifier
            .size(WorkspaceUiMetrics.hierarchyActionSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = iconTint ?: LocalContentColor.current,
            modifier = Modifier.size(iconSize),
        )
    }
}

private fun Modifier.hierarchyDepthGuides(
    depth: Int,
    guideColor: Color,
    continuesToChild: Boolean = false,
): Modifier {
    if (depth <= 0 && !continuesToChild) return this

    return drawBehind {
        val indent = WorkspaceUiMetrics.hierarchyIndentStep.toPx()
        val guideOffset = WorkspaceUiMetrics.hierarchyGuideOffset.toPx()
        val strokeWidth = WorkspaceUiMetrics.hierarchyGuideStrokeWidth.toPx()

        repeat(depth) { level ->
            val x = guideOffset + indent * level
            drawLine(
                color = guideColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = strokeWidth,
            )
        }

        if (depth > 0) {
            val parentGuideX = guideOffset + indent * (depth - 1)
            drawLine(
                color = guideColor,
                start = Offset(parentGuideX, size.height / 2f),
                end = Offset(indent * depth, size.height / 2f),
                strokeWidth = strokeWidth,
            )
        }

        if (continuesToChild) {
            val childGuideX = guideOffset + indent * depth
            drawLine(
                color = guideColor,
                start = Offset(childGuideX, size.height / 2f),
                end = Offset(childGuideX, size.height),
                strokeWidth = strokeWidth,
            )
        }
    }
}

@Composable
private fun FolderContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onCreateFile: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        if (onCreateFile != null) {
            DropdownMenuItem(
                text = { Text("새 파일") },
                onClick = onCreateFile,
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
            )
        }
        DropdownMenuItem(
            text = { Text("이름/설정 변경") },
            onClick = onEdit,
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
        )
        if (onDelete != null) {
            DropdownMenuItem(
                text = { Text("삭제") },
                onClick = onDelete,
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.hierarchyClickable(
    onClick: () -> Unit,
    onContextMenu: (() -> Unit)? = null,
): Modifier {
    val pointerModifier = if (onContextMenu == null) {
        Modifier
    } else {
        Modifier.pointerInput(onContextMenu) {
            awaitEachGesture {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    onContextMenu()
                }
            }
        }
    }

    return this
        .then(pointerModifier)
        .combinedClickable(
            onClick = onClick,
            onLongClickLabel = onContextMenu?.let { "메뉴 열기" },
            onLongClick = onContextMenu,
        )
}

private fun Set<FolderKey>.toggle(key: FolderKey): Set<FolderKey> =
    if (key in this) this - key else this + key
