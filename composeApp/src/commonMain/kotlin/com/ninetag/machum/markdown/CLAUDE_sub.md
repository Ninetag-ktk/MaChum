# MarkdownTextField v2 — 구현 참고 문서

CLAUDE.md의 설계를 기반으로 실제 구현 시 참고할 상세 내용을 정리한다.

---

## 레퍼런스 vs MaChum 컴포넌트 대응표

| reference_hyphen | MaChum v2 | 비고 |
|---|---|---|
| `MarkupStyle` | `MarkupStyle` | WikiLink, EmbedLink 추가 필요 |
| `MarkupStyleRange` | `MarkupStyleRange` | 동일 구조 재사용 가능 |
| `HyphenTextState` | `MarkdownEditorState` | toMarkdown() 필수 |
| `MarkdownProcessor` | `MarkdownStyleProcessor` | 기존 InlineParser 활용 가능 |
| `MarkdownSerializer` | `MarkdownSerializer` | 기존 MarkdownParser 역활용 |
| `applyMarkdownStyles()` | `MarkdownEditorOutputTransformation` | OutputTransformation 구현 |
| `HyphenBasicTextEditor` | `MarkdownTextField` | value/onValueChange 시그니처 |
| `SpanManager` | SpanManager (또는 인라인) | 스팬 shift/merge/consolidate |
| `HistoryManager` | (Phase 2) | undo/redo |
| `BlockStyleManager` | (일부 포함) | Enter 스마트 처리 |

---

## MarkupStyleRange 구조

```kotlin
data class MarkupStyleRange(
    val style: MarkupStyle,
    val start: Int,   // clean text 기준 시작 오프셋 (inclusive)
    val end: Int,     // clean text 기준 끝 오프셋 (exclusive)
)
```

---

## MarkdownStyleProcessor 동작 원리

레퍼런스의 `MarkdownProcessor.process()` 참고.

### 핵심: applyRule 패턴

```
regex 매칭 → 구분자 제거 분량(prefixRemoved, suffixRemoved) 계산
           → clean text에서 구분자 제거
           → MarkupStyleRange 생성
           → 커서 위치 보정 (구분자 제거량 반영)
```

### 인라인 처리 예시

```
입력: "**bold**"  cursor=8
      ↓ BOLD_REGEX 매칭
cleanText: "bold"  cursor=4
spans: [Bold(0..4)]
```

### 블록 처리 예시 (Heading)

레퍼런스와 동일: prefix만 제거, span은 해당 줄 전체를 커버.

```
입력: "# Heading"
      ↓ H1_REGEX, prefixRemoved=2 ("# " 제거)
cleanText: "Heading"
spans: [H1(0..7)]  ← 줄 전체
```

블록 스타일(BulletList, Blockquote, OrderedList)은 레퍼런스와 달리 **prefix를 cleanText에 유지**.
(렌더링 시 prefix에 별도 SpanStyle 적용)

---

## MarkdownEditorOutputTransformation SpanStyle 매핑

```kotlin
Bold        → SpanStyle(fontWeight = FontWeight.Bold)
Italic      → SpanStyle(fontStyle = FontStyle.Italic)
Strikethrough → SpanStyle(textDecoration = TextDecoration.LineThrough)
Highlight   → SpanStyle(background = Color(0xFFFFFF00).copy(alpha = 0.4f))
InlineCode  → SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
H1          → SpanStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
H2          → SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
H3          → SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
H4          → SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
H5          → SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
H6          → SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
BulletList  → prefix("- "): SpanStyle(color = bulletColor), content: 기본
OrderedList → prefix("N. "): SpanStyle(color = bulletColor), content: 기본
Blockquote  → SpanStyle(color = quoteColor)
WikiLink    → SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
```

---

## MarkdownSerializer 직렬화 규칙

clean text + spans → 마크다운 문자열

```
Bold(start..end)   → text[start..end] 감싸기: "**" + content + "**"
Italic(start..end) → "*" + content + "*"
H1(line)           → "# " + lineContent
BulletList(line)   → prefix("- ") 이미 cleanText에 있으므로 그대로
Blockquote(line)   → prefix("> ") 이미 cleanText에 있으므로 그대로
```

중첩 처리: 안쪽 스팬부터 처리 (start 기준 역순으로 삽입).

---

## MarkdownEditorState 초기화 (파일 로딩)

마크다운 파일 → clean text + spans 변환은 기존 `MarkdownParser` 출력을 재활용한다.

```kotlin
fun setMarkdown(markdown: String) {
    // 1. 기존 MarkdownStyleProcessor.process()로 inline 기호 처리
    // 2. 결과를 textFieldState에 설정
    // 3. spans 재구성
}
```

---

## EditorPage 연동 (Phase 1 완성 목표)

```kotlin
// EditorPage.kt
key(file.name) {
    MarkdownTextField(
        value = noteFile.body,          // raw 마크다운 텍스트
        onValueChange = { markdown ->
            viewModel.updateBody(file.name, markdown)
        },
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    )
}
```

`onValueChange`는 `MarkdownEditorState.toMarkdown()` 결과를 받는다.
호출 주기: 즉시 (디바운싱은 EditorPage에서 담당).

---

## 기존 코드 활용 정리

| 파일 | v2에서의 역할 |
|---|---|
| `parser/MarkdownParser.kt` 외 | 파일 로딩 시 초기 파싱에 계속 사용 |
| `renderer/BlockRenderer.kt` 외 | CodeBlock 등 특수 블록 향후 재활용 가능 |
| `editor/EditorInputTransformation.kt` | 계속 사용 (auto-close, Tab→space) |
| `editor/MarkdownTextFieldState.kt` | v1 구현 — v2 완성 후 obsolete (삭제 금지) |
| `editor/MarkdownTextField.kt` (v1) | v2로 교체 — 기존 파일 덮어씌움 |

---

## 알려진 버그 및 수정 내역

### 버그: 서식 첫 글자에만 적용 (수정 완료)

**증상**: `# Hello World` 처럼 heading을 입력할 때, `H` 한 글자에만 H1 서식이 적용되고 이후 타이핑한 글자에는 서식이 붙지 않음.

**원인 분석**:

`SpanManager.shiftSpans`에서 `changeStart >= span.end` 조건을 사용했음.

1. `# H` 입력 시 `MarkdownStyleProcessor.process()` 가 firing → cleanText=`H`, H1(0,1)
2. 이후 `e` 타이핑: changeStart=1, span.end=1 → `1 >= 1` = true → 스팬 불변, H1(0,1) 유지
3. 결과: `He` 인데 H1 스팬은 여전히 (0,1), 두 번째 글자부터 서식 미적용

**수정**:
1. `changeStart >= span.end` → `changeStart > span.end` (strict 부등호): span.end 위치 삽입 시 스팬 확장
2. `MarkdownEditorState.applyInput` 에 `reanchorHeadingSpans()` 추가: 매 입력 후 heading 스팬을 현재 줄 전체(lineStart..lineEnd)에 재조정
   - 줄 경계 넘어 확장되거나 Enter 삽입 후 `\n` 포함 등의 케이스 모두 처리

**레퍼런스 라이브러리와의 차이**:
레퍼런스(reference_hyphen)는 Heading을 inline style로 분류하고 `finalSpans` 단계에서 동일하게 re-anchoring을 수행함. 우리 구현도 같은 전략으로 수정.

---

## 🔴 Phase 2: Active line syntax 표시 (최우선 과제)

### 목표

커서가 있는 줄에서 `#`, `**`, `~~` 등 서식 기호가 보이며 직접 편집 가능해야 한다.

```
# Hello World      ← 커서 위치: # 기호 보임, 편집 가능
**bold text**      ← 비활성: "bold text" 만 보임 (굵게 표시)
```

### 근본 문제 (v2 한계)

현재 v2(clean text) 방식에서는 `MarkdownStyleProcessor`가 `# `, `**` 등을 텍스트에서 **제거**한다.
`OutputTransformation`은 기존 텍스트에 SpanStyle을 입힐 수만 있고, 없어진 문자를 다시 표시할 수 없다.

→ **v2 clean text 방식으로는 active line에 서식 기호를 표시하는 것이 구조적으로 불가**.

### 해결 방향: Raw Text + 기호 투명화 방식

TextFieldState에 **raw 마크다운 텍스트**를 그대로 저장하고, `OutputTransformation`에서 비활성 줄의 기호를 숨긴다.

#### 데이터 흐름

```
[입력]
  사용자 타이핑 → TextFieldState = raw markdown text (# Hello, **bold** 등 유지)

[OutputTransformation]
  커서 위치 파악 → cursor가 속한 줄 = 활성 줄

  활성 줄:
    - 서식 기호 그대로 표시 (Color 변경 없음)
    - 서식 SpanStyle 미적용 (raw 텍스트 그대로)

  비활성 줄:
    - 서식 기호: Color.Transparent (숨김)
    - 내용 부분: 해당 서식 SpanStyle 적용

[저장]
  raw text 그대로 저장 (toMarkdown() 불필요)
```

#### 구현 계획

1. **`MarkdownPatternScanner`** (신규): raw text → 서식 범위 목록 반환 (기호 제거 없음)
   - 기호 범위: `SyntaxRange(start, end)` — 투명 처리 대상
   - 내용 범위: `ContentRange(start, end, style)` — SpanStyle 적용 대상
   - 라인별로 스캔하여 결과 캐시

2. **`RawMarkdownOutputTransformation`** (신규): `OutputTransformation` 구현
   - 비활성 줄 기호: `addStyle(SpanStyle(color = Color.Transparent), ...)`
   - 비활성 줄 내용: `addStyle(style.toSpanStyle(), ...)`
   - 활성 줄: 변환 없음

3. **`MarkdownTextField`** 시그니처 유지 (`value: String, onValueChange: (String) -> Unit`)
   - 내부 구조만 변경: `MarkdownEditorState` 대신 `TextFieldState(raw text)` 직접 사용
   - `onValueChange`: `state.text.toString()` 그대로 전달

#### 기호 범위 산출 예시

```
raw: "# Hello\n**bold** text"

활성 줄이 "# Hello" 일 때:
  → 표시: "# Hello" (기호 보임)

비활성 줄 "**bold** text":
  SyntaxRange(8, 10)  ← "**"
  ContentRange(10, 14, Bold)
  SyntaxRange(14, 16) ← "**"
  ContentRange(16, 21, None)  ← " text"
```

#### 현재 v2 코드와의 호환성

| 파일 | Phase 2 에서의 처리 |
|---|---|
| `MarkdownEditorState.kt` | 내부 구조 변경 (raw text 관리, spans 제거) |
| `MarkdownStyleProcessor.kt` | 불필요 — 기호 제거 대신 `MarkdownPatternScanner` 사용 |
| `MarkdownSerializer.kt` | 불필요 — raw text 그대로 저장 |
| `MarkdownEditorOutputTransformation.kt` | `RawMarkdownOutputTransformation` 으로 교체 |
| `EditorInputTransformation.kt` | 그대로 사용 (auto-close 유지) |
| `MarkdownTextField.kt` | 시그니처 유지, 내부만 교체 |

---

## 특수 블록 렌더링 (Phase 3 설계)

SpanStyle만으로 표현할 수 없는 블록들. 기존 `renderer/` 패키지의 Composable들을 재활용한다.
단일 BasicTextField 안에서 Composable을 삽입하려면 **`BasicTextField`의 `decorator` 또는 `InlineContent`** 방식을 써야 한다.

### 공통 접근 전략: InlineContent + Placeholder

Compose의 `BasicTextField`는 `AnnotatedString` 내에 `InlineTextContent`를 삽입할 수 있다.
`OutputTransformation`에서 특수 블록 위치에 placeholder 문자(`\uFFFD` 등)를 삽입하고,
해당 문자에 `InlineTextContent`로 Composable을 매핑하는 방식.

```
cleanText: "...본문...\uFFFD...본문..."
           ↑ placeholder 위치에 Composable 블록 렌더링
```

> ⚠️ 단, BasicTextField의 새 API(TextFieldState 기반)에서 InlineTextContent 지원 여부 확인 필요.
> 지원되지 않을 경우 대안: SubcomposeLayout으로 BasicTextField 위에 절대 위치 오버레이
> (단, 이 경우 높이 동기화 문제 재발 가능 — 블록 높이를 먼저 측정 후 clean text에 빈 줄을 삽입하여 공간 확보하는 방식 고려)

---

### 🔴 Callout `> [!type]`

**우선순위 높음. 자주 사용.**

#### 파싱 규칙
```
> [!NOTE] 제목
> 내용 줄 1
> 내용 줄 2
```
- 첫 줄: `> [!TYPE]` 패턴 → Callout 시작
- 이후 `> ` 접두사를 가진 연속 줄 → Callout 내용
- TYPE: NOTE, TIP, WARNING, DANGER, INFO 등 (대소문자 무관)

#### 저장 모델
```kotlin
data class CalloutRange(
    val type: String,       // "NOTE", "WARNING" 등
    val title: String,      // "[!NOTE] 제목"에서 제목 부분
    val bodyStart: Int,     // clean text 기준 body 시작
    val bodyEnd: Int,
)
```
`MarkdownEditorState`에 `callouts: List<CalloutRange>` 추가.

#### 렌더링
기존 `CalloutRenderer.kt` Composable 재활용.
placeholder 방식 또는 오버레이 방식으로 삽입.

#### 편집 모드
커서가 Callout 내부에 있을 때:
- `> ` prefix를 SpanStyle로 dimmed 처리 (완전 숨김 대신)
- type/title 줄은 raw 형태로 표시

---

### 🔴 Embed `![[파일명]]`

**우선순위 높음. 자주 사용.**

#### 지원 형식
```
![[파일명]]           전체 파일 임베드
![[파일명#헤딩]]      헤딩 단위
![[파일명^블록ID]]    블록ID 단위
```

#### 렌더링 방식
1. `MarkdownEditorState`가 embed 위치 목록 `embeds: List<EmbedRange>` 관리
2. `FileManager`로 해당 파일의 `NoteFile` 비동기 로딩
3. 로딩된 내용을 재귀 파싱 (최대 2단계 — 기존 `MarkdownParserImpl`의 depth 제한 활용)
4. 렌더링: placeholder 위치에 `EmbedRenderer` 또는 `BlockRenderer` 삽입

#### 로딩 상태
- 로딩 전: `![[파일명]]` placeholder 표시 (기존 `EmbedRenderer` placeholder 활용)
- 로딩 완료: 렌더링 결과 교체
- 파일 없음: "파일을 찾을 수 없음" 표시

#### 편집 모드
커서가 embed 줄에 있을 때: `![[파일명]]` raw 텍스트 표시 (렌더링 해제)

---

### 🟡 CodeBlock ` ``` `

#### 저장 모델
cleanText에 코드 내용 유지, `CodeBlockRange`로 범위 추적.
````
```kotlin
val x = 1
```
````
→ cleanText: `val x = 1` + CodeBlock span (언어: "kotlin")

#### 렌더링
`DrawBehind`로 rounded 배경 박스 그리기 + 모노스페이스 SpanStyle.
기존 `CodeBlockRenderer.kt` 참고.

#### 편집 모드
커서가 코드 블록 내부: 배경 유지 + ``` 구분자 표시.

---

### 🟢 Table `|`

#### 저장 모델
표 전체를 별도 `TableRange`로 추적. cleanText에는 `|` 구분자 포함 raw 텍스트 유지.

#### 렌더링
기존 `TableRenderer.kt` Composable 재활용.
placeholder 방식으로 삽입 (표 영역 전체를 하나의 Composable로).

#### 편집 모드
커서가 표 안에 있을 때: raw 텍스트 (`|` 구분자 포함) 표시, 렌더링 해제.

---

## 구현 시 주의사항

1. **스팬 offset은 clean text 기준**: 마크다운 기호가 제거된 후의 위치
2. **블록 스타일 prefix는 cleanText에 유지**: BulletList의 `- `, Blockquote의 `> ` 등은 텍스트에 남김 (레퍼런스와 동일)
3. **Heading prefix는 제거**: `# ` 제거 후 해당 줄 전체에 H1 span 적용
4. **스팬 consolidate**: 동일 스타일의 인접 스팬은 병합
5. **파일 전환**: `key(file.name)` 으로 state 재생성 (rememberHyphenTextState 패턴 참고)
