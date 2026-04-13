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
| Phase 3 | 특수 블록 오버레이 (Callout, Table) | ✅ 완료 |
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
| — | CodeBlock raw 표시 (오버레이 제거, 펜스 포함 raw 마크다운) | ✅ 완료 |
| — | Blockquote/Callout 중첩 depth 지원 (`>>`, `>>>`) | ✅ 완료 |
| — | Blockquote depth별 다중 왼쪽 테두리 (DrawBehind) | ✅ 완료 |
| — | Callout 연속 작성 (같은 depth Callout 헤더 → 블록 분리) | ✅ 완료 |
| — | Blockquote `>` 투명 처리 (정상 크기 유지 → 테두리와 자연 간격) | ✅ 완료 |
| — | Callout body 내 full scanner (중첩 callout 헤더 서식 적용) | ✅ 완료 |
| — | Callout RoundBox 디자인 (왼쪽 stroke → 라운드 테두리) | ✅ 완료 |
| — | Callout Title 아이콘 (타입별 Material Icon, 18×18) | ✅ 완료 |
| — | Callout body fontSize 0.9em (title 기준, 중첩 시 누적) | ✅ 완료 |
| — | Inline Code RoundRect 배경 (DrawBehind, 4dp cornerRadius) | ✅ 완료 |
| — | LineHeightStyle 정규화 (Bold/Italic 줄 높이 통일) | ✅ 완료 |
| — | 오버레이 onRequestActivation 콜백 (FocusRequester 기반) | ✅ 완료 |
| — | 중첩 오버레이 스크롤 포워딩 (parentScrollState → 루트) | ✅ 완료 |

### 미구현 / 진행 중 기능

| 기능 | 비고 |
|---|---|
| **재귀적 오버레이 구조** | Callout body에 `MarkdownBasicTextFieldCore` 사용하여 중첩 오버레이 지원. **구현 완료 — 테스트 중** (아래 상세 참고) |
| Undo/Redo | raw text 기반 재설계 필요 |
| 툴바 UI | `RawStyleToggle` 로직 완료, UI만 추가 필요 |
| Embed 오버레이 | 콘텐츠 크기 예측 불가 → LazyColumn 별도 블록 방식 필요 |
| 클립보드 직렬화 | raw text 복사로 대체 가능 |

### 재귀적 오버레이 구조 (구현 완료 — 테스트 중)

Callout body에서 중첩 Callout/Table을 별도 오버레이 Composable로 렌더링하는 구조.
**Callout에만 적용** — Table 내부에서는 재귀하지 않음. CodeBlock은 오버레이 없이 raw 표시.

```
MarkdownBasicTextField → MarkdownBasicTextFieldCore(depth=0)
  └─ CalloutOverlay (RoundBox + Icon + Title/Body)
       └─ MarkdownBasicTextFieldCore(depth=1, fontSize=0.9em)  ← body 재귀 렌더링
            └─ CalloutOverlay (중첩, fontSize 누적 축소)
                 └─ MarkdownBasicTextFieldCore(depth=2, fontSize=0.81em)
                      └─ (depth >= MAX_OVERLAY_DEPTH(3) → 오버레이 생성 중단, 인라인 서식만)
```

**구현 상태:**
- `MarkdownBasicTextField` → `MarkdownBasicTextFieldCore` internal composable 분리 완료
- `MarkdownBasicTextFieldCore`: `overlayDepth` 파라미터, depth≥3 오버레이 스킵, `parentScrollState` 파라미터
- 부모 overlay 루프에 `key(block.blockRange.textRange.first)` 적용 → 블록 identity 관리
- `BlockOverlay` → `CalloutOverlay`에 `overlayDepth`, `onRequestActivation` 전달
- `CalloutOverlay`: RoundBox 디자인 + 타입별 아이콘(18×18) + body 0.9em fontSize
- `CalloutOverlay`: body에 `MarkdownBasicTextFieldCore(depth+1)` 사용, `MarkdownEditorState`로 body 관리
- `CalloutOverlay`: Title ↔ Body 방향키/Enter 내비게이션 (`FocusRequester`)
- `RawMarkdownOutputTransformation`: `isFocused` 프로퍼티, `inlineCodeRanges` 노출
- `BlockDecorationDrawer`: `isNested` 파라미터, inline code RoundRect 배경, Callout RoundBox 테두리
- `normalizedTextStyle`: `LineHeightStyle` 적용 (Bold/Italic 줄 높이 통일)
- depth=0 `fillMaxSize()`, depth>0 `fillMaxWidth()` (무한 확장 방지)
- 포커스 이탈 시 즉시 raw 동기화 (`LaunchedEffect(isCalloutFocused)`)
- `onRequestActivation` 콜백: 부모 FocusRequester + 커서 이동 (Callout/Table 공통)

**CalloutOverlay state 관리 전략 (Phase 5 핵심 설계):**

기존 문제: `remember(data.blockRange.textRange)`로 titleState/bodyEditorState를 키잉하면,
overlay→raw sync가 부모 raw text를 변경 → textRange 변경 → state 재생성 → 사용자 입력 소실.
특히 body에서 `>[!note] 제목` + Enter 시 Smart Enter 적용 후 sync가 textRange를 변경하여
새 state가 생성되면서 Enter로 추가된 줄이 사라지는 문제 발생.

해결:
1. `remember` 키 없이 state 생성 → sync로 인한 textRange 변경 시 state 재생성 방지
2. 부모 overlay 루프에서 `key(block.blockRange.type, index)` → 안정적인 블록 identity 관리
   - `textRange.first`를 key로 사용하면 블록 위에서 타이핑 시 매번 오프셋 변경 → 오버레이 파괴/재생성 → 깜빡임
   - 블록 타입 + 순서 인덱스는 내용이 같으면 안정 유지 → recomposition만 발생
3. `rememberUpdatedState(data)` → sync LaunchedEffect 내에서 항상 최신 data.blockRange 참조
4. LaunchedEffect 키: `Unit` (한 번만 시작, 재시작 없음)
5. raw→overlay sync: `LaunchedEffect(data.bodyLines, isCalloutFocused)` — 포커스 없을 때만 적용

**오버레이 깜빡임 방지 (stale TextLayoutResult 대응):**
- 텍스트 변경 후 TextLayoutResult는 한 프레임 뒤에 갱신됨
- `layout.layoutInput.text.length != rawText.length`이면 stale layout으로 판단
- `lastValidOverlayBlocks`(이전 유효 데이터)를 반환하여 오버레이가 사라졌다 나타나는 것을 방지
- 다음 프레임에서 새 TextLayoutResult 도착 시 정확한 위치로 갱신

**스크롤 포워딩 전략:**
- `overlayScrollForwarder`는 callout이 비활성(보기 모드)일 때만 적용
- 편집 중(`isCalloutFocused=true`)에는 비활성화 → body 높이 변경 시 부모 스크롤 점프 방지
- `parentScrollState` 파라미터: 중첩 오버레이가 루트 ScrollState에 포워딩
  - depth=0: `overlayForwardScrollState = scrollState` (루트)
  - depth>0: `overlayForwardScrollState = parentScrollState` (부모에서 전달받은 루트)
  - BasicTextField 자체는 항상 자신의 `scrollState` 사용 (루트 바인딩 시 커서 자동스크롤 부작용)

**알려진 이슈 / 주의사항:**
- 오버레이 초기화 1프레임 지연: `textLayoutResult` 확보 전 blockTransparent 적용 → 잠깐 투명 텍스트(오버레이 없음). 최상위 에디터도 동일 현상이며 보통 눈에 띄지 않음
- `isFocused` 기본값 `false` 필수: body 생성 시 커서가 텍스트 끝에 위치하므로, `isFocused=true`이면 마지막 줄이 raw로 보임
- `overlaysAvailable` SideEffect 방식은 사용 불가: OutputTransformation 재실행을 트리거하지 못함. 필요시 생성자에서 고정 설정
- 포커스 전환 순서: 중첩 Callout에서 메인 에디터로 포커스 이동 시, depth-2 → depth-1 순으로 sync 필요. 현재 `LaunchedEffect` 실행 순서에 의존
- `forceAllOverlaysInactive` 프로퍼티: 선언은 남아있으나 미사용. `isFocused` 기반 제어로 대체됨. 정리 가능

### 해결된 이슈 기록

| 이슈 | 원인 | 해결 |
|---|---|---|
| body에서 `>[!note]` + Enter → 텍스트 소실 | `remember(textRange)` 키로 sync 후 state 재생성 | remember 키 제거 + `key(textRange.first)` + `rememberUpdatedState` |
| body에서 Enter → 부모 스크롤 점프 | `overlayScrollForwarder`가 편집 중에도 활성화 | 포커스 시 scroll forwarder 비활성화 |
| body 맨 앞 Backspace → callout 해제 | 미구현이었음 | `onPreviewKeyEvent`로 Backspace 감지 → 부모 커서 이동 |
| depth≥MAX에서 텍스트 투명 | `applyBlockTransparent=true`인데 overlay 미생성 | `applyBlockTransparent = overlayDepth < MAX_OVERLAY_DEPTH` |
| depth>0에서 무한 확장 | `fillMaxSize()` in Column | depth>0은 `fillMaxWidth()` |
| `isFocused=true` 기본값 → 마지막 줄 raw | body 생성 시 커서 위치가 텍스트 끝 | `isFocused` 기본값 `false` |
| Smart Enter 단일 `>` depth만 지원 | `BLOCKQUOTE_REGEX = "> "` | `(?:> ?)+` 로 다중 depth 지원 |
| `> > >` 공백 prefix | `syncCalloutToRaw`에서 `"> $line"` | `if (line.startsWith(">")) ">$line" else "> $line"` |
| 1프레임 타이밍 갭 | OutputTransformation(layout) vs overlay(composition) | 아키텍처 한계 — 문서화만 |
| LongPress → raw 전환 미작동 | 부모 포커스 없이 cursor만 이동 | `onRequestActivation` 콜백 + FocusRequester |
| 중첩 오버레이 스크롤 안 됨 | depth>0 scrollState가 빈 상태 | `parentScrollState` 파라미터로 루트 전달 |
| Bold/Italic 줄 높이 불균일 | 폰트 메트릭 차이 | `LineHeightStyle(Proportional, Trim.Both)` |
| Inline Code 직사각형 배경 | SpanStyle.background는 rect만 지원 | DrawBehind `drawRoundRect` + `inlineCodeRanges` |

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
        └── InlineOnlyOutputTransformation.kt
```

### 디렉토리 구분 기준

| 디렉토리 | 역할 | 포함 기준 |
|---|---|---|
| `service/` | 설정 데이터, 공용 유틸리티 | 여러 레이어에서 참조하는 설정/도구. 특정 레이어에 종속되지 않음 |
| `service/util/` | 이벤트 핸들러, Modifier 유틸 | 키보드/스크롤 이벤트를 처리하여 state 레이어에 위임하는 중간 계층 |
| `state/` | 상태 관리, 텍스트 스캔/변환 | TextFieldState, InputTransformation, OutputTransformation, 스캐너, 파서 등 비즈니스 로직 |
| `ui/` | Composable 함수, 캔버스 드로잉 | 화면에 직접 그리는 컴포넌트. state에 의존 |
| `ui/block/` | 블록 오버레이 Composable | 특수 블록(Callout, Table)의 오버레이 UI. 내부 TextField + raw 동기화 |

---

## 3. 파일별 역할

### service/

| 파일 | 역할 |
|---|---|
| `MarkdownStyleConfig.kt` | 마크다운 서식 SpanStyle 설정. `CalloutDecorationStyle`, `blockTransparent`, `codeInlineBackground` 등. 모든 레이어에서 참조 |

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
| `RawMarkdownOutputTransformation.kt` | `OutputTransformation` 구현. 활성 줄/블록 판별 → 비활성 줄에 인라인 서식 적용, 비활성 오버레이 블록에 `blockTransparent` 적용. 테이블 구분자 줄은 `marker`(0.01sp)로 높이 축소. `blockRanges`/`activeBlockRanges`/`inlineCodeRanges` 프로퍼티를 외부에 노출 |
| `EditorInputTransformation.kt` | `InputTransformation` 구현. Smart Enter(블록 prefix 자동 continuation) + Auto-close(`[[`, `**`, `` ` `` 등) + Tab→space |
| `RawStyleToggle.kt` | raw text 서식 토글 유틸리티. `toggleInlineStyle(state, marker)`, `toggleHeading(state, level)`, `toggleBlockPrefix(state, prefix)`. 키보드 단축키 및 향후 툴바 공용 |
| `OverlayBlockParser.kt` | `BlockRange` + raw text → `OverlayBlockData` 경량 파서. Callout/Table raw 텍스트에서 제목/내용/셀 등을 추출. `OverlayBlockData` sealed class(CalloutData, TableData, CodeBlockData)도 이 파일에 정의 |

### ui/

| 파일 | 역할 |
|---|---|
| `MarkdownBasicTextField.kt` | Material3 미의존 BasicTextField 래퍼. `Box(clipToBounds)` 내 BasicTextField + DrawBehind(블록 데코레이션) + 오버레이 레이어 조립. `scrollState`, `onTextLayout` 관리 |
| `MarkdownTextField.kt` | Material3 래퍼. `MarkdownBasicTextField`를 호출하며 Material3 테마(색상, 타이포그래피, Callout 스타일)를 자동 적용 |
| `BlockDecorationDrawer.kt` | `DrawScope` 확장 함수. Callout: RoundBox(배경 + 라운드 테두리), Inline Code: RoundRect 배경, Blockquote: depth별 왼쪽 테두리, HorizontalRule: 구분선. `inlineCodeRanges` 파라미터 |
| `OverlayPositionCalculator.kt` | `TextLayoutResult` + `scrollOffset` → 뷰포트 좌표 `Rect` 변환. 뷰포트 밖 블록 컬링(`isVisible`) |

### ui/block/

| 파일 | 역할 |
|---|---|
| `BlockOverlay.kt` | 블록 타입별 오버레이 라우팅. `OverlayBlockData` 타입에 따라 CalloutOverlay/TableOverlay 분기. `onRequestActivation` 콜백 전달 |
| `CalloutOverlay.kt` | Callout 블록 오버레이. RoundBox(배경 + 라운드 테두리) + 타입별 아이콘(18×18) + Title/Body. body는 `MarkdownBasicTextFieldCore(depth+1, fontSize=0.9em)`. 양방향 raw 동기화(300ms 디바운스, `rememberUpdatedState`). Title↔Body 방향키 내비게이션. `onRequestActivation`으로 raw 전환 |
| `TableOverlay.kt` | Table 블록 오버레이. Column + Row 그리드 + 셀별 BasicTextField. raw 동기화 시 구분자 줄(`\|---\|`) 자동 재생성. `onRequestActivation`으로 raw 전환 |
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
      ↓ Callout: RoundBox (배경 + 라운드 테두리)
      ↓ Blockquote: depth별 왼쪽 테두리
      ↓ HorizontalRule: 구분선
      ↓ Inline Code: RoundRect 배경 (inlineCodeRanges 기반, 4dp cornerRadius)
      ↓ 비활성 오버레이 블록: 오버레이가 데코레이션 담당 → 스킵

[화면 표시 — 블록 오버레이]
  MarkdownBasicTextField (ui)
      ↓ OverlayPositionCalculator.compute() → 뷰포트 좌표
      ↓ OverlayBlockParser.parse() → OverlayBlockData
      ↓ key(textRange.first) { BlockOverlay → CalloutOverlay / TableOverlay }
      ↓ 오버레이 내 TextField로 직접 편집 → raw markdown 동기화
      ↓ 오버레이 위 스크롤 → OverlayScrollForwarder → 루트 scrollState (parentScrollState 경유)

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
| Callout `> [!type]` | 오버레이 (RoundBox + 아이콘 + Title/Body) | ✅ |
| Callout 중첩 `>> [!type]` | 재귀 오버레이 (body 0.9em, depth별 축소) | ✅ |
| CodeBlock ` ``` ` | raw 마크다운 그대로 표시 (오버레이 없음) | ✅ |
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
