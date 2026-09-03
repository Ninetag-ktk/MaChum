package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.ui.MarkdownBlockTextFieldM3
import com.ninetag.machum.external.ProjectFile
import androidx.compose.ui.Alignment

@Composable
fun EditorPage(
    projectFile: ProjectFile,
    documentKey: Any,
    loadState: FileLoadUiState?,
    onLoad: (ProjectFile) -> Unit,
    onRetry: (ProjectFile) -> Unit,
    onBodyChange: (String) -> Unit,
) {
    val latestOnLoad by rememberUpdatedState(onLoad)
    LaunchedEffect(projectFile.key, projectFile.platformFile.toString()) {
        latestOnLoad(projectFile)
    }

    when (val currentLoadState = loadState ?: FileLoadUiState.Loading) {
        FileLoadUiState.Loading -> FileLoadMessage {
            CircularProgressIndicator()
            Text("파일을 불러오는 중…")
        }

        is FileLoadUiState.Error -> FileLoadMessage {
            Text(
                text = currentLoadState.message,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onRetry(projectFile) }) {
                Text("다시 시도")
            }
        }

        is FileLoadUiState.Loaded -> MarkdownBlockTextFieldM3(
            value = currentLoadState.noteFile.body,
            onValueChange = onBodyChange,
            documentKey = documentKey,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun FileLoadMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
