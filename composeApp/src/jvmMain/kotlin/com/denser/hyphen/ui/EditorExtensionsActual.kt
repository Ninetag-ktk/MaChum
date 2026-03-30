package com.denser.hyphen.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import com.denser.hyphen.state.HyphenTextState

@Composable
internal actual fun rememberMarkdownClipboard(
    state: HyphenTextState,
    clipboardLabel: String,
): Clipboard = LocalClipboard.current
