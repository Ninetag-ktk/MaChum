package com.ninetag.machum

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.ninetag.machum.di.commonModule
import io.github.vinceglb.filekit.FileKit
import org.koin.core.context.GlobalContext.startKoin

fun main() {
    startKoin {
        modules(commonModule)
    }
    FileKit.init(appId = "MaChum")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "맞춤",
        ) {
            App()
        }
    }
}