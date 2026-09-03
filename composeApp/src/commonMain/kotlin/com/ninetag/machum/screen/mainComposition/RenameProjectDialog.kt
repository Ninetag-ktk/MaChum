package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.ninetag.machum.external.isValidProjectFolderName
import com.ninetag.machum.screen.common.SingleLineSubmitGate

@Composable
internal fun RenameProjectDialog(
    currentName: String,
    existingProjectNames: Set<String>,
    isRenaming: Boolean,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val submitGate = remember(currentName) { SingleLineSubmitGate() }
    val nameFocusRequester = remember(currentName) { FocusRequester() }
    val trimmedName = name.trim()
    val otherProjectNames = existingProjectNames
        .filterNot { it.equals(currentName, ignoreCase = true) }
        .mapTo(mutableSetOf()) { it.lowercase() }
    val nameError = when {
        name.isBlank() -> null
        name != trimmedName -> "이름 앞뒤의 공백을 제거해 주세요."
        !isValidProjectFolderName(name) -> "프로젝트 이름으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다."
        name.lowercase() in otherProjectNames -> "같은 이름의 프로젝트가 이미 있습니다."
        name.equals(currentName, ignoreCase = true) && name != currentName ->
            "대소문자만 변경하는 이름은 사용할 수 없습니다."
        else -> null
    }
    val canRename = trimmedName.isNotEmpty() && trimmedName != currentName && nameError == null
    val submit = {
        submitGate.submitIf(canRename && !isRenaming) {
            onRename(trimmedName)
        }
        Unit
    }

    LaunchedEffect(currentName) {
        nameFocusRequester.requestFocus()
    }

    LaunchedEffect(isRenaming, errorMessage) {
        if (!isRenaming && errorMessage != null) submitGate.reset()
    }

    AlertDialog(
        onDismissRequest = { if (!isRenaming) onDismissRequest() },
        title = { Text("프로젝트 이름 변경") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester),
                    label = { Text("프로젝트 이름") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    enabled = !isRenaming,
                    isError = nameError != null,
                    supportingText = nameError?.let { error -> { Text(error) } },
                )
                Text(
                    text = "프로젝트 폴더와 파일의 프로젝트 태그가 함께 변경됩니다.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canRename && !isRenaming,
                onClick = submit,
            ) {
                Text(if (isRenaming) "변경 중…" else "변경")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isRenaming) {
                Text("취소")
            }
        },
    )
}
