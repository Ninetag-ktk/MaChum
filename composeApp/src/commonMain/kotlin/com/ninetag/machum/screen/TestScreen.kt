package com.ninetag.machum.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.external.getLastModified
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Suppress("NewApi")
@Composable
fun TestScreen(
    onWorkflowManage: () -> Unit = {},
) {
    val fileManager = koinInject<FileManager>()
    var currentDirectory by remember { mutableStateOf<PlatformFile?>(null) }
    var currentProject by remember { mutableStateOf<PlatformFile?>(null) }
    var list by remember { mutableStateOf<List<PlatformFile>>(emptyList()) }
    var currentFile by remember { mutableStateOf<PlatformFile?>(null) }
    var content by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val bookmark by fileManager.bookmarks.collectAsState()
    val workflowList by fileManager.workflowList.collectAsState()
    val workflow by fileManager.workflow.collectAsState()

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        bookmark?.let{
            message = "북마크 불러옴: dir=${it.vaultData != null}, prj=${it.projectData != null}, file=${it.fileData != null}"
            it.vaultData?.let { vault ->
                currentDirectory = vault
            }
            it.projectData?.let { project ->
                currentProject = project
                list = fileManager.listFile(project)
            }
            it.fileData?.let { file ->
                currentFile = file
                content = fileManager.read(file)
            }
        }
    }

    Column (modifier = Modifier
        .safeContentPadding()
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
    ) {
        Text(text = message)
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

        // 북마크 삭제
        OutlinedButton(
            onClick = {
                scope.launch {
                    fileManager.clearPreferencesTest()
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

        // 에디터
        Text("파일 내용:")
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            enabled = currentFile != null,
            placeholder = { Text("파일을 열어주세요") }
        )
        currentFile?.let { file ->
            val lastModifier = file.getLastModified()
            Text(text = "$lastModifier")
        }
        Text(text = "$workflowList")
        workflow?.let{
            Text(text = "$it")
        }
    }
}