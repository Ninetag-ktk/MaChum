package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HorizontalRuleDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
