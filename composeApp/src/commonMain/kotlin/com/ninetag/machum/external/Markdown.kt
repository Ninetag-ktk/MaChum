package com.ninetag.machum.external

class Markdown private constructor(
    private val frontMatterRaw: String?,
    val body: String,
) {
    companion object {
        const val KEY_ID = "id"
        private val ID_REGEX = Regex("^${KEY_ID}:\\s*(.+)$", RegexOption.MULTILINE)
        private val CHARS = ('a'..'z') + ('0'..'9')

        fun parse(raw: String): Markdown {
            // frontMatter가 없는 경우 _ 시작점 기준
            if (!raw.startsWith("---\n")) return Markdown(frontMatterRaw = null, body = raw)

            val end = raw.indexOf("\n---", 4).takeIf { it != -1 }
                ?: return Markdown(frontMatterRaw = null, body = raw)

            val frontMatterRaw = raw.substring(4, end)
            val body = if (end + 5 <= raw.length) raw.substring(end + 5).trimStart('\n') else ""

            return Markdown(frontMatterRaw = frontMatterRaw, body = body)
        }

        private fun generatedId(): String = (1..8).map { CHARS.random() }.joinToString("")
    }

    // ID 관리
    fun getId(): String? = frontMatterRaw?.let{ID_REGEX.find(it)?.groupValues?.get(1)?.trim()}

    fun ensureId(): Markdown {
        if (getId() != null) return this
        val newId = generatedId()
        val newLine = "$KEY_ID: $newId"
        // frontMatter가 아예 없으면 새로 생성
        val updateRaw = if (frontMatterRaw == null) newLine else "$newLine\n$frontMatterRaw"
        return Markdown(frontMatterRaw = updateRaw, body = body)
    }

    fun withBody(body: String): Markdown = Markdown(frontMatterRaw = frontMatterRaw ?: generatedId(), body = body)

    fun inject(): String = if (frontMatterRaw == null) body else "---\n$frontMatterRaw\n---\n\n$body"
}