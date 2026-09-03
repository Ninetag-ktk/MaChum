package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.external.isValidProjectFolderName
import com.ninetag.machum.screen.common.SingleLineSubmitGate

@Composable
internal fun EditProjectDirectoryDialog(
    directoryName: String,
    existingDirectoryNames: Set<String>,
    allowRename: Boolean,
    initialConfig: FolderConfig,
    onDismissRequest: () -> Unit,
    onSave: (String, FolderConfig) -> Unit,
    onDeleteRequest: (() -> Unit)?,
) {
    val editorState = rememberFolderConfigEditorState(initialConfig)
    var name by remember(directoryName) { mutableStateOf(directoryName) }
    val submitGate = remember(directoryName) { SingleLineSubmitGate() }
    val trimmedName = name.trim()
    val otherNames = existingDirectoryNames
        .filterNot { it.equals(directoryName, ignoreCase = true) }
        .mapTo(mutableSetOf()) { it.lowercase() }
    val nameError = when {
        !allowRename -> null
        name.isBlank() -> null
        name != trimmedName -> "이름 앞뒤의 공백을 제거해 주세요."
        !isValidProjectFolderName(name) -> "폴더 이름으로 사용할 수 없는 문자가 포함되어 있습니다."
        name != directoryName && name.equals(directoryName, ignoreCase = true) ->
            "대소문자만 바꾸는 이름 변경은 지원하지 않습니다."
        name.lowercase() in otherNames -> "같은 이름의 디렉터리가 이미 있습니다."
        else -> null
    }
    val canSave = !allowRename || (trimmedName.isNotEmpty() && nameError == null)
    val submit = {
        submitGate.submitIf(canSave) {
            onSave(trimmedName, editorState.config)
            onDismissRequest()
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("디렉터리 설정") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (allowRename) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        label = { Text("디렉터리 이름") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        isError = nameError != null,
                        supportingText = nameError?.let { error -> { Text(error) } },
                    )
                } else {
                    Text(
                        text = directoryName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                FolderConfigEditor(
                    state = editorState,
                    plotDescription = "기존 파일은 자동 변경하지 않고 미분류 상태로 유지합니다.",
                    autoTagsDescription = "공백은 _로 저장되며 기존 파일에도 즉시 반영됩니다.",
                    onSubmit = submit,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = submit,
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            Row {
                onDeleteRequest?.let { deleteRequest ->
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            deleteRequest()
                        },
                    ) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text("취소")
                }
            }
        },
    )
}
