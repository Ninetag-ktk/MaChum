package com.ninetag.machum.screen.vaultScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.ninetag.machum.theme.AppTheme

class DesktopVaultPickerUI : VaultPickerUI {
    @Composable
    override fun Show(reset: () -> Unit) {
        Window(
            onCloseRequest = reset,
            state = rememberWindowState(
                width = 520.dp,
                height = 620.dp,
                position = WindowPosition(Alignment.Center),
            ),
            title = "맞춤 · Vault 선택",
            resizable = false,
        ) {
            AppTheme {
                VaultPickerContent(reset)
            }
        }
    }
}

@Composable
actual fun rememberVaultPickerUI(): VaultPickerUI = remember { DesktopVaultPickerUI() }

@Composable
fun DesktopVaultPickerContainer(reset: () -> Unit) {
    VaultPickerContent(reset)
}
