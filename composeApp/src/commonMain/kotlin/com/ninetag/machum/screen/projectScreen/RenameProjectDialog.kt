package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.screen.common.SingleLineSubmitGate
import com.ninetag.machum.screen.common.directoryNameError

@Composable
internal fun RenameProjectDialog(
    currentName: String,
    existingProjectNames: Set<String>,
    isRenaming: Boolean,
    errorMessage: String?,
    onDismissRequest: () -> Unit,
    onRename: (String) -> Unit,
    workspaceLabel: String = "프로젝트",
    description: String = "프로젝트 폴더와 파일의 프로젝트 태그가 함께 변경됩니다.",
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val submitGate = remember(currentName) { SingleLineSubmitGate() }
    val nameFocusRequester = remember(currentName) { FocusRequester() }
    val trimmedName = name.trim()
    val nameError = directoryNameError(
        name = name,
        existingNames = existingProjectNames,
        currentName = currentName,
        invalidNameMessage = "$workspaceLabel 이름으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다.",
        duplicateNameMessage = "같은 이름의 $workspaceLabel 항목이 이미 있습니다.",
        caseOnlyRenameMessage = "대소문자만 변경하는 이름은 사용할 수 없습니다.",
    )
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

    PolicyDialog(
        onDismissRequest = { if (!isRenaming) onDismissRequest() },
        title = "$workspaceLabel 이름 변경",
        confirmButton = {
            Button(
                enabled = canRename && !isRenaming,
                onClick = submit,
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) {
                Text(if (isRenaming) "이름 변경 중…" else "이름 변경")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                enabled = !isRenaming,
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) {
                Text("취소")
            }
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocusRequester),
            label = { Text("$workspaceLabel 이름") },
            shape = RoundedCornerShape(6.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            enabled = !isRenaming,
            isError = nameError != null,
            supportingText = nameError?.let { error -> { Text(error) } },
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
