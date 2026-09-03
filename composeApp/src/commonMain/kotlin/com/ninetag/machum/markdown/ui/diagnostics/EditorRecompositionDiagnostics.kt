package com.ninetag.machum.markdown.ui.diagnostics

import com.ninetag.machum.markdown.state.DocumentSelection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect

internal data class EditorRecompositionSample(
    val scope: String,
    val key: String,
    val count: Int,
)

/** 실제 Composable SideEffect가 실행된 횟수를 집계하는 작은 개발 진단용 counter. */
internal class EditorRecompositionCounter {
    private val counts = mutableMapOf<Pair<String, String>, Int>()

    fun record(scope: String, key: String): EditorRecompositionSample {
        val identity = scope to key
        val count = (counts[identity] ?: 0) + 1
        counts[identity] = count
        return EditorRecompositionSample(scope, key, count)
    }

    fun snapshot(): List<EditorRecompositionSample> = counts
        .map { (identity, count) ->
            EditorRecompositionSample(identity.first, identity.second, count)
        }
        .sortedWith(compareBy(EditorRecompositionSample::scope, EditorRecompositionSample::key))

    fun reset() {
        counts.clear()
    }
}

/**
 * Desktop 개발 실행에서만 명시적으로 켤 수 있는 recomposition 계측 지점.
 *
 * `MACHUM_EDITOR_RECOMPOSITION_METRICS=true` 환경 변수 또는
 * `-Dmachum.editor.recomposition.metrics=true` JVM 속성을 지정한 경우에만 SideEffect를 등록한다.
 * Android actual은 항상 false이며, 일반 실행에서는 counter와 로그를 전혀 갱신하지 않는다.
 * 고정된 scope/key로 반복 호출되어도 측정 호출 자체가 skip되지 않아야 하므로 non-skippable이다.
 */
@Composable
@NonSkippableComposable
internal fun TrackEditorRecomposition(scope: String, key: String) {
    if (!isEditorRecompositionDiagnosticsEnabled()) return
    SideEffect {
        val sample = EditorRecompositionDiagnostics.counter.record(scope, key)
        // 초기 block 구성은 로그를 과도하게 만들므로 생략하고, 실제 재구성(2회차 이상)부터 기록한다.
        if ((scope != "block" && scope != "block-row") || sample.count > 1) {
            println(
                "[EditorRecomposition] scope=${sample.scope} key=${sample.key} count=${sample.count}",
            )
        }
    }
}

/** selection 상태가 invalidation시키는 최소 restart scope를 직접 측정한다. */
@Composable
internal fun TrackEditorSelectionRecomposition(
    documentSelection: State<DocumentSelection>?,
    key: String,
) {
    if (!isEditorRecompositionDiagnosticsEnabled()) return
    // SideEffect가 현재 값을 캡처하게 하여 이 읽기가 본 restart scope의 실제 의존성으로 남도록 한다.
    val selectionSnapshot = documentSelection?.value
    SideEffect {
        val sample = EditorRecompositionDiagnostics.counter.record("selection-surface", key)
        println(
            "[EditorRecomposition] scope=${sample.scope} key=${sample.key} " +
                "count=${sample.count} selection=${when (selectionSnapshot) {
                    is DocumentSelection.Multi -> "Multi"
                    DocumentSelection.None -> "None"
                    null -> "disabled"
                }}",
        )
    }
}

internal object EditorRecompositionDiagnostics {
    val counter = EditorRecompositionCounter()
}

internal expect fun isEditorRecompositionDiagnosticsEnabled(): Boolean
