package com.ninetag.machum

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ninetag.machum.di.commonModule
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.mainScreen.WorkspaceSaveCoordinator
import com.ninetag.machum.screen.vaultScreen.DesktopVaultPickerContainer
import io.github.vinceglb.filekit.FileKit
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
        val hasVault = bookmark.vaultData != null
        val windowState = rememberWindowState(
            width = 520.dp,
            height = 620.dp,
            position = WindowPosition(Alignment.Center),
        )
        LaunchedEffect(hasVault) {
            if (!hasVault) {
                windowState.placement = WindowPlacement.Floating
                windowState.position = WindowPosition(Alignment.Center)
            }
            windowState.size = if (hasVault) DpSize(800.dp, 600.dp) else DpSize(520.dp, 620.dp)
        }
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

        // Window를 교체하면 bookmark 복원 도중 App의 초기화 coroutine도 취소된다.
        Window(
            onCloseRequest = closeApplication,
            state = windowState,
            title = "맞춤",
            resizable = hasVault,
        ) {
            App(vaultContent = { reset -> DesktopVaultPickerContainer(reset = reset) })
        }
    }
}
