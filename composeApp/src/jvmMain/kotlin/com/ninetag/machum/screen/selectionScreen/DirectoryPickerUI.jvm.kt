package com.ninetag.machum.screen.selectionScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.ninetag.machum.external.FileManager
import org.koin.compose.koinInject

class DesktopVaultPickerUI : VaultPickerUI {
    @Composable
    override fun Show(
        asPopup: Boolean,
        reset: () -> Unit
    ) {
        val fileManager = koinInject<FileManager>()
        val scope = rememberCoroutineScope()

        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        var isCreating by remember { mutableStateOf(false) }
    }

}

@Composable
actual fun rememberVaultPickerUI(): VaultPickerUI {
    return remember { DesktopVaultPickerUI() }
}