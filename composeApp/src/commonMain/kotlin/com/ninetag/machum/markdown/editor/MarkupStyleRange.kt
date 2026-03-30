package com.ninetag.machum.markdown.editor

/**
 * clean text 상의 문자 범위 [start, end) 에 [style] 서식을 적용함을 나타낸다.
 *
 * @param start inclusive
 * @param end   exclusive
 */
data class MarkupStyleRange(
    val style: MarkupStyle,
    val start: Int,
    val end: Int,
) {
    init {
        require(start >= 0) { "start must be >= 0, was $start" }
        require(start <= end) { "start($start) must be <= end($end)" }
    }

    val isEmpty: Boolean get() = start == end
}
