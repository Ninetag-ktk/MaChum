package com.ninetag.machum.markdown.state

/**
 * 문서 레벨 selection 모델.
 *
 * 각 [EditorBlock] 이 독립 TextFieldState 를 보유하는 v2 아키텍처에서 블록 경계를 넘는
 * selection 추적용. native `TextFieldState.selection`은
 * 블록 내부에만 작동하므로 cross-block 영역은 본 모델로 별도 추적한다.
 *
 * 상세 설계: `docs/markdown-editor.md`.
 */
sealed class DocumentSelection {

    /** 블록 내부 단일 selection — native TextFieldState.selection 그대로 사용. */
    data object None : DocumentSelection()

    /**
     * Cross-block selection (anchor → focus 방향성 보존).
     *
     * @property anchor 사용자가 처음 누른 위치
     * @property focus 현재 위치 (드래그/Shift+화살표 이동 중)
     */
    data class Multi(
        val anchor: SelectionEndpoint,
        val focus: SelectionEndpoint,
    ) : DocumentSelection()
}

/**
 * Selection endpoint — 어떤 블록의 어디.
 *
 * @property containerPath 최상위 → 가장 가까운 컨테이너까지의 id chain.
 *   외부 (최상위 블록 직접) 면 empty. 예: 최상위 Callout(id="A") body 안 TextBlock 의 endpoint 는
 *   `containerPath=["A"], blockId="<text>"`. 중첩 Callout 의 경우 깊이만큼 누적.
 * @property blockId 가리키는 블록 자체의 id
 * @property offset 블록 내부 텍스트 offset. atomic 블록은
 *   [SelectionEndpoint.ATOMIC_START] 또는 [SelectionEndpoint.ATOMIC_END] sentinel
 */
data class SelectionEndpoint(
    val containerPath: List<String>,
    val blockId: String,
    val offset: Int,
) {
    companion object {
        /** atomic 블록을 시작 위치로 가리킬 때 사용 */
        const val ATOMIC_START = 0

        /** atomic 블록을 끝 위치로 가리킬 때 사용. Int.MAX_VALUE 대신 작은 sentinel 사용 (overflow 방지) */
        const val ATOMIC_END = Int.MAX_VALUE
    }
}

/**
 * Phase 1 atomic 정책: Text 만 부분 선택 가능, 그 외 모든 블록은 외부에서 통째 선택.
 * Callout 은 내부에서 자체 cross-selection 가능 (재귀 호출 시 자기 컨테이너 안에서).
 *
 * Phase 5 에서 Table 셀 단위 / CodeBlock 텍스트 부분 선택 등 채택 시 분기 추가.
 */
fun isAtomic(block: EditorBlock): Boolean = when (block) {
    is EditorBlock.Text -> false
    is EditorBlock.Callout -> true  // 외부 기준 — Callout 안에서는 별도 컨테이너로 처리됨
    is EditorBlock.Code -> true
    is EditorBlock.Table -> true
    is EditorBlock.Embed -> true
    is EditorBlock.HorizontalRule -> true
}

/**
 * 정규화된 selection — start/end 가 결정되고, 컨테이너 횡단이면 공통 prefix 깊이의 atomic 으로 승격됨.
 *
 * @property start 문서 순서상 먼저 등장하는 endpoint
 * @property end 문서 순서상 나중 등장하는 endpoint
 */
data class NormalizedSelection(
    val start: SelectionEndpoint,
    val end: SelectionEndpoint,
)

/**
 * Selection 정규화. 공통 prefix 보다 깊은 endpoint 는 공통 prefix 깊이의 atomic 으로 승격한다.
 *
 * 예: anchor=(path=[], blockA), focus=(path=["B"], blockC)
 *   → 공통 prefix=[], focus 가 더 깊으므로 focus 는 path=[] 의 atomic id="B" 로 승격
 *   → 결과: start=(blockA), end=(B atomic)
 *
 * 같은 컨테이너에서는 단순히 블록 인덱스 + offset 으로 비교.
 *
 * @return 정규화 결과. blocks 가 endpoint 를 찾지 못하면 null.
 */
fun DocumentSelection.Multi.normalize(blocks: List<EditorBlock>): NormalizedSelection? {
    val anchorPromoted = promoteToCommonPath(anchor, focus.containerPath)
    val focusPromoted = promoteToCommonPath(focus, anchor.containerPath)
    if (!endpointExists(anchorPromoted, blocks) || !endpointExists(focusPromoted, blocks)) return null

    return when (compareEndpoint(anchorPromoted, focusPromoted, blocks)) {
        in Int.MIN_VALUE..0 -> NormalizedSelection(anchorPromoted, focusPromoted)
        else -> NormalizedSelection(focusPromoted, anchorPromoted)
    }
}

private fun endpointExists(endpoint: SelectionEndpoint, blocks: List<EditorBlock>): Boolean =
    resolveContainerBlocks(blocks, endpoint.containerPath)?.any { it.id == endpoint.blockId } == true

/**
 * endpoint 를 다른 path 와 공통이 되는 깊이까지 atomic 으로 승격한다.
 * 자기 path 가 상대 path 의 prefix 이거나 같으면 그대로 반환.
 */
private fun promoteToCommonPath(
    endpoint: SelectionEndpoint,
    otherPath: List<String>,
): SelectionEndpoint {
    val myPath = endpoint.containerPath
    val commonLen = commonPrefixLength(myPath, otherPath)

    if (myPath.size <= commonLen) {
        // 내 path 가 더 얕거나 같음 — 승격 불필요
        return endpoint
    }

    // 공통 prefix 깊이의 컨테이너 id 가 atomic 승격 대상
    val atomicId = myPath[commonLen]
    val atomicContainerPath = myPath.take(commonLen)

    // 어느 위치 (start/end) 로 승격할지는 컨테이너 내부 위치로 결정
    // 일단 ATOMIC_START 로 두고 비교 단계에서 보정 (offset 결정은 부르는 쪽 책임)
    return SelectionEndpoint(
        containerPath = atomicContainerPath,
        blockId = atomicId,
        offset = endpoint.offset,
    )
}

private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
    var i = 0
    while (i < a.size && i < b.size && a[i] == b[i]) i++
    return i
}

/**
 * 두 endpoint 의 문서 순서 비교. a < b 면 음수, a > b 면 양수, 같으면 0.
 *
 * 알고리즘:
 * 1. 공통 prefix 만큼 따라 들어가며 blocks → bodyBlocks 재귀
 * 2. 공통 prefix 끝에서 두 endpoint 의 블록 인덱스 비교
 * 3. 인덱스 같으면 offset 비교
 */
private fun compareEndpoint(
    a: SelectionEndpoint,
    b: SelectionEndpoint,
    blocks: List<EditorBlock>,
): Int {
    // 공통 path 깊이까지 동일 컨테이너 → 내려가서 비교
    val commonLen = commonPrefixLength(a.containerPath, b.containerPath)
    var currentBlocks: List<EditorBlock> = blocks
    for (i in 0 until commonLen) {
        val containerId = a.containerPath[i]
        val callout = currentBlocks.firstOrNull { it.id == containerId } as? EditorBlock.Callout
            ?: return 0  // 비정상 — endpoint 가 stale
        currentBlocks = callout.bodyBlocks
    }

    // 이 시점에 a, b 가 같은 컨테이너 (currentBlocks) 에 속함 (양쪽 path 모두 commonLen 깊이로 정규화된 상태)
    val aIdx = currentBlocks.indexOfFirst { it.id == a.blockId }
    val bIdx = currentBlocks.indexOfFirst { it.id == b.blockId }
    return when {
        aIdx != bIdx -> aIdx - bIdx
        else -> a.offset - b.offset
    }
}

/**
 * Selection 범위의 markdown 추출. clipboard write 에 사용.
 *
 * 알고리즘:
 * 1. selection 을 [normalize] 로 start/end 결정 (필요시 atomic 승격)
 * 2. start 블록: 부분 텍스트 추출 (text.substring(start.offset..length))
 * 3. 중간 블록: 통째로 `block.toMarkdown()`
 * 4. end 블록: 부분 텍스트 추출 (text.substring(0..end.offset))
 * 5. 모두 `\n` 으로 join (List<EditorBlock>.toMarkdown() 와 같은 규칙)
 *
 * 단일 블록 selection (start.blockId == end.blockId) 이면 그 블록의 substring 만 반환.
 */
fun extractMarkdown(
    blocks: List<EditorBlock>,
    selection: DocumentSelection.Multi,
): String {
    val normalized = selection.normalize(blocks) ?: return ""
    val (start, end) = normalized

    // 공통 path 깊이까지 따라 들어가서 그 컨테이너의 blocks 를 얻음
    val containerBlocks = resolveContainerBlocks(blocks, start.containerPath) ?: return ""

    val startIdx = containerBlocks.indexOfFirst { it.id == start.blockId }
    val endIdx = containerBlocks.indexOfFirst { it.id == end.blockId }
    if (startIdx < 0 || endIdx < 0) return ""

    return buildString {
        for (i in startIdx..endIdx) {
            if (i > startIdx) append('\n')
            val block = containerBlocks[i]
            val piece = when {
                // 단일 블록 (시작 == 끝)
                startIdx == endIdx -> partialMarkdown(block, start.offset, end.offset)
                // 시작 블록
                i == startIdx -> partialMarkdown(block, start.offset, ATOMIC_END_OFFSET)
                // 끝 블록
                i == endIdx -> partialMarkdown(block, ATOMIC_START_OFFSET, end.offset)
                // 중간 — 통째
                else -> block.toMarkdown()
            }
            append(piece)
        }
    }
}

private const val ATOMIC_START_OFFSET = 0
private const val ATOMIC_END_OFFSET = Int.MAX_VALUE

/**
 * 블록의 일부 markdown 추출. atomic 블록은 (range 와 무관하게) 통째 반환.
 *
 * @param startOffset 시작 offset. [ATOMIC_START_OFFSET] 이면 처음부터
 * @param endOffset 끝 offset. [ATOMIC_END_OFFSET] 이면 끝까지
 */
private fun partialMarkdown(block: EditorBlock, startOffset: Int, endOffset: Int): String {
    if (isAtomic(block)) return block.toMarkdown()
    val text = (block as? EditorBlock.Text)?.textFieldState?.text?.toString() ?: return block.toMarkdown()
    val s = startOffset.coerceIn(0, text.length)
    val e = if (endOffset == ATOMIC_END_OFFSET) text.length else endOffset.coerceIn(s, text.length)
    return text.substring(s, e).replace(EditorBlock.BLANK_LINE_MARKER, "")
}

/**
 * 누적 cross-block selection (Scope A) 의 핵심 traversal.
 *
 * 현재 focus endpoint 에서 방향(up/down)으로 한 칸 이동한 새 focus endpoint 를 계산한다.
 * **focus 가 속한 컨테이너(focus.containerPath) 안에서만** 이동하며, 그 컨테이너의 경계에
 * 도달하면 null 을 반환한다 (컨테이너 횡단 누적은 Scope B — 미구현).
 *
 * 이웃 블록은 항상 블록 단위(통째)로 들어온다. atomic 블록은 통째, Text 블록도 누적 단계에선
 * start/end 전체로 취급한다 — 부분 offset 은 anchor 쪽에서만 의미가 있고 [extractMarkdown] 이 처리.
 *
 * @param down true 면 아래(다음 블록), false 면 위(이전 블록)
 * @return 새 focus endpoint, 또는 컨테이너 경계라 더 확장 불가하면 null
 */
fun nextFocusEndpoint(
    blocks: List<EditorBlock>,
    focus: SelectionEndpoint,
    down: Boolean,
): SelectionEndpoint? {
    val containerBlocks = resolveContainerBlocks(blocks, focus.containerPath) ?: return null
    val idx = containerBlocks.indexOfFirst { it.id == focus.blockId }
    if (idx < 0) return null
    val targetIdx = if (down) idx + 1 else idx - 1
    if (targetIdx !in containerBlocks.indices) return null
    val target = containerBlocks[targetIdx]
    val offset = if (down) {
        when (target) {
            is EditorBlock.Text -> target.textFieldState.text.length
            else -> SelectionEndpoint.ATOMIC_END
        }
    } else {
        SelectionEndpoint.ATOMIC_START
    }
    return SelectionEndpoint(
        containerPath = focus.containerPath,
        blockId = target.id,
        offset = offset,
    )
}

/**
 * containerPath 를 따라 내려가서 해당 컨테이너의 blocks 리스트를 반환.
 * path 가 empty 면 최상위 blocks 그대로. 중간에 잘못된 id 면 null.
 */
private fun resolveContainerBlocks(
    blocks: List<EditorBlock>,
    containerPath: List<String>,
): List<EditorBlock>? {
    var current: List<EditorBlock> = blocks
    for (id in containerPath) {
        val callout = current.firstOrNull { it.id == id } as? EditorBlock.Callout ?: return null
        current = callout.bodyBlocks
    }
    return current
}
