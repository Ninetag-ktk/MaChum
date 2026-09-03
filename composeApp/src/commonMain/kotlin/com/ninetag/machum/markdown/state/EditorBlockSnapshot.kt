package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState

/**
 * Undo/Redo history가 사용하는 UI 비의존 불변 블록 값.
 *
 * [TextFieldState] 참조를 보관하지 않으므로 snapshot 이후 원본 편집과 독립적이다. 복원할 때만 새
 * TextFieldState를 만들고 기존 block ID와 중첩 구조를 그대로 사용한다.
 */
internal sealed interface EditorBlockSnapshot {
    val id: String

    data class Text(
        override val id: String,
        val text: String,
        val rawMode: Boolean,
        val rawOrigin: RawOrigin?,
    ) : EditorBlockSnapshot

    data class Callout(
        override val id: String,
        val calloutType: String,
        val title: String,
        val bodyBlocks: List<EditorBlockSnapshot>,
    ) : EditorBlockSnapshot

    data class Code(
        override val id: String,
        val language: String,
        val code: String,
    ) : EditorBlockSnapshot

    data class Table(
        override val id: String,
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : EditorBlockSnapshot

    data class HorizontalRule(
        override val id: String,
    ) : EditorBlockSnapshot

    data class Embed(
        override val id: String,
        val target: String,
    ) : EditorBlockSnapshot
}

/** 한 시점의 editor 문서 상태. 파일 저장용이 아니라 in-memory history 전용이다. */
internal data class EditorDocumentSnapshot(
    val blocks: List<EditorBlockSnapshot>,
)

internal fun captureEditorDocumentSnapshot(
    blocks: List<EditorBlock>,
): EditorDocumentSnapshot = EditorDocumentSnapshot(
    blocks = blocks.toEditorBlockSnapshots(),
)

internal fun EditorDocumentSnapshot.restoreBlocks(): List<EditorBlock> = blocks.toEditorBlocks()

internal fun List<EditorBlock>.toEditorBlockSnapshots(): List<EditorBlockSnapshot> =
    map(EditorBlock::toSnapshot)

internal fun List<EditorBlockSnapshot>.toEditorBlocks(): List<EditorBlock> =
    map(EditorBlockSnapshot::restore)

private fun EditorBlock.toSnapshot(): EditorBlockSnapshot = when (this) {
    is EditorBlock.Text -> EditorBlockSnapshot.Text(
        id = id,
        text = textFieldState.text.toString(),
        rawMode = rawMode,
        rawOrigin = rawOrigin,
    )
    is EditorBlock.Callout -> EditorBlockSnapshot.Callout(
        id = id,
        calloutType = calloutType,
        title = titleState.text.toString(),
        bodyBlocks = bodyBlocks.toEditorBlockSnapshots(),
    )
    is EditorBlock.Code -> EditorBlockSnapshot.Code(
        id = id,
        language = language,
        code = codeState.text.toString(),
    )
    is EditorBlock.Table -> EditorBlockSnapshot.Table(
        id = id,
        headers = headerStates.map { it.text.toString() },
        rows = rowStates.map { row -> row.map { it.text.toString() } },
    )
    is EditorBlock.HorizontalRule -> EditorBlockSnapshot.HorizontalRule(id)
    is EditorBlock.Embed -> EditorBlockSnapshot.Embed(id, target)
}

private fun EditorBlockSnapshot.restore(): EditorBlock = when (this) {
    is EditorBlockSnapshot.Text -> EditorBlock.Text(
        id = id,
        textFieldState = TextFieldState(text),
        rawMode = rawMode,
        rawOrigin = rawOrigin,
    )
    is EditorBlockSnapshot.Callout -> EditorBlock.Callout(
        id = id,
        calloutType = calloutType,
        titleState = TextFieldState(title),
        bodyBlocks = bodyBlocks.toEditorBlocks(),
    )
    is EditorBlockSnapshot.Code -> EditorBlock.Code(
        id = id,
        language = language,
        codeState = TextFieldState(code),
    )
    is EditorBlockSnapshot.Table -> EditorBlock.Table(
        id = id,
        headerStates = headers.map(::TextFieldState),
        rowStates = rows.map { row -> row.map(::TextFieldState) },
    )
    is EditorBlockSnapshot.HorizontalRule -> EditorBlock.HorizontalRule(id)
    is EditorBlockSnapshot.Embed -> EditorBlock.Embed(id, target)
}
