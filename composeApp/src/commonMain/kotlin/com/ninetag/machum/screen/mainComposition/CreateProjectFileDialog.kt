package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.PlotStage
import com.ninetag.machum.external.PlotFileEntry
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.isValidProjectFileTitle
import com.ninetag.machum.external.nextDefaultFileName
import com.ninetag.machum.external.nextPlotFileName
import com.ninetag.machum.screen.common.SingleLineSubmitGate

@Composable
internal fun CreateProjectFileDialog(
    folderConfig: FolderConfig,
    defaultStartNumber: Int,
    files: List<ProjectFile>,
    plotEntries: List<PlotFileEntry>,
    initialPlotStage: PlotStage? = null,
    onDismissRequest: () -> Unit,
    onCreate: (title: String, stage: PlotStage?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedStage by remember(initialPlotStage) { mutableStateOf(initialPlotStage) }
    val submitGate = remember { SingleLineSubmitGate() }
    val titleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        titleFocusRequester.requestFocus()
    }

    val trimmedTitle = title.trim()
    val titleError = if (title.isEmpty()) null else projectFileTitleError(title)
    val previewBaseName = when {
        trimmedTitle.isEmpty() || titleError != null -> null
        folderConfig.isPlot -> selectedStage?.let { plotEntries.nextPlotFileName(it, trimmedTitle) }
        folderConfig.type == FolderType.DEFAULT -> files.nextDefaultFileName(
            title = trimmedTitle,
            startAt = defaultStartNumber,
        )
        else -> trimmedTitle
    }
    val previewFileName = previewBaseName?.let { "$it.md" }
    val existingNames = files.mapTo(mutableSetOf()) { it.key.fileName.lowercase() }
    val duplicateError = previewFileName?.lowercase() in existingNames
    val canCreate = previewFileName != null && !duplicateError
    val submit = {
        submitGate.submitIf(canCreate) {
            onCreate(trimmedTitle, selectedStage)
            onDismissRequest()
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = "새 파일",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(titleFocusRequester),
                    label = { Text("파일 제목") },
                    placeholder = { Text("제목") },
                    shape = RoundedCornerShape(6.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = titleError != null || duplicateError,
                    supportingText = {
                        when {
                            titleError != null -> Text(titleError)
                            duplicateError -> Text("같은 이름의 파일이 이미 있습니다.")
                            else -> Text("확장자는 자동으로 .md가 붙습니다.")
                        }
                    },
                )

                if (folderConfig.isPlot) {
                    Text(
                        text = "플롯 단계",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    PlotStage.entries.forEach { stage ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStage = stage }
                                .padding(vertical = 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedStage == stage,
                                onClick = { selectedStage = stage },
                            )
                            Text(
                                text = stage.frontmatterValue,
                                modifier = Modifier.padding(start = 4.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                previewFileName?.let { fileName ->
                    Text(
                        text = "생성 파일: $fileName",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (duplicateError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = submit,
            ) {
                Text("생성")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("취소")
            }
        },
    )
}

internal fun projectFileTitleError(title: String): String? = when {
    title.isBlank() -> "파일 제목을 입력해 주세요."
    title != title.trim() -> "제목 앞뒤의 공백을 제거해 주세요."
    title.endsWith(".md", ignoreCase = true) -> "확장자 .md는 입력하지 않아도 됩니다."
    !isValidProjectFileTitle(title) -> "파일 제목으로 사용할 수 없는 문자나 예약어가 포함되어 있습니다."
    else -> null
}
