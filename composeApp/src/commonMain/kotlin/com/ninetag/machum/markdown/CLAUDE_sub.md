# 블록 기반 마크다운 에디터 — 설계 및 구현 가이드

> **이 문서는 다른 세션/PC에서 작업을 이어갈 때 참고하는 상세 가이드이다.**
> 현재 진행 상태, 아키텍처, 각 파일의 역할, 남은 작업을 구체적으로 기술한다.

## 0. 문서 작성 지침

이 설계 문서는 **세션과 PC를 넘어 작업 연속성을 보장하기 위한 것**이다.
다음 지침을 따른다:

1. **버그를 수정하거나 기능을 구현하면 즉시 이 문서에 반영한다.**
   - compact.md의 체크리스트 상태 업데이트 (`[ ]` → `[x]`)
   - 이 문서(CLAUDE_sub.md)의 해당 섹션 업데이트 또는 추가
2. **해결된 버그는 "4. 해결된 기술적 이슈" 테이블에 반드시 추가한다.**
   - 증상(어떤 조건에서 무엇이 발생), 근본 원인(왜), 해결(어떤 코드를 어떻게 변경) 3가지를 모두 기술
   - 코드 위치(파일명:줄번호 범위)를 구체적으로 명시
3. **중요한 아키텍처 결정이나 패턴은 "주의사항" 형태로 관련 섹션에 기록한다.**
   - 예: "LazyColumn 아이템 콜백에서 외부 상태를 캡처할 때는 반드시 `rememberUpdatedState`를 사용"
4. **새 세션에서 이 문서만 읽고도 현재 상태를 완전히 파악할 수 있어야 한다.**
   - "위에서 설명한 것처럼", "앞서 언급한" 같은 불명확한 참조 금지
   - 각 항목은 독립적으로 이해 가능하도록 자기 완결적으로 작성

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

---

## 2. 파일 구조 및 역할

### 블록 에디터 (현행 코드)

```
markdown/
├── state/
│   ├── EditorBlock.kt              ← 블록 모델 sealed class + BLANK_LINE_MARKER + toMarkdown()
│   ├── MarkdownBlockParser.kt      ← parse(markdown) → List<EditorBlock> (pendingNewlines 방식)
│   └── BlockOperations.kt          ← 블록 분할/병합/재파싱 (tryReparse 특수블록 우선 포커스)
│
├── ui/
│   ├── MarkdownBlockTextField.kt   ← 공개 API: value/onValueChange + M3 래퍼
│   ├── MarkdownBlockEditor.kt      ← LazyColumn 블록 dispatcher + BlockNavigation + escape 콜백
│   └── TextBlockEditor.kt          ← BasicTextField + 인라인 서식 + 패턴 감지 + ←↑↓ 블록 이동
│
└── ui/block/
    ├── CalloutBlockEditor.kt       ← Callout (Standard ↓↑ / DL ←→, Enter body 생성)
    ├── CodeBlockEditor.kt          ← CodeBlock (monospace, ↑↓ 블록 이동)
    ├── TableBlockEditor.kt         ← Table (2D focusGrid, Tab/Enter 행 추가, +버튼)
    └── HorizontalRuleDivider.kt    ← HR (미사용 — TextBlock 인라인 렌더링으로 전환)
```

### v1에서 재활용하는 파일 (블록 에디터 내부에서 사용)

```
markdown/
├── service/
│   ├── MarkdownStyleConfig.kt      ← 전체 스타일 설정
│   └── util/
│       └── EditorKeyboardShortcuts.kt ← Ctrl+B/I/E 등
├── state/
│   ├── InlineStyleScanner.kt       ← SpanStyle 계산 (HR → blockTransparent)
│   ├── MarkdownPatternScanner.kt   ← 문서 스캔
│   ├── RawMarkdownOutputTransformation.kt ← TextBlockEditor의 OutputTransformation
│   ├── EditorInputTransformation.kt ← Smart Enter, auto-close
│   ├── RawStyleToggle.kt           ← 서식 토글 유틸리티
│   └── MarkdownBlock.kt            ← 블록 타입 정의 (Scanner 내부용)
└── ui/
    └── BlockDecorationDrawer.kt     ← DrawBehind (blockquote 테두리, HR Divider, inline code 배경)
```

### v1 전용 (제거 예정)

```
MarkdownBasicTextField.kt, MarkdownTextField.kt, MarkdownEditorState.kt
OverlayBlockParser.kt, OverlayPositionCalculator.kt, OverlayScrollForwarder.kt
BlockOverlay.kt, CalloutOverlay.kt, CodeBlockOverlay.kt, TableOverlay.kt
InlineOnlyOutputTransformation.kt
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 EditorBlock (`state/EditorBlock.kt`)

- `BLANK_LINE_MARKER` = `"\u200B"` (ZWSP) — Block→Block 사이 빈 줄 표현 마커
- `Text.toMarkdown()`: ZWSP → `""` 치환으로 원본 빈 줄 복원
- `List<EditorBlock>.toMarkdown()`: universal `"\n"` 조인 (예외 없음)
- TextFieldState를 블록 내부에 보유: LazyColumn `key = { block.id }`로 state 유지
- Callout.bodyBlocks: 재귀적 블록 리스트 → 중첩 지원

### 3.2 MarkdownBlockParser (`state/MarkdownBlockParser.kt`)

`pendingNewlines` 카운터로 빈 줄 추적:
- `line.isEmpty()` → `pendingNewlines++`
- 다음 텍스트 줄에서 반영: textAccum 있으면 구분자 `\n` + 빈 줄 `\n`×N, 없으면 빈 줄만
- `flushText()`: textAccum 비어있고 pending 있으면 ZWSP TextBlock 생성 (Block→Block 빈 줄)
- 특수 블록 직전 trailing `\n` 유지 (trimEnd 없음)

줄 단위 블록 감지: ` ``` ` → Code, `> [!TYPE]` → Callout, `|` → Table, `![[]]` → Embed

### 3.3 BlockOperations (`state/BlockOperations.kt`)

- `tryReparse()`: 특수 블록(Callout/Code/Table) 우선 포커스 (`indexOfFirst { !is Text }`)
- `trySplitByEmptyLine()`: `\n\n`으로 TextBlock 분리 (사용자 Enter 2번)
- `mergeWithPrevious()`: TextBlock 병합, 빈 CodeBlock 삭제

### 3.4 MarkdownBlockEditor (`ui/MarkdownBlockEditor.kt`)

`BlockNavigation` 데이터 클래스: `onMoveToPrevious`, `onMoveToNext`, `onMoveLeft`, `onMergeWithPrevious`, `onSplitBlock`, `onSplitByEmptyLine`, `onReparse`

파라미터:
- `onEscapeToPrevious/Next`: 첫/마지막 블록 경계 탈출 콜백
- `onEscapeLeft`: ← 경계 탈출 (Dialogue body → title)
- `firstBlockFocusRequester`: 외부에서 첫 블록 FocusRequester 지정 (Callout body)

**stale 클로저 방지 (`rememberUpdatedState`):**
`BlockWithNav`와 `BlockItem` 내부에서 콜백 클로저가 `blocks`/`index`/`allBlocks`/`blockIndex`를 캡처할 때 `rememberUpdatedState`를 사용한다. LazyColumn이 아이템의 직접 파라미터가 동일하면 recomposition을 skip하므로, 외부 스코프에서 캡처한 변수가 stale 상태로 남을 수 있다. `rememberUpdatedState`는 값이 변경될 때 내부 `State`만 갱신하여, 클로저를 재생성하지 않아도 최신 값을 참조하게 한다. (#18-3 해결)

### 3.5 TextBlockEditor (`ui/TextBlockEditor.kt`)

- 인라인 서식: `RawMarkdownOutputTransformation(applyBlockTransparent=false)`
- 포커스 기반 서식 전환: `remember(styleConfig, isFocused)`로 OT 재생성
- 패턴 감지: `snapshotFlow` + `debounce(150ms)` → `onReparse()`
- 블록 이동: ↑(첫 줄)→prev, ↓(마지막 줄)→next, ←(위치 0)→moveLeft, Backspace(위치 0)→merge

### 3.6 CalloutBlockEditor (`ui/block/CalloutBlockEditor.kt`)

**StandardCallout** (세로 레이아웃):
- Title: `titleFocusRequester` 연결, Enter→body 생성/이동, ↓→body, ↑→이전 블록
- Body: `MarkdownBlockEditor(isNested=true)`, `firstBlockFocusRequester`로 title에서 진입
- `onEscapeToPrevious` → title 포커스, `onEscapeToNext` → 다음 블록

**DialogueCallout** (`> [!DL]`, 대소문자 무관, 가로 레이아웃):
- Title: →(끝)→body, Enter→body 생성/이동, ↑↓→Callout 탈출
- Body: `onEscapeLeft` → title 포커스, `onEscapeToPrevious` → 이전 블록 (title 아님)

공통: `pendingBodyFocus` + `LaunchedEffect(delay 50ms)` — body 생성 직후 지연 포커스

### 3.7 CodeBlockEditor (`ui/block/CodeBlockEditor.kt`)

- Backspace(빈 상태) → merge, ↑(첫 줄) → prev, ↓(마지막 줄) → next

### 3.8 TableBlockEditor (`ui/block/TableBlockEditor.kt`)

- 2D `focusGrid[row][col]` — 첫 셀은 block-level `focusRequester` 사용
- ←→↑↓ 셀 이동, 경계에서 블록 이동
- Tab → 다음 셀 / 마지막 셀이면 행 추가, Enter → 행 삽입
- `pendingFocusRow/Col` + `LaunchedEffect` — 행 추가 후 지연 포커스
- 포커스 시 오른쪽 `+`(열 추가), 아래 `+`(행 추가) 버튼
- `onBlockChanged: (EditorBlock.Table) -> Unit` — 행/열 변경 전파

### 3.9 HorizontalRule 인라인 렌더링

`---`는 TextBlock에 포함. `MarkdownPatternScanner`가 `blockTransparent` 스팬 적용.
비활성 시 `BlockDecorationDrawer.drawHorizontalRule()`, 포커스 시 raw `---` 표시.

---

## 4. 해결된 기술적 이슈

| 이슈 | 해결 |
|---|---|
| 포커스 아웃 서식 미적용 | `remember(styleConfig, isFocused)` OT 재생성 |
| FocusRequester not initialized | id 기반 맵 + `focusRequestCounter` + delay |
| TextBlock 내 블록 서식 깨짐 | `tryReparse()` 자동 분리 |
| 블록 앞뒤 빈 줄 미표시 | pendingNewlines + trailing/leading `\n` 보존 |
| 독립 TextField `"\n"` = 2줄 | ZWSP(`\u200B`) 마커 → 1줄 높이 + toMarkdown 시 `""` 치환 |
| 블록 진입 시 FocusRequester 미연결 | Callout title / Table 첫 셀에 연결 |
| 특수 블록 생성 후 포커스 이탈 | tryReparse에서 `indexOfFirst { !is Text }` 우선 포커스 |
| **#18-3 Callout body 유실** | 아래 상세 설명 참고 |

### #18-3 Callout body 유실 버그 — 해결 기록

**증상:** Callout을 새로 작성하고 body에 내용을 입력한 뒤, 아래에 새 Callout을 작성하면 기존 Callout들의 body가 모두 사라짐. 파일에서 로드된(기존) Callout의 body는 유지됨.

**근본 원인: LazyColumn의 recomposition skip으로 인한 stale 클로저 캡처**

`MarkdownBlockEditor.kt`의 `BlockWithNav` 함수 내 `BlockNavigation` 콜백들이 외부 스코프의 `blocks` (MarkdownBlockEditor 파라미터)를 직접 캡처하고 있었다.

```
1. blocks = [Text, Callout1(body=[]), Text2]  ← BlockWithNav가 compose됨
   Text2의 onReparse 클로저 → blocks 캡처 (Callout1 body 없음)
2. 사용자가 Callout1 title에서 Enter → body 생성
   onBlocksChanged → blocks = [Text, Callout1(body=[Text("hello")]), Text2]
3. MarkdownBlockEditor recomposition → LazyColumn 재평가
   Text2 아이템: 같은 key, 같은 block 참조 → recomposition SKIP
   ★ Text2의 onReparse 클로저는 step 1의 stale blocks를 여전히 캡처
4. 사용자가 Text2에서 > [!NOTE] 입력 → tryReparse 발동
   tryReparse(staleBlocks, 2) → staleBlocks[1] = Callout1(body=[]) ← body 없는 참조!
   newBlocks = [Text, Callout1(body=[]), Callout2] → body 유실
```

**해결: `rememberUpdatedState`로 최신 참조 보장** (`MarkdownBlockEditor.kt`)

`BlockWithNav`에서:
```kotlin
val currentBlocks by rememberUpdatedState(blocks)
val currentIndex by rememberUpdatedState(index)
```
모든 `BlockNavigation` 콜백에서 `blocks`/`index` 대신 `currentBlocks`/`currentIndex`를 사용.

`BlockItem`에서:
```kotlin
val latestAllBlocks by rememberUpdatedState(allBlocks)
val latestBlockIndex by rememberUpdatedState(blockIndex)
```
Callout `onBlocksChanged`와 Table `onBlockChanged` 클로저에서 사용.

`rememberUpdatedState`는 값이 변경될 때 내부 `State`를 갱신하지만, 그 `State`를 읽는 클로저 자체는 재생성하지 않아도 최신 값을 참조한다. 따라서 LazyColumn이 아이템 recomposition을 skip해도 콜백이 항상 최신 `blocks`를 사용한다.

**⚠️ 주의사항 (향후 작업 시):**
LazyColumn 아이템 내에서 외부 상태(`blocks`, `index` 등)를 콜백 클로저에 캡처할 때는
**반드시 `rememberUpdatedState`를 사용**해야 한다. Compose의 LazyColumn은 아이템의
직접 파라미터가 변경되지 않으면 recomposition을 skip할 수 있어, 외부 스코프에서
캡처한 변수가 stale 상태로 남는다.

---

## 5. Phase 진행 상태

### Phase 1: 기본 구조 ✅ 완료

EditorBlock, Parser, toMarkdown, BlockEditor, TextBlock, Callout, Code, Table, HR 인라인, M3 래퍼, EditorPage 연동.

### Phase 2: 블록 간 상호작용 (진행 중)

**완료:**
- #12 BlockOperations 분할/병합
- #13 TextBlock 간 ↑↓ 커서 이동
- #14 TextBlock 재파싱 자동 분리
- #15 TextBlock Backspace 병합
- #16 빈 줄 TextBlock 포함 (pendingNewlines + ZWSP + universal `\n` 조인)
- #17 Callout/Code/Table 간 방향키 이동 + Table 내비게이션/행열 추가
- #18 Callout title ↔ body + Enter body 생성
- #18-1 특수 블록 생성 시 자동 포커스

**남은 작업:**
- ~~#18-2 Table 수정사항 재점검~~ ✅ data row border 수정, Column 래핑, pendingFocus delay 보정
- ~~#18-3 Callout body 유실 버그~~ ✅ — LazyColumn stale 클로저 → `rememberUpdatedState` 적용 (섹션 4 참고)
- **#18-4 CodeBlock: 닫는 ``` 전까지 블록 변환하지 않기** ⬜
- **#18-5 Table: 1줄 `|col|` 입력 시 커서 이탈** ⬜
- **#19 블록 간 이동 시 커서 위치 보정** — ↑→이전 블록 마지막 줄 같은 x, ↓→다음 블록 첫 줄 같은 x. `TextLayoutResult.getHorizontalPosition()` → `getOffsetForPosition()` 사용
- **#20 Smart Enter 블록 단위 확장** — 빈 CodeBlock Enter→탈출, Callout Enter 2회→탈출

### Phase 3: 고급 기능

- #21 Cross-block selection (아래 섹션 6 참고)
- #22 Undo/Redo (아래 섹션 7 참고)
- #23 Embed 블록 렌더링
- #24 v1 코드 제거

---

## 6. Cross-Block Selection (Phase 3, #21)

문서 레벨 가상 selection: `DocumentSelection(startBlockId, startOffset, endBlockId, endOffset)`
시각: 시작 블록 네이티브 selection, 중간 블록 DrawBehind 전체, 끝 블록 DrawBehind 부분.
복사: 각 블록 `toMarkdown()` 범위 추출.

---

## 7. Undo/Redo (Phase 3, #22)

`UndoManager(maxHistory=50)` — `blocks.toMarkdown()` 스냅샷. undo 시 재파싱으로 복원.

---

## 8. EditorPage 연동

```kotlin
MarkdownBlockTextFieldM3(
    value = noteFile.body,
    onValueChange = { pendingMarkdown.value = it },
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
)
```

`key(file.name)`: 파일 전환 시 에디터 리셋. `MutableStateFlow` → `debounce(500ms)` → 파일 저장.
