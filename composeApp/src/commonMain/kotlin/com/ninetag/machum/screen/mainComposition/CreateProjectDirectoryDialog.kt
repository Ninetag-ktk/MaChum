package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.external.isValidProjectFolderName

@Composable
internal fun CreateProjectDirectoryDialog(
    existingDirectoryNames: Set<String>,
    onDismissRequest: () -> Unit,
    onCreate: (String, FolderConfig) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val editorState = rememberFolderConfigEditorState()

    val trimmedName = name.trim()
    val normalizedExistingNames = existingDirectoryNames.mapTo(mutableSetOf()) { it.lowercase() }
    val nameError = when {
        name.isBlank() -> null
        name != trimmedName -> "이름 앞뒤의 공백을 제거해 주세요."
        !isValidProjectFolderName(name) -> "폴더 이름으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다."
        name.lowercase() in normalizedExistingNames -> "같은 이름의 디렉터리가 이미 있습니다."
        else -> null
    }
    val canCreate = trimmedName.isNotEmpty() && nameError == null

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("디렉터리 추가") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("디렉터리 이름") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { error -> { Text(error) } },
                )

                FolderConfigEditor(
                    state = editorState,
                    plotDescription = "7단계와 단계별 순번으로 원고를 구성합니다.",
                    autoTagsDescription = "쉼표로 구분하며 공백은 _로 저장됩니다. #은 생략해도 됩니다.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = {
                    onCreate(trimmedName, editorState.config)
                    onDismissRequest()
                },
            ) {
                Text("추가")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("취소")
            }
        },
    )
}
