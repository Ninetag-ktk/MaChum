# MaChum 마크다운 에디터 — 구조 설명

---

## 1. 아키텍처 변천

| 버전 | 방식 | 상태 |
|---|---|---|
| v1 (Phase 2~5) | 단일 BasicTextField + OutputTransformation + 오버레이 Composable | **현행** (deprecated 예정) |
| **v2** | **블록 기반 에디터 (LazyColumn + 블록별 Composable)** | **설계 완료, 구현 예정** |

### v1의 한계 (v2 전환 이유)
- overlay 높이 ≠ raw text 높이 (chrome 누적, SpanStyle로 lineHeight 축소 불가)
- depth=0 clipToBounds → 긴 callout body 내부 스크롤 발생
- overlay ↔ raw text 양방향 sync 복잡도
- TextLayoutResult 1프레임 지연 → stale layout 대응 필요

### v2의 해결
- 각 블록이 독립 Composable → 높이 제한 없음
- 투명 텍스트/오버레이 배치 불필요 → 높이 불일치 근본 해결
- 직접 편집 → `toMarkdown()` 직렬화 (양방향 sync 불필요)

---

## 2. v2 블록 에디터 구조

상세 설계: **CLAUDE_sub.md** 참고.

### 데이터 흐름

```
[파일 로딩]
  .md 파일 (raw markdown)
      ↓ MarkdownBlockParser.parse()
  List<EditorBlock>

[사용자 입력]
  각 블록의 TextFieldState에 직접 입력
      ↓ 블록별 InputTransformation (Smart Enter 등)
      ↓ 블록별 OutputTransformation (인라인 서식)
  화면 표시

[저장]
  blocks.joinToString("\n\n") { it.toMarkdown() }
      ↓ raw markdown 문자열
```

### 블록 타입

| 블록 | Composable | 편집 방식 |
|---|---|---|
| Text | `TextBlockEditor` | BasicTextField + 인라인 서식 |
| Callout | `CalloutBlockEditor` | 제목 TextField + 재귀적 블록 에디터 |
| CodeBlock | `CodeBlockEditor` | Monospace TextField |
| Table | `TableBlockEditor` | 셀별 TextField |
| HorizontalRule | `HorizontalRuleDivider` | 읽기 전용 |
| Embed | `EmbedBlockRenderer` | 읽기 전용 (원본 파일 편집은 별도) |

### 구현 순서

- **Phase 1**: 블록 모델 + 파싱/직렬화 + 블록별 렌더링
- **Phase 2**: 블록 간 커서 이동 + 분할/병합
- **Phase 3**: Cross-block selection + Undo/Redo + Embed

---

## 3. v1 현행 구조 (참고용)

v2 전환 완료 시 제거 예정. 전환 기간 동안 참고.

### 핵심 원리

TextFieldState에 raw markdown 저장. `OutputTransformation`이 비활성 줄의 기호를 투명 처리.
특수 블록은 투명 텍스트 위에 오버레이 Composable을 배치.

### 파일 구조

```
markdown/
├── service/
│   ├── MarkdownStyleConfig.kt          ← v2 재활용
│   └── util/
│       ├── EditorKeyboardShortcuts.kt   ← v2 재활용
│       └── OverlayScrollForwarder.kt    ← v2에서 제거
│
├── state/
│   ├── MarkdownBlock.kt                 ← v2 재활용
│   ├── MarkdownEditorState.kt           ← v2에서 EditorBlock으로 대체
│   ├── MarkdownPatternScanner.kt        ← v2 MarkdownBlockParser 기반
│   ├── InlineStyleScanner.kt            ← v2 재활용
│   ├── RawMarkdownOutputTransformation.kt ← v2 TextBlock 전용으로 간소화
│   ├── EditorInputTransformation.kt     ← v2 재활용
│   ├── RawStyleToggle.kt               ← v2 재활용
│   └── OverlayBlockParser.kt           ← v2에서 제거
│
└── ui/
    ├── MarkdownBasicTextField.kt        ← v2에서 MarkdownBlockEditor로 대체
    ├── MarkdownTextField.kt             ← v2에서 래퍼 유지 (인터페이스 호환)
    ├── BlockDecorationDrawer.kt         ← v2 재활용
    ├── OverlayPositionCalculator.kt     ← v2에서 제거
    └── block/
        ├── BlockOverlay.kt              ← v2에서 제거
        ├── CalloutOverlay.kt            ← v2 CalloutBlockEditor로 전환
        ├── CodeBlockOverlay.kt          ← v2 CodeBlockEditor로 전환
        └── TableOverlay.kt              ← v2 TableBlockEditor로 전환
```
