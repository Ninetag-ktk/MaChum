package com.ninetag.machum.screen.mainScreen

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics

@Composable
internal fun FileTrashDialog(
    state: FileTrashUiState,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    PolicyDialog(
        onDismissRequest = onDismissRequest,
        title = "파일을 휴지통으로 이동",
        confirmButton = {
            Button(
                enabled = !state.busy,
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) {
                Text(if (state.busy) "이동 중…" else "휴지통으로 이동")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !state.busy,
                onClick = onDismissRequest,
                modifier = Modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
            ) { Text("취소") }
        },
    ) {
        Text("${state.file.key.fileName} 파일을 Vault 내부 휴지통으로 이동합니다. 휴지통의 파일은 30일 후 자동 삭제됩니다.")
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
