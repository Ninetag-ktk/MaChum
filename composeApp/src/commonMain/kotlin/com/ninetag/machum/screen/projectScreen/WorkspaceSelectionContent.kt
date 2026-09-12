package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.WorkspaceSetup
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.screen.common.WorkspaceBackHandler
import com.ninetag.machum.theme.WorkspaceUiMetrics
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal data class WorkspaceChoice(val directory: PlatformFile, val setup: WorkspaceSetup, val hasSuspendedProject: Boolean = false)

private sealed interface WorkspaceDialog {
    data object Create : WorkspaceDialog
    data class Rename(val directory: PlatformFile) : WorkspaceDialog
    data class Transition(val choice: WorkspaceChoice) : WorkspaceDialog
    data class Trash(val choice: WorkspaceChoice) : WorkspaceDialog
}

@Composable
fun ProjectSelectionScreen(
    operationScope: CoroutineScope,
    onVaultChange: () -> Unit,
    onOpened: suspend () -> Unit,
    runOperation: suspend (suspend () -> Unit) -> Result<Unit>,
    externalError: String? = null,
    onErrorDismiss: () -> Unit = {},
) {
    val fileManager = koinInject<FileManager>()
    val focusManager = LocalFocusManager.current
    val bookmark by fileManager.bookmarks.collectAsState()
    val openRequest by fileManager.workspaceOpenRequest.collectAsState()
    val workspaceTrashWarning by fileManager.workspaceTrashWarning.collectAsState()
    val vault = bookmark.vaultData
    var choices by remember(vault) { mutableStateOf<List<WorkspaceChoice>?>(null) }
    var loadError by remember(vault) { mutableStateOf<String?>(null) }
    var operationError by remember(vault) { mutableStateOf<String?>(null) }
    var busy by remember(vault) { mutableStateOf(false) }
    var reload by remember(vault) { mutableIntStateOf(0) }
    var activeDialog by remember(vault) { mutableStateOf<WorkspaceDialog?>(null) }
    var menuTarget by remember(vault) { mutableStateOf<String?>(null) }
    // Keep a successful creation distinct from an unsuccessful open. Retry never creates twice.
    var createdGeneral by remember(vault) { mutableStateOf<PlatformFile?>(null) }
    var createdProject by remember(vault) { mutableStateOf<PlatformFile?>(null) }
    val blocked = busy || openRequest != null || activeDialog is WorkspaceDialog.Trash

    LaunchedEffect(vault, reload) {
        choices = null
        loadError = null
        if (vault == null) return@LaunchedEffect
        try {
            val directories = fileManager.listWorkspaceDirectories(vault)
            choices = (directories.projectChoices + directories.generalFolders).map {
                val setup = fileManager.workspaceSetup(it)
                WorkspaceChoice(it, setup, setup == WorkspaceSetup.GENERAL && fileManager.hasSuspendedProject(vault, it))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { loadError = error.message ?: "작업 공간을 읽지 못했습니다." }
    }

    fun execute(action: suspend () -> Unit) {
        if (busy || openRequest != null) return
        busy = true
        operationError = null
        operationScope.launch {
            try {
                runOperation(action).onFailure {
                    operationError = it.message ?: "작업을 완료하지 못했습니다."
                    reload++
                }
            } finally { busy = false }
        }
    }

    suspend fun open(directory: PlatformFile, configure: Boolean = false) {
        if (openWorkspaceChoice(fileManager, vault ?: error("Vault가 없습니다."), directory, configure)) onOpened()
    }

    // Screen-root barrier stays active beneath later popup/dialog handlers.
    WorkspaceBackHandler(enabled = true) {
        focusManager.clearFocus(force = true)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("작업 공간 선택", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onVaultChange, enabled = !blocked) { Text("Vault 선택으로") }
            }
            Text(vault?.name ?: "현재 Vault", style = WorkspaceUiMetrics.bodyTextStyle,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            (operationError ?: externalError)?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = WorkspaceUiMetrics.secondaryTextStyle)
                TextButton(onClick = { operationError = null; onErrorDismiss() }) { Text("알림 닫기") }
            }
            workspaceTrashWarning?.takeUnless { it == operationError }?.let { warning ->
                Text(warning, color = MaterialTheme.colorScheme.error, style = WorkspaceUiMetrics.secondaryTextStyle)
                TextButton(onClick = { if (!blocked) reload++ }, enabled = !blocked) { Text("휴지통 정리 다시 시도") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                loadError != null -> ProjectMessage("작업 공간을 불러오지 못했습니다", loadError!!,
                    Modifier.weight(1f), "다시 시도", { if (!blocked) reload++ })
                choices == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                choices!!.isEmpty() -> ProjectMessage("아직 작업 공간이 없습니다", "프로젝트나 일반 폴더를 만들어 시작하세요.", Modifier.weight(1f))
                else -> WorkspaceChoiceList(
                    choices = choices.orEmpty(), blocked = blocked || activeDialog is WorkspaceDialog.Transition,
                    menuTarget = menuTarget, onMenuTargetChange = { menuTarget = it },
                    onOpen = { execute { open(it.directory) } },
                    onRename = { operationError = null; activeDialog = WorkspaceDialog.Rename(it.directory) },
                    onTransition = { operationError = null; activeDialog = WorkspaceDialog.Transition(it) },
                    onTrash = { operationError = null; activeDialog = WorkspaceDialog.Trash(it) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            // Stack text actions on narrow screens instead of shrinking icon-only controls.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val existing = createdProject
                    if (existing != null) execute { open(existing) }
                    else { operationError = null; activeDialog = WorkspaceDialog.Create }
                }, enabled = !blocked && vault != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (createdProject == null) "새 프로젝트" else "생성한 프로젝트 다시 열기")
                }
                OutlinedButton(onClick = {
                    execute {
                        val target = createdGeneral ?: fileManager.createGeneralWorkspace(vault ?: error("Vault가 없습니다."))
                            .also { createdGeneral = it; reload++ }
                        try { open(target) } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            error("${target.name} 폴더는 생성됐지만 열지 못했습니다. 다시 열기를 눌러 주세요. ${error.message.orEmpty()}")
                        }
                    }
                }, enabled = !blocked && vault != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (createdGeneral == null) "새 일반 폴더" else "생성한 일반 폴더 다시 열기")
                }
            }
        }
    }
    when (val dialog = activeDialog) {
        is WorkspaceDialog.Trash -> {
            val target = dialog.choice
            WorkspaceTrashDialog(
                target = target, busy = busy, errorMessage = operationError,
                onDismiss = { if (!busy) { activeDialog = null; operationError = null } },
                onConfirm = { execute {
                    val result = fileManager.moveWorkspaceToTrash(vault ?: error("Vault가 없습니다."), target.directory)
                    val originalPath = target.directory.toString()
                    if (createdProject?.toString() == originalPath) createdProject = null
                    if (createdGeneral?.toString() == originalPath) createdGeneral = null
                    choices = choices?.filterNot { it.directory.toString() == originalPath }
                    activeDialog = null
                    operationError = result.cleanupWarning
                    reload++
                } },
            )
        }
        is WorkspaceDialog.Transition -> {
            val target = dialog.choice
            WorkspaceTransitionDialog(
                target = target, busy = busy, errorMessage = operationError,
                onDismiss = { if (!busy) { activeDialog = null; operationError = null } },
                onConfirm = { execute {
                    fileManager.transitionWorkspace(
                        vault ?: error("Vault가 없습니다."), target.directory, target.setup,
                        if (target.setup == WorkspaceSetup.PROJECT) WorkspaceKind.GENERAL else WorkspaceKind.PROJECT,
                    )
                    activeDialog = null
                    reload++
                } },
            )
        }
        WorkspaceDialog.Create -> CreateProjectDialog(
            existingProjectNames = choices.orEmpty().mapTo(mutableSetOf()) { it.directory.name },
            isCreating = busy, errorMessage = operationError,
            onDismiss = { if (!busy) { activeDialog = null; operationError = null } },
            onCreate = { name -> execute {
                val target = createdProject ?: (fileManager.createProject(name) ?: error("프로젝트를 만들지 못했습니다."))
                    .also { createdProject = it; reload++ }
                activeDialog = null
                open(target)
            } },
        )
        is WorkspaceDialog.Rename -> {
            val target = dialog.directory
            RenameProjectDialog(
                currentName = target.name,
                existingProjectNames = choices.orEmpty().mapTo(mutableSetOf()) { it.directory.name },
                isRenaming = busy, errorMessage = operationError,
                onDismissRequest = { if (!busy) { activeDialog = null; operationError = null } },
                onRename = { name -> execute {
                    val renamed = checkNotNull(fileManager.renameWorkspace(vault ?: error("Vault가 없습니다."), target, name)) {
                        "이름을 변경하지 못했습니다. 이름과 접근 권한을 확인해 주세요."
                    }
                    if (createdProject?.toString() == target.toString()) createdProject = renamed
                    if (createdGeneral?.toString() == target.toString()) createdGeneral = renamed
                    activeDialog = null; reload++
                } },
                workspaceLabel = "작업 공간",
                description = "선택한 작업 공간의 이름을 변경합니다. 프로젝트는 관리 태그도 함께 갱신합니다.",
            )
        }
        null -> Unit
    }
}

internal fun WorkspaceSetup.label() = when (this) {
    WorkspaceSetup.PROJECT -> "프로젝트"
    WorkspaceSetup.GENERAL -> "일반 폴더"
    WorkspaceSetup.NEEDS_CONFIRMATION -> "사용 방식 미정"
}
