package com.ninetag.machum

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ninetag.machum.di.commonModule
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.mainComposition.WorkspaceSaveCoordinator
import com.ninetag.machum.screen.vaultScreen.DesktopVaultPickerContainer
import com.ninetag.machum.theme.AppTheme
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext.startKoin

fun main() {
    startKoin {
        modules(commonModule)
    }
    FileKit.init(appId = "MaChum")
    application {
        val fileManager = koinInject<FileManager>()
        val workspaceSaveCoordinator = koinInject<WorkspaceSaveCoordinator>()
        val bookmark by fileManager.bookmarks.collectAsState()
        val vaultData by remember {fileManager.bookmarks.map { it.vaultData }}.collectAsState(null)
        val closeScope = rememberCoroutineScope()
        var isClosing by remember { mutableStateOf(false) }
        val closeApplication = {
            if (!isClosing) {
                isClosing = true
                closeScope.launch {
                    if (workspaceSaveCoordinator.flushPendingWrites().isSuccess) {
                        exitApplication()
                    } else {
                        isClosing = false
                    }
                }
            }
        }

        bookmark.let {
            when (vaultData) {
                null -> {
                    // Vault 선택 팝업
                    Window(
                        onCloseRequest = closeApplication,
                        state = rememberWindowState(
                            width = 520.dp,
                            height = 620.dp,
                            position = WindowPosition(Alignment.Center),
                        ),
                        title = "맞춤",
                        resizable = false,
                    ) {
                        AppTheme {
                            DesktopVaultPickerContainer(reset = {})
                        }
                    }
                }
                else -> {
                    Window(
                        onCloseRequest = closeApplication,
                        title = "맞춤",
                    ) {
                        App()
                    }
                }
            }
        }
    }
}
