package com.ninetag.machum.entity

/** PLOT 파일의 고정 서사 단계. [code]는 파일명 접두사의 첫 번째 숫자다. */
enum class PlotStage(
    val code: Int,
    val displayName: String,
) {
    PROLOGUE(0, "프롤로그"),
    SETUP(1, "발단"),
    DEVELOPMENT(2, "전개"),
    CRISIS(3, "위기"),
    CLIMAX(4, "절정"),
    RESOLUTION(5, "결말"),
    EPILOGUE(6, "에필로그"),
    ;

    val frontmatterValue: String
        get() = "$code) $displayName"

    fun fileName(order: Int, title: String): String {
        require(order >= FIRST_ORDER) { "plot order must start at $FIRST_ORDER" }
        return "$code-$order. $title"
    }

    companion object {
        const val FIRST_ORDER: Int = 1

        fun fromCode(code: Int): PlotStage? = entries.find { it.code == code }

        fun fromFrontmatter(value: String?): PlotStage? {
            val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            normalized.substringBefore(')').trim().toIntOrNull()?.let(::fromCode)?.let { return it }
            return entries.find { it.displayName.equals(normalized, ignoreCase = true) }
        }
    }
}
