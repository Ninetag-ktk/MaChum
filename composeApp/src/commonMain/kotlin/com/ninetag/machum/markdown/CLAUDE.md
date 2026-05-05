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

### TextBlock 보조 컴포넌트 (v1에서 재활용 → v2 전용으로 정리 완료)

| 컴포넌트 | 파일 | 블록 에디터에서의 용도 |
|---|---|---|
| `RawMarkdownOutputTransformation` | `state/` | TextBlockEditor의 OutputTransformation (인라인 서식 + inline code 범위 수집) |
| `InlineStyleScanner` | `state/` | TextBlock SpanStyle 계산 (Heading / TextBlock / HorizontalRule) |
| `MarkdownPatternScanner` | `state/` | TextBlock 콘텐츠 스캔 → BLOCKQUOTE / HORIZONTAL_RULE BlockRange |
| `EditorInputTransformation` | `state/` | Smart Enter, auto-close |
| `RawStyleToggle` | `state/` | 서식 토글 (Ctrl+B/I/E 등) |
| `EditorKeyboardShortcuts` | `service/util/` | 키보드 단축키 핸들러 |
| `BlockDecorationDrawer` | `ui/` | DrawBehind (blockquote 좌측 바, HR 구분선, inline code 배경) |
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
| Embed `![[파일명]]` | ⬜ Phase 3 (#23) — 현재 변환 비활성화. 일반 TextBlock 텍스트로 표시됨. 박스 UI 구현 시점에 활성화 |

---

## dissolve(서식 해제) 동작 (Phase 3 #26 — v3 코드 구현 완료, 수동 검증 대기)

특수 블록을 raw markdown TextBlock 으로 풀어내고, **편집 중에는 절대 다시 rendering 되지 않으며 focus-out 후에야** 마커 검사를 통해 rendering 으로 돌아온다.

**dissolve 트리거 (Backspace):**

| 트리거 | 처리 |
|---|---|
| Code/Callout/Table/Embed 다음 TextBlock 위치 0 Backspace | 직전 특수 블록만 dissolve → `Text(rawMode=true, rawOrigin=...)`. 인접 블록 merge 없음 |
| Callout title 위치 0 Backspace | Callout 만 dissolve → `Text(rawMode=true, rawOrigin=CALLOUT)` |

**rendering 복귀 트리거 (focus-out, rawMode=true 블록만):**

raw 블록에서 다른 곳으로 포커스 이동 → 200ms delay → `tryReparse` 1회 → 무조건 적용:
- parsed = 단일 특수 블록 → 그 블록으로 변환 (rendering 복귀)
- parsed = 단일 일반 텍스트 → rawMode=false 인 새 Text 로 교체 (자동 해제)
- parsed = 여러 블록 → 일반 분리

200ms delay 안에 다시 raw 블록으로 돌아오면 reparse 미발동 (transient focus-out 무시). focus 는 사용자가 옮긴 위치 그대로 보존 (silent reparse).

**자동 격하 / 자동 해제:**

- **Block 유형 (Code/Callout/Table)**: 모든 state 가 빈 순간 (한 번이라도 내용이 있었던 경우) → 빈 일반 TextBlock 으로 자동 격하. 박스 사라짐
- **raw 블록**: 텍스트가 빈 순간 → `rawMode=false` 인 일반 TextBlock 으로 즉시 변환 (id/textFieldState 유지, transient 상태 정리)
- **ZWSP placeholder**: Block→Block 빈 줄 마커가 있는 TextBlock 에 사용자가 입력하는 순간 ZWSP 자동 제거 → 일반 TextBlock 으로 격하 (line prefix 매칭 깨짐 방지)
- **Embed 변환 비활성화 (현재)**: parser 와 tryReparse 가 `![[xxx]]` 를 Embed 블록으로 변환하지 않음. 일반 TextBlock 텍스트로 그대로 남음. 박스 UI 가 미구현(#23) 인 상태에서는 변환의 의미가 없고 focus 끊김 등 부작용만 발생하므로 비활성화. Embed 클래스 / RawOrigin.EMBED / dissolveSpecial 의 Embed 케이스 / BlockItem 의 Embed 분기 + promotion 로직은 모두 보존되어 #23 진입 시 parser 한 줄 활성화로 복귀 가능

**Smart Enter 정책 (#20):** 박스 UI 가 있는 CodeBlock 에만 적용 (빈 마지막 줄 + Enter → 탈출). TextBlock 은 미적용 — 다음 블록 이동은 ↓ 방향키 사용. Callout body 도 ↓ 방향키 경로로 외부 탈출.

**raw 상태 시각 정책:** raw zone (포커스 줄 / rawMode=true 블록 전체) 에서는 `> ` blockquote 의 좌측 회색 바를 그리지 않음. raw 마커가 그대로 보이는 상태에서 좌측 바가 같이 표시되면 시각적 충돌 — 특히 dissolve 된 Callout raw TextBlock 의 `> body` 텍스트에서 어색했음.

상세 / 검증 시나리오는 `CLAUDE_sub.md` 섹션 10.

---

## 이력

v1 (단일 BasicTextField + overlay) 아키텍처는 v2 블록 기반으로 완전히 대체됨. 미사용 파일 12개 삭제, 재활용 5개 정리 완료(~580줄 삭감). 이력은 git log 와 `CLAUDE_sub.md` 의 "해결된 기술적 이슈" 테이블 참조.
