# Cross-Block Selection — 설계 문서

블록 기반 에디터(`markdown/`) 의 cross-block selection / 클립보드 / 키보드 네비게이션 설계.
관련 항목: `compact.md` Phase 3 **#21**, `CLAUDE.md` 의 Phase 3 항목.

---

## 1. 배경 및 목표

### 현재 상태 (Phase 2 종료 시점)

각 `EditorBlock` 이 독립 `TextFieldState` 를 보유하고, `MarkdownBlockEditor` 가 LazyColumn 으로 디스패치한다. selection 은 **블록 내부에만 존재**:

- `block.textFieldState.selection` — `TextRange(start, end)` 형식
- 블록 경계를 넘는 selection 추적 메커니즘 부재
- 클립보드 단축키 (Ctrl+A/C/V/X) 미구현 — `BasicTextField` 기본(블록 단위)에 의존
- Compose `SelectionContainer` 는 `BasicTextField` 와 함께 작동하지 않아 native 우회 불가

### 목표

문서 레벨 selection 모델을 도입하여 다음을 가능하게 한다:

- 여러 블록에 걸친 markdown 복사 (`Ctrl+C`)
- 잘라내기 / 붙여넣기 (`Ctrl+X`, `Ctrl+V`)
- 전체 문서 선택 (`Ctrl+A`)
- 키보드 (`Shift+화살표`) / 마우스 드래그 selection
- 컨테이너(Callout body) 횡단 selection

### 사용자 결정 사항

- **컨테이너 간 횡단 가능**: 외부 ↔ Callout body 까지 한 selection 으로 이을 수 있음
- **Block 단위 atomic 정책**: Callout 은 외부에서 선택 시 title+body 가 한 덩어리로 들어감/빠짐. 단 Callout 내부에서 자체 cross-selection 은 가능 (재귀)
- **Table / CodeBlock 의 cross-selection 정책은 후속 결정** — Phase 1 에서는 atomic 으로 취급

---

## 2. Selection 모델 — `DocumentSelection`

### 2.1 데이터 구조

```kotlin
sealed class DocumentSelection {
    /** 블록 내부 단일 selection — 별도 추적 안 함. native TextFieldState.selection 그대로. */
    data object None : DocumentSelection()

    /**
     * Cross-block selection (anchor → focus 방향성 보존).
     * anchor: 사용자가 처음 누른 위치
     * focus: 현재 위치 (드래그/Shift+화살표로 이동 중)
     * 정렬은 비교 함수로 결정 (start vs end).
     */
    data class Multi(
        val anchor: SelectionEndpoint,
        val focus: SelectionEndpoint,
    ) : DocumentSelection()
}

/**
 * 어떤 블록의 어디 — 재귀 컨테이너(Callout body) 까지 따라 들어가는 경로.
 *
 * containerPath: 최상위 → 가장 가까운 컨테이너까지의 id chain (외부 = empty)
 *   예: 최상위 Callout(id="A") 의 body 안 TextBlock 이면 containerPath = ["A"]
 *       최상위 Callout(id="A") body 안의 또 다른 Callout(id="B") body 안 TextBlock 이면
 *       containerPath = ["A", "B"]
 * blockId: 가리키는 블록 자체의 id
 * offset: 블록 내부 텍스트 offset (atomic 블록은 0 또는 length)
 */
data class SelectionEndpoint(
    val containerPath: List<String>,
    val blockId: String,
    val offset: Int,
)
```

### 2.2 atomic 정책

| 블록 | 외부에서 선택될 때 | 내부에서 cross-selection |
|---|---|---|
| `Text` | 부분 선택 가능 (offset 기반) | N/A (단일 TextFieldState) |
| `Callout` | atomic — title+body 통째 | 가능 (body 안 재귀 selection) |
| `Code` | atomic (Phase 1 한정. Phase 5 에서 재검토) | Phase 1 미지원 |
| `Table` | atomic (Phase 1 한정. Phase 5 에서 재검토) | Phase 1 미지원 |
| `Embed` / `HorizontalRule` | atomic | N/A |

`isAtomic(EditorBlock): Boolean` 헬퍼로 통일 처리. atomic 블록의 endpoint offset 은 0 (시작) 또는 `Int.MAX_VALUE` (끝) 같은 sentinel 로 정규화.

### 2.3 컨테이너 횡단 규칙

- `Multi.anchor` 와 `focus` 의 `containerPath` 가 **달라도** 허용 (예: anchor 는 외부 TextBlock, focus 는 Callout body 안 TextBlock)
- 같은 컨테이너 안에서 시작했어도 selection 이 컨테이너 밖으로 확장되면 → 그 Callout 은 atomic 으로 통째 들어감 (개별 body 블록 단위가 아니라)
- 정규화 함수 `DocumentSelection.normalize()` 가 anchor/focus 비교 후 atomic 처리 적용

### 2.4 endpoint 비교 — start / end 결정

```
fun compareEndpoint(a: SelectionEndpoint, b: SelectionEndpoint, blocks: List<EditorBlock>): Int
```

- a, b 의 containerPath 의 첫 번째 id 가 최상위 blocks 에서 어느 인덱스인지 비교
- 같은 최상위 컨테이너면 더 깊은 path 로 들어가서 재귀 비교
- 최종적으로 같은 블록이면 offset 비교

---

## 3. Phase 분할 로드맵

### Phase 1 — Foundation: 키보드 + 복사 (다음 PR)

핵심 모델 + 시각화 + Ctrl+A/C 까지.

**구현 범위**:
1. `state/DocumentSelection.kt` 신규 — 모델 + `isAtomic` + `normalize` + `extractMarkdown`
2. `MarkdownBlockEditor` 에 `selection: MutableState<DocumentSelection>` + `containerPath: List<String>` 추가, 재귀 자식에 전파
3. `Ctrl+A`: 현재 컨테이너의 모든 블록 선택
4. `Shift + ↑/↓`: 시작점 고정, 끝점을 블록 단위로 확장 (Callout 은 통째)
5. `Esc` 또는 클릭: `DocumentSelection.None` 으로 리셋
6. **시각화** — 선택된 블록 위에 `DrawBehind` 로 반투명 배경. 부분 선택된 endpoint 블록은 native TextFieldState selection 동기화
7. `Ctrl+C`: `extractMarkdown(blocks, selection)` → `LocalClipboardManager.setText`

**Phase 1 의도적 미포함**:
- 마우스 드래그
- 잘라내기/붙여넣기
- 글자/단어 단위 Shift+화살표
- Table/CodeBlock 내부 cross-selection

### Phase 2 — 마우스 드래그 selection

1. `pointerInput { detectDragGestures }` 를 `MarkdownBlockEditor` 컨테이너에 부착
2. `Modifier.onGloballyPositioned` 로 각 `BlockItem` 의 절대 좌표 + `TextLayoutResult` 캐시 (`blockLayoutMap: Map<String, BlockLayoutInfo>`)
3. 드래그 좌표 → `findBlockAtY(y)` → 해당 블록의 `getOffsetForPosition` → endpoint 갱신
4. Auto-scroll: 드래그가 LazyColumn 가장자리 도달 시 `lazyListState.scrollBy`
5. 단일 블록 내부 드래그는 native BasicTextField 동작 그대로 (DocumentSelection 으로 승격은 블록 경계 넘을 때만)

### Phase 3 — 잘라내기 / 붙여넣기

1. `BlockOperations.deleteSelection(blocks, selection): List<EditorBlock>` — endpoint 블록 분할 + 중간 블록 제거 + 인접 일반 텍스트 병합
2. `Ctrl+X` = `Ctrl+C` + `deleteSelection`
3. `Ctrl+V`:
   - clipboard text → `MarkdownBlockParser.parse` → 새 블록 리스트
   - selection 있으면 `deleteSelection` → 그 자리에 삽입
   - 단일 블록 내부 paste 는 native (markdown 패턴 감지로 자동 split 은 기존 `tryReparse` 흐름 활용)
4. selection 있는 상태로 텍스트 입력 → `replaceSelection(text)`

### Phase 4 — 키보드 selection 확장

1. `Shift + ←/→` — 글자 단위, 블록 경계 넘기 (현재 블록 끝/시작에서 인접 블록으로 진입)
2. `Shift + Home/End` — 줄 단위
3. `Shift + Ctrl/Alt + ←/→` — 단어 단위 (플랫폼 modifier 감지)
4. `Shift + PageUp/PageDown` — 화면 단위

### Phase 5 — Table / CodeBlock 정책 확정 (사용자 추가 결정 후)

후보 정책 (사용자와 추후 논의):
- **A**: Phase 1 atomic 유지 (가장 단순)
- **B**: Table 셀 단위 cross-selection (셀 ~ 셀, 행 단위 확장)
- **C**: CodeBlock 안 텍스트 부분 선택 가능

채택안에 따라 `SelectionEndpoint` 에 `cell: TableCell?` / `inCodeBlock: Boolean` 같은 필드를 확장하여 도입.

---

## 4. 시각화 정책

```
시작 endpoint 블록  → native BasicTextField selection (TextFieldState.edit { selection = ... })
중간 atomic 블록들  → Modifier.drawBehind 로 반투명 배경 (config 의 selectionAccent 색)
끝 endpoint 블록    → native BasicTextField selection
중간 일반 텍스트    → 줄 단위 0..length native selection
```

부분 endpoint 의 native selection 과 cross-block selection 의 sync 충돌 방지:
- `DocumentSelection` 이 `None` 이면 native selection 자유
- `DocumentSelection` 이 `Multi` 면 endpoint 가 가리키는 두 블록만 native selection 강제 (LaunchedEffect 가 textFieldState 업데이트)

---

## 5. Clipboard 통합

Compose Multiplatform 의 `LocalClipboardManager` 사용 (commonMain). `androidApp` / `desktopApp` 모두 native clipboard 로 자동 위임. 별도 expect/actual 불필요.

```kotlin
val clipboard = LocalClipboardManager.current
clipboard.setText(AnnotatedString(extractMarkdown(blocks, selection)))
```

`extractMarkdown` 시그니처:

```kotlin
fun extractMarkdown(
    blocks: List<EditorBlock>,
    selection: DocumentSelection.Multi,
): String
```

알고리즘:
1. selection.normalize() 로 start / end 결정
2. start endpoint 블록: 텍스트 substring(start.offset..length)
3. 중간 블록들: `block.toMarkdown()` 그대로
4. end endpoint 블록: substring(0..end.offset)
5. 컨테이너 차이 처리: anchor/focus 가 다른 컨테이너면 둘 중 더 외부에 있는 컨테이너 단위로 atomic 승격
6. 모두 `\n` join (universal — `List<EditorBlock>.toMarkdown()` 규칙과 동일)

---

## 6. 핵심 파일 목록 (Phase 1 시작 시점)

| 파일 | 변경 |
|---|---|
| `state/DocumentSelection.kt` | **신규** — 모델, 정규화, extractMarkdown, isAtomic |
| `ui/MarkdownBlockEditor.kt` | `documentSelection` state 호이스팅, `containerPath` 파라미터, BlockItem 시각화, BlockNavigation 확장 (`onExtendSelectionToPrevious/Next`) |
| `ui/MarkdownBlockTextField.kt` | 최상단 키 핸들러 (Ctrl+A/C, Esc), clipboard 통합 |
| `ui/TextBlockEditor.kt` | Shift+↑/↓ 블록 단위 확장 트리거 |
| `ui/block/CalloutBlockEditor.kt` | title/body 의 Shift+↑/↓ endpoint 처리, 재귀 path 전파 |

재사용 가능한 기존 유틸:
- `EditorBlock.toMarkdown()` / `List<EditorBlock>.toMarkdown()` — 중간 atomic 블록의 markdown 추출
- `MarkdownBlockParser.parse()` — Phase 3 paste 시 사용
- `EditorBlock.id` — endpoint blockId 안정 식별자 (UUID 기반)
- `bottomEntryFRMap` / `focusRequesterMap` — endpoint 블록으로 포커스 이동에 재활용
- `findDeepestLastText` — Callout body 마지막 endpoint 결정 시 재활용

---

## 7. 후속 결정 대상

### 7.1 Table cross-selection 의 단위

| 옵션 | 설명 | 트레이드오프 |
|---|---|---|
| A. atomic 유지 | Phase 1 처럼 통째 | 단순. 표 안 일부 셀 복사 불가 |
| B. 셀 단위 | 셀 ~ 셀 사각형 영역 | 자연스러움. 모델/시각화 복잡도 ↑ |
| C. 행 단위 | 행 1개 또는 여러 행 | 중간 복잡도. 셀 수준 정밀도는 없음 |

### 7.2 CodeBlock cross-selection 의 단위

| 옵션 | 설명 |
|---|---|
| A. atomic 유지 | 통째로만 선택 |
| B. 텍스트 부분 선택 | code 텍스트 안에서 offset 기반 선택 가능 |

Phase 5 진입 시 사용자와 논의하여 채택안 결정 후 `SelectionEndpoint` 확장.

---

## 8. 개정 이력

- **2026-05-05**: 최초 작성. Phase 1 진입 전 영구 설계 문서로 격상. plan 파일 (`.claude/plans/2-inherited-lecun.md`) 의 1~2 절을 본 문서로 옮김.
