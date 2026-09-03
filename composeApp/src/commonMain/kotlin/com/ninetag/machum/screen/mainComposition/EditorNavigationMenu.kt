package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileKey
import com.ninetag.machum.external.ProjectFile

@Composable
internal fun EditorNavigationMenu(
    expanded: Boolean,
    files: List<ProjectFile>,
    currentFile: ProjectFile?,
    onDismissRequest: () -> Unit,
    onFileSelected: (FileKey) -> Unit,
    onCreateFile: (() -> Unit)?,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        Text("현재 폴더 파일", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        files.forEach { file ->
            DropdownMenuItem(
                text = { Text(file.key.fileName.removeSuffix(".md")) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (file.key == currentFile?.key) Text("✓")
                },
                onClick = {
                    onFileSelected(file.key)
                    onDismissRequest()
                },
            )
        }

        if (onCreateFile != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("새 파일") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    onCreateFile()
                    onDismissRequest()
                },
            )
        }
    }
}
