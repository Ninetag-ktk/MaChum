package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.external.isValidProjectFolderName
import com.ninetag.machum.screen.common.SingleLineSubmitGate

@Composable
internal fun CreateProjectDirectoryDialog(
    existingDirectoryNames: Set<String>,
    onDismissRequest: () -> Unit,
    onCreate: (String, FolderConfig) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val editorState = rememberFolderConfigEditorState()
    val submitGate = remember { SingleLineSubmitGate() }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

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
    val submit = {
        submitGate.submitIf(canCreate) {
            onCreate(trimmedName, editorState.config)
            onDismissRequest()
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = "디렉터리 추가",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester),
                    label = { Text("디렉터리 이름") },
                    shape = RoundedCornerShape(6.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = nameError != null,
                    supportingText = nameError?.let { error -> { Text(error) } },
                )

                FolderConfigEditor(
                    state = editorState,
                    plotDescription = "7단계와 단계별 순번으로 원고를 구성합니다.",
                    autoTagsDescription = "쉼표로 구분하며 공백은 _로 저장됩니다. #은 생략해도 됩니다.",
                    onSubmit = submit,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = submit,
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
