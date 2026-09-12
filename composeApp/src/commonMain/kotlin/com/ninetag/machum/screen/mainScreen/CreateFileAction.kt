package com.ninetag.machum.screen.mainScreen

import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.screen.common.DrawerMenuItem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.screen.common.PopupUiMetrics

/** 이름 입력 없이 생성하며 Plot만 호출 버튼에 연결된 목록에서 고른다. */
@Composable
internal fun CreateFileAction(
    isPlot: Boolean,
    enabled: Boolean = true,
    onCreate: (PlotStage?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(isPlot) { mutableStateOf(false) }
    var submitted by remember(isPlot) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) submitted = false else expanded = false }
    Box {
        TextButton(
            onClick = {
                if (isPlot) { submitted = false; expanded = true }
                else onCreate(null)
            },
            enabled = enabled,
            modifier = modifier.heightIn(min = PopupUiMetrics.RowMinHeight),
        ) { Text("새 파일") }
        DrawerDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            width = PopupUiMetrics.MenuWidth,
        ) {
            PlotStage.entries.forEach { stage ->
                DrawerMenuItem(
                    text = stage.frontmatterValue,
                    onClick = {
                        if (!submitted) { submitted = true; expanded = false; onCreate(stage) }
                    },
                )
            }
        }
    }
}
