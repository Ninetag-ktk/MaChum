package com.ninetag.machum.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow.Companion.StartEllipsis
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.createFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Suppress("NewApi")
@Composable
fun TestScreen() {
    val fileManager = koinInject<FileManager>()
    var currentDirectory by remember { mutableStateOf<PlatformFile?>(null) }
    var currentProject by remember { mutableStateOf<PlatformFile?>(null) }
    var currentFile by remember { mutableStateOf<PlatformFile?>(null) }
    var content by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<PlatformFile>?>(null) }
    val bookmarks by fileManager.bookmarks.collectAsState()

    var parentDirectory by remember {mutableStateOf<PlatformFile?>(null)}
    var vaultName by remember {mutableStateOf<String>("")}

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val bookmarks = fileManager.getPreferences()
        message = "북마크 불러옴: dir=${bookmarks.vaultData != null}, prj=${bookmarks.projectData != null}, file=${bookmarks.fileData != null}"
        bookmarks.vaultData?.let {
            currentDirectory = it
            list = fileManager.listProject(currentDirectory!!)
        }
        bookmarks.projectData?.let {
            currentProject = it
        }
        bookmarks.fileData?.let {
            currentFile = it
            content = fileManager.read(currentFile!!)
        }
    }

    Column (modifier = Modifier.fillMaxSize()) {
        Text(text = message)
        HorizontalDivider()
        Text("현재 디렉토리: ${currentDirectory?.name ?: "없음"}")

        // 폴더 선택 버튼
        Button(
            onClick = {
                scope.launch {
                    val directory = fileManager.pickVault()
                    if (directory != null) {
                        currentDirectory = directory
                        message = "폴더 선택됨: ${directory.name}"
                    } else {
                        message = "폴더 선택 취소"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("폴더 선택")
        }

        HorizontalDivider()

        // 현재 파일
        Text("현재 파일: ${currentFile?.name ?: "없음"}")

        // 파일 선택 버튼
        Button(
            onClick = {
                scope.launch {
                    val file = fileManager.pickFileTest()
                    if (file != null) {
                        currentFile = file
                        content = fileManager.read(file)
                        message = "파일 열림: ${file.name} (${content.length}자)"
                    } else {
                        message = "파일 선택 취소"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("파일 열기")
        }

        // 파일 저장 버튼
        Button(
            onClick = {
                scope.launch {
                    currentFile?.let { file ->
                        fileManager.write(file, content)
                        message = "파일 저장됨: ${file.name}"
                    } ?: run {
                        message = "열린 파일이 없습니다"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentFile != null
        ) {
            Text("파일 저장")
        }

        // 파일 닫기 버튼
        Button(
            onClick = {
                scope.launch {
                    currentFile = null
                    content = ""
                    fileManager.setPreferences(
                        fileManager.getPreferences().copy(fileData = null)
                    )
                    message = "파일 닫힘"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentFile != null
        ) {
            Text("파일 닫기")
        }

        HorizontalDivider()

        // 북마크 정보
        Button(
            onClick = {
                scope.launch {
                    val bookmarks = fileManager.getPreferences()
                    message = "저장된 북마크: dir=${bookmarks.vaultData != null}, file=${bookmarks.fileData != null}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("북마크 확인")
        }

        // 북마크 전체 삭제
        OutlinedButton(
            onClick = {
                scope.launch {
                    fileManager.clearPreferences()
                    currentDirectory = null
                    currentFile = null
                    content = ""
                    message = "모든 북마크 삭제됨"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "북마크 초기화")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = vaultName,
                onValueChange = { vaultName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Vault Name") },
                placeholder = { Text("Enter vaultName") },
                trailingIcon = {
                    IconButton(
                        onClick = { vaultName = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = "CancelValue",
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable {
                        scope.launch {
                            scope.launch {
                                FileKit.openDirectoryPicker()?.let{parentDirectory = it}
                            }
                        }
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp, 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = parentDirectory?.path?.let{ URLDecoder.decode(it, StandardCharsets.UTF_8)}?:"Tap for Vault Directory",
                        color = parentDirectory?.let{ MaterialTheme.colorScheme.onBackground }?:run{ MaterialTheme.colorScheme.onSurfaceVariant },
                        textAlign = TextAlign.Center,
                        overflow = StartEllipsis,
                        maxLines = 1,
                    )
                }
            }
            Button(
                onClick = {
                    scope.launch {
                        fileManager.createFile(parentDirectory!!, vaultName)
                    }
                },
                modifier = Modifier.wrapContentWidth(),
                enabled = parentDirectory != null && vaultName.isNotBlank(),
            ) {
                Text("Create")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // 에디터
        Text("파일 위치: ${bookmarks?.fileData?.path}")
        Text("파일 내용:")
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .weight(1f),
            enabled = currentFile != null,
            placeholder = { Text("파일을 열어주세요") }
        )
        Spacer(modifier = Modifier.height(16.dp))
        list?.forEach { file ->
            Text(text = file.name)
        }
    }
}