package com.ninetag.machum.screen.selectionScreen

import androidx.compose.runtime.Composable

interface VaultPickerUI {
    @Composable
    fun Show(reset: () -> Unit)
}

@Composable
expect fun rememberVaultPickerUI(): VaultPickerUI