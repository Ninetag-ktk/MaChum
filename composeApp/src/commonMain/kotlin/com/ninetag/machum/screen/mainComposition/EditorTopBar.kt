package com.ninetag.machum.screen.mainComposition

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.markdownName
import com.ninetag.machum.theme.WorkspaceUiMetrics
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    projectFile: ProjectFile?,
    folderName: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onMenuClick: () -> Unit,
    onCommitClick: () -> Unit,
    onFileListClick: () -> Unit,
    onRenameFile: suspend (ProjectFile, String) -> String?,
    navigationMenuContent: @Composable () -> Unit = {},
) {
    val fileName = projectFile?.platformFile?.markdownName()
    var isEditing by remember(projectFile?.key) { mutableStateOf(false) }
    var editingTitle by remember(projectFile?.key) { mutableStateOf(fileName?.title.orEmpty()) }
    var originalTitle by remember(projectFile?.key) { mutableStateOf(fileName?.title.orEmpty()) }
    var editingTarget by remember(projectFile?.key) { mutableStateOf<ProjectFile?>(null) }
    var hasFocused by remember(projectFile?.key) { mutableStateOf(false) }
    var isSubmitting by remember(projectFile?.key) { mutableStateOf(false) }
    var renameError by remember(projectFile?.key) { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    TopAppBar(
        modifier = Modifier.height(WorkspaceUiMetrics.topBarHeight),
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "프로젝트 루트로 돌아가기",
                            modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                        )
                    }
                }
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "파일 탐색기 열기",
                        modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                    )
                }
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.weight(0.5f))
                folderName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (folderName != null && fileName != null) {
                    Text(
                        text = "/",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (fileName != null) {
                    val numberingAlpha by animateFloatAsState(
                        targetValue = if (isEditing) 0f else 1f,
                        animationSpec = tween(durationMillis = 300)
                    )
                    if (fileName.numbering.isNotEmpty()) {
                        Text(
                            text = "${fileName.numbering}.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .wrapContentWidth()
                                .alpha(numberingAlpha)
                                .padding(0.dp)
                        )
                    }
                    val submitRename = {
                        if (!isSubmitting) {
                            val target = editingTarget
                            if (target == null || editingTitle == originalTitle) {
                                renameError = null
                                isEditing = false
                                hasFocused = false
                                editingTarget = null
                            } else {
                                val titleError = projectFileTitleError(editingTitle)
                                if (titleError != null) {
                                    renameError = titleError
                                } else {
                                    val targetName = target.platformFile.markdownName()
                                    val renamed = if (targetName.numbering.isEmpty()) {
                                        editingTitle
                                    } else {
                                        "${targetName.numbering}. $editingTitle"
                                    }
                                    isSubmitting = true
                                    renameError = null
                                    scope.launch {
                                        val error = onRenameFile(target, renamed)
                                        isSubmitting = false
                                        if (error == null) {
                                            isEditing = false
                                            hasFocused = false
                                            editingTarget = null
                                        } else {
                                            renameError = error
                                            isEditing = true
                                            focusRequester.requestFocus()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (isEditing) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        Column {
                            BasicTextField(
                                value = editingTitle,
                                onValueChange = {
                                    editingTitle = it
                                    renameError = null
                                },
                                enabled = !isSubmitting,
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 14.sp,
                                    lineHeight = 14.sp,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submitRename() }),
                                modifier = Modifier
                                    .widthIn(min = 48.dp)
                                    .width(IntrinsicSize.Max)
                                    .focusRequester(focusRequester)
                                    .onKeyEvent { keyEvent ->
                                        if (
                                            !isSubmitting &&
                                            keyEvent.key == Key.Escape &&
                                            keyEvent.type == KeyEventType.KeyDown
                                        ) {
                                            editingTitle = fileName.title
                                            renameError = null
                                            isEditing = false
                                            hasFocused = false
                                            editingTarget = null
                                            true
                                        } else false
                                    }
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            hasFocused = true
                                        } else if (hasFocused) {
                                            submitRename()
                                        }
                                    }
                                    .padding(horizontal = 1.dp)
                            )
                            renameError?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 10.sp,
                                    lineHeight = 11.sp,
                                    maxLines = 2,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = editingTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .wrapContentWidth()
                                .clickable {
                                    renameError = null
                                    originalTitle = fileName.title
                                    editingTarget = projectFile
                                    isEditing = true
                                }
                                .padding(0.dp)
                        )
                    }
                }
                Box {
                    IconButton(onClick = onFileListClick) {
                        Icon(
                            imageVector = Icons.Default.UnfoldMore,
                            contentDescription = "현재 폴더 파일",
                            modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                        )
                    }
                    navigationMenuContent()
                }
                Spacer(Modifier.weight(0.5f))
            }
        },
        actions = {
            IconButton(onClick = onCommitClick) {
                Icon(
                    imageVector = Icons.Default.Commit,
                    contentDescription = "프로젝트 커밋",
                    modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                )
            }
        }
    )
}
