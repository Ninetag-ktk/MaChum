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

### TextBlock 전용 보조 파일 (v1에서 재활용 → v2 전용으로 정리 완료)

```
markdown/
├── service/
│   ├── MarkdownStyleConfig.kt      ← 전체 스타일 설정
│   └── util/
│       └── EditorKeyboardShortcuts.kt ← Ctrl+B/I/E 등
├── state/
│   ├── MarkdownBlock.kt            ← 블록 타입 (Heading/TextBlock/HorizontalRule 3종)
│   ├── InlineStyleScanner.kt       ← TextBlock 인라인 서식 SpanStyle 계산
│   ├── MarkdownPatternScanner.kt   ← TextBlock 콘텐츠 스캔 (BLOCKQUOTE/HORIZONTAL_RULE)
│   ├── RawMarkdownOutputTransformation.kt ← TextBlockEditor의 OutputTransformation
│   ├── EditorInputTransformation.kt ← Smart Enter, auto-close
│   └── RawStyleToggle.kt           ← 서식 토글 유틸리티
└── ui/
    └── BlockDecorationDrawer.kt    ← DrawBehind (blockquote 좌측 바, HR 구분선, inline code 배경)
```

### v1 정리 — 완료

- v1 미사용 파일 12개 삭제 완료
- 재활용 파일 5개의 v1 로직 정리 완료 (총 ~580줄 삭감). 상세는 git log 참조
- TextBlockEditor 의 OT 는 `applyBlockTransparent`/`excludeCalloutTypes`/`activeBlockRanges` 같은 overlay 시절 필드를 모두 제거하고 인라인 서식 + inline code 범위만 다룬다

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
onMoveToNext (↓)    → focusRequesterMap[targetId]                          + cursorHint=Start
onMoveToPrevious (↑) → bottomEntryFRMap[targetId] ?: focusRequesterMap[...]  + cursorHint=End
onMoveLeft (←)       → bottomEntryFRMap[targetId] ?: focusRequesterMap[...]  + cursorHint=End
```

`onMoveLeft (←)` 는 ↑ 와 동일한 진입 의미: 이전 블록의 "마지막 위치" 로 이동. Callout 의 경우 body 끝, body 가 없으면 title 끝. 단순 focus + cursor hint 미설정으로 두면 Callout 의 title 로 가는 버그가 있었음 (이전 잔재 default).

**Callout body 마지막 cursor 강제 (bottomEntry + End):**

`bottomEntryFRMap` 으로 Callout 의 body 마지막 블록 FocusRequester 에 focus 만 요청하면 — 그 블록의 textFieldState 는 **이전 selection 위치 그대로 복원** 된다 (사용자가 이전에 body 첫 줄에 있었으면 거기로). 사용자 의도는 "이전 cursor 위치 무시하고 body 맨 끝으로" 이므로, LaunchedEffect 가 다음 추가 처리:

```kotlin
if (effectiveHint is CursorHint.End && pendingUseBottomEntry && targetBlock is EditorBlock.Callout) {
    val lastText = findDeepestLastText(targetBlock.bodyBlocks)
    lastText?.textFieldState?.edit { selection = TextRange(text.length) }
}
```

`findDeepestLastText` 는 재귀적으로 중첩 Callout 의 body 까지 들어가 가장 깊은 마지막 `EditorBlock.Text` 를 찾는다. body 가 빈 Callout 은 null 반환 (이 경우 title 로 fallback).

이 구조로 Callout 내부의 `cursorHint` 기반 redirect와 `lastBlockFocusRequester`를 완전히 제거한다.
CalloutBlockEditor는 `onRegisterBottomEntryFR: (FocusRequester?) -> Unit` 콜백으로 body 상태에 따라 FR을 등록/해제한다.

**stale 클로저 방지 (`rememberUpdatedState`):**
`BlockWithNav`와 `BlockItem` 내부에서 콜백 클로저가 `blocks`/`index`/`allBlocks`/`blockIndex`를 캡처할 때 `rememberUpdatedState`를 사용한다. LazyColumn이 아이템의 직접 파라미터가 동일하면 recomposition을 skip하므로, 외부 스코프에서 캡처한 변수가 stale 상태로 남을 수 있다. `rememberUpdatedState`는 값이 변경될 때 내부 `State`만 갱신하여, 클로저를 재생성하지 않아도 최신 값을 참조하게 한다. (#18-3 해결)

### 3.5 TextBlockEditor (`ui/TextBlockEditor.kt`)

- 인라인 서식: `RawMarkdownOutputTransformation(styleConfig)` — `isFocused` 만 설정 (overlay 관련 필드 모두 제거됨)
- 포커스 기반 서식 전환: `remember(styleConfig, isFocused)`로 OT 재생성
- 패턴 감지: `snapshotFlow` + `debounce(150ms)` → `onReparse()`. **단 `block.rawMode=true` 일 때는 skip** (dissolve 정책 v3)
- focus-out reparse: `LaunchedEffect(isFocused, block.rawMode)` 에서 `block.rawMode && !isFocused` 일 때 200ms delay 후 `onReparseSilent()` 1회 (dissolve 정책 v3, 섹션 10)
- 빈 raw 자동 해제: `LaunchedEffect(block.rawMode)` 에서 rawMode 인 블록의 텍스트가 빈 순간 `onClearRawMode()` 호출 (rawMode=false 로 플래그만 해제)
- ZWSP 자동 제거: `LaunchedEffect(block.textFieldState)` 에서 text 가 `BLANK_LINE_MARKER` 포함 + length>1 이면 ZWSP 제거 (placeholder → 일반 TextBlock 격하)
- 블록 이동: ↑(첫 줄)→prev, ↓(마지막 줄)→next, ←(위치 0)→moveLeft, →(위치 text.length)→next, Backspace(위치 0)→merge. → 는 ← 와 대칭으로 다음 블록 시작 위치로 진입 (BasicTextField 기본 동작은 블록 경계에서 멈추므로 명시 핸들러 필요). raw 블록의 multi-line 텍스트에서 끝까지 도달 시 다음 블록으로 자연스럽게 넘어감
- **Enter 핸들러 (조건부 활성화)**: 외부 TextBlock 은 박스 UI 가 없어 Smart Enter 탈출 불필요 (BasicTextField 기본 동작 = `\n` 추가). 단 **Callout body 안 TextBlock** 은 `escapeOnEmptyEnter=true` 로 활성화 — 빈 마지막 줄 + Enter → trailing `\n` 제거 + `onMoveToNext` 호출 (조건: `isLastLine && lineStart == lineEnd`). Callout body 마지막 TextBlock 에서 발동 시 `onMoveToNext` → `onEscapeToNext` 체인 → Callout 외부 탈출. 활성화 흐름: `MarkdownBlockEditor.enableEnterEscape: Boolean` 파라미터 → `BlockItem` 이 Text 블록의 `TextBlockEditor` 에 `escapeOnEmptyEnter` 로 전달. Callout 의 body 호출에서만 true
- DrawBehind: `drawBlockDecorations(blocks, config, scrollOffset, inlineCodeRanges, rawZones)` — BLOCKQUOTE 좌측 바 + HR 구분선 + inline code RoundRect 배경. **raw zone 안의 줄은 BLOCKQUOTE 좌측 바를 그리지 않음** (raw 마커가 보이는 상태에서는 좌측 바도 숨김)
- raw zone 결정: `RawMarkdownOutputTransformation.currentRawZones` — `isRawMode=true` (block.rawMode) 면 전체 텍스트, `isFocused=true` 면 커서 줄, 그 외 빈 리스트. dissolve 된 raw TextBlock 의 경우 (Callout/Code dissolve 결과의 `> body` / ` ``` ` 텍스트) 좌측 바가 자연스럽게 숨겨짐

### 3.6 CalloutBlockEditor (`ui/block/CalloutBlockEditor.kt`)

#### 마크다운 문법

```markdown
> [!NOTE] 제목
> 본문

>> [!TIP] 중첩 Callout
>> 본문

> [!DL] 화자명
> 대사 내용
```

- 헤더 형식: `> [!TYPE]` (선택적 공백) + `제목` (선택)
- 본문 줄: `> 내용` (각 줄에 `>` prefix)
- 중첩: `>>` (depth 2), `>>>` (depth 3) 등으로 표시
- Dialogue 타입은 `DL` 로 직렬화 (`> [!DL] 화자명`)
- 대소문자 무관 (`dl`, `DL`, `Dl` 모두 인식). 판별: `equals("DL", ignoreCase = true)`

#### 지원 타입

| 타입 | 배경/테두리 색상 계열 | 비고 |
|---|---|---|
| NOTE | 파랑 | 기본 |
| TIP | 청록 | |
| IMPORTANT | 보라 | |
| WARNING | 주황 | |
| DANGER | 빨강 | |
| CAUTION | 빨강 | DANGER 와 동일 |
| QUESTION | 남색 | |
| SUCCESS | 초록 | |
| DL (Dialogue) | 타입별 | Row 레이아웃 (title + body 가로 배치) |

스타일 매핑: `service/MarkdownStyleConfig.kt` → `calloutDecorationStyle(type)` + `defaultCalloutStyles()`. M3 테마 컬러는 `MarkdownBlockTextField.kt` 의 `defaultMaterialBlockStyleConfig()` 에서 덮어씀.

#### Callout 내비게이션 정책

**블록 진입:**
- ↓(위 블록에서 진입) → **title 맨 앞**에 커서. `focusRequester`는 title에 연결
- ↑(아래 블록에서 진입) → **body 가장 깊은 마지막 Text 의 맨 끝**에 커서. `bottomEntryFRMap` 으로 body 마지막 블록 focus + LaunchedEffect 가 `findDeepestLastText().textFieldState.selection = End` 강제. body 없으면 title 맨 끝
- ←(다음 블록에서 ← 로 진입) → **↑ 와 동일** (body 맨 끝, body 없으면 title 맨 끝). `MarkdownBlockEditor.onMoveLeft` 가 `bottomEntryFRMap` + `cursorHint=End` 사용

**중요 — cursor 위치 강제**: bottomEntry FocusRequester 만 호출하면 이전 selection 그대로 복원되어 사용자 의도와 어긋남(예: 이전에 body 첫 줄에 있었으면 거기로). 따라서 `MarkdownBlockEditor` 의 LaunchedEffect 가 Callout + bottomEntry + End 케이스에 한해 body 의 가장 깊은 마지막 Text 의 selection 을 명시적으로 `text.length` 로 설정한다.

**Enter (title에서):**
- body 없음 → body 생성 (빈 TextBlock) + body로 커서 이동
- body 있음 → body 맨 앞으로 커서 이동

**StandardCallout** (세로 레이아웃, `Column`):
| 위치 | 키 | 동작 |
|---|---|---|
| title | ↑ | 이전 블록으로 탈출 |
| title | ↓ | body 있으면 body 맨 앞, 없으면 다음 블록 |
| title | Enter | body 생성/이동 (위 참고) |
| title | Tab | Enter 와 동일 — body 생성/이동. 명시 핸들러로 가로채어 default focus traversal 우회 |
| title | **Backspace at offset 0** | **`onDissolveSelf` → Callout 자리에 raw TextBlock(rawOrigin=CALLOUT) 으로 풀림 (dissolve 정책 v3, 섹션 10)** |
| body | ↑(첫 줄, 위치 0) | title로 이동 |
| body | ↓(마지막) | 다음 블록으로 탈출 |

**DialogueCallout** (`> [!DL]`, 대소문자 무관, 가로 레이아웃, `Row`):
| 위치 | 키 | 동작 |
|---|---|---|
| title | ↑ | 이전 블록으로 탈출 |
| title | ↓ | 다음 블록으로 탈출 |
| title | →(맨 끝) | body 있으면 body 맨 앞으로 이동 |
| title | Enter | body 생성/이동 (위 참고) |
| title | Tab | Enter 와 동일 — body 생성/이동. 명시 핸들러로 가로채어 default \t 입력 우회 |
| title | **Backspace at offset 0** | **`onDissolveSelf` → Callout 자리에 raw TextBlock(rawOrigin=CALLOUT) 으로 풀림 (dissolve 정책 v3, 섹션 10)** |
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

- Backspace(빈 상태, sel.start=0) → `onMergeWithPrevious` (위 블록과 병합 또는 빈 CodeBlock 단축 삭제)
- ↑(첫 줄) → prev, ↓(마지막 줄) → next
- **Smart Enter 블록 탈출 (#20)**: 빈 마지막 줄에서 Enter → trailing `\n` 제거 + `onMoveToNext` (`isLastLine && lineStart == lineEnd` 조건). 박스 UI 안에 갇혀있으므로 탈출 통로 필요

### 3.8 TableBlockEditor (`ui/block/TableBlockEditor.kt`)

- 2D `focusGrid[row][col]` — 첫 셀은 block-level `focusRequester` 사용
- ←→↑↓ 셀 이동, 경계에서 블록 이동
- Tab → 같은 행 다음 열 이동 / 마지막 열이면 열 추가(`addColumn()`)
- Enter → 같은 열 다음 행 이동 / 마지막 행이면 행 추가(`addRow()`)
- `cellKeyHandler`(`onPreviewKeyEvent`)는 `focusRequester`보다 outer에 배치 — Desktop Compose Tab 가로채기 필요
- `pendingFocusRow/Col` + `LaunchedEffect` — 행/열 추가 후 지연 포커스
- 포커스 시 오른쪽 `+`(열 추가), 아래 `+`(행 추가) 버튼. 비포커스 시 hover/click 비활성화
- 셀 간 구분: 셀별 border 대신 Row/Column 사이 `Box(0.5.dp)` divider, 외부 테두리만 `border(0.5.dp)`
- ↑ 진입(아래 블록에서): `bottomEntryFRMap`에 `focusGrid[lastRow][0]` 등록 → 마지막 행 첫 열로 포커스. `LaunchedEffect(totalRows, colCount)`로 행/열 변경 시 재등록
- `onBlockChanged: (EditorBlock.Table) -> Unit` — 행/열 변경 전파
- `onRegisterBottomEntryFR: (FocusRequester?) -> Unit` — `bottomEntryFRMap` 등록 콜백 (Callout과 동일 메커니즘)

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
| **#18-2 Table 열 추가(+버튼) 동작 안 함** | 아이콘만 조건부 표시. 비포커스 시 비활성화는 사용자 직접 처리 (`TableBlockEditor.kt`) |
| **#18-2 Table 열 추가 시 셀 간격 벌어짐** | 셀 border 제거, Box divider 교체 (`TableBlockEditor.kt`) |
| **#18-2 Table Tab 마지막 열→열 추가** | Tab 분��: `addRow()` → `addColumn()`, 불필요 분기 제거, `cellKeyHandler` outer 배치 (`TableBlockEditor.kt`) |
| **#18-2 Table Enter 마지막 행→행 추가** | Enter 분기: 아래 행 있으면 이동, 없으면 `addRow()`. `insertRowBelow()` 제거 (`TableBlockEditor.kt`) |
| **#19 Table ↑ 진입 시 첫 셀로 이동** | `bottomEntryFRMap`에 `focusGrid[lastRow][0]` 등록. `LaunchedEffect(totalRows, colCount)`로 재등록 (`TableBlockEditor.kt`, `MarkdownBlockEditor.kt`) |
| **#20 Smart Enter 블록 탈출 (정책 정정)** | 박스 UI 가 있는 블록에만 적용. CodeBlock 은 빈 마지막 줄 + Enter → trailing `\n` 제거 + `onMoveToNext`. **TextBlock 은 미적용** — 박스 없으므로 탈출 통로 불필요, ↓ 방향키로 충분. 이전엔 TextBlock 도 적용했으나 ZWSP placeholder / 자동 격하 결과물에서 의도치 않은 탈출이 발생해 핸들러 제거 (`TextBlockEditor.kt`). Callout body 는 마지막 TextBlock 의 ↓ 방향키가 `onEscapeToNext` 체인을 발동시켜 Callout 외부로 이동 |
| **ZWSP placeholder 자동 제거** | Block→Block 빈 줄 마커가 텍스트 시작에 박혀 있으면 `InlineStyleScanner` 의 line prefix 매칭(`# ` heading, `> ` blockquote 등)이 깨짐 (첫 char 가 일반 문자가 아님). `TextBlockEditor` 에 LaunchedEffect 추가 — text 가 `BLANK_LINE_MARKER` 를 포함하고 길이가 1 보다 크면(=사용자가 입력함) 즉시 ZWSP 제거. 빈 줄 placeholder 는 사용자가 내용 추가하는 순간 일반 TextBlock 으로 격하 |
| **Callout ← 진입 시 title 로 가는 버그** | `MarkdownBlockEditor.onMoveLeft` 가 `pendingCursorHint`/`pendingUseBottomEntry` 를 설정하지 않아 default `focusRequesterMap[id] = titleFocusRequester` 로 포커스. ↑ 진입과 동일하게 `pendingCursorHint=End` + `pendingUseBottomEntry=true` 추가 → body 마지막(없으면 title 끝) 으로 포커스 |
| **Embed 텍스트 삭제 후 복원되는 버그** | `BlockItem` 의 Embed 분기가 매 recomposition 마다 `TextFieldState(block.toMarkdown())` 를 새로 생성 → 사용자 입력이 부모로 전파 안 됨. `tempState` 를 `remember(block.id)` 로 보존 + LaunchedEffect 로 사용자 입력 감지 시 같은 id+state 로 `Text(rawMode=true, rawOrigin=EMBED)` promotion. 이후엔 raw Text 의 textFieldState 그대로 사용되어 cursor/focus/입력 모두 보존 |
| **Callout body 진입 시 이전 cursor 위치로 복원** | `bottomEntryFRMap` 으로 body 마지막 블록 FocusRequester 만 호출 → textFieldState 의 이전 selection 그대로 복원 (사용자가 이전에 body 첫 줄에 있었으면 거기로 이동). `MarkdownBlockEditor` LaunchedEffect 에 Callout + bottomEntry + End 케이스 추가 — `findDeepestLastText().textFieldState.selection = TextRange(text.length)` 강제 설정. 중첩 Callout body 까지 재귀 추적 (`MarkdownBlockEditor.kt`) |
| **Embed 삭제 시 cursor 사라짐** | (1) 매 입력마다 promotion 발동 (collectLatest) → onBlocksChanged 반복으로 컴포지션 불안정. (2) promotion 후 BlockItem 의 when 분기 Embed → Text 전환하며 BasicTextField 재생성, 같은 외부 focusRequester 라도 시스템 focus 가 끊김. 해결: `snapshotFlow.filter.first()` 로 단발 처리 + promotion 직후 `delay(50ms) + focusRequester.requestFocus()` 로 focus 명시 재요청 (`MarkdownBlockEditor.kt:BlockItem` Embed 분기) |
| **Embed 변환 비활성화 (현재)** | 박스 UI 미구현(#23) 상태에서는 Embed 변환이 시각적 의미가 없고 focus 끊김(생성 직후) / 빈 잔류(삭제 후) 등 부작용만 발생. `MarkdownBlockParser` 의 `isEmbedLine` 분기 제거 → `![[xxx]]` 는 일반 TextBlock 텍스트로 남음. **EditorBlock.Embed / RawOrigin.EMBED / dissolveSpecial Embed 케이스 / BlockItem Embed 분기 + promotion 로직 모두 보존** — #23 진입 시 parser 한 줄(`isEmbedLine` 분기) 활성화로 복귀 |
| **raw 상태에서 BLOCKQUOTE 좌측 바 표시 (Callout dissolve 시 어색)** | dissolve 된 raw TextBlock(rawOrigin=CALLOUT) 은 텍스트가 `> [!NOTE] 제목\n> 본문` 형태라 `MarkdownPatternScanner` 가 BLOCKQUOTE 로 인식 → 좌측 회색 바 그려짐. raw 마커(`>`) 와 좌측 바가 동시에 보여 시각적 충돌. `RawMarkdownOutputTransformation` 에 `currentRawZones` + `isRawMode` 노출, `drawBlockDecorations` 가 raw zone 안의 줄에서 좌측 바를 skip (`BlockDecorationDrawer.kt`, `RawMarkdownOutputTransformation.kt`, `TextBlockEditor.kt`). raw zone 정의: rawMode=true 면 전체 / focus 받은 줄 / 그 외 비어있음 |
| **#24 v1 레거시 정리** | overlay 시절 코드 ~580줄 삭감. `MarkdownBlock` 6→3 타입, `InlineStyleScanner` callout/code/embed span 제거, `MarkdownPatternScanner` 의 CALLOUT/CODE_BLOCK/TABLE/EMBED 감지 제거, `BlockDecorationDrawer` 의 callout/embed drawer 제거, `RawMarkdownOutputTransformation` 의 overlay/blockTransparent/heightCollapse 로직 제거, `excludeCalloutTypes` 가 OT 까지 흐르던 경로 단절 |
| **#26 dissolve(서식 해제)** v3 구현 | `EditorBlock.Text` 에 `rawMode/rawOrigin` 추가(transient). `BlockOperations.dissolveSpecial/dissolveCallout/DissolveResult` 추가, `tryReparse` 에 rawMode + origin 비교 분기(마커 그대로면 skip). `MarkdownBlockEditor` 에 `CursorHint.AtOffset`, `BlockNavigation.onDissolveSelf`, `applyDissolveResult`. `onMergeWithPrevious` 가 직전이 특수블록일 때 dissolveSpecial 라우팅. `CalloutBlockEditor` Standard/Dialogue title 핸들러에 Backspace at offset 0 분기. **TextBlockEditor 에 rawMode 가드된 트리거 적용**: snapshotFlow 는 rawMode=true 시 skip, focus-out 시 200ms delay 후 reparse 1회. v1 회귀 원인은 focus-out reparse 자체가 아니라 rawMode 가드 누락이었음. 상세는 섹션 10 |
| **Smart Enter `isCurrentLineEmpty` 오판** (TextBlock/Code/Embed) | 기존 `sel.start == lineStart` 만으로는 "내용 있는 줄의 맨 앞"도 빈 줄로 오판되어, 블록 맨 앞 또는 다음 블록 첫 줄 맨 앞에서 Enter 시 빈 줄 추가 대신 블록 탈출이 일어났음. `lineEnd` 를 계산해서 `lineStart == lineEnd` 로 판정 변경. (`TextBlockEditor.kt:129~`, `CodeBlockEditor.kt:57~`. Embed 분기는 `MarkdownBlockEditor.kt:386` 에서 `TextBlockEditor` 재사용 → 자동 적용) |
| **dissolve v3 — focus-out 후 rendering 복귀 안 됨** | `tryReparse` 의 rawMode 분기가 v2 잔재(`if (sameOrigin) return null`) 그대로 남아 focus-out 시 적용을 막았음. v3 트리거 정책에 맞춰 분기를 self-contained 처리로 정정 (마커 살아있음 → 특수 블록 변환 / 마커 깨짐 + 단일 Text → rawMode=false 교체 / 여러 블록 → 분리). (`BlockOperations.kt:172~`) |
| **dissolve v3 — raw 블록의 ``` 가 투명/배경 처리** | `InlineStyleScanner` 의 InlineCode 매칭이 backtick 개수 검사 없이 인접 backtick 두 개를 길이 0 inline code 로 매칭 → marker(투명) 적용 + 길이 0 range 가 `inlineCodeRanges` 에 수집되어 RoundRect 배경 그려짐. fence 가드(연속 3+ backtick 시 skip) + 길이 0 가드(`close > i + 1`) 추가. (`InlineStyleScanner.kt:208~`) |
| **dissolve v3 — focus-out reparse 시 focus 가 새 rendering 블록으로 끌려감** | `applyResult` 가 항상 `pendingFocusBlockId` 설정 → 사용자가 이미 다른 블록으로 옮긴 포커스가 새 Code/Callout/Table 블록으로 강제 이동. `BlockNavigation.onReparseSilent` 콜백 + `applyResult(requestFocus = false)` 옵션 추가. focus-out LaunchedEffect 가 silent 버전을 호출하여 사용자 포커스 위치 보존. (`MarkdownBlockEditor.kt`, `TextBlockEditor.kt`) |
| **Block 유형 state-empty 자동 격하 — 제거 (정책 정정)** | 이전에 `BlockNavigation.onDegradeToText` + `BlockItem` LaunchedEffect 로 Block 유형의 모든 state 가 비면 빈 TextBlock 으로 격하했음. 그러나 사용자가 title 을 잠깐 비운 채 다시 입력하려는 단순 편집 흐름에서도 박스가 사라지는 부작용 발생. **자동 격하의 본래 의도는 raw 블록의 마커 깨짐(`> [!note]` → `> [!note`, `\|---\|` 행 삭제 등) 을 일반 텍스트로 정리하는 것** — 이는 이미 `tryReparse` 의 rawMode 분기가 focus-out 시점에 처리. Block 유형(rawMode=false 박스) 의 state-empty 격하는 잘못된 해석이었으므로 LaunchedEffect 와 `onDegradeToText` 콜백 모두 제거. dissolve 가 필요하면 사용자가 명시적 트리거(title 위치 0 Backspace 등) 를 사용. (`MarkdownBlockEditor.kt`) |
| **dissolve v3 — 빈 raw 블록의 transient 상태** | rawMode=true 인 블록의 텍스트를 다 지우면 시각적으로 일반 TextField 인데 내부 플래그가 살아있어 snapshotFlow 가드가 작동 중인 어색한 상태. `BlockNavigation.onClearRawMode` 콜백 + `TextBlockEditor` 의 LaunchedEffect 가 빈 상태 감지 시 즉시 `block.copy(rawMode=false, rawOrigin=null)` 로 플래그만 해제 (id/textFieldState 유지). (`MarkdownBlockEditor.kt`, `TextBlockEditor.kt`) |
| **Embed dissolve 통합** | `RawOrigin.EMBED` 추가 + `dissolveSpecial` when 에 `is EditorBlock.Embed -> RawOrigin.EMBED`. 이전엔 `else -> return null` 로 빠져 Embed 삭제 경로가 막혀 있었음. 다음 TextBlock 위치 0 Backspace → dissolve → raw `![[xxx]]` → 다 지우면 onClearRawMode → 빈 일반 TextBlock → Backspace 로 위 블록과 병합 (다른 Block 과 동일 패턴). (`EditorBlock.kt`, `BlockOperations.kt`) |
| **Table parser — `\|---\|` 구분자 행 필수화** | 기존 `MarkdownBlockParser` 가 `\|` 로 시작하는 줄 2개 이상이면 무조건 Table 로 인식 (구분자 행 없이도). `EditorBlock.Table.toMarkdown()` 이 항상 `\| --- \|` 자동 생성하므로, dissolve 된 raw Table 에서 사용자가 구분자 행을 지워도 focus-out 후 reparse 시 다시 Table 로 변환되며 자동으로 구분자가 부활하는 회귀가 있었음. parser 의 Table 분기에 "두 번째 줄이 `---` 포함" 조건 추가 → 구분자 없는 raw 는 일반 TextBlock 으로 처리 (`MarkdownBlockParser.kt:154~`) |
| **TextBlock → 방향키로 다음 블록 진입** | `Key.DirectionRight` 핸들러 부재로 BasicTextField 가 cursor 를 `text.length` 에 도달시키면 그대로 멈춤 — 다음 블록으로 넘어갈 통로가 없었음. raw 블록의 multi-line 텍스트(`> [!NOTE] ...\n> body`) 에서 특히 두드러짐. ← 와 대칭으로 `Key.DirectionRight` 핸들러 추가: `sel.collapsed && sel.start == text.length` 면 `navigation.onMoveToNext()` 호출 → 다음 블록 Start 위치로 진입 (`TextBlockEditor.kt`) |

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
- ~~#18-2 Table 수정사항 재점검~~ ✅ 4건 해결:
  - ✅ 열 추가(+ 버튼) 클릭 시 동작 안 함: `clickable`을 항상 적용, 아이콘만 조건부 표시. 비포커스 시 hover/click 비활성화는 사용자가 직접 처리 (`TableBlockEditor.kt`)
  - ✅ 열 추가 시 셀 간격 벌어짐: 셀 border 제거 → Box divider 교체 (`TableBlockEditor.kt`)
  - ✅ Tab 마지막 열에서 열 추가: Tab 분기를 `addRow()` → `addColumn()`으로 수정, 불필요한 다음 행 이동 분기 제거, `cellKeyHandler`를 `focusRequester`보다 outer로 이동 (`TableBlockEditor.kt:156-165`)
  - ✅ Enter 마지막 행에서 행 추가: Tab과 동일한 패턴(아래 행 있으면 이동, 없으면 `addRow()`). `insertRowBelow()` 제거 (`TableBlockEditor.kt:167-176`)
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
  - ✅ **Table ↑ 진입 시 마지막 행 첫 열**: `bottomEntryFRMap`에 `focusGrid[lastRow][0]` 등록. `onRegisterBottomEntryFR` 콜백으로 `BlockItem`에서 전달 (`TableBlockEditor.kt`, `MarkdownBlockEditor.kt`)
  - ⬜ **soft wrap 줄 이동**: `isFirstLine`/`isLastLine`이 `\n` 기준이라 soft wrap 줄에서 ↑↓ 시 즉시 블록 탈출. 단순히 `textLayoutResult.getLineForOffset()` 조건 교체만으로는 해결 안 됨 — `onPreviewKeyEvent`에서 이벤트를 소비하면 BasicTextField 내부의 커서 이동이 차단되어 심각한 사이드이펙트 발생. BasicTextField의 내부 ↑↓ 처리와 블록 탈출 판단을 분리하는 별도 설계 필요
- ~~#20 Smart Enter 블록 탈출 (정책 정정 v2)~~ ✅ — 박스 UI 안에 있는 블록에 한해 적용 (CodeBlock + **Callout body 안 TextBlock**). 외부 TextBlock 은 미적용.
  - **CodeBlock**: 코드 입력 후 Enter(빈 줄 생성) → 빈 마지막 줄에서 Enter → trailing `\n` 제거 + `onMoveToNext` (`CodeBlockEditor.kt:57~`)
  - **Callout body 안 TextBlock**: 빈 마지막 줄 + Enter → trailing `\n` 제거 + `onMoveToNext`. body 안 다음 블록이 있으면 그쪽으로, 마지막 블록이면 `onEscapeToNext` 체인 → Callout 외부 탈출. 활성화 흐름: `CalloutBlockEditor` 가 body `MarkdownBlockEditor` 를 호출할 때 `enableEnterEscape=true` → `BlockItem` 이 Text 블록의 `TextBlockEditor` 에 `escapeOnEmptyEnter=true` 로 전달 → `Key.Enter` 분기 활성
  - **외부 TextBlock 미적용**: 박스 UI 가 없어 탈출 통로 불필요. 다음 블록 이동은 ↓ 방향키로 충분. 이전 a91f994 에서 모든 TextBlock 에 적용했으나 ZWSP placeholder / 자동 격하 결과물에서 의도치 않은 탈출 발생 → 외부 호출에서는 `enableEnterEscape=false` (default) 로 비활성
  - 조건: `sel.collapsed && isLastLine && lineStart == lineEnd`. trailing `\n` 제거: `lineStart > 0` 이면 `edit { replace(lineStart-1, lineStart, "") }` + `onMoveToNext`
  - **`isCurrentLineEmpty` 판정**: `lineStart == lineEnd` (줄의 시작 == 줄의 끝). 이전엔 `sel.start == lineStart` 만 봐서 "내용 있는 줄의 맨 앞"도 빈 줄로 오판하는 버그가 있었음. 수정 후 정상
  - ZWSP 빈 줄 블록은 사용자 입력(텍스트 또는 Enter) 시 자동 제거됨 (섹션 10 ZWSP 자동 제거 정책 참고)
  - 회귀 이력: dissolve v3 작업 중 외부 TextBlock 에서의 부작용을 막기 위해 `Key.Enter` 분기를 제거했었으나, Callout body 의 a91f994 동작도 함께 사라지는 회귀 발생. 위와 같이 `enableEnterEscape` 로 컨텍스트별 분기로 복원

### Phase 3: 고급 기능

- #21 Cross-block selection (아래 섹션 6 참고)
- #22 Undo/Redo (아래 섹션 7 참고)
- #23 Embed 블록 렌더링
- ~~#24 v1 코드 제거~~ ✅ — overlay 시절 죽은 코드 ~580줄 삭감, 6개 파일 정리. 섹션 4 해결된 이슈 테이블 참고
- #25 하드코딩 컬러 M3 테마 적용 (아래 섹션 9 참고)
- #26 dissolve(서식 해제) 동작 — 정책 v3 코드 구현 완료. 수동 검증 대기. 아래 섹션 10 참고

---

## 6. Cross-Block Selection (Phase 3, #21)

**상세 설계는 `SELECTION.md` 로 분리.** 본 섹션은 요약만 유지.

### 6.1 개요

블록 경계를 넘는 selection / 클립보드 / 키보드 네비게이션 도입. 5 phase 로드맵으로 점진 구현.

- **Phase 1 ✅ 완료** (검증 통과): 모델(`DocumentSelection`) + Ctrl+A/C/Esc + Shift+↑/↓ 블록 단위 + body 안 cross-selection + 경계 박스 탈출 + DL Shift+→ + focus 이동 자동 해제 + 마우스 press 해제 + native selection 색 통합
- **Scope A 누적 확장 ✅ 완료** (검증 대기): 최상위 단일 소유 (`documentSelectionShortcuts` 가 Multi 시 Shift+↑/↓ 가로채 `nextFocusEndpoint` 로 focus 누적 이동) → 이전 race 제거. 같은 컨테이너 안에서 연타 확장. 컨테이너 횡단 누적(Scope B)은 보류. `SELECTION.md` 2.5.1
- **Phase 2**: 마우스 드래그 selection + auto-scroll
- **Phase 3**: 잘라내기/붙여넣기 (`Ctrl+X/V`) + selection-replace. **작업량 분석 완료** (`SELECTION.md` 11): 3a deleteSelection+Ctrl+X → 3b Ctrl+V Multi 대체 → 3c 입력 시 대체(리스크 높음). Phase 1 의 미구현(입력 시 replace)은 3c 에서 통합
- **Phase 4**: 글자/단어/줄/페이지 단위 Shift+화살표
- **Phase 5 ✅ 정책 결정 완료**: Table 셀 단위 누적 사각형 selection (엑셀 형식) + CodeBlock atomic 유지. `SELECTION.md` 7.1 참조

### 6.2 핵심 모델 (`DocumentSelection`)

```kotlin
sealed class DocumentSelection {
    data object None : DocumentSelection()
    data class Multi(val anchor: SelectionEndpoint, val focus: SelectionEndpoint) : DocumentSelection()
}

data class SelectionEndpoint(
    val containerPath: List<String>,  // 최상위 → 가장 가까운 컨테이너까지의 id chain
    val blockId: String,
    val offset: Int,
)
```

### 6.3 atomic 정책

- `Text` 외부 — 부분 선택 가능
- `Callout` — 외부 atomic (title+body 통째), 내부 cross-selection 가능 (재귀)
- `Code/Table/Embed/HR` — atomic (Phase 1 한정)

### 6.4 컨테이너 횡단

`Multi.anchor` 와 `focus` 의 `containerPath` 가 달라도 허용. selection 이 컨테이너 밖으로 확장되면 그 Callout 은 atomic 으로 통째 들어감.

상세 (모델 비교 함수, 시각화 정책, clipboard 통합, Phase 별 작업 목록) 은 `SELECTION.md` 참조.

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

---

## 9. 하드코딩 컬러 M3 테마 적용 (Phase 3, #25)

현재 `MarkdownStyleConfig`의 기본값과 `BlockDecorationDrawer`에 하드코딩된 컬러가 있다.
`defaultMaterialBlockStyleConfig()` (`MarkdownBlockTextField.kt`)에서 M3 테마 컬러로 덮어쓰는 항목도 있지만, 누락된 항목이 남아 있다.

**M3에서 이미 덮어쓰는 항목 (변경 불필요):**
- `link`, `highlight`, `codeInline`, `codeInlineBackground`, `codeBlockBackground`
- `calloutStyles` (NOTE/TIP/IMPORTANT 등 8개 타입)

**M3 테마 적용 필요 (하드코딩 → `MaterialTheme.colorScheme` 참조로 변경):**

| 파일 | 항목 | 현재 값 | 방향 |
|---|---|---|---|
| `MarkdownStyleConfig.kt:48` | `bulletPrefix` | `Color(0x66000000)` | `onSurface.copy(alpha=0.4f)` |
| `MarkdownStyleConfig.kt:49` | `orderedPrefix` | `Color(0x66000000)` | `onSurface.copy(alpha=0.4f)` |
| `MarkdownStyleConfig.kt:50` | `blockquoteAccent` | `Color(0xFF9E9E9E)` | `onSurfaceVariant` |
| `MarkdownStyleConfig.kt:55` | `codeBlockBackground` | `Color(0x11000000)` | 이미 M3 덮어쓰지만 기본값도 수정 |
| `MarkdownStyleConfig.kt:67-74` | `defaultCalloutStyles()` | 하드코딩 8색 | 이미 M3 덮어쓰지만 기본값도 수정 |
| `BlockDecorationDrawer.kt:201` | HR 구분선 | `Color(0x33000000)` | `MarkdownStyleConfig`에 `hrColor` 필드 추가, M3에서 `outlineVariant` 등으로 설정 |

**적용 제외:**
- `calloutIndicator` (`Color(0x33000000)`) — 하드코딩 유지

**v1 정리 시 보존된 필드 (참고):**
- `MarkdownStyleConfig.blockTransparent` — `InlineStyleScanner.hideLinePrefix()` 의 blockquote `>` 숨김 + HR span 에 사용 중. v1 잔재로 보였으나 v2 에서도 활용
- `MarkdownStyleConfig.codeBlockBackground` — `CodeBlockEditor.kt` 의 v2 코드블록 배경에 사용 중. v1 잔재로 보였으나 v2 에서도 활용

---

## 10. dissolve(서식 해제) 동작 (Phase 3, #26)

> **상태**: 정책 v3 코드 구현 완료. 사용자 수동 검증(10.9 시나리오) 대기.
> 이 섹션이 단일 source of truth — 다른 PC/세션에서 이 섹션만 보고 작업을 이어갈 수 있어야 함.
>
> **정책 변천 요약**: v1(focus-out 무가드, auto-merge) → 회귀 → v2(focus-out 제거, snapshotFlow 단일 트리거 + origin 비교) → 사용자 의도 재확인 → **v3(rawMode 가드된 focus-out 트리거, snapshotFlow 는 rawMode=true 시 skip)**.
> v3 의 핵심: dissolve 된 블록은 **편집 중에는 절대 rendering 으로 돌아가지 않음**, focus-out 후 200ms delay 가 지나야 마커 검사 → 마커 살아있으면 다시 rendering, 깨졌으면 일반 텍스트.
>
> 진행 체크 (10.7 의 단계 번호와 동일):
> - [x] Step 1: `state/EditorBlock.kt` — `RawOrigin` enum (`CODE/CALLOUT/TABLE/EMBED`) + `Text.rawMode/rawOrigin` 필드 추가
> - [x] Step 2: `state/BlockOperations.kt` — `dissolveSpecial()` (Code/Callout/Table/Embed 모두), `dissolveCallout()`, `DissolveResult` + `tryReparse` rawMode 분기 (focus-out 시점이라 무조건 적용)
> - [x] Step 3: `ui/MarkdownBlockEditor.kt` — `CursorHint.AtOffset`, `BlockNavigation.onDissolveSelf` (dissolveSpecial 통합 라우팅), `onClearRawMode`, `onDegradeToText`, `applyDissolveResult` 헬퍼, `onMergeWithPrevious` dissolve 라우팅. `BlockItem` 자동 격하 LaunchedEffect (Code/Callout/Table)
> - [x] Step 4: `ui/block/CalloutBlockEditor.kt` — Standard/Dialogue title `Backspace at start` → `onDissolveSelf`
> - [x] Step 5: `ui/TextBlockEditor.kt` — rawMode=true 시 snapshotFlow skip + focus-out 200ms delay 후 silent reparse + 빈 raw 즉시 onClearRawMode
> - [x] Step 6: Embed dissolve 통합 (`RawOrigin.EMBED` + `dissolveSpecial` 의 Embed 케이스)
> - [ ] Step 7: 10.9 의 검증 시나리오 수동 테스트
>
> **향후 개선사항 (당장 구현 안 함):**
> - Table 자체에서 dissolve 시작 트리거 (예: 첫 셀 빈 + Backspace at 0 → onDissolveSelf). 자동 격하로 모든 셀이 비면 빈 일반 TextBlock 으로 전환되므로 우선순위 낮음. Callout title 패턴과 일관성 위해 향후 추가 가능
> - soft wrap 줄 이동 (Phase 2 #19) — `textLayoutResult.getLineForOffset()` 기반

### 10.1 배경 및 목표

특수 블록(Code/Callout/Table)을 raw markdown 텍스트로 풀어 다시 일반 텍스트처럼 편집할 수 있는 통로가 필요하다. 현재 Backspace 는 특수 블록 경계에서 작동하지 않거나(Table/Callout title), 빈 블록만 삭제(Code) 한다.

또한 dissolve 결과 TextBlock 을 만들어도 `tryReparse(150ms debounce)` 가 즉시 재변환하므로 무효화된다. dissolve 가 의미 있게 동작하려면 **선택적 재변환 보류**가 필요.

목표:
- 사용자가 명확한 입력(Backspace) 만으로 특수 블록을 raw markdown TextBlock 으로 풀 수 있다
- 풀린 블록의 마커가 살아있는 동안에는 다시 블록으로 합쳐지지 않는다
- 마커가 깨지는 순간 즉시 일반 rendering 으로 돌아간다 (focus-out 기다리지 않음)
- 일반 타이핑 시의 라이브 변환 UX (`> [!NOTE]` 즉시 Callout 변환 등) 는 그대로 유지

### 10.2 결정된 정책 v2 (현행)

#### 정책 트리거 (Backspace)

| 트리거 | 처리 |
|---|---|
| **Code/Callout/Table 다음 TextBlock 의 위치 0 에서 Backspace** | 직전 특수 블록을 dissolve → 1개 TextBlock(`rawMode=true`) (Callout/Table/Code 자리에). **인접 TextBlock 과 merge 하지 않음** |
| **Callout title 위치 0 에서 Backspace** | Callout 만 dissolve → 1개 TextBlock(`rawMode=true`) (Callout 자리에) |
| ~~Callout body 첫 블록 위치 0 Backspace~~ | **dissolve 가 아님.** 기존 `onEscapeToPrevious` (title 로 커서 이동) 유지 |
| ~~빈 특수 블록~~ | **dissolve 가 아님.** 현재 `BlockOperations.mergeWithPrevious()` 의 빈 CodeBlock 삭제 동작 유지 |

> v1(롤백됨) 은 첫 번째 트리거에서 인접 TextBlock 과 auto-merge 했다. v2에서는 **모든 트리거에서 merge 안 함**. 이유는 10.4 참조.

#### dissolve 결과 — `rawMode` + `rawOrigin` 두 필드

- `EditorBlock.Text` 에 두 필드 추가 (둘 다 transient — 직렬화/로드 시 무시):
  ```kotlin
  data class Text(
      override val id: String = generateId(),
      val textFieldState: TextFieldState,
      val rawMode: Boolean = false,
      val rawOrigin: RawOrigin? = null,
  ) : EditorBlock()

  enum class RawOrigin { CODE, CALLOUT, TABLE }
  ```
- dissolve 시 `Text(textFieldState = TextFieldState(specialBlock.toMarkdown()), rawMode = true, rawOrigin = ...)`
- `rawOrigin` 은 reparse 결과가 "원래 dissolve 한 형태와 같은지" 판정하는 데 사용

#### reparse 정책 v3 — 트리거와 마커 검사

**트리거 매트릭스:**

| 블록 상태 | snapshotFlow + 150ms debounce | focus-out + 200ms delay |
|---|---|---|
| `rawMode=false` (일반 TextBlock) | ✅ reparse 발동 (기존 동작) | ❌ 무시 |
| `rawMode=true` (dissolve 결과 raw 블록) | ❌ skip (편집 중 reparse 보류) | ✅ reparse 1회 |

**핵심:** rawMode 가드가 양쪽 트리거 모두에 걸려 있어, 일반 TextBlock 의 Smart Enter / 방향키 이동 시 발생하는 transient focus-out 은 reparse 를 발동시키지 않음. rawMode=true 인 dissolve 블록만 focus-out 시 마커 검사를 받음. transient focus-out (블록간 이동 중 잠깐 잃었다 다시 받는 케이스) 은 200ms delay 안에 isFocused 가 true 로 돌아오면 LaunchedEffect 가 cancel 되어 reparse 발동 안 함.

**Silent 변형 (focus 보존):** focus-out 트리거 reparse 는 `BlockNavigation.onReparseSilent()` 를 호출 (snapshotFlow 트리거는 `onReparse()`). silent 는 `applyResult(result, requestFocus=false)` 로 라우팅되어 새 rendering 블록으로 focus 를 끌어가지 않는다. 사용자는 이미 다른 블록으로 focus 를 옮긴 상태이므로 그 위치를 보존.

#### 두 유형의 정의 (정책 이해의 기반)

| 유형 | 시각 | state 안의 마커 | 자동 변환 |
|---|---|---|---|
| **Block 유형** (Code/Callout/Table/Embed) | 박스 UI (배경/테두리/셀/아이콘) | 마커가 state 에 **없음** — `toMarkdown()` 시 자동 생성 | Block 자체에서는 사용자 입력으로 박스가 사라지지 않음 (마커는 state 가 아니므로) |
| **MarkdownTextField 유형** (TextBlock — rawMode=false 일반 / rawMode=true raw) | 평범한 BasicTextField (박스 없음) | 마커가 textFieldState 의 **텍스트로** 들어가 있음 | 마커 입력/삭제 시 OutputTransformation 이 그 자리에 SpanStyle 자동 적용/해제 |

raw 블록은 시각적으로 MarkdownTextField 유형이지만 내부 플래그 `rawMode=true` 로 편집 중 reparse 차단된 transient 상태.

#### 자동 격하 / 자동 해제 정책

두 유형 사이의 비대칭을 메우기 위해 다음 자동 변환이 적용됨. **격하의 본래 의도는 raw 블록의 마커가 깨졌을 때** (예: Callout dissolve raw 의 `> [!note]` → `> [!note` 로 ] 가 삭제, Table dissolve raw 의 `|---|` 구분자 행 삭제) **일반 텍스트로 정리** 하는 것. Block 유형(rawMode=false 인 박스 UI) 자체에서 state 가 빈 순간을 트리거로 잡으면 사용자가 단순 편집 중에도 박스가 사라지는 문제가 발생하므로 그런 트리거는 두지 않는다.

**1. raw 블록 마커 깨짐 → 자동 격하 (focus-out 트리거, `tryReparse` 의 rawMode 분기)**

dissolve 된 raw TextBlock(`rawMode=true, rawOrigin=...`) 에서 사용자가 마커를 손상시킨 경우, focus-out 시 `BlockOperations.tryReparse` 의 rawMode 분기가 자체 적용:

| 조건 (parsed 결과) | 결과 |
|---|---|
| 마커 살아있음 (특수 블록 1개로 파싱됨) | 그 특수 블록으로 교체 (rendering 복귀) |
| 마커 깨짐 (단일 일반 Text 로 파싱됨) | `rawMode=false` 인 새 Text 로 교체 (자동 해제) |
| 마커 부분 깨짐 (여러 블록으로 파싱됨) | 일반 분리 |

이 흐름이 사용자가 의도한 격하 정책의 본체. focus-out 시점에서만 발동하고, 편집 중에는 `snapshotFlow` 의 rawMode 가드로 reparse 가 차단되므로 박스가 갑자기 사라지지 않음. 상세는 본 섹션 10 의 트리거 매트릭스.

**Block 유형(rawMode=false) 자체의 state-empty 자동 격하는 두지 않음.** 사용자가 title 만 잠깐 비운 채 다시 입력하려는 경우에도 박스가 사라지는 부작용이 있어 제거됨. 빈 박스 자체는 그대로 유지하고, dissolve 가 필요하면 사용자가 명시적 트리거(`title 위치 0 Backspace` 등) 를 사용한다.

**2. raw 블록 자동 해제 (`onClearRawMode`)**

| 조건 | 결과 |
|---|---|
| `rawMode=true` 인 TextBlock 의 `textFieldState.text.isEmpty()` | 같은 블록의 `rawMode=false, rawOrigin=null` 로 플래그만 해제. id/textFieldState 그대로 → cursor/focus 보존 |

raw 블록은 시각적으로 일반 TextField 인데 텍스트까지 비면 transient 상태가 사용자에게 어색함. 즉시 정리. 구현: `TextBlockEditor.kt` 의 LaunchedEffect (`block.rawMode` key + isEmpty 감지).

**3. ZWSP placeholder 자동 제거**

| 조건 | 결과 |
|---|---|
| TextBlock 의 text 가 `BLANK_LINE_MARKER` 를 포함 + length > 1 (사용자가 입력 시작) | text 의 모든 ZWSP 를 제거. textFieldState 는 그대로 (id 보존), 사용자 입력만 남음 |

ZWSP 는 Block→Block 빈 줄을 1줄 높이로 표현하기 위한 마커 (`MarkdownBlockParser.flushText()` 가 자동 생성). 사용자가 ZWSP 블록에 입력을 추가하면 그 자리는 더 이상 "빈 줄 placeholder" 가 아닌 일반 TextBlock 이므로 ZWSP 를 제거한다. 제거하지 않으면 줄 시작에 ZWSP 가 박혀 있어 `InlineStyleScanner` 의 line prefix 매칭(`# `, `> `, `- ` 등)이 깨진다. 구현: `TextBlockEditor.kt` 의 LaunchedEffect (snapshotFlow + filter).

**4. Embed 사용자 편집 시 자동 promotion (`BlockItem` 의 Embed 분기) — 현재 도달 안 됨**

> **상태**: 코드는 보존되어 있으나 현재 Embed 변환이 비활성화되어 있어 도달하지 않음. 향후 Phase 3 #23 (Embed 박스 UI 렌더링) 구현 시 parser 의 `isEmbedLine` 분기를 활성화하면 이 promotion 로직이 다시 사용됨.

Embed 는 `target: String` 만 가진 readonly 토큰이지만 자체 박스 UI 가 없어 (현재) `BasicTextField` 로 raw markdown(`![[target]]`) 을 표시한다. 사용자가 그 텍스트를 편집하려고 하면 — 매번 새 `TextFieldState` 가 생성되어 입력이 부모로 전파되지 않고 **다음 recomposition 때 원래 raw 로 복원**되는 버그가 있었다.

해결: `BlockItem` 의 Embed 분기에서 `tempState` 를 `remember(block.id)` 로 보존 + `LaunchedEffect` 로 사용자 입력 감지 시 즉시 raw TextBlock 으로 promotion. **단발 처리 + focus 보존** 필수:

- **단발 처리**: `snapshotFlow { ... }.filter { it != original }.first()` 로 첫 변경만 받고 LaunchedEffect 종료. `collectLatest` 로 매 입력마다 promotion 호출하면 onBlocksChanged 가 반복 발동되어 cursor 가 깨짐
- **focus 보존**: promotion 직후 `focusRequester.requestFocus()` 명시 호출 (`delay(50.ms)` 후). promotion 으로 BlockItem 의 when 분기가 Embed → Text 로 전환되며 BasicTextField 가 재생성되는데, 같은 외부 `focusRequester` 를 재요청해야 cursor 가 화면에 유지됨

| 조건 | 결과 |
|---|---|
| `Embed` 블록의 `tempState.text` 가 원본(`block.toMarkdown()`)과 달라진 순간 | 같은 블록 자리에 `EditorBlock.Text(id=block.id, textFieldState=tempState, rawMode=true, rawOrigin=EMBED)` 로 교체. cursor/focus 그대로 보존 |

이후 사용자는 raw TextBlock 으로 자유롭게 편집. focus-out 시 마커 살아있으면 다시 Embed 로 복귀, 깨지면 일반 텍스트, 빈 상태면 `onClearRawMode` 로 자동 해제.

**삭제 흐름**:
1. 사용자가 Embed 안에서 raw 마커(`![[`, `]]`) 또는 target 의 일부를 지움 → `tempState.text` 변경 감지 → 자동 promotion (raw Text)
2. raw 텍스트를 모두 지움 → `onClearRawMode` → 빈 일반 TextBlock
3. Backspace at 0 → `mergeWithPrevious` 로 위 블록과 병합 → 사라짐

**마커 검사 (focus-out 후 발동된 `tryReparse` 가 rawMode=true 블록에 대해 수행):**

reparse 호출 시점이 focus-out 뿐이므로 (snapshotFlow 는 rawMode 가드로 차단) **무조건 적용**한다. 결과 종류에 따라 처리만 다름:
- **마커 살아있음** (parsed = 단일 특수 블록, origin 무관) → 특수 블록으로 변환 (rendering 복귀). raw TextBlock 자리에 Code/Callout/Table 1 개로 교체
- **마커 깨짐 + 단일 일반 텍스트** → rawMode=false 인 새 Text 로 교체 (자동 해제). 시각상 동일하지만 플래그가 false 가 되어 이후 snapshotFlow 가 다시 작동
- **마커 깨짐 + 여러 블록** → 일반 분리 흐름 (Code + Text 등으로 splat)

⚠️ origin 비교는 하지 않는다. v2 의 sameOrigin skip 은 snapshotFlow 트리거 시 raw 유지 목적이었으나, v3 에서는 트리거 자체가 focus-out 이라 `return null` 하면 영원히 raw 로 남는다.

#### 커서 위치

dissolve 후 새 TextBlock 의 커서는 **raw markdown 의 끝** (Backspace 위치 근사). auto-merge 가 없으므로 단순.

### 10.3 트리거별 입출력 매핑 (도식)

#### 트리거 1 — Code 다음 TextBlock 위치 0 Backspace (auto-merge 없음)

**Before**
```
[Text]  "윗 텍스트"
[Code]  lang=kotlin, body=foo\nbar
[Text]  "다음 텍스트"     ← 위치 0 Backspace
```

**After**
```
[Text]                                "윗 텍스트"
[Text rawMode=true rawOrigin=CODE]    "```kotlin\nfoo\nbar\n```"
                                                              ↑ 커서 (raw 끝)
[Text]                                "다음 텍스트"
```

Callout/Table 도 동일 패턴. 다음 TextBlock 은 그대로 보존.

#### 트리거 2 — Callout title 위치 0 Backspace

**Before**
```
[Text]    "윗 텍스트"
[Callout] type=NOTE, title="제목"     ← title 위치 0 Backspace
          body=[Text "본문"]
[Text]    "아래 텍스트"
```

**After**
```
[Text]                                  "윗 텍스트"
[Text rawMode=true rawOrigin=CALLOUT]   "> [!NOTE] 제목\n> 본문"
                                                          ↑ 커서
[Text]                                  "아래 텍스트"
```

중첩 Callout 의 부모 dissolve 시 자식도 raw markdown 의 일부로 풀려 나옴 (`Callout.toMarkdown()` 이 재귀). 위/아래 블록은 그대로.

### 10.4 정책 v1 → v2 → v3 변경 이유

#### 변경 1. auto-merge 제거 (v1 → v2)

v1 동작:
```
[Text rawMode=true] "```kotlin\nfoo\n```\n다음 텍스트"     ← raw 와 다음 텍스트가 한 블록
```

문제: 사용자가 raw 를 편집하다가 fence 만 지우고 일반 텍스트로 돌리고 싶을 때, "다음 텍스트" 와의 경계가 모호. 빈 줄 처리가 예측 불가능.

v2/v3 동작:
```
[Text rawMode=true] "```kotlin\nfoo\n```"
[Text]              "다음 텍스트"
```

장점: raw 블록과 다음 텍스트가 명확히 분리됨. fence 깨지는 영향이 raw 블록 안으로 한정.

#### 변경 2. v1 의 무가드 focus-out reparse → v2 (focus-out 제거) → v3 (rawMode 가드된 focus-out)

**v1 의 잘못된 동작**: `onFocusChanged` 에서 rawMode 와 무관하게 모든 TextBlock 에서 focus-out 시 `navigation.onReparse()` 발동. Smart Enter / 방향키로 다음 블록 생성·이동하면 직전 블록이 transient focus-out → reparse 발동 → stale 텍스트 기반 분리 → `pendingFocusBlockId` 충돌. 회귀 증상: "블록 사이 Enter 입력이 제대로 작동하지 않음."

**v2 의 과교정**: focus-out 트리거 자체를 제거하고 snapshotFlow + `tryReparse` 의 origin 비교로 일원화. 사용자 의도를 잘못 옮긴 부분: "마커 깨자마자 즉시 rendering 을 원함." 실제 사용자 의도는 **"편집 중에는 절대 rendering 안 됨, focus-out 후에야 rendering"**.

**v3 의 정정**: focus-out 트리거를 다시 도입하되 다음 두 가지 가드를 추가하여 v1 회귀를 차단.
1. **rawMode 가드**: `if (block.rawMode && !isFocused)` — rawMode=false 인 일반 TextBlock 에는 focus-out reparse 가 발동하지 않음. Smart Enter / 방향키 이동 시 발생하는 transient focus-out 영향 없음.
2. **delay 가드**: focus-out 후 즉시가 아니라 200ms delay 후 reparse. `LaunchedEffect(isFocused, block.rawMode)` 의 key 변경이 코루틴을 cancel 하므로, delay 안에 다시 focus 받으면 reparse 발동 안 함. 짧은 transient focus-out 자동 무시.

추가로 snapshotFlow 트리거도 v3 에서는 `if (block.rawMode) return@LaunchedEffect` 가드를 둬 편집 중 reparse 를 완전 차단.

### 10.5 v1 시도 기록 (롤백됨, 참고용)

다음 변경을 가했으나 회귀 발생 → `git checkout HEAD --` 와 수동 Edit 으로 모두 되돌림. v3 에서 회귀 재발 방지를 위해 기록 보존:

| 파일 | v1 변경 내용 |
|---|---|
| `state/EditorBlock.kt` | `Text` 에 `rawMode: Boolean = false` 추가 (rawOrigin 없음) |
| `state/BlockOperations.kt` | `dissolveAndMergeWithNext()` (auto-merge 포함), `dissolveCallout()`, `DissolveResult` 추가 |
| `ui/MarkdownBlockEditor.kt` | `CursorHint.AtOffset`, `BlockNavigation.onDissolveSelf`, `applyDissolveResult`, `onMergeWithPrevious` 라우팅 |
| `ui/TextBlockEditor.kt` | `block.rawMode` 시 snapshotFlow skip + **`onFocusChanged` 에서 rawMode 가드 없이 포커스 아웃 시 `navigation.onReparse()` 1회** ← 회귀 원인 |
| `ui/block/CalloutBlockEditor.kt` | Standard/Dialogue 양쪽 title `onPreviewKeyEvent` 에 Backspace at start → `onDissolveSelf` |

회귀 증상: "블록 사이 엔터 입력이 제대로 작동하지 않음."

**회귀 원인 재해석 (v3 진단):** v1 의 focus-out reparse 가 `rawMode` 가드 없이 모든 TextBlock 에 적용된 것이 진짜 원인. Smart Enter / 방향키 이동 시 직전 TextBlock 이 잠깐 focus-out 되며 reparse 가 발동, stale 한 텍스트로 분리되며 `pendingFocusBlockId` 충돌. **focus-out 트리거 자체가 문제는 아니다 — 가드 누락이 문제였다.** v3 는 `if (block.rawMode && !isFocused)` 가드 + 200ms delay 로 transient focus-out 을 자연스럽게 거른다.

### 10.6 영향도 (v3)

| 영역 | 영향 |
|---|---|
| 일반 TextBlock 입력 흐름 | 영향 없음 (rawMode=false 면 기존 흐름 그대로). focus-out reparse 는 rawMode 가드로 차단됨 |
| 블록 사이 Enter 이동 / 방향키 이동 | 영향 없음 (rawMode=false 라 transient focus-out 이 reparse 발동 안 함) |
| `BlockOperations.tryReparse` | rawMode 분기 ~10줄 추가. rawMode=false 경로 변경 없음 |
| `EditorBlock.Text` 모델 | `rawMode`, `rawOrigin` 두 필드 추가 (default null/false → 기존 호출처 호환) |
| `BlockOperations` 새 함수 | `dissolveSpecial()`, `dissolveCallout()`, `DissolveResult` |
| `MarkdownBlockEditor` | `BlockNavigation.onDissolveSelf`, `applyDissolveResult`, `CursorHint.AtOffset`, `onMergeWithPrevious` 라우팅 |
| `TextBlockEditor` | **변경 있음** — snapshotFlow `if (block.rawMode) return` 가드 + `LaunchedEffect(isFocused, block.rawMode)` 에서 200ms delay 후 reparse |
| `CalloutBlockEditor` | Standard/Dialogue title 핸들러에 Backspace 분기 1개씩 |
| 회귀 표면적 | v1 대비 매우 작음 — focus-out reparse 가 rawMode 가드 + delay 가드로 이중 차단 |

#### Cross-block selection (#21) 와의 호환

rawMode 와 cross-select 는 **직교 관계**. 영향 없음:
- `TextFieldState`, `TextLayoutResult`, `toMarkdown()` 모두 rawMode 무관 동작
- 시각 selection: 줄 높이/너비는 LayoutResult 가 정확히 계산
- 복사: `Text.toMarkdown()` = textFieldState.text → raw markdown 그대로 (사용자가 보고 있던 raw 가 그대로 클립보드)
- selection 도중 reparse 발동 시 깨질 위험은 rawMode 만의 문제가 아니라 cross-select 자체에서 다뤄야 할 일반 이슈

#### Undo/Redo (#22) 와의 호환

- `blocks.toMarkdown()` 스냅샷 사용 → rawMode/rawOrigin 보존 안 됨 (transient)
- Undo 결과는 일반 블록. 마커가 그대로면 reparse 가 다시 Code/Callout/Table 로 변환
- "dissolve 한 transient 상태 자체" 는 undo 대상 아님 — 받아들일 만한 동작

#### Embed (#23) / M3 컬러 (#25) 와의 호환

- Embed 도 동일 패턴 적용 가능 (`RawOrigin.EMBED` 추가)
- M3 컬러 무관

### 10.7 구현 단계 (v2 — 재시도 시 그대로 따를 것)

1. **`state/EditorBlock.kt`**:
   - `RawOrigin` enum 정의 (`CODE`, `CALLOUT`, `TABLE`)
   - `Text` 에 `rawMode: Boolean = false`, `rawOrigin: RawOrigin? = null` 필드 추가
   - `toMarkdown()` 은 변경 없음 (둘 다 transient)

2. **`state/BlockOperations.kt`**:
   - `data class DissolveResult(newBlocks: List<EditorBlock>, targetBlockId: String, cursorOffset: Int)` 추가
   - `fun dissolveSpecial(blocks, specialIndex): DissolveResult?` — Code/Callout/Table 자리에 `Text(rawMode=true, rawOrigin=...)` 1개로 교체. 인접 블록은 보존
   - `fun dissolveCallout(blocks, calloutIndex): DissolveResult?` — Callout 만 같은 패턴
   - `tryReparse` 에 rawMode 분기 추가 (v3 — 무조건 적용):
     ```kotlin
     if (block.rawMode) {
         val newBlocks = blocks.toMutableList()
         // 마커 살아있음 → 특수 블록으로 변환 (rendering 복귀)
         if (parsed.size == 1 && parsed[0] !is EditorBlock.Text) {
             newBlocks[blockIndex] = parsed[0]
             return SplitResult(newBlocks, focusBlockIndex = blockIndex)
         }
         // 마커 깨짐 + 단일 Text → rawMode=false 인 새 Text 로 교체 (자동 해제)
         if (parsed.size <= 1 && parsed.firstOrNull() is EditorBlock.Text) {
             newBlocks[blockIndex] = parsed[0]
             return SplitResult(newBlocks, focusBlockIndex = blockIndex)
         }
         // 마커 깨짐 + 여러 블록 → 일반 분리
         newBlocks.removeAt(blockIndex)
         newBlocks.addAll(blockIndex, parsed)
         val specialIdx = parsed.indexOfFirst { it !is EditorBlock.Text }
         val focusIdx = blockIndex + if (specialIdx >= 0) specialIdx else parsed.lastIndex
         return SplitResult(newBlocks, focusBlockIndex = focusIdx)
     }
     // rawMode=false 일반 흐름 (단일 Text 면 변경 없음 가드 유지)
     ```

3. **`ui/MarkdownBlockEditor.kt`**:
   - `CursorHint.AtOffset(offset: Int)` variant 추가
   - `LaunchedEffect(focusRequestCounter)` 에 AtOffset 처리 분기 추가:
     ```kotlin
     is CursorHint.AtOffset -> {
         if (targetBlock is EditorBlock.Text) {
             val state = targetBlock.textFieldState
             state.edit { selection = TextRange(effectiveHint.offset.coerceIn(0, state.text.length)) }
         }
     }
     ```
   - `BlockNavigation.onDissolveSelf: () -> Unit = {}` 추가
   - `applyDissolveResult(result: DissolveResult?)` 헬퍼 추가:
     - `onBlocksChanged(result.newBlocks)` + `pendingFocusBlockId = result.targetBlockId` + `pendingCursorHint = CursorHint.AtOffset(result.cursorOffset)` + `focusRequestCounter++`
   - `onMergeWithPrevious` 라우팅:
     ```kotlin
     onMergeWithPrevious = {
         val merged = BlockOperations.mergeWithPrevious(currentBlocks, currentIndex)
         if (merged != null) {
             applyResult(merged)
         } else {
             val prev = currentBlocks.getOrNull(currentIndex - 1)
             if (prev != null && prev !is EditorBlock.Text && prev !is EditorBlock.HorizontalRule) {
                 applyDissolveResult(BlockOperations.dissolveSpecial(currentBlocks, currentIndex - 1))
             }
         }
     }
     ```
   - `onDissolveSelf` 결선:
     ```kotlin
     onDissolveSelf = {
         applyDissolveResult(BlockOperations.dissolveCallout(currentBlocks, currentIndex))
     }
     ```

4. **`ui/TextBlockEditor.kt`** (v3 의 핵심):
   - snapshotFlow gating: `LaunchedEffect(block.textFieldState, block.rawMode) { if (block.rawMode) return@LaunchedEffect; ... }`
   - focus-out reparse: 별도 `LaunchedEffect(isFocused, block.rawMode) { if (block.rawMode && !isFocused) { delay(200ms); navigation.onReparse() } }`
   - **`if (block.rawMode)` 가드 필수** — 가드 없이 적용하면 v1 회귀 재발 (Smart Enter / 방향키 이동 중 transient focus-out 으로 일반 TextBlock 도 reparse 됨)

5. **`ui/block/CalloutBlockEditor.kt`**: Standard/Dialogue 양쪽 `titleKeyHandler` 에 Backspace 분기 추가:
   ```kotlin
   Key.Backspace -> {
       if (sel.collapsed && sel.start == 0) {
           navigation.onDissolveSelf()
           true
       } else false
   }
   ```

### 10.8 dissolve 가 **아닌** 케이스 (회귀 방지용 명시)

| 케이스 | 동작 |
|---|---|
| Callout body 첫 블록 위치 0 Backspace | 기존 `onEscapeToPrevious` → title 로 커서 이동 |
| 빈 CodeBlock + 위 TextBlock 위치 0 Backspace | 기존 `mergeWithPrevious()` → 빈 CodeBlock 삭제 |
| Table 셀 내부 Backspace | 셀 내 텍스트 편집 (dissolve 아님) |
| 일반 TextBlock 위치 0 Backspace | 기존 `mergeWithPrevious()` → 두 TextBlock merge |
| Callout title 위치 0 Backspace 인데 Callout 위에 블록이 없는 경우 | 트리거 2 그대로 적용 — Callout 자리에 raw TextBlock |
| rawMode 블록에서 Enter / 방향키 / 일반 입력 | snapshotFlow + debounce 흐름. tryReparse 가 origin 비교로 skip/적용 |

### 10.9 검증 시나리오 (재시도 시 1순위 테스트, v3 트리거 기준)

회귀 방지를 위해 dissolve 변경 직후 다음 시나리오를 우선 검증:

1. **블록 사이 Enter 이동** (v1 회귀 항목 — rawMode 가드 검증)
   - TextBlock A 에서 마지막 줄 빈 + Enter → Smart Enter 로 다음 블록 B 생성·이동 → B 에 입력
   - 기대: B 에 입력한 내용이 정상 반영, A 는 trailing `\n` 제거된 상태로 stable. transient focus-out 이 발생하지만 rawMode=false 라 reparse 미발동
2. **dissolve 직후 그 블록을 계속 편집**
   - Code 다음 TextBlock 위치 0 Backspace → raw TextBlock(rawMode=true) 생성
   - 포커스 유지하면서 본문(`foo` 부분) 을 자유롭게 수정 → snapshotFlow 가 rawMode 가드로 skip → **편집 중에는 절대 다시 Code 로 변환되지 않음**
3. **dissolve 후 마커 그대로 두고 focus-out → 다시 rendering 복귀**
   - 위 raw TextBlock 에서 fence 는 그대로 두고 본문만 수정 → 다른 블록 클릭 (focus-out)
   - 200ms delay 후 reparse → parsed = [Code] → **다시 Code 블록으로 변환 (rendering 복귀)**, 수정한 본문 반영
4. **dissolve 후 fence 깨고 focus-out → 일반 텍스트로 자동 해제**
   - raw TextBlock 에서 closing ` ``` ` 삭제 → 다른 블록 클릭 (focus-out)
   - 200ms delay 후 reparse → parsed = [Text] (단일) → **rawMode=false 인 새 Text 로 교체** (시각상 동일, 플래그만 해제)
   - 그 후 같은 블록에서 패턴 입력 → snapshotFlow 정상 동작 (rawMode 가드 해제됨)
5. **transient focus-out (블록 간 짧은 이동)**
   - dissolve 된 raw 블록에 포커스 → 다른 블록을 잠깐 클릭 → 200ms 안에 다시 raw 블록으로 돌아옴
   - 기대: `LaunchedEffect(isFocused, ...)` 가 cancel → reparse 미발동. raw 모드 그대로 유지
6. **Callout title dissolve**
   - Callout title 위치 0 Backspace → Callout 자리에 `> [!NOTE] 제목\n> 본문` raw TextBlock(rawOrigin=CALLOUT). 위/아래 블록 그대로. focus-out 시 마커 살아있으면 다시 Callout 으로 rendering
7. **빈 CodeBlock 위 TextBlock 위치 0 Backspace**
   - dissolve 아님. 빈 CodeBlock 삭제 + 위 TextBlock 으로 포커스 (기존 동작 유지)
8. **Callout body 첫 블록 위치 0 Backspace**
   - dissolve 아님. title 로 커서 이동 (기존 onEscapeToPrevious 유지)
