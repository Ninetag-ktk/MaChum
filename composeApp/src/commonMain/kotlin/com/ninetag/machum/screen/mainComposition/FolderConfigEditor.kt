package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ninetag.machum.entity.FolderConfig
import com.ninetag.machum.entity.FolderType
import com.ninetag.machum.entity.normalizeTags

internal class FolderConfigEditorState(initialConfig: FolderConfig) {
    var type by mutableStateOf(initialConfig.type)
        private set

    var plotEnabled by mutableStateOf(initialConfig.isPlot)
        private set

    var autoTagsText by mutableStateOf(initialConfig.autoTags.joinToString(", "))

    val autoTags: List<String>
        get() = parseFolderAutoTags(autoTagsText)

    val config: FolderConfig
        get() = FolderConfig(
            type = type,
            plotEnabled = plotEnabled,
            autoTags = autoTags,
        )

    fun selectType(type: FolderType) {
        this.type = type
        if (type == FolderType.GENERAL) plotEnabled = false
    }

    fun updatePlotEnabled(enabled: Boolean) {
        plotEnabled = type == FolderType.DEFAULT && enabled
    }
}

@Composable
internal fun rememberFolderConfigEditorState(
    initialConfig: FolderConfig = FolderConfig(),
): FolderConfigEditorState = remember(initialConfig) {
    FolderConfigEditorState(initialConfig)
}

@Composable
internal fun FolderConfigEditor(
    state: FolderConfigEditorState,
    plotDescription: String,
    autoTagsDescription: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = "디렉터리 유형",
            style = MaterialTheme.typography.labelLarge,
        )

        listOf(FolderType.DEFAULT, FolderType.GENERAL).forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.selectType(option) }
                    .padding(vertical = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.type == option,
                    onClick = { state.selectType(option) },
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(option.displayName(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = option.description(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (option == FolderType.DEFAULT && state.type == FolderType.DEFAULT) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.updatePlotEnabled(!state.plotEnabled) }
                        .padding(start = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.plotEnabled,
                        onCheckedChange = state::updatePlotEnabled,
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Plot", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = plotDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.autoTagsText,
            onValueChange = { state.autoTagsText = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("자동 태그 (선택)") },
            placeholder = { Text("캐릭터, 설정") },
            shape = RoundedCornerShape(6.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            supportingText = {
                Text(
                    text = autoTagsDescription,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        )
    }
}

internal fun parseFolderAutoTags(value: String): List<String> = normalizeTags(value.split(','))

private fun FolderType.displayName(): String = when (this) {
    FolderType.DEFAULT -> "Default"
    FolderType.GENERAL -> "General"
}

private fun FolderType.description(): String = when (this) {
    FolderType.DEFAULT -> "새 파일 이름 앞에 번호를 자동으로 붙입니다."
    FolderType.GENERAL -> "파일명을 그대로 사용하고 이름순으로 정렬합니다."
}
