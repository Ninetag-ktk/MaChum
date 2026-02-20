package com.ninetag.machum.screen.workflowSceen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ninetag.machum.screen.common.paddingDefaultTop

@Composable
fun WorkflowEditHeader(
    title: String,
    onTitleChange: (String) -> Unit,
    isMenuExpended: Boolean,
    onMenuToggle: () -> Unit,
    onDescriptionEdit: () -> Unit,
    onDelete: () -> Unit,
    onBackClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().zIndex(90f),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp // 배경색 변화 없음
    ) {
        Row(
           modifier = Modifier
               .paddingDefaultTop()
               .fillMaxWidth()
               .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(96.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
            Box(
                modifier = Modifier.width(96.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                AnimatedContent(
                    targetState = isMenuExpended,
                    transitionSpec = {
                        fadeIn() + expandHorizontally(expandFrom = Alignment.End) togetherWith
                        fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                    }
                ) { expanded ->
                    if (expanded) {
                        Row {
                            IconButton(onClick = onDescriptionEdit) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = "DescriptionToggle",
                                )
                            }
                            IconButton(onClick = onDelete) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = onMenuToggle) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "MenuToggle",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkflowDescriptionEditor(
    description: String,
    onDescriptionChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                )
            )
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                )
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(
                    bottomStart = 8.dp,
                    bottomEnd = 8.dp
                )
            )
            .padding(8.dp),
    ) {
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Workflow에 대한 설명을 입력하세요") },
            singleLine = true,
        )
    }
}