# Markdown Engine & Editor — 설계 문서

이 문서는 `markdown/` 패키지의 파서·렌더러 엔진과, 이를 사용하는 `MarkdownTextField` 컴포지션의 요구사항·설계·구현 계획을 정리한다.

---

## 목표

BasicTextField처럼 단독으로 사용 가능한 **Live Preview 마크다운 편집 컴포지션** 제공.

- 커서가 위치한 블록 → 마크다운 문법 기호 표시 (편집 가능)
- 나머지 블록 → 서식이 적용된 결과 표시
- 두 상태가 동시에 화면에 보임 (모드 전환 아님)

---

## 지원 문법

### 현재 지원 (SpanStyle 기반)

| 문법 | 구현 상태 |
|---|---|
| Heading `#` ~ `######` | ✅ 파서/렌더러 완료 |
| TextBlock (일반 텍스트) | ✅ 완료 |
| HorizontalRule `---` / `***` / `___` | ✅ 완료 |
| Bold `**` / `__` | ✅ 완료 |
| Italic `*` / `_` | ✅ 완료 |
| Strikethrough `~~` | ✅ 완료 |
| Highlight `==` | ✅ 완료 |
| InlineCode `` ` `` | ✅ 완료 |
| WikiLink `[[파일명\|별칭]]` | ✅ 완료 |
| ExternalLink `[텍스트](URL)` | ✅ 완료 |
| BulletList `-` / `*` | ✅ 완료 |
| OrderedList `숫자.` | ✅ 완료 |
| Blockquote `>` | ✅ 완료 |

### SpanStyle 한계로 미지원 (추후 별도 처리 — 상세 설계는 CLAUDE_sub.md 참고)

| 문법 | 우선순위 | 비고 |
|---|---|---|
| Callout `> [!type]` | 🔴 높음 | 커스텀 컨테이너 + 아이콘 필요 |
| Embed `![[파일명]]` | 🔴 높음 | 외부 파일 비동기 로딩 필요 |
| CodeBlock ` ``` ` | 🟡 중간 | 배경색 블록 필요 |
| Table `\|` | 🟢 낮음 | 그리드 레이아웃 필요 |

---

## 현재 파서 구조 (구현 완료, 로딩/저장에 활용)

```
Raw 문자열
    ↓
BlockSplitter → BlockParser → InlineParser → MarkdownParserImpl
    ↓
ParseResult(blocks: List<MarkdownBlock>, lineToBlockIndex: Map<Int, Int>)
```

> 파서는 **파일 로딩 시 clean text + spans 추출**에 사용된다.
> 에디터 입력 중 실시간 파싱은 `MarkdownStyleProcessor`가 담당한다.

---

## Live Preview 에디터 설계 (v2 — SpanStyle 방식)

### 패러다임 전환

기존 v1은 raw 마크다운 텍스트를 그대로 저장하고 Composable 오버레이로 렌더링했으나,
raw 텍스트 높이와 렌더링 결과 높이 불일치로 오버레이 위치가 어긋나는 근본적 문제가 있었다.

v2는 **reference_hyphen** 방식을 채택한다:
- 에디터 내부 텍스트는 항상 **clean** (마크다운 기호 없음)
- 서식은 `List<MarkupStyleRange>` 스팬 목록으로 별도 관리
- `OutputTransformation`에서 SpanStyle 적용 → 단일 BasicTextField 내부에서 완결
- 저장 시 `toMarkdown()`으로 clean text + spans → 마크다운 문자열 직렬화

### 전체 데이터 흐름

```
[파일 로딩]
  markdown 파일 (raw text)
      ↓ MarkdownParser (기존 파서 재활용)
  ParseResult → clean text + List<MarkupStyleRange>
      ↓
  MarkdownEditorState 초기화

[편집 중]
  사용자 입력
      ↓ InputTransformation (MarkdownStyleProcessor)
  패턴 감지 ("**bold**" 완성 시)
      → clean text 업데이트 ("bold")
      → MarkupStyleRange 추가 (Bold, 0..4)
      ↓ OutputTransformation (MarkdownEditorOutputTransformation)
  SpanStyle 적용 → 화면 표시

[저장]
  MarkdownEditorState.toMarkdown()
      → clean text + spans → "**bold**" 마크다운 직렬화
      → NoteFile.body에 저장
```

### 저장 모델 비교

| | v1 (기존) | v2 (신규) |
|---|---|---|
| 에디터 내부 텍스트 | `**bold**` (raw) | `bold` (clean) |
| 서식 정보 | 텍스트에 내포 | `List<MarkupStyleRange>` |
| 렌더링 | Composable 오버레이 | SpanStyle in OutputTransformation |
| 저장 형식 | 그대로 저장 | toMarkdown() 직렬화 후 저장 |
| 파일 형식 | 동일 (.md) | 동일 (.md) |

### Active Line (커서 위치 줄) 처리

- **Phase 1 (현재 목표)**: active line 구분 없이 항상 SpanStyle 적용 (reference 수준)
- **Phase 2 (향후)**: 커서가 위치한 줄은 문법 기호 표시 (`# Heading` → `# ` 보임), 다른 줄은 숨김
  - `OutputTransformation` 내에서 cursor line 감지 후 해당 구간 SpanStyle 미적용

---

## 컴포넌트 설계

### MarkdownEditorState

```kotlin
class MarkdownEditorState(initialMarkdown: String) {
    val textFieldState = TextFieldState()  // clean text
    val spans: List<MarkupStyleRange>      // snapshot state list

    fun processInput(buffer: TextFieldBuffer)  // InputTransformation 콜백
    fun toMarkdown(): String                   // 저장용 직렬화
    fun setMarkdown(markdown: String)          // 파일 전환 시 재초기화
    fun toggleStyle(style: MarkupStyle)        // 툴바 버튼 등 외부 조작
}
```

### MarkdownStyleProcessor

레퍼런스의 `MarkdownProcessor` 역할. 입력 텍스트에서 완성된 마크다운 패턴을 감지하여 clean text + spans로 변환한다.

```
"**bold**" 입력 완성 시:
  cleanText = "bold"
  spans += MarkupStyleRange(Bold, 0, 4)
  cursor 위치 보정
```

지원 패턴: Bold `**`, Italic `*`/`_`, Strikethrough `~~`, Highlight `==`, InlineCode `` ` ``,
H1~H6 (`# `~`###### `), BulletList (`- `), OrderedList (`N. `), Blockquote (`> `)

### MarkdownEditorOutputTransformation

`OutputTransformation` 구현. `spans` 목록을 순회하여 각 스팬에 해당하는 `SpanStyle` 적용.

```kotlin
outputTransformation = {
    spans.forEach { span ->
        when (span.style) {
            Bold        -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), ...)
            Italic      -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), ...)
            H1          -> addStyle(SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold), ...)
            Blockquote  -> addStyle(SpanStyle(color = quoteColor), ...)
            // ...
        }
    }
}
```

### MarkdownSerializer

clean text + spans → 마크다운 문자열 직렬화. 저장 및 클립보드 복사에 사용.

```kotlin
// "bold" + Bold(0..4) → "**bold**"
// "Heading" + H1(0..7) → "# Heading"
```

### MarkdownTextField (Composable)

```kotlin
@Composable
fun MarkdownTextField(
    value: String,           // 초기 마크다운 텍스트 (파일 내용)
    onValueChange: (String) -> Unit,  // 저장용 콜백 (마크다운 직렬화 결과)
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
)
```

내부에서 `MarkdownEditorState`를 `remember`로 생성. 호출부는 state 관리 불필요.
파일 전환은 호출부의 `key(file.name) { }` 로 처리.

---

## 파일 구조

```
markdown/
├── token/
│   ├── MarkdownBlock.kt              ✅ 완료 (파일 로딩용)
│   └── InlineToken.kt                ✅ 완료 (파일 로딩용)
├── parser/                           ✅ 모두 완료 (파일 로딩용)
│   ├── MarkdownParser.kt
│   ├── BlockSplitter.kt
│   ├── InlineParser.kt
│   ├── BlockParser.kt
│   ├── MarkdownParserImpl.kt
│   └── block/ (7개 sub-parser)
├── renderer/                         ✅ 완료 (향후 CodeBlock 등 특수 블록에 재활용 가능)
│   └── (11개 파일)
└── editor/
    ├── MarkdownEditorState.kt         ⬜ 신규 (HyphenTextState 대응)
    ├── MarkdownStyleProcessor.kt      ⬜ 신규 (HyphenMarkdownProcessor 대응)
    ├── MarkdownSerializer.kt          ⬜ 신규 (HyphenMarkdownSerializer 대응)
    ├── MarkdownEditorOutputTransformation.kt  ⬜ 신규
    ├── MarkdownTextField.kt           🔄 교체 (value/onValueChange 시그니처)
    ├── EditorInputTransformation.kt   ✅ 완료 (auto-close, Tab→space)
    ├── MarkdownTextFieldState.kt      ⚠️  v1 구현 — v2로 교체 시 obsolete
    └── [구버전 파일들은 삭제하지 않고 유지]
```

---

## 구현 순서

### 1단계: MarkupStyle 정의
`editor/MarkupStyle.kt` — 레퍼런스의 `MarkupStyle` 대응. MaChum 전용 스타일 추가 (WikiLink 등).

### 2단계: MarkdownStyleProcessor
`editor/MarkdownStyleProcessor.kt` — 입력 중 패턴 감지 + clean text 변환 + spans 생성.

### 3단계: MarkdownSerializer
`editor/MarkdownSerializer.kt` — clean text + spans → 마크다운 직렬화.

### 4단계: MarkdownEditorState
`editor/MarkdownEditorState.kt` — 텍스트 + 스팬 상태 통합 관리, processInput, toMarkdown.

### 5단계: MarkdownEditorOutputTransformation + MarkdownTextField
`editor/MarkdownEditorOutputTransformation.kt` + `editor/MarkdownTextField.kt` (v/onValueChange 시그니처) 작성.

### 6단계: EditorPage 연동
`screen/mainComposition/EditorPage.kt`에서 새 `MarkdownTextField(value, onValueChange)` 사용.

---

## 향후 과제

상세 설계는 **CLAUDE_sub.md** 참고.

### 🔴 Phase 2 (최우선): Active line syntax 표시

커서가 있는 줄에서 서식 기호(`#`, `**`, `~~` 등)가 보이며 편집 가능해야 한다.

**근본 문제**: v2는 clean text(기호 제거) 방식이므로 `OutputTransformation`으로는 이미 삭제된 기호를 다시 표시할 수 없다.

**해결 방향 — Raw Text + 기호 숨김 방식으로 전환**:
- TextFieldState 에 **raw 마크다운 텍스트** 그대로 저장 (기호 제거 없음)
- `OutputTransformation` 에서:
  - **비활성 줄**: 기호 문자에 `Color.Transparent` 적용 + 내용 부분에 서식 SpanStyle 적용
  - **활성 줄(커서 위치)**: 기호 문자 투명 처리 없음 → 그대로 표시·편집 가능
- 저장: raw text 그대로 저장 (직렬화 불필요)
- 상세 설계는 **CLAUDE_sub.md §Phase 2** 참고.

### Phase 3: 특수 블록 렌더링 (우선순위 순)
1. **Callout** `> [!type]` — 자주 사용. SubcomposeLayout 또는 InlineContent 방식
2. **Embed** `![[파일명]]` — 자주 사용. FileManager 비동기 로딩 + 재귀 파싱
3. **CodeBlock** ` ``` ` — DrawBehind로 배경 블록 처리
4. **Table** `|` — 그리드 레이아웃 (가장 복잡)

### 기타
- **링크 클릭**: WikiLink → 파일 이동, ExternalLink → 클립보드/브라우저
- **Undo/Redo**: `HistoryManager` 패턴 참고하여 구현
