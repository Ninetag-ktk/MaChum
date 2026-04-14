# Markdown Engine & Editor — 설계 문서

`markdown/` 패키지의 마크다운 에디터 구현을 설명한다.

---

## 현재 아키텍처: 블록 기반 에디터

문서를 `List<EditorBlock>`(블록 리스트)로 관리하고, 각 블록이 독립 Composable로 렌더링된다.
이전 v1(단일 BasicTextField + overlay Composable) 아키텍처를 대체함.

**상세 설계: `CLAUDE_sub.md`** | **체크리스트: `compact.md`**

### 핵심 컴포넌트

| 컴포넌트 | 파일 | 역할 |
|---|---|---|
| `EditorBlock` | `state/EditorBlock.kt` | 블록 모델 sealed class + BLANK_LINE_MARKER + `toMarkdown()` |
| `MarkdownBlockParser` | `state/MarkdownBlockParser.kt` | raw markdown → `List<EditorBlock>` (pendingNewlines 방식) |
| `BlockOperations` | `state/BlockOperations.kt` | 블록 분할/병합/재파싱 (특수블록 우선 포커스) |
| `MarkdownBlockEditor` | `ui/MarkdownBlockEditor.kt` | LazyColumn 블록 dispatcher + escape 콜백 |
| `MarkdownBlockTextField` | `ui/MarkdownBlockTextField.kt` | 공개 API + M3 래퍼 |
| `TextBlockEditor` | `ui/TextBlockEditor.kt` | 텍스트 블록 (인라인 서식 + ←↑↓ 블록 이동) |
| `CalloutBlockEditor` | `ui/block/CalloutBlockEditor.kt` | Callout (Standard ↓↑ / DL ←→, Enter body 생성) |
| `CodeBlockEditor` | `ui/block/CodeBlockEditor.kt` | CodeBlock (monospace, ↑↓ 블록 이동) |
| `TableBlockEditor` | `ui/block/TableBlockEditor.kt` | Table (2D focusGrid, Tab/Enter 행 추가, +버튼) |
| `HorizontalRuleDivider` | `ui/block/HorizontalRuleDivider.kt` | HR (미사용 — TextBlock 인라인 렌더링으로 전환) |

### v1에서 재활용하는 컴포넌트

| 컴포넌트 | 파일 | 블록 에디터에서의 용도 |
|---|---|---|
| `RawMarkdownOutputTransformation` | `state/` | TextBlockEditor의 인라인 서식 (`applyBlockTransparent=false`) |
| `InlineStyleScanner` | `state/` | OT 내부에서 SpanStyle 계산 |
| `MarkdownPatternScanner` | `state/` | OT 내부에서 문서 스캔 |
| `EditorInputTransformation` | `state/` | Smart Enter, auto-close |
| `RawStyleToggle` | `state/` | 서식 토글 (Ctrl+B/I/E 등) |
| `EditorKeyboardShortcuts` | `service/util/` | 키보드 단축키 핸들러 |
| `BlockDecorationDrawer` | `ui/` | DrawBehind (blockquote 테두리, inline code 배경) |
| `MarkdownStyleConfig` | `service/` | 전체 스타일 설정 |

---

## 지원 문법

### TextBlock 내 인라인 서식 (SpanStyle 기반)

| 문법 | 상태 |
|---|---|
| Heading `#` ~ `######` | ✅ |
| Bold `**`, Italic `*`, BoldItalic `***` | ✅ |
| Strikethrough `~~`, Highlight `==` | ✅ |
| InlineCode `` ` `` | ✅ |
| WikiLink `[[파일명\|별칭]]` | ✅ |
| ExternalLink `[텍스트](URL)` | ✅ |
| BulletList `-` / `*`, OrderedList `숫자.` | ✅ |
| Blockquote `>` | ✅ |

### 독립 블록 (별도 Composable)

| 문법 | 상태 |
|---|---|
| Callout `> [!type]` | ✅ CalloutBlockEditor |
| CodeBlock ` ``` ` | ✅ CodeBlockEditor |
| Table `\|` | ✅ TableBlockEditor |
| HorizontalRule `---` | ✅ TextBlock 인라인 렌더링 (blockTransparent + DrawBehind Divider) |
| Embed `![[파일명]]` | ⬜ Phase 3 |

---

## v1 아키텍처 (참고용 — deprecated)

단일 BasicTextField에 raw markdown을 저장하고, OutputTransformation으로 기호를 투명 처리.
특수 블록(Callout/Table/Code)은 투명 텍스트 위에 overlay Composable을 배치.

**한계**: overlay 높이 ≠ raw text 높이 (chrome 누적), depth=0 clipToBounds body 스크롤,
overlay ↔ raw text 양방향 sync 복잡도. → 블록 에디터로 전환하여 해결.

v1 전용 파일들은 EditorPage에서 미사용이며 제거 예정:
`MarkdownBasicTextField`, `MarkdownTextField`, `MarkdownEditorState`,
`OverlayBlockParser`, `OverlayPositionCalculator`, `OverlayScrollForwarder`,
`BlockOverlay`, `CalloutOverlay`, `CodeBlockOverlay`, `TableOverlay`.
