package com.ninetag.machum.screen.vaultScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class AndroidVaultPickerUI : VaultPickerUI {
    @Composable
    override fun Show(reset: () -> Unit) {
        VaultPickerContent(reset)
    }
}

@Composable
actual fun rememberVaultPickerUI(): VaultPickerUI = remember { AndroidVaultPickerUI() }
