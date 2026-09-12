package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.WorkspaceBackHandler
import io.github.vinceglb.filekit.name

@Composable
internal fun WorkspaceTrashDialog(
    target: WorkspaceChoice,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WorkspaceBackHandler(true) { if (!busy) onDismiss() }
    PolicyDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "${target.directory.name} · 휴지통으로 이동",
        confirmButton = {
            Button(
                onClick = { if (!busy) onConfirm() }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (busy) "이동 중…" else "휴지통으로 이동") }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
        },
    ) {
        Text("${target.directory.name} 폴더 전체를 현재 Vault 내부 휴지통으로 이동하고 작업 공간 목록에서 제외합니다.")
        Text("하위 문서, 설정, 커밋 이력이 함께 이동합니다. 지금 영구 삭제하는 것은 아닙니다.")
        Text("이동 후 30일이 지나면 앱이 자동으로 영구 삭제합니다. 앱 실행이나 목록 갱신 시 정리합니다.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            Text("이동하지 못했습니다. 오류를 확인한 뒤 다시 시도해 주세요.")
        }
    }
}
