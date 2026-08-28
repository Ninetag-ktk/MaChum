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

### 2.4 Shift+↑/↓ 확장 정책 (옵션 C v2 — 재정정)

이전 옵션 C 의 "외부 → Callout 진입 시 외부 블록까지 native 확장" 정책은 사용자 의도와 다른 것으로 확인됨. **Callout 이 selection 의 endpoint 인 모든 경우에 Callout 자체만 atomic** 이 사용자 의도. 외부 블록과 Callout 이 함께 selection 되는 케이스는 외부 → 외부 (둘 다 Text) 만 해당.

**A. 외부 Text ↔ 외부 Text (Callout/Code/Table 같은 atomic 블록 미포함)** — native 확장

| 시나리오 | 동작 |
|---|---|
| 외부 TextBlock A 마지막 줄에서 `Shift+↓` → 다음 외부 TextBlock | A 의 cursor 위치 ~ end + 다음 TextBlock 모두 selection |
| 외부 TextBlock A 첫 줄에서 `Shift+↑` → 이전 외부 TextBlock | 위와 대칭 |

다른 에디터의 native Shift+↑/↓ 와 같은 자연스러운 확장. **단 다음/이전 블록이 atomic 블록 (Callout/Code/Table 등) 이면 B 정책 적용** — 외부 Text 는 selection 에서 빠지고 atomic 블록만 selected.

**B. atomic 블록 (Callout 등) 이 selection 의 endpoint** — 그 atomic 블록 자체만 selection

| 시나리오 | 발동 조건 | 결과 |
|---|---|---|
| 외부 TextBlock 마지막 줄에서 `Shift+↓` → 다음이 Callout/Code/Table 등 atomic | atomic 블록을 endpoint 로 가져갈 때 | **atomic 블록 자체만** selection. 외부 TextBlock 포함 X |
| 외부 TextBlock 첫 줄에서 `Shift+↑` → 이전이 atomic 블록 | 동일 | atomic 블록 자체만 selection. 외부 TextBlock 포함 X |
| Callout title 에서 `Shift+↑` | (title 은 SingleLine 이라 위치 무관) | Callout 자체만 atomic. 외부 다른 블록 포함 X |
| Callout title 에서 `Shift+↓` | 동일 | Callout 자체만 atomic. 외부 다른 블록 포함 X |
| Callout body 의 **첫 블록 첫 줄** 에서 `Shift+↑` | 첫 블록의 첫 줄 cursor 일 때만 | Callout 자체만 atomic. 외부 위 블록 포함 X |
| Callout body 의 **마지막 블록 마지막 줄** 에서 `Shift+↓` | 마지막 블록의 마지막 줄 cursor 일 때만 | Callout 자체만 atomic. 외부 아래 블록 포함 X |

발동 조건 핵심: body 안의 **중간 위치** 에서의 Shift+↑/↓ 는 위 정책이 발동하지 않고, body 안의 cross-selection 또는 native 동작이 일어난다. body 첫 줄/마지막 줄 cursor 에서만 "박스 탈출" 트리거.

**C. Callout body 안의 cross-selection** — body 안에서 한정

| 시나리오 | 동작 |
|---|---|
| body 의 중간 블록의 Shift+↑/↓ | body 안에서만 cross-selection 확장 (외부 atomic 승격 안 함) |

body 안 cross-selection 은 documentSelection 의 path 가 `[callout.id]` 인 selection 으로 추적. body 경계 (첫 줄 ↑ / 마지막 줄 ↓) 도달 시 위 B 정책으로 전환 (Callout 자체 atomic).

**구현 — `onSelectSelfAsAtomic` + `onExtendSelectionTo*` 의 atomic 분기**

B 정책 (Callout 안에서의 박스 탈출) 은 `onSelectSelfAsAtomic` 콜백 — 현재 블록 (Callout) 자체만 atomic selection 으로 갱신:
- `Multi(anchor=startEndpointOf(currentBlock), focus=endEndpointOf(currentBlock))`

A 정책 + B 정책의 외부 → atomic 진입 케이스 처리:
- `extendSelectionToPrevious/Next` 헬퍼 안에서 **다음/이전 블록이 atomic** 이면 `selectBlockAsAtomic(thatBlock, ...)` 으로 분기. 외부 Text 의 anchor 를 보존하지 않고 atomic 블록 자체만 selection
- 외부 TextBlock 끼리는 anchor 보존 native 확장

**Shift+→ 도 동일 정책 (DL title 한정)**:
- Dialogue Callout 의 title 에서 `Shift+→` → 외부 컨테이너에서 Callout 자체만 atomic selection (`onSelectSelfAsAtomic`). DL 은 title 과 body 가 가로 배치라 →가 자연스러운 "박스 탈출" 방향

### 2.5 Shift 누적 확장 시도 → 롤백 (시도 기록)

이전에 "Shift+↑/↓ 를 여러 번 누르면 selection 누적 확장" 정책을 시도했었음:
- `extendSelectionToPrevious/Next` 헬퍼가 누적 호출 시 `existing.focus.blockId` 기준으로 baseIndex 재계산
- 첫 호출에서만 atomic 분기, 누적 호출이면 anchor 보존 + focus 만 갱신
- cursor 도 selection 의 focus endpoint 블록으로 이동 (`LaunchedEffect(focusTargetId)`)

**결과: 작동 안 함**. focus 가 endpoint 블록으로 이동한 직후의 동작이 일관되지 않았고, Callout/CodeBlock 안에서 Shift+↑/↓ 가 의도와 다르게 작동 (atomic 만 재선택 / cursor 이동만 + selection 해제). 사용자 검증에서 회귀로 확인되어 다음을 **롤백**:
- 헬퍼의 baseIndex 가 currentIndex 만 사용
- 누적 분기 제거 — 항상 첫 호출 정책 (atomic 진입이면 atomic 만, 외부 Text 끼리면 anchor=currentBlock + focus=nextBlock)
- `CalloutBlockEditor` title 의 Shift+↑/↓ 가 무조건 `onSelectSelfAsAtomic` 호출 (첫/누적 분기 제거)
- `CodeBlockEditor` 의 Shift+↑/↓ 가 무조건 `onSelectSelfAsAtomic` 호출

**보존된 부분**:
- `resetDocumentSelectionOnFocus(blockId)` 의 endpoint 비교 분기는 유지 — selection 의 anchor/focus 블록 매칭 시 reset 안 함
- `LaunchedEffect(focusTargetId)` 의 cursor 이동은 유지 — Shift 동작 시 cursor 가 selection 의 focus 블록으로 이동
- 외부 Text ↔ Text 끼리의 anchor 보존 native 확장 (헬퍼 안의 `existing?.anchor ?:` fallback 형태)

향후 누적 확장 재시도 시 고려할 점: focus 이동과 keystroke 발생 위치의 race condition, 누적 시 currentIndex vs focus.blockId 의 인덱스 일관성 보장 필요

#### 2.5.1 Scope A 재구현 — 최상위 단일 소유 (구현 완료)

위 롤백의 근본 원인은 **누적 확장을 각 블록의 `onPreviewKeyEvent` 에서 처리**한 점이었다. 그러면 다음 keystroke 가 어느 블록에 도착하느냐가 focus 이동 타이밍에 의존해 race 가 발생한다.

**재설계 핵심: 누적 확장의 소유권을 최상위 핸들러로 이전.**

- `documentSelectionShortcuts` 의 `onPreviewKeyEvent` 는 preview (top-down) 라 **블록 핸들러보다 먼저** 발동한다. 따라서 Multi 가 이미 존재하면 최상위가 Shift+↑/↓ 를 가로채 직접 처리하고 consume → 블록 핸들러는 보지 못함 → focus 위치와 무관한 단일 소유.
- focus 이동 (`LaunchedEffect(focusTargetId)`) 은 **순수 cosmetic** 으로 강등 — selection 로직이 더 이상 focus 위치에 의존하지 않으므로 race 가 구조적으로 사라짐.
- 개시 (None → 첫 Multi 생성) 는 여전히 블록 핸들러 담당. 최상위는 `Multi` 일 때만 가로채고 `None` 이면 `false` 반환.

**Scope A 정책 (구현된 범위):**

- 누적 확장은 **focus 가 속한 컨테이너 안에서만** (`focus.containerPath`). 이웃 블록은 블록 단위(통째)로 들어옴. atomic 블록도 통째.
- anchor 고정, focus 만 `nextFocusEndpoint(blocks, focus, down)` 으로 한 칸씩 이동. 컨테이너 경계 도달 시 null → 더 확장 안 함 (selection 보존).
- 모델/추출/시각화 (`normalize` / `extractMarkdown` / `isBlockInSelection` / 색 통합) 는 **같은 컨테이너 내 다중 블록 범위**를 이미 지원하므로 신규 작업 없이 재활용.
- **스크롤 따라가기**: cursor-following `LaunchedEffect(focusTargetId)` 에 스크롤-into-view 추가. 누적 확장은 한 칸씩 빠르게 이어지므로 **적응형 단발 스크롤** — 대상이 부분만 보이면 가장자리로 끌어올 `delta` 만 계산해 `animateScrollBy(delta)` 한 번, 화면에 아예 없으면(멀리 점프) `animateScrollToItem`. 네비게이션 경로의 고정 80px nudge + 50ms 대기 + 재점프 2단계는 블록이 크면 둔하게 느껴져서 채택 안 함. **스크롤 먼저 → 노드 compose → `requestFocus`** 순서라 off-screen recycle 된 블록도 focus 성공 (중간 대기 없음). nested(Callout body)는 `Column` 이라 recycle 없음 → `!isNested` no-op. (anchor 쪽은 화면 유지 안 함 — focus edge 만 추적.)

**Scope B (미구현, 후속 판단):** Callout body 안에서 누적하다 **경계를 넘어 외부로 이어지는** cross-container 누적. 재귀 traversal + `extractMarkdown` 의 cross-container 추출 (현재 `resolveContainerBlocks(start.containerPath)` 단일 컨테이너만 iterate) + 부분 컨테이너 시각화 (`isBlockInSelection` 은 start/end path 가 동일 컨테이너일 때만 매칭) 가 모두 필요해 큰 작업. Callout 은 외부에서 atomic 으로 통째 선택되므로 효용 대비 비용이 커 보류.

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

### Phase 5 — Table 셀 단위 누적 selection (엑셀 형식 채택)

- **Table**: 옵션 B 채택 — 엑셀 형식 셀 단위 사각형 selection. 섹션 7.1 참조
- **CodeBlock**: 옵션 A 유지 (atomic). 섹션 7.2 참조

핵심 구현:
1. `SelectionEndpoint` 에 `cell: TableCell?` 필드 추가 (data class TableCell(row, col))
2. `TableBlockEditor` 각 셀의 onPreviewKeyEvent 에 `Shift+→ / ← / ↑ / ↓` 분기 — endpoint.cell 갱신
3. 시각화: documentSelection.focus.cell 과 anchor.cell 의 사각형 영역에 selectionAccent 배경
4. normalize/extractMarkdown 에 cell-aware 분기 (사각형 영역 추출, `\|` join)
5. Table 경계 도달 + 추가 Shift → 외부 컨테이너의 onSelectSelfAsAtomic (Table 자체 atomic 승격)

---

## 4. 시각화 정책

```
시작 endpoint 블록  → native BasicTextField selection (TextFieldState.edit { selection = ... })
중간 atomic 블록들  → Modifier.background(selectionAccent) 로 outer Box 배경
끝 endpoint 블록    → native BasicTextField selection
중간 일반 텍스트    → 줄 단위 0..length native selection
```

부분 endpoint 의 native selection 과 cross-block selection 의 sync 충돌 방지:
- `DocumentSelection` 이 `None` 이면 native selection 자유
- `DocumentSelection` 이 `Multi` 면 endpoint 가 가리키는 두 블록만 native selection 강제 (LaunchedEffect 가 textFieldState 업데이트)

**색 통합** — native BasicTextField 의 selection 색과 documentSelection 의 시각화 색이 일치해야 두 selection 메커니즘이 같은 selection 처럼 보임. `MarkdownBlockTextField` 안에서 `CompositionLocalProvider(LocalTextSelectionColors provides ...)` 로 native selection 의 `backgroundColor` 를 `styleConfig.selectionAccent` 와 동기화. `handleColor` 는 기존 값 유지 (handle 은 진한 색이어야 자연스러움). `defaultMaterialBlockStyleConfig()` 의 `selectionAccent` 기본값은 M3 `primary.copy(alpha=0.4f)` — Material 의 native default 와 일치.

---

## 5. Clipboard 통합

Compose Multiplatform 의 신 `LocalClipboard` (`Clipboard`) API 사용 (commonMain). 구 `LocalClipboardManager` 는 deprecated 라 마이그레이션 완료.

신 API 는 `suspend fun setClipEntry(ClipEntry?)` 만 노출하고 (`setText(AnnotatedString)` 같은 공통 텍스트 편의 메서드 없음), `ClipEntry` 는 플랫폼별 `expect class` 라 텍스트로 만드는 공통 생성자가 없다. Compose 내부의 `AnnotatedString.toClipEntry()` 헬퍼는 `internal` 이라 접근 불가하므로 직접 expect/actual (`external/ClipEntryFactory.kt` 의 `clipEntryOf(text)`) 를 둔다. Android=`ClipData.newPlainText`, Desktop=AWT `StringSelection`.

```kotlin
val clipboard = LocalClipboard.current
val scope = rememberCoroutineScope()   // setClipEntry 가 suspend
// ...
scope.launch { clipboard.setClipEntry(clipEntryOf(extractMarkdown(blocks, selection))) }
```

> 참고: 신 API 가 `suspend` 인 것은 Web(Wasm/JS) 타겟의 비동기·권한 기반 clipboard 를 공통 인터페이스로 흡수하기 위함. 이 프로젝트는 Android/Desktop 만 타겟이라 `scope.launch` 로 fire-and-forget.

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

## 7. 후속 결정 — Phase 5 정책

### 7.1 Table cross-selection — 엑셀 형식 셀 단위 사각형 selection (채택)

옵션 B (셀 단위 사각형 영역) 를 채택. 엑셀 / Google Sheets 의 표준 동작과 동일:

| 시나리오 | 동작 |
|---|---|
| 셀 A1 에 cursor → `Shift+→` | A1, B1 두 셀 selected |
| 그 상태에서 `Shift+→` 누적 | A1, B1, C1 까지 selected (누적 사각형 확장) |
| 그 상태에서 `Shift+↓` | A1, B1, C1, A2, B2, C2 사각형 selected |
| 셀 단위 cursor 이동 (anchor 보존, focus 가 사각형의 한 모서리로 이동) | 사각형의 반대 모서리 = anchor, 현재 cursor = focus |
| `Esc` / focus 이동 / 방향키 (Shift 없이) | None 으로 자동 해제 (다른 selection 과 동일 정책) |
| `Ctrl+C` | 사각형 안 셀들의 markdown 추출 — 행은 `\|`, 행간은 `\n` 으로 join (Table.toMarkdown() 의 부분 추출) |

**모델 확장**:

```kotlin
data class SelectionEndpoint(
    val containerPath: List<String>,
    val blockId: String,
    val offset: Int,
    /** Table 셀을 가리킬 때만 사용. blockId 가 Table 인 경우의 셀 좌표.
     *  null 이면 atomic 전체 또는 Text 의 offset 기반. Phase 5 에서 도입. */
    val cell: TableCell? = null,
)

data class TableCell(val row: Int, val col: Int)
```

normalize 시 anchor.cell 과 focus.cell 사이의 사각형 영역으로 정렬 (start = min(row, col), end = max(row, col)).

시각화: 사각형 안의 모든 셀에 `selectionAccent` 배경. atomic 통째 selection 과 다르게 셀별로 시각.

**구현 방향**:
- `TableBlockEditor` 의 각 셀 BasicTextField 의 `Modifier.onPreviewKeyEvent` 에 `Shift+→ / ← / ↑ / ↓` 분기 추가
- 셀에서 Shift+화살표 → BlockNavigation 새 콜백 (예: `onTableExtendSelectionCell(direction)`) → 외부 documentSelection 의 endpoint.cell 갱신
- 사각형이 Table 경계 (`row in 0 until rowCount && col in 0 until colCount`) 안에서만 확장. 경계 도달 시 추가 Shift 는 무동작 (또는 Table 외부로 escalate)
- Table 외부 escalate: Step B-2c 의 `onEscapeSelection*` 콜백과 동일 패턴 — 사각형이 Table 전체 (모든 셀) 가 되면 그 시점에 추가 Shift+↑/↓ 는 외부 컨테이너의 `onSelectSelfAsAtomic` 발동 (Table 자체 atomic 으로 승격)

### 7.2 CodeBlock cross-selection — 일단 atomic 유지

| 옵션 | 설명 |
|---|---|
| **A. atomic 유지 (채택)** | 통째로만 선택. CodeBlock 안 텍스트 부분 선택 불가 |
| B. 텍스트 부분 선택 | code 텍스트 안에서 offset 기반 선택. native BasicTextField 가 이미 부분 selection 지원하므로 cross-block 으로 확장만 별도 처리 필요 |

CodeBlock 안의 텍스트 부분 선택은 BasicTextField 의 native selection 으로 이미 가능 (Shift+←→ / drag). cross-block 확장 (코드 안에서 외부로 가는 selection) 은 Phase 5 시점에 사용자 추가 결정 시 도입.

---

## 8. Phase 1 Step A 구현 결과

Phase 1 을 두 단계로 나누어 진행:
- **Step A** (완료): 모델 + 시각화 + Ctrl+A/C/Esc + clipboard
- **Step B** (다음): Shift+↑/↓ 블록 단위 확장 트리거 + 재귀 컨테이너 path 전파

### Step A 신규/변경 파일

| 파일 | 분류 | 역할 |
|---|---|---|
| `state/DocumentSelection.kt` | **신규** | sealed class `DocumentSelection { None, Multi(anchor, focus) }` + `SelectionEndpoint(containerPath, blockId, offset)` + `isAtomic(block)` + `Multi.normalize(blocks)` → `NormalizedSelection` + `extractMarkdown(blocks, selection)` + private 헬퍼 (compareEndpoint, promoteToCommonPath, resolveContainerBlocks, partialMarkdown) |
| `service/MarkdownStyleConfig.kt` | 변경 | `selectionAccent: Color` 필드 추가 (cross-block selection 의 atomic 블록 배경) |
| `ui/MarkdownBlockEditor.kt` | 변경 | 파라미터 `documentSelection: MutableState<DocumentSelection>?` + `containerPath: List<String>` 추가. 본문에 `normalizedSelection` 캐싱 + `isBlockSelected(index)` 헬퍼. `BlockWithNav` 안에서 selection 검사 → `Box(Modifier.background(selectionAccent))` 로 시각화 wrapping |
| `ui/MarkdownBlockTextField.kt` | 변경 | `documentSelection` state 호이스팅, `LocalClipboardManager` 통합, 최상단 `Box(Modifier.onPreviewKeyEvent { ... })` 로 Ctrl/Cmd+A (전체 선택), Ctrl/Cmd+C (clipboard write), Esc (None 리셋) 핸들러. 외부 value 변경 시 selection 도 리셋. private 헬퍼 `endOffsetFor(block)` |

### Step A 동작 범위

- `Ctrl+A` (또는 `Cmd+A`): 최상위 블록 리스트 전체를 `Multi(first, last)` 로 selection 갱신
- `Ctrl+C` (또는 `Cmd+C`): `documentSelection` 이 `Multi` 일 때 `extractMarkdown(blocks, selection)` 호출 → `LocalClipboardManager.setText` 로 raw markdown 전체 복사. 단일 블록 selection (None) 일 때는 native BasicTextField 의 Ctrl+C 그대로 사용
- `Esc`: `Multi` 상태일 때만 `None` 으로 리셋 (단일 블록 selection 에서는 native 동작)
- **방향키 / Home / End / PageUp / PageDown**: `Multi` 상태일 때 `None` 으로 자동 해제. cursor 는 마지막 focus 블록의 native 위치 유지 (정밀 이동은 후속 단계). `false` 반환으로 BasicTextField 의 native cursor 이동 동작도 그대로 진행
- **focus 이동 자동 해제**: Multi 상태에서 어떤 블록이 새로 focus 를 받으면 (사용자 클릭 등) `None` 으로 자동 해제. CompositionLocal 로 `documentSelection` 을 자식 컴포넌트에 제공하고, Modifier helper (`Modifier.resetDocumentSelectionOnFocus(blockId)`) 가 각 BasicTextField 의 `onFocusChanged` 에 hook 부착. cursor 가 selection 영역과 무관한 블록으로 이동하면 시각도 자연스럽게 해제. **단 selection 의 anchor/focus endpoint blockId 와 일치하는 블록이면 reset 안 함** — Shift+↑/↓ 확장 시 cursor 가 endpoint 블록으로 이동해도 selection 유지
- **Shift+↑/↓ 시 cursor 이동**: Shift+↑/↓ 로 selection 확장될 때마다 키보드 cursor 도 selection 의 focus endpoint 블록으로 이동. `MarkdownBlockEditor` 의 `LaunchedEffect(documentSelection?.value)` 가 `Multi` 상태 감지 → `focusRequesterMap[focus.blockId]?.requestFocus()` 호출. focus 이동 시 위의 reset helper 가 endpoint 매칭으로 reset 안 함 → selection 유지된 채 cursor 만 이동
- 시각화: 정규화된 selection 범위의 모든 블록에 `selectionAccent` 배경. atomic 블록 (Callout/Code/Table/Embed/HR) 은 통째로 칠해지고 Text 도 통째 (Step A 에서는 endpoint 부분 선택 미구현 — Step B 또는 후속 단계)
- 외부에서 새 `value` 가 들어오면 selection 도 None 으로 리셋
- 색 통합: native BasicTextField selection 색과 documentSelection 시각화 색이 동일 (`CompositionLocalProvider(LocalTextSelectionColors provides unifiedSelectionColors)`)

### Step A 의 미구현 — Phase 3 로 이관

- **selection + 텍스트 입력 → replace**: 현재는 selection 시각만 있고 native BasicTextField 입력이 endpoint 블록에 그대로 들어감 (selection 영역 보존). 자연스러운 동작은 selection 범위를 삭제하고 그 자리에 입력 텍스트 삽입 (`deleteSelection` + `insert`). 이는 Phase 3 (`Ctrl+X/V` + `deleteSelection`/`replaceSelection`) 의 일부로 통합 진행. Step A 단계에서는 focus 이동 자동 해제로 대체 (사용자가 입력 전에 다른 블록 클릭하면 selection 풀림)

### Step A 의 의도적 한계

| 항목 | 한계 |
|---|---|
| Shift+↑/↓ | 미구현 — selection 을 만들려면 Ctrl+A 뿐 |
| 마우스 드래그 selection | 미구현 (Phase 2) |
| endpoint 부분 선택 시각화 | 전체 블록 배경만 — endpoint 의 일부만 칠하는 native TextFieldState 동기화 없음 |
| 재귀 Callout body 안 selection | 시각화 안 됨 — 외부 selection 만 표시. 단 외부 ~ Callout 까지 확장된 selection 은 Callout 이 atomic 으로 승격되어 outer Box 에 배경 그려짐 |
| 잘라내기/붙여넣기 | Phase 3 |
| selection 중 입력 시 replace | 미구현 (BasicTextField 의 native 동작이 자기 블록의 selection 만 반영) |

### Step B 진행 (분할 진행 중)

Step B 를 세 sub-step 으로 분할:

**Step B-1 (완료) — Refactor only**
- `ui/selection/SelectionUiHelpers.kt` 신규 — UI 측 selection 헬퍼 단일 파일 (3 섹션: 시각화 / 단축키 / 유틸)
- `MarkdownBlockTextField.kt` 의 shortcutHandler / endOffsetFor → 헬퍼 호출로 단순화
- `MarkdownBlockEditor.kt` 의 isBlockSelected → 헬퍼 호출

**Step B-2a (재작업 필요) — 외부 → atomic 블록 진입 시 분기 누락**
- 현재 `extendSelectionToPrevious/Next` 헬퍼가 다음/이전 블록이 atomic (Callout/Code/Table) 인 경우에도 anchor=currentBlock 유지 → 외부 TextBlock + atomic 블록 모두 selection 됨. **사용자 의도와 다름**
- 재작업: 헬퍼 안에서 `nextBlock` 또는 `previousBlock` 이 atomic (`isAtomic(block) == true`) 이면 `selectBlockAsAtomic(thatBlock, containerPath, documentSelection)` 으로 분기. atomic 블록 자체만 selection. 외부 Text 끼리만 native anchor 보존 확장
- 변경 파일: `SelectionUiHelpers.kt` 의 `extendSelectionToPrevious` / `extendSelectionToNext` 함수
- 검증 시나리오: 외부 TextBlock 마지막 줄에서 Shift+↓ → 다음이 Callout 이면 Callout 만 selected (외부 TextBlock 포함 X), 다음이 TextBlock 이면 두 블록 모두 selected (현재 동작 유지)

**Step B-2b (완료) — Callout title 의 Shift+↑/↓**
- `BlockNavigation.onSelectSelfAsAtomic` 추가, `MarkdownBlockEditor.BlockWithNav` 안에서 `selectBlockAsAtomic` 헬퍼로 구현
- `CalloutBlockEditor` Standard / Dialogue title 의 Shift+↑/↓ 분기가 `onSelectSelfAsAtomic` 호출로 변경. Callout 자체만 atomic selection

**Step B-2c (완료) — body 안 cross-selection + body 경계 박스 탈출**

변경 사항:
- `MarkdownBlockEditor` 시그니처에 `onEscapeSelectionToPrevious/Next: () -> Unit = {}` 파라미터 추가. BlockNavigation 의 onExtendSelection* 콜백 안에서 헬퍼에 `onEscapeToParent` 로 전달
- `BlockItem` 시그니처에 `documentSelection` + `containerPath` 추가, Callout 분기에서 CalloutBlockEditor 에 전달
- `CalloutBlockEditor` Standard / Dialogue 시그니처에 `documentSelection` + `containerPath` 추가. body MarkdownBlockEditor 호출에:
  - `documentSelection = documentSelection`
  - `containerPath = containerPath + block.id`
  - `onEscapeSelectionToPrevious/Next = { navigation.onSelectSelfAsAtomic() }`
- `SelectionUiHelpers.kt` 의 extendSelectionToPrevious/Next 헬퍼에 `onEscapeToParent: () -> Unit = {}` 파라미터 추가. `currentIndex == 0` (또는 lastIndex) + `containerPath.isNotEmpty()` 면 onEscapeToParent 호출 → 부모 컨테이너의 onSelectSelfAsAtomic 으로 자기 Callout 을 atomic 선택

**검증 시나리오**:
- Callout body 의 첫 블록 첫 줄 cursor → Shift+↑ → Callout 자체만 atomic selection (외부 위 블록 포함 X) ✓
- Callout body 의 마지막 블록 마지막 줄 cursor → Shift+↓ → Callout 자체만 atomic ✓
- body 안 중간 블록의 Shift+↑/↓ → body 안 cross-selection 활성 (containerPath = [callout.id])
- 중첩 Callout 도 재귀적 path 누적으로 자연 지원

### Step A 검증 시나리오

1. 빈 문서에 텍스트 + Callout + Code 블록 작성 → `Ctrl+A` 누르기 → 모든 블록에 selectionAccent 배경 표시
2. `Ctrl+C` → 외부 에디터에 paste → raw markdown 전체가 들어가있음
3. `Esc` → 배경 사라짐
4. Callout 안에서 입력 중 `Ctrl+A` → Callout 자체가 atomic 으로 들어가있는 selection 으로 표시됨
5. `LocalClipboardManager` deprecation 경고 1건 (`LocalClipboard` 로 마이그레이션 권장) — 동작 무관

---

## 9. 다음 세션 시작 가이드 (작업 인계)

### 9.1 현재 상태 한 줄 요약

**Phase 1 완료 + Scope A 누적 확장 완료.** Phase 1 (Step A + B-1 + B-2a + B-2b + B-2c + DL Shift+→) 사용자 검증 통과. 누적 cross-block selection 의 Scope A (최상위 단일 소유, 같은 컨테이너 내 연타 확장) 구현 완료 — 검증 대기. 다음 작업은 Phase 3 (잘라내기/붙여넣기 + replace, 섹션 11 분석) 또는 Phase 2 (마우스 드래그) 중 선택.

### 9.2 검증된 동작 (Phase 1 전체)

| 기능 | 동작 |
|---|---|
| `Ctrl+A` / `Cmd+A` | 최상위 전체 블록 selection |
| `Ctrl+C` / `Cmd+C` | selection 범위의 markdown 을 clipboard 로 복사 |
| `Esc` / 방향키 / Home / End / PageUp/Down | Multi 상태에서 None 으로 자동 해제 |
| focus 이동 (다른 블록 클릭) | 자동 해제 — 단 endpoint blockId 매칭 시 보존 (Shift 확장 후 cursor 이동 대응) |
| 마우스 press (에디터 내부 어디든) | 무조건 자동 해제 — Ctrl+A 후 같은 블록 재클릭 / endpoint 블록 클릭의 사각지대 보완 (`resetDocumentSelectionOnPointerPress`) |
| 외부 Text 의 첫/마지막 줄에서 Shift+↑/↓ | 외부 Text 끼리는 native 확장, atomic 블록 진입이면 atomic 만 selected |
| Callout title 에서 Shift+↑/↓ | Callout 자체만 atomic (외부 블록 포함 X) |
| DL Callout title 에서 Shift+→ | DL Callout 자체만 atomic |
| CodeBlock 첫/마지막 줄에서 Shift+↑/↓ | CodeBlock 자체만 atomic |
| Callout body 첫 블록 첫 줄에서 Shift+↑ | 부모 Callout 자체만 atomic (외부 위 블록 포함 X) |
| Callout body 마지막 블록 마지막 줄에서 Shift+↓ | 부모 Callout 자체만 atomic |
| Callout body 안 중간 블록의 Shift+↑/↓ | body 안 cross-selection (containerPath=[callout.id]) |
| 중첩 Callout | 재귀적 path 누적으로 자연 지원 |
| 시각화 색 | native BasicTextField selection 색과 통합 (`LocalTextSelectionColors` 동기화) |
| Shift+↑/↓ 후 cursor 이동 | selection 의 focus 블록으로 자동 focus 이동 (selection 보존) |
| **Shift+↑/↓ 누적 확장 (Scope A)** | Multi 존재 시 최상위가 소유 — anchor 고정 + focus 한 칸씩 이동. 같은 컨테이너 안에서 연타로 계속 확장. 경계 도달 시 보존. atomic 블록은 통째 포함 |
| **누적 확장 시 스크롤 따라가기** | focus endpoint 가 화면 밖이면 root LazyColumn 자동 스크롤 — 적응형 단발(`delta` 만큼 `animateScrollBy` 한 번, 멀면 `animateScrollToItem`). 스크롤 먼저 → 노드 compose → requestFocus 순서라 recycle 된 블록도 focus 성공 |

### 9.3 미구현 — Phase 3 으로 이관

- **selection + 텍스트 입력 → replace**: 현재 selection 시각만 있고 native 입력이 endpoint 블록에 그대로 들어감 (selection 보존). 자연스러운 동작은 selection 범위 삭제 + 입력 텍스트 삽입. Phase 3 의 `deleteSelection` / `replaceSelection` 과 통합 진행 (섹션 11.3 분석)
- **~~Shift+↑/↓ 누적 확장~~**: ✅ **Scope A 로 구현 완료** (섹션 2.5.1). Multi 존재 시 최상위 핸들러가 소유하여 anchor 고정 + focus 한 칸씩 누적. 컨테이너 횡단 누적(Scope B)은 보류

### 9.4 다음 작업 선택지

**Phase 2 — 마우스 드래그 selection** (큰 작업)
- `pointerInput { detectDragGestures }` 를 최상위에 부착
- 각 `BlockItem` 의 절대 좌표 + `TextLayoutResult` 캐시 (`onGloballyPositioned`)
- 드래그 좌표 → 해당 블록의 `getOffsetForPosition` → endpoint 갱신
- LazyColumn 가장자리 도달 시 auto-scroll
- 단일 블록 내부 드래그는 native BasicTextField 동작 그대로 (블록 경계 넘을 때만 documentSelection 으로 승격)

**Phase 3 — 잘라내기 / 붙여넣기 + replace** (큰 작업)
- `BlockOperations.deleteSelection(blocks, selection): List<EditorBlock>` — endpoint 블록 분할 + 중간 블록 제거 + 인접 일반 텍스트 병합
- `Ctrl+X` = `Ctrl+C` + `deleteSelection`
- `Ctrl+V`: clipboard text → `MarkdownBlockParser.parse` → 새 블록 리스트. selection 있으면 `deleteSelection` 후 삽입
- selection 있는 상태로 텍스트 입력 → `replaceSelection(text)` (9.3 의 미구현 사항도 같이 해결)

**Phase 4 — 글자/단어/줄/페이지 단위 Shift+화살표** (중간)

**Phase 5 — Table 셀 단위 누적 selection (엑셀 형식)** + CodeBlock atomic 유지 (사용자 결정 완료, 섹션 7.1 참조)

### 9.5 관련 파일 빠른 참조

| 역할 | 파일 |
|---|---|
| Selection 모델 + 비즈니스 로직 (`normalize` / `extractMarkdown` / `isAtomic` / **`nextFocusEndpoint`** 누적 traversal) | `markdown/state/DocumentSelection.kt` |
| UI 헬퍼 (단축키 + **Shift+↑/↓ 누적 분기** / 시각화 / 개시 확장 + escape / focus reset + pointer reset) | `markdown/ui/selection/SelectionUiHelpers.kt` |
| 최상위 진입 + state 호이스팅 + CompositionLocal 제공 + native selection 색 통합 + `resetDocumentSelectionOnPointerPress` 부착 | `markdown/ui/MarkdownBlockTextField.kt` |
| 블록 dispatcher + BlockNavigation (onExtendSelection*/onSelectSelfAsAtomic/onEscapeSelection*) + 시각화 + cursor 이동 LaunchedEffect | `markdown/ui/MarkdownBlockEditor.kt` |
| Callout title 의 Shift 핸들러 + body 재귀 호출 (documentSelection/containerPath 전파) + DL Shift+→ | `markdown/ui/block/CalloutBlockEditor.kt` |
| TextBlock 의 Shift+↑/↓ 핸들러 + 외부 → atomic 진입 분기 | `markdown/ui/TextBlockEditor.kt` |
| CodeBlock 의 Shift+↑/↓ 핸들러 | `markdown/ui/block/CodeBlockEditor.kt` |
| 모든 BasicTextField 의 `Modifier.resetDocumentSelectionOnFocus(blockId)` 부착 | TextBlock / Callout title (Std+DL) / Code / Table 셀들 |
| 스타일 (selectionAccent, M3 native default 와 동기화) | `markdown/service/MarkdownStyleConfig.kt` |

### 9.6 빌드 / 검증 명령

```bash
./gradlew :composeApp:compileKotlinJvm  # 빌드 검증
./gradlew :desktopApp:run               # 수동 검증 (데스크탑)
```

---

## 11. Phase 3 작업량 분석 — 잘라내기 / 붙여넣기 / selection-replace

Scope A 누적 selection 완료 후 가장 가치 있는 다음 작업. 코드 조사 기반 분석 (`BlockOperations.kt` 300줄, `MarkdownBlockTextField.kt` 의 blocks 호이스팅, native paste 흐름).

### 11.1 핵심 신규 연산 — `deleteSelection`

```kotlin
fun deleteSelection(
    blocks: List<EditorBlock>,
    selection: DocumentSelection.Multi,
): DeleteResult  // { blocks: List<EditorBlock>, focusBlockId: String, cursorOffset: Int }
```

- `normalize` 로 start/end 결정 → 같은 컨테이너 범위 (Scope A 산출물은 항상 동일 컨테이너).
- start 블록이 Text → `substring(0, start.offset)` 잔여, end 블록이 Text → `substring(end.offset, len)` 잔여 → **두 잔여를 한 Text 블록으로 병합** (인접하므로). 중간 블록 제거.
- start/end 가 atomic → 통째 제거.
- cursor 는 병합 지점 (= start.offset).
- 컨테이너가 Callout body 면 그 Callout 을 새 bodyBlocks 로 재구성 (부모 rebuild).

**규모: 중.** 잔여 병합 (id 재사용 + TextFieldState 재생성 + cursor offset) 이 까다로운 핵심. 기존 `mergeWithPrevious` 와 결은 비슷하나 형태가 달라 직접 재활용은 안 됨. cross-container 시 부모 rebuild 가 추가.

### 11.2 잘라내기 (Ctrl+X) / 붙여넣기 (Ctrl+V)

| 동작 | 구현 | 규모 / 리스크 |
|---|---|---|
| **Ctrl+X** | `extractMarkdown` (구현됨) → clipboard + `deleteSelection` | **소** (deleteSelection 완성 후). 낮음 |
| **Ctrl+V (Multi selection 있음)** | clipboard → `MarkdownBlockParser.parse` (구현됨) → `deleteSelection` 후 그 자리 삽입 + 경계 병합 | **중**. 중간 |
| **Ctrl+V (cursor만, selection 없음)** | **native 그대로** — BasicTextField 가 텍스트 삽입, focus-out 시 기존 `tryReparse` 가 블록 승격 처리 | **0** (이미 동작). 없음 |

핵심 절감: cursor-only paste 는 손대지 않는다. **Multi selection 을 대체하는 paste 만** 신규 코드. Ctrl+V 핸들러는 Multi 일 때만 가로채고 아니면 false 반환.

### 11.3 selection-replace on input (입력 시 삭제 후 대체)

Multi selection 상태에서 사용자가 글자를 입력하면 → selection 범위 삭제 + 입력 글자 삽입.

**가장 까다로운 부분.** 이유:
- 텍스트 입력은 key event 가 아니라 BasicTextField 의 `InputTransformation` 경로로 들어온다. 그런데 InputTransformation 은 **블록 단위**라 다른 블록을 삭제할 수 없다.
- 입력 keystroke 는 현재 focus 된 (옛) 블록에 도착 → 삭제 적용 전에 입력이 먼저 들어가는 순서 문제 (race).
- printable key → 문자 매핑은 IME / 로케일에서 취약 (key event 로 문자를 직접 만들면 한글 조합 등 깨짐).

**후보 접근:**
1. 최상위 `onPreviewKeyEvent` 에서 Multi + printable 감지 → `deleteSelection` → Multi=None → 병합 블록에 focus + cursor → 이벤트는 consume 안 함 → 병합 블록에서 native 입력 진행. **단 focus 이동과 입력 도착의 순서 보장이 관건** (LaunchedEffect 비동기 focus vs 동기 keystroke).
2. IME 안전성 위해 printable 판정은 최소화하고, 조합 문자는 별도 처리 필요할 수 있음.

**규모: 대 + 리스크 높음.** Phase 1 누적 시도와 유사한 focus/keystroke race 위험이 있어 가장 마지막에, 독립적으로 검증하며 진행 권장.

### 11.4 plumbing

- 현재 `documentSelectionShortcuts(rootBlocks, documentSelection)` 는 blocks 를 mutate 할 수단이 없음. Ctrl+X/V/replace 는 `onBlocksChanged: (List<EditorBlock>) -> Unit` 추가 전달 필요 (`MarkdownBlockTextField` 의 `blocks` state).
- cross-container 삭제 시 부모 Callout rebuild 로직 (deleteSelection 내부).

**규모: 중.**

### 11.5 권장 분할 + 작업량 요약

| 단계 | 내용 | 규모 | 리스크 |
|---|---|---|---|
| **3a** | `deleteSelection` + `Ctrl+X` + plumbing | 중 | 낮음 |
| **3b** | `Ctrl+V` (Multi 대체 paste). cursor-only 는 native 유지 | 중 | 중간 |
| **3c** | selection-replace on input | 대 | **높음** (focus/keystroke race, IME) |

**권장 순서: 3a → 3b → 3c.** 3a 가 가장 가치 높고 자족적 (잘라내기 + 삭제 기반). 3c 는 마지막에 독립 검증. 전체적으로 Phase 1 (Step A~B 전체) 와 비슷하거나 약간 큰 규모이며, 리스크는 3c 에 집중됨.

---

## 10. 개정 이력

- **2026-05-05**: 최초 작성. Phase 1 진입 전 영구 설계 문서로 격상. plan 파일 (`.claude/plans/2-inherited-lecun.md`) 의 1~2 절을 본 문서로 옮김.
- **2026-05-05**: Phase 1 Step A 구현 완료. 섹션 8 추가.
- **2026-05-05**: 옵션 C v2 정책으로 정정 — 외부 → atomic 진입 시에도 atomic 블록만 selection (외부 Text 포함 X). 섹션 2.4 갱신. Step B-2a 가 재작업 필요로 표시됨. 섹션 9 "다음 세션 시작 가이드" 신규 추가.
- **2026-05-05**: Step B-2a 재작업 완료 (헬퍼의 atomic 분기). focus 이동 자동 해제 (`Modifier.resetDocumentSelectionOnFocus`) + native selection 색 통합 (`LocalTextSelectionColors` 동기화) 추가.
- **2026-05-05**: Shift+↑/↓ 누적 확장 시도 (헬퍼의 baseIndex 재계산 + 누적 분기 + cursor 이동 LaunchedEffect) → race condition 으로 잘못 작동 → **롤백** (섹션 2.5 시도 기록). 단 cursor 이동 LaunchedEffect 와 endpoint 비교 reset 보존.
- **2026-05-05**: DL Callout title 의 Shift+→ → Callout 자체만 atomic 추가 (섹션 2.4).
- **2026-05-05**: Step B-2c 완료 (body 안 cross-selection + 경계 박스 탈출). `BlockNavigation.onSelectSelfAsAtomic` 콜백 + 헬퍼의 `onEscapeToParent` 콜백 + `CalloutBlockEditor` 의 `documentSelection`/`containerPath` 파라미터 + body 호출에서 `containerPath + block.id` 누적 + `onEscapeSelectionToPrevious/Next = { navigation.onSelectSelfAsAtomic() }` 연결. 중첩 Callout 도 재귀적 path 누적으로 자연 지원. **Phase 1 전체 완료** — 검증 대기 중.
- **2026-05-05**: Phase 5 정책 사용자 결정 — Table 은 엑셀 형식 셀 단위 누적 사각형 selection 채택 (옵션 B). CodeBlock 은 atomic 유지 (옵션 A). 섹션 7.1 (구현 가이드 포함) / 섹션 3 의 Phase 5 갱신.
- **2026-05-23**: Phase 1 검증 중 발견된 버그 수정 — Ctrl+A 후 마우스 클릭으로 selection 이 해제되지 않던 문제. 원인: `onFocusChanged` 기반 해제가 (1) Ctrl+A 는 포커스를 옮기지 않아 같은 블록 재클릭 시 미발동, (2) endpoint 블록 클릭은 보존 예외라 미해제. 마우스 press 시점에 무조건 해제하는 `resetDocumentSelectionOnPointerPress` (Initial pass 관찰, non-consuming) 추가하여 최상위 Box 에 부착.
- **2026-05-23**: **Scope A 누적 확장 구현 완료** (섹션 2.5.1). 롤백된 누적 확장을 최상위 단일 소유 구조로 재설계 — `documentSelectionShortcuts` 의 onPreviewKeyEvent (preview = top-down) 가 Multi 존재 시 Shift+↑/↓ 를 가로채 `nextFocusEndpoint(blocks, focus, down)` 으로 focus 만 누적 이동, consume. 개시는 블록 핸들러 유지 (None 이면 false 반환). focus 이동을 cosmetic 으로 강등하여 race 제거. `DocumentSelection.kt` 에 `nextFocusEndpoint` 신규. 컨테이너 횡단 누적 (Scope B) 은 보류.
- **2026-05-23**: Scope A 누적 확장에 스크롤 따라가기 추가 — cursor-following `LaunchedEffect(focusTargetId)` 에 스크롤-into-view (`!isNested` 가드). 처음엔 네비게이션 경로의 고정 80px nudge + 50ms 대기 방식을 복사했으나 **느리게 느껴져** 적응형 단발(가장자리까지 `delta` 만큼 `animateScrollBy` 한 번, 멀면 `animateScrollToItem`, 중간 대기 제거)로 교체. 섹션 2.5.1 / 9.2.
- **2026-05-23**: Phase 3 (잘라내기/붙여넣기/selection-replace) 작업량 분석 추가 (섹션 11). 3a(deleteSelection+Ctrl+X) → 3b(Ctrl+V Multi 대체) → 3c(입력 시 대체) 분할 권장. cursor-only paste 는 native 유지로 절감. 3c 는 focus/keystroke race + IME 리스크로 마지막 독립 진행 권장.
