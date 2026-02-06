package com.ninetag.machum

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.vinceglb.filekit.FileKit

fun main() {
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