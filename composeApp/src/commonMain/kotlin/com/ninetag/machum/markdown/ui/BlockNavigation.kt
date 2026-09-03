package com.ninetag.machum.markdown.ui

/**
 * 블록 사이의 포커스 이동 요청.
 *
 * 실제 이동 대상과 커서 위치 계산은 [MarkdownBlockEditor]가 소유한다.
 */
data class BlockFocusActions(
    val onMoveToPrevious: () -> Unit = {},
    val onMoveToNext: () -> Unit = {},
    /** x 좌표 힌트 포함 이전 블록 이동 (Text→Text) */
    val onMoveToPreviousWithX: (cursorX: Float) -> Unit = {},
    /** x 좌표 힌트 포함 다음 블록 이동 (Text→Text) */
    val onMoveToNextWithX: (cursorX: Float) -> Unit = {},
    val onMoveLeft: () -> Unit = {},
)

/** 블록 리스트를 변경하는 구조 편집 요청. */
data class BlockMutationActions(
    val onMergeWithPrevious: () -> Unit = {},
    val onSplitBlock: () -> Unit = {},
    val onSplitByEmptyLine: () -> Unit = {},
    val onReparse: () -> Unit = {},
    /**
     * focus-out 트리거 reparse: 블록 교체만 하고 새 블록으로 focus를 이동시키지 않는다.
     * 사용자가 이미 다른 블록으로 포커스를 옮긴 상태이므로 그 포커스를 보존해야 한다.
     */
    val onReparseSilent: () -> Unit = {},
    /** Callout title 위치 0 Backspace 등 자기 자신을 dissolve 하는 트리거. */
    val onDissolveSelf: () -> Unit = {},
    /** raw 블록의 텍스트가 비면 block id와 text state를 유지한 채 rawMode만 해제한다. */
    val onClearRawMode: () -> Unit = {},
)

/** 블록 단위 selection 확장 및 atomic selection 요청. */
data class BlockSelectionActions(
    val onExtendSelectionToPrevious: () -> Unit = {},
    val onExtendSelectionToNext: () -> Unit = {},
    /** Callout 등의 컨테이너 경계에서 현재 블록 하나를 atomic selection으로 선택한다. */
    val onSelectSelfAsAtomic: () -> Unit = {},
)

/** 블록 에디터가 상위 문서 에디터에 전달하는 요청을 역할별로 묶는다. */
data class BlockNavigation(
    val focus: BlockFocusActions = BlockFocusActions(),
    val mutation: BlockMutationActions = BlockMutationActions(),
    val selection: BlockSelectionActions = BlockSelectionActions(),
)
