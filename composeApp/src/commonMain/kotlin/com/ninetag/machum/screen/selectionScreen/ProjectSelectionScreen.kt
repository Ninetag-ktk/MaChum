package com.ninetag.machum.screen.selectionScreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.common.ListItem
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.nameWithoutExtension
import kotlinx.coroutines.launch
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
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start
    ) {
        list?.forEach { project ->
            ProjectCard(
                isSelected = false,
                project = project
            )
        }
    }
}

@Composable
internal fun ProjectCard(
    isSelected: Boolean,
    project: PlatformFile
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()
    var showContextMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }

    Box {
        ListItem(
            selected = isSelected,
            isLongPressed = showContextMenu,
            onClick = {
                scope.launch {
                    fileManager.pickProject(project = project)
                }
            },
            onContextMenu = { offset ->
                menuPosition = offset
                showContextMenu = true
            },
            modifier = Modifier.height(40.dp)
        ) {
            Text(project.nameWithoutExtension)
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = DpOffset(
                x = with(LocalDensity.current) {menuPosition.x.toDp()},
                y = with(LocalDensity.current) {menuPosition.y.toDp()}
            ),
        ) {
            DropdownMenuItem(
                text = { Text("드롭다운") },
                onClick = {  }
            )
        }
    }
}