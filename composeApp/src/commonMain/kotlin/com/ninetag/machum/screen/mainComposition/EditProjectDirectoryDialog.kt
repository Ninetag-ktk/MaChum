package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig

@Composable
internal fun EditProjectDirectoryDialog(
    directoryName: String,
    initialConfig: FolderConfig,
    onDismissRequest: () -> Unit,
    onSave: (FolderConfig) -> Unit,
) {
    val editorState = rememberFolderConfigEditorState(initialConfig)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("디렉터리 설정") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = directoryName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                FolderConfigEditor(
                    state = editorState,
                    plotDescription = "기존 파일은 자동 변경하지 않고 미분류 상태로 유지합니다.",
                    autoTagsDescription = "공백은 _로 저장되며 기존 파일에도 즉시 반영됩니다.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(editorState.config)
                    onDismissRequest()
                },
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("취소")
            }
        },
    )
}
