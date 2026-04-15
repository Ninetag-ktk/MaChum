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

줄 단위 블록 감지 (lookahead로 유효성 확인 후 변환):
- ` ``` ` → 닫는 ``` 존재 시에만 Code 블록 (없으면 TextBlock 유지)
- `> [!TYPE]` → Callout (`excludeCalloutTypes`에 포함된 타입은 텍스트로 유지)
- `|` → 2줄 이상일 때만 Table 블록 (1줄이면 TextBlock 유지, flushText 안 함)
- `![[]]` → Embed

**`excludeCalloutTypes` 파라미터:** `parse(markdown, excludeCalloutTypes)` / `parseLines(lines, excludeCalloutTypes)`.
DL Callout body 파싱 시 `excludeCalloutTypes = setOf("DL") + 부모에서 전달받은 excludes`로 DL 중첩을 방지한다. Standard Callout body에서는 `> [!NOTE]` 등 모든 타입이 허용되지만 DL body 안에서 `> [!DL]`은 텍스트로 유지된다.

### 3.3 BlockOperations (`state/BlockOperations.kt`)

- `tryReparse(blocks, blockIndex, excludeCalloutTypes)`: 특수 블록(Callout/Code/Table) 우선 포커스 (`indexOfFirst { !is Text }`). `excludeCalloutTypes`를 `MarkdownBlockParser.parse()`에 전달하여 편집 중에도 Callout 중첩 제한 적용.
- `trySplitByEmptyLine()`: `\n\n`으로 TextBlock 분리 (사용자 Enter 2번)
- `mergeWithPrevious()`: TextBlock 병합, 빈 CodeBlock 삭제

### 3.4 MarkdownBlockEditor (`ui/MarkdownBlockEditor.kt`)

`BlockNavigation` 데이터 클래스: `onMoveToPrevious`, `onMoveToNext`, `onMoveLeft`, `onMergeWithPrevious`, `onSplitBlock`, `onSplitByEmptyLine`, `onReparse`

파라미터:
- `onEscapeToPrevious/Next`: 첫/마지막 블록 경계 탈출 콜백
- `onEscapeLeft`: ← 경계 탈출 (Dialogue body → title)
- `firstBlockFocusRequester`: 외부에서 첫 블록 FocusRequester 지정 (Callout body)

**포커스 맵 구조 (`bottomEntryFRMap` 도입 예정, #19-callout 리팩토링):**

블록당 진입 방향별 FocusRequester 2개를 지원한다:
- `focusRequesterMap[id]` — 기본 진입점 (↓ 진입). 모든 블록이 등록.
- `bottomEntryFRMap[id]` — ↑ 진입점. Callout만 등록 (body가 있을 때). 미등록 블록은 `focusRequesterMap`으로 fallback.

```
onMoveToNext (↓)    → focusRequesterMap[targetId]
onMoveToPrevious (↑) → bottomEntryFRMap[targetId] ?: focusRequesterMap[targetId]
```

이 구조로 Callout 내부의 `cursorHint` 기반 redirect와 `lastBlockFocusRequester`를 완전히 제거한다.
CalloutBlockEditor는 `onRegisterBottomEntryFR: (FocusRequester?) -> Unit` 콜백으로 body 상태에 따라 FR을 등록/해제한다.

**stale 클로저 방지 (`rememberUpdatedState`):**
`BlockWithNav`와 `BlockItem` 내부에서 콜백 클로저가 `blocks`/`index`/`allBlocks`/`blockIndex`를 캡처할 때 `rememberUpdatedState`를 사용한다. LazyColumn이 아이템의 직접 파라미터가 동일하면 recomposition을 skip하므로, 외부 스코프에서 캡처한 변수가 stale 상태로 남을 수 있다. `rememberUpdatedState`는 값이 변경될 때 내부 `State`만 갱신하여, 클로저를 재생성하지 않아도 최신 값을 참조하게 한다. (#18-3 해결)

### 3.5 TextBlockEditor (`ui/TextBlockEditor.kt`)

- 인라인 서식: `RawMarkdownOutputTransformation(applyBlockTransparent=false)`
- 포커스 기반 서식 전환: `remember(styleConfig, isFocused)`로 OT 재생성
- 패턴 감지: `snapshotFlow` + `debounce(150ms)` → `onReparse()`
- 블록 이동: ↑(첫 줄)→prev, ↓(마지막 줄)→next, ←(위치 0)→moveLeft, Backspace(위치 0)→merge

### 3.6 CalloutBlockEditor (`ui/block/CalloutBlockEditor.kt`)

#### Callout 내비게이션 정책

**블록 진입:**
- ↓(위 블록에서 진입) → **title 맨 앞**에 커서. `focusRequester`는 title에 연결
- ↑(아래 블록에서 진입) → **body 맨 끝**에 커서. `lastBlockFocusRequester`로 body 마지막 블록에 연결. body 없으면 title 맨 끝

**Enter (title에서):**
- body 없음 → body 생성 (빈 TextBlock) + body로 커서 이동
- body 있음 → body 맨 앞으로 커서 이동

**StandardCallout** (세로 레이아웃, `Column`):
| 위치 | 키 | 동작 |
|---|---|---|
| title | ↑ | 이전 블록으로 탈출 |
| title | ↓ | body 있으면 body 맨 앞, 없으면 다음 블록 |
| title | Enter | body 생성/이동 (위 참고) |
| body | ↑(첫 줄, 위치 0) | title로 이동 |
| body | ↓(마지막) | 다음 블록으로 탈출 |

**DialogueCallout** (`> [!DL]`, 대소문자 무관, 가로 레이아웃, `Row`):
| 위치 | 키 | 동작 |
|---|---|---|
| title | ↑ | 이전 블록으로 탈출 |
| title | ↓ | 다음 블록으로 탈출 |
| title | →(맨 끝) | body 있으면 body 맨 앞으로 이동 |
| title | Enter | body 생성/이동 (위 참고) |
| body | ←(위치 0) | title 맨 끝으로 이동 |
| body | ↑ | 이전 줄 있으면 줄 이동, 첫 줄이면 이전 블록으로 탈출 |
| body | ↓ | 다음 줄 있으면 줄 이동, 마지막 줄이면 다음 블록으로 탈출 |

**DL 중첩 정책:**
- DL body 내부에서 Standard Callout (`> [!NOTE]` 등) → **중첩 가능**
- DL body 내부에서 DL (`> [!DL]`) → **중첩 불가** (텍스트로 유지)
- 파서 로딩과 편집 중(tryReparse) 모두 적용
- 구현: `MarkdownBlockParser.parse(text, excludeCalloutTypes=setOf("DL"))`, `BlockOperations.tryReparse(blocks, index, excludeCalloutTypes)`, `MarkdownBlockEditor(excludeCalloutTypes=...)`를 DL body의 MarkdownBlockEditor에 전달

**구현 — `bottomEntryFRMap` 방식:**

Callout 내부 FR 구성:
- `titleFocusRequester` = block-level FR (`focusRequesterMap`에 등록). ↓ 진입 시 항상 title.
- `bodyFocusRequester` = body 첫 블록 FR (`firstBlockFocusRequester`로 전달). title→body 이동용.
- body가 있을 때 `bottomEntryFRMap`에 등록:
  - body 1블록 → `bodyFocusRequester` 등록 (first이자 last)
  - body 2+블록 → 별도 `bodyLastFocusRequester` 등록
- body가 없을 때 `bottomEntryFRMap`에 `titleFocusRequester` 등록 (↑ 진입 시 title 맨 끝)

MarkdownBlockEditor가 ↑ 이동 시 `bottomEntryFRMap`에서 FR을 가져와 직접 포커스.
CalloutBlockEditor 내부의 `LaunchedEffect(cursorHint)` redirect 불필요 → **제거**.
`lastBlockFocusRequester` 파라미터 불필요 → **제거**.

title→body 내부 이동:
- `focusBodyStart()`: `bodyFocusRequester.requestFocus()` + 첫 블록 커서 위치 0 설정
- `pendingBodyFocus` + `LaunchedEffect(delay 50ms)`: body 생성 직후 지연 포커스

**해결된 문제점 (bottomEntryFRMap 도입으로 해결):**
1. ~~`cursorHint` 타이밍 레이스~~ → `bottomEntryFRMap`으로 직접 포커스. cursorHint redirect 제거.
2. ~~`firstBlockFocusRequester`/`lastBlockFocusRequester` FR 충돌~~ → body 1블록일 때 `lastBlockFocusRequester = null`. `bottomEntryFR`으로 `bodyFocusRequester` 직접 등록.
3. ~~FR 과다~~ → titleFR(block-level) + bodyFR(first) + bodyLastFR(2+블록) + bottomEntryFR(부모 등록). cursorHint redirect LaunchedEffect 제거.

**중첩 Callout ↑ 진입 체인:**
`onLastBlockBottomEntryRegistered` 콜백으로 마지막 body 블록이 bottomFR을 등록하면 부모 Callout이 `nestedLastFR`로 캡처하여 자신의 bottomFR로 재등록. depth0~N까지 재귀적으로 전파되어 가장 깊은 body의 TextBlock FR이 최상위까지 도달.

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
| CodeBlock 여는 ``` 만으로 즉시 변환 | 닫는 ``` lookahead 추가. 없으면 TextBlock 유지 (`MarkdownBlockParser.kt`) |
| Table 1줄 `\|col\|` 입력 시 커서 이탈 | flushText를 2줄+ lookahead 후에만 호출 (`MarkdownBlockParser.kt`) |
| Table +버튼 안 보임 | `focusedCellCount` 카운터 방식 → 외부 Column `onFocusChanged { hasFocus }` 방식 |
| Table Tab/행 추가 stale block | `rememberUpdatedState(block)` 적용 (`TableBlockEditor.kt`) |
| Table 상단 빈 줄 Enter 롤백 | `trySplitByEmptyLine`의 `trimEnd()` 제거 + 빈 텍스트면 분리 안 함 (`BlockOperations.kt`) |

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
- **#18-2 Table 수정사항 재점검** — +버튼 공간/높이 ✅. 미해결:
  - Tab 마지막 셀 행 추가 안 됨: `onPreviewKeyEvent`에서 Tab이 정상 소비되지 않는 것으로 추정. Desktop Compose의 Tab 포커스 이동과 충돌 가능. 대안: Tab 대신 다른 키(예: Ctrl+Enter) 또는 `focusProperties { canFocus = false }` 등으로 Tab 포커스 이동 차단 검토
  - 열 추가 시 기존 셀 간격 벌어짐: 모든 셀에 `border(0.5.dp)` 적용 중 → 열 추가 시 인접 셀 border가 중복(0.5+0.5=1dp). 해결 방향: 셀별 border 대신 Row/Column divider 사용, 또는 start/top border만 적용
- ~~#18-6 빈 줄 Enter 롤백~~ ✅ `endsWith("\n\n")` 자동 분리 비활성화. #16(빈 줄 TextBlock 포함)과 충돌하므로 #20 Smart Enter에서 재설계
- ~~#18-3 Callout body 유실 버그~~ ✅ — LazyColumn stale 클로저 → `rememberUpdatedState` 적용 (섹션 4 참고)
- ~~#18-4 CodeBlock: 닫는 ``` 전까지 블록 변환하지 않기~~ ✅ 닫는 펜스 lookahead, 없으면 TextBlock 유지
- ~~#18-5 Table 1줄 입력 시 커서 이탈~~ ✅ 2줄+ lookahead 후에만 flushText + Table 생성
- **#19 블록 간 이동 시 커서 위치 보정** — 부분 완료. 미해결 아래:
  - ✅ Text→Text x좌표 유지 (`CursorHint.AtX` + `getOffsetForPosition`)
  - ✅ Block→Text: ↓ 맨 처음, ↑ 맨 마지막
  - ✅ Callout title→body: body 맨 처음 (`focusBodyStart()`), body→title: title 맨 마지막
  - ✅ **↑로 Code/Callout 진입**: `isFirstLine` 버그 수정 — `sel.start == 0 || lastIndexOf(...) == -1`
  - ✅ **스크롤 보정**: `animateScrollBy(±80f)` → 안 보이면 `animateScrollToItem` fallback
  - ✅ **Callout ↑ 진입 시 body 마지막**: `bottomEntryFRMap` + `onLastBlockBottomEntryRegistered` 체인으로 해결. 중첩 Callout(depth0~N)에서도 가장 깊은 body로 진입
  - ⬜ **soft wrap 줄 이동**: `isFirstLine`/`isLastLine`이 `\n` 기준이라 soft wrap 줄에서 ↑↓ 시 즉시 블록 탈출. `textLayoutResult.getLineForOffset()` 사용 필요
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
