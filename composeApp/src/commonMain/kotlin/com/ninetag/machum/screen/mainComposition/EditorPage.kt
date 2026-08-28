package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.markdown.ui.MarkdownBlockTextFieldM3
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EditorPage(file: PlatformFile) {
    val viewModel: MainViewModel = koinViewModel()

    LaunchedEffect(file) { viewModel.loadPage(file) }

    val cache by viewModel.noteFileCache.collectAsState()
    val noteFile = cache[file.name] ?: return

    key(file.name) {
        MarkdownBlockTextFieldM3(
            value = noteFile.body,
            onValueChange = { viewModel.updateBody(file.name, it) },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
    }
}
