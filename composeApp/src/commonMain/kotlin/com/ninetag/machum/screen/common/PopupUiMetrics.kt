package com.ninetag.machum.screen.common

import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import com.ninetag.machum.theme.platformUsesTouchUi

/** Presentation values shared by popups; workspace chrome and editor geometry stay separate. */
internal object PopupUiMetrics {
    val DialogWidth = 440.dp
    val SettingsWidth = 520.dp
    val TaskWidth = 640.dp
    val ComparisonWidth = 900.dp
    val MenuWidth = 240.dp
    val MenuMaxHeight = 360.dp
    val RowMinHeight = 48.dp
    val IconSize = if (platformUsesTouchUi) 24.dp else 20.dp
    val bodyTextStyle: TextStyle
        @Composable get() = if (platformUsesTouchUi) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val secondaryTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium
    val ContentPadding = 24.dp
    val ListPadding = 16.dp
    val SectionSpacing = 16.dp
    val ItemSpacing = 8.dp
}
