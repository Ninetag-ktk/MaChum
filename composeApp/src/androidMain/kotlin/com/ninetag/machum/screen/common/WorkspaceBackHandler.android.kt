package com.ninetag.machum.screen.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
internal actual fun WorkspaceBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    BackHandler(enabled) {
        if (imeVisible) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        } else {
            onBack()
        }
    }
}
