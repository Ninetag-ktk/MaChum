package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.editor.MarkdownTextField
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
@Composable
fun EditorPage(file: PlatformFile) {
    val viewModel: MainViewModel = koinViewModel()

    LaunchedEffect(file) { viewModel.loadPage(file) }

    val cache by viewModel.noteFileCache.collectAsState()
    val noteFile = cache[file.name] ?: return

    key(file.name) {
        // onValueChange 콜백을 FlowPreview 디바운싱으로 연결
        val pendingMarkdown = remember { MutableStateFlow(noteFile.body) }

        LaunchedEffect(pendingMarkdown) {
            pendingMarkdown
                .debounce(500.milliseconds)
                .collectLatest { viewModel.updateBody(file.name, it) }
        }

        MarkdownTextField(
            value = noteFile.body,
            onValueChange = { pendingMarkdown.value = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
    }
}
