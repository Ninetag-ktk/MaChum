package com.ninetag.machum.markdown.state

/**
 * History에 기록되는 편집 경계.
 *
 * 같은 [Typing.coalescingKey]의 연속 입력만 750ms 범위에서 합치고, 구조 편집·clipboard 동작은
 * [Atomic] 한 건으로 유지한다. 시간은 UI가 전달하여 테스트와 플랫폼 clock을 분리한다.
 */
internal sealed interface EditorHistoryTransaction {
    data class Typing(
        val coalescingKey: String,
        val occurredAtMillis: Long,
    ) : EditorHistoryTransaction

    data object Atomic : EditorHistoryTransaction
}

/** UI와 Compose에 의존하지 않는 문서 Undo/Redo stack. */
internal class EditorHistory(
    initialSnapshot: EditorDocumentSnapshot,
    private val maxUndoEntries: Int = DEFAULT_MAX_UNDO_ENTRIES,
    private val typingCoalesceWindowMillis: Long = DEFAULT_TYPING_COALESCE_WINDOW_MILLIS,
) {
    init {
        require(maxUndoEntries > 0) { "maxUndoEntries must be positive" }
        require(typingCoalesceWindowMillis >= 0) { "typingCoalesceWindowMillis must not be negative" }
    }

    private val undoStack = mutableListOf<EditorDocumentSnapshot>()
    private val redoStack = mutableListOf<EditorDocumentSnapshot>()
    private var lastTransaction: EditorHistoryTransaction? = null

    var current: EditorDocumentSnapshot = initialSnapshot
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoSize: Int get() = undoStack.size
    val redoSize: Int get() = redoStack.size

    /** 같은 snapshot은 기록하지 않는다. 새 편집이 기록되면 redo branch를 폐기한다. */
    fun record(
        snapshot: EditorDocumentSnapshot,
        transaction: EditorHistoryTransaction,
    ): Boolean {
        if (snapshot == current) return false

        if (!shouldCoalesce(lastTransaction, transaction)) {
            undoStack += current
            trimUndoStack()
        }
        current = snapshot
        redoStack.clear()
        lastTransaction = transaction
        return true
    }

    fun undo(): EditorDocumentSnapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack += current
        current = previous
        lastTransaction = null
        return current
    }

    fun redo(): EditorDocumentSnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack += current
        trimUndoStack()
        current = next
        lastTransaction = null
        return current
    }

    /** 진짜 외부 문서 교체 시 새 baseline으로 초기화한다. */
    fun reset(snapshot: EditorDocumentSnapshot) {
        current = snapshot
        undoStack.clear()
        redoStack.clear()
        lastTransaction = null
    }

    private fun shouldCoalesce(
        previous: EditorHistoryTransaction?,
        next: EditorHistoryTransaction,
    ): Boolean {
        if (previous !is EditorHistoryTransaction.Typing || next !is EditorHistoryTransaction.Typing) {
            return false
        }
        val elapsed = next.occurredAtMillis - previous.occurredAtMillis
        return previous.coalescingKey == next.coalescingKey &&
            elapsed in 0..typingCoalesceWindowMillis
    }

    private fun trimUndoStack() {
        while (undoStack.size > maxUndoEntries) {
            undoStack.removeAt(0)
        }
    }

    companion object {
        const val DEFAULT_MAX_UNDO_ENTRIES = 100
        const val DEFAULT_TYPING_COALESCE_WINDOW_MILLIS = 750L
    }
}

/**
 * 두 block snapshot 사이의 변화가 한 editable field의 일반 입력인지 판정한다.
 * 구조·타입·서식 메타데이터 변화 또는 여러 field 동시 변화는 atomic transaction이다.
 */
internal fun classifyEditorHistoryTransaction(
    previous: List<EditorBlockSnapshot>,
    next: List<EditorBlockSnapshot>,
    occurredAtMillis: Long,
): EditorHistoryTransaction {
    val changedKeys = mutableListOf<String>()
    val sameStructure = collectChangedEditableFields(
        previous = previous,
        next = next,
        containerPath = emptyList(),
        changedKeys = changedKeys,
    )
    return if (sameStructure && changedKeys.size == 1) {
        EditorHistoryTransaction.Typing(changedKeys.single(), occurredAtMillis)
    } else {
        EditorHistoryTransaction.Atomic
    }
}

private fun collectChangedEditableFields(
    previous: List<EditorBlockSnapshot>,
    next: List<EditorBlockSnapshot>,
    containerPath: List<String>,
    changedKeys: MutableList<String>,
): Boolean {
    if (previous.size != next.size) return false
    for (index in previous.indices) {
        val before = previous[index]
        val after = next[index]
        if (before.id != after.id || before::class != after::class) return false
        val blockPath = (containerPath + before.id).joinToString("/")
        when {
            before is EditorBlockSnapshot.Text && after is EditorBlockSnapshot.Text -> {
                if (before.rawMode != after.rawMode || before.rawOrigin != after.rawOrigin) return false
                if (before.text != after.text) changedKeys += "$blockPath:text"
            }
            before is EditorBlockSnapshot.Callout && after is EditorBlockSnapshot.Callout -> {
                if (before.calloutType != after.calloutType) return false
                if (before.title != after.title) changedKeys += "$blockPath:title"
                if (!collectChangedEditableFields(before.bodyBlocks, after.bodyBlocks, containerPath + before.id, changedKeys)) {
                    return false
                }
            }
            before is EditorBlockSnapshot.Code && after is EditorBlockSnapshot.Code -> {
                if (before.language != after.language) return false
                if (before.code != after.code) changedKeys += "$blockPath:code"
            }
            before is EditorBlockSnapshot.Table && after is EditorBlockSnapshot.Table -> {
                if (before.headers.size != after.headers.size || before.rows.size != after.rows.size) return false
                for (column in before.headers.indices) {
                    if (before.headers[column] != after.headers[column]) {
                        changedKeys += "$blockPath:header:$column"
                    }
                }
                for (row in before.rows.indices) {
                    if (before.rows[row].size != after.rows[row].size) return false
                    for (column in before.rows[row].indices) {
                        if (before.rows[row][column] != after.rows[row][column]) {
                            changedKeys += "$blockPath:cell:$row:$column"
                        }
                    }
                }
            }
            before is EditorBlockSnapshot.HorizontalRule && after is EditorBlockSnapshot.HorizontalRule -> Unit
            before is EditorBlockSnapshot.Embed && after is EditorBlockSnapshot.Embed -> {
                if (before.target != after.target) return false
            }
            else -> return false
        }
    }
    return true
}
