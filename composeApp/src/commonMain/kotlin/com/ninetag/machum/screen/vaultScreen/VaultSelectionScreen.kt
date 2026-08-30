package com.ninetag.machum.screen.vaultScreen

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun VaultSelectionScreen(reset: () -> Unit) {
    rememberVaultPickerUI().Show(reset)
}

@Composable
internal fun VaultPickerContent(reset: () -> Unit) {
    var isCreating by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCreating) {
                CreateVaultContent(reset = reset, onBack = { isCreating = false })
            } else {
                SelectVaultContent(reset = reset, onCreate = { isCreating = true })
            }
        }
    }
}

@Composable
private fun SelectVaultContent(
    reset: () -> Unit,
    onCreate: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()
    var isOpening by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.padding(18.dp).size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "글쓰기 공간을 선택하세요",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Vault에는 여러 프로젝트와 Markdown 파일이 저장됩니다.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = {
                scope.launch {
                    isOpening = true
                    errorMessage = null
                    runCatching { fileManager.pickVault() }
                        .onSuccess { vault -> if (vault != null) reset() }
                        .onFailure { errorMessage = "Vault를 열지 못했습니다. 다시 시도해 주세요." }
                    isOpening = false
                }
            },
            enabled = !isOpening,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (isOpening) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("기존 Vault 열기")
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = onCreate,
            enabled = !isOpening,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("새 Vault 만들기")
        }
        errorMessage?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CreateVaultContent(
    reset: () -> Unit,
    onBack: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()
    var parentDirectory by remember { mutableStateOf<PlatformFile?>(null) }
    var vaultName by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
        TextButton(onClick = onBack, enabled = !isCreating) {
            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Vault 선택으로 돌아가기")
        }
        Spacer(Modifier.height(20.dp))
        Text(text = "새 Vault 만들기", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "이름과 저장할 위치를 선택하세요.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = vaultName,
            onValueChange = { vaultName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Vault 이름") },
            placeholder = { Text("예: 나의 소설") },
            supportingText = { Text("선택한 위치 아래에 같은 이름의 폴더를 만듭니다.") },
            singleLine = true,
            enabled = !isCreating,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedCard(
            onClick = {
                scope.launch {
                    FileKit.openDirectoryPicker()?.let { parentDirectory = it }
                }
            },
            enabled = !isCreating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("저장 위치", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = parentDirectory?.name ?: "폴더를 선택하세요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("선택", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val parent = parentDirectory ?: return@Button
                scope.launch {
                    isCreating = true
                    errorMessage = null
                    runCatching { fileManager.setVault(parent, vaultName.trim()) }
                        .onSuccess { vault -> if (vault != null) reset() }
                        .onFailure { errorMessage = "Vault를 만들지 못했습니다. 이름과 위치를 확인해 주세요." }
                    isCreating = false
                }
            },
            enabled = parentDirectory != null && vaultName.isNotBlank() && !isCreating,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Vault 만들기")
            }
        }
        errorMessage?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
