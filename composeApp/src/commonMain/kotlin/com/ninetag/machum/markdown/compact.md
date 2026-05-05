# 블록 에디터 — Phase별 체크리스트

상세 설계: **CLAUDE_sub.md** 참고.

---

### Phase 1: 기본 구조 ✅ 완료
- [x] `EditorBlock` sealed class (`state/EditorBlock.kt`)
- [x] `MarkdownBlockParser.parse()` (`state/MarkdownBlockParser.kt`)
- [x] `EditorBlock.toMarkdown()` + `List<EditorBlock>.toMarkdown()`
- [x] `MarkdownBlockEditor` LazyColumn dispatcher (`ui/MarkdownBlockEditor.kt`)
- [x] `TextBlockEditor` 인라인 서식 (`ui/TextBlockEditor.kt`)
- [x] `CalloutBlockEditor` Standard + DL (`ui/block/CalloutBlockEditor.kt`)
- [x] `CodeBlockEditor` (`ui/block/CodeBlockEditor.kt`)
- [x] `TableBlockEditor` (`ui/block/TableBlockEditor.kt`)
- [x] HorizontalRule → TextBlock 인라인 렌더링 (blockTransparent + DrawBehind)
- [x] `MarkdownBlockTextField` + M3 래퍼 (`ui/MarkdownBlockTextField.kt`)
- [x] EditorPage 직접 전환 (`screen/mainComposition/EditorPage.kt`)

### Phase 2: 블록 간 상호작용 (진행 중)

**완료:**
- [x] #12 `BlockOperations` 분할/병합
- [x] #13 TextBlock 간 ↑↓ 방향키 커서 이동
- [x] #14 TextBlock 재파싱 자동 분리 (`tryReparse()` + debounce 150ms)
- [x] #15 TextBlock Backspace 병합
- [x] #16 빈 줄 TextBlock 포함 (pendingNewlines + ZWSP 마커 + universal \n 조인)
- [x] #17 Callout/Code/Table 간 방향키 이동 + Table 셀 내비게이션/행열 추가 UI
- [x] #18 Callout title ↔ body + Enter body 생성 (Standard ↓↑, DL ←→/↑↓탈출)
- [x] #18-1 특수 블록 생성 시 자동 포커스 (tryReparse에서 Callout/Code/Table 우선)

**남은 작업:**
- [x] **#18-2 Table 수정사항 재점검** — 아래 4건 해결:
  - [x] 열 추가(+ 버튼) 클릭 시 동작 안 함: `clickable`이 `tableFocused` 조건부 적용 → 클릭 시 셀 포커스 아웃으로 제거됨. 수정: `clickable` 항상 적용, 아이콘만 조건부 표시. 비포커스 시 hover/click 비활성화는 사용자가 직접 처리 완료
  - [x] 열 추가 시 기존 셀 간격 벌어짐: 셀별 `border(0.5.dp)` → Box divider 방식 교체
  - [x] Tab 마지막 열에서 열 추가: `cellKeyHandler`의 Tab 분기를 `addRow()` → `addColumn()`으로 수정. 불필요한 2번 분기(다음 행 이동) 제거. `cellKeyHandler`를 `focusRequester`보다 outer로 이동하여 Tab 가로채기 해결
  - [x] Enter 마지막 행에서 행 추가: Tab과 동일한 패턴 적용 (아래 행 있으면 이동, 없으면 `addRow()`). `insertRowBelow()` 제거
- [x] **#18-6 빈 줄 Enter 롤백** — `endsWith("\n\n")` 자동 분리 비활성화. #16과 충돌하므로 #20 Smart Enter에서 재설계
- [x] **#18-3 Callout body 유실 버그** — LazyColumn stale 클로저 캡처. `BlockWithNav`/`BlockItem`에 `rememberUpdatedState(blocks)`/`rememberUpdatedState(index)` 적용 (`MarkdownBlockEditor.kt`)
- [x] **#18-4 CodeBlock: 닫는 ``` 전까지 블록 변환하지 않기** — 닫는 펜스 lookahead 후 없으면 TextBlock 유지
- [x] **#18-5 Table: 1줄 `|col|` 입력 시 커서 이탈** — 2줄+ lookahead 후에만 flushText + Table 생성
- [ ] **#19 블록 간 이동 시 커서 위치 보정** — 부분 완료. 미해결:
  - [x] Callout ↑ 진입 → body 마지막: `bottomEntryFRMap` + `onLastBlockBottomEntryRegistered` 체인
  - [x] Table ↑ 진입 → 마지막 행 첫 열: `bottomEntryFRMap`에 `focusGrid[lastRow][0]` 등록 (`TableBlockEditor.kt`, `MarkdownBlockEditor.kt`)
  - [ ] soft wrap 줄 이동: `\n` 기준 → `textLayoutResult.getLineForOffset()` 기준으로 변경 필요
- [x] **#20 Smart Enter 블록 탈출 (정책 정정)** — 박스 UI 가 있는 블록만 적용. CodeBlock 만 빈 마지막 줄 + Enter → 탈출. **TextBlock 은 미적용** (박스 없으므로 탈출 통로 불필요, ↓ 방향키로 충분). 이전 적용 시 ZWSP placeholder / 자동 격하 결과물에서 의도치 않은 탈출 발생 → 핸들러 제거. Callout body 는 마지막 TextBlock 의 ↓ 방향키가 onEscapeToNext 체인 발동
- [x] **ZWSP placeholder 자동 제거** — 사용자가 ZWSP 블록에 입력(텍스트 또는 Enter) 시 즉시 ZWSP 제거 → 일반 TextBlock 으로 격하. line prefix 매칭 깨짐 방지 (`TextBlockEditor.kt` LaunchedEffect)
- [x] **Callout ← 진입 시 body 끝으로 이동** — `onMoveLeft` 에 `pendingCursorHint=End` + `pendingUseBottomEntry=true` 추가. ↑ 와 동일한 진입 의미로 통일
- [x] **Embed 사용자 편집 시 raw 자동 promotion** — `BlockItem` 의 Embed 분기에서 `tempState` remember + 입력 감지 LaunchedEffect → 같은 id+textFieldState 로 `Text(rawMode=true, rawOrigin=EMBED)` 로 교체. "Embed 텍스트 삭제 후 복원" 버그 해결. **단발 처리** (`first()`) + promotion 후 `focusRequester.requestFocus()` 로 cursor 보존
- [x] **Callout body 진입 cursor 강제 (← / ↑)** — bottomEntry FR 호출만으로는 이전 selection 복원되어 어색. LaunchedEffect 가 Callout + bottomEntry + End 케이스에 한해 body 가장 깊은 마지막 Text 의 selection 을 `text.length` 로 강제 설정 (재귀 추적)
- [x] **raw 상태에서 BLOCKQUOTE 좌측 바 숨김** — `RawMarkdownOutputTransformation.currentRawZones` 노출 + `isRawMode` 필드 추가 (block.rawMode). `drawBlockDecorations` 의 BLOCKQUOTE 분기가 raw zone 안의 줄에서 좌측 바 skip. dissolve 된 Callout raw TextBlock 의 `> body` 텍스트 위에 좌측 바가 겹쳐 보이던 시각 충돌 해소

### Phase 3: 고급 기능
- [ ] #21 Cross-block selection + 복사/잘라내기
- [ ] #22 Undo/Redo (문서 스냅샷)
- [ ] **#23 Embed 블록 렌더링** — 박스 UI (이미지/노트 미리보기) 구현. **현재 Embed 변환 비활성화 상태** (parser 의 `isEmbedLine` 분기 제거됨). #23 시점에 parser 한 줄 복원 + 박스 UI 추가. EditorBlock.Embed / RawOrigin.EMBED / dissolveSpecial Embed 케이스 / BlockItem Embed 분기 + promotion 로직은 모두 보존됨
- [x] **#24 v1 코드 제거 + 패키지 정리** — 미사용 파일 12개 삭제 완료. 재활용 파일 5개의 v1 로직 정리 완료 (총 ~580줄 삭감)
- [ ] #25 하드코딩 컬러 M3 테마 적용 — `bulletPrefix`, `orderedPrefix`, `blockquoteAccent`, HR 구분선 등. `calloutIndicator`는 제외
- [ ] **#26 dissolve(서식 해제) 동작** — 정책 v3 코드 구현 완료, 수동 검증 대기. `CLAUDE_sub.md` 섹션 10 참고
  - [x] `EditorBlock.Text` 에 `rawMode`, `rawOrigin` 필드 + `RawOrigin` enum (CODE/CALLOUT/TABLE/EMBED)
  - [x] `BlockOperations.dissolveSpecial()` (Code/Callout/Table/Embed 모두), `dissolveCallout()`, `DissolveResult` 추가 (auto-merge 없음)
  - [x] `BlockOperations.tryReparse` 에 rawMode 분기 (focus-out 시점이라 무조건 적용 — 마커 살아있음→특수블록 / 단일 Text→자동 해제 / 여러 블록→분리)
  - [x] `MarkdownBlockEditor` 에 `CursorHint.AtOffset`, `BlockNavigation.onDissolveSelf` (dissolveSpecial 통합), `onClearRawMode`, `onDegradeToText`, `applyDissolveResult`, `onMergeWithPrevious` dissolve 라우팅
  - [x] `CalloutBlockEditor` Standard/Dialogue title 핸들러에 Backspace at start → `onDissolveSelf`
  - [x] `TextBlockEditor` 에 rawMode 가드된 트리거: snapshotFlow skip + focus-out 200ms delay 후 silent reparse + 빈 상태 즉시 onClearRawMode
  - [x] `BlockItem` 자동 격하 LaunchedEffect — Code/Callout/Table 의 모든 state 가 비면 빈 일반 TextBlock 으로 교체 (`hasHadContent` 가드로 새로 만든 빈 Block 은 제외)
  - [x] InlineStyleScanner 의 ``` fence 가드 + 길이 0 inline code 가드 (raw 블록의 fence 가 일반 텍스트로 보이게)
  - [x] 빌드 검증 (`:composeApp:compileKotlinJvm` BUILD SUCCESSFUL)
  - [ ] 수동 검증: `CLAUDE_sub.md` 10.9 의 시나리오들

**향후 개선사항 (우선순위 낮음):**
- [ ] Table 자체 dissolve 트리거 (첫 셀 빈 + Backspace at 0 → onDissolveSelf). 자동 격하로 해결되므로 필수 아님

---

### 해결된 이슈
- 포커스 아웃 서식 미적용 → `remember(styleConfig, isFocused)` OT 재생성
- FocusRequester 초기화 → id 기반 맵 + `focusRequestCounter` + delay
- TextBlock 내 블록 서식 깨짐 → `tryReparse()` 자동 분리
- 블록 앞뒤 빈 줄 미표시 → pendingNewlines 카운터 + ZWSP 마커(Block→Block)
- 독립 TextField "\n" = 2줄 높이 → ZWSP(`\u200B`) 1줄 높이 + toMarkdown 시 "" 치환
- FocusRequester not initialized → Callout title / Table 첫 셀에 focusRequester 연결
- 특수 블록 생성 후 포커스 이탈 → tryReparse에서 특수 블록 우선 포커스
- **Callout body 유실 (#18-3)** → LazyColumn stale 클로저. `BlockWithNav`/`BlockItem`에 `rememberUpdatedState` 적용. LazyColumn 아이템 콜백에서 외부 상태 캡처 시 반드시 `rememberUpdatedState` 사용할 것
