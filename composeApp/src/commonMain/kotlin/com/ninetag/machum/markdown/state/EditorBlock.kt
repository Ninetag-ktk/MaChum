package com.ninetag.machum.markdown.state

import androidx.compose.foundation.text.input.TextFieldState
import kotlin.uuid.Uuid

/**
 * 블록 기반 에디터의 문서 모델.
 *
 * 각 블록은 독립적인 TextFieldState를 보유하며, LazyColumn의 아이템으로 렌더링된다.
 * [id]는 LazyColumn key로 사용되어 블록 재활용 시 state를 유지한다.
 * [toMarkdown]으로 직렬화하여 raw markdown 문자열을 복원한다.
 */
sealed class EditorBlock {
    abstract val id: String
    abstract fun toMarkdown(): String

    /**
     * 일반 텍스트 블록.
     * Heading, BulletList, OrderedList, Blockquote, 일반 텍스트를 포함한다.
     * 인라인 서식은 RawMarkdownOutputTransformation이 처리.
     */
    data class Text(
        override val id: String = generateId(),
        val textFieldState: TextFieldState,
    ) : EditorBlock() {
        override fun toMarkdown(): String = textFieldState.text.toString()
    }

    /**
     * Callout 블록 (`> [!TYPE] Title` + body).
     * body는 재귀적 블록 리스트로 중첩 Callout/Table/CodeBlock을 포함할 수 있다.
     */
    data class Callout(
        override val id: String = generateId(),
        val calloutType: String,
        val titleState: TextFieldState,
        val bodyBlocks: List<EditorBlock>,
    ) : EditorBlock() {
        override fun toMarkdown(): String {
            val header = "> [!$calloutType] ${titleState.text}"
            if (bodyBlocks.isEmpty()) return header
            val body = bodyBlocks.joinToString("\n") { block ->
                block.toMarkdown().lines().joinToString("\n") { "> $it" }
            }
            return "$header\n$body"
        }
    }

    /**
     * 코드 블록 (``` 펜스).
     * 펜스 줄은 toMarkdown()에서 자동 생성 (에디터에는 코드만 표시).
     */
    data class Code(
        override val id: String = generateId(),
        val language: String,
        val codeState: TextFieldState,
    ) : EditorBlock() {
        override fun toMarkdown(): String = "```$language\n${codeState.text}\n```"
    }

    /**
     * 테이블 블록 (| 구분자).
     * 구분자 줄(`| --- |`)은 toMarkdown()에서 자동 생성.
     */
    data class Table(
        override val id: String = generateId(),
        val headerStates: List<TextFieldState>,
        val rowStates: List<List<TextFieldState>>,
    ) : EditorBlock() {
        override fun toMarkdown(): String {
            val headerLine = "| ${headerStates.joinToString(" | ") { it.text.toString() }} |"
            val sepLine = "| ${headerStates.joinToString(" | ") { "---" }} |"
            val dataLines = rowStates.joinToString("\n") { row ->
                "| ${row.joinToString(" | ") { it.text.toString() }} |"
            }
            return "$headerLine\n$sepLine" + if (dataLines.isNotEmpty()) "\n$dataLines" else ""
        }
    }

    /** 수평선 (`---`). 읽기 전용. */
    data class HorizontalRule(
        override val id: String = generateId(),
    ) : EditorBlock() {
        override fun toMarkdown(): String = "---"
    }

    /** 임베드 (`![[파일명]]`). 읽기 전용. */
    data class Embed(
        override val id: String = generateId(),
        val target: String,
    ) : EditorBlock() {
        override fun toMarkdown(): String = "![[$target]]"
    }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun generateId(): String = Uuid.random().toString()

/** 블록 리스트를 raw markdown 문자열로 직렬화한다. 블록 사이에 빈 줄(\n\n)을 삽입. */
fun List<EditorBlock>.toMarkdown(): String =
    joinToString("\n\n") { it.toMarkdown() }
