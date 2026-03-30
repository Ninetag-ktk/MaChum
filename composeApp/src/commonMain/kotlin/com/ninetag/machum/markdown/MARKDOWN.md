# MaChum 마크다운 엔진 구조 설명

이 문서는 MaChum의 마크다운 에디터 구현을 **레퍼런스(Hyphen 라이브러리)**와 비교하면서 설명한다.
모듈을 직접 수정할 때 어느 파일의 어느 부분을 건드려야 하는지 파악하는 용도로 사용한다.

---

## 1. 전체 구조 비교

| 항목 | 레퍼런스 (`reference_hyphen/`) | MaChum (`markdown/editor/`) |
|---|---|---|
| 패키지 | `com.denser.hyphen` | `com.ninetag.machum.markdown.editor` |
| 내부 텍스트 | clean text (기호 제거) | clean text (기호 제거) |
| 서식 저장 | `List<MarkupStyleRange>` (스팬 목록) | 동일 |
| 화면 표시 | `OutputTransformation` + SpanStyle | 동일 |
| 저장 형식 | `toMarkdown()` 직렬화 | 동일 |
| Undo/Redo | `HistoryManager` 포함 | **미구현** |
| 블록 스타일 조작 (툴바) | `BlockStyleManager` 포함 | **미구현** |
| pending override (타이핑 전 스타일 지정) | `pendingOverrides` map | **미구현** |
| 클립보드 직렬화 | `rememberMarkdownClipboard` | **미구현** |
| 하드웨어 키보드 단축키 | Ctrl+B/I/U 등 | **미구현** |
| Checkbox | 지원 | 미지원 |
| WikiLink / ExternalLink | 미지원 | **추가 지원** |

---

## 2. 핵심 데이터 흐름

```
[파일 로딩]
  .md 파일 (raw 마크다운)
      ↓ MarkdownEditorState.setMarkdown()
      ↓ MarkdownStyleProcessor.process()
  clean text + List<MarkupStyleRange>
      ↓
  TextFieldState (clean text 저장)
  _spans (mutableStateOf, 서식 목록 저장)

[사용자 입력]
  키 입력
      ↓ EditorInputTransformation (auto-close: [[, **, ~~ 등 자동 닫기)
      ↓ MarkdownPatternInputTransformation
      ↓ MarkdownEditorState.applyInput()
        1. prevLength, newText, cursorPos, lengthDelta 계산
        2. SpanManager.resolveChangeOrigin() → 변경 시작 위치
        3. SpanManager.shiftSpans() → 기존 스팬 오프셋 이동
        4. MarkdownStyleProcessor.process() → 패턴 감지
        5. applyMinimalEdits() → 버퍼 최소 편집 (IME 보존)
        6. SpanManager.mergeSpans() → 스팬 병합
        7. reanchorHeadingSpans() → 헤딩 스팬 줄 경계 재조정
      ↓
  BasicTextField 렌더링
      ↓ MarkdownEditorOutputTransformation (OutputTransformation)
  SpanStyle 적용 → 화면 표시

[저장]
  MarkdownEditorState.toMarkdown()
      ↓ MarkdownSerializer.toMarkdown(cleanText, spans)
  .md 파일
```

레퍼런스(`HyphenTextState.processInput`)도 동일한 흐름이지만 Undo 스냅샷 저장, pendingOverrides 적용 등이 추가로 포함된다.

---

## 3. 파일별 역할 및 레퍼런스 대응표

### 모델

| MaChum 파일 | 레퍼런스 파일 | 설명 |
|---|---|---|
| `editor/MarkupStyle.kt` | `model/MarkupStyle.kt` | 서식 종류 sealed interface |
| `editor/MarkupStyleRange.kt` | `model/MarkupStyleRange.kt` | 서식 적용 범위 (start, end) |

**주요 차이:**
레퍼런스에는 `Underline`, `CheckboxUnchecked`, `CheckboxChecked`가 있고,
MaChum에는 대신 `WikiLink`, `ExternalLink`가 추가되어 있다.
레퍼런스의 `StyleSets.kt` (allInline/allBlock/allHeadings 목록)는 MaChum에서 `MarkupStyle.kt` 내 확장 프로퍼티(`isHeading`, `isBlock`)로 인라인 처리된다.

---

### 패턴 감지 (MarkdownStyleProcessor)

| | MaChum | 레퍼런스 |
|---|---|---|
| 파일 | `editor/MarkdownStyleProcessor.kt` | `markdown/MarkdownProcessor.kt` |
| 진입점 | `process(rawText, cursorPosition): ProcessResult?` | 동일 시그니처 |
| 반환값 | `ProcessResult(cleanText, spans, cursorPosition)` | `ProcessResult(cleanText, newSpans, newCursorPosition, explicitlyClosedStyles)` |

**구현 방식 차이:**

레퍼런스는 `applyRule()` 내부 함수 하나로 모든 패턴을 처리한다.
정규식 매치를 찾고 → 텍스트 교체 → 기존 extractedSpans를 shiftSpans로 이동 → 새 스팬 추가의 흐름이다.
헤딩을 포함한 모든 규칙이 `applyRule`의 `getPrefixRemoved`/`getSuffixRemoved` 파라미터로 표현된다.

MaChum은 역할별로 세 함수를 분리했다:
- `applyStripping()` — Bold/Italic 등 구분자를 제거하는 인라인 스타일
- `applyHeading()` — `# ` prefix를 제거하고 줄 전체에 스팬 적용
- `applyBlockNoStrip()` — BulletList/Blockquote 등 구분자를 남기고 스팬만 추가
- `applyLinkNoStrip()` — WikiLink/ExternalLink (레퍼런스에 없는 추가 기능)

또한 MaChum은 **매칭 목록을 먼저 수집한 뒤** 역방향으로 처리(`offset` 변수로 보정)하여
루프 내 `text` 수정이 다음 match 위치에 영향을 주는 문제를 방지한다.

레퍼런스의 `explicitlyClosedStyles`(닫힌 스타일 감지 → pendingOverrides 해제)는
MaChum에 아직 없다. pendingOverrides 자체가 미구현이기 때문이다.

---

### 직렬화 (MarkdownSerializer)

| | MaChum | 레퍼런스 |
|---|---|---|
| 파일 | `editor/MarkdownSerializer.kt` | `markdown/MarkdownSerializer.kt` |
| 진입점 | `toMarkdown(cleanText, spans): String` | `serialize(text, spans, start, end): String` |

**구현 방식 차이:**

두 구현 모두 `Insertion` data class를 정의하고, offset 내림차순으로 정렬하여
앞 삽입이 뒤 offset에 영향을 주지 않도록 한다.

레퍼런스는 `priority` 필드를 두어 같은 offset의 중첩 스타일 삽입 순서를 제어한다
(InlineCode=1, Bold=2, Italic=3 순으로 바깥에서 안으로).
MaChum은 `isEnd` Boolean으로 종료 구분자가 먼저 삽입되도록 정렬만 한다.

레퍼런스는 `serialize(text, spans, start, end)` 오버로드로 부분 범위 직렬화(클립보드 복사용)를 지원한다.
MaChum의 `toMarkdown`은 전체 직렬화만 지원한다.

---

### 상태 관리 (MarkdownEditorState / HyphenTextState)

| | MaChum | 레퍼런스 |
|---|---|---|
| 파일 | `editor/MarkdownEditorState.kt` | `state/HyphenTextState.kt` |
| 스팬 저장 | `var _spans by mutableStateOf<List<...>>` | `val _spans = mutableStateListOf<...>()` |

**스팬 저장 방식 차이:**
레퍼런스는 `mutableStateListOf`를 사용해 리스트 내부 원소 변경도 감지된다.
MaChum은 `mutableStateOf<List<...>>`를 사용해 리스트 자체를 교체할 때만 recomposition이 트리거된다.
성능 측면에서 MaChum 방식이 매 입력마다 새 리스트를 생성하므로 GC 부담이 있으나,
입력 처리 흐름이 단순하여 현재는 큰 문제가 없다.

**`processInput` vs `applyInput`:**
레퍼런스의 `processInput(buffer)`는 다음을 추가로 처리한다:
1. `isUndoingOrRedoing` 플래그로 undo 중 재진입 방지
2. `saveSnapshot()` — Undo 스택에 스냅샷 저장 (단어 경계, 붙여넣기, 패턴 완성 시)
3. `applyTypingOverrides()` — pendingOverrides 적용 (타이핑 전에 스타일 지정한 경우)
4. 블록 스타일 필터링: `shifted.filterNot { BlockStyleManager.isBlockStyle(it.style) }`

MaChum의 `applyInput(buffer)`는 이 기능들 없이 핵심 흐름만 구현되어 있다.

**`reanchorHeadingSpans` (MaChum 추가):**
레퍼런스의 `processInput` 말미 `finalSpans` 계산에 동일 로직이 인라인으로 포함되어 있다.
MaChum은 별도 함수로 분리하여 재사용성을 높였다.

**`applyMinimalEdits` (MaChum 추가):**
레퍼런스에는 없는 MaChum 전용 로직.
레퍼런스는 패턴 감지 시 `buffer.replace(0, buffer.length, cleanText)`로 전체 교체한다.
한국어 IME 환경에서 이 전체 교체가 IME 조합 상태를 리셋하여 자모가 분리되는 문제가 발생했다.
MaChum은 공통 접두사/접미사를 계산하여 최소 범위만 `buffer.replace(start, end, "")`로 삭제한다
(순수 삭제이고 커서 앞의 변경일 때만 — 그 외에는 전체 교체 fallback).

---

### 스팬 유틸리티 (SpanManager)

| | MaChum | 레퍼런스 |
|---|---|---|
| 파일 | `editor/SpanManager.kt` | `state/SpanManager.kt` |

**`shiftSpans` 차이:**
레퍼런스: `changeStart >= span.end → span 불변`
MaChum: `changeStart > span.end → span 불변`
`=`를 제거한 이유 — `span.end` 위치에서 타이핑할 때 스팬이 확장되어야 하기 때문.
예: `bold` Bold(0,4)에서 `d` 바로 뒤에 글자를 추가하면 Bold 범위가 늘어나야 한다.

**`mergeSpans` 차이:**
레퍼런스: 블록 스타일(isBlock)은 무조건 기존 스팬을 버리고 새 것으로 교체.
MaChum: Heading은 예외 처리 — 겹치는 줄의 Heading만 교체하고, 다른 줄 Heading은 유지.
이를 통해 H1 줄 아래에 Bold를 입력해도 H1 서식이 유지된다.

**레퍼런스에만 있는 함수:**
- `toggleStyle()` — 선택 범위에 스타일 토글 (툴바 버튼용)
- `applyTypingOverrides()` — pendingOverrides 적용 (빈 커서에서 스타일 지정 후 타이핑)

---

### 자동완성 입력 변환 (EditorInputTransformation)

| 파일 | `editor/EditorInputTransformation.kt` |
|---|---|
| 레퍼런스 대응 | `ui/EditorExtensions.kt`의 `processMarkdownInput()` 함수 |

MaChum의 `EditorInputTransformation`은 `InputTransformation` 인터페이스를 구현한 클래스다.
레퍼런스는 함수 형태(`processMarkdownInput`)로 `BasicTextField`의 `inputTransformation = { ... }` 람다 안에서 호출한다.

지원 변환:

| 입력 | 결과 | 조건 |
|---|---|---|
| `[[` | `[[|]]` | — |
| `![[` | `![[|]]` | `[[`보다 먼저 체크 |
| `**` | `**|**` | 앞·뒤 문자가 `*`가 아닐 때 |
| `~~` | `~~|~~` | 앞·뒤 문자가 `~`가 아닐 때 |
| `==` | `==|==` | 앞·뒤 문자가 `=`가 아닐 때 |
| `` ` `` | `` `|` `` | 앞·뒤 문자가 `` ` ``가 아닐 때 |
| `*` | `*|*` | 앞·뒤 문자가 `*`가 아닐 때 |
| Tab | 스페이스 2칸 | 항상 |

레퍼런스는 여기에 **스마트 Enter** 기능이 추가로 있다:
BulletList, OrderedList, Blockquote, Checkbox 줄에서 Enter를 치면 다음 줄에 자동으로 같은 prefix가 붙고,
내용 없이 Enter를 치면 prefix가 제거된다. 이 로직은 `BlockStyleManager.handleSmartEnter()`에 있다.

---

### 출력 변환 (OutputTransformation)

| | MaChum | 레퍼런스 |
|---|---|---|
| 파일 | `editor/MarkdownEditorOutputTransformation.kt` | `ui/EditorExtensions.kt`의 `applyMarkdownStyles()` |

두 구현 모두 `List<MarkupStyleRange>`를 순회하며 각 스팬에 `SpanStyle`을 적용한다.
MaChum은 `OutputTransformation` 구현 클래스, 레퍼런스는 `TextFieldBuffer` 확장 함수다.

레퍼런스는 `HyphenStyleConfig` data class로 각 스타일의 색상/크기를 외부에서 주입할 수 있다.
MaChum은 `toSpanStyle()` 확장 함수에 하드코딩되어 있다.
**나중에 테마 지원이 필요하면 레퍼런스처럼 StyleConfig를 분리하면 된다.**

---

### 컴포지션 진입점

| | MaChum | 레퍼런스 |
|---|---|---|
| 파일 | `editor/MarkdownTextField.kt` | `ui/HyphenBasicTextEditor.kt` |
| 시그니처 | `MarkdownTextField(value, onValueChange, modifier, textStyle)` | `HyphenBasicTextEditor(state, modifier, ...)` |

MaChum은 상태를 내부에서 `remember`로 생성한다 (`value`/`onValueChange` 방식).
레퍼런스는 상태를 외부에서 `rememberHyphenTextState()`로 생성하여 주입한다 (hoisted state 방식).
hoisted state 방식은 외부(ViewModel 등)에서 `state.toggleStyle(Bold)` 같은 조작이 가능해 툴바 구현에 유리하다.

`ChainedInputTransformation`은 MaChum 전용 클래스.
레퍼런스에서는 `inputTransformation = { processMarkdownInput(state, this) }` 람다 하나로 처리하기 때문에 필요 없다.

---

## 4. MaChum에 없는 레퍼런스 기능 — 구현 위치 안내

나중에 기능을 추가할 때 참고할 파일 위치다.

### Undo / Redo

**레퍼런스:** `state/HistoryManager.kt`

- `EditorSnapshot(text, selection, spans)` — 스냅샷 data class
- `saveSnapshot(snapshot, force)` — 500ms debounce, 최대 50개 유지
- `undo(currentState)` / `redo(currentState)` — 스택 교환
- **트리거 위치:** `HyphenTextState.processInput()` 내 단어 경계/붙여넣기/패턴 완성 시

**MaChum 추가 위치:** `MarkdownEditorState.kt`에 `HistoryManager`를 멤버로 추가하고,
`applyInput()` 내 패턴 완성 시 + `MarkdownTextField.kt`에서 외부 호출용 `undo()`/`redo()` 노출.

---

### 툴바 스타일 토글 (Bold/Italic/Heading 버튼)

**레퍼런스:** `state/HyphenTextState.kt` → `toggleStyle(style)`, `hasStyle(style)`, `clearAllStyles()`
인라인 스타일은 `SpanManager.toggleStyle()`, 블록 스타일은 `BlockStyleManager.applyBlockStyle()`

**MaChum 추가 위치:**
- `SpanManager.kt`에 `toggleStyle()` 함수 추가 (레퍼런스에서 그대로 가져올 수 있음)
- `MarkdownEditorState.kt`에 `toggleStyle(style)`, `hasStyle(style)`, `clearAllStyles()` 추가
- `pendingOverrides: Map<MarkupStyle, Boolean>` 상태 추가 — 커서 위치에서 스타일 지정 후 타이핑 시 적용
- `SpanManager.applyTypingOverrides()` 추가 + `applyInput()` 내 호출

---

### 스마트 Enter (리스트 자동 continuation)

**레퍼런스:** `state/BlockStyleManager.kt` → `handleSmartEnter(state, buffer)`
Enter 감지는 `EditorExtensions.kt`의 `processMarkdownInput()` 내부에서 수행.

**MaChum 추가 위치:** `EditorInputTransformation.kt`의 `transformInput()` 내에서 `'\n'` 감지 후
`BlockStyleManager.handleSmartEnter()` 류의 로직 호출. 또는 별도 `SmartEnterTransformation` 클래스로 분리하고 `ChainedInputTransformation`에 추가.

---

### 클립보드 직렬화

**레퍼런스:** `ui/EditorExtensions.kt` → `rememberMarkdownClipboard(state, label)`
`expect/actual`로 플랫폼별 클립보드를 구현하고, 복사 시 `state.toMarkdown(start, end)`로 직렬화.

**MaChum 추가 위치:**
- `MarkdownSerializer.toMarkdown(cleanText, spans, start, end)` 부분 범위 오버로드 추가
- `editor/EditorExtensions.kt`에 `expect fun rememberMarkdownClipboard()` 선언
- `jvmMain/kotlin/com/ninetag/machum/markdown/editor/EditorExtensionsActual.kt`에 actual 구현

---

### 하드웨어 키보드 단축키

**레퍼런스:** `ui/EditorExtensions.kt` → `handleHardwareKeyEvent(event, state)`
`Modifier.onPreviewKeyEvent { handleHardwareKeyEvent(it, state) }`로 `BasicTextField`에 연결.

**MaChum 추가 위치:** `MarkdownTextField.kt`의 `BasicTextField`에
`modifier = modifier.onPreviewKeyEvent { event -> handleHardwareKeyEvent(event, state) }` 추가 후
`handleHardwareKeyEvent` 함수를 별도 파일 또는 같은 파일에 구현.

---

## 5. 파일 구조 요약

```
markdown/
├── reference_hyphen/          ← 레퍼런스 라이브러리 (com.denser.hyphen)
│   ├── markdown/
│   │   ├── MarkdownConstants.kt   정규식 패턴 정의
│   │   ├── MarkdownProcessor.kt   패턴 감지 → cleanText + spans (MaChum의 MarkdownStyleProcessor)
│   │   └── MarkdownSerializer.kt  cleanText + spans → 마크다운 문자열
│   ├── model/
│   │   ├── MarkupStyle.kt         서식 종류 sealed interface
│   │   ├── MarkupStyleRange.kt    서식 적용 범위 data class
│   │   └── StyleSets.kt           allInline / allBlock / allHeadings 목록
│   ├── state/
│   │   ├── HyphenTextState.kt     핵심 상태 (MaChum의 MarkdownEditorState)
│   │   ├── SpanManager.kt         스팬 변환 유틸리티
│   │   ├── BlockStyleManager.kt   블록 스타일 조작 (스마트 Enter, 리스트 prefix)
│   │   ├── HistoryManager.kt      Undo/Redo
│   │   └── SelectionManager.kt    포커스 잃어도 선택 범위 유지
│   └── ui/
│       ├── HyphenBasicTextEditor.kt   BasicTextField 래퍼 컴포지션
│       ├── HyphenStyleConfig.kt       SpanStyle 테마 설정 data class
│       ├── EditorExtensions.kt        하드웨어 키 처리, OutputTransformation, 클립보드
│       └── material3/HyphenTextEditor.kt  Material3 데코레이터 래퍼
│
└── editor/                    ← MaChum 구현 (com.ninetag.machum.markdown.editor)
    ├── MarkupStyle.kt             서식 종류 (WikiLink/ExternalLink 추가, Underline/Checkbox 없음)
    ├── MarkupStyleRange.kt        서식 범위 data class
    ├── MarkdownConstants.kt       정규식 패턴
    ├── MarkdownStyleProcessor.kt  패턴 감지 (MaChum 전용 applyHeading/applyBlockNoStrip 분리)
    ├── MarkdownSerializer.kt      직렬화
    ├── SpanManager.kt             스팬 변환 (shiftSpans >=→> 수정, mergeSpans Heading 보존 수정)
    ├── MarkdownEditorState.kt     핵심 상태 (applyMinimalEdits, reanchorHeadingSpans 추가)
    ├── MarkdownEditorOutputTransformation.kt  SpanStyle 적용
    ├── MarkdownTextField.kt       BasicTextField 래퍼 (value/onValueChange 방식)
    ├── EditorInputTransformation.kt  auto-close 변환
    └── [미사용 v1 파일들]          MarkdownTextFieldState.kt 등
```

---

## 6. 알려진 버그 및 수정 이력

### Bug 1: 첫 글자에만 서식 적용 (수정 완료)

**원인:** `SpanManager.shiftSpans`에서 `changeStart >= span.end`일 때 스팬을 불변으로 처리했다.
`span.end` 위치에서 입력할 때도 스팬이 확장되지 않아 첫 글자 뒤에 입력한 문자가 서식 밖에 놓였다.

**수정:** `>=` → `>` 변경 (`SpanManager.kt:23`).
`reanchorHeadingSpans()` 추가로 Heading 스팬은 항상 현재 줄 전체를 커버하도록 재조정.

### Bug 2: 한국어 IME 자모 분리 (수정 완료)

**원인:** 패턴 감지 완성 시 `buffer.replace(0, buffer.length, cleanText)`로 전체 교체하면
IME 조합 상태가 리셋되어 현재 조합 중인 자모가 분리된다.

**수정:** `applyMinimalEdits()` 추가 (`MarkdownEditorState.kt`).
공통 접두사/접미사를 계산하여 순수 삭제이고 커서 앞의 변경인 경우만 최소 범위 부분 삭제를 사용한다.

### Bug 3: 다음 줄 서식 입력 시 Heading 소멸 (수정 완료)

**원인:** `SpanManager.mergeSpans`에서 `isBlock` 스팬(Heading 포함)을 무조건 새 스팬으로 교체했다.
다음 줄에서 Bold를 입력하면 `MarkdownStyleProcessor.process()`가 Bold 스팬만 반환하고
Heading이 없으므로 `mergeSpans`가 기존 Heading을 버렸다.

**수정:** Heading 스팬은 `isBlock`이어도 새 Heading과 범위가 겹칠 때만 교체한다.
다른 줄의 Heading은 보존된다 (`SpanManager.kt`의 `mergeSpans` 함수).
