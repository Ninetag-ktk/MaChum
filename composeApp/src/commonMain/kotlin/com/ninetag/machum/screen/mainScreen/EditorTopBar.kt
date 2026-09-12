package com.ninetag.machum.screen.mainScreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.rememberTooltipState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.ninetag.machum.theme.WorkspaceMotion
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.ninetag.machum.external.ProjectFile
import com.ninetag.machum.external.markdownName
import com.ninetag.machum.theme.WorkspaceUiMetrics
import kotlinx.coroutines.launch

@Composable
fun EditorTopBar(
    projectFile: ProjectFile?,
    folderName: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onMenuClick: () -> Unit,
    onCommitClick: (() -> Unit)?,
    onRenameFile: suspend (ProjectFile, String) -> String?,
    documentInfoExpanded: Boolean = false,
    onDocumentInfoToggle: () -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    minimumContentHeight: Dp = WorkspaceUiMetrics.topBarHeight,
) {
    val fileName = projectFile?.platformFile?.markdownName()
    var isEditing by remember(projectFile?.key) { mutableStateOf(false) }
    var editingTitle by remember(projectFile?.key) { mutableStateOf(fileName?.title.orEmpty()) }
    var originalTitle by remember(projectFile?.key) { mutableStateOf(fileName?.title.orEmpty()) }
    var editingTarget by remember(projectFile?.key) { mutableStateOf<ProjectFile?>(null) }
    var hasFocused by remember(projectFile?.key) { mutableStateOf(false) }
    var isSubmitting by remember(projectFile?.key) { mutableStateOf(false) }
    var renameError by remember(projectFile?.key) { mutableStateOf<String?>(null) }
    val animatedContextAlpha by key(projectFile?.key) {
        animateFloatAsState(if (isEditing) 0f else 1f, WorkspaceMotion.contentEnterSpec(), label = "titleContext")
    }
    // Opacity and layout consume the same composition snapshot of the animation.
    val contextAlpha = animatedContextAlpha
    // Collapse only horizontal context space; the title control and bar keep their heights.
    val contextVisibility = Modifier.clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout((placeable.width * contextAlpha).roundToInt(), placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }.alpha(contextAlpha)
        .then(if (isEditing) Modifier.clearAndSetSemantics {} else Modifier)
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val titleHeight = with(density) { WorkspaceUiMetrics.titleLineHeight.toDp() }
    val contentHeight = maxOf(minimumContentHeight, titleHeight + 16.dp)
    val titleControlHeight = maxOf(48.dp, titleHeight)
    val infoButtonSize = 48.dp
    val infoButtonGap = 4.dp
    val renameErrorPositionProvider = remember(density) {
        RenameErrorPopupPositionProvider(
            margin = with(density) { 8.dp.roundToPx() },
            gap = with(density) { 4.dp.roundToPx() },
        )
    }

    CenterAlignedTopAppBar(
        expandedHeight = contentHeight,
        windowInsets = windowInsets,
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "파일 탐색기 열기",
                        modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                    )
                }
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                            contentDescription = "프로젝트 루트로 돌아가기",
                            modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                        )
                    }
                }
            }
        },
        title = {
            BoxWithConstraints {
            val availableTitleWidth = maxWidth
            val titleStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal,
                fontSize = WorkspaceUiMetrics.titleFontSize,
                lineHeight = WorkspaceUiMetrics.titleLineHeight,
            )
            val textMeasurer = rememberTextMeasurer()
            val measuredTitle = textMeasurer.measure(editingTitle, titleStyle, softWrap = false, maxLines = 1)
            val entryWidth = if (isEditing) textMeasurer.measure(originalTitle, titleStyle, softWrap = false, maxLines = 1).size.width else 0
            val titleWidth = with(density) { maxOf(measuredTitle.size.width, entryWidth).toDp() } + 2.dp
            val numberWidthLimit = availableTitleWidth * 0.2f
            val numberWidth = if (fileName?.numbering?.isNotEmpty() == true) {
                (with(density) { textMeasurer.measure("${fileName.numbering}.", titleStyle).size.width.toDp() } + 4.dp)
                    .coerceAtMost(numberWidthLimit)
            } else 0.dp
            val separatorWidth = with(density) { textMeasurer.measure("/", LocalTextStyle.current).size.width.toDp() } + 4.dp
            // Reserve the natural title first, then let the path use all remaining space.
            // Its budget uses the original gap, not constraints that change during the fade.
            val pathWidthLimit = if (fileName == null) availableTitleWidth else {
                (availableTitleWidth - titleWidth - infoButtonSize - infoButtonGap - numberWidth - separatorWidth)
                    .coerceAtLeast(0.dp)
            }
            val showPath = folderName != null && pathWidthLimit > 0.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                folderName?.takeIf { showPath }?.let { name ->
                    Text(
                        text = name,
                        modifier = Modifier.widthIn(max = pathWidthLimit)
                            .testTag("title-path-context").then(contextVisibility).padding(end = 4.dp),
                        style = WorkspaceUiMetrics.bodyTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showPath && fileName != null) {
                    Text(
                        text = "/",
                        modifier = contextVisibility.padding(end = 4.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (fileName != null) {
                    if (fileName.numbering.isNotEmpty()) {
                        Text(
                            text = "${fileName.numbering}.",
                            fontSize = WorkspaceUiMetrics.titleFontSize,
                            fontWeight = FontWeight.Normal,
                            lineHeight = WorkspaceUiMetrics.titleLineHeight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                // The prefix may be arbitrary text before ". ", not only a short number.
                                .widthIn(max = numberWidthLimit)
                                .wrapContentWidth()
                                .testTag("title-number-context").then(contextVisibility).padding(end = 4.dp)
                                .heightIn(min = 48.dp)
                                .wrapContentHeight(Alignment.CenterVertically)
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
                        Column(Modifier.weight(1f, fill = false)) {
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
                                    fontSize = WorkspaceUiMetrics.titleFontSize,
                                    lineHeight = WorkspaceUiMetrics.titleLineHeight,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submitRename() }),
                                modifier = Modifier
                                    .height(titleControlHeight)
                                    .width(titleWidth)
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
                                    },
                                decorationBox = { innerTextField ->
                                    Box(Modifier.height(titleControlHeight).padding(horizontal = 1.dp), contentAlignment = Alignment.CenterStart) {
                                        innerTextField()
                                    }
                                },
                            )
                            renameError?.let { error ->
                                Popup(
                                    popupPositionProvider = renameErrorPositionProvider,
                                    onDismissRequest = { renameError = null },
                                    properties = PopupProperties(
                                        focusable = false,
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = true,
                                    ),
                                ) {
                                    Surface(
                                        modifier = Modifier.widthIn(max = 320.dp).semantics {
                                            liveRegion = LiveRegionMode.Assertive
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        shadowElevation = 4.dp,
                                    ) {
                                        Text(
                                            text = error,
                                            style = WorkspaceUiMetrics.secondaryTextStyle,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.weight(1f, fill = false)
                                .height(titleControlHeight)
                                .clickable {
                                    renameError = null
                                    originalTitle = fileName.title
                                    editingTarget = projectFile
                                    isEditing = true
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = editingTitle,
                                modifier = Modifier.padding(horizontal = 1.dp),
                                fontSize = WorkspaceUiMetrics.titleFontSize,
                                fontWeight = FontWeight.Normal,
                                lineHeight = WorkspaceUiMetrics.titleLineHeight,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (projectFile != null && (!isEditing || contextAlpha > 0f)) Row(
                    Modifier.testTag("title-info-context").then(contextVisibility),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Spacer(Modifier.width(infoButtonGap))
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(if (documentInfoExpanded) "문서 정보 접기" else "문서 정보 펼치기") } },
                    state = rememberTooltipState(),
                ) {
                    IconToggleButton(
                        checked = documentInfoExpanded,
                        onCheckedChange = { if (!isEditing) onDocumentInfoToggle() },
                        enabled = !isEditing,
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            disabledContentColor = if (documentInfoExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.size(infoButtonSize).semantics {
                            stateDescription = if (documentInfoExpanded) "펼쳐짐" else "접힘"
                        },
                    ) {
                        Icon(
                            imageVector = if (documentInfoExpanded) Icons.AutoMirrored.Filled.Article else Icons.AutoMirrored.Outlined.Article,
                            contentDescription = if (documentInfoExpanded) "문서 정보 접기" else "문서 정보 펼치기",
                            modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                        )
                    }
                }
                }
            }
            }
        },
        actions = {
            if (onCommitClick != null) IconButton(onClick = onCommitClick) {
                Icon(
                    imageVector = Icons.Default.Commit,
                    contentDescription = "프로젝트 커밋",
                    modifier = Modifier.size(WorkspaceUiMetrics.iconSize),
                )
            }
        }
    )
}

private class RenameErrorPopupPositionProvider(
    private val margin: Int,
    private val gap: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = preferredX.coerceIn(margin, maxX)
        val preferredY = anchorBounds.bottom + gap
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        return IntOffset(x, preferredY.coerceAtMost(maxY))
    }
}
