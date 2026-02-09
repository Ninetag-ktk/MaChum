package com.ninetag.machum.screen.selectionScreen

import androidx.compose.runtime.Composable

interface VaultPickerUI {
    @Composable
    fun Show(
        asPopup: Boolean = false,
        reset: () -> Unit,
    )
}

@Composable
expect fun rememberVaultPickerUI(): VaultPickerUI