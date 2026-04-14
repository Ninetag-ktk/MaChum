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
- [x] **#18-2 Table 수정사항 재점검** — data row border 수정, Column 래핑, pendingFocus delay 보정
- [x] **#18-3 Callout body 유실 버그** — LazyColumn stale 클로저 캡처. `BlockWithNav`/`BlockItem`에 `rememberUpdatedState(blocks)`/`rememberUpdatedState(index)` 적용 (`MarkdownBlockEditor.kt`)
- [ ] **#18-4 CodeBlock: 닫는 ``` 전까지 블록 변환하지 않기** — 여는 ``` 만으로 즉시 Code 블록 변환되는 문제
- [ ] **#18-5 Table: 1줄 `|col|` 입력 시 커서 이탈** — tryReparse가 1줄 Table을 TextBlock으로 처리, 포커스가 아래로 빠짐
- [ ] **#19 블록 간 이동 시 커서 위치 보정** — ↑→이전 블록 마지막 줄 같은 x, ↓→다음 블록 첫 줄 같은 x
- [ ] **#20 Smart Enter 블록 단위 확장** — 빈 CodeBlock Enter→탈출, Callout Enter 2회→탈출

### Phase 3: 고급 기능
- [ ] #21 Cross-block selection + 복사/잘라내기
- [ ] #22 Undo/Redo (문서 스냅샷)
- [ ] #23 Embed 블록 렌더링
- [ ] #24 v1 코드 제거 + 패키지 정리

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
