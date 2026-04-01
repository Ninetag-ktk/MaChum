# MaChum 마크다운 엔진 구조 설명

이 문서는 MaChum의 마크다운 에디터(`markdown/` 패키지) 구현을 설명한다.

---

## 1. 전체 구조

### 아키텍처 — Raw Text + 기호 투명화 방식

| 항목 | 설명 |
|---|---|
| 내부 텍스트 | **raw text** (기호 유지: `# 가나다`, `**볼드**`) |
| 서식 정보 | `OutputTransformation`이 실시간 계산 |
| 화면 표시 | 활성 줄: raw text 그대로 / 비활성 줄: 기호 투명 + SpanStyle |
| 특수 블록 | 비활성: 투명 텍스트 + 오버레이 Composable / 활성: raw text + DrawBehind 배경 |
| 저장 형식 | raw text 그대로 저장 (직렬화 불필요) |

### 구현 상태

| Phase | 내용 | 상태 |
|---|---|---|
| Phase 2 | Raw Text 에디터 (인라인 서식, 활성 줄 감지) | ✅ 완료 |
| Phase 3 | 특수 블록 오버레이 (Callout, Table, CodeBlock) | ✅ 완료 |
| Phase 4 | Smart Enter, 서식 토글, 키보드 단축키 | ✅ 완료 |
| — | 오버레이 위 스크롤 포워딩 | ✅ 완료 |
| — | 활성 Callout/Embed 배경 숨김 | ✅ 완료 |
| — | 테이블 구분자 줄 높이 축소 (marker 0.01sp) | ✅ 완료 |
| — | HorizontalRule (`---`) DrawBehind 구분선 렌더링 | ✅ 완료 |
| — | 서식 내부 auto-close 방지 | ✅ 완료 |
| — | 블록 경계 빈 줄 자동 삽입 (Smart Enter) | ✅ 완료 |
| — | Blockquote `>` prefix 숨김 + DrawBehind 왼쪽 테두리 | ✅ 완료 |
| — | BulletList/OrderedList prefix 연한 색 표시 (숨김 대신) | ✅ 완료 |
| — | OrderedList prefix Monospace (숫자 너비 통일) | ✅ 완료 |
| — | CodeBlock 오버레이 전환 (펜스 줄 축소 + 오버레이 편집) | ✅ 완료 |
| — | Blockquote/Callout 중첩 depth 지원 (`>>`, `>>>`) | ✅ 완료 |
| — | Blockquote depth별 다중 왼쪽 테두리 (DrawBehind) | ✅ 완료 |
| — | Callout 연속 작성 (같은 depth Callout 헤더 → 블록 분리) | ✅ 완료 |
| — | Blockquote `>` 투명 처리 (정상 크기 유지 → 테두리와 자연 간격) | ✅ 완료 |
| — | Callout body 내 full scanner (중첩 callout 헤더 서식 적용) | ✅ 완료 |

### 미구현 / 진행 중 기능

| 기능 | 비고 |
|---|---|
| **재귀적 오버레이 구조** | Callout body에 MarkdownBasicTextField 사용하여 중첩 오버레이 지원. `overlayDepth` 파라미터로 무한 재귀 방지. **구현 진행 중** |
| Undo/Redo | raw text 기반 재설계 필요 |
| 툴바 UI | `RawStyleToggle` 로직 완료, UI만 추가 필요 |
| Embed 오버레이 | 콘텐츠 크기 예측 불가 → LazyColumn 별도 블록 방식 필요 |
| 클립보드 직렬화 | raw text 복사로 대체 가능 |

### 재귀적 오버레이 설계 (진행 중)

Callout/CodeBlock 등 오버레이 내부에서 또 다른 오버레이를 생성하는 구조.

```
MarkdownBasicTextField(depth=0)
  └─ CalloutOverlay
       └─ MarkdownBasicTextField(depth=1)  ← body를 마크다운으로 렌더링
            └─ CodeBlockOverlay            ← 중첩 코드블록 오버레이
            └─ CalloutOverlay              ← 중첩 Callout 오버레이
                 └─ MarkdownBasicTextField(depth=2)
                      └─ (depth >= maxDepth → 오버레이 생성 중단)
```

**핵심 변경점:**
- `MarkdownBasicTextField`에 `overlayDepth` 파라미터 추가
- `overlayDepth >= maxOverlayDepth` 이면 오버레이 생성 스킵 (BasicTextField만 렌더링)
- `BlockOverlay` → `CalloutOverlay`: body에 MarkdownBasicTextField 사용 (depth+1)
- Callout body 동기화: body 텍스트 변경 → `> ` prefix 재추가 → raw 동기화
- 순환 의존: MarkdownBasicTextField → BlockOverlay → CalloutOverlay → MarkdownBasicTextField (depth로 종료 보장)

### 리스트 인디케이터 커스터마이징 가이드

BulletList / OrderedList의 시각적 표현을 변경하려면 다음 파일을 수정한다.

**스타일 설정**: `service/MarkdownStyleConfig.kt`
```kotlin
// BulletList prefix (- , * ) 스타일
val bulletPrefix: SpanStyle = SpanStyle(color = Color(0x66000000)),
// OrderedList prefix (1. , 2. ) 스타일 — Monospace로 숫자 너비 통일
val orderedPrefix: SpanStyle = SpanStyle(color = Color(0x66000000), fontFamily = FontFamily.Monospace),
```

- `bulletPrefix`: `- ` 또는 `* ` 에 적용. `color`, `fontSize`, `fontFamily` 등 변경 가능
- `orderedPrefix`: `1. ` 등에 적용. `fontFamily = FontFamily.Monospace`로 숫자 너비 통일됨
- prefix를 완전히 숨기려면 `config.marker` (0.01sp, 투명)로 변경

**스타일 적용 위치**: `state/InlineStyleScanner.kt` 의 `hideLinePrefix()` 함수 (187번줄~)
- BulletList: `config.bulletPrefix` 적용 구간
- OrderedList: `config.orderedPrefix` 적용 구간

**Material3 래퍼에서 오버라이드**: `ui/MarkdownTextField.kt`
- `defaultMaterialStyleConfig()` 에서 Material3 색상 기반으로 `bulletPrefix` / `orderedPrefix` 오버라이드 가능

---

## 2. 디렉토리 구조

```
markdown/
├── service/                           ← 설정, 유틸리티
│   ├── MarkdownStyleConfig.kt
│   └── util/
│       ├── EditorKeyboardShortcuts.kt
│       └── OverlayScrollForwarder.kt
│
├── state/                             ← 상태, 스캔, 변환, 타입
│   ├── MarkdownBlock.kt
│   ├── MarkdownEditorState.kt
│   ├── MarkdownPatternScanner.kt
│   ├── InlineStyleScanner.kt
│   ├── RawMarkdownOutputTransformation.kt
│   ├── EditorInputTransformation.kt
│   ├── RawStyleToggle.kt
│   └── OverlayBlockParser.kt
│
└── ui/                                ← Composable, 드로잉
    ├── MarkdownBasicTextField.kt
    ├── MarkdownTextField.kt
    ├── BlockDecorationDrawer.kt
    ├── OverlayPositionCalculator.kt
    └── block/
        ├── BlockOverlay.kt
        ├── CalloutOverlay.kt
        ├── TableOverlay.kt
        ├── CodeBlockOverlay.kt
        └── InlineOnlyOutputTransformation.kt
```

### 디렉토리 구분 기준

| 디렉토리 | 역할 | 포함 기준 |
|---|---|---|
| `service/` | 설정 데이터, 공용 유틸리티 | 여러 레이어에서 참조하는 설정/도구. 특정 레이어에 종속되지 않음 |
| `service/util/` | 이벤트 핸들러, Modifier 유틸 | 키보드/스크롤 이벤트를 처리하여 state 레이어에 위임하는 중간 계층 |
| `state/` | 상태 관리, 텍스트 스캔/변환 | TextFieldState, InputTransformation, OutputTransformation, 스캐너, 파서 등 비즈니스 로직 |
| `ui/` | Composable 함수, 캔버스 드로잉 | 화면에 직접 그리는 컴포넌트. state에 의존 |
| `ui/block/` | 블록 오버레이 Composable | 특수 블록(Callout, Table, CodeBlock)의 오버레이 UI. 내부 TextField + raw 동기화 |

---

## 3. 파일별 역할

### service/

| 파일 | 역할 |
|---|---|
| `MarkdownStyleConfig.kt` | 마크다운 서식 SpanStyle 설정. `CalloutDecorationStyle`, `blockTransparent`, `codeBlockBackground` 등. 모든 레이어에서 참조 |

### service/util/

| 파일 | 역할 |
|---|---|
| `EditorKeyboardShortcuts.kt` | 하드웨어 키보드 단축키 핸들러. Ctrl/Cmd+B/I/E, Ctrl/Cmd+Shift+S/X/H → `RawStyleToggle` 호출 |
| `OverlayScrollForwarder.kt` | 오버레이 위 스크롤 제스처를 부모 `ScrollState`에 포워딩하는 `@Composable` Modifier 유틸. `Modifier.scrollable` + `dispatchRawDelta` 방식 |

### state/

| 파일 | 역할 |
|---|---|
| `MarkdownBlock.kt` | 블록 타입 sealed class (`Heading`, `TextBlock`, `HorizontalRule`, `CodeBlock`, `Table`, `Embed`). `InlineStyleScanner.computeSpans()` 타입 분기에 사용 |
| `MarkdownEditorState.kt` | `TextFieldState`(raw text) 홀더. `setMarkdown()`으로 파일 전환 시 초기화 |
| `MarkdownPatternScanner.kt` | 문서 전체 raw 텍스트를 줄 단위 스캔 → `ScanResult(spans, blocks)` 반환. `BlockType` enum, `BlockRange`, `ScanResult` 데이터 클래스도 이 파일에 정의 |
| `InlineStyleScanner.kt` | 블록 타입별 SpanStyle 범위 계산. MARKER(0.01sp 투명) + 내용 서식. `calloutSpans()`, `scanInline()`, `hideLinePrefix()` 포함 |
| `RawMarkdownOutputTransformation.kt` | `OutputTransformation` 구현. 활성 줄/블록 판별 → 비활성 줄에 인라인 서식 적용, 비활성 오버레이 블록에 `blockTransparent` 적용. 테이블 구분자 줄은 `marker`(0.01sp)로 높이 축소. `blockRanges`/`activeBlockRanges` 프로퍼티를 외부(DrawBehind, 오버레이)에 노출 |
| `EditorInputTransformation.kt` | `InputTransformation` 구현. Smart Enter(블록 prefix 자동 continuation) + Auto-close(`[[`, `**`, `` ` `` 등) + Tab→space |
| `RawStyleToggle.kt` | raw text 서식 토글 유틸리티. `toggleInlineStyle(state, marker)`, `toggleHeading(state, level)`, `toggleBlockPrefix(state, prefix)`. 키보드 단축키 및 향후 툴바 공용 |
| `OverlayBlockParser.kt` | `BlockRange` + raw text → `OverlayBlockData` 경량 파서. Callout/CodeBlock/Table raw 텍스트에서 제목/내용/셀 등을 추출. `OverlayBlockData` sealed class(CalloutData, TableData, CodeBlockData)도 이 파일에 정의 |

### ui/

| 파일 | 역할 |
|---|---|
| `MarkdownBasicTextField.kt` | Material3 미의존 BasicTextField 래퍼. `Box(clipToBounds)` 내 BasicTextField + DrawBehind(블록 데코레이션) + 오버레이 레이어 조립. `scrollState`, `onTextLayout` 관리 |
| `MarkdownTextField.kt` | Material3 래퍼. `MarkdownBasicTextField`를 호출하며 Material3 테마(색상, 타이포그래피, Callout 스타일)를 자동 적용 |
| `BlockDecorationDrawer.kt` | `DrawScope` 확장 함수. 활성 블록(raw 편집 중)의 배경/테두리 데코레이션. CodeBlock: 라운드 배경, Callout: 배경 + 왼쪽 테두리, Embed: 반투명 배경. 활성 Callout/Embed는 배경 숨김. `scrollOffset` 보정 |
| `OverlayPositionCalculator.kt` | `TextLayoutResult` + `scrollOffset` → 뷰포트 좌표 `Rect` 변환. 뷰포트 밖 블록 컬링(`isVisible`) |

### ui/block/

| 파일 | 역할 |
|---|---|
| `BlockOverlay.kt` | 블록 타입별 오버레이 라우팅. `OverlayBlockData` 타입에 따라 CalloutOverlay/TableOverlay 분기. 뷰포트 좌표로 절대 위치 배치(`Modifier.offset`) |
| `CalloutOverlay.kt` | Callout 블록 오버레이. 배경 + 왼쪽 테두리 + 제목/내용 각각 BasicTextField. 양방향 raw 동기화(300ms 디바운스). 내용에 `InlineOnlyOutputTransformation` 적용 |
| `TableOverlay.kt` | Table 블록 오버레이. Column + Row 그리드 + 셀별 BasicTextField. raw 동기화 시 구분자 줄(`|---|`) 자동 재생성 |
| `CodeBlockOverlay.kt` | CodeBlock 블록 오버레이. 모노스페이스 배경 + 코드 BasicTextField. 펜스(` ``` `) 포함 전체 블록 재구성 |
| `InlineOnlyOutputTransformation.kt` | 오버레이 내부 TextField용 `OutputTransformation`. 인라인 서식만 적용(블록 투명 없음). 포커스 기반 활성 줄 감지 + 스캔 캐시 |

---

## 4. 핵심 데이터 흐름

```
[파일 로딩]
  .md 파일 (raw 마크다운)
      ↓ MarkdownEditorState.setMarkdown(markdown)
  TextFieldState ← raw text 그대로 주입

[사용자 입력]
  키 입력
      ↓ onPreviewKeyEvent → EditorKeyboardShortcuts (service/util)
        - Ctrl+B/I/E, Ctrl+Shift+S/X/H → RawStyleToggle (state)
      ↓ EditorInputTransformation (state)
        - Smart Enter: 블록 prefix 자동 continuation
        - auto-close: [[ → [[|]], ** → **|** 등
        - Tab → 스페이스 2칸
      ↓ TextFieldState에 반영 (raw text)

[화면 표시 — 인라인 서식]
  BasicTextField 렌더링 시
      ↓ RawMarkdownOutputTransformation.transformOutput() (state)
        1. text != cachedText → MarkdownPatternScanner.scan(text) 재실행
           → ScanResult(spans, blocks) 반환
        2. selection 으로 활성 블록/줄 판별
        3. 비활성 인라인: addStyle(서식 SpanStyle)
        4. 비활성 오버레이 블록: addStyle(blockTransparent)
           - 테이블 구분자 줄만 marker(0.01sp)로 높이 축소
        5. blockRanges / activeBlockRanges 프로퍼티 노출

[화면 표시 — DrawBehind]
  BlockDecorationDrawer (ui)
      ↓ 활성 CodeBlock: 라운드 배경
      ↓ 활성 Callout/Embed: 배경 숨김 (raw 텍스트만 표시)
      ↓ 비활성 오버레이 블록: 오버레이가 데코레이션 담당 → 스킵

[화면 표시 — 블록 오버레이]
  MarkdownBasicTextField (ui)
      ↓ OverlayPositionCalculator.compute() → 뷰포트 좌표
      ↓ OverlayBlockParser.parse() → OverlayBlockData
      ↓ BlockOverlay → CalloutOverlay / TableOverlay / CodeBlockOverlay (ui/block)
      ↓ 오버레이 내 TextField로 직접 편집 → raw markdown 동기화
      ↓ 오버레이 위 스크롤 → OverlayScrollForwarder → 부모 scrollState

[저장]
  textFieldState.text.toString()
      ↓ EditorPage 의 pendingMarkdown (500ms debounce)
      ↓ viewModel.updateBody()
      ↓ NoteFile.body에 저장
```

---

## 5. 주요 설계 결정

### Raw Text 방식 선택 이유

v2(clean text)의 한계:
1. **Active line 불가**: 기호를 제거한 상태이므로 커서 줄에 기호를 다시 표시할 수 없음
2. **IME 리셋**: 기호 제거 시 `buffer.replace()`로 텍스트가 바뀌면 한국어 IME 조합 상태가 리셋
3. **직렬화 오버헤드**: 매 저장마다 clean text + spans → markdown 재조립 필요

Raw text 방식의 장점:
1. **자연스러운 active line**: 기호가 항상 있으므로 투명만 해제하면 됨
2. **IME 안전**: 기호를 건드리지 않으므로 조합 상태 유지
3. **저장 단순화**: raw text 그대로 저장, 직렬화 불필요

### 투명 텍스트 + 오버레이 방식 선택 이유

검토한 대안:
1. **InlineContent + Placeholder**: TextFieldState 기반 BasicTextField에서 지원되지 않음
2. **블록 단위 LazyColumn 분할**: 블록 간 커서 이동/선택 복잡
3. **DrawBehind만 사용**: Canvas 그리기만 가능, 대화형 UI(TextField) 불가
4. **✅ 투명 텍스트 + 오버레이**: raw text가 줄 높이를 보존하고, 그 위에 자유로운 Composable 배치

장점:
- 모든 raw markdown이 단일 TextFieldState에 존재 → 저장/Undo/선택이 자연스러움
- 블록 패턴이 깨지면 오버레이 자동 제거 → 삭제 로직 불필요
- 오버레이 내 TextField로 직접 편집 가능 → raw 전환 없이 WYSIWYG 경험

### 오버레이 ↔ raw markdown 동기화

오버레이 → raw:
1. 변경된 내용을 raw markdown 형식으로 재구성
2. `TextFieldState.edit { replace(blockStart, blockEnd, newRawText) }`
3. Scanner 재실행 → ScanResult 갱신 → 오버레이 자동 재구성

raw → 오버레이:
1. Scanner가 블록 감지 → BlockRange 생성
2. OverlayBlockParser가 raw text 파싱 → OverlayBlockData 생성
3. 오버레이 Composable에 파싱된 데이터 전달

### Embed 별도 블록 분리 이유

Embed(`![[파일명]]`)의 raw text는 1줄이지만 렌더링된 콘텐츠는 크기를 예측할 수 없다.
투명 텍스트 방식은 raw text의 줄 높이 = 오버레이 높이를 전제하므로 Embed에는 적용 불가.
EditorPage에서 Embed 위치 기준으로 문서를 세그먼트로 분할하는 방식이 필요하다 (미구현).

---

## 6. 지원 문법

### 인라인 서식 (SpanStyle 기반)

| 문법 | 구현 상태 |
|---|---|
| Bold `**` | ✅ |
| Italic `*` | ✅ |
| BoldItalic `***` | ✅ |
| Strikethrough `~~` | ✅ |
| Highlight `==` | ✅ |
| InlineCode `` ` `` | ✅ |
| WikiLink `[[파일명\|별칭]]` | ✅ |
| ExternalLink `[텍스트](URL)` | ✅ |
| EmbedLink `![[파일명]]` | ✅ |

### 블록 서식

| 문법 | 렌더링 방식 | 구현 상태 |
|---|---|---|
| Heading `#` ~ `######` | SpanStyle (fontSize + bold) | ✅ |
| BulletList `-` / `*` | SpanStyle (prefix 연한 색 표시) | ✅ |
| OrderedList `숫자.` | SpanStyle (prefix 연한 색 + Monospace) | ✅ |
| Blockquote `>` | marker 숨김 + DrawBehind 왼쪽 테두리 (depth별 다중) | ✅ |
| Blockquote 중첩 `>>` | depth별 다중 테두리 + `>` marker 숨김 | ✅ |
| HorizontalRule `---` | marker 숨김 + DrawBehind 구분선 | ✅ |
| Callout `> [!type]` | 오버레이 (배경 + 테두리 + TextField) | ✅ |
| Callout 중첩 `>> [!type]` | 외부 Callout body 내 depth 테두리 + `>` prefix 숨김 | ✅ |
| CodeBlock ` ``` ` | 오버레이 (펜스 줄 축소 + 코드 TextField) | ✅ |
| Table `\|` | 오버레이 (그리드 + 셀별 TextField + 구분자 축소) | ✅ |
| Embed `![[파일]]` | 별도 블록 (미구현) | 📌 |

### 편집 기능

| 기능 | 구현 상태 |
|---|---|
| Auto-close (`[[`, `**`, `` ` ``, `~~`, `==`) | ✅ |
| Tab → 스페이스 2칸 | ✅ |
| Smart Enter (리스트/인용구/체크박스 continuation) | ✅ |
| 서식 토글 (인라인/헤딩/블록 prefix) | ✅ |
| 키보드 단축키 (Ctrl+B/I/E, Ctrl+Shift+S/X/H) | ✅ |
| 오버레이 위 스크롤 포워딩 | ✅ |
