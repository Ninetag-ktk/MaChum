# MarkdownTextField — 구현 참고 문서

CLAUDE.md의 설계를 기반으로 실제 구현 시 참고할 상세 내용을 정리한다.

---

## 레퍼런스 vs MaChum 컴포넌트 대응표

> `reference_hyphen/`은 설계 참고용으로 복사한 외부 라이브러리이다.
> MaChum은 이를 직접 사용하지 않고, 프로젝트에 최적화하여 별도 구현한다.

| reference_hyphen | MaChum (Phase 2) | 비고 |
|---|---|---|
| `MarkupStyle` | — (삭제) | Phase 2는 SpanStyle 직접 사용 |
| `MarkupStyleRange` | — (삭제) | Phase 2는 spans 목록 없음 |
| `HyphenTextState` | `MarkdownEditorState` | raw text 홀더로 단순화됨 |
| `MarkdownProcessor` | `MarkdownPatternScanner` | 기호 제거 대신 범위만 반환 |
| `MarkdownSerializer` | — (삭제) | raw text 직접 저장으로 불필요 |
| `applyMarkdownStyles()` | `RawMarkdownOutputTransformation` | 커서 줄 기반 조건부 적용 |
| `HyphenBasicTextEditor` | `MarkdownBasicTextField` | value/onValueChange 시그니처 유지 |
| (HyphenTextEditor) | `MarkdownTextField` | Material3 래퍼 |
| `SpanManager` | `RawStyleToggle` | 서식 토글 유틸리티 (Phase 4) |
| `HistoryManager` | (미구현) | undo/redo |
| `BlockStyleManager` | `EditorInputTransformation` (Smart Enter) + `RawStyleToggle` (블록 토글) | Phase 4 구현 완료 |
| `handleHardwareKeyEvent()` | `EditorKeyboardShortcuts` | 키보드 단축키 (Phase 4) |

---

## Phase 2 구현 완료 (현재 상태)

### 아키텍처

```
TextFieldState ← raw markdown text ("# 가나다\n**볼드**")
                  ↓
OutputTransformation (RawMarkdownOutputTransformation)
  ├─ 활성 줄 (커서): 변환 없음 → "# 가나다" 그대로 표시
  └─ 비활성 줄: "# " → 투명, "가나다" → H1 SpanStyle
                  ↓
저장: textFieldState.text.toString() → raw text 그대로
```

### 기호 숨김 방식

비활성 줄의 서식 기호에 적용하는 MARKER 스타일:

```kotlin
SpanStyle(fontSize = 0.01.sp, color = Color.Transparent)
```

- `fontSize = 0.01.sp` → 공간을 거의 차지하지 않음
- `Color.Transparent` → 보이지 않음
- 완전히 0으로 하지 않는 이유: 일부 렌더러에서 fontSize=0을 처리하지 못하거나 커서 hit-testing이 깨질 수 있음

### 커서 줄 감지

`OutputTransformation.transformOutput()` 내부에서 `selection.start`로 실시간 계산:

```kotlin
val cursorPos = selection.start
val cursorLineStart = text.lastIndexOf('\n', cursorPos - 1) + 1
val cursorLineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
```

커서가 이동할 때마다 `selection` 변경 → `transformOutput()` 재실행 → 즉각 반응.

### 스캐닝 캐시

`RawMarkdownOutputTransformation`은 내부에 `cachedText`/`cachedSpans`를 보관한다.
텍스트가 변경될 때만 `MarkdownPatternScanner.scan()`을 호출하고,
커서만 이동한 경우 캐시된 스팬 목록을 재사용한다.

### MarkdownPatternScanner 동작

문서 전체를 줄 단위로 스캔:

1. `` ``` `` 펜스 감지 → 코드 블록 추적
2. `#` + 공백 → 헤딩 레벨 감지
3. 나머지 → 일반 텍스트

각 블록 타입에 맞는 `MarkdownBlock` 인스턴스를 생성하여 `InlineStyleScanner.computeSpans()`에 위임.

### InlineStyleScanner 스타일 매핑

```
MARKER (투명)  → SpanStyle(fontSize = 0.01.sp, color = Color.Transparent)
BOLD           → SpanStyle(fontWeight = FontWeight.Bold)
ITALIC         → SpanStyle(fontStyle = FontStyle.Italic)
BOLD_ITALIC    → SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
STRIKE         → SpanStyle(textDecoration = TextDecoration.LineThrough)
HIGHLIGHT      → SpanStyle(background = Color(0xFFFFEB3B))
CODE_INLINE    → SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))
CODE_BLOCK     → SpanStyle(fontFamily = FontFamily.Monospace)
LINK           → SpanStyle(color = Color(0xFF1565C0))
H1             → SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
H2             → SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
H3~H6          → 점진적 축소
```

---

## EditorPage 연동

```kotlin
// EditorPage.kt — Material3 래퍼 사용
key(file.name) {
    MarkdownTextField(
        value = noteFile.body,          // raw 마크다운 텍스트
        onValueChange = { markdown ->
            pendingMarkdown.value = markdown  // raw text 그대로
        },
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    )
}
```

`onValueChange`는 `textFieldState.text.toString()` 결과를 받는다 (raw text).
디바운싱은 EditorPage에서 담당 (500ms).

---

## 특수 블록 렌더링 (Phase 3 설계)

SpanStyle만으로 표현할 수 없는 블록들. 기존 `renderer/` 패키지의 Composable들을 재활용한다.
단일 BasicTextField 안에서 Composable을 삽입하려면 **`InlineContent`** 또는 **`SubcomposeLayout`** 방식을 써야 한다.

### 공통 접근 전략: InlineContent + Placeholder

Compose의 `BasicTextField`는 `AnnotatedString` 내에 `InlineTextContent`를 삽입할 수 있다.
`OutputTransformation`에서 특수 블록 위치에 placeholder 문자(`\uFFFD` 등)를 삽입하고,
해당 문자에 `InlineTextContent`로 Composable을 매핑하는 방식.

> 단, BasicTextField의 새 API(TextFieldState 기반)에서 InlineTextContent 지원 여부 확인 필요.
> 지원되지 않을 경우 대안: SubcomposeLayout으로 BasicTextField 위에 절대 위치 오버레이.

---

### Callout `> [!type]`

**우선순위 높음. 자주 사용.**

파싱 규칙:
```
> [!NOTE] 제목
> 내용 줄 1
> 내용 줄 2
```

편집 모드 (커서가 Callout 내부):
- `> ` prefix 표시, raw 형태로 편집 가능

렌더링 모드 (커서가 밖):
- 커스텀 컨테이너 + 타입별 아이콘

---

### Embed `![[파일명]]`

**우선순위 높음. 자주 사용.**

지원 형식:
```
![[파일명]]           전체 파일 임베드
![[파일명#헤딩]]      헤딩 단위
![[파일명^블록ID]]    블록ID 단위
```

편집 모드: `![[파일명]]` raw 텍스트 표시
렌더링 모드: FileManager 비동기 로딩 + 재귀 파싱 결과 표시

---

### CodeBlock ` ``` `

배경색 블록: `DrawBehind`로 rounded 배경 박스 + 모노스페이스 SpanStyle.

편집 모드: ` ``` ` 구분자 표시 + 배경 유지

---

### Table `|`

그리드 레이아웃. placeholder 방식으로 삽입.

편집 모드: raw 텍스트 (`|` 구분자 포함) 표시

---

## Phase 4 구현 완료

### 스마트 Enter (리스트 자동 continuation)

`EditorInputTransformation`에 `handleSmartEnter()` 추가.
`\n` 삽입 감지 시 이전 줄의 블록 prefix를 자동 continuation.
prefix-only 줄이면 prefix를 제거하여 빈 줄로 변환.

### 서식 토글 (RawStyleToggle)

`TextFieldState`에서 마크다운 마커를 직접 삽입/제거하는 유틸리티.
- `toggleInlineStyle(state, marker)`: `**`, `*`, `` ` ``, `~~`, `==`
- `toggleHeading(state, level)`: 현재 줄의 `#` prefix
- `toggleBlockPrefix(state, prefix)`: 현재 줄의 `- `, `> ` 등

### 하드웨어 키보드 단축키 (EditorKeyboardShortcuts)

`MarkdownBasicTextField.kt`의 `BasicTextField`에 `Modifier.onPreviewKeyEvent` 추가.
Ctrl/Cmd+B/I/E, Ctrl/Cmd+Shift+S/X/H → `RawStyleToggle` 호출.

---

## 미구현 기능 — 향후 참고

> `reference_hyphen/`에 해당 기능의 레퍼런스 구현이 있다.
> 직접 사용하지 않고, 참고하여 MaChum에 최적화된 버전으로 새로 작성한다.

### Undo / Redo

Phase 2에서는 raw text 기반이므로 텍스트 + 커서 스냅샷만 저장하면 된다.
레퍼런스의 `HistoryManager.kt` 참고.

### 툴바 UI

`RawStyleToggle`을 연동하여 버튼 UI를 추가.
로직은 Phase 4에서 구현 완료, UI만 추가하면 됨.

### 클립보드 직렬화

raw text 기반이므로 선택 범위의 텍스트를 그대로 복사하면 마크다운이 유지된다.
커스텀 처리가 필요하면 레퍼런스의 `rememberMarkdownClipboard()` 참고.
