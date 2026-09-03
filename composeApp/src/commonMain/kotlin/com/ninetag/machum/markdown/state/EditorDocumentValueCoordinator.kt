package com.ninetag.machum.markdown.state

/**
 * 외부 문서 값과 에디터 내부 직렬화 값의 왕복을 조정한다.
 *
 * 외부 변경을 적용하는 동안 이전 블록의 snapshot이 늦게 도착해 새 문서를 덮어쓰지 않도록
 * [revision]으로 수명 세대를 구분한다. Compose나 파일 I/O에 의존하지 않아 단위 테스트할 수 있다.
 */
internal class EditorDocumentValueCoordinator(initialValue: String) {
    private var acknowledgedExternalValue = initialValue
    private var lastInternalValue = initialValue

    var revision: Long = 0L
        private set

    /** 외부 값을 받아 블록 재생성이 필요한 경우 true를 반환한다. */
    fun acceptExternal(value: String): Boolean {
        if (value == acknowledgedExternalValue) return false
        acknowledgedExternalValue = value
        if (value == lastInternalValue) return false

        lastInternalValue = value
        revision += 1
        return true
    }

    /** 현재 문서 세대에서 실제로 새로 발생한 내부 값만 부모로 전달하게 한다. */
    fun acceptInternal(
        value: String,
        expectedExternalValue: String,
        collectorRevision: Long,
    ): Boolean {
        if (collectorRevision != revision) return false
        if (expectedExternalValue != acknowledgedExternalValue) return false
        if (value == lastInternalValue) return false

        lastInternalValue = value
        return true
    }
}
