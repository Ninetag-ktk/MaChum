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
├── token/                             ← 블록/인라인 타입 정의 (InlineStyleScanner, MarkdownPatternScanner에서 사용)
│   ├── MarkdownBlock.kt              ✅ 완료
│   └── InlineToken.kt                ✅ 완료
└── editor/
    ├── MarkdownEditorState.kt         ✅ Phase 2 (raw text 홀더)
    ├── MarkdownPatternScanner.kt      ✅ Phase 2+3 (문서 전체 스캔 + BlockType, BlockRange, ScanResult 데이터 모델 포함)
    ├── RawMarkdownOutputTransformation.kt  ✅ Phase 2+3+5 (블록 수준 커서 감지, blockTransparent, isFocused)
    ├── InlineStyleScanner.kt          ✅ Phase 2+3 (블록별 SpanStyle, calloutSpans, overlay 모드)
    ├── MarkdownStyleConfig.kt         ✅ Phase 2+3 (서식 설정 + CalloutDecorationStyle + blockTransparent)
    ├── BlockDecorationDrawer.kt       ✅ Phase 3+5 (DrawScope — 활성 블록 데코레이션, isNested)
    ├── OverlayBlockParser.kt          ✅ Phase 3 (raw text → OverlayBlockData 경량 파서 + OverlayBlockData sealed class 포함)
    ├── OverlayPositionCalculator.kt   ✅ Phase 3 (TextLayoutResult → 뷰포트 좌표)
    ├── overlay/
    │   ├── BlockOverlay.kt            ✅ Phase 3+5 (오버레이 타입별 라우팅, overlayDepth 전달)
    │   ├── CalloutOverlay.kt          ✅ Phase 3+5 (제목 TextField + body MarkdownBasicTextFieldCore 재귀)
    │   ├── TableOverlay.kt            ✅ Phase 3 (셀별 TextField + raw 동기화)
    │   ├── CodeBlockOverlay.kt        ✅ Phase 3 (코드 TextField + raw 동기화)
    │   ├── InlineOnlyOutputTransformation.kt  ✅ Phase 3 (오버레이 내 인라인 서식)
    │   └── OverlayScrollForwarder.kt  ✅ Phase 3 (스크롤 포워딩 유틸리티)
    ├── MarkdownBasicTextField.kt      ✅ Phase 2+3+5 (MarkdownBasicTextFieldCore 분리, overlayDepth, onFocusChanged)
    ├── MarkdownTextField.kt           ✅ Phase 2+3 (Material3 래퍼)
    ├── EditorInputTransformation.kt   ✅ Phase 4 (Smart Enter + auto-close + Tab→space)
    ├── RawStyleToggle.kt              ✅ Phase 4 (서식 토글 유틸리티)
    └── EditorKeyboardShortcuts.kt     ✅ Phase 4 (하드웨어 키보드 단축키)
```

---

## Phase 3: 특수 블록 — 오버레이 Composable 아키텍처

상세 구현 설계는 **CLAUDE_sub.md** 참고.

### 핵심 원리

Raw markdown은 모두 **단일 TextFieldState에 존재**한다. 특수 블록(Callout, Table, CodeBlock)의
텍스트는 `OutputTransformation`에서 **투명 + 정상 폰트 크기**로 처리하여 줄 높이를 보존하고,
그 위에 오버레이 Composable을 배치한다. 오버레이 내부에 별도 TextField를 두어 직접 편집하며,
변경 사항은 underlying raw markdown에 동기화된다.

Embed는 콘텐츠 크기를 예측할 수 없으므로 LazyColumn 내 **별도 블록**으로 분리한다.

### 세 가지 렌더링 레이어

```
Layer 1: OutputTransformation — 인라인 서식(MARKER 0.01sp) + 블록 투명(blockTransparent)
Layer 2: DrawBehind           — 활성 블록(raw 편집 중)의 배경/테두리
Layer 3: Overlay Composable   — 비활성 블록의 커스텀 UI (TextField 포함, 직접 편집)
```

### 블록별 전략

| 블록 | 전략 | 편집 방식 | 구현 상태 |
|---|---|---|---|
| Callout `> [!type]` | 투명 텍스트 + 오버레이 | 제목/내용 각각 TextField | ✅ 완료 |
| Table `\|` | 투명 텍스트 + 오버레이 | 셀별 TextField | ✅ 완료 |
| CodeBlock ` ``` ` | 투명 텍스트 + 오버레이 | 코드 TextField (모노스페이스) | ✅ 완료 |
| Embed `![[파일]]` | LazyColumn 별도 블록 | 읽기 전용 (원본 파일 편집은 별도) | 📌 미구현 |

### Phase 3 구현 상태

Scanner → ScanResult → OutputTransformation(블록 투명) → DrawBehind(활성 블록 데코레이션) → 오버레이 Composable(비활성 블록 직접 편집) 파이프라인이 완성되었다.
각 오버레이는 내부 TextField로 직접 편집하며, 변경 사항을 raw markdown에 동기화한다.
Embed는 콘텐츠 크기 예측 불가로 LazyColumn 별도 블록 방식이며, 아직 미구현이다.

### Phase 4: 에디터 기능 확장 (✅ 완료)
- **스마트 Enter**: 리스트/인용구/체크박스 자동 continuation (`EditorInputTransformation`)
- **서식 토글**: 인라인/헤딩/블록 prefix 토글 (`RawStyleToggle`)
- **하드웨어 키보드 단축키**: Ctrl+B/I/E, Ctrl+Shift+S/X/H (`EditorKeyboardShortcuts`)

### Phase 5: 재귀적 오버레이 + UI 개선 (✅ 구현 완료)

Callout body에서 중첩 Callout/Table을 별도 오버레이 Composable로 렌더링.
Callout에만 적용 — Table 내부에서는 재귀하지 않음. CodeBlock은 오버레이 없이 raw 표시.

**구현 항목:**
- `MarkdownBasicTextFieldCore`: `overlayDepth`, `parentScrollState` 파라미터
- overlay 루프: `key(textRange.first)`, `onRequestActivation` 콜백 (FocusRequester 기반)
- `CalloutOverlay`: RoundBox 디자인 (배경 + 라운드 테두리) + 타입별 아이콘(18×18)
- `CalloutOverlay`: body fontSize 0.9em (중첩 시 누적), Title↔Body 방향키/Enter 내비게이션
- `CalloutOverlay`: `remember`(키 없음) + `rememberUpdatedState(data)` → sync 안정성
- `CalloutOverlay`: Backspace(position 0)/LongPress → `onRequestActivation`으로 raw 전환
- `CalloutOverlay`: 편집 중 scroll forwarder 비활성화, `parentScrollState`로 루트 전달
- CodeBlock: 오버레이 제거, raw 마크다운 그대로 표시 (펜스 감지만 유지 → 인라인 스캔 방지)
- Inline Code: `codeInlineBackground` 분리 + DrawBehind `drawRoundRect`(4dp cornerRadius)
- `normalizedTextStyle`: `LineHeightStyle(Proportional, Trim.Both)` → Bold/Italic 줄 높이 통일
- `RawMarkdownOutputTransformation`: `inlineCodeRanges` 노출

**알려진 이슈:**
- 초기 1프레임 지연: `textLayoutResult` 확보 전 blockTransparent 적용 → 잠깐 투명 텍스트
- `forceAllOverlaysInactive` 선언 잔재 (미사용, 정리 가능)

상세 설계: **MARKDOWN.md**, Callout 전용: **Callout.md** 참고.

### 기타
- **Undo/Redo**: Phase 2 raw text 기반으로 재설계 필요
- **툴바 UI**: `RawStyleToggle` 연동하여 버튼 UI 추가
- **링크 클릭**: WikiLink → 파일 이동, ExternalLink → 클립보드/브라우저
