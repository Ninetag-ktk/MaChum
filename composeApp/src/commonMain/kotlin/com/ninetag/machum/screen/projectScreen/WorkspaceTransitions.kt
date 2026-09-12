package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.WorkspaceSetup
import com.ninetag.machum.screen.common.PolicyDialog
import com.ninetag.machum.screen.common.PopupUiMetrics
import com.ninetag.machum.screen.common.DrawerDropdownMenu
import com.ninetag.machum.theme.WorkspaceUiMetrics
import com.ninetag.machum.theme.platformUsesTouchUi
import io.github.vinceglb.filekit.name

internal fun WorkspaceChoice.transitionLabel(): String = when {
    setup == WorkspaceSetup.PROJECT -> "General로 전환"
    hasSuspendedProject -> "프로젝트로 복귀"
    else -> "프로젝트로 전환"
}

/** Drop locations are visible headers/rows, clipped to the scrolling list's viewport. */
internal fun workspaceDropDestination(
    source: WorkspaceSetup,
    point: Offset,
    viewport: Rect,
    targets: Collection<Pair<WorkspaceSetup, Rect>>,
): WorkspaceSetup? {
    if (source == WorkspaceSetup.NEEDS_CONFIRMATION || !viewport.contains(point)) return null
    return targets.firstOrNull { (setup, bounds) ->
        setup != source && setup != WorkspaceSetup.NEEDS_CONFIRMATION && bounds.contains(point)
    }?.first
}

@Composable
internal fun WorkspaceChoiceList(
    choices: List<WorkspaceChoice>,
    blocked: Boolean,
    menuTarget: String?,
    onMenuTargetChange: (String?) -> Unit,
    onOpen: (WorkspaceChoice) -> Unit,
    onRename: (WorkspaceChoice) -> Unit,
    onTransition: (WorkspaceChoice) -> Unit,
    modifier: Modifier = Modifier,
    onTrash: (WorkspaceChoice) -> Unit = {},
) {
    val bounds = remember { mutableStateMapOf<String, Pair<WorkspaceSetup, Rect>>() }
    val listState = rememberLazyListState()
    val edgeSize = with(LocalDensity.current) { 72.dp.toPx() }
    val scrollStep = with(LocalDensity.current) { 12.dp.toPx() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var dragging by remember { mutableStateOf<WorkspaceChoice?>(null) }
    var pressedChoice by remember { mutableStateOf<WorkspaceChoice?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val latestTransition by rememberUpdatedState(onTransition)
    val hover = dragging?.let {
        workspaceDropDestination(it.setup, pointer, viewport, bounds.values)
    }
    LaunchedEffect(blocked) { if (blocked) dragging = null }
    LaunchedEffect(dragging != null, blocked) {
        while (dragging != null && !blocked) {
            withFrameNanos { }
            if (viewport.contains(pointer)) {
                val edge = (viewport.height / 5f).coerceAtMost(edgeSize)
                val delta = when {
                    pointer.y < viewport.top + edge -> -scrollStep
                    pointer.y > viewport.bottom - edge -> scrollStep
                    else -> 0f
                }
                if (delta != 0f) listState.scrollBy(delta)
            }
        }
    }
    // The gesture belongs to the list, so scrolling the source row offscreen does not cancel it.
    val pressModifier = if (blocked) Modifier else Modifier.pointerInput(choices, blocked) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val initialPoint = viewport.topLeft + down.position
            pressedChoice = choices.firstOrNull { candidate ->
                candidate.setup != WorkspaceSetup.NEEDS_CONFIRMATION &&
                    bounds[candidate.directory.toString()]?.second?.contains(initialPoint) == true
            }
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).changes.none { it.pressed }) break
            }
        }
    }
    val dragModifier = if (blocked) Modifier else Modifier.pointerInput(choices, blocked) {
        val start: (Offset) -> Unit = { local ->
            pointer = viewport.topLeft + local
            dragging = pressedChoice
            if (dragging != null) onMenuTargetChange(null)
        }
        val end: () -> Unit = {
            val source = dragging
            if (source != null && workspaceDropDestination(source.setup, pointer, viewport, bounds.values) != null)
                latestTransition(source)
            dragging = null
        }
        if (platformUsesTouchUi) detectDragGesturesAfterLongPress(
            onDragStart = start, onDragEnd = end, onDragCancel = { dragging = null },
        ) { change, _ -> if (dragging != null) { change.consume(); pointer = viewport.topLeft + change.position } }
        else detectDragGestures(
            onDragStart = start, onDragEnd = end, onDragCancel = { dragging = null },
        ) { change, _ -> if (dragging != null) { change.consume(); pointer = viewport.topLeft + change.position } }
    }
    LazyColumn(modifier.onGloballyPositioned { viewport = it.boundsInRoot() }.then(pressModifier).then(dragModifier), state = listState) {
        for (setup in listOf(WorkspaceSetup.PROJECT, WorkspaceSetup.GENERAL, WorkspaceSetup.NEEDS_CONFIRMATION)) {
            val group = choices.filter { it.setup == setup }
            // Both classified groups stay available as drop destinations, including an empty group.
            if (group.isNotEmpty() || setup != WorkspaceSetup.NEEDS_CONFIRMATION) {
                item("heading-$setup") {
                    DisposableEffect(setup) { onDispose { bounds.remove("heading-$setup") } }
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .onGloballyPositioned { bounds["heading-$setup"] = setup to it.boundsInRoot() },
                        color = if (dragging != null && dragging?.setup != setup && setup != WorkspaceSetup.NEEDS_CONFIRMATION)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
                        tonalElevation = if (hover == setup) 4.dp else 0.dp,
                    ) {
                        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
                            Text(setup.label(), style = WorkspaceUiMetrics.labelTextStyle)
                            if (group.isEmpty()) Text("아직 항목이 없습니다", style = WorkspaceUiMetrics.secondaryTextStyle)
                            if (dragging != null && dragging?.setup != setup && setup != WorkspaceSetup.NEEDS_CONFIRMATION)
                                Text("여기에 놓아 전환 확인", style = WorkspaceUiMetrics.secondaryTextStyle)
                        }
                    }
                }
                items(group, key = { it.directory.toString() }) { choice ->
                    val key = choice.directory.toString()
                    DisposableEffect(key) { onDispose { bounds.remove(key) } }
                    Row(
                        Modifier.fillMaxWidth().onGloballyPositioned { bounds[key] = setup to it.boundsInRoot() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = { onOpen(choice) }, enabled = !blocked && dragging == null,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            color = if (dragging?.directory == choice.directory) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(choice.directory.name, style = WorkspaceUiMetrics.bodyTextStyle,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(setup.label(), style = WorkspaceUiMetrics.secondaryTextStyle)
                            }
                        }
                        if (setup != WorkspaceSetup.NEEDS_CONFIRMATION) Box {
                            IconButton(onClick = { onMenuTargetChange(key) }, enabled = !blocked && dragging == null) {
                                Icon(Icons.Default.MoreVert, "${choice.directory.name} 작업 공간 메뉴")
                            }
                            DrawerDropdownMenu(menuTarget == key, { onMenuTargetChange(null) }, PopupUiMetrics.MenuWidth) {
                                DropdownMenuItem(text = { Text("이름 변경") }, enabled = !blocked, onClick = {
                                    onMenuTargetChange(null); onRename(choice)
                                }, modifier = Modifier.heightIn(min = 48.dp))
                                DropdownMenuItem(text = { Text(choice.transitionLabel() + "…") }, enabled = !blocked, onClick = {
                                    onMenuTargetChange(null); onTransition(choice)
                                }, modifier = Modifier.heightIn(min = 48.dp))
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("휴지통으로 이동…", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    enabled = !blocked,
                                    onClick = { onMenuTargetChange(null); onTrash(choice) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceTransitionDialog(
    target: WorkspaceChoice,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PolicyDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = "${target.directory.name} · ${target.transitionLabel()}",
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (busy) "전환 중…" else target.transitionLabel())
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
        },
    ) {
        Text("폴더와 파일, 본문과 파일명에 포함된 번호는 그대로 유지됩니다.")
        Text(when {
            target.setup == WorkspaceSetup.PROJECT ->
                "Plot 구분과 자동 번호·태그 관리, 커밋 기능이 중단됩니다. 프로젝트 설정과 이력은 보관하며 프로젝트로 복귀하면 다시 적용합니다. 폴더 설정을 다시 연결하기 위한 숨김 관리 파일이 추가됩니다."
            target.hasSuspendedProject ->
                "보관한 폴더 유형·Plot·관리 설정과 커밋 이력을 다시 사용합니다. 기존 파일명은 바꾸지 않으며 삭제된 항목은 되살리지 않습니다."
            else -> "기본 프로젝트 폴더와 설정을 준비하고 문서 ID·프로젝트명 태그를 보완합니다. 기존 파일과 사용자 태그는 유지합니다."
        })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
