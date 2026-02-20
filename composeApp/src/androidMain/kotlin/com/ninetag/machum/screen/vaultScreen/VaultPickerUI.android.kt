package com.ninetag.machum.screen.vaultScreen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow.Companion.StartEllipsis
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.nameWithoutExtension
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class AndroidVaultPickerUI : VaultPickerUI {
    @Composable
    override fun Show(reset: () -> Unit) {
        var isCreating by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!isCreating) {
                Menu(
                    reset = { reset() },
                    onCreateMenuClicked = { isCreating = true }
                )
            } else {
                Create(
                    reset = { reset() },
                    onBackClick = { isCreating = false }
                )
            }
        }
    }
}

@Composable
actual fun rememberVaultPickerUI(): VaultPickerUI {
    return remember { AndroidVaultPickerUI() }
}

@Composable
private fun Menu(
    reset: () -> Unit,
    onCreateMenuClicked: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()
    val bookmark = fileManager.bookmarks.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = bookmark.value?.vaultData?.nameWithoutExtension ?: "맞춤",
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = bookmark.value?.vaultData?.path ?: "글쓰기 앱",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(48.dp))
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).clickable { onCreateMenuClicked() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.AddCircleOutline,
                contentDescription = "CreateVaultIcon",
            )
            Text(
                text = "Create new vault",
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "SelectMenu",
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).clickable { scope.launch { fileManager.pickVault()?.let { reset() } } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = "OpenVaultIcon",
            )
            Text(
                text = "Open vault",
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "SelectMenu",
            )
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Create(
    reset: () -> Unit,
    onBackClick: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()

    var parentDirectory by remember {mutableStateOf<PlatformFile?>(null)}
    var vaultName by remember {mutableStateOf("")}

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).clickable { onBackClick() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "BackIcon",
            )
            Text("Create new vault",)
        }
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = vaultName,
            onValueChange = { vaultName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Vault Name") },
            placeholder = { Text("Enter vaultName") },
            trailingIcon = {
                IconButton(
                    onClick = { vaultName = "" }) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = "CancelValue",
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable {
                    scope.launch {
                        FileKit.openDirectoryPicker()?.let{parentDirectory = it}
                    }
                },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp, 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = parentDirectory?.path?:"Tap for Vault Directory",
                    color = parentDirectory?.let{ MaterialTheme.colorScheme.onBackground }?:run{ MaterialTheme.colorScheme.onSurfaceVariant },
                    textAlign = TextAlign.Center,
                    overflow = StartEllipsis,
                    maxLines = 1,
                )
            }
        }
        Button(
            onClick = {
                scope.launch {
                    fileManager.setVault(parentDirectory!!, vaultName)
                    reset()
                }
            },
            modifier = Modifier.wrapContentWidth(),
            enabled = parentDirectory != null && vaultName.isNotBlank(),
        ) {
            Text("Create")
        }
    }
}