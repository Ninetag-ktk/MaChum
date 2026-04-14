# 블록 에디터 — Phase별 체크리스트

상세 설계: **CLAUDE_sub.md** 참고.

---

### Phase 1: 기본 구조 ✅ 완료
- [x] `EditorBlock` sealed class (`state/EditorBlock.kt`)
- [x] `MarkdownBlockParser.parse()` (`state/MarkdownBlockParser.kt`)
- [x] `EditorBlock.toMarkdown()` + `List<EditorBlock>.toMarkdown()`
- [x] `MarkdownBlockEditor` LazyColumn dispatcher (`ui/MarkdownBlockEditor.kt`)
- [x] `TextBlockEditor` 인라인 서식 (`ui/TextBlockEditor.kt`)
- [x] `CalloutBlockEditor` Standard + DIALOGUE (`ui/block/CalloutBlockEditor.kt`)
- [x] `CodeBlockEditor` (`ui/block/CodeBlockEditor.kt`)
- [x] `TableBlockEditor` (`ui/block/TableBlockEditor.kt`)
- [x] `HorizontalRuleDivider` (`ui/block/HorizontalRuleDivider.kt`)
- [x] `MarkdownBlockTextField` + M3 래퍼 (`ui/MarkdownBlockTextField.kt`)
- [x] EditorPage 직접 전환 (`screen/mainComposition/EditorPage.kt`)

### Phase 2: 블록 간 상호작용 (부분 완료)
- [x] `BlockOperations` 분할/병합 (`state/BlockOperations.kt`)
- [x] TextBlock 간 ↑↓ 방향키 커서 이동
- [x] TextBlock 재파싱 자동 분리 (`tryReparse()` + debounce 150ms)
- [x] TextBlock Backspace 병합
- [ ] **Callout/CodeBlock/Table/HR 간 방향키 이동**
- [ ] **Callout title ↔ body 키보드 내비게이션**
- [ ] **Smart Enter 블록 단위 확장**

### Phase 3: 고급 기능
- [ ] Cross-block selection + 복사/잘라내기
- [ ] Undo/Redo (문서 스냅샷)
- [ ] Embed 블록 렌더링
- [ ] v1 코드 제거 + 패키지 정리

---

### 해결된 이슈
- 포커스 아웃 서식 미적용 → `remember(styleConfig, isFocused)` OT 재생성
- FocusRequester 초기화 → id 기반 맵 + `focusRequestCounter` + delay
- TextBlock 내 블록 서식 깨짐 → `tryReparse()` 자동 분리
