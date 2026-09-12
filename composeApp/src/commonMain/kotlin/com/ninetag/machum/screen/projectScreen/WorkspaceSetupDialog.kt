package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.WorkspaceKind
import com.ninetag.machum.external.WorkspaceOpenRequest
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import io.github.vinceglb.filekit.name

@Composable
internal fun WorkspaceSetupDialog(
    request: WorkspaceOpenRequest,
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceKind) -> Unit,
) {
    PolicyDialog(
        onDismissRequest = { if (!request.busy) onDismiss() },
        title = "폴더 사용 방식 선택",
        width = PopupUiMetrics.SettingsWidth,
        confirmButton = {
            Button(
                onClick = { onConfirm(WorkspaceKind.PROJECT) },
                enabled = !request.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (request.busy) "처리 중…" else "프로젝트로 설정")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !request.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("취소")
            }
        },
    ) {
        Text(
            text = "‘${request.directory.name}’에는 프로젝트 설정이 없습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "프로젝트로 설정하면 기본 작업 폴더(Concept, Outline, Character, Scene)를 만들고, " +
                "문서에 ID와 프로젝트 태그를 반영합니다. 기존 폴더와 문서 내용은 유지합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "소재 정리나 필사 용도라면 일반 폴더로 사용하세요. 이름순으로 탐색하며 번호와 태그를 자동으로 붙이지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { onConfirm(WorkspaceKind.GENERAL) },
            enabled = !request.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text("일반 폴더로 사용")
        }
        request.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (request.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
