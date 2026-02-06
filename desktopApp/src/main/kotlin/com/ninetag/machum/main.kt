package com.ninetag.machum

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "맞춤",
    ) {
        App()
    }
}