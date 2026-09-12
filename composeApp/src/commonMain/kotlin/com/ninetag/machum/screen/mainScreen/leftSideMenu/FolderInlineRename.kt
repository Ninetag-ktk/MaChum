package com.ninetag.machum.screen.mainScreen.leftSideMenu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import com.ninetag.machum.external.isValidProjectFolderName
import com.ninetag.machum.screen.common.WorkspaceBackHandler
import com.ninetag.machum.theme.WorkspaceUiMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FolderInlineRename(name: String, onCancel: () -> Unit, onRename: suspend (String) -> Boolean) {
    var value by remember(name) { mutableStateOf(name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val submit = {
        if (!busy) {
            when {
                value == name -> onCancel()
                value != value.trim() || !isValidProjectFolderName(value) -> error = "올바른 폴더 이름을 입력해 주세요."
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            if (onRename(value)) onCancel()
                            else error = "이름을 변경하지 못했습니다. 중복 이름과 접근 권한을 확인해 주세요."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "이름을 변경하지 못했습니다." }
                        finally { busy = false }
                    }
                }
            }
        }
    }
    HierarchyInlineNameEditor(value, { value = it; error = null }, "폴더 이름", busy, error, onCancel, { submit() })
}

/** Replaces only the name and trailing actions; the owning row retains its icon and geometry. */
@Composable
internal fun HierarchyInlineNameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    fieldLabel: String,
    busy: Boolean,
    errorMessage: String?,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = WorkspaceUiMetrics.secondaryTextStyle,
    backEnabled: Boolean = true,
    inputReadOnly: Boolean = busy,
    submitLabel: String = "이름 변경 저장",
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    WorkspaceBackHandler(backEnabled) { if (!busy) onCancel() }
    val density = LocalDensity.current
    val position = remember(density) {
        HierarchyNameErrorPosition(with(density) { 8.dp.roundToPx() }, with(density) { 4.dp.roundToPx() })
    }
    var errorDismissed by remember(errorMessage) { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    SideEffect {
        if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    Box(modifier.fillMaxWidth().testTag("hierarchy-name-editor")) {
        Row(Modifier.fillMaxWidth().heightIn(min = WorkspaceUiMetrics.hierarchyFolderRowHeight)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.Escape -> { if (!busy) onCancel(); true }
                    else -> false
                }
            }, verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = fieldValue, onValueChange = { fieldValue = it; if (value != it.text) onValueChange(it.text) },
                singleLine = true, readOnly = inputReadOnly,
                textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("hierarchy-name-input")
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter) && fieldValue.composition == null) {
                            if (!busy) onSubmit()
                            true
                        } else false
                    }.semantics {
                    contentDescription = fieldLabel
                    if (busy) stateDescription = "변경 중"
                    errorMessage?.let { error(it) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy && fieldValue.composition == null) onSubmit() }),
            )
            HierarchyIconButton(imageVector = Icons.Default.Close, contentDescription = "이름 변경 취소", onClick = onCancel, enabled = !busy,
                modifier = Modifier.testTag("hierarchy-name-cancel"))
            HierarchyIconButton(imageVector = Icons.Default.Check, contentDescription = if (busy) "이름 변경 중" else submitLabel, onClick = onSubmit, enabled = !busy,
                modifier = Modifier.testTag("hierarchy-name-submit"))
        }
        if (errorMessage != null && !errorDismissed) {
            Popup(position, onDismissRequest = { errorDismissed = true },
                properties = PopupProperties(focusable = false, dismissOnBackPress = false)) {
                Surface(Modifier.widthIn(max = 320.dp).testTag("hierarchy-name-error").semantics { liveRegion = LiveRegionMode.Assertive },
                    shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer, shadowElevation = 4.dp) {
                    Text(errorMessage, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = WorkspaceUiMetrics.secondaryTextStyle)
                }
            }
        }
    }
}

private class HierarchyNameErrorPosition(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom + gap
        val y = (if (below + popupContentSize.height <= windowSize.height - margin) below else anchorBounds.top - gap - popupContentSize.height)
            .coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
        return IntOffset(x, y)
    }
}
