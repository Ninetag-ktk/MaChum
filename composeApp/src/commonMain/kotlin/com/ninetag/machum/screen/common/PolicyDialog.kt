package com.ninetag.machum.screen.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ninetag.machum.theme.platformUsesTouchUi

/** Layout only: callers keep validation, submission, busy state and dismissal decisions. */
@Composable
internal fun PolicyDialog(
    onDismissRequest: () -> Unit,
    title: String,
    width: Dp = PopupUiMetrics.DialogWidth,
    dismissOnClickOutside: Boolean = false,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    leadingButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // The dialog supplies finite window constraints. Wrap content rather than forcing its height.
        BoxWithConstraints(Modifier.safeDrawingPadding().imePadding()) {
            val margin = if (maxWidth >= 600.dp) 24.dp else 16.dp
            val availableWidth = (maxWidth - margin * 2).coerceAtLeast(0.dp)
            val availableHeight = (maxHeight - margin * 2).coerceAtLeast(0.dp)
            Box(
                Modifier.matchParentSize().pointerInput(dismissOnClickOutside, onDismissRequest) {
                    detectTapGestures { if (dismissOnClickOutside) onDismissRequest() }
                },
            )
            Surface(
                modifier = Modifier
                    .padding(margin)
                    .width(minOf(width, availableWidth))
                    .heightIn(max = availableHeight)
                    .semantics { paneTitle = title },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                val typography = MaterialTheme.typography
                MaterialTheme(typography = if (platformUsesTouchUi) typography.copy(
                    bodyMedium = typography.bodyLarge,
                    bodySmall = typography.bodyMedium,
                    labelLarge = typography.labelLarge.copy(fontSize = typography.bodyLarge.fontSize, lineHeight = typography.bodyLarge.lineHeight),
                ) else typography) {
                    Column(Modifier.padding(PopupUiMetrics.ContentPadding)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.height(PopupUiMetrics.SectionSpacing))
                        val scrollState = rememberScrollState()
                        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .verticalScroll(scrollState)
                                        .padding(end = PopupUiMetrics.ItemSpacing),
                                    verticalArrangement = Arrangement.spacedBy(PopupUiMetrics.ItemSpacing),
                                    content = content,
                                )
                            }
                            PolicyVerticalScrollbar(
                                state = scrollState,
                                modifier = Modifier.align(Alignment.CenterEnd).matchParentSize(),
                            )
                        }
                        Spacer(Modifier.height(PopupUiMetrics.SectionSpacing))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(PopupUiMetrics.ItemSpacing))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (leadingButton != null && maxWidth < 360.dp) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PopupUiMetrics.ItemSpacing)) {
                                    leadingButton()
                                    DialogTrailingActions(dismissButton, confirmButton)
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(PopupUiMetrics.ItemSpacing),
                                ) {
                                    leadingButton?.invoke()
                                    Box(Modifier.weight(1f)) {
                                        DialogTrailingActions(dismissButton, confirmButton)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTrailingActions(
    dismissButton: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PopupUiMetrics.ItemSpacing, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(PopupUiMetrics.ItemSpacing),
    ) {
        dismissButton()
        confirmButton()
    }
}
