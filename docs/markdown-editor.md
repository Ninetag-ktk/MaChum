# 블록 기반 마크다운 에디터 설계

> 역할: `composeApp/.../markdown/`의 현행 구현 계약과 검증 기준의 source of truth  
> 마지막 검토: 2026-08-31
>
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

### 문서 생명주기와 외부 값 동기화

`HorizontalPager`와 `MarkdownBlockTextField`는 모두 `FileKey` 기반 document key를 사용한다. 파일이 바뀌면
블록·selection·focus requester·지연 reparse effect를 포함한 에디터 컴포지션 전체가 새 수명으로 생성된다.
`EditorPage`는 ViewModel이나 전체 cache를 직접 구독하지 않고 상위 화면에서 현재 파일의 `NoteFile`과 callback만 받는다.

같은 파일의 외부 변경은 `EditorDocumentValueCoordinator`가 조정한다. 내부 입력을 부모가 다시 내려준 값은 재파싱하지 않고,
실제 외부 교체만 새 블록으로 파싱한다. 외부 교체마다 revision을 증가시켜 이전 블록 snapshot의 늦은
`onValueChange`가 새 외부 값을 덮어쓰지 못하게 한다. 최초 렌더링의 동일 값도 편집 이벤트로 전달하지 않는다.

블록 간 예약 포커스는 UI 비의존 `EditorFocusCoordinator`가 최신 `EditorFocusRequest` 하나만 소유한다.
외부 value revision은 root editor의 `focusEpoch`로도 사용하여 문서 교체 전 요청을 폐기한다. 실제 스크롤,
`FocusRequester` 실행과 `TextLayoutResult` 기반 x 좌표 계산은 Compose 계층에 남긴다.

구조 편집은 UI 비의존 `EditorMutationDispatcher`가 기존 `BlockOperations`를 호출하고, 새 blocks와 선택적인
`EditorFocusIntent`를 하나의 `EditorMutation`으로 반환한다. history나 새로운 reducer는 아직 넣지 않는다.

블록별 event callback은 `BlockNavigation` 하나를 유지하되 내부를 `focus`, `mutation`, `selection` 세 action
그룹으로 나눈다. 블록 에디터는 자신이 요청하는 역할만 참조하고, 실제 대상 계산·구조 변경·selection 갱신은
`MarkdownBlockEditor`가 기존 순서대로 연결한다.

Cross-block selection의 다음 상태 계산은 UI 비의존 `EditorSelectionCoordinator`가 담당한다. 문서 selection
상태 자체는 계속 `MarkdownBlockTextField`가 단일 소유하며, Compose helper는 키 입력·클립보드·포커스 이벤트에
coordinator 결과를 적용한다. 따라서 별도 reducer나 중복 selection 저장소는 만들지 않는다.

Standard·DL Callout의 body 생성·진입·탈출 목표는 UI 비의존 `CalloutBodyPolicy`가 결정한다. Compose 계층은
공통 runtime에서 FocusRequester와 생성 후 지연 포커스를 소유하고, `CalloutBodyEditor` 하나로 재귀 body를 렌더링한다.
세로·가로 레이아웃과 title 키 차이는 각 Callout Composable에 그대로 남긴다.

### 컴포지션 정비 일정

폴더-존 최소 수직 흐름과 전환 UI의 회귀 검증을 마친 직후, frontmatter 자동화와 Undo/Redo보다 먼저
에디터 컴포지션 정비를 수행한다. 상세 순서와 완료 조건은 [제품 로드맵의 에디터 컴포지션 정비 게이트](product-roadmap.md#에디터-컴포지션-정비-게이트-2단계-완료-직후)를 따른다.

정비 중에는 블록 모델·Markdown 직렬화 결과·키보드 동작을 바꾸지 않는다. 상태 소유권과 effect 생명주기,
callback 경계, recomposition 범위만 정리하고 기능 변경은 별도 작업으로 분리한다.

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
│   ├── EditorDocumentValueCoordinator.kt
│   ├── EditorFocusCoordinator.kt
│   ├── EditorMutationDispatcher.kt
│   ├── EditorSelectionCoordinator.kt
│   ├── EditorBlockSnapshot.kt
│   ├── EditorHistory.kt
│   ├── CalloutBodyPolicy.kt
│   └── RawStyleToggle.kt
└── ui/
    ├── BlockNavigation.kt
    ├── MarkdownBlockTextField.kt
    ├── MarkdownBlockEditor.kt
    ├── TextBlockEditor.kt
    ├── BlockDecorationDrawer.kt
    ├── diagnostics/EditorRecompositionDiagnostics.kt
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

`MarkdownBlockParser.parse("")`의 순수 파서 결과는 빈 목록이지만, 편집기 진입 경계는 빈 본문을 직렬화 값이
빈 문자열인 `EditorBlock.Text` 하나로 보정한다. 새 파일은 visible placeholder 없이 빈 `BasicTextField`를 즉시
표시한다. 이 유일한 빈 최상위 Text는 viewport 높이를 입력 hit area로 사용하므로 본문 여백을 클릭해도 focus할 수
있지만, 사용자가 입력하기 전에는 불필요한 본문 문자를 저장하지 않는다. 내용이 있는 일반 Text와 중첩 Text는 기존
자연 높이를 유지한다.

최상위 문서의 마지막 블록이 Callout·Code·Table 같은 비-Text이면 그 아래에 visible placeholder 없는 저장되지 않는
가상 빈 Text 입력면을 하나 표시한다. 마지막 실제 블록 아래부터 현재 viewport 끝까지의 남은 높이를 hit area로
사용하되, 내용이 viewport를 넘으면 일반 Text의 최소 높이만 확보한다. 이 입력면은 `blocks`에 포함되지 않으므로
표시·focus만으로 canonical Markdown, autosave, diff,
history가 바뀌지 않는다. IME composition이 끝난 첫 비어 있지 않은 편집에서만 같은 block ID와 `TextFieldState`를
실제 마지막 `EditorBlock.Text`로 append하고 기존 focus coordinator로 삽입 끝 위치를 다시 요청한다. 따라서 첫 입력과
cursor를 보존하며, append는 일반 구조 transaction 한 건이어서 Undo하면 원래 특수 블록 끝 문서와 가상 입력면으로
돌아간다. 이미 Text로 끝나는 문서, 빈 문서, 중첩 Callout body에는 중복 입력면을 만들지 않는다.

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
- 사용자가 ZWSP marker 블록에 입력하면 ZWSP를 즉시 제거

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

파서는 헤더와 데이터 행의 최대 열 수를 기준으로 모든 행을 정규화한다. 비정형 Table 자동 테스트와 수동 로드 검증을 완료했다.

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

### 후속 회귀: 마지막 atomic 블록에서 trailing 입력면으로 진입

최상위 마지막 Callout·Code·Table 등 비-Text 블록에서 수정 키 없는 `↓`를 누르면 저장되지 않은 trailing 입력면의
offset 0으로 focus해야 한다. focus 이동만으로 블록을 materialize하거나 canonical Markdown을 변경해서는 안 된다.

- `Enter`는 각 특수 블록의 기존 로컬 편집 의미를 유지한다.
- 마우스로 남은 viewport 영역을 클릭하는 경로는 현재 trailing 입력면을 직접 focus한다.
- 키보드 진입 후에도 IME composition이 끝난 첫 확정 입력에서만 materialize한다.
- Callout·Code·Table 및 이후 추가되는 모든 atomic 블록별로 focus-only 직렬화 불변, 첫 입력 보존, Undo 복원을
  자동·화면 회귀 테스트해야 한다.

이 항목은 현재 넓은 클릭 영역 작업과 분리해 후속 구현한다.

### 활성 줄 인라인 preview와 후속 inline-token raw

현재 중간 계약은 focus된 활성 줄에서 Markdown 마커와 블록 prefix를 raw로 노출하되, Bold·Italic·Strikethrough·
Highlight·Inline code·Link와 heading content의 `SpanStyle`은 즉시 적용하는 것이다. 따라서 `**굵게**`의 별표를 보면서
본문의 굵기를 확인할 수 있고, inline code 배경도 유지된다. `rawMode=true`인 dissolve 편집만 마커·내용 스타일·코드
배경을 모두 끄는 완전 raw 상태를 유지한다. unfocused 줄은 마커 숨김을 포함한 전체 preview를 적용한다.

후속 고급안은 커서가 속한 inline construct/token의 마커만 raw로 노출하고 같은 활성 줄의 다른 표현 마커는 숨겨
live preview를 유지하는 방식이다.

이 변경은 단순 색상·SpanStyle 수정이 아니다. scanner 결과에 token 범위 메타데이터를 유지하고 marker 경계 cursor,
token 내부·외부 selection, IME composition, Undo, output offset 안정성을 함께 보장해야 한다. Bold·Italic·Strike·Inline
code 각각의 시작/끝 마커 경계까지 자동·화면 테스트가 필요하므로 별도 작업으로 보류한다.

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

`CalloutBodyPolicy`는 body 유무와 Standard·DL 레이아웃을 입력으로 받아 생성, body 첫 위치, title 끝,
이전·다음 외부 블록 중 하나를 목표로 반환한다. `CalloutBodyRuntime`은 이 목표를 실제 block 변경과 focus 요청으로
연결하며 두 레이아웃에서 한 번만 생성된다. 중첩 body 렌더링도 공통 `CalloutBodyEditor`가 담당한다.

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
- ZWSP marker 블록에 입력하면 ZWSP 제거
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

endpoint의 `containerPath`는 항상 root 기준 absolute path다. 재귀 `MarkdownBlockEditor`는 body의 로컬 block
목록을 렌더링하므로, 강조 범위를 계산할 때만 현재 컨테이너 prefix를 제거해 정규화하고 결과를 다시 absolute
path로 복원한다. 현재 컨테이너 밖 endpoint가 섞이면 로컬 강조를 만들지 않고 상위 editor의 atomic 승격에 맡긴다.

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
- 같은 container의 keyboard Delete/Cut/Paste 구현 완료
- 같은 container의 Multi 상태 일반 문자·IME 확정 입력 대체 구현 완료
- Windows 자동화 도구가 물리 한/영 키를 지원하지 않아 실제 IME composition 과정은 수동 확인 필요
- 글자·단어·줄 단위 cross-block Shift 이동 미구현
- Table 셀 selection 미구현

### Cut/Paste 구현 단계

1. `replaceSelectedMarkdown` 기반 Delete/Backspace + Ctrl+X — 완료
2. Multi selection 대체 Ctrl+V — 완료
3. selection 상태에서 일반 입력·IME 확정 문자열 대체 — 완료

cursor-only paste는 native TextField 동작을 유지한다. Multi 상태에서는 문서 단위 input capture가 독립 TextField 사이의
입력 소유권을 하나로 모으고, IME composition이 끝난 문자열만 `replaceSelectedText`로 적용한다. 치환 뒤에는 새 Text의
삽입 끝 위치만 일회성으로 요청하며 이전 focus나 selection은 복원하지 않는다. 실제 TextField로 focus가 넘어가기 전에
추가 확정 입력이 오면 capture가 `continueTextInputAt`으로 같은 endpoint에 이어 붙이고, 최신 focus 요청으로 offset을
전진시킨다. handoff는 임의 timeout이 아니라 focus coordinator의 요청 실행 완료 신호로 종료한다.

---

## 11. 구현 상태

| 영역 | 상태 |
|---|---|
| 블록 모델·parse·serialize | 핵심 자동 검증 완료 |
| Text/Callout/Code/Table 렌더링 | 구현됨 |
| 블록 간 기본 이동 | 구현됨 |
| 예약 focus coordinator | 구현·자동/수동 검증 완료 |
| 구조 편집 mutation dispatcher | 구현·자동/수동 검증 완료 |
| Callout 공통 body 정책 | 구현·자동/수동 검증 완료 |
| soft wrap ↑/↓ | 부분 구현 |
| Smart Enter | 구현됨 |
| dissolve v3 | 구현·수동 검증 완료 |
| selection foundation·Scope A | 구현·수동 검증 완료 |
| Undo/Redo | 내용 복원 구현, 자동/Desktop 검증 완료 |
| selection Delete/Cut/Paste | 구현·자동/Desktop 검증 완료 |
| Multi selection 일반 입력 대체 | 구현·자동/Desktop 확정 문자열 검증 완료, 실제 IME 조합 수동 확인 필요 |
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

1. 폴더-존 최소 수직 흐름과 전환 UI 완료
2. 에디터 컴포지션 정비 게이트의 구조 분리·selection 조정 책임·recomposition 측정 완료
3. frontmatter·메타 인덱스 완료
4. 문서 단위 Undo/Redo 내용 이력 MVP 완료
5. 첫 전체 구조·파일 최적화 게이트 완료
6. Cross-block delete/Cut 완료
7. Multi paste 완료
8. selection 일반 문자·IME 확정 문자열 입력 대체 완료
9. 실제 Windows 한글 IME composition 수동 확인 — 다음 검증
10. 조건부 마우스 drag
11. Table 셀 selection
12. Embed와 M3 시각 정리

---

## 15. 완성 목표와 범위

현재 에디터는 기본 집필이 가능한 수직 흐름이지만 범용 편집기로서는 미완성이다. 다음 작업은
Obsidian이나 워드프로세서 전체 기능을 복제하는 것이 아니라, MaChum의 장문 집필에 필요한 데이터 안전성과
키보드 생산성을 먼저 완성하는 것을 목표로 한다.

### 1차 완성 기준

- Text·Callout·Code·Table의 기존 parse·serialize 계약 유지
- 파일·외부 변경·블록 재구성 뒤 stale focus나 stale write 없음
- 문서 단위 Undo/Redo
- 키보드 기반 cross-block Delete/Cut/Paste
- Multi selection의 명시적인 입력 대체
- Desktop과 Android에서 저장 결과가 동일함
- 핵심 문서·명령·history 로직을 Compose 없이 자동 테스트할 수 있음

### 1차 범위에서 제외

- Obsidian 플러그인·렌더러 호환
- 운영체제 네이티브 selection handle이 여러 `BasicTextField`를 연속으로 가로지르는 동작
- Table 사각형 셀 selection
- 블록 경계를 넘는 정밀 마우스 drag selection
- Embed 미리보기와 파일 해석

제외 항목은 구현 불가를 의미하지 않는다. 현재 독립 `TextFieldState` 블록 구조에서 비용이 지나치게 크거나
기본 집필 안전성과 직접 관련이 없어 후속 결정 게이트로 보낸다.

---

## 16. 목표 아키텍처

현재의 `EditorBlock`, `MarkdownBlockParser`, `BlockOperations`, `DocumentSelection` 계약은 유지한다.
새 계층은 기존 동작을 재작성하지 않고 `MarkdownBlockEditor`에 섞인 조정 책임만 이동한다.

```text
MarkdownBlockTextField
  └── EditorDocumentState
       ├── blocks
       ├── documentSelection
       ├── EditorDocumentValueCoordinator
       └── EditorHistory
            ↓
MarkdownBlockEditor                 렌더링·블록별 이벤트 연결
  ├── EditorActionDispatcher        명령 → 블록 변경 결과
  ├── EditorFocusCoordinator        focus intent 상태와 순서
  ├── DocumentSelection operations  normalize·delete·paste
  └── BlockItem
       ├── TextBlockEditor
       ├── CalloutBlockEditor
       ├── CodeBlockEditor
       └── TableBlockEditor
```

### 16.1 `EditorDocumentState`

문서 한 개의 런타임 authority다. ViewModel이나 파일 저장을 소유하지 않는다.

- 현재 `List<EditorBlock>`
- 현재 `DocumentSelection`
- 외부 value revision
- Undo/Redo history
- 현재 문서에서 실행 중인 편집 transaction

`MainViewModel`은 계속 Markdown 문자열과 파일 저장만 소유한다. 따라서 editor history와 500ms 파일 저장
debounce는 서로 독립이며, Undo 결과도 일반 `onValueChange`와 같은 저장 경로를 사용한다.

### 16.2 `EditorMutationDispatcher`

UI callback이 직접 여러 Compose state를 변경하지 않고 명령 결과를 반환한다.

```kotlin
data class EditorMutation(
    val blocks: List<EditorBlock>,
    val focusIntent: EditorFocusIntent?,
)
```

초기 구현에서는 기존 `BlockOperations`를 호출하는 얇은 dispatcher로 시작한다. 새로운 reducer 체계를
별도로 복제하거나 모든 키 입력을 명령 객체로 바꾸지 않는다. 2026-08-31 기준 split·빈 줄 split·merge,
특수 블록 fallback dissolve, reparse·silent reparse, raw mode 해제를 이 경로로 통일했다. `SplitResult`의
cursor offset은 nullable로 명확히 하여 실제 offset이 있는 merge에서만 `AtOffset`을 전달한다.

Undo/Redo 단계에서 selection과 history policy가 실제로 필요해질 때 `EditorMutation`을 확장한다. 현재 사용하지
않는 필드를 미리 추가하지 않는다.

### 16.3 `EditorFocusCoordinator`

`FocusRequester` 자체는 Compose UI에 남긴다. coordinator는 실행할 의도와 최신 request만 소유한다.

```kotlin
data class EditorFocusRequest(
    val id: Long,
    val targetBlockId: String,
    val cursorHint: CursorHint?,
    val preferBottomEntry: Boolean,
)
```

`CursorHint`도 state 계층으로 옮겨 Text 시작·끝·정확한 offset·시각적 x 좌표를 표현한다.
`FocusRequester`와 `TextLayoutResult`만 Composable 실행 계층에서 사용한다.

- 블록 이동은 coordinator 요청을 만들고, 구조 편집은 dispatcher의 `EditorFocusIntent`를 coordinator 요청으로 변환
- Composable effect 한 곳이 scroll → focus → cursor 순서로 실행
- 더 최신 request가 오면 이전 지연 작업 취소
- 대상 block ID가 사라졌으면 조용히 폐기
- 임의의 10/50/100ms 지연은 노드가 아직 compose되지 않은 경로에만 제한하고 테스트 가능한 상태 전이와 분리

2026-08-31 구현 범위:

- `pendingFocusBlockId`, cursor hint, bottom-entry 여부와 request counter를 coordinator 요청 하나로 통합
- 지연·재시도 구간마다 request ID를 검사해 이전 요청의 뒤늦은 focus·cursor 적용 차단
- 요청 대상이 현재 블록 목록에서 사라지면 취소
- 외부 문서 revision 변경 시 coordinator 수명을 재생성
- Table 셀 내부 focus, Embed promotion 후 focus 복구와 selection endpoint의 파생 focus effect는 로컬 유지

### 16.4 `BlockNavigation` 역할 그룹

블록 editor parameter는 `BlockNavigation` 하나를 유지해 호출부를 늘리지 않고, callback만 다음 세 역할로 구분한다.

- `BlockFocusActions`: 이전·다음·좌측 이동과 시각적 x 좌표를 보존한 이동
- `BlockMutationActions`: merge·split·reparse·dissolve·raw mode 해제
- `BlockSelectionActions`: 이전·다음 확장과 atomic 블록 선택

그룹은 UI event를 상위에 알리는 얇은 경계이며 별도 상태나 reducer를 소유하지 않는다. 중첩 Callout의 body
탈출도 같은 focus·selection 그룹을 전달하므로 기존 부모 경계 정책을 유지한다. 기본 callback은 모두 no-op이고,
그룹별 callback 독립성과 기본 호출 안전성을 common test로 고정한다.

2026-08-31 Desktop에서는 Text↔Callout 방향키 이동, Callout dissolve→silent reparse, 기존 Text focus 보존,
`Shift+↓` 블록 selection 확장을 같은 테스트 문서에서 확인했다.

### 16.5 `CalloutBodyPolicy`

Standard와 DL의 시각 레이아웃은 분리하되 다음 body 계약은 공유한다.

- Enter·Tab의 빈 body 생성 또는 기존 body 첫 위치 진입
- Standard title `↓`, DL title 끝 `→`의 body 진입
- body 이전·다음·좌측 경계에서 title 끝 또는 외부 블록으로 탈출
- body가 하나·여러 개·중첩 Callout 끝인 경우의 bottom-entry target 선택

`CalloutBodyPolicy`는 `CalloutBodyAction`만 반환하는 순수 결정 계층이다. 실제 `TextFieldState` 생성,
50ms 지연 후 FocusRequester 실행, title·body cursor 변경은 `CalloutBodyRuntime`에 남긴다. 재귀
`MarkdownBlockEditor`의 공통 parameter는 `CalloutBodyEditor`가 한 번만 연결한다.

자동 테스트는 Standard·DL title 진입, 경계 탈출과 nested-bottom 우선순위를 행렬로 검증한다. Desktop에서는
Standard body 생성·title 복귀, DL title↔body 좌우 이동, 다음 외부 블록 이동과 body 끝 역진입을 확인했다.
테스트 Vault의 `1. Concept/2. Callout body policy.md`를 DL 회귀 fixture로 유지한다.

### 16.6 `EditorSelectionCoordinator`

`EditorSelectionCoordinator`는 다음 결정을 Compose 없이 계산한다.

- 빈 문서가 아닌 경우의 전체 선택 endpoint
- 이미 시작된 Multi selection의 이전·다음 focus 이동
- Text에서 인접 Text로 selection을 시작할 때의 anchor·focus
- Code·Callout·Table·Embed 등 atomic 이웃의 전체 선택
- root 경계의 selection 보존과 중첩 컨테이너 경계의 부모 escape 구분
- focus가 selection endpoint인지 확인해 자동 해제를 보존할지 결정

`SelectionUiHelpers`에는 `MutableState` 적용, `LocalClipboard`, key/pointer/focus modifier만 남긴다. coordinator는
상태를 보관하지 않으므로 `MarkdownBlockTextField`의 문서 수명과 selection 권한이 분산되지 않는다.

### 16.7 개발용 recomposition 계측

Desktop 개발 실행에서 다음 중 하나를 설정하면 실제 Compose `SideEffect` 횟수를 로그로 확인할 수 있다.

```powershell
$env:MACHUM_EDITOR_RECOMPOSITION_METRICS='true'
.\gradlew.bat :desktopApp:run
```

또는 JVM 속성 `-Dmachum.editor.recomposition.metrics=true`를 사용한다. 일반 실행에서는 계측 effect와 로그를
등록하지 않으며 Android actual은 항상 비활성이다. 계측 scope는 `document`, `container`, `selection-surface`,
`block-row`, `block`이다. 초기 block·block-row 로그는 대량 출력을 막기 위해 숨기고 2회차부터 표시한다.

2026-08-31 단일 Text 문서 최초 표시 기준은 `document=1`, `container(root)=1`,
`selection-surface(root)=1`이었다. 같은 화면에서 `Ctrl+A`의 전체 행 강조와 `Esc` 해제를 확인했다. 이 수치는
성능 합격선이 아니라 이후 100/1,000블록 측정과 구조 변경을 같은 조건에서 비교하기 위한 시작점이다.

### 16.8 history snapshot

현재 `EditorBlock`은 변경 가능한 `TextFieldState`를 포함하므로 그 객체를 그대로 history에 저장할 수 없다.
history는 ID와 문자열만 가진 immutable snapshot을 사용한다.

```kotlin
data class EditorDocumentSnapshot(
    val blocks: List<EditorBlockSnapshot>,
)
```

`EditorBlockSnapshot`은 Text·Callout·Code·Table의 block ID, 문자열, `rawMode`, `rawOrigin`과 중첩 구조를
보존한다. snapshot 복원 시에만 새 `TextFieldState`를 만들며 기존 block ID를 다시 사용한다.
raw Markdown 문자열만 저장했다가 parser로 복원하는 history는 block ID와 편집 중간 구조 메타데이터를 잃으므로 금지한다.

2026-08-31 구현 범위:

- Text·Callout·Code·Table·HorizontalRule·Embed 전체의 양방향 snapshot 변환
- 중첩 Callout body, Table 셀, `rawMode`·`rawOrigin`, block ID 보존
- snapshot 복원 시에만 새 `TextFieldState`를 만들고 원본과 참조 분리
- `EditorDocumentSnapshot`은 블록 내용만 소유하고 focus·cursor·selection은 기록하지 않음
- UI 비의존 `EditorHistory`에 Undo/Redo stack, 외부 baseline reset과 최대 100개 제한 구현
- 같은 focus key의 연속 일반 입력은 750ms까지 병합하고 구조·clipboard transaction은 원자적으로 유지
- Undo 뒤 새 편집 시 redo branch 폐기

`MarkdownBlockTextField`의 입력 collector가 변경 전후 snapshot을 분류·기록하며 Ctrl/Cmd+Z,
Ctrl/Cmd+Shift+Z와 Ctrl/Cmd+Y를 문서 history에 연결했다. Undo/Redo 결과는 일반 `onValueChange` 경로로 저장되고,
실제 외부 value가 들어오면 해당 값을 새 baseline으로 삼아 이전 undo/redo stack을 비운다. Desktop에서는 일반 Text,
중첩 Dialogue Callout body와 Code 입력의 Undo → Redo → Undo 및 원문 저장을 화면에서 확인했다.

Undo/Redo는 복원된 텍스트의 focus, cursor 위치나 선택 강조를 재현하지 않는다. 문서 단위 custom selection은
`None`으로 해제하고 내용만 복원한다. 이 정책으로 각 `BasicTextField`의 native 상태를 history에 보고하는 effect와
필드 경로 모델을 제거해 Code·Table·재귀 Callout을 history 구현과 결합하지 않는다.

초기 transaction 정책:

- 같은 블록의 연속 일반 입력은 마지막 입력 후 750ms까지 한 transaction으로 병합
- split·merge·reparse·dissolve·Table 행/열 추가는 각각 한 transaction
- Cut·Paste·selection replace는 각각 한 transaction
- Undo 뒤 새 편집이 발생하면 redo stack 제거
- 실제 외부 파일 변경은 external-wins 정책에 따라 새 baseline이 되며 기존 undo/redo stack 제거

750ms는 저장 debounce와 별개다. 실제 사용성 측정 후 조정할 수 있다.

---

## 17. 단계별 구현 계획

각 단계는 동작 변경과 구조 변경을 섞지 않으며, 이전 단계의 자동·수동 회귀가 통과해야 다음 단계로 이동한다.

### 단계 A — 현행 계약 고정과 측정 (`M`)

1. 기존 parser·BlockOperations·DocumentSelection 테스트를 명령별 표로 연결
2. 100블록·1,000블록 문서의 parse, serialize, 첫 표시, 한 글자 편집 기준 측정
3. focus 이동, split, merge, dissolve, Callout 진입의 Desktop 수동 기준 고정
4. Compose recomposition count 측정 지점을 개발 빌드에만 추가 — 완료

완료 조건:

- 기능별 현재 합격 기준과 측정값 기록
- 이후 구조 변경의 전후 결과를 같은 입력으로 비교 가능

### 단계 B — focus와 action 조정 분리 (`M~L`)

1. `EditorFocusRequest`와 UI 비의존 `EditorFocusCoordinator` 추가 — 완료
2. `pendingFocusBlockId`, cursor hint, bottom-entry 상태와 request counter 이동 — 완료
3. split·merge·reparse·dissolve 결과를 `EditorMutation`으로 통일 — 완료
4. `BlockNavigation` callback 15개를 focus·mutation·selection action 그룹으로 분리 — 완료
5. Standard·DL Callout의 공통 body 생성·진입·탈출 정책 추출 — 완료

완료 조건:

- `MarkdownBlockEditor`는 렌더링과 이벤트 연결만 담당
- focus target 결정은 Compose 없이 자동 테스트
- 기존 DIS·SEL·TBL 수동 계약 변화 없음

### 단계 C — 문서 snapshot과 Undo/Redo (`L`)

1. `EditorBlockSnapshot` 양방향 변환과 중첩 Callout round-trip 테스트 — 완료
2. `EditorHistory` transaction·coalescing 구현 — 완료
3. 구조 변경을 포함한 collector 변경을 typing/atomic transaction으로 분류해 history에 연결 — 완료
4. Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z와 Ctrl/Cmd+Y 연결 — 완료
5. 외부 value 교체 시 history baseline 초기화 — 완료
6. focus·cursor·selection 비추적 정책으로 history와 개별 편집 필드 결합 제거 — 완료

완료 조건:

- 일반 입력과 모든 구조 변경을 순서대로 Undo/Redo
- block ID와 중첩 블록 내용 복원
- Undo 결과가 일반 저장 흐름으로 기록되고 외부 변경을 되살리지 않음

### Undo/Redo MVP 직후 구조·파일 최적화 게이트

단계 C의 내용 이력 UI 연결과 Desktop 회귀를 마친 뒤, 단계 D의 cross-block 파괴적 편집을 시작하기 전에 프로젝트
전체 구조를 정리했다.

1. 완료: immutable snapshot과 순수 history 기반
2. 완료: 입력 collector·구조 변경 분류·단축키·외부 baseline 연결과 Desktop Text 화면 검증
3. 완료: 실행 경로 밖 workflow 계층 제거와 스캐너 전용 모델 병합
4. 완료: 중첩 Callout body와 Code 내용의 Desktop 검증
5. 완료: cross-block Delete/Cut/Paste 순수 치환·clipboard 연결과 Desktop 화면 검증
6. 완료: Multi selection 일반 문자·IME 확정 문자열 입력 대체와 Desktop 회귀 검증

정리 전 `composeApp/src`의 Kotlin 파일은 136개였고, `commonMain` 91개 중 80줄 이하 파일은 33개,
Markdown commonMain은 29개였다. 정리 후에는 각각 117개, 74개 중 23개, 28개다. 작은 파일 수 자체를
문제로 보지 않고 다음 조건으로 정리했다.

- 단일 소비자만 있고 독립 테스트 가치가 없는 coordinator·policy·helper는 인접 책임과 통합 검토
- `FileManager`, `MainViewModel`, `MarkdownBlockEditor`처럼 큰 파일은 줄 수가 아니라 변경 이유와 의존 방향으로 분리
- expect/actual, 플랫폼 I/O, commit object store처럼 플랫폼·영속성 경계를 가진 파일은 단순 개수 감축 대상으로 삼지 않음
- production과 test의 1:1 파일 분리는 탐색성을 높이면 유지하고, 중복 fixture·helper만 통합
- 사용하지 않는 workflow·호환 코드와 중복 문서 계약 제거
- JVM 전체 테스트, Desktop/Android 컴파일, recomposition 기준을 정리 전후 동일하게 비교

적용 결과:

- 라이브 흐름에 없던 workflow·테스트 화면 소스 15개와 `FileManager`의 관련 상태/API 제거
- 사용되지 않던 `Greeting`·`Platform` 예제 경계 제거
- scanner에서만 쓰는 `MarkdownBlock`을 `InlineStyleScanner`에 병합
- `pickFile`의 불필요한 파일 본문 읽기와 사용되지 않는 공통 `read` API 제거
- 독립 테스트가 있는 history·focus·selection·Callout policy와 expect/actual 파일은 유지

목표는 파일 개수만 줄이는 것이 아니라 새 기능 하나가 여러 계층을 동시에 수정하지 않도록 책임 경계를 단순하게
만드는 것이다. 최적화 작업 중에는 기능을 추가하지 않는다.

### 단계 D — cross-block 파괴적 편집 (`L~XL`)

1. `replaceSelectedMarkdown`을 순수 operation으로 구현 — 완료
2. Ctrl/Cmd+X = 복사 성공 후 delete — 완료
3. Multi selection Ctrl/Cmd+V = 선택 범위를 Markdown parse 결과로 치환 — 완료
4. 일반 키 입력과 IME commit의 selection 대체 — 완료
5. Callout atomic 경계와 같은 container 범위 검증 — 순수 테스트 완료

`replaceSelectedMarkdown`은 선택 시작 Text의 prefix와 끝 Text의 suffix만 이어 붙인 영향 구간을 다시 parse하고,
선택 밖 블록과 상위 Callout ID를 보존한다. 전체 문서를 지우면 입력 가능한 빈 Text 하나를 남긴다. 구조 치환과
Undo/Redo 후에는 이전 위치를 추적하지 않고 현재 첫 블록에만 focus를 요청해 단축키 입력이 끊기지 않게 한다.
Desktop에서는 전체 Code+Table 문서를 대상으로 Delete → Undo, Cut → 빈 문서, 빈 Text 전체 선택 → Paste,
Paste Undo → Cut Undo와 최종 원문 저장을 확인했다.

일반 문자·IME 입력은 별도 `replaceSelectedText` 경로를 사용한다. Multi가 활성화된 동안 문서 단위의 1dp 투명
`BasicTextField`가 입력을 소유하고 composition이 null이 된 확정 문자열만 한 번 적용한다. 선택 구간은 편집 가능한
Text 하나로 바뀌며 선택 시작 Text ID를 우선 재사용한다. 삽입 끝 위치는 history snapshot이 아니라 일회성
`DocumentInputFocusRequest`로 기존 focus coordinator에 전달한다. 요청 실행 전의 빠른 후속 확정 문자열은 투명 input
capture가 대상 Text와 endpoint offset에 즉시 이어 붙이고, 새 요청이 이전 focus coroutine을 취소한다. 같은 container의 Text+Callout 전체 선택에서
영문·한글 문자열 치환, 연속 입력, Undo 원문 복원과 Esc·왼쪽 방향키 해제 후 입력 복귀를 Desktop 화면에서 확인했다.

완료 조건:

- Text 부분 endpoint와 atomic 블록이 섞인 selection을 데이터 손실 없이 삭제·복원
- Cut/Paste 한 번이 history transaction 한 개
- cursor-only clipboard는 네이티브 `BasicTextField` 동작 유지

### 단계 E — 조건부 고급 selection (`L~XL`)

1. Callout container 경계를 넘는 Scope B traversal
2. 블록 layout 좌표 registry
3. custom pointer drag hit-test와 자동 스크롤
4. Android touch handle 정책 별도 검증

이 단계는 아래 `DEC-EDITOR-01~02` 합의 전에는 시작하지 않는다.

### 단계 F — 별도 제품 기능 (`L~XL`)

- Table 사각형 selection과 TSV clipboard
- Embed 미리보기와 파일 resolver
- 접근성 semantics와 M3 시각 정리

Table과 Embed는 일반 문서 selection/history와 독립 배포할 수 있게 별도 milestone로 유지한다.

---

## 18. 구현 가능성 및 기술적 한계

| 항목 | 가능 여부 | 난이도 | 결론 |
|---|---|---:|---|
| focus coordinator 분리 | 구현 완료 | M~L | 자동 테스트와 Desktop 수동 회귀 통과 |
| mutation dispatcher | 구현 완료 | M | 기존 `BlockOperations` 재사용·자동/수동 회귀 통과 |
| BlockNavigation 역할 분리 | 구현 완료 | M | focus·mutation·selection 그룹 자동/수동 회귀 통과 |
| Callout 공통 body 정책 | 구현 완료 | M | 순수 정책 행렬 테스트와 Standard·DL Desktop 회귀 통과 |
| 문서 단위 Undo/Redo | 구현 완료 | L | 자동 테스트와 Desktop Text·중첩 Callout·Code 내용 복원 통과 |
| keyboard cross-block Delete/Cut/Paste | 구현 완료 | L | 순수 테스트·Desktop 전체 문서 화면 검증 통과 |
| Multi 상태 일반 입력 대체 | 구현 완료 | L | 순수 테스트와 Desktop 영문·한글 확정 문자열 검증 완료, 실제 IME 조합 수동 확인 필요 |
| Callout Scope B selection | 가능 | L~XL | 기본 clipboard 이후로 보류 권장 |
| 마우스 drag cross-block selection | custom 방식만 가능 | XL | 제품 필요성 합의 전 보류 |
| Android 네이티브 selection handle의 블록 횡단 | 현 구조에서는 불가 | 재작성 | 단일 text surface가 필요하므로 비목표 |
| Table 사각형 셀 selection | 가능 | XL | 별도 milestone 권장 |
| Embed 미리보기 | 가능 | L | 파일 resolver·cache·권한 정책 필요 |
| Desktop·Android 완전 동일 IME/selection 감각 | 보장 불가 | XL+ | 공통 데이터 계약만 동일하게 보장 |

### 현 구조에서 허용하지 않는 구현

1. raw Markdown 문자열만 저장하고 매 Undo마다 전체 parse하는 history
   - block ID와 편집 중간 구조 메타데이터가 바뀌어 다음 구조 편집이 불안정해질 수 있다.
2. 여러 `BasicTextField`의 네이티브 selection을 하나의 OS selection처럼 연결하는 방식
   - Compose와 플랫폼 selection owner 경계를 넘을 수 없어 custom `DocumentSelection`이 필요하다.
3. 임의 지연 시간을 추가해 focus race를 덮는 방식
   - 새 focus request가 이전 coroutine을 취소하는 단일 실행 경로를 사용한다.
4. 일반 cross-block delete보다 Table 셀 selection을 먼저 구현하는 방식
   - clipboard·history·selection mutation을 Table에서 다시 구현하게 된다.

---

## 19. 결정 게이트

다음 항목은 기술적으로 가능하지만 비용이 커 사용자 합의 전 구현하지 않는다.

### `DEC-EDITOR-01` 1차 selection 범위

- 권장: 키보드 기반 selection·Delete/Cut/Paste까지 1차 완성
- 고비용안: 마우스 drag와 Android touch drag까지 동시에 완성

### `DEC-EDITOR-02` selection UX

- 권장: 현재 custom block selection과 Text endpoint를 제품 계약으로 사용
- 고비용안: 운영체제 네이티브 연속 selection과 동일한 손잡이·문자 단위 drag를 요구
  - 이 경우 독립 `BasicTextField` 구조를 단일 text surface로 재설계해야 한다.

프로젝트 커밋은 이 선택의 판단 기준이 아니다. 두 구조 모두 저장 직전 canonical Markdown을 만들 수 있으며,
커밋 계층은 `EditorBlock.id`가 아니라 frontmatter 파일 `id`, 상대 경로와 직렬화된 파일 content hash만 사용한다.
단일 text surface가 줄 diff를 직접 보여주기 쉽다는 장점은 있지만 같은 diff를 저장된 Markdown blob에서도 계산할 수 있다.
따라서 commit 지원 때문에 현재 블록 구조를 폐기하지 않으며, Callout·Table 같은 구조화 UI 요구가 유지되는 동안에는
custom block 구조를 권장안으로 유지한다.

### `DEC-EDITOR-03` Table 범위

- 권장: Table을 cross-block selection에서는 atomic으로 유지하고 행·열 편집만 보완
- 고비용안: 1차 완성에 사각형 셀 selection·TSV Cut/Paste까지 포함

### `DEC-EDITOR-04` Embed 범위

- 권장: 현재 raw Text 편집 유지
- 확장안: 1차 완성에 미리보기·파일 열기·누락 상태 UI 포함

별도 합의가 없으면 모든 권장안을 기본 정책으로 사용한다.
