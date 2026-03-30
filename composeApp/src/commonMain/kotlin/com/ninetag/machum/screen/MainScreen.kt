package com.ninetag.machum.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.markdownName
import com.ninetag.machum.screen.mainComposition.EditorPage
import com.ninetag.machum.screen.mainComposition.EditorTopBar
import com.ninetag.machum.screen.mainComposition.MainViewModel
import io.github.vinceglb.filekit.nameWithoutExtension
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen() {
    val fileManager = koinInject<FileManager>()
    val viewModel: MainViewModel = koinViewModel()

    val fileList by viewModel.fileList.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()

    if (fileList.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = {fileList.size}
    )

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            viewModel.onPageChanged(pagerState.currentPage)
        }
    }
    LaunchedEffect(currentIndex) {
        if (pagerState.currentPage != currentIndex) {
            pagerState.animateScrollToPage(currentIndex)
        }
    }


    Scaffold(
        topBar = {
            EditorTopBar(
                fileName = fileList[pagerState.currentPage].markdownName(),
                onCommitClick = { /*TODO*/ },
                onFileListClick = { /*TODO*/ },
                onToggleClick = { /*TODO*/ },
                onRenameFile = {newName ->
                    viewModel.onRenameFile(fileList[pagerState.currentPage], newName)
                }
            )
        }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(it)
        ) { page ->
            EditorPage(file = fileList[page])
        }
    }
}