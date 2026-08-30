package com.ninetag.machum.screen.mainComposition

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.ninetag.machum.external.MarkdownName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    fileName: MarkdownName?,
    emptyTitle: String = "빈 폴더",
    folderName: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onMenuClick: () -> Unit,
    onCommitClick: () -> Unit,
    onFileListClick: () -> Unit,
    onRenameFile: (String) -> Unit,
    navigationMenuContent: @Composable () -> Unit = {},
) {
    var isEditing by remember { mutableStateOf(false) }
    var editingTitle by remember(fileName) { mutableStateOf(fileName?.title.orEmpty()) }
    var hasFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    TopAppBar(
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "프로젝트 루트로 돌아가기",
                        )
                    }
                }
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "파일 탐색기 열기",
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
                    Text(
                        text = "/",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (fileName == null) {
                    Text(
                        text = emptyTitle,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
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
                        val renamed = if (fileName.numbering.isEmpty()) {
                            editingTitle
                        } else {
                            "${fileName.numbering}. $editingTitle"
                        }
                        onRenameFile(renamed)
                        isEditing = false
                        hasFocused = false
                    }
                    if (isEditing) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                        BasicTextField(
                            value = editingTitle,
                            onValueChange = { editingTitle = it },
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                                lineHeight = 14.sp,
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitRename() }),
                            modifier = Modifier
                                .wrapContentWidth()
                                .focusRequester(focusRequester)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown) {
                                        editingTitle = fileName.title
                                        isEditing = false
                                        hasFocused = false
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
                                .padding(0.dp)
                        )
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
                                .clickable { isEditing = true }
                                .padding(0.dp)
                        )
                    }
                }
                Box {
                    IconButton(onClick = onFileListClick) {
                        Icon(
                            imageVector = Icons.Default.UnfoldMore,
                            contentDescription = "FileList",
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
                    contentDescription = "Commit",
                )
            }
        }
    )
}
