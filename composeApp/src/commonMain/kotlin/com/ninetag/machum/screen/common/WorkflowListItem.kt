package com.ninetag.machum.screen.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninetag.machum.external.getDescription
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.nameWithoutExtension

@Composable
fun WorkflowListItem(
    workflow: PlatformFile,
    onClick: () -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(Offset.Zero) }
    var workflowDescription by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        workflowDescription = workflow.getDescription()
    }

    Box {
        CustomListItem(
            isLongPressed = showContextMenu,
            onClick = onClick,
            onContextMenu = { offset ->
                menuPosition = offset
                showContextMenu = true
            },
            modifier = Modifier.height(56.dp),
        ) {
            Text(
                text = workflow.nameWithoutExtension,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = workflowDescription,
                modifier = Modifier.weight(1f).alpha(0.5f),
                fontSize = 12.sp,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = DpOffset(
                x = with(LocalDensity.current) {menuPosition.x.toDp()},
                y = with(LocalDensity.current) {menuPosition.y.toDp() - 56.dp}
            ),
        ) {
            DropdownMenuItem(
                text = { Text("드롭다운") },
                onClick = {  }
            )
        }
    }
}