package com.ninetag.machum.screen.mainComposition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    fileName: String,
    onCommitClick: () -> Unit,
    onFileListClick: () -> Unit,
    onToggleClick: () -> Unit,
    onRenameFile: (String) -> Unit,
) {
    val parts = fileName.split(". ", limit = 2)
    val numbering = parts.getOrNull(0) ?: ""
    val title = parts.getOrNull(1) ?: fileName

    var isEditing by remember { mutableStateOf(false) }
    var editingText by remember(fileName) { mutableStateOf(title) }
    var hasFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onCommitClick) {
                Icon(
                    imageVector = Icons.Default.Commit,
                    contentDescription = "Commit",
                )
            }
        },
        title = {
            Row(
                /*TODO - 상단을 가득 채우게끔 구성 필요*/
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.weight(0.5f))
                if (isEditing) {
                    Text(
                        color = Color.Transparent,
                        text = "${numbering}. ",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .wrapContentWidth()
                            .clickable { isEditing = true }
                    )
                    BasicTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                onRenameFile("${numbering}. ${editingText}")
                                isEditing = false
                                hasFocused = false
                            }
                        ),
                        modifier = Modifier
                            .wrapContentWidth()
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    hasFocused = true
                                } else if (hasFocused) {
                                    onRenameFile("${numbering}. ${editingText}")
                                    isEditing = false
                                    hasFocused = false
                                }
                            }
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                } else {
                    Text(
                        text = "${numbering}. ${title}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .wrapContentWidth()
                            .clickable { isEditing = true }
                    )
                }
                IconButton(onClick = onFileListClick) {
                    Icon(
                        imageVector = Icons.Default.UnfoldMore,
                        contentDescription = "FileList",
                    )
                }
                Spacer(Modifier.weight(0.5f))
            }
        },
        actions = {
            IconButton(onClick = onToggleClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.FormatListBulleted,
                    contentDescription = "Index",
                )
            }
        }
    )
}