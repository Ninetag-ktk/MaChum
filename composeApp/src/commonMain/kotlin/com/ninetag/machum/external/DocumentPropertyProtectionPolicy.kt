package com.ninetag.machum.external

/** Shared protection rules; UI-only restrictions on renaming/deleting a key remain in the form. */
internal class DocumentPropertyProtectionPolicy(
    private val managedTagNames: Set<String>,
    private val sourceIsManaged: Boolean,
) {
    fun isManagedTagsKey(key: String?): Boolean = key == "tags" && managedTagNames.isNotEmpty()

    fun isManagedSourceKey(key: String?): Boolean = sourceIsManaged && key == "source"

    /** Validate the persisted change without imposing the form's stricter key-operation rules. */
    fun persistedChangeError(before: NoteFile, after: NoteFile): String? {
        if (after.id != before.id || after.plot != before.plot) return "id와 plot은 자동 관리 속성입니다."
        val beforeRaw = before.inject()
        val afterRaw = after.inject()
        if (automaticKeys.any { documentPropertySource(beforeRaw, it) != documentPropertySource(afterRaw, it) }) {
            return "id와 plot은 자동 관리 속성입니다."
        }
        if (!after.tags.containsAll(before.tags.filter { it in managedTagNames })) {
            return "자동 관리 태그는 삭제할 수 없습니다."
        }
        if (sourceIsManaged && documentPropertySource(beforeRaw, "source") != documentPropertySource(afterRaw, "source") &&
            GeneralSourceProperty.read(afterRaw).error != null) {
            return "source는 텍스트 값 하나여야 합니다."
        }
        return null
    }

    companion object {
        private val automaticKeys = setOf("id", "plot")
        fun isAutomaticKey(key: String?): Boolean = key in automaticKeys
    }
}
