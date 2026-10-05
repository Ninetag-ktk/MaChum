package com.ninetag.machum.screen.mainScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import com.ninetag.machum.theme.platformUsesTouchUi
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.ui.MarkdownBlockTextFieldM3
import com.ninetag.machum.markdown.service.MarkdownEditorStyleTokens
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionRequest
import com.ninetag.machum.markdown.state.MarkdownBlockReferenceCreation
import com.ninetag.machum.markdown.state.MarkdownNavigationTarget
import com.ninetag.machum.markdown.state.MarkdownEmbedPreview
import androidx.compose.ui.Alignment

@Composable
fun EditorPage(
    projectFile: ProjectFile,
    documentKey: Any,
    isActive: Boolean,
    completionRevision: Any = Unit,
    loadState: FileLoadUiState?,
    onLoad: (ProjectFile) -> Unit,
    onRetry: (ProjectFile) -> Unit,
    onBodyChange: (String) -> Unit,
    onInternalLinkClick: (String) -> Unit = {},
    onInternalLinkCompletion: (MarkdownLinkCompletionRequest) -> List<MarkdownLinkCompletionCandidate> = { emptyList() },
    onCreateBlockReference: suspend (MarkdownBlockReferenceCreation, () -> Boolean) -> String? = { _, _ -> null },
    onBlockReferenceError: (String) -> Unit = {},
    isInternalLinkResolved: (String) -> Boolean = { true },
    onResolveEmbed: suspend (String) -> MarkdownEmbedPreview = {
        MarkdownEmbedPreview.Unavailable("임베드 대상을 찾을 수 없습니다.")
    },
    navigationTarget: MarkdownNavigationTarget? = null,
    onNavigationHandled: (Long) -> Unit = {},
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

        is FileLoadUiState.Loaded -> {
            val uriHandler = LocalUriHandler.current
            MarkdownBlockTextFieldM3(
                value = currentLoadState.noteFile.body,
                onValueChange = onBodyChange,
                documentKey = documentKey,
                isActive = isActive,
                showQuickBar = platformUsesTouchUi && isActive &&
                    WindowInsets.ime.getBottom(LocalDensity.current) > 0,
                completionRevision = completionRevision,
                onOpenExternalLink = { url -> runCatching { uriHandler.openUri(url) } },
                onOpenInternalLink = onInternalLinkClick,
                onCompleteInternalLink = onInternalLinkCompletion,
                onCreateBlockReference = onCreateBlockReference,
                onBlockReferenceError = onBlockReferenceError,
                isInternalLinkResolved = isInternalLinkResolved,
                onResolveEmbed = onResolveEmbed,
                navigationTarget = navigationTarget,
                onNavigationHandled = onNavigationHandled,
                contentPadding = PaddingValues(
                    start = MarkdownEditorStyleTokens.bodyHorizontalPadding,
                    top = MarkdownEditorStyleTokens.bodyTopPadding,
                    end = MarkdownEditorStyleTokens.bodyHorizontalPadding,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            )
        }
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
