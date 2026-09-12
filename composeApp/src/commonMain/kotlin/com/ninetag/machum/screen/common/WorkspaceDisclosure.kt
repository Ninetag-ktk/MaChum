package com.ninetag.machum.screen.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.ninetag.machum.theme.WorkspaceMotion

/** A single-content disclosure that preserves drafts while its measured height animates. */
@Composable
internal fun WorkspaceDisclosure(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var contentHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded && contentHasFocus) focusManager.clearFocus(force = true)
    }
    val collapsedBlocker = if (expanded) Modifier else Modifier
        .focusProperties {
            canFocus = false
            onEnter = { cancelFocusChange() }
        }
        .onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
        .clearAndSetSemantics { }

    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = WorkspaceMotion.contentExpandSpec(),
            expandFrom = Alignment.Top,
            clip = true,
        ) + fadeIn(WorkspaceMotion.contentEnterSpec()),
        exit = shrinkVertically(
            animationSpec = WorkspaceMotion.contentCollapseSpec(),
            shrinkTowards = Alignment.Top,
            clip = true,
        ) + fadeOut(WorkspaceMotion.contentCollapseSpec()),
    ) {
        Box(
            Modifier.fillMaxWidth().then(collapsedBlocker)
                .onFocusEvent { contentHasFocus = it.hasFocus }
                .focusGroup(),
        ) {
            content()
        }
    }
}
