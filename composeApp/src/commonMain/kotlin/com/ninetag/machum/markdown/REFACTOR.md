# v1 레거시 정리 — 리팩토링 대상

> v1 미사용 파일 12개는 삭제 완료. 이 문서는 v2에서 재활용 중인 v1 코드 중
> v1 전용 로직이 남아있는 파일의 리팩토링 방향을 정리한다.

---

## 삭제 완료 (12파일)

```
ui/MarkdownBasicTextField.kt
ui/MarkdownTextField.kt
ui/OverlayPositionCalculator.kt
ui/block/BlockOverlay.kt
ui/block/CalloutOverlay.kt
ui/block/CodeBlockOverlay.kt
ui/block/TableOverlay.kt
ui/block/InlineOnlyOutputTransformation.kt
ui/block/HorizontalRuleDivider.kt
state/MarkdownEditorState.kt
state/OverlayBlockParser.kt
service/util/OverlayScrollForwarder.kt
```

---

## 리팩토링 대상 (6파일)

### 1. MarkdownPatternScanner.kt — v1 코드 약 50%

**현재 역할:** 텍스트를 스캔하여 블록 범위(`BlockRange`)와 인라인 서식(`spans`)을 생성.
`RawMarkdownOutputTransformation`이 호출 → `BlockDecorationDrawer`가 블록 범위를 사용.

**v2에서 실제로 사용하는 블록 타입:**
- `BLOCKQUOTE` — 좌측 바 그리기 ✅
- `HORIZONTAL_RULE` — 구분선 그리기 ✅

**v2에서 사용하지 않는 블록 타입 (전용 에디터가 처리):**
- `CALLOUT` — CalloutBlockEditor가 처리. 스캐너가 감지해도 DrawBehind에서 skip.
- `TABLE` — TableBlockEditor가 처리. DrawBehind에서 빈 case.
- `CODE_BLOCK` — CodeBlockEditor가 처리. DrawBehind에서 빈 case.
- `EMBED` — 현재 TextBlock으로 fallback. drawEmbedDecoration은 사실상 미사용.

**리팩토링 방향:**
- CALLOUT/TABLE/CODE_BLOCK/EMBED 감지 로직 제거 (약 200줄)
- BLOCKQUOTE + HR + 인라인 서식 스캔만 유지
- `BlockType` enum에서 `CODE_BLOCK`, `CALLOUT`, `TABLE`, `EMBED` 제거

**예상 삭감:** ~200줄

---

### 2. InlineStyleScanner.kt — v1 코드 약 40%

**현재 역할:** 블록 타입별 SpanStyle 계산. `MarkdownPatternScanner`와 `RawMarkdownOutputTransformation` 내부에서 호출.

**v2에서 실제로 사용하는 분기:**
- `Heading` → 헤딩 SpanStyle ✅
- `TextBlock` → 줄 prefix 숨김 + 인라인 서식 ✅
- `HorizontalRule` → blockTransparent ✅

**v2에서 사용하지 않는 분기:**
- `CodeBlock` → v2 CodeBlockEditor가 자체 처리
- `Table` → v2 TableBlockEditor가 자체 처리
- `Embed` → 별도 처리 없음
- `calloutSpans()` 함수 전체 — v1 overlay용. v2 CalloutBlockEditor가 자체 렌더링

**리팩토링 방향:**
- `computeSpans()`에서 `CodeBlock`, `Table`, `Embed` case 제거
- `calloutSpans()` 함수 삭제 (약 60줄)
- `MarkdownBlock` sealed class에서 해당 타입 제거

**예상 삭감:** ~100줄

---

### 3. RawMarkdownOutputTransformation.kt — v1 코드 약 60%

**현재 역할:** TextBlockEditor의 OutputTransformation. 인라인 서식 적용 + 비활성 블록 투명 처리.

**v2에서 실제로 사용하는 기능:**
- `MarkdownPatternScanner.scan()` 호출 → 인라인 서식 spans 적용 ✅
- `inlineCodeRanges` → DrawBehind에서 인라인 코드 배경 그리기 ✅
- `blockRanges` → DrawBehind에서 BLOCKQUOTE/HR 그리기 ✅
- `isFocused` → 커서 줄 raw zone 판별 ✅
- `excludeCalloutTypes` → DL 중첩 방지 ✅

**v2에서 사용하지 않는 기능 (v2 TextBlockEditor가 `applyBlockTransparent = false`로 설정):**
- `forceAllOverlaysInactive` — v1 overlay 전용 플래그
- `applyBlockTransparent` — v2에서 항상 false. 분기 자체가 불필요
- `activeBlockRanges` — overlay 활성 판별용. v2 DrawBehind에서 참조하지만 항상 empty
- blockTransparent/heightCollapse 적용 로직 (약 30줄) — `applyBlockTransparent = false`로 항상 skip

**리팩토링 방향:**
- `forceAllOverlaysInactive`, `applyBlockTransparent` 프로퍼티 제거
- blockTransparent/heightCollapse 적용 분기 제거
- `activeBlockRanges` 제거 (항상 empty)
- 클래스명을 `TextBlockOutputTransformation` 등으로 변경하여 역할 명확화

**예상 삭감:** ~50줄 + 복잡도 대폭 감소

---

### 4. BlockDecorationDrawer.kt — v1 코드 약 40%

**현재 역할:** TextBlockEditor의 `drawBehind`에서 블록 데코레이션(좌측 바, 구분선, 인라인 코드 배경) 그리기.

**v2에서 실제로 사용하는 함수:**
- `drawBlockquoteLines()` — blockquote 좌측 바 ✅
- `drawHorizontalRule()` — HR 구분선 ✅
- 인라인 코드 배경 RoundRect 그리기 ✅

**v2에서 사용하지 않는 함수/분기:**
- `drawCalloutDecoration()` — v1 overlay 배경/테두리. v2 CalloutBlockEditor가 자체 처리
- `drawEmbedDecoration()` — v1 embed 테두리
- CALLOUT/TABLE/EMBED 블록 타입 분기 (진입 자체가 안 됨)
- `activeBlockRanges` 관련 skip 로직

**리팩토링 방향:**
- `drawCalloutDecoration()`, `drawEmbedDecoration()` 함수 삭제
- 블록 타입 분기를 BLOCKQUOTE + HR만 남기고 단순화
- `activeBlockRanges` 파라미터 제거

**예상 삭감:** ~120줄

---

### 5. MarkdownBlock.kt — v1 코드 약 50%

**현재 역할:** `InlineStyleScanner.computeSpans()`에서 블록 타입별 분기에 사용하는 sealed class.

**v2에서 실제로 사용하는 타입:**
- `Heading(level)` ✅
- `TextBlock` ✅
- `HorizontalRule` ✅

**v2에서 사용하지 않는 타입:**
- `CodeBlock(language)` — v2 CodeBlockEditor가 자체 처리
- `Table(columnCount)` — v2 TableBlockEditor가 자체 처리
- `Embed(fileName)` — 미사용

**리팩토링 방향:**
- `CodeBlock`, `Table`, `Embed` 제거
- 3개 타입만 남김

**예상 삭감:** ~5줄 (파일 자체가 작음)

---

### 6. MarkdownStyleConfig.kt — 미사용 필드 존재 (경미)

**v2에서 사용하지 않는 필드:**
- `blockTransparent` — v2 TextBlockEditor에서 `applyBlockTransparent = false`로 미적용
- `codeBlockBackground` — v1 drawEmbedDecoration에서만 사용

**리팩토링 방향:**
- 위 2/3/4번 리팩토링 후 자연스럽게 제거 가능
- 단독으로는 우선도 낮음

---

## 리팩토링 순서 권장

1. **MarkdownBlock.kt** → 가장 작고 의존성 파악 용이
2. **InlineStyleScanner.kt** → MarkdownBlock 변경에 따라 case 제거
3. **MarkdownPatternScanner.kt** → 블록 감지 로직 대폭 삭감
4. **BlockDecorationDrawer.kt** → 미사용 함수 삭제
5. **RawMarkdownOutputTransformation.kt** → overlay 로직 제거
6. **MarkdownStyleConfig.kt** → 미사용 필드 정리

1~5번을 한번에 진행하면 약 **470~500줄** 삭감 + v1 overlay 개념 완전 제거.

---

## 리팩토링 하지 않는 파일 (v2 전용 / 이미 정리됨)

| 파일 | 상태 |
|---|---|
| `EditorInputTransformation.kt` | ✅ 깨끗함 |
| `RawStyleToggle.kt` | ✅ 깨끗함 |
| `EditorKeyboardShortcuts.kt` | ✅ 깨끗함 |
| `EditorBlock.kt` | ✅ v2 전용 |
| `MarkdownBlockParser.kt` | ✅ v2 전용 |
| `BlockOperations.kt` | ✅ v2 전용 |
| `MarkdownBlockTextField.kt` | ✅ v2 전용 |
| `MarkdownBlockEditor.kt` | ✅ v2 전용 |
| `TextBlockEditor.kt` | ✅ v2 전용 |
| `CalloutBlockEditor.kt` | ✅ v2 전용 |
| `CodeBlockEditor.kt` | ✅ v2 전용 |
| `TableBlockEditor.kt` | ✅ v2 전용 |
