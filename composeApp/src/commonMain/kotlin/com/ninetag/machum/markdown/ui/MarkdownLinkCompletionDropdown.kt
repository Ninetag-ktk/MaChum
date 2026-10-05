package com.ninetag.machum.markdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ninetag.machum.markdown.state.MarkdownLinkCompletionCandidate

@Composable
internal fun MarkdownLinkCompletionDropdown(
    candidates: List<MarkdownLinkCompletionCandidate>,
    selectedIndex: Int,
    width: Dp,
    yOffsetPx: Int,
    onSelect: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        if (selectedIndex !in listState.layoutInfo.visibleItemsInfo.map { it.index }) {
            listState.scrollToItem(selectedIndex)
        }
    }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, yOffsetPx),
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            modifier = Modifier.width(width),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = COMPLETION_ROW_HEIGHT * 3),
                state = listState,
            ) {
                itemsIndexed(candidates) { index, candidate ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                            )
                            .clickable { onSelect(index) }
                            .heightIn(min = COMPLETION_ROW_HEIGHT)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = candidate.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = candidate.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private val COMPLETION_ROW_HEIGHT = 48.dp
