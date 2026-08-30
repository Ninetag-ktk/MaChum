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
import com.ninetag.machum.external.ProjectFile
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun EditorPage(projectFile: ProjectFile) {
    val viewModel: MainViewModel = koinViewModel()

    LaunchedEffect(projectFile) { viewModel.loadPage(projectFile) }

    val cache by viewModel.noteFileCache.collectAsState()
    val noteFile = cache[projectFile.key] ?: return

    key(projectFile.key) {
        MarkdownBlockTextFieldM3(
            value = noteFile.body,
            onValueChange = { viewModel.updateBody(projectFile.key, it) },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
    }
}
