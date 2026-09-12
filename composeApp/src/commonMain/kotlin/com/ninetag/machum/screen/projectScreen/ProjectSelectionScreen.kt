package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.SingleLineSubmitGate
import com.ninetag.machum.screen.common.directoryNameError

@Composable
internal fun ProjectMessage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun CreateProjectDialog(
    existingProjectNames: Set<String>,
    isCreating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val submitGate = remember { SingleLineSubmitGate() }
    val nameFocusRequester = remember { FocusRequester() }
    val trimmedName = name.trim()
    val nameError = directoryNameError(
        name = name,
        existingNames = existingProjectNames,
        invalidNameMessage = "프로젝트 이름으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다.",
        duplicateNameMessage = "같은 이름의 프로젝트가 이미 있습니다.",
    )
    val canCreate = trimmedName.isNotEmpty() && nameError == null && !isCreating
    val submit = {
        submitGate.submitIf(canCreate) { onCreate(trimmedName) }
        Unit
    }

    LaunchedEffect(Unit) {
        nameFocusRequester.requestFocus()
    }

    LaunchedEffect(isCreating, errorMessage) {
        if (!isCreating && errorMessage != null) submitGate.reset()
    }

    PolicyDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = "새 프로젝트",
        confirmButton = {
            Button(
                onClick = submit,
                enabled = canCreate,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (isCreating) "생성 중…" else "프로젝트 생성")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating,
                modifier = Modifier.heightIn(min = 48.dp),
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
            label = { Text("프로젝트 이름") },
            placeholder = { Text("예: 장편 소설") },
            singleLine = true,
            enabled = !isCreating,
            isError = nameError != null,
            supportingText = nameError?.let { error -> { Text(error) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = RoundedCornerShape(6.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
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
