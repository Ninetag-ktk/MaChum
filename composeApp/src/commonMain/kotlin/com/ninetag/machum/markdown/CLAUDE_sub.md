# 블록 기반 마크다운 에디터 — 설계 및 구현 가이드

> **이 문서는 다른 세션/PC에서 작업을 이어갈 때 참고하는 상세 가이드이다.**
> 현재 진행 상태, 아키텍처, 각 파일의 역할, 남은 작업을 구체적으로 기술한다.

---

## 1. 아키텍처 개요

문서를 `List<EditorBlock>`으로 관리하고, 각 블록이 독립 Composable로 렌더링된다.
이전 v1(단일 BasicTextField + overlay Composable) 아키텍처를 대체한다.

### 데이터 흐름

```
[파일 로딩]
  .md 파일 (raw markdown)
      ↓ MarkdownBlockParser.parse()
  List<EditorBlock>

[사용자 편집]
  각 블록의 TextFieldState에 직접 입력
      ↓ TextBlock: RawMarkdownOutputTransformation (인라인 서식)
      ↓ CalloutBlock: 재귀적 MarkdownBlockEditor
      ↓ CodeBlock/TableBlock: 자체 TextField
      ↓ 패턴 감지 (debounce 150ms): 블록 분리/변환
  화면 표시

[저장]
  blocks.toMarkdown()  ← 블록 리스트 → raw markdown
      ↓ onValueChange(rawMarkdown) → EditorPage debounce(500ms) → 파일 저장
```

### v1 overlay 방식과의 차이

| 항목 | v1 (overlay) | 현재 (블록) |
|---|---|---|
| 텍스트 저장 | 단일 TextFieldState (전체 문서) | 블록별 TextFieldState |
| 특수 블록 | 투명 텍스트 위에 overlay Composable | 독립 Composable (높이 제한 없음) |
| 높이 계산 | raw text ↔ overlay 높이 일치 필요 | 각 블록이 자체 높이 결정 |
| 동기화 | overlay ↔ raw text 양방향 sync | 불필요 (직접 편집 → toMarkdown) |

---

## 2. 파일 구조 및 역할

### 블록 에디터 (현행 코드)

```
markdown/
├── state/
│   ├── EditorBlock.kt              ← 블록 모델 sealed class + toMarkdown()
│   ├── MarkdownBlockParser.kt      ← parse(markdown) → List<EditorBlock>
│   └── BlockOperations.kt          ← 블록 분할/병합/재파싱 로직
│
├── ui/
│   ├── MarkdownBlockTextField.kt   ← 공개 API: value/onValueChange + M3 래퍼
│   ├── MarkdownBlockEditor.kt      ← LazyColumn 블록 dispatcher + BlockNavigation
│   └── TextBlockEditor.kt          ← BasicTextField + 인라인 서식 + 패턴 감지
│
└── ui/block/
    ├── CalloutBlockEditor.kt       ← Callout (Standard + DIALOGUE, 재귀 body)
    ├── CodeBlockEditor.kt          ← CodeBlock (monospace)
    ├── TableBlockEditor.kt         ← Table (셀별 TextField)
    └── HorizontalRuleDivider.kt    ← HR (Divider)
```

### v1에서 재활용하는 파일 (블록 에디터 내부에서 사용)

```
markdown/
├── service/
│   ├── MarkdownStyleConfig.kt      ← 전체 스타일 설정 (callout 색상, 마커, 인라인 등)
│   └── util/
│       └── EditorKeyboardShortcuts.kt ← Ctrl+B/I/E 등 (TextBlockEditor에서 사용)
│
├── state/
│   ├── InlineStyleScanner.kt       ← SpanStyle 계산 (OT 내부에서 호출)
│   ├── MarkdownPatternScanner.kt   ← 문서 스캔 (OT 내부에서 호출)
│   ├── RawMarkdownOutputTransformation.kt ← TextBlockEditor의 OutputTransformation
│   ├── EditorInputTransformation.kt ← Smart Enter, auto-close
│   ├── RawStyleToggle.kt           ← 서식 토글 유틸리티
│   └── MarkdownBlock.kt            ← 블록 타입 정의 (Scanner 내부용)
│
└── ui/
    └── BlockDecorationDrawer.kt     ← DrawBehind (blockquote 테두리, HR, inline code 배경)
```

### v1 전용 (EditorPage에서 미사용, 제거 예정)

```
MarkdownBasicTextField.kt, MarkdownTextField.kt, MarkdownEditorState.kt
OverlayBlockParser.kt, OverlayPositionCalculator.kt
OverlayScrollForwarder.kt
BlockOverlay.kt, CalloutOverlay.kt, CodeBlockOverlay.kt, TableOverlay.kt
InlineOnlyOutputTransformation.kt
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 EditorBlock (`state/EditorBlock.kt`)

```kotlin
sealed class EditorBlock {
    abstract val id: String       // UUID — LazyColumn key
    abstract fun toMarkdown(): String

    data class Text(id, textFieldState: TextFieldState)
    data class Callout(id, calloutType: String, titleState: TextFieldState, bodyBlocks: List<EditorBlock>)
    data class Code(id, language: String, codeState: TextFieldState)
    data class Table(id, headerStates: List<TextFieldState>, rowStates: List<List<TextFieldState>>)
    data class HorizontalRule(id)
    data class Embed(id, target: String)
}

fun List<EditorBlock>.toMarkdown(): String = joinToString("\n\n") { it.toMarkdown() }
```

- **TextFieldState를 블록 내부에 보유**: LazyColumn 재활용 시에도 `key = { block.id }`로 state 유지
- **UUID**: `kotlin.uuid.Uuid.random()` 사용 (`@OptIn(ExperimentalUuidApi::class)`)
- **Callout.bodyBlocks**: 재귀적 블록 리스트 → 중첩 Callout/Table/Code 지원
- **toMarkdown()**: Callout body는 각 줄에 `> ` prefix 추가

### 3.2 MarkdownBlockParser (`state/MarkdownBlockParser.kt`)

```kotlin
object MarkdownBlockParser {
    fun parse(markdown: String): List<EditorBlock>
}
```

줄 단위 순회로 블록 감지:
- ` ``` ` → CodeBlock (펜스 내부 줄 수집)
- `^(>+) ?\[!(\w+)]\s*(.*)` → Callout (후속 `>` 줄 수집 + body 재귀 파싱)
- `|` 시작 + 2개 이상 `|` → Table (연속 `|` 줄 수집, 최소 2줄)
- `---`/`***`/`___` (3+ 문자) → HorizontalRule
- `![[...]]` → Embed
- 빈 줄 → TextBlock 분리
- 나머지 → TextBlock에 축적 (heading, list, blockquote 포함)

**TextBlock에 포함되는 것**: Heading, BulletList, OrderedList, Blockquote, 일반 텍스트
→ `RawMarkdownOutputTransformation` + `BlockDecorationDrawer`로 렌더링

### 3.3 BlockOperations (`state/BlockOperations.kt`)

```kotlin
object BlockOperations {
    fun trySplitTextBlock(blocks, blockIndex): SplitResult?    // 마지막 줄 패턴 감지 → 분리
    fun trySplitByEmptyLine(blocks, blockIndex): SplitResult?  // \n\n → TextBlock 분리
    fun mergeWithPrevious(blocks, blockIndex): SplitResult?    // Backspace → 이전 블록 병합
    fun tryReparse(blocks, blockIndex): SplitResult?           // 전체 재파싱 → 블록 분리
}

data class SplitResult(newBlocks: List<EditorBlock>, focusBlockIndex: Int, focusCursorOffset: Int = 0)
```

- **tryReparse()**: TextBlock 텍스트를 `MarkdownBlockParser.parse()`로 재파싱.
  여러 블록이 나오면 분리. TextBlockEditor의 debounce(150ms) 후 호출됨.
- **mergeWithPrevious()**: TextBlock+TextBlock → 병합, 빈 CodeBlock/HR → 삭제

### 3.4 MarkdownBlockTextField (`ui/MarkdownBlockTextField.kt`)

```kotlin
// 공개 API — EditorPage에서 직접 사용
@Composable
fun MarkdownBlockTextFieldM3(value: String, onValueChange: (String) -> Unit, ...)

// 내부 구현
@Composable
fun MarkdownBlockTextField(value: String, onValueChange: (String) -> Unit, ...)
```

- `value` → `MarkdownBlockParser.parse()` → `List<EditorBlock>`
- `snapshotFlow { blocks.toMarkdown() }` → `onValueChange` 콜백
- 외부 value 변경 감지: `value != lastExternalValue && value != lastInternalValue` → 재파싱
- Material3 래퍼: `defaultMaterialBlockStyleConfig()`으로 테마 색상 적용

### 3.5 MarkdownBlockEditor (`ui/MarkdownBlockEditor.kt`)

```kotlin
@Composable
internal fun MarkdownBlockEditor(
    blocks: List<EditorBlock>,
    onBlocksChanged: (List<EditorBlock>) -> Unit,
    isNested: Boolean = false,  // true: Column, false: LazyColumn
    ...)
```

- **FocusRequester 관리**: `mutableMapOf<String, FocusRequester>` (블록 id 기반)
- **포커스 지연 요청**: `pendingFocusBlockId` + `focusRequestCounter` + `LaunchedEffect` + `delay(50ms)`
- **BlockNavigation**: 각 블록에 전달되는 콜백 집합 (이전/다음 이동, 분할, 병합, 재파싱)
- **isNested=true**: Callout body 등 재귀 호출 시 Column 사용 (LazyColumn 중첩 방지)

### 3.6 TextBlockEditor (`ui/TextBlockEditor.kt`)

```kotlin
@Composable
internal fun TextBlockEditor(block: EditorBlock.Text, focusRequester, navigation, ...)
```

- **인라인 서식**: `RawMarkdownOutputTransformation(applyBlockTransparent=false)` 재활용
- **포커스 기반 서식 전환**: `remember(styleConfig, isFocused)`로 OT 인스턴스 재생성
  → 포커스 아웃 시 모든 줄에 서식 적용, 포커스 인 시 커서 줄만 raw
- **패턴 감지**: `snapshotFlow` + `debounce(150ms)` → `onReparse()` 호출
  → BlockOperations.tryReparse()가 텍스트를 재파싱하여 블록 분리
- **블록 간 커서 이동**: `onPreviewKeyEvent`에서 ↑(첫 줄)/↓(마지막 줄) 감지 → `onMoveToPrevious/Next`
- **Backspace 병합**: position 0에서 Backspace → `onMergeWithPrevious`

### 3.7 CalloutBlockEditor (`ui/block/CalloutBlockEditor.kt`)

- Standard: Column(배경+테두리) + Row(Icon+Title) + 재귀 MarkdownBlockEditor(body)
- DIALOGUE: Row(Title+Body 가로 배치)
- body fontSize: `textStyle.fontSize * 0.9f`
- 타입별 아이콘: `calloutIcon()` (Icons.Outlined 사용)
- `onBlocksChanged`: body 블록 변경 시 부모에 전파

### 3.8 CodeBlockEditor / TableBlockEditor

- **CodeBlockEditor**: Box(라운드 배경) + monospace BasicTextField
  - 빈 상태에서 Backspace → `navigation.onMergeWithPrevious()`
- **TableBlockEditor**: Column/Row 그리드 + 셀별 BasicTextField (SingleLine)
  - 헤더 bold, 셀 border 0.5dp

---

## 4. 해결된 기술적 이슈

| 이슈 | 원인 | 해결 |
|---|---|---|
| 포커스 아웃 후 서식 미적용 | `isFocused`가 단순 var → OT의 `transformOutput()` 재실행 안 됨 | `remember(styleConfig, isFocused)`로 OT 인스턴스 재생성 |
| FocusRequester not initialized | `LaunchedEffect` 안에서 key 변경 → effect 자기 취소 | 별도 `focusRequestCounter` + `delay(50ms)` 후 requestFocus |
| FocusRequester 블록 변경 시 유실 | `remember(blocks.size)`로 리스트 재생성 | `mutableMapOf<String, FocusRequester>` id 기반 관리 |
| TextBlock 내 블록 서식 깨짐 | OT가 callout/code 마커를 숨기지만 overlay UI 없음 | `tryReparse()`로 텍스트 재파싱 → 자동 블록 분리 (debounce 150ms) |

---

## 5. Phase 진행 상태 및 다음 작업

### Phase 1: 블록 에디터 기본 구조 ✅ 완료

| # | 항목 | 파일 |
|---|---|---|
| 1 | EditorBlock sealed class | `state/EditorBlock.kt` |
| 2 | MarkdownBlockParser | `state/MarkdownBlockParser.kt` |
| 3 | toMarkdown() 직렬화 | `state/EditorBlock.kt` |
| 4 | MarkdownBlockEditor (LazyColumn) | `ui/MarkdownBlockEditor.kt` |
| 5 | TextBlockEditor | `ui/TextBlockEditor.kt` |
| 6 | CalloutBlockEditor (Standard + DIALOGUE) | `ui/block/CalloutBlockEditor.kt` |
| 7 | CodeBlockEditor | `ui/block/CodeBlockEditor.kt` |
| 8 | TableBlockEditor | `ui/block/TableBlockEditor.kt` |
| 9 | HorizontalRuleDivider | `ui/block/HorizontalRuleDivider.kt` |
| 10 | MarkdownBlockTextField + M3 래퍼 | `ui/MarkdownBlockTextField.kt` |
| 11 | EditorPage 연동 | `screen/mainComposition/EditorPage.kt` |

### Phase 2: 블록 간 상호작용 (부분 완료)

| # | 항목 | 상태 | 파일/비고 |
|---|---|---|---|
| 12 | BlockOperations (분할/병합) | ✅ | `state/BlockOperations.kt` |
| 13 | TextBlock 간 ↑↓ 커서 이동 | ✅ | `ui/TextBlockEditor.kt` onPreviewKeyEvent |
| 14 | TextBlock 재파싱 자동 분리 | ✅ | `tryReparse()` + debounce 150ms |
| 15 | TextBlock Backspace 병합 | ✅ | `mergeWithPrevious()` |
| 16 | **Callout/Code/Table/HR 간 방향키 이동** | ⬜ | 각 블록 에디터에 `navigation` 전달 필요 |
| 17 | **Callout title ↔ body 키보드 내비게이션** | ⬜ | FocusRequester로 title↔body 전환 |
| 18 | **Smart Enter 블록 단위 확장** | ⬜ | TextBlock에서 Enter → 특수 블록 생성 |

#### Phase 2 남은 작업 구현 가이드

**#16 Callout/Code/Table/HR 간 방향키 이동**:
- `CalloutBlockEditor`: 마지막 body 블록 ↓ → 다음 블록, 첫 body 블록 ↑ → 이전 블록
  - `CalloutBlockEditor`에 `navigation: BlockNavigation` 파라미터 추가
  - body의 `MarkdownBlockEditor`에서 첫/마지막 블록의 이동을 부모 navigation으로 전파
- `CodeBlockEditor`: ↑(첫 줄) → 이전 블록, ↓(마지막 줄) → 다음 블록
  - 이미 `navigation` 파라미터 있음. `onPreviewKeyEvent`에 ↑↓ 처리 추가
- `TableBlockEditor`: 마지막 셀에서 Tab → 다음 블록 (또는 새 행 추가)
  - `navigation` 파라미터 추가 필요
- `HorizontalRuleDivider`: 클릭 또는 포커스 시 이전/다음 블록으로 이동
  - 현재 읽기 전용 → focusable modifier 추가 필요

**#17 Callout title ↔ body 내비게이션**:
- Title에서 ↓ 또는 Enter → body 첫 블록에 포커스
- Body 첫 블록에서 ↑ → title에 포커스
- v1 `CalloutOverlay`의 `titleFocusRequester`/`bodyFocusRequester` 패턴 참고

**#18 Smart Enter 블록 단위 확장**:
- TextBlock에서 `---` 입력 + Enter → HR 블록 생성 (현재는 tryReparse로 처리)
- 빈 CodeBlock에서 Enter → 다음 TextBlock 생성
- Callout body 마지막에서 Enter 2회 → Callout 블록 종료, 다음 TextBlock 생성

### Phase 3: 고급 기능

| # | 항목 | 상태 | 구현 가이드 |
|---|---|---|---|
| 19 | Cross-block selection | ⬜ | 아래 섹션 6 참고 |
| 20 | Undo/Redo | ⬜ | 아래 섹션 7 참고 |
| 21 | Embed 블록 렌더링 | ⬜ | FileManager 비동기 로딩 → 읽기 전용 Composable |
| 22 | v1 코드 제거 | ⬜ | 위 "v1 전용" 파일 삭제 + CLAUDE.md 정리 |

---

## 6. Cross-Block Selection (Phase 3)

### 접근 방식

Compose BasicTextField의 selection은 단일 TextField 내에서만 동작.
cross-block selection은 **문서 레벨 가상 selection**으로 구현.

```kotlin
data class DocumentSelection(
    val startBlockId: String,
    val startOffset: Int,
    val endBlockId: String,
    val endOffset: Int,
)
```

### 시각적 표현

- 시작 블록: BasicTextField 네이티브 selection (startOffset ~ 끝)
- 중간 블록: DrawBehind로 전체 영역 selection 색상
- 끝 블록: DrawBehind로 시작 ~ endOffset

### 복사

각 블록의 `toMarkdown()` 결과에서 선택 범위만 추출하여 합성.
텍스트 단위 선택 (블록 전체 선택이 아닌 글자 단위).

---

## 7. Undo/Redo (Phase 3)

### 문서 스냅샷 방식

```kotlin
class UndoManager(maxHistory: Int = 50) {
    private val undoStack = ArrayDeque<String>()  // markdown 스냅샷
    private val redoStack = ArrayDeque<String>()
    fun snapshot(markdown: String)
    fun undo(): String?
    fun redo(): String?
}
```

`blocks.toMarkdown()`의 결과를 스냅샷. undo 시 재파싱하여 블록 리스트 복원.

---

## 8. EditorPage 연동

```kotlin
// screen/mainComposition/EditorPage.kt
import com.ninetag.machum.markdown.ui.MarkdownBlockTextFieldM3

MarkdownBlockTextFieldM3(
    value = noteFile.body,
    onValueChange = { pendingMarkdown.value = it },
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
)
```

- `value`: raw markdown (NoteFile.body)
- `onValueChange`: raw markdown 콜백 → `MutableStateFlow` → `debounce(500ms)` → `viewModel.updateBody()`
- `key(file.name)`: 파일 전환 시 에디터 리셋
