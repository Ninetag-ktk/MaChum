package com.ninetag.machum.screen.mainScreen.leftSideMenu

import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.screen.common.DrawerMenuItem
import com.ninetag.machum.screen.mainScreen.HierarchyFolderContent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.DEFAULT_BASE_FOLDER_CONFIG
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.GeneralSourceState
import com.ninetag.machum.external.GeneralSourcePlan
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.PlotOrderAssignment
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.ProjectFolder
import com.ninetag.machum.external.ProjectFolderDeletionPreview
import com.ninetag.machum.external.numberedPrefix
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.theme.WorkspaceMotion
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectNavigationDrawer(
    projects: List<PlatformFile>,
    generalFolders: List<PlatformFile>,
    currentProject: PlatformFile?,
    isManagedProject: Boolean,
    creationInProgress: Boolean,
    folders: List<ProjectFolder>,
    folderConfigs: Map<String, FolderConfig>,
    currentFolder: ProjectFolder?,
    folderContents: Map<FolderKey, HierarchyFolderContent>,
    currentFile: ProjectFile?,
    onFolderSelected: (FolderKey) -> Unit,
    onFileSelected: (ProjectFile) -> Unit,
    onCreateFile: (FolderKey) -> Unit,
    onCreatePlotFile: (FolderKey, PlotStage) -> Unit,
    onSaveDefaultOrder: suspend (FolderKey, List<FileKey>) -> Boolean,
    onSavePlotOrder: suspend (FolderKey, List<PlotOrderAssignment>) -> Boolean,
    onCreateDirectory: (String, FolderConfig) -> Unit,
    onUpdateDirectory: suspend (FolderKey, String, FolderConfig) -> Boolean,
    onUpdateFolderTags: suspend (FolderKey, List<String>) -> Boolean,
    onUpdateFolderType: suspend (FolderKey, FolderPresentation) -> Boolean,
    pendingFolderDeletion: ProjectFolderDeletionPreview?,
    onDeleteDirectoryRequested: (FolderKey) -> Unit,
    onDeleteDirectoryDismissed: () -> Unit,
    onDeleteDirectoryConfirmed: () -> Unit,
    onWorkspaceSelection: () -> Unit,
    onProjectSelected: (PlatformFile) -> Unit,
    onProjectWorkspaceSelected: () -> Unit,
    onClose: () -> Unit,
    generalSourceState: GeneralSourceState?,
    onCreateGeneralSourceGroup: suspend () -> String?,
    onCreateGeneralSourceFile: (String?) -> Unit,
    onPlanGeneralSourceRename: suspend (String, String) -> GeneralSourcePlan,
    onPlanGeneralSourceDelete: suspend (String) -> GeneralSourcePlan,
    onPlanGeneralSourceAssign: suspend (ProjectFile, String) -> GeneralSourcePlan,
    onApplyGeneralSourcePlan: suspend (GeneralSourcePlan) -> String?,
    onFileTrashRequested: (ProjectFile) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val collapsedSourceGroups = remember { mutableStateMapOf<String, Set<String?>>() }
    val collapsedPlotGroups = remember { mutableStateMapOf<String, Set<Pair<FolderKey, PlotStage?>>>() }
    val sourceWorkspaceKey = currentProject?.toString().orEmpty()
    var groupCreateBusy by remember(currentProject?.toString()) { mutableStateOf(false) }
    var groupCreateError by remember(currentProject?.toString()) { mutableStateOf<String?>(null) }
    val tagDrafts = remember(currentProject?.toString(), isManagedProject) { mutableMapOf<FolderKey, FolderTagDraft>() }
    var renamingFolderKey by remember { mutableStateOf<FolderKey?>(null) }
    var editingFolderKey by remember { mutableStateOf<FolderKey?>(null) }
    var contextMenuFolderKey by remember { mutableStateOf<FolderKey?>(null) }
    LaunchedEffect(currentProject?.toString(), isManagedProject) {
        renamingFolderKey = null
        editingFolderKey = null
        contextMenuFolderKey = null
    }
    LaunchedEffect(currentFolder?.key) { renamingFolderKey = null }
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
    fun togglePlotGroup(folder: FolderKey, stage: PlotStage?) {
        val collapsed = collapsedPlotGroups[sourceWorkspaceKey].orEmpty()
        val target = folder to stage
        collapsedPlotGroups[sourceWorkspaceKey] = if (target in collapsed) collapsed - target else collapsed + target
    }
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

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().widthIn(max = WorkspaceUiMetrics.drawerMaxWidth)
            .testTag("sidebar-focus-entry")
            // Material requests focus on opening. Touch-mode buttons are skipped, so without
            // this neutral target the first text field would start editing automatically.
            .focusProperties { canFocus = inputModeManager.inputMode == InputMode.Touch }
            .focusable(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
            val selectorWidth = (maxWidth - 24.dp).coerceAtLeast(48.dp)
            Column(modifier = Modifier.fillMaxHeight()) {
                if (creationInProgress) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = PopupUiMetrics.RowMinHeight).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("생성 중…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WorkspaceUiMetrics.hierarchyToolbarHeight),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SidebarWorkspaceSelector(
                            currentProject = currentProject,
                            generalFolders = generalFolders,
                            isManagedProject = isManagedProject,
                            onProjectWorkspaceSelected = onProjectWorkspaceSelected,
                            onGeneralFolderSelected = onProjectSelected,
                            modifier = Modifier.weight(1f),
                            menuWidth = selectorWidth,
                        )
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
                            contentDescription = if (isManagedProject) "현재 작업 공간 루트에 새 폴더" else "새 source 구분",
                            onClick = {
                                if (isManagedProject) onCreateDirectory("무제", FolderConfig(type = FolderType.GENERAL))
                                else if (!groupCreateBusy) {
                                    groupCreateBusy = true
                                    scope.launch {
                                        try { groupCreateError = onCreateGeneralSourceGroup() }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) { groupCreateError = error.message ?: "구분 생성에 실패했습니다." }
                                        finally { groupCreateBusy = false }
                                    }
                                }
                            },
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

                if (groupCreateBusy) Text("구분 생성 중…", Modifier.padding(8.dp))
                groupCreateError?.let { Text(it, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error) }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    val baseFolderConfig = folderConfigs[FolderKey.Base.relativePath]
                        ?: if (isManagedProject) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig(type = FolderType.GENERAL)
                    val baseContent = folderContents[FolderKey.Base] ?: HierarchyFolderContent()
                    if (!isManagedProject && generalSourceState?.enabled == true) hierarchyMotionItem(key = "general-source-groups") {
                        androidx.compose.runtime.key(currentProject?.toString()) {
                            GeneralSourceControls(generalSourceState, currentFile, onFileSelected,
                                onPlanGeneralSourceRename, onPlanGeneralSourceDelete, onPlanGeneralSourceAssign, onApplyGeneralSourcePlan,
                                collapsedGroups = collapsedSourceGroups[sourceWorkspaceKey].orEmpty(),
                                onToggleGroup = { group ->
                                    val collapsed = collapsedSourceGroups[sourceWorkspaceKey].orEmpty()
                                    collapsedSourceGroups[sourceWorkspaceKey] = if (group in collapsed) collapsed - group else collapsed + group
                                },
                                creationInProgress = creationInProgress,
                                onCreateFile = onCreateGeneralSourceFile,
                                onFileTrashRequested = onFileTrashRequested)
                        }
                    }
                    if (isManagedProject || generalSourceState?.enabled != true) hierarchyFolderContentItems(
                        folderKey = FolderKey.Base,
                        contentDepth = 0,
                        folderConfig = baseFolderConfig,
                        content = baseContent,
                        orderDraft = orderDrafts[FolderKey.Base],
                        orderSaving = FolderKey.Base in orderSavingFolders,
                        currentFile = currentFile,
                        onFileSelected = onFileSelected,
                        onFileTrashRequested = onFileTrashRequested,
                        onCreatePlotFile = onCreatePlotFile,
                        onOrderDragStart = {
                            beginOrderDrag(FolderKey.Base, baseFolderConfig, baseContent)
                        },
                        onOrderMove = { fileKey, direction ->
                            moveOrderDraft(FolderKey.Base, fileKey, direction)
                        },
                        onOrderDragEnd = { finishOrderDrag(FolderKey.Base) },
                        onOrderDragCancel = { cancelOrderDrag(FolderKey.Base) },
                        collapsedPlotStages = collapsedPlotGroups[sourceWorkspaceKey].orEmpty().filter { it.first == FolderKey.Base }.map { it.second }.toSet(),
                        onTogglePlotStage = { togglePlotGroup(FolderKey.Base, it) },
                    )
                    hierarchyOrderErrorItem(
                        folderKey = FolderKey.Base,
                        depth = 0,
                        message = orderErrors[FolderKey.Base],
                    )

                    folders.filterNot { it.key == FolderKey.Base }.forEach { folder ->
                        val folderConfig = if (!isManagedProject) FolderConfig(type = FolderType.GENERAL)
                            else folderConfigs[folder.key.relativePath] ?: FolderConfig()
                        val tagDraft = if (isManagedProject) tagDrafts.getOrPut(folder.key) { FolderTagDraft(folderConfig.autoTags) } else null
                        hierarchyMotionItem(key = "folder:${folder.key.relativePath}", animate = orderDrafts.isEmpty() && orderSavingFolders.isEmpty()) {
                            FolderHierarchyRow(
                                folder = folder,
                                selected = folder.key == currentFolder?.key,
                                expanded = folder.key in expandedFolderKeys,
                                hasChildren = folderConfig.isPlot || folderContents[folder.key]?.files?.isNotEmpty() == true,
                                contextMenuExpanded = contextMenuFolderKey == folder.key,
                                isPlot = folderConfig.isPlot,
                                orderSaving = folder.key in orderSavingFolders,
                                onToggleExpanded = {
                                    expandedFolderKeys = expandedFolderKeys.toggle(folder.key)
                                },
                                onSelected = {
                                    focusManager.clearFocus(force = true)
                                    onFolderSelected(folder.key)
                                },
                                onCreateFile = { onCreateFile(folder.key) },
                                onEdit = null,
                                onRename = { renamingFolderKey = folder.key },
                                nameEditor = if (renamingFolderKey == folder.key) {{
                                    FolderInlineRename(
                                        name = folder.key.relativePath,
                                        onCancel = { renamingFolderKey = null },
                                        onRename = { name -> onUpdateDirectory(folder.key, name, folderConfig) },
                                    )
                                }} else null,
                                onContextMenu = { contextMenuFolderKey = folder.key },
                                onContextMenuDismissed = { contextMenuFolderKey = null },
                                onDelete = { onDeleteDirectoryRequested(folder.key) },
                            )
                        }
                        if (tagDraft != null && (folder.key == currentFolder?.key || tagDraft.retained)) {
                            hierarchyMotionItem(key = "folder-controls:${folder.key.relativePath}", fade = false) {
                                FolderDirectControls(
                                    name = folder.key.relativePath, config = folderConfig, draft = tagDraft, scope = scope,
                                    onSaveTags = { onUpdateFolderTags(folder.key, it) },
                                    onChangeType = { onUpdateFolderType(folder.key, it) },
                                )
                            }
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
                                onFileTrashRequested = onFileTrashRequested,
                                onCreatePlotFile = onCreatePlotFile,
                                onOrderDragStart = {
                                    beginOrderDrag(folder.key, folderConfig, content)
                                },
                                onOrderMove = { fileKey, direction ->
                                    moveOrderDraft(folder.key, fileKey, direction)
                                },
                                onOrderDragEnd = { finishOrderDrag(folder.key) },
                                onOrderDragCancel = { cancelOrderDrag(folder.key) },
                                collapsedPlotStages = collapsedPlotGroups[sourceWorkspaceKey].orEmpty().filter { it.first == folder.key }.map { it.second }.toSet(),
                                onTogglePlotStage = { togglePlotGroup(folder.key, it) },
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
                        SidebarProjectSelector(
                            projects = projects,
                            generalFolders = generalFolders,
                            currentProject = currentProject,
                            isManagedProject = isManagedProject,
                            onProjectSelected = onProjectSelected,
                            modifier = Modifier.weight(1f),
                            menuWidth = selectorWidth,
                        )
                        SidebarFooterActions(
                            onWorkspaceSelection = onWorkspaceSelection,
                        )
                    }
                }
            }
        }
    }

    editingFolderKey?.takeUnless { it == FolderKey.Base }?.let { folderKey ->
        EditProjectDirectoryDialog(
            showProjectSettings = isManagedProject,
            directoryName = folderKey.relativePath,
            existingDirectoryNames = folders
                .filterNot { it.key == FolderKey.Base }
                .mapTo(mutableSetOf()) { it.key.relativePath },
            initialConfig = folderConfigs[folderKey.relativePath] ?: FolderConfig(),
            onDismissRequest = { editingFolderKey = null },
            onSave = { updatedName, config -> onUpdateDirectory(folderKey, updatedName, config) },
            onDeleteRequest = { onDeleteDirectoryRequested(folderKey) },
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
    collapsedPlotStages: Set<PlotStage?>,
    onTogglePlotStage: (PlotStage?) -> Unit,
    onFileTrashRequested: (ProjectFile) -> Unit,
) {
    if (!folderConfig.isPlot) {
        val defaultDraft = orderDraft as? DefaultHierarchyOrderDraft
        val displayedFiles = defaultDraft?.reorder(content.files) ?: content.files
        val managedFileCount = content.files.count { it.numberedPrefix() != null }
        displayedFiles.forEach { file ->
            hierarchyMotionItem(key = "file:${file.key.relativePath}", animate = orderDraft == null && !orderSaving) {
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
                    onTrashRequested = if (orderSaving) null else { { onFileTrashRequested(file) } },
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
        hierarchyMotionItem(key = "plot-stage:${folderKey.relativePath}:${stage.name}", animate = orderDraft == null && !orderSaving) {
            HierarchyGroupRow(
                label = stage.frontmatterValue,
                depth = contentDepth,
                expanded = stage !in collapsedPlotStages,
                hasChildren = stageEntries.isNotEmpty(),
                selected = stageEntries.any { it.projectFile.key == currentFile?.key },
                onToggle = { onTogglePlotStage(stage) },
                onCreate = { onCreatePlotFile(folderKey, stage) },
                createEnabled = !orderSaving,
            )
        }
        if (stage !in collapsedPlotStages) stageEntries.forEach { entry ->
                hierarchyMotionItem(key = "file:${entry.projectFile.key.relativePath}", animate = orderDraft == null && !orderSaving) {
                    HierarchyFileRow(
                        file = entry.projectFile,
                        displayName = entry.order
                            ?.let { order -> "$order. ${entry.title}" }
                            ?: entry.title,
                        depth = contentDepth + 1,
                        groupChild = true,
                        draggable = !orderSaving,
                        selected = entry.projectFile.key == currentFile?.key,
                        onClick = { onFileSelected(entry.projectFile) },
                        onTrashRequested = if (orderSaving) null else { { onFileTrashRequested(entry.projectFile) } },
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
        hierarchyMotionItem(key = "plot-stage:${folderKey.relativePath}:unclassified", animate = orderDraft == null && !orderSaving) {
            HierarchyGroupRow(
                label = "미분류",
                depth = contentDepth,
                expanded = null !in collapsedPlotStages,
                hasChildren = unclassifiedEntries.isNotEmpty(),
                selected = unclassifiedEntries.any { it.projectFile.key == currentFile?.key },
                onToggle = { onTogglePlotStage(null) },
                onCreate = null,
            )
        }
        if (null !in collapsedPlotStages) unclassifiedEntries.forEach { entry ->
            hierarchyMotionItem(key = "file:${entry.projectFile.key.relativePath}", animate = orderDraft == null && !orderSaving) {
                HierarchyFileRow(
                    file = entry.projectFile,
                    displayName = entry.title,
                    depth = contentDepth + 1,
                    groupChild = true,
                    draggable = !orderSaving,
                    selected = entry.projectFile.key == currentFile?.key,
                    onClick = { onFileSelected(entry.projectFile) },
                    onTrashRequested = if (orderSaving) null else { { onFileTrashRequested(entry.projectFile) } },
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
    hierarchyMotionItem(key = "order-error:${folderKey.relativePath}") {
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

/** Stable lazy items keep long hierarchies lazy while neighbors move as children enter/leave. */
internal fun LazyListScope.hierarchyMotionItem(
    key: String,
    animate: Boolean = true,
    fade: Boolean = true,
    content: @Composable LazyItemScope.() -> Unit,
) {
    item(key = key) {
        Box(
            if (animate) Modifier.animateItem(
                fadeInSpec = if (fade) WorkspaceMotion.contentExpandSpec() else null,
                placementSpec = WorkspaceMotion.contentExpandSpec(),
                fadeOutSpec = if (fade) WorkspaceMotion.contentCollapseSpec() else null,
            ) else Modifier,
        ) { content() }
    }
}

@Composable
internal fun FolderHierarchyRow(
    folder: ProjectFolder,
    selected: Boolean,
    expanded: Boolean,
    hasChildren: Boolean,
    contextMenuExpanded: Boolean,
    isPlot: Boolean,
    orderSaving: Boolean,
    onToggleExpanded: () -> Unit,
    onSelected: () -> Unit,
    onCreateFile: () -> Unit,
    onEdit: (() -> Unit)?,
    onRename: () -> Unit,
    onContextMenu: () -> Unit,
    onContextMenuDismissed: () -> Unit,
    onDelete: () -> Unit,
    nameEditor: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight)
                .then(if (nameEditor == null) Modifier.hierarchyClickable(onClick = onSelected, onContextMenu = onContextMenu) else Modifier),
            color = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                Color.Transparent
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (nameEditor != null) HierarchyIconButton(
                    imageVector = if (hasChildren && expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = "${folder.key.relativePath} 폴더", onClick = null,
                ) else HierarchyToggleIcon(
                    label = folder.key.relativePath, expanded = expanded, hasChildren = hasChildren,
                    restingIcon = if (hasChildren && expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    onToggle = onToggleExpanded, showChevronOnInteraction = false,
                )
                Row(
                    modifier = Modifier
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (nameEditor != null) nameEditor() else Text(
                        text = folder.key.relativePath,
                        style = WorkspaceUiMetrics.secondaryTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (nameEditor == null && !isPlot && !orderSaving) {
                    HierarchyIconButton(
                        imageVector = Icons.Default.Add,
                        contentDescription = "${folder.key.relativePath}에 새 파일",
                        onClick = onCreateFile,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                } else if (nameEditor == null) Spacer(Modifier.size(WorkspaceUiMetrics.hierarchyFolderRowHeight))
            }
        }
        FolderContextMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = onContextMenuDismissed,
            onRename = { onContextMenuDismissed(); onRename() },
            onEdit = onEdit?.let { edit -> { onContextMenuDismissed(); edit() } },
            onDelete = {
                onContextMenuDismissed()
                onDelete()
            },
        )
    }
}

@Composable
internal fun HierarchyFileRow(
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
    groupChild: Boolean = false,
    onTrashRequested: (() -> Unit)? = null,
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
            val stepThreshold = PopupUiMetrics.RowMinHeight.toPx()
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
    HierarchyDocumentRow(
        label = fileName, depth = depth, selected = selected, onClick = onClick,
        groupChild = groupChild,
        dragModifier = dragModifier,
        dragDescription = if (draggable) "$fileName 순서 변경 핸들" else null,
        onTrashRequested = onTrashRequested,
    )
}
@Composable
private fun CompactIconAction(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconTint: Color? = null,
) {
    HierarchyIconButton(
        imageVector = imageVector,
        contentDescription = contentDescription,
        onClick = onClick,
        tint = iconTint,
    )
}

internal fun Modifier.hierarchyDepthGuides(
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
    onEdit: (() -> Unit)?,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    DrawerDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        width = PopupUiMetrics.MenuWidth,
        offset = DpOffset(4.dp, 4.dp),
    ) {
        if (onRename != null) DrawerMenuItem(
            text = "이름 변경",
            onClick = onRename,
            leadingIcon = Icons.Default.Edit,
        )
        if (onEdit != null) DrawerMenuItem(
            text = "폴더 설정",
            onClick = onEdit,
            leadingIcon = Icons.Default.Settings,
        )
        if (onDelete != null) {
            HorizontalDivider()
            DrawerMenuItem(
                text = "삭제",
                onClick = onDelete,
                leadingIcon = Icons.Default.Delete,
                danger = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.hierarchyClickable(
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
