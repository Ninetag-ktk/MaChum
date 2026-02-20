package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.common.ProjectListItem
import com.ninetag.machum.screen.common.paddingDefault
import io.github.vinceglb.filekit.PlatformFile
import org.koin.compose.koinInject

@Composable
fun ProjectSelectionScreen() {
    val fileManager = koinInject<FileManager>()
    val bookmark by fileManager.bookmarks.collectAsState()

    var list by remember { mutableStateOf<List<PlatformFile>?>(null) }

    LaunchedEffect(Unit, bookmark?.vaultData) {
        list = fileManager.listProject(bookmark?.vaultData!!)
    }

    Column(
        modifier = Modifier
            .paddingDefault()
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        list?.forEach { project ->
            ProjectListItem(
                isSelected = false,
                project = project
            )
        }
    }
}