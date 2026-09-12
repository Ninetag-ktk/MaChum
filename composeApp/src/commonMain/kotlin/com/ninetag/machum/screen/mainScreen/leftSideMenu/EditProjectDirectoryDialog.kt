package com.ninetag.machum.screen.mainScreen.leftSideMenu

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.ninetag.machum.screen.common.directoryNameError

@Composable
internal fun EditProjectDirectoryDialog(
    showProjectSettings: Boolean = true,
    directoryName: String,
    existingDirectoryNames: Set<String>,
    initialConfig: FolderConfig,
    onDismissRequest: () -> Unit,
    onSave: suspend (String, FolderConfig) -> Boolean,
    onDeleteRequest: (() -> Unit)?,
) {
    val editorState = rememberFolderConfigEditorState(initialConfig)
    var name by remember(directoryName) { mutableStateOf(directoryName) }
    val scope = rememberCoroutineScope()
    var isSaving by remember(directoryName) { mutableStateOf(false) }
    var saveError by remember(directoryName) { mutableStateOf<String?>(null) }
    val trimmedName = name.trim()
    val nameError = directoryNameError(
        name = name,
        existingNames = existingDirectoryNames,
        currentName = directoryName,
        invalidNameMessage = "폴더 이름으로 사용할 수 없는 문자가 포함되어 있습니다.",
    )
    val canSave = !isSaving && trimmedName.isNotEmpty() && nameError == null
    val dismiss = { if (!isSaving) onDismissRequest() }
    val submit = {
        if (!isSaving && canSave) {
            val submittedName = trimmedName
            val submittedConfig = if (showProjectSettings) editorState.config else FolderConfig(type = FolderType.GENERAL)
            isSaving = true
            saveError = null
            scope.launch {
                try {
                    if (onSave(submittedName, submittedConfig)) onDismissRequest()
                    else saveError = "설정을 저장하지 못했습니다. 입력을 확인하고 다시 시도해 주세요."
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    saveError = error.message ?: "설정을 저장하지 못했습니다. 다시 시도해 주세요."
                } finally {
                    isSaving = false
                }
            }
        }
        Unit
    }
    val deleteAction: (@Composable () -> Unit)? = onDeleteRequest?.let { deleteRequest ->
        {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    onDismissRequest()
                    deleteRequest()
                },
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    PolicyDialog(
        onDismissRequest = dismiss,
        title = if (showProjectSettings) "폴더 설정" else "폴더 이름 변경",
        width = if (showProjectSettings) PopupUiMetrics.SettingsWidth else PopupUiMetrics.DialogWidth,
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = submit,
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) {
                Text(if (isSaving) "저장 중…" else "저장")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = dismiss,
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) {
                Text("취소")
            }
        },
        leadingButton = deleteAction,
    ) {
        OutlinedTextField(
            enabled = !isSaving,
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("디렉터리 이름") },
            shape = RoundedCornerShape(6.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            isError = nameError != null,
            supportingText = nameError?.let { error -> { Text(error) } },
        )
        if (showProjectSettings) FolderConfigEditor(
            state = editorState,
            enabled = !isSaving,
            plotDescription = "기존 파일은 자동 변경하지 않고 미분류 상태로 유지합니다.",
            autoTagsDescription = "공백은 _로 저장되며 기존 파일에도 즉시 반영됩니다.",
            onSubmit = submit,
        )
        if (isSaving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                if (showProjectSettings) "폴더 설정과 기존 문서의 자동 태그를 반영하는 중입니다."
                else "폴더 이름을 변경하는 중입니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    }
}
