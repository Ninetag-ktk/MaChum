package com.ninetag.machum.markdown.editor

/**
 * 스팬 목록에 대한 순수 변환 유틸리티.
 * Compose 의존성 없음 — 단위 테스트 가능.
 */
internal object SpanManager {

    /**
     * [changeStart] 위치에서 [lengthDelta] 만큼 텍스트가 변경될 때 스팬 오프셋을 이동시킨다.
     * 삭제(lengthDelta < 0) 시 해당 범위 안에 완전히 포함된 스팬은 제거된다.
     */
    fun shiftSpans(
        spans: List<MarkupStyleRange>,
        changeStart: Int,
        lengthDelta: Int,
    ): List<MarkupStyleRange> {
        if (lengthDelta == 0) return spans
        return spans.mapNotNull { span ->
            when {
                // 변경 구간이 스팬 END 보다 뒤 → 스팬 불변
                // (=을 포함하지 않음: span.end 위치에서의 삽입은 스팬을 확장)
                changeStart > span.end -> span

                // 변경 구간이 스팬 START 이전 → 전체 이동
                changeStart < span.start -> {
                    val newStart = (span.start + lengthDelta).coerceAtLeast(0)
                    val newEnd   = (span.end   + lengthDelta).coerceAtLeast(newStart)
                    if (newStart == newEnd) null else span.copy(start = newStart, end = newEnd)
                }

                // 변경 구간이 스팬 내부 → end만 이동
                else -> {
                    val newEnd = (span.end + lengthDelta).coerceAtLeast(span.start)
                    if (span.start == newEnd) null else span.copy(end = newEnd)
                }
            }
        }
    }

    /**
     * 기존 스팬 목록에 새로 감지된 [markdown] 스팬을 병합한다.
     * 같은 위치·같은 스타일의 기존 스팬은 교체된다.
     */
    fun mergeSpans(
        existing: List<MarkupStyleRange>,
        markdown: List<MarkupStyleRange>,
    ): List<MarkupStyleRange> {
        // inline 스팬에 대해서만 중복 키 집합을 구성
        val newInlineKeys = markdown
            .filter { !it.style.isBlock }
            .map { Triple(it.style::class, it.start, it.end) }
            .toHashSet()

        // 새로 감지된 heading 의 범위 목록 (겹치는 기존 heading 을 제거하기 위해 사용)
        val newHeadingRanges = markdown.filter { it.style.isHeading }

        val kept = existing.filter { span ->
            when {
                // 비-heading 블록 스팬(BulletList 등)은 항상 교체
                span.style.isBlock && !span.style.isHeading -> false
                // heading 스팬은 새 heading 과 겹치면 교체, 겹치지 않으면 유지
                span.style.isHeading -> newHeadingRanges.none { new ->
                    new.start <= span.end && new.end >= span.start
                }
                // inline 스팬은 동일 위치·스타일이 새로 들어오면 교체
                else -> Triple(span.style::class, span.start, span.end) !in newInlineKeys
            }
        }
        return kept + markdown
    }

    /**
     * 같은 스타일의 인접·겹치는 스팬을 병합하여 정규화한다.
     */
    fun consolidate(spans: List<MarkupStyleRange>): List<MarkupStyleRange> {
        val result = mutableListOf<MarkupStyleRange>()

        // 블록 스타일은 중복 제거 없이 그대로 유지
        result.addAll(spans.filter { it.style.isBlock })

        spans
            .filter { !it.style.isBlock }
            .groupBy { it.style }
            .forEach { (style, group) ->
                val sorted = group.filter { !it.isEmpty }.sortedBy { it.start }
                if (sorted.isEmpty()) return@forEach

                var curStart = sorted[0].start
                var curEnd   = sorted[0].end

                for (i in 1 until sorted.size) {
                    val s = sorted[i]
                    if (s.start <= curEnd) {
                        curEnd = maxOf(curEnd, s.end)
                    } else {
                        result.add(MarkupStyleRange(style, curStart, curEnd))
                        curStart = s.start
                        curEnd   = s.end
                    }
                }
                result.add(MarkupStyleRange(style, curStart, curEnd))
            }

        return result
    }

    /**
     * [changeStart] 위치 기준으로 변경 origin(삽입 시작 위치)을 계산한다.
     */
    fun resolveChangeOrigin(
        cursorPosition: Int,
        lengthDelta: Int,
        prevLength: Int,
    ): Int {
        val origin = if (lengthDelta > 0) cursorPosition - lengthDelta else cursorPosition
        return origin.coerceIn(0, prevLength)
    }
}
