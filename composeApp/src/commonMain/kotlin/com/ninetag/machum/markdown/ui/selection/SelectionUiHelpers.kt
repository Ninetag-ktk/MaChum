package com.ninetag.machum.markdown.ui.selection

import com.ninetag.machum.markdown.state.DocumentSelection
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.NormalizedSelection
import com.ninetag.machum.markdown.state.SelectionEndpoint
import com.ninetag.machum.markdown.state.extractMarkdown
import com.ninetag.machum.markdown.state.normalize

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * Cross-block selection 의 UI 측 헬퍼들.
 *
 * 모델 / 비즈니스 로직은 `state/DocumentSelection.kt` 참조.
 * 본 파일은 Compose UI 통합 (Modifier 확장, 시각화 검사, 유틸) 만 담당.
 *
 * 상세 설계: `markdown/SELECTION.md`.
 */

// ── 1. 시각화 ──

/**
 * 주어진 블록 인덱스가 정규화된 selection 범위에 포함되는지 검사.
 *
 * Phase 1: normalized selection 의 start/end path 가 모두 현재 컨테이너 path 와 일치해야 매칭.
 * 재귀 Callout body 안 selection 은 Step B 의 path 전파 완료 후 자연스럽게 동작.
 *
 * @param blockIndex 검사 대상 블록의 현재 컨테이너 안 인덱스
 * @param blocksInContainer 현재 컨테이너의 블록 리스트
 * @param containerPath 현재 컨테이너의 path (최상위는 empty)
 * @param normalizedSelection 정규화된 selection (없으면 null)
 */
fun isBlockInSelection(
    blockIndex: Int,
    blocksInContainer: List<EditorBlock>,
    containerPath: List<String>,
    normalizedSelection: NormalizedSelection?,
): Boolean {
    val norm = normalizedSelection ?: return false
    if (norm.start.containerPath != containerPath) return false
    if (norm.end.containerPath != containerPath) return false
    val startIdx = blocksInContainer.indexOfFirst { it.id == norm.start.blockId }
    val endIdx = blocksInContainer.indexOfFirst { it.id == norm.end.blockId }
    if (startIdx < 0 || endIdx < 0) return false
    return blockIndex in startIdx..endIdx
}

// ── 2. 단축키 핸들러 ──

/**
 * 최상단 wrapper 에 부착하는 cross-block selection 단축키 Modifier.
 *
 * 처리 키:
 * - `Ctrl/Cmd + A`: 최상위 블록 전체 selection (Multi)
 * - `Ctrl/Cmd + C`: selection 범위의 markdown 추출 → clipboard write. Multi 일 때만 가로채고,
 *   단일 블록 selection (None) 일 때는 false 반환 → BasicTextField 의 native Ctrl+C 동작
 * - `Esc`: Multi 상태일 때 None 으로 리셋
 * - 방향키 / Home / End / PageUp / PageDown: Multi 상태일 때 None 으로 자동 해제 (BasicTextField 의
 *   native cursor 이동 동작은 false 반환으로 그대로 진행)
 *
 * @param rootBlocks 최상위 블록 리스트. Ctrl+A 의 first/last 선택 + Ctrl+C 의 extractMarkdown 대상
 * @param documentSelection 호이스팅된 selection state. 본 핸들러가 직접 갱신
 */
@Composable
fun Modifier.documentSelectionShortcuts(
    rootBlocks: List<EditorBlock>,
    documentSelection: MutableState<DocumentSelection>,
): Modifier {
    val clipboard = LocalClipboardManager.current
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrlOrCmd = event.isCtrlPressed || event.isMetaPressed
        when {
            ctrlOrCmd && event.key == Key.A -> {
                val firstBlock = rootBlocks.firstOrNull() ?: return@onPreviewKeyEvent false
                val lastBlock = rootBlocks.lastOrNull() ?: return@onPreviewKeyEvent false
                documentSelection.value = DocumentSelection.Multi(
                    anchor = SelectionEndpoint(
                        containerPath = emptyList(),
                        blockId = firstBlock.id,
                        offset = SelectionEndpoint.ATOMIC_START,
                    ),
                    focus = SelectionEndpoint(
                        containerPath = emptyList(),
                        blockId = lastBlock.id,
                        offset = endOffsetFor(lastBlock),
                    ),
                )
                true
            }
            ctrlOrCmd && event.key == Key.C -> {
                val sel = documentSelection.value
                if (sel is DocumentSelection.Multi) {
                    val markdown = extractMarkdown(rootBlocks, sel)
                    if (markdown.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(markdown))
                    }
                    true
                } else false
            }
            event.key == Key.Escape -> {
                if (documentSelection.value is DocumentSelection.Multi) {
                    documentSelection.value = DocumentSelection.None
                    true
                } else false
            }
            event.key == Key.DirectionUp ||
            event.key == Key.DirectionDown ||
            event.key == Key.DirectionLeft ||
            event.key == Key.DirectionRight ||
            event.key == Key.MoveHome ||
            event.key == Key.MoveEnd ||
            event.key == Key.PageUp ||
            event.key == Key.PageDown -> {
                if (documentSelection.value is DocumentSelection.Multi) {
                    documentSelection.value = DocumentSelection.None
                }
                false
            }
            else -> false
        }
    }
}

// ── 3. Shift+↑/↓ selection 확장 ──

/**
 * 현재 블록의 첫 위치에서 위쪽으로 selection 확장 (Shift+↑ 트리거).
 *
 * 정책:
 * - documentSelection 이 None 이면 anchor = 현재 블록의 끝, focus = 이전 블록의 시작 으로 새 Multi 시작
 * - 이미 Multi 면 focus 만 이전 블록의 시작으로 갱신 (anchor 유지)
 * - 이전 블록 없으면 (현재가 첫 블록): 컨테이너 외부로 확장하려는 의미. 외부 컨테이너의 책임이라
 *   본 헬퍼는 아무 동작 안 함 (호출자 = 외부 BlockNavigation 에 위임)
 */
fun extendSelectionToPrevious(
    currentBlock: EditorBlock,
    currentIndex: Int,
    blocksInContainer: List<EditorBlock>,
    containerPath: List<String>,
    documentSelection: MutableState<DocumentSelection>?,
) {
    if (documentSelection == null) return
    if (currentIndex <= 0) return  // 컨테이너 외부 확장은 호출자 책임
    val previousBlock = blocksInContainer[currentIndex - 1]
    val existing = documentSelection.value as? DocumentSelection.Multi
    val newAnchor = existing?.anchor ?: endEndpointOf(currentBlock, containerPath)
    val newFocus = startEndpointOf(previousBlock, containerPath)
    documentSelection.value = DocumentSelection.Multi(newAnchor, newFocus)
}

/** 현재 블록의 끝 위치에서 아래쪽으로 selection 확장 (Shift+↓ 트리거). [extendSelectionToPrevious] 와 대칭. */
fun extendSelectionToNext(
    currentBlock: EditorBlock,
    currentIndex: Int,
    blocksInContainer: List<EditorBlock>,
    containerPath: List<String>,
    documentSelection: MutableState<DocumentSelection>?,
) {
    if (documentSelection == null) return
    if (currentIndex >= blocksInContainer.lastIndex) return
    val nextBlock = blocksInContainer[currentIndex + 1]
    val existing = documentSelection.value as? DocumentSelection.Multi
    val newAnchor = existing?.anchor ?: startEndpointOf(currentBlock, containerPath)
    val newFocus = endEndpointOf(nextBlock, containerPath)
    documentSelection.value = DocumentSelection.Multi(newAnchor, newFocus)
}

/**
 * 현재 블록 (Callout) 자체만 atomic selection 으로 설정. "박스 탈출" 정책 (SELECTION.md 2.4 옵션 C).
 *
 * anchor 와 focus 가 같은 블록의 atomic start/end 를 가리키므로 normalize 결과는 이 블록 한 개의 범위.
 * 외부 다른 블록은 selection 에 포함되지 않음.
 */
fun selectBlockAsAtomic(
    block: EditorBlock,
    containerPath: List<String>,
    documentSelection: MutableState<DocumentSelection>?,
) {
    if (documentSelection == null) return
    documentSelection.value = DocumentSelection.Multi(
        anchor = startEndpointOf(block, containerPath),
        focus = endEndpointOf(block, containerPath),
    )
}

// ── 4. focus 이동 시 selection 자동 해제 ──

/**
 * Cross-block selection state 를 자식 컴포넌트(BasicTextField 들)에 제공하는 CompositionLocal.
 *
 * [MarkdownBlockTextField] 의 최상위에서 `CompositionLocalProvider(LocalDocumentSelection provides ...)`
 * 로 제공. 자식 컴포넌트는 `Modifier.resetDocumentSelectionOnFocus()` 헬퍼로 focus 이동 시 자동 해제 hook 부착.
 */
val LocalDocumentSelection = compositionLocalOf<MutableState<DocumentSelection>?> { null }

/**
 * BasicTextField 의 modifier 체인에 부착. 이 필드가 focus 를 받는 순간 documentSelection 이 Multi 면
 * None 으로 자동 해제. 사용자가 selection 상태에서 다른 블록 클릭 시 selection 시각이 자연스럽게 풀림.
 *
 * Ctrl+A 직후엔 focus 가 변하지 않으므로 발동 안 함. Esc / 방향키와 별개로 동작.
 */
@Composable
fun Modifier.resetDocumentSelectionOnFocus(): Modifier {
    val selection = LocalDocumentSelection.current ?: return this
    return this.onFocusChanged { focusState ->
        if (focusState.isFocused && selection.value is DocumentSelection.Multi) {
            selection.value = DocumentSelection.None
        }
    }
}

// ── 5. 유틸리티 ──

/** atomic 블록의 끝 offset (sentinel) 또는 Text 의 text.length */
internal fun endOffsetFor(block: EditorBlock): Int = when (block) {
    is EditorBlock.Text -> block.textFieldState.text.length
    else -> SelectionEndpoint.ATOMIC_END
}

/**
 * 주어진 컨테이너 블록의 선택 가능한 첫 endpoint 를 만든다 (atomic 블록의 시작 위치).
 *
 * Step B 의 Shift+↑/↓ 확장 로직에서 사용 예정.
 */
internal fun startEndpointOf(block: EditorBlock, containerPath: List<String>): SelectionEndpoint =
    SelectionEndpoint(
        containerPath = containerPath,
        blockId = block.id,
        offset = SelectionEndpoint.ATOMIC_START,
    )

/**
 * 주어진 컨테이너 블록의 선택 가능한 마지막 endpoint 를 만든다 (atomic 블록의 끝 위치).
 *
 * Step B 의 Shift+↑/↓ 확장 로직에서 사용 예정.
 */
internal fun endEndpointOf(block: EditorBlock, containerPath: List<String>): SelectionEndpoint =
    SelectionEndpoint(
        containerPath = containerPath,
        blockId = block.id,
        offset = endOffsetFor(block),
    )
