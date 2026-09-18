package com.ninetag.machum.screen.mainScreen.leftSideMenu

import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.screen.common.DrawerMenuItem
import com.ninetag.machum.screen.mainScreen.HierarchyFolderContent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.ninetag.machum.screen.mainScreen.FileMoveResult
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.theme.WorkspaceMotion
import com.ninetag.machum.theme.platformUsesTouchUi
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
    onMoveFile: suspend (ProjectFile, FolderKey, PlotStage?) -> FileMoveResult = { file, _, _ -> FileMoveResult(file.key) },
) {
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val collapsedSourceGroups = remember { mutableStateMapOf<String, Set<String?>>() }
    val collapsedPlotGroups = remember { mutableStateMapOf<String, Set<Pair<FolderKey, PlotStage?>>>() }
    val sourceWorkspaceKey = currentProject?.toString().orEmpty()
    val folderKeys = remember(folders) { folders.map(ProjectFolder::key) }
    val childFolders = remember(folders) { folders.filterNot { it.key == FolderKey.Base } }
    val availableFolderKeys = remember(folderKeys) {
        folderKeys.filterNotTo(mutableSetOf()) { it == FolderKey.Base }
    }
    val existingDirectoryNames = remember(childFolders) {
        childFolders.mapTo(mutableSetOf()) { it.key.relativePath }
    }
    val collapsedSourceGroupsForWorkspace = collapsedSourceGroups[sourceWorkspaceKey].orEmpty()
    val collapsedPlotGroupsForWorkspace = collapsedPlotGroups[sourceWorkspaceKey].orEmpty()
    val collapsedPlotStagesByFolder = remember(collapsedPlotGroupsForWorkspace) {
        collapsedPlotGroupsForWorkspace.groupBy({ it.first }, { it.second })
            .mapValues { (_, stages) -> stages.toSet() }
    }
    val moveDestinationsBySource = remember(isManagedProject, folderKeys, folderConfigs) {
        val sourceStages = listOf<PlotStage?>(null) + PlotStage.entries
        (listOf(FolderKey.Base) + folderKeys).distinct().associateWith { sourceFolder ->
            sourceStages.associateWith { sourceStage ->
                projectFileMoveDestinations(
                    isManagedProject,
                    sourceFolder,
                    sourceStage,
                    folders,
                    folderConfigs,
                )
            }
        }
    }
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
    var movingFileKeys by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf(emptySet<FileKey>())
    }
    var moveErrors by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf<Map<FileKey, String>>(emptyMap())
    }
    var fileMoveDrag by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf<ProjectFileMoveDrag?>(null)
    }
    var hoveredMoveDestination by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf<HierarchyFileMoveDestination?>(null)
    }
    var hoveredOrderInsertion by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf<ProjectFileOrderInsertion?>(null)
    }
    var fileMovePointer by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf<Offset?>(null)
    }
    var hierarchyViewport by remember(currentProject?.toString(), isManagedProject) {
        mutableStateOf(Rect.Zero)
    }
    val hierarchyListState = remember(currentProject?.toString(), isManagedProject) { LazyListState() }
    val moveDropTargetBounds = remember(currentProject?.toString(), isManagedProject) {
        mutableMapOf<HierarchyFileMoveDestination, Rect>()
    }
    val moveSourceBounds = remember(currentProject?.toString(), isManagedProject) {
        mutableMapOf<FileKey, Pair<ProjectFileMoveSource, Rect>>()
    }
    val scope = rememberCoroutineScope()
    val workspaceIdentity = "$isManagedProject:$sourceWorkspaceKey"
    val currentWorkspaceIdentity by rememberUpdatedState(workspaceIdentity)
    val currentOnMoveFile by rememberUpdatedState(onMoveFile)
    val currentOnSaveDefaultOrder by rememberUpdatedState(onSaveDefaultOrder)
    val currentOnSavePlotOrder by rememberUpdatedState(onSavePlotOrder)

    fun createOrderDraft(folderKey: FolderKey): HierarchyOrderDraft? {
        val config = folderConfigs[folderKey.relativePath]
            ?: if (folderKey == FolderKey.Base && isManagedProject) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()
        val content = folderContents[folderKey] ?: HierarchyFolderContent()
        return when {
            config.isPlot -> plotHierarchyOrderDraft(folderKey, content.plotEntries)
            config.type == FolderType.DEFAULT -> defaultHierarchyOrderDraft(folderKey, content.files)
            else -> null
        }
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
            orderErrors = if (saved) orderErrors - folderKey else orderErrors + (
                folderKey to "순서를 저장하지 못해 원래 순서로 복원했습니다."
            )
        }
    }
    fun moveFile(file: ProjectFile, destination: HierarchyFileMoveDestination) {
        if (!isManagedProject || fileMoveDrag != null || movingFileKeys.isNotEmpty() || orderSavingFolders.isNotEmpty() || orderDrafts.isNotEmpty()) return
        val requestedWorkspace = workspaceIdentity
        movingFileKeys += file.key
        moveErrors -= file.key
        scope.launch {
            val result = try {
                currentOnMoveFile(file, destination.folderKey, destination.plotStage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FileMoveResult(file.key, error.message ?: "파일을 이동하지 못했습니다.")
            } finally {
                if (currentWorkspaceIdentity == requestedWorkspace) movingFileKeys -= file.key
            }
            if (currentWorkspaceIdentity == requestedWorkspace) {
                moveErrors = if (result.errorMessage == null) moveErrors - file.key - result.finalKey
                else (moveErrors - file.key) + (result.finalKey to result.errorMessage)
            }
        }
    }
    fun startFileMoveDrag(
        file: ProjectFile,
        sourceFolder: FolderKey,
        sourcePlotStage: PlotStage?,
        orderable: Boolean,
        pointer: Offset,
    ) {
        if (!isManagedProject || fileMoveDrag != null || movingFileKeys.isNotEmpty() || orderSavingFolders.isNotEmpty() || orderDrafts.isNotEmpty()) return
        val allowed = projectFileMoveDestinations(
            isManagedProject,
            sourceFolder,
            sourcePlotStage,
            folders,
            folderConfigs,
        ).toSet()
        val orderDraft = createOrderDraft(sourceFolder)?.takeIf { orderable && it.contains(file.key) }
        if (allowed.isEmpty() && orderDraft == null) return
        if (orderDraft != null) {
            orderErrors = orderErrors - sourceFolder
            orderDrafts = orderDrafts + (sourceFolder to orderDraft)
        }
        fileMoveDrag = ProjectFileMoveDrag(file, sourceFolder, sourcePlotStage, orderDraft != null, allowed)
        fileMovePointer = pointer
        hoveredMoveDestination = projectFileMoveDropDestination(allowed, pointer, moveDropTargetBounds)
    }
    fun updateFileMoveDrag(pointer: Offset) {
        val drag = fileMoveDrag ?: return
        fileMovePointer = pointer
        val pointerOnSource = moveSourceBounds[drag.file.key]?.second?.contains(pointer) == true
        val insertion = when {
            !drag.orderable -> null
            pointerOnSource -> null
            else -> projectFileOrderInsertion(
                sourceKey = drag.file.key,
                sourceFolder = drag.sourceFolder,
                sourcePlotStage = drag.sourcePlotStage,
                pointer = pointer,
                sources = moveSourceBounds,
            )
        }
        hoveredOrderInsertion = insertion
        hoveredMoveDestination = if (insertion == null && !pointerOnSource) {
            projectFileMoveDropDestination(drag.allowedDestinations, pointer, moveDropTargetBounds)
        } else null
    }
    fun removeMoveDropTarget(destination: HierarchyFileMoveDestination) {
        moveDropTargetBounds.remove(destination)
        val drag = fileMoveDrag
        val pointer = fileMovePointer
        if (drag != null && pointer != null) {
            hoveredMoveDestination = projectFileMoveDropDestination(
                drag.allowedDestinations,
                pointer,
                moveDropTargetBounds,
            )
        }
    }
    fun cancelFileMoveDrag() {
        fileMoveDrag?.takeIf(ProjectFileMoveDrag::orderable)?.sourceFolder?.let { folderKey ->
            orderDrafts = orderDrafts - folderKey
        }
        fileMoveDrag = null
        fileMovePointer = null
        hoveredMoveDestination = null
        hoveredOrderInsertion = null
    }
    fun finishFileMoveDrag() {
        val drag = fileMoveDrag
        val pointer = fileMovePointer
        val insertion = if (drag?.orderable == true && pointer != null) {
            projectFileOrderInsertion(
                sourceKey = drag.file.key,
                sourceFolder = drag.sourceFolder,
                sourcePlotStage = drag.sourcePlotStage,
                pointer = pointer,
                sources = moveSourceBounds,
            )
        } else null
        val destination = if (insertion == null) {
            projectFileMoveReleaseDestination(
                drag?.allowedDestinations,
                pointer,
                moveDropTargetBounds,
            )
        } else null
        fileMoveDrag = null
        fileMovePointer = null
        hoveredMoveDestination = null
        hoveredOrderInsertion = null
        when {
            drag == null -> Unit
            destination != null -> {
                orderDrafts = orderDrafts - drag.sourceFolder
                moveFile(drag.file, destination)
            }
            drag.orderable -> {
                if (insertion != null) {
                    val draft = orderDrafts[drag.sourceFolder]
                    if (draft != null) {
                        orderDrafts = orderDrafts + (
                            drag.sourceFolder to moveHierarchyOrderDraftToInsertion(
                                draft = draft,
                                sourceKey = drag.file.key,
                                targetKey = insertion.targetKey,
                                after = insertion.edge == HierarchyOrderInsertionEdge.AFTER,
                            )
                        )
                    }
                }
                finishOrderDrag(drag.sourceFolder)
            }
        }
    }
    fun togglePlotGroup(folder: FolderKey, stage: PlotStage?) {
        val collapsed = collapsedPlotGroups[sourceWorkspaceKey].orEmpty()
        val target = folder to stage
        collapsedPlotGroups[sourceWorkspaceKey] = if (target in collapsed) collapsed - target else collapsed + target
    }
    LaunchedEffect(availableFolderKeys) {
        expandedFolderKeys = (expandedFolderKeys intersect availableFolderKeys) +
            (availableFolderKeys - knownFolderKeys)
        knownFolderKeys = availableFolderKeys
    }

    LaunchedEffect(currentFolder?.key) {
        currentFolder?.key
            ?.takeUnless { it == FolderKey.Base }
            ?.let { key -> expandedFolderKeys += key }
    }

    val baseFolderConfig = folderConfigs[FolderKey.Base.relativePath]
        ?: if (isManagedProject) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig(type = FolderType.GENERAL)
    val baseContent = folderContents[FolderKey.Base] ?: HierarchyFolderContent()
    val rootDropDestination = HierarchyFileMoveDestination("프로젝트 루트", FolderKey.Base, null)
        .takeIf { isManagedProject && !baseFolderConfig.isPlot }
    val rootDropModifier = projectFileMoveTargetModifier(
        destination = rootDropDestination,
        onPositioned = { destination, bounds -> moveDropTargetBounds[destination] = bounds },
        onDisposed = ::removeMoveDropTarget,
    )
    LaunchedEffect(movingFileKeys, orderSavingFolders) {
        if (movingFileKeys.isNotEmpty() || orderSavingFolders.isNotEmpty()) cancelFileMoveDrag()
    }
    DisposableEffect(workspaceIdentity) {
        onDispose { cancelFileMoveDrag() }
    }
    val density = LocalDensity.current
    val dragAutoScrollEdgePx = with(density) { WorkspaceUiMetrics.hierarchyDragAutoScrollEdge.toPx() }
    val dragAutoScrollMaxSpeedPx = with(density) { WorkspaceUiMetrics.hierarchyDragAutoScrollMaxSpeed.toPx() }
    LaunchedEffect(fileMoveDrag != null, hierarchyListState, dragAutoScrollEdgePx, dragAutoScrollMaxSpeedPx) {
        if (fileMoveDrag == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (fileMoveDrag != null) {
            val frame = withFrameNanos { it }
            val pointer = fileMovePointer
            if (pointer != null) {
                val velocity = hierarchyDragAutoScrollVelocity(
                    pointer = pointer,
                    viewport = hierarchyViewport,
                    edgePx = dragAutoScrollEdgePx,
                    maxSpeedPxPerSecond = dragAutoScrollMaxSpeedPx,
                )
                if (velocity != 0f) {
                    val elapsedSeconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                    hierarchyListState.scrollBy(velocity * elapsedSeconds)
                    // Scrolling changes every visible target's bounds even when the pointer stays still.
                    updateFileMoveDrag(pointer)
                }
            }
            previousFrame = frame
        }
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
                        .heightIn(min = WorkspaceUiMetrics.hierarchyToolbarHeight)
                        .then(rootDropModifier),
                    color = when {
                        rootDropDestination != null && hoveredMoveDestination == rootDropDestination -> MaterialTheme.colorScheme.secondaryContainer
                        fileMoveDrag?.allowedDestinations?.contains(rootDropDestination) == true ->
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    tonalElevation = if (rootDropDestination != null && hoveredMoveDestination == rootDropDestination) 2.dp else 0.dp,
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
                    state = hierarchyListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .onGloballyPositioned { hierarchyViewport = it.boundsInRoot() }
                        .then(hierarchyFileMoveDragModifier(
                            touchUi = platformUsesTouchUi,
                            sessionKey = workspaceIdentity,
                            toRoot = { local -> hierarchyViewport.topLeft + local },
                            onStart = { pointer ->
                                val source = moveSourceBounds.values
                                    .firstOrNull { (_, bounds) -> bounds.contains(pointer) }
                                    ?.first
                                if (source == null) false else {
                                    startFileMoveDrag(source.file, source.folderKey, source.plotStage, source.orderable, pointer)
                                    fileMoveDrag != null
                                }
                            },
                            onDrag = ::updateFileMoveDrag,
                            onEnd = ::finishFileMoveDrag,
                            onCancel = ::cancelFileMoveDrag,
                        )),
                ) {
                    if (!isManagedProject && generalSourceState?.enabled == true) hierarchyMotionItem(key = "general-source-groups") {
                        androidx.compose.runtime.key(currentProject?.toString()) {
                            GeneralSourceControls(generalSourceState, currentFile, onFileSelected,
                                onPlanGeneralSourceRename, onPlanGeneralSourceDelete, onPlanGeneralSourceAssign, onApplyGeneralSourcePlan,
                                collapsedGroups = collapsedSourceGroupsForWorkspace,
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
                        moveDestinationsForPlacement = { sourcePlotStage ->
                            moveDestinationsBySource[FolderKey.Base]?.get(sourcePlotStage).orEmpty()
                        },
                        activeMoveDestinations = fileMoveDrag?.allowedDestinations.orEmpty(),
                        moveInProgress = movingFileKeys.isNotEmpty(),
                        movingFileKeys = movingFileKeys,
                        moveErrors = moveErrors,
                        fileMoveDragActive = fileMoveDrag != null,
                        draggingFileKey = fileMoveDrag?.file?.key,
                        onMoveSourcePositioned = { file, sourcePlotStage, orderable, bounds ->
                            moveSourceBounds[file.key] = ProjectFileMoveSource(file, FolderKey.Base, sourcePlotStage, orderable) to bounds
                        },
                        onMoveSourceDisposed = { moveSourceBounds.remove(it) },
                        hoveredMoveDestination = hoveredMoveDestination,
                        hoveredOrderInsertion = hoveredOrderInsertion,
                        onDropTargetPositioned = { destination, bounds -> moveDropTargetBounds[destination] = bounds },
                        onDropTargetDisposed = ::removeMoveDropTarget,
                        onCreatePlotFile = onCreatePlotFile,
                        collapsedPlotStages = collapsedPlotStagesByFolder[FolderKey.Base].orEmpty(),
                        onTogglePlotStage = { togglePlotGroup(FolderKey.Base, it) },
                    )
                    hierarchyOrderErrorItem(
                        folderKey = FolderKey.Base,
                        depth = 0,
                        message = orderErrors[FolderKey.Base],
                    )

                    childFolders.forEach { folder ->
                        val folderConfig = if (!isManagedProject) FolderConfig(type = FolderType.GENERAL)
                            else folderConfigs[folder.key.relativePath] ?: FolderConfig()
                        val tagDraft = if (isManagedProject) tagDrafts.getOrPut(folder.key) { FolderTagDraft(folderConfig.autoTags) } else null
                        val folderDropDestination = HierarchyFileMoveDestination(folder.key.relativePath, folder.key, null)
                            .takeIf { isManagedProject && !folderConfig.isPlot }
                        hierarchyMotionItem(key = "folder:${folder.key.relativePath}", animate = orderDrafts.isEmpty() && orderSavingFolders.isEmpty()) {
                            val targetModifier = projectFileMoveTargetModifier(
                                destination = folderDropDestination,
                                onPositioned = { destination, bounds -> moveDropTargetBounds[destination] = bounds },
                                onDisposed = ::removeMoveDropTarget,
                            )
                            FolderHierarchyRow(
                                folder = folder,
                                selected = folder.key == currentFolder?.key,
                                expanded = folder.key in expandedFolderKeys,
                                hasChildren = folderConfig.isPlot || folderContents[folder.key]?.files?.isNotEmpty() == true,
                                contextMenuExpanded = contextMenuFolderKey == folder.key,
                                isPlot = folderConfig.isPlot,
                                orderSaving = folder.key in orderSavingFolders,
                                modifier = targetModifier,
                                dropAvailable = fileMoveDrag?.allowedDestinations?.contains(folderDropDestination) == true,
                                dropHovered = folderDropDestination != null && hoveredMoveDestination == folderDropDestination,
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
                                moveDestinationsForPlacement = { sourcePlotStage ->
                                    moveDestinationsBySource[folder.key]?.get(sourcePlotStage).orEmpty()
                                },
                                activeMoveDestinations = fileMoveDrag?.allowedDestinations.orEmpty(),
                                moveInProgress = movingFileKeys.isNotEmpty(),
                                movingFileKeys = movingFileKeys,
                                moveErrors = moveErrors,
                                fileMoveDragActive = fileMoveDrag != null,
                                draggingFileKey = fileMoveDrag?.file?.key,
                                onMoveSourcePositioned = { file, sourcePlotStage, orderable, bounds ->
                                    moveSourceBounds[file.key] = ProjectFileMoveSource(file, folder.key, sourcePlotStage, orderable) to bounds
                                },
                                onMoveSourceDisposed = { moveSourceBounds.remove(it) },
                                hoveredMoveDestination = hoveredMoveDestination,
                                hoveredOrderInsertion = hoveredOrderInsertion,
                                onDropTargetPositioned = { destination, bounds -> moveDropTargetBounds[destination] = bounds },
                                onDropTargetDisposed = ::removeMoveDropTarget,
                                onCreatePlotFile = onCreatePlotFile,
                                collapsedPlotStages = collapsedPlotStagesByFolder[folder.key].orEmpty(),
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
            existingDirectoryNames = existingDirectoryNames,
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
    collapsedPlotStages: Set<PlotStage?>,
    onTogglePlotStage: (PlotStage?) -> Unit,
    onFileTrashRequested: (ProjectFile) -> Unit,
    moveDestinationsForPlacement: (PlotStage?) -> List<HierarchyFileMoveDestination>,
    activeMoveDestinations: Set<HierarchyFileMoveDestination>,
    moveInProgress: Boolean,
    movingFileKeys: Set<FileKey>,
    moveErrors: Map<FileKey, String>,
    fileMoveDragActive: Boolean,
    draggingFileKey: FileKey?,
    onMoveSourcePositioned: (ProjectFile, PlotStage?, Boolean, Rect) -> Unit,
    onMoveSourceDisposed: (FileKey) -> Unit,
    hoveredMoveDestination: HierarchyFileMoveDestination?,
    hoveredOrderInsertion: ProjectFileOrderInsertion?,
    onDropTargetPositioned: (HierarchyFileMoveDestination, Rect) -> Unit,
    onDropTargetDisposed: (HierarchyFileMoveDestination) -> Unit,
) {
    if (!folderConfig.isPlot) {
        val moveDestinations = moveDestinationsForPlacement(null)
        val defaultDraft = orderDraft as? DefaultHierarchyOrderDraft
        val displayedFiles = defaultDraft?.reorder(content.files) ?: content.files
        val managedFileCount = content.files.count { it.numberedPrefix() != null }
        displayedFiles.forEach { file ->
            val orderable = folderConfig.type == FolderType.DEFAULT &&
                file.numberedPrefix() != null && managedFileCount > 1
            hierarchyMotionItem(key = "file:${file.key.relativePath}", animate = orderDraft == null && !orderSaving) {
                HierarchyFileRow(
                    file = file,
                    displayName = defaultDraft?.displayName(file),
                    depth = contentDepth,
                    selected = file.key == currentFile?.key,
                    onClick = { onFileSelected(file) },
                    onTrashRequested = if (orderSaving || moveInProgress || fileMoveDragActive) null else { { onFileTrashRequested(file) } },
                    supportingText = moveErrors[file.key],
                    moveBusy = file.key in movingFileKeys,
                    moveDragging = file.key == draggingFileKey,
                    moveDraggable = !orderSaving && !moveInProgress && (
                        (fileMoveDragActive && orderable) ||
                            (!fileMoveDragActive && orderDraft == null && (moveDestinations.isNotEmpty() || orderable))
                    ),
                    orderInsertionEdge = hoveredOrderInsertion?.takeIf { it.targetKey == file.key }?.edge,
                    onMoveSourcePositioned = { bounds -> onMoveSourcePositioned(file, null, orderable, bounds) },
                    onMoveSourceDisposed = { onMoveSourceDisposed(file.key) },
                )
            }
        }
        return
    }

    val plotDraft = orderDraft as? PlotHierarchyOrderDraft
    val displayedPlotEntries = plotDraft?.reorder(content.plotEntries) ?: content.plotEntries
    val plotEntriesByStage = displayedPlotEntries.groupBy { it.stage }
    PlotStage.entries.forEach { stage ->
        val stageEntries = plotEntriesByStage[stage].orEmpty()
        hierarchyMotionItem(key = "plot-stage:${folderKey.relativePath}:${stage.name}", animate = orderDraft == null && !orderSaving) {
            val destination = HierarchyFileMoveDestination(
                label = "${if (folderKey == FolderKey.Base) "프로젝트 루트" else folderKey.relativePath} · ${stage.frontmatterValue}",
                folderKey = folderKey,
                plotStage = stage,
            )
            val targetModifier = projectFileMoveTargetModifier(destination, onDropTargetPositioned, onDropTargetDisposed)
            HierarchyGroupRow(
                label = stage.frontmatterValue,
                depth = contentDepth,
                expanded = stage !in collapsedPlotStages,
                hasChildren = stageEntries.isNotEmpty(),
                selected = stageEntries.any { it.projectFile.key == currentFile?.key },
                onToggle = { onTogglePlotStage(stage) },
                onCreate = { onCreatePlotFile(folderKey, stage) },
                createEnabled = !orderSaving,
                dropAvailable = fileMoveDragActive && destination in activeMoveDestinations,
                dropHovered = hoveredMoveDestination == destination,
                modifier = targetModifier,
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
                        selected = entry.projectFile.key == currentFile?.key,
                        onClick = { onFileSelected(entry.projectFile) },
                        onTrashRequested = if (orderSaving || moveInProgress || fileMoveDragActive) null else { { onFileTrashRequested(entry.projectFile) } },
                        supportingText = moveErrors[entry.projectFile.key],
                        moveBusy = entry.projectFile.key in movingFileKeys,
                        moveDragging = entry.projectFile.key == draggingFileKey,
                        moveDraggable = !orderSaving && !moveInProgress && (
                            fileMoveDragActive || orderDraft == null
                        ),
                        orderInsertionEdge = hoveredOrderInsertion?.takeIf { it.targetKey == entry.projectFile.key }?.edge,
                        onMoveSourcePositioned = { bounds -> onMoveSourcePositioned(entry.projectFile, entry.stage, true, bounds) },
                        onMoveSourceDisposed = { onMoveSourceDisposed(entry.projectFile.key) },
                    )
                }
            }
    }

    val unclassifiedEntries = plotEntriesByStage[null].orEmpty()
    if (unclassifiedEntries.isNotEmpty()) {
        val moveDestinations = moveDestinationsForPlacement(null)
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
                    selected = entry.projectFile.key == currentFile?.key,
                    onClick = { onFileSelected(entry.projectFile) },
                    onTrashRequested = if (orderSaving || moveInProgress || fileMoveDragActive) null else { { onFileTrashRequested(entry.projectFile) } },
                    supportingText = moveErrors[entry.projectFile.key],
                    moveBusy = entry.projectFile.key in movingFileKeys,
                    moveDragging = entry.projectFile.key == draggingFileKey,
                    moveDraggable = moveDestinations.isNotEmpty() && !orderSaving && orderDraft == null && !moveInProgress,
                    onMoveSourcePositioned = { bounds -> onMoveSourcePositioned(entry.projectFile, null, false, bounds) },
                    onMoveSourceDisposed = { onMoveSourceDisposed(entry.projectFile.key) },
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
    modifier: Modifier = Modifier,
    dropAvailable: Boolean = false,
    dropHovered: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight)
                .then(if (nameEditor == null) Modifier.hierarchyClickable(onClick = onSelected, onContextMenu = onContextMenu) else Modifier),
            color = when {
                dropHovered -> MaterialTheme.colorScheme.secondaryContainer
                dropAvailable -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                selected -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> Color.Transparent
            },
            tonalElevation = if (dropHovered) 2.dp else 0.dp,
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
    selected: Boolean,
    onClick: () -> Unit,
    groupChild: Boolean = false,
    onTrashRequested: (() -> Unit)? = null,
    supportingText: String? = null,
    moveBusy: Boolean = false,
    moveDragging: Boolean = false,
    moveDraggable: Boolean = false,
    orderInsertionEdge: HierarchyOrderInsertionEdge? = null,
    onMoveSourcePositioned: (Rect) -> Unit = {},
    onMoveSourceDisposed: () -> Unit = {},
) {
    val fileName = displayName ?: file.key.fileName.let { name ->
        if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
    }

    val insertionColor = MaterialTheme.colorScheme.primary
    val moveSourceModifier = if (moveDraggable) {
        Modifier.onGloballyPositioned { onMoveSourcePositioned(it.boundsInRoot()) }
    } else Modifier
    val currentOnMoveSourceDisposed by rememberUpdatedState(onMoveSourceDisposed)
    DisposableEffect(file.key, moveDraggable) {
        onDispose { currentOnMoveSourceDisposed() }
    }
    HierarchyDocumentRow(
        label = fileName, depth = depth, selected = selected, onClick = onClick,
        groupChild = groupChild,
        modifier = if (orderInsertionEdge == null) Modifier else Modifier.drawWithContent {
            drawContent()
            val strokeWidth = 2.dp.toPx()
            val y = if (orderInsertionEdge == HierarchyOrderInsertionEdge.BEFORE) {
                strokeWidth / 2f
            } else {
                size.height - strokeWidth / 2f
            }
            drawLine(insertionColor, Offset(0f, y), Offset(size.width, y), strokeWidth)
        },
        onTrashRequested = onTrashRequested,
        supportingText = supportingText,
        moveDragging = moveDragging,
        bodyDragModifier = moveSourceModifier,
        showMenuAction = platformUsesTouchUi && !moveBusy && !moveDragging,
        contextMenuOnLongPress = false,
        trailingAction = if (moveBusy) {{
            Box(Modifier.size(WorkspaceUiMetrics.hierarchyFolderRowHeight), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }} else null,
    )
}

@Composable
internal fun hierarchyFileMoveDragModifier(
    touchUi: Boolean,
    sessionKey: Any?,
    toRoot: (Offset) -> Offset,
    onStart: (Offset) -> Boolean,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val currentToRoot by rememberUpdatedState(toRoot)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnCancel by rememberUpdatedState(onCancel)
    return Modifier.pointerInput(touchUi, sessionKey) {
        var active = false
        val start: (Offset) -> Boolean = { local ->
            currentOnStart(currentToRoot(local)).also { active = it }
        }
        val drag: (Offset) -> Unit = { local ->
            if (active) currentOnDrag(currentToRoot(local))
        }
        val end: () -> Unit = {
            if (active) {
                active = false
                currentOnEnd()
            }
        }
        val cancel: () -> Unit = {
            if (active) {
                active = false
                currentOnCancel()
            }
        }
        if (touchUi) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start(it) },
                onDragEnd = end,
                onDragCancel = cancel,
                onDrag = { change, _ ->
                    if (active) {
                        change.consume()
                        drag(change.position)
                    }
                },
            )
        } else {
            detectPrimaryMouseDragGestures(
                onDragStart = start,
                onDragEnd = end,
                onDragCancel = cancel,
                onDrag = drag,
            )
        }
    }
}

private suspend fun PointerInputScope.detectPrimaryMouseDragGestures(
    onDragStart: (Offset) -> Boolean,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    var dragActive = false
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id }
                if (change == null) {
                    if (dragActive) {
                        dragActive = false
                        onDragCancel()
                    }
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    if (dragActive) {
                        dragActive = false
                        onDragEnd()
                    }
                    return@awaitEachGesture
                }
                if (!dragActive && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                    continue
                }
                if (!dragActive) {
                    dragActive = onDragStart(down.position)
                    if (!dragActive) return@awaitEachGesture
                }
                change.consume()
                onDrag(change.position)
            }
        }
    } finally {
        if (dragActive) onDragCancel()
    }
}

private data class ProjectFileMoveDrag(
    val file: ProjectFile,
    val sourceFolder: FolderKey,
    val sourcePlotStage: PlotStage?,
    val orderable: Boolean,
    val allowedDestinations: Set<HierarchyFileMoveDestination>,
)

internal data class ProjectFileMoveSource(
    val file: ProjectFile,
    val folderKey: FolderKey,
    val plotStage: PlotStage?,
    val orderable: Boolean,
)

internal enum class HierarchyOrderInsertionEdge { BEFORE, AFTER }

internal data class ProjectFileOrderInsertion(
    val targetKey: FileKey,
    val edge: HierarchyOrderInsertionEdge,
)

internal fun projectFileOrderInsertion(
    sourceKey: FileKey,
    sourceFolder: FolderKey,
    sourcePlotStage: PlotStage?,
    pointer: Offset,
    sources: Map<FileKey, Pair<ProjectFileMoveSource, Rect>>,
): ProjectFileOrderInsertion? = sources.values
    .firstOrNull { (target, bounds) ->
        target.file.key != sourceKey && target.orderable &&
            target.folderKey == sourceFolder && target.plotStage == sourcePlotStage &&
            bounds.contains(pointer)
    }
    ?.let { (target, bounds) ->
        ProjectFileOrderInsertion(
            targetKey = target.file.key,
            edge = if (pointer.y < bounds.center.y) HierarchyOrderInsertionEdge.BEFORE
                else HierarchyOrderInsertionEdge.AFTER,
        )
    }

internal fun moveHierarchyOrderDraftToInsertion(
    draft: HierarchyOrderDraft,
    sourceKey: FileKey,
    targetKey: FileKey,
    after: Boolean,
): HierarchyOrderDraft {
    val groupKeys = when (draft) {
        is DefaultHierarchyOrderDraft -> draft.currentKeys
        is PlotHierarchyOrderDraft -> {
            val sourceStage = draft.currentItems.firstOrNull { it.fileKey == sourceKey }?.stage ?: return draft
            val targetStage = draft.currentItems.firstOrNull { it.fileKey == targetKey }?.stage ?: return draft
            if (sourceStage != targetStage) return draft
            draft.currentItems.filter { it.stage == sourceStage }.map(HierarchyPlotOrderItem::fileKey)
        }
    }
    val sourceIndex = groupKeys.indexOf(sourceKey)
    val targetIndex = groupKeys.indexOf(targetKey)
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex) return draft
    val rawInsertionIndex = targetIndex + if (after) 1 else 0
    val finalIndex = rawInsertionIndex - if (sourceIndex < rawInsertionIndex) 1 else 0
    if (finalIndex == sourceIndex) return draft
    val direction = if (finalIndex > sourceIndex) 1 else -1
    var moved = draft
    repeat(kotlin.math.abs(finalIndex - sourceIndex)) {
        moved = moved.move(sourceKey, direction)
    }
    return moved
}

internal fun projectFileMoveDropDestination(
    allowedDestinations: Set<HierarchyFileMoveDestination>,
    pointer: Offset,
    targetBounds: Map<HierarchyFileMoveDestination, Rect>,
): HierarchyFileMoveDestination? = targetBounds.entries
    .firstOrNull { (destination, bounds) -> destination in allowedDestinations && bounds.contains(pointer) }
    ?.key

internal fun projectFileMoveReleaseDestination(
    allowedDestinations: Set<HierarchyFileMoveDestination>?,
    pointer: Offset?,
    targetBounds: Map<HierarchyFileMoveDestination, Rect>,
): HierarchyFileMoveDestination? = if (allowedDestinations != null && pointer != null) {
    projectFileMoveDropDestination(allowedDestinations, pointer, targetBounds)
} else {
    null
}

internal fun hierarchyDragAutoScrollVelocity(
    pointer: Offset,
    viewport: Rect,
    edgePx: Float,
    maxSpeedPxPerSecond: Float,
): Float {
    if (
        edgePx <= 0f || maxSpeedPxPerSecond <= 0f ||
        viewport.width <= 0f || viewport.height <= 0f ||
        !viewport.contains(pointer)
    ) return 0f

    val effectiveEdge = edgePx.coerceAtMost(viewport.height / 2f)
    return when {
        pointer.y < viewport.top + effectiveEdge -> {
            val proximity = ((viewport.top + effectiveEdge - pointer.y) / effectiveEdge).coerceIn(0f, 1f)
            -maxSpeedPxPerSecond * proximity
        }
        pointer.y > viewport.bottom - effectiveEdge -> {
            val proximity = ((pointer.y - (viewport.bottom - effectiveEdge)) / effectiveEdge).coerceIn(0f, 1f)
            maxSpeedPxPerSecond * proximity
        }
        else -> 0f
    }
}

@Composable
private fun projectFileMoveTargetModifier(
    destination: HierarchyFileMoveDestination?,
    onPositioned: (HierarchyFileMoveDestination, Rect) -> Unit,
    onDisposed: (HierarchyFileMoveDestination) -> Unit,
): Modifier {
    val currentOnDisposed by rememberUpdatedState(onDisposed)
    DisposableEffect(destination) {
        onDispose { destination?.let(currentOnDisposed) }
    }
    return if (destination == null) Modifier else Modifier
        .testTag("project-file-drop:${destination.folderKey.relativePath}:${destination.plotStage?.name ?: "folder"}")
        .onGloballyPositioned { onPositioned(destination, it.boundsInRoot()) }
}

internal fun projectFileMoveDestinations(
    isManagedProject: Boolean,
    sourceFolder: FolderKey,
    sourcePlotStage: PlotStage?,
    folders: List<ProjectFolder>,
    folderConfigs: Map<String, FolderConfig>,
): List<HierarchyFileMoveDestination> {
    if (!isManagedProject) return emptyList()
    val availableFolderKeys = (listOf(FolderKey.Base) + folders.map(ProjectFolder::key)).distinct()
    return availableFolderKeys.flatMap { folderKey ->
        val folderLabel = if (folderKey == FolderKey.Base) "프로젝트 루트" else folderKey.relativePath
        val config = folderConfigs[folderKey.relativePath]
            ?: if (folderKey == FolderKey.Base) DEFAULT_BASE_FOLDER_CONFIG else FolderConfig()
        if (config.isPlot) {
            PlotStage.entries
                .filterNot { stage -> folderKey == sourceFolder && stage == sourcePlotStage }
                .map { stage ->
                    HierarchyFileMoveDestination(
                        label = "$folderLabel · ${stage.frontmatterValue}",
                        folderKey = folderKey,
                        plotStage = stage,
                    )
                }
        } else if (folderKey == sourceFolder && sourcePlotStage == null) {
            emptyList()
        } else {
            listOf(HierarchyFileMoveDestination(folderLabel, folderKey, null))
        }
    }
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
    onLongClick: (() -> Unit)? = onContextMenu,
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
            onLongClickLabel = onLongClick?.let { "메뉴 열기" },
            onLongClick = onLongClick,
        )
}

private fun Set<FolderKey>.toggle(key: FolderKey): Set<FolderKey> =
    if (key in this) this - key else this + key
