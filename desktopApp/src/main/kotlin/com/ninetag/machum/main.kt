package com.ninetag.machum

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ninetag.machum.di.commonModule
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.selectionScreen.DesktopVaultPickerContainer
import io.github.vinceglb.filekit.FileKit
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext.startKoin

fun main() {
    startKoin {
        modules(commonModule)
    }
    FileKit.init(appId = "MaChum")
    application {
        val fileManager = koinInject<FileManager>()
        val vaultData by remember {fileManager.bookmarks.map { it?.vaultData }}.collectAsState(null)

        when (vaultData) {
            null -> {
                // Vault 선택 팝업
                Window(
                    onCloseRequest = ::exitApplication,
                    state = rememberWindowState(
                        width = 420.dp,
                        height = 560.dp,
                        position = WindowPosition(Alignment.Center),
                    ),
                    title = "맞춤",
                    resizable = false,
                ) {
                    DesktopVaultPickerContainer(reset = {})
                }
            }
            else -> {
                Window(
                    onCloseRequest = ::exitApplication,
                    title = "맞춤",
                ) {
                    App()
                }
            }
        }
    }
}