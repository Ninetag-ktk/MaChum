package com.ninetag.machum.screen.mainComposition

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.ninetag.machum.external.ProjectFolderDeletionPreview

@Composable
internal fun DeleteProjectDirectoryDialog(
    preview: ProjectFolderDeletionPreview,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val folderName = preview.folder.key.relativePath
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(if (preview.canDelete) "디렉터리 삭제" else "삭제할 수 없음")
        },
        text = {
            if (preview.canDelete) {
                val fileDescription = if (preview.markdownFiles.isEmpty()) {
                    "빈 디렉터리입니다."
                } else {
                    "Markdown 파일 ${preview.markdownFiles.size}개가 포함되어 있습니다."
                }
                Text("'$folderName'을 삭제합니다. $fileDescription 이 작업은 되돌릴 수 없습니다.")
            } else {
                val entries = preview.unsupportedEntries.take(5).joinToString(", ")
                Text(
                    "앱에서 관리하지 않는 하위 폴더나 파일이 있어 삭제하지 않습니다. " +
                        "외부 파일 관리자에서 먼저 정리해 주세요.\n\n확인된 항목: $entries"
                )
            }
        },
        confirmButton = {
            if (preview.canDelete) {
                TextButton(onClick = onConfirm) {
                    Text("영구 삭제", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(if (preview.canDelete) "취소" else "확인")
            }
        },
    )
}
