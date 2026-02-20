package com.ninetag.machum.screen.vaultScreen

import androidx.compose.runtime.Composable

interface VaultPickerUI {
    @Composable
    fun Show(reset: () -> Unit)
}

@Composable
expect fun rememberVaultPickerUI(): VaultPickerUI