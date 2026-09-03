package com.ninetag.machum.commit

internal data class LineChangeCount(
    val added: Int,
    val deleted: Int,
)

/**
 * 두 blob의 줄 단위 LCS를 이용해 추가/삭제 줄 수를 구한다.
 *
 * 공통 prefix/suffix를 먼저 제거해 일반적인 산문 수정은 작은 행렬만 계산한다. 완전히 다른 대형 파일은
 * UI 미리보기가 멈추지 않도록 전체 교체로 계산하며, commit 원본 blob과 복원 가능성에는 영향을 주지 않는다.
 */
internal object LineDiffCounter {
    private const val MAX_LCS_CELLS = 4_000_000L

    fun count(oldContent: String?, newContent: String?): LineChangeCount {
        val oldLines = oldContent.toLines()
        val newLines = newContent.toLines()
        if (oldLines.isEmpty()) return LineChangeCount(newLines.size, 0)
        if (newLines.isEmpty()) return LineChangeCount(0, oldLines.size)

        var prefix = 0
        val commonLimit = minOf(oldLines.size, newLines.size)
        while (prefix < commonLimit && oldLines[prefix] == newLines[prefix]) prefix += 1

        var suffix = 0
        while (
            suffix < commonLimit - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) {
            suffix += 1
        }

        val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
        val newMiddle = newLines.subList(prefix, newLines.size - suffix)
        if (oldMiddle.isEmpty()) return LineChangeCount(newMiddle.size, 0)
        if (newMiddle.isEmpty()) return LineChangeCount(0, oldMiddle.size)
        if (oldMiddle.size.toLong() * newMiddle.size > MAX_LCS_CELLS) {
            return LineChangeCount(newMiddle.size, oldMiddle.size)
        }

        val lcs = lcsLength(oldMiddle, newMiddle)
        return LineChangeCount(
            added = newMiddle.size - lcs,
            deleted = oldMiddle.size - lcs,
        )
    }

    private fun lcsLength(first: List<String>, second: List<String>): Int {
        val rows: List<String>
        val columns: List<String>
        if (first.size >= second.size) {
            rows = first
            columns = second
        } else {
            rows = second
            columns = first
        }
        var previous = IntArray(columns.size + 1)
        var current = IntArray(columns.size + 1)
        rows.forEach { row ->
            columns.forEachIndexed { index, column ->
                val cell = index + 1
                current[cell] = if (row == column) {
                    previous[cell - 1] + 1
                } else {
                    maxOf(previous[cell], current[cell - 1])
                }
            }
            val swap = previous
            previous = current
            current = swap
            current.fill(0)
        }
        return previous.last()
    }

    private fun String?.toLines(): List<String> = when {
        this == null || isEmpty() -> emptyList()
        else -> split('\n')
    }
}

