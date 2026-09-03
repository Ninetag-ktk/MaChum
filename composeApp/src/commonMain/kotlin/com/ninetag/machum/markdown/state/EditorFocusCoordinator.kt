package com.ninetag.machum.markdown.state

/** 블록 간 이동 뒤 적용할 커서 위치 힌트. */
sealed class CursorHint {
    data object Start : CursorHint()
    data object End : CursorHint()
    data class AtX(val x: Float, val lastLine: Boolean) : CursorHint()
    data class AtOffset(val offset: Int) : CursorHint()
}

/**
 * 한 번의 포커스 이동 의도.
 *
 * [preferBottomEntry]는 Callout처럼 위쪽에서 역방향으로 진입할 별도 FocusRequester가 있는 블록에서만
 * 사용한다. 실제 FocusRequester와 스크롤은 Compose UI 계층이 담당한다.
 */
internal data class EditorFocusRequest(
    val id: Long,
    val targetBlockId: String,
    val cursorHint: CursorHint?,
    val preferBottomEntry: Boolean,
)

/** coordinator에 request ID를 부여하기 전의 UI 비의존 포커스 의도. */
internal data class EditorFocusIntent(
    val targetBlockId: String,
    val cursorHint: CursorHint? = null,
    val preferBottomEntry: Boolean = false,
)

/**
 * 에디터 포커스 의도의 단일 소유자.
 *
 * UI 객체나 coroutine에 의존하지 않는다. 새 요청이 들어오면 이전 요청은 즉시 stale이 되며,
 * UI는 작업의 각 지연 지점에서 [isCurrent]를 확인해 이전 요청이 뒤늦게 적용되는 것을 막는다.
 */
internal class EditorFocusCoordinator {
    private var nextRequestId = 0L
    private var activeRequest: EditorFocusRequest? = null

    /** Compose effect를 다시 시작시키기 위한 단조 증가 상태 버전. */
    var version: Long = 0L
        private set

    fun request(
        targetBlockId: String,
        cursorHint: CursorHint? = null,
        preferBottomEntry: Boolean = false,
    ): EditorFocusRequest {
        val request = EditorFocusRequest(
            id = ++nextRequestId,
            targetBlockId = targetBlockId,
            cursorHint = cursorHint,
            preferBottomEntry = preferBottomEntry,
        )
        activeRequest = request
        version += 1
        return request
    }

    fun request(intent: EditorFocusIntent): EditorFocusRequest = request(
        targetBlockId = intent.targetBlockId,
        cursorHint = intent.cursorHint,
        preferBottomEntry = intent.preferBottomEntry,
    )

    fun currentRequest(): EditorFocusRequest? = activeRequest

    fun isCurrent(request: EditorFocusRequest): Boolean = activeRequest?.id == request.id

    /** 현재 요청을 실행 완료 상태로 전환한다. stale 요청의 완료 통지는 무시한다. */
    fun complete(request: EditorFocusRequest): Boolean = clearIfCurrent(request)

    /** 대상 블록이 사라지는 등 실행할 수 없게 된 현재 요청을 취소한다. */
    fun cancel(request: EditorFocusRequest): Boolean = clearIfCurrent(request)

    private fun clearIfCurrent(request: EditorFocusRequest): Boolean {
        if (!isCurrent(request)) return false
        activeRequest = null
        version += 1
        return true
    }
}
