package com.ninetag.machum.screen.selectionScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.ninetag.machum.external.FileManager
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class DesktopVaultPickerUI : VaultPickerUI {
    @Composable
    override fun Show(reset: () -> Unit) {
        Window(
            onCloseRequest = { reset() },
            state = rememberWindowState(
                width = 420.dp,
                height = 560.dp,
                position = WindowPosition(Alignment.Center),
            ),
            resizable = false,
        ) {
            DesktopVaultPickerContainer(reset = reset)
        }
    }

}

@Composable
actual fun rememberVaultPickerUI(): VaultPickerUI {
    return remember { DesktopVaultPickerUI() }
}

// Desktop App Main에서도 호출이 가능하게끔 컨테이너로 구현
@Composable
fun DesktopVaultPickerContainer(
    reset: () -> Unit,
) {
    var isCreating by remember { mutableStateOf(false) }

    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = "맞춤",
                fontSize = 48.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "글쓰기 앱",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.height(48.dp))
            if (!isCreating) {
                Menu(
                    reset = reset,
                    onCreateMenuClicked = {isCreating = true}
                )
            } else {
                Create(
                    reset = reset,
                    onBackClick = {isCreating = false}
                )
            }
        }
    }
}

@Composable
private fun Menu(
    reset: () -> Unit,
    onCreateMenuClicked: () -> Unit,
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.5f),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp),
//                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "새 보관함 생성",
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onCreateMenuClicked,
                    ) {
                        Text("생성")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primaryContainer)
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "보관함 열기",
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { scope.launch { fileManager.pickVault()?.let { reset() } } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    ) {
                        Text("열기")
                    }
                }
            }
        }
    }
}

@Composable
private fun Create(
    reset: () -> Unit,
    onBackClick: () -> Unit
) {
    val fileManager = koinInject<FileManager>()
    val scope = rememberCoroutineScope()

    var parentDirectory by remember { mutableStateOf<PlatformFile?>(null) }
    var vaultName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            TextButton(
                onClick = onBackClick,
                shape = MaterialTheme.shapes.small,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "BackIcon",
                )
                Text("이전으로 돌아가기")
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.5f),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp, 0.dp),
//                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "보관함 이름",
                        modifier = Modifier.weight(1f),
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    BasicTextField(
                        value = vaultName,
                        onValueChange = { vaultName = it },
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp, minWidth = 120.dp).width(120.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 14.sp,
                            lineHeight = 14.sp,
                        ),
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(OutlinedTextFieldDefaults.colors().cursorColor),
                        decorationBox = { innerTextField ->
                            OutlinedTextFieldDefaults.DecorationBox(
                                value = vaultName,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = interactionSource,
                                placeholder = {Text(text = "보관함 이름", fontSize = 14.sp, lineHeight = 14.sp)},
                                contentPadding = PaddingValues(12.dp, 8.dp),
                                container = {
                                    OutlinedTextFieldDefaults.Container(
                                        enabled = true,
                                        isError = false,
                                        interactionSource = interactionSource,
                                        colors = OutlinedTextFieldDefaults.colors(),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                }
                            )
                        }
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.primaryContainer)
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "위치",
                        )
                        Text(
                            text = parentDirectory?.path ?: "새 보관함의 위치를 정합니다",
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                    Button(
                        onClick = { scope.launch { FileKit.openDirectoryPicker()?.let{parentDirectory = it} } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    ) {
                        Text("탐색")
                    }
                }
            }
        }
        Button(
            onClick = {
                scope.launch {
                    fileManager.setVault(parentDirectory!!, vaultName)
                    reset()
                }
            },
            modifier = Modifier.wrapContentWidth(),
            enabled = parentDirectory != null && vaultName.isNotBlank(),
        ) {
            Text("Create")
        }
    }
}