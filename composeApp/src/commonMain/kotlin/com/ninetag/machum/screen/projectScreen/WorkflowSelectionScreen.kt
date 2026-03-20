package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.common.WorkflowListItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowSelectionScreen() {
    val fileManager = koinInject<FileManager>()
    val workflowList by fileManager.workflowList.collectAsState()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { it != SheetValue.Hidden }
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = {},
        modifier = Modifier.fillMaxWidth(),
        sheetState = sheetState,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val contentHeight = 56.dp * workflowList.size
            val isScrollable = contentHeight > maxHeight * 0.9f

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isScrollable) Modifier.fillMaxHeight(0.9f)
                        else Modifier.wrapContentHeight()
                    )
            ) {
                items(workflowList) { workflow ->
                    WorkflowListItem(
                        workflow = workflow,
                        onClick = { scope.launch { fileManager.pickWorkflow(workflow) } },
                    )
                }
            }
        }
    }
}