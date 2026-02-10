package com.ninetag.machum.screen.selectionScreen

import androidx.compose.runtime.Composable

@Composable
fun VaultSelectionScreen(reset: () -> Unit, ) {
    val vaultPickerUI = rememberVaultPickerUI()
    vaultPickerUI.Show(reset = {reset()})
}