package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.isValidProjectFolderName
import com.ninetag.machum.screen.common.ProjectListItem
import com.ninetag.machum.screen.common.SingleLineSubmitGate
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun ProjectSelectionScreen() {
    val fileManager = koinInject<FileManager>()
    val bookmark by fileManager.bookmarks.collectAsState()
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<PlatformFile>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var isCreatingProject by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var projectOpenError by remember { mutableStateOf<String?>(null) }
    var reloadRequestId by remember { mutableStateOf(0) }

    LaunchedEffect(bookmark.vaultData, reloadRequestId) {
        val vault = bookmark.vaultData ?: return@LaunchedEffect
        projects = null
        loadError = false
        runCatching { fileManager.listProject(vault) }
            .onSuccess { projects = it }
            .onFailure {
                projects = emptyList()
                loadError = true
            }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "프로젝트 선택",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = bookmark.vaultData?.name ?: "현재 Vault",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { scope.launch { fileManager.reset() } },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Vault 변경")
                    }
                }
                Spacer(Modifier.height(20.dp))

                when {
                    projects == null -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    loadError -> {
                        ProjectMessage(
                            title = "프로젝트를 불러오지 못했습니다",
                            description = "Vault 접근 권한과 폴더 상태를 확인해 주세요.",
                            modifier = Modifier.weight(1f),
                            actionLabel = "다시 시도",
                            onAction = { reloadRequestId += 1 },
                        )
                    }

                    projects.isNullOrEmpty() -> {
                        ProjectMessage(
                            title = "아직 프로젝트가 없습니다",
                            description = "첫 프로젝트를 만들고 바로 글쓰기를 시작해 보세요.",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    else -> {
                        Text(
                            text = "프로젝트 ${projects.orEmpty().size}개",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        projectOpenError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            items(
                                items = projects.orEmpty(),
                                key = { it.toString() },
                            ) { project ->
                                ProjectListItem(
                                    project = project,
                                    onClick = {
                                        scope.launch {
                                            projectOpenError = null
                                            runCatching { fileManager.pickProject(project) }
                                                .onFailure { error ->
                                                    projectOpenError = error.message
                                                        ?.takeIf { it.isNotBlank() }
                                                        ?: "프로젝트를 열지 못했습니다. 다시 시도해 주세요."
                                                }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = {
                            createError = null
                            showCreateDialog = true
                        },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("새 프로젝트")
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            existingProjectNames = projects.orEmpty().mapTo(mutableSetOf()) { it.name },
            isCreating = isCreatingProject,
            errorMessage = createError,
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                scope.launch {
                    isCreatingProject = true
                    createError = null
                    val created = runCatching { fileManager.setProject(name) }.getOrNull()
                    isCreatingProject = false
                    if (created != null) {
                        showCreateDialog = false
                    } else {
                        createError = "프로젝트를 만들지 못했습니다. 이름을 확인해 주세요."
                    }
                }
            },
        )
    }
}

@Composable
private fun ProjectMessage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    existingProjectNames: Set<String>,
    isCreating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val submitGate = remember { SingleLineSubmitGate() }
    val nameFocusRequester = remember { FocusRequester() }
    val trimmedName = name.trim()
    val normalizedExistingNames = existingProjectNames.mapTo(mutableSetOf()) { it.lowercase() }
    val nameError = when {
        name.isBlank() -> null
        name != trimmedName -> "이름 앞뒤의 공백을 제거해 주세요."
        !isValidProjectFolderName(name) -> "프로젝트 이름으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다."
        name.lowercase() in normalizedExistingNames -> "같은 이름의 프로젝트가 이미 있습니다."
        else -> null
    }
    val canCreate = trimmedName.isNotEmpty() && nameError == null && !isCreating
    val submit = {
        submitGate.submitIf(canCreate) { onCreate(trimmedName) }
        Unit
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    LaunchedEffect(isCreating, errorMessage) {
        if (!isCreating && errorMessage != null) submitGate.reset()
    }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("새 프로젝트") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.focusRequester(nameFocusRequester),
                    label = { Text("프로젝트 이름") },
                    placeholder = { Text("예: 장편 소설") },
                    singleLine = true,
                    enabled = !isCreating,
                    isError = nameError != null,
                    supportingText = nameError?.let { error -> { Text(error) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                errorMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = submit,
                enabled = canCreate,
            ) {
                Text(if (isCreating) "만드는 중…" else "만들기")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text("취소")
            }
        },
    )
}
