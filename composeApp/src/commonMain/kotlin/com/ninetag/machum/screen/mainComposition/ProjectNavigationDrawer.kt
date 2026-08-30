package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.external.FolderKey
import com.ninetag.machum.external.ProjectFolder
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name

@Composable
internal fun ProjectNavigationDrawer(
    vault: PlatformFile?,
    projects: List<PlatformFile>,
    currentProject: PlatformFile?,
    folders: List<ProjectFolder>,
    folderConfigs: Map<String, FolderConfig>,
    currentFolder: ProjectFolder?,
    onVaultChange: () -> Unit,
    onProjectSelected: (PlatformFile) -> Unit,
    onFolderSelected: (FolderKey) -> Unit,
    onCreateDirectory: (String, FolderConfig) -> Unit,
    onUpdateDirectoryConfig: (FolderKey, FolderConfig) -> Unit,
    onClose: () -> Unit,
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var settingsMenuExpanded by remember { mutableStateOf(false) }
    var showCreateDirectoryDialog by remember { mutableStateOf(false) }
    var editingFolderKey by remember { mutableStateOf<FolderKey?>(null) }

    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().widthIn(max = 360.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("디렉터리", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = currentProject?.name ?: "프로젝트를 선택하세요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "사이드바 닫기")
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                folders.filterNot { it.key == FolderKey.Base }.forEach { folder ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = folder.key.relativePath,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        selected = folder.key == currentFolder?.key,
                        onClick = { onFolderSelected(folder.key) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                            )
                        },
                        badge = {
                            IconButton(onClick = { editingFolderKey = folder.key }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "${folder.key.relativePath} 설정",
                                )
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                OutlinedButton(
                    onClick = { showCreateDirectoryDialog = true },
                    enabled = currentProject != null,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("디렉터리 추가", modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TextButton(
                            onClick = { projectMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Text(
                                text = currentProject?.name ?: "Project 선택",
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
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
                            Icon(Icons.Default.Settings, contentDescription = "설정")
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

    editingFolderKey?.let { folderKey ->
        EditProjectDirectoryDialog(
            directoryName = if (folderKey == FolderKey.Base) {
                currentProject?.name ?: "프로젝트 기본 설정"
            } else {
                folderKey.relativePath
            },
            initialConfig = folderConfigs[folderKey.relativePath] ?: FolderConfig(),
            onDismissRequest = { editingFolderKey = null },
            onSave = { config -> onUpdateDirectoryConfig(folderKey, config) },
        )
    }
}
