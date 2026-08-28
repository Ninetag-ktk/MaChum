# 블록 기반 마크다운 에디터 설계

> 역할: `composeApp/.../markdown/`의 현행 구현 계약과 검증 기준의 source of truth  
> 마지막 검토: 2026-08-28  
> 제품 우선순위: [product-roadmap.md](product-roadmap.md)  
> 앱 상위 구조: [architecture.md](architecture.md)

---

## 1. 현재 아키텍처

문서는 `List<EditorBlock>`으로 관리하고 각 블록을 독립 Composable로 렌더링한다. 과거의 단일 `BasicTextField + overlay` 구조는 제거됐다.

```text
raw markdown
  → MarkdownBlockParser.parse()
  → List<EditorBlock>
  → MarkdownBlockEditor
       ├── TextBlockEditor
       ├── CalloutBlockEditor
       ├── CodeBlockEditor
       └── TableBlockEditor
  → List<EditorBlock>.toMarkdown()
  → onValueChange(raw markdown)
```

각 블록은 안정 ID와 자체 `TextFieldState`를 가진다. 최상위는 `LazyColumn`, Callout body는 재귀 `Column`을 사용한다.

---

## 2. 핵심 파일

```text
markdown/
├── service/
│   ├── MarkdownStyleConfig.kt
│   └── util/EditorKeyboardShortcuts.kt
├── state/
│   ├── EditorBlock.kt
│   ├── MarkdownBlockParser.kt
│   ├── BlockOperations.kt
│   ├── DocumentSelection.kt
│   ├── MarkdownBlock.kt
│   ├── InlineStyleScanner.kt
│   ├── MarkdownPatternScanner.kt
│   ├── RawMarkdownOutputTransformation.kt
│   ├── EditorInputTransformation.kt
│   └── RawStyleToggle.kt
└── ui/
    ├── MarkdownBlockTextField.kt
    ├── MarkdownBlockEditor.kt
    ├── TextBlockEditor.kt
    ├── BlockDecorationDrawer.kt
    ├── selection/SelectionUiHelpers.kt
    └── block/
        ├── CalloutBlockEditor.kt
        ├── CodeBlockEditor.kt
        └── TableBlockEditor.kt
```

---

## 3. 블록 모델과 직렬화

### 3.1 `EditorBlock.Text`

Heading, list, blockquote, horizontal rule, 일반 텍스트와 인라인 Markdown을 포함한다.

필드:

- `id`
- `textFieldState`
- `rawMode`
- `rawOrigin`

`rawMode`와 `rawOrigin`은 dissolve 편집 중에만 사용하는 transient 상태다. 저장 결과에는 영향을 주지 않는다.

### 3.2 `EditorBlock.Callout`

- `calloutType`
- `titleState`
- `bodyBlocks`

body는 다시 `List<EditorBlock>`이므로 중첩 Callout, Code, Table을 포함할 수 있다.

### 3.3 `EditorBlock.Code`

language와 code `TextFieldState`만 저장한다. 여는 fence와 닫는 fence는 `toMarkdown()`이 생성한다.

### 3.4 `EditorBlock.Table`

- `headerStates`
- `rowStates`

구분자 행은 state에 저장하지 않고 `toMarkdown()`에서 생성한다.

### 3.5 HorizontalRule와 Embed

- HorizontalRule은 sealed class에 남아 있지만 현재 parser는 TextBlock 안의 인라인 렌더링으로 처리한다.
- Embed 클래스와 dissolve 경로는 남아 있지만 parser 변환은 비활성화되어 일반 TextBlock으로 편집한다.

### 3.6 빈 줄

블록과 블록 사이의 빈 줄은 `BLANK_LINE_MARKER = "\u200B"`를 가진 TextBlock으로 표현한다.

- 빈 문자열은 TextField 높이가 0이 될 수 있음
- `"\n"`은 2줄 높이
- ZWSP는 보이지 않는 1줄 높이를 제공
- `toMarkdown()`에서는 ZWSP를 빈 문자열로 치환
- 사용자가 placeholder에 입력하면 ZWSP를 즉시 제거

`List<EditorBlock>.toMarkdown()`은 모든 블록을 `"\n"`으로 연결한다. 빈 줄 표현은 각 TextBlock의 선행·후행 newline과 ZWSP가 담당한다.

---

## 4. 파서 계약

`MarkdownBlockParser.parse(markdown, excludeCalloutTypes)`가 raw Markdown을 블록 리스트로 바꾼다.

### Text 누적과 빈 줄

- 일반 줄은 하나의 TextBlock에 누적
- 빈 줄은 `pendingNewlines`로 보류
- 특수 블록 앞뒤의 newline을 보존
- Block → 빈 줄 → Block은 ZWSP TextBlock 생성

### Code

- 여는 ``` 뒤에 닫는 ```가 있을 때만 Code로 변환
- 닫는 fence가 없으면 일반 TextBlock 유지

### Callout

```markdown
> [!NOTE] 제목
> 본문
```

- callout type은 대소문자를 보존
- body의 `>` prefix를 한 단계 제거한 뒤 재귀 파싱
- DL body 내부에서는 DL 중첩을 금지
- Standard Callout은 다른 Callout을 중첩 가능

### Table

- `|`로 시작하는 연속 행
- 적어도 2행
- 두 번째 행에 구분자(`---`) 필요
- 한 줄 `|col|` 또는 구분자 없는 표 모양 텍스트는 TextBlock 유지

현재 파서는 데이터 행의 열 수를 헤더에 맞춰 정규화하지 않는다. 외부 Markdown 안전성을 위해 최대 열 수 기준 정규화가 P0 작업이다.

### HorizontalRule

`---`, `***`, `___`는 별도 블록으로 분리하지 않는다. 포커스가 없으면 `BlockDecorationDrawer`가 divider를 그리고, 포커스 줄에서는 raw marker를 표시한다.

### Embed

`![[target]]`의 독립 Embed 변환은 비활성화됐다. 미리보기 박스가 없는 상태에서 변환할 경우 포커스 끊김만 발생하기 때문이다.

---

## 5. TextBlock 편집

`TextBlockEditor`는 `BasicTextField`와 OutputTransformation을 사용한다.

### 지원 인라인 표현

| 문법 | 상태 |
|---|---|
| Heading `#`~`######` | 구현됨 |
| Bold, Italic, BoldItalic | 구현됨 |
| Strikethrough, Highlight | 구현됨 |
| Inline code | 구현됨 |
| Wiki link | 구현됨 |
| External link | 구현됨 |
| Bullet·ordered list | 구현됨 |
| Blockquote | 구현됨 |
| Horizontal rule | 인라인 divider |

### 블록 승격

일반 TextBlock은 `snapshotFlow`와 150ms debounce로 내용을 감시한다. Callout, 닫힌 Code fence, 유효한 Table이 나타나면 `BlockOperations.tryReparse()`로 분리·승격한다.

`rawMode=true`인 TextBlock은 편집 중 reparse를 하지 않는다. dissolve 계약은 9절을 따른다.

### 키보드 이동

- `←` at offset 0: 이전 블록의 끝으로 이동
- `→` at text.length: 다음 블록의 시작으로 이동
- `↑` 첫 논리 줄: 이전 블록
- `↓` 마지막 논리 줄: 다음 블록
- Backspace at offset 0: 이전 Text와 병합하거나 이전 특수 블록 dissolve

현재 첫/마지막 줄 판정은 일부 경로에서 `\n` 기준이다. soft wrap된 시각 줄의 ↑/↓ 처리는 완전히 해결되지 않았다.

### Smart Enter

빈 마지막 줄에서 Enter로 박스를 탈출하는 기능은 박스 내부에만 적용한다.

- Callout body의 TextBlock: 활성
- 최상위 일반 TextBlock: 비활성

최상위 TextBlock은 박스에 갇히지 않으므로 일반 newline 입력을 유지한다.

---

## 6. Callout

### 종류

Standard Callout은 세로 레이아웃, DL Callout은 title과 body가 가로로 배치되는 대화형 레이아웃이다.

지원 style type:

- NOTE
- TIP
- IMPORTANT
- WARNING
- DANGER
- CAUTION
- QUESTION
- SUCCESS
- DL

### 진입 정책

- 위 블록에서 `↓`: title 시작
- 아래 블록에서 `↑`: 가장 깊은 body 마지막 Text의 끝
- 다음 블록에서 `←`: `↑`와 동일
- body가 없으면 역방향 진입은 title 끝

`bottomEntryFRMap`을 사용해 가장 깊은 body FocusRequester를 부모까지 전달한다. FocusRequester 요청만으로는 예전 selection이 복원될 수 있어, 역방향 진입 시 마지막 Text의 selection을 명시적으로 끝으로 설정한다.

### title 동작

- Enter 또는 Tab: body 생성 또는 body 첫 블록 진입
- Backspace at offset 0: Callout 전체 dissolve
- Standard `↑`: 이전 블록
- Standard `↓`: body 또는 다음 블록
- DL `→` at end: body
- DL `↑/↓`: 외부 블록

### body 동작

- 첫 블록의 위쪽 경계: title 또는 부모 selection 정책
- 마지막 블록의 아래쪽 경계: 다음 외부 블록
- 빈 마지막 줄 + Enter: Callout 밖으로 탈출
- DL body에서 `←` at 0: title 끝

### stale closure 불변조건

Lazy item callback이 `blocks`, `index`, `allBlocks`를 캡처할 때 `rememberUpdatedState`를 사용해야 한다. 그렇지 않으면 recomposition이 skip된 item의 callback이 오래된 Callout body를 덮어쓸 수 있다.

---

## 7. CodeBlock

- monospace TextField
- language는 표시·직렬화에 사용
- `↑/↓`로 인접 블록 이동
- 빈 마지막 줄 + Enter: trailing newline 제거 후 다음 블록으로 탈출
- 빈 Code에서 Backspace at start: 기존 merge/delete 규칙 적용
- document selection에서는 현재 atomic 블록

Code 내부 native selection은 동작하지만 cross-block selection으로 확장할 때는 전체 블록을 선택한다.

---

## 8. TableBlockEditor

### 현재 구현

- 셀별 `TextFieldState`
- `focusGrid[row][col]` 2차원 포커스
- 좌우 셀 이동 후 행 경계 이동
- 상하 같은 열 이동
- Tab: 다음 열, 마지막 열이면 열 추가
- Enter: 다음 행, 마지막 행이면 행 추가
- 우측 `+`: 열 추가
- 하단 `+`: 행 추가
- 아래 블록에서 `↑`: 마지막 행 첫 열
- `rememberUpdatedState(block)`으로 최신 table 상태 사용
- document selection에서는 Table 전체 atomic

### P0 안정화

비정형 행을 최대 열 수로 정규화해야 한다.

```markdown
| A | B |
| --- | --- |
| 1 | 2 | 3 |
```

현재 UI grid는 헤더 열 수로 생성하지만 행 렌더링은 실제 행 셀 수를 사용한다. 데이터 손실 없이 모든 행과 헤더를 최대 열 수로 맞추는 정책을 사용한다.

### 단기 개선

1. Shift+Tab 역방향 이동
2. 행·열 삭제
3. 직접 dissolve 트리거
4. focus와 cursor 위치 정책 보완
5. 접근성 semantics

### 장기 셀 selection

Excel/Google Sheets 방식의 사각형 selection을 사용한다.

- `SelectionEndpoint`에 TableCell 좌표 추가
- Shift+방향키로 anchor/focus 사각형 확장
- 셀별 selection 배경
- Markdown 또는 TSV 복사
- Delete/Cut/Paste
- 경계에서 Table 전체 atomic selection으로 승격

일반 cross-block delete/paste가 선행되어야 한다.

---

## 9. dissolve 현행 정책

dissolve는 특수 블록을 raw Markdown TextBlock으로 풀어 편집하는 기능이다.

### 트리거

| 트리거 | 동작 |
|---|---|
| Code/Callout/Table/Embed 다음 TextBlock의 offset 0 Backspace | 직전 특수 블록만 raw Text로 교체 |
| Callout title offset 0 Backspace | Callout 자체를 raw Text로 교체 |

인접 TextBlock과 자동 merge하지 않는다.

### 결과

```kotlin
EditorBlock.Text(
    rawMode = true,
    rawOrigin = RawOrigin.CODE, // 또는 CALLOUT/TABLE/EMBED
)
```

커서는 raw Markdown 끝으로 이동한다.

### reparse 트리거

| 상태 | 편집 중 150ms 감시 | focus-out 200ms |
|---|---:|---:|
| 일반 Text | 사용 | 사용 안 함 |
| rawMode Text | 사용 안 함 | 한 번 실행 |

편집 중에는 raw 표현을 절대 박스로 되돌리지 않는다. focus-out 뒤 200ms 안에 다시 포커스를 받으면 coroutine이 취소되어 raw 상태를 유지한다.

### focus-out 결과

- marker가 살아 있고 단일 특수 블록으로 parse: 박스 렌더링 복귀
- marker가 깨지고 단일 Text로 parse: `rawMode=false` 일반 Text
- 여러 블록으로 parse: 일반 분리

focus-out reparse는 silent 경로를 사용해 이미 이동한 사용자 focus를 새 박스로 끌어오지 않는다.

### 자동 정리

- raw Text가 완전히 비면 같은 ID와 TextFieldState를 유지한 채 rawMode만 해제
- Block 유형 박스의 title/body/cell이 잠깐 비었다는 이유로 자동 격하하지 않음
- ZWSP placeholder에 입력하면 ZWSP 제거
- Embed는 변환이 비활성이라 현재 promotion 경로에 도달하지 않음

### dissolve가 아닌 동작

- Callout body 첫 블록 offset 0 Backspace: title로 이동
- 빈 Code의 기존 delete/merge
- Table 셀 내부 Backspace: 셀 편집
- 일반 Text offset 0 Backspace: 이전 Text와 merge

---

## 10. Cross-block selection

### 모델

```kotlin
sealed class DocumentSelection {
    data object None
    data class Multi(
        val anchor: SelectionEndpoint,
        val focus: SelectionEndpoint,
    )
}

data class SelectionEndpoint(
    val containerPath: List<String>,
    val blockId: String,
    val offset: Int,
)
```

`containerPath`는 최상위에서 현재 Callout body까지의 ID chain이다.

### atomic 정책

| 블록 | 외부 선택 |
|---|---|
| Text | offset 기반 부분 선택 가능 |
| Callout | title+body 전체 atomic |
| Code | atomic |
| Table | atomic |
| Embed, HorizontalRule | atomic |

Callout body 안에서는 별도 컨테이너로 cross-selection할 수 있다. selection이 body 밖으로 나가면 부모 Callout 전체 atomic selection으로 승격한다.

### 구현 완료

- Ctrl/Cmd+A 전체 선택
- Ctrl/Cmd+C raw Markdown 복사
- Esc와 일반 방향키로 Multi 해제
- pointer press로 Multi 해제
- native selection 색과 block selection 배경 통합
- 외부 Text 사이 Shift+↑/↓ selection 시작
- atomic 블록 진입 시 해당 블록만 선택
- Callout title/body 경계에서 부모 Callout atomic 선택
- DL title Shift+→
- 같은 컨테이너 안의 Shift+↑/↓ 누적 확장
- focus endpoint 자동 스크롤과 focus 이동

### 누적 확장의 소유권

첫 Shift+↑/↓는 각 블록 handler가 selection을 시작한다. Multi가 이미 존재하면 최상위 `documentSelectionShortcuts`의 preview handler가 다음 입력을 소유한다.

이 구조는 focus 이동 timing에 selection 로직이 의존하지 않게 한다. focus 이동은 화면과 cursor를 따라가게 하는 시각적 효과일 뿐 selection authority가 아니다.

### 현재 한계

- 누적 확장은 현재 container 안에서만 가능
- Callout body에서 외부 컨테이너로 이어지는 Scope B traversal 미구현
- 마우스 drag 미구현
- Cut/Paste 미구현
- Multi 상태 입력 대체 미구현
- 글자·단어·줄 단위 cross-block Shift 이동 미구현
- Table 셀 selection 미구현

### Cut/Paste 권장 단계

1. `deleteSelection` + Ctrl+X
2. Multi selection 대체 Ctrl+V
3. selection 상태에서 일반 입력 대체

cursor-only paste는 native TextField 동작을 유지한다. 입력 대체는 IME와 비동기 focus race가 있어 마지막에 독립 검증한다.

---

## 11. 구현 상태

| 영역 | 상태 |
|---|---|
| 블록 모델·parse·serialize | 핵심 자동 검증 완료 |
| Text/Callout/Code/Table 렌더링 | 구현됨 |
| 블록 간 기본 이동 | 구현됨 |
| soft wrap ↑/↓ | 부분 구현 |
| Smart Enter | 구현됨 |
| dissolve v3 | 구현·수동 검증 필요 |
| selection foundation·Scope A | 구현·수동 검증 필요 |
| Undo/Redo | 미구현 |
| selection Cut/Paste/replace | 미구현 |
| 마우스 drag | 미구현 |
| Table 셀 selection | 미구현 |
| Embed 박스 | 비활성 |
| M3 컬러 정리 | 부분 구현 |

---

## 12. 필수 불변조건

1. Lazy item callback이 외부 block list나 index를 캡처하면 `rememberUpdatedState`를 사용한다.
2. 외부 value가 실제 내부 value와 다를 때만 전체 재파싱한다.
3. rawMode 블록은 편집 중 reparse하지 않는다.
4. rawMode focus-out reparse는 사용자 focus를 이동시키지 않는다.
5. Callout 역방향 진입은 body의 가장 깊은 마지막 Text 끝으로 이동한다.
6. DL body 안에서 DL Callout을 다시 생성하지 않는다.
7. 닫히지 않은 Code fence와 한 줄 Table은 Text로 유지한다.
8. Embed 박스가 구현되기 전 parser 변환을 다시 활성화하지 않는다.
9. ZWSP는 직렬화에서 제거하고 사용자 입력 시작 시 state에서도 제거한다.
10. document selection의 누적 입력은 Multi 상태에서 최상위 preview handler가 소유한다.

---

## 13. 검증 기준

### 자동 테스트 우선순위

1. parse → serialize round-trip
2. 빈 줄과 ZWSP
3. 닫힌·닫히지 않은 Code
4. 중첩 Callout과 DL 중첩 제한
5. 정상·비정형 Table
6. tryReparse split과 focus target
7. dissolve 후 marker 유지·파괴
8. DocumentSelection normalize·extractMarkdown·nextFocusEndpoint

### dissolve 수동 시나리오

1. 특수 블록 다음 Text 시작에서 Backspace
2. raw 블록을 계속 편집하는 동안 박스로 복귀하지 않음
3. marker 유지 후 focus-out하면 박스로 복귀
4. marker 파괴 후 focus-out하면 일반 Text
5. 200ms 안에 focus 복귀하면 raw 유지
6. Callout title dissolve
7. Callout body 시작 Backspace는 title 이동

### selection 수동 시나리오

1. Ctrl/Cmd+A와 복사 결과
2. Esc, 방향키, pointer press 해제
3. Text ↔ Text Shift 확장
4. Text → atomic 진입 시 atomic만 선택
5. Callout title과 body 경계 선택
6. 같은 컨테이너 누적 확장과 역방향 축소
7. 화면 밖 focus endpoint 스크롤

### Table 수동 시나리오

1. 방향키 셀 이동과 외부 블록 탈출
2. Tab 열 추가
3. Enter 행 추가
4. `+` 버튼 클릭 후 편집 내용 보존
5. 아래 블록에서 ↑ 진입
6. 열 수가 다른 외부 Markdown 로드

플랫폼별 실행 순서와 합격 기준은 [P0 수동 테스트](p0-manual-test.md)에서 관리한다.

---

## 14. 다음 작업 권장 순서

1. 파서·직렬화·BlockOperations 자동 테스트
2. Table 열 수 정규화
3. 저장·외부 변경 경합 검증
4. Undo/Redo
5. Cross-block delete/Cut
6. Multi paste
7. selection 입력 대체
8. 마우스 drag
9. Table 셀 selection
10. Embed와 M3 시각 정리
