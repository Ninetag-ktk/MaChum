# Markdown Engine & Editor — 설계 문서

이 문서는 `markdown/` 패키지의 파서·렌더러 엔진과, 이를 사용하는 `MarkdownTextField`(Material3) / `MarkdownBasicTextField`(Basic) 컴포지션의 요구사항·설계·구현 계획을 정리한다.

---

## 목표

BasicTextField처럼 단독으로 사용 가능한 **Live Preview 마크다운 편집 컴포지션** 제공.

- 커서가 위치한 줄 → 마크다운 문법 기호 표시 (편집 가능)
- 나머지 줄 → 서식이 적용된 결과 표시 (기호는 투명 처리)
- 두 상태가 동시에 화면에 보임 (모드 전환 아님)

---

## 지원 문법

### 현재 지원 (SpanStyle 기반)

| 문법 | 구현 상태 |
|---|---|
| Heading `#` ~ `######` | ✅ 완료 |
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
| Callout `> [!type]` | 높음 | 커스텀 컨테이너 + 아이콘 필요 |
| Embed `![[파일명]]` | 높음 | 외부 파일 비동기 로딩 필요 |
| CodeBlock ` ``` ` | 중간 | 배경색 블록 필요 |
| Table `\|` | 낮음 | 그리드 레이아웃 필요 |

---

## 현재 파서 구조 (구현 완료)

```
Raw 문자열
    ↓
BlockSplitter → BlockParser → InlineParser → MarkdownParserImpl
    ↓
ParseResult(blocks: List<MarkdownBlock>, lineToBlockIndex: Map<Int, Int>)
```

> 파서는 파일 로딩 시 블록 타입 판별에 사용된다.
> 에디터는 Phase 2 전환으로 파서를 거치지 않고 raw text를 직접 다룬다.

---

## Live Preview 에디터 설계 (Phase 2 — Raw Text + 기호 투명화 방식)

### 진화 과정

| 버전 | 내부 텍스트 | 서식 정보 | 저장 | 비고 |
|---|---|---|---|---|
| v1 | raw text | 텍스트에 내포 | 그대로 | 오버레이 높이 불일치 문제 |
| v2 | clean text (기호 제거) | `List<MarkupStyleRange>` | `toMarkdown()` 직렬화 | Active line 기호 표시 불가 |
| **Phase 2 (현재)** | **raw text** | **OutputTransformation 실시간 계산** | **그대로 저장** | **Obsidian 방식** |

### 핵심 원리

TextFieldState에 `# 가나다\n**볼드**` 같은 raw 마크다운을 그대로 저장한다.
화면 표시는 `OutputTransformation`이 담당한다:

- **활성 줄 (커서 위치)**: 변환 없음 → `# 가나다` 그대로 보임 (편집 가능)
- **비활성 줄**: 기호(`# `, `**`)에 `Color.Transparent + fontSize 0.01.sp` 적용 → 안 보임
  내용(`가나다`, `볼드`)에 서식 SpanStyle 적용 → 크게/굵게/이탤릭 등

커서가 이동할 때마다 `selection`이 바뀌고 → `transformOutput()` 재실행 → 즉각 반응.

### 전체 데이터 흐름

```
[파일 로딩]
  .md 파일 (raw 마크다운)
      ↓ MarkdownEditorState.setMarkdown()
  TextFieldState (raw text 그대로 저장)

[사용자 입력]
  키 입력
      ↓ onPreviewKeyEvent → EditorKeyboardShortcuts (Ctrl+B/I 등)
        → RawStyleToggle (마커 삽입/제거)
      ↓ EditorInputTransformation (Smart Enter + auto-close: [[, **, ~~ 등)
      ↓ TextFieldState 에 raw text 직접 반영
      ↓ RawMarkdownOutputTransformation (OutputTransformation)
        1. MarkdownPatternScanner.scan(text) → 서식 범위 목록
        2. selection.start 로 커서 줄 계산
        3. 비활성 줄: 기호 투명 + 내용 서식 적용
        4. 활성 줄: 변환 없음
      ↓
  화면 표시

[저장]
  textFieldState.text.toString() → raw text 그대로
      ↓ NoteFile.body 에 저장
```

---

## 컴포넌트 설계

### MarkdownEditorState

```kotlin
class MarkdownEditorState(initialMarkdown: String) {
    val textFieldState = TextFieldState()  // raw text 저장

    fun setMarkdown(markdown: String)  // 파일 전환 시 재초기화
}
```

raw text를 그대로 담는 단순한 홀더. v2의 `_spans`, `toMarkdown()`, `applyInput()` 은 제거됨.

### MarkdownPatternScanner

문서 전체 raw 텍스트를 스캔하여 서식 범위 목록을 반환한다.
기호를 제거하지 않고, 기호 범위(MARKER)와 내용 범위(SpanStyle)만 알려준다.

```
입력: "# 가나다\n**볼드**"
출력:
  (0..1, MARKER)       ← "# " 투명 처리 대상
  (2..4, H1 SpanStyle) ← "가나다" 서식 적용 대상
  (6..7, MARKER)       ← "**"
  (8..9, Bold)         ← "볼드"
  (10..11, MARKER)     ← "**"
```

내부적으로 줄 단위 블록 타입 감지(헤딩, 코드 블록 등) 후 `InlineStyleScanner`에 위임.

### RawMarkdownOutputTransformation

`OutputTransformation` 구현. 커서 줄 판별 + 비활성 줄에만 스팬 적용.

```kotlin
class RawMarkdownOutputTransformation : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val cursorLineStart = ... // selection.start 기반
        val cursorLineEnd = ...
        val spans = MarkdownPatternScanner.scan(text)
        for ((range, style) in spans) {
            if (커서 줄과 겹침) continue  // 활성 줄: 변환 없음
            addStyle(style, range)       // 비활성 줄: 서식 적용
        }
    }
}
```

텍스트 변경 시에만 재스캔하고, 커서만 이동한 경우 캐시된 결과를 재사용한다.

### InlineStyleScanner

비활성 블록의 raw 텍스트에 적용할 SpanStyle 범위 리스트를 계산한다.
블록 타입별 전략:

- **Heading**: `# ` prefix → MARKER, 내용 → heading SpanStyle
- **CodeBlock**: `` ``` `` 펜스 → MARKER, 코드 → 모노스페이스
- **일반 줄**: 블록 prefix(`> `, `- ` 등) → MARKER, 나머지 → 인라인 스캔
- **인라인**: `**`, `~~`, `==`, `` ` ``, `[[`, `[text](url)` 등 마커 → MARKER, 내용 → 서식

MARKER 스타일: `SpanStyle(fontSize = 0.01.sp, color = Color.Transparent)` → 공간 거의 안 차지.

### MarkdownBasicTextField / MarkdownTextField (Composable)

`MarkdownBasicTextField`는 Material3에 의존하지 않는 Basic 버전.
`MarkdownTextField`는 Material3 테마를 자동 적용하며, 내부에서 `MarkdownBasicTextField`를 호출한다.

```kotlin
// Basic 버전 (MarkdownBasicTextField.kt)
@Composable
fun MarkdownBasicTextField(
    value: String,           // raw 마크다운 텍스트 (파일 내용)
    onValueChange: (String) -> Unit,  // raw text 그대로 전달
    modifier: Modifier = Modifier,
    textStyle: TextStyle = TextStyle.Default,
    cursorBrush: Brush = SolidColor(Color.Black),
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig(),
)

// Material3 버전 (MarkdownTextField.kt)
@Composable
fun MarkdownTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    styleConfig: MarkdownStyleConfig = defaultMaterialStyleConfig(),
)
```

내부에서 `MarkdownEditorState`를 `remember`로 생성. 호출부는 state 관리 불필요.
파일 전환은 호출부의 `key(file.name) { }` 로 처리.
`BasicTextField`에 `onPreviewKeyEvent`로 키보드 단축키 핸들러가 연결된다.

### RawStyleToggle

`TextFieldState`에서 마크다운 마커를 직접 삽입/제거하는 유틸리티.
키보드 단축키와 향후 툴바 버튼이 공용으로 사용.

- `toggleInlineStyle(state, marker)`: `**`, `*`, `` ` ``, `~~`, `==` 등 인라인 마커 토글
- `toggleHeading(state, level)`: 현재 줄의 `#` prefix 토글
- `toggleBlockPrefix(state, prefix)`: 현재 줄의 블록 prefix (`- `, `> ` 등) 토글

### EditorKeyboardShortcuts

`onPreviewKeyEvent`에서 호출되는 하드웨어 키보드 단축키 핸들러.

| 단축키 | 동작 |
|---|---|
| Ctrl/Cmd + B | Bold `**` 토글 |
| Ctrl/Cmd + I | Italic `*` 토글 |
| Ctrl/Cmd + E | InlineCode `` ` `` 토글 |
| Ctrl/Cmd + Shift + S/X | Strikethrough `~~` 토글 |
| Ctrl/Cmd + Shift + H | Highlight `==` 토글 |

---

## 파일 구조

```
markdown/
├── token/
│   ├── MarkdownBlock.kt              ✅ 완료
│   └── InlineToken.kt                ✅ 완료
├── parser/                           ✅ 모두 완료
│   ├── MarkdownParser.kt
│   ├── BlockSplitter.kt
│   ├── InlineParser.kt
│   ├── BlockParser.kt
│   ├── MarkdownParserImpl.kt
│   └── block/ (7개 sub-parser)
├── renderer/                         ✅ 완료 (향후 특수 블록 재활용 가능)
│   └── (11개 파일)
├── reference_hyphen/                 📌 레퍼런스 전용 (직접 사용하지 않음)
│   └── (Hyphen 라이브러리 원본 — 설계 참고용)
└── editor/
    ├── MarkdownEditorState.kt         ✅ Phase 2 (raw text 홀더)
    ├── MarkdownPatternScanner.kt      ✅ Phase 2 (문서 전체 스캔)
    ├── RawMarkdownOutputTransformation.kt  ✅ Phase 2 (커서 줄 기반 서식)
    ├── InlineStyleScanner.kt          ✅ Phase 2 (블록별 SpanStyle 계산)
    ├── MarkdownStyleConfig.kt         ✅ Phase 2 (서식 커스터마이징 설정)
    ├── MarkdownBasicTextField.kt      ✅ Phase 2 (BasicTextField 래퍼 — Basic)
    ├── MarkdownTextField.kt           ✅ Phase 2 (Material3 래퍼)
    ├── EditorInputTransformation.kt   ✅ Phase 4 (Smart Enter + auto-close + Tab→space)
    ├── RawStyleToggle.kt              ✅ Phase 4 (서식 토글 유틸리티)
    └── EditorKeyboardShortcuts.kt     ✅ Phase 4 (하드웨어 키보드 단축키)
```

---

## 향후 과제

상세 설계는 **CLAUDE_sub.md** 참고.

### Phase 3: 특수 블록 렌더링 (우선순위 순)
1. **Callout** `> [!type]` — 자주 사용. SubcomposeLayout 또는 InlineContent 방식
2. **Embed** `![[파일명]]` — 자주 사용. FileManager 비동기 로딩 + 재귀 파싱
3. **CodeBlock** ` ``` ` — DrawBehind로 배경 블록 처리
4. **Table** `|` — 그리드 레이아웃 (가장 복잡)

### Phase 4: 에디터 기능 확장 (✅ 완료)
- **스마트 Enter**: 리스트/인용구/체크박스 자동 continuation (`EditorInputTransformation`)
- **서식 토글**: 인라인/헤딩/블록 prefix 토글 (`RawStyleToggle`)
- **하드웨어 키보드 단축키**: Ctrl+B/I/E, Ctrl+Shift+S/X/H (`EditorKeyboardShortcuts`)

### 기타
- **Undo/Redo**: Phase 2 raw text 기반으로 재설계 필요
- **툴바 UI**: `RawStyleToggle` 연동하여 버튼 UI 추가
- **링크 클릭**: WikiLink → 파일 이동, ExternalLink → 클립보드/브라우저
