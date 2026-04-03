# Callout 구현 문서

Callout 블록(`> [!TYPE] 제목`)의 전체 구현을 설명한다.

---

## 1. 지원 타입

| 타입 | 기본 배경색 | 기본 테두리색 |
|---|---|---|
| `NOTE` | `#1A1565C0` | `#FF1565C0` (파랑) |
| `TIP` | `#1A00897B` | `#FF00897B` (청록) |
| `IMPORTANT` | `#1A6A1B9A` | `#FF6A1B9A` (보라) |
| `WARNING` | `#1AE65100` | `#FFE65100` (주황) |
| `DANGER` | `#1AC62828` | `#FFC62828` (빨강) |
| `CAUTION` | `#1AC62828` | `#FFC62828` (빨강) |
| `QUESTION` | `#1A4527A0` | `#FF4527A0` (남색) |
| `SUCCESS` | `#1A2E7D32` | `#FF2E7D32` (초록) |

미등록 타입은 `NOTE` 스타일로 fallback.
Material3 래퍼(`MarkdownTextField`)에서는 `MaterialTheme.colorScheme` 기반으로 재매핑됨.

### 스타일 설정 위치

- **기본 스타일**: `service/MarkdownStyleConfig.kt` → `defaultCalloutStyles()`
- **Material3 스타일**: `ui/MarkdownTextField.kt` → `defaultMaterialStyleConfig()` 내 `calloutStyles`
- **런타임 조회**: `MarkdownStyleConfig.calloutDecorationStyle(type)` — `type.uppercase()` 후 Map 검색

---

## 2. 마크다운 문법

```markdown
> [!NOTE] 제목 텍스트
> 본문 줄 1
> 본문 줄 2

> [!WARNING]
> 제목 없이 본문만

>[!TIP] 공백 없이도 가능
>본문

>> [!NOTE] 중첩 Callout (depth 2)
>> 본문
```

### 파싱 규칙

- 헤더: `>+ ?[!\w+]\s*(.*)` — `>` 1개 이상 + 선택적 공백 + `[!TYPE]` + 선택적 제목
- 본문: `>` 로 시작하는 후속 줄 (같은 depth 이하의 새 Callout 헤더가 나오면 중단)
- **body 없는 Callout** (헤더만): 인라인 스타일만 적용, 오버레이 미생성 (`calloutLines.size >= 2` 조건)
- **중첩**: depth가 더 깊은 (`>` 개수가 더 많은) Callout 헤더는 본문으로 포함됨

---

## 3. 처리 흐름

### 3.1 스캔 — `MarkdownPatternScanner.scan()`

**파일**: `state/MarkdownPatternScanner.kt`

```
Raw text → 줄 단위 순회
  ↓
calloutHeaderRegex = "^(>+) ?\\[!(\\w+)]\\s*"
  ↓ 매칭 시
depth = groupValues[1].length    (> 개수)
type  = groupValues[2]           (NOTE, TIP 등)
  ↓
후속 ">" 줄 소비 (같은/상위 depth Callout 헤더에서 중단)
  ↓
결과:
  spans → InlineStyleScanner.calloutSpans() 호출
  blocks → BlockRange(CALLOUT, textRange, meta = {"calloutType": type})
           단, calloutLines.size >= 2 일 때만
```

### 3.2 인라인 스타일 — `InlineStyleScanner.calloutSpans()`

**파일**: `state/InlineStyleScanner.kt`

| 줄 | 처리 |
|---|---|
| 첫 줄 `> [!TYPE] 제목` | `> [!TYPE] ` → `marker` (0.01sp, 투명), `제목` → `bold` + 인라인 스캔 |
| 나머지 `> 본문` | `> ` → `blockTransparent` (색만 투명, 높이 유지), 본문 → 인라인 스캔 |

`hideLinePrefix()`가 `>` prefix를 처리:
- Blockquote/Callout의 `>` 문자 → `blockTransparent` (색 투명, fontSize 유지 → DrawBehind 테두리와 간격 보존)
- 공백은 그대로 유지

### 3.3 OutputTransformation — `RawMarkdownOutputTransformation`

**파일**: `state/RawMarkdownOutputTransformation.kt`

```
transformOutput() 실행
  ↓
활성 블록 판별: 커서/선택 범위와 교차하는 블록
  ↓
비활성 CALLOUT 블록:
  1. 인라인 스팬 스킵 (오버레이가 렌더링 담당)
  2. blockTransparent 적용 (색만 투명, 줄 높이 유지)
  → 투명 텍스트 위에 CalloutOverlay 배치
  ↓
활성 CALLOUT 블록:
  1. 인라인 스팬 적용 (marker, bold 등)
  2. rawZones 내 줄은 스팬 미적용 (raw 편집)
  3. BlockDecorationDrawer에서 활성 Callout 배경 스킵
```

### 3.4 오버레이 파싱 — `OverlayBlockParser.parseCallout()`

**파일**: `state/OverlayBlockParser.kt`

```
Raw text: "> [!NOTE] 제목\n> 본문1\n> 본문2"
  ↓
calloutHeaderRegex = "^> ?\\[!(\\w+)]\\s*(.*)"
  type  = "NOTE"
  title = "제목"
  ↓
본문 줄에서 ">" prefix 제거:
  "> 본문1" → "본문1"
  ">본문1"  → "본문1"
  ↓
CalloutData(calloutType, title, bodyLines)
```

### 3.5 오버레이 렌더링 — `CalloutOverlay`

**파일**: `ui/block/CalloutOverlay.kt`

```
Column(배경 + 왼쪽 테두리) {
  BasicTextField(title, SingleLine, Bold)
  MarkdownBasicTextFieldCore(body, 재귀 렌더링, depth+1)
}
```

### 3.6 DrawBehind 데코레이션 — `BlockDecorationDrawer`

**파일**: `ui/BlockDecorationDrawer.kt`

비활성 Callout → 오버레이가 배경 담당 (DrawBehind 스킵)
활성 Callout → 배경 스킵 (raw 텍스트만 표시)

---

## 4. 오버레이 동기화

### 4.1 오버레이 → Raw (편집 중)

```
사용자가 title/body 편집
  ↓ snapshotFlow + debounce 300ms
syncCalloutToRaw():
  header = "> [!TYPE] " + title
  bodyLines = body.lines().map {
    if (startsWith(">")) ">$it"  // 중첩 prefix 간결 유지
    else "> $it"
  }
  newRaw = header + "\n" + bodyLines
  ↓
textFieldState.edit { replace(blockStart, blockEnd, newRaw) }
```

### 4.2 Raw → 오버레이 (포커스 없을 때)

```
부모 raw text 변경 → 새 data 계산
  ↓ LaunchedEffect(data.title/bodyLines, isCalloutFocused)
포커스 없으면: titleState / bodyEditorState 업데이트
```

### 4.3 포커스 이탈 시 즉시 동기화

```
isCalloutFocused: true → false
  ↓ LaunchedEffect(isCalloutFocused)
syncCalloutToRaw() 즉시 호출 (debounce 대기 중인 변경도 포함)
```

---

## 5. 키보드 내비게이션

| 위치 | 키 | 동작 |
|---|---|---|
| Title | `↓` / `Enter` | Body 시작으로 포커스 이동 |
| Body 첫 줄 | `↑` | Title 끝으로 포커스 이동 |
| Body position 0 | `Backspace` | 부모 에디터로 포커스 이동 (raw 표시) |
| Title/Body | Long Press | 부모 에디터로 포커스 이동 (raw 표시) |

### Backspace/LongPress → Raw 전환 메커니즘

`onRequestActivation` 콜백:
1. 부모 `MarkdownBasicTextFieldCore`의 `FocusRequester`로 포커스 요청
2. 부모 `TextFieldState`의 selection을 Callout 블록 시작으로 설정
3. 부모에 포커스 → `isFocused=true` → 블록 활성화 → 오버레이 제거

`activating` 플래그:
- `true` 설정 후 모든 sync LaunchedEffect 차단
- 포커스 이탈 sync가 Callout을 다시 쓰는 것 방지

---

## 6. 스크롤 포워딩

```
overlayScrollForwarder(parentScrollState):
  Modifier.scrollable → dispatchRawDelta(-delta)
```

- 비활성(보기 모드): 스크롤 제스처를 부모 ScrollState에 포워딩
- 활성(편집 중): 포워딩 비활성화 (body 높이 변경 시 부모 스크롤 점프 방지)
- 중첩 오버레이: `parentScrollState` 파라미터로 루트 ScrollState 전달 → 모든 depth에서 루트까지 포워딩

---

## 7. 재귀적 오버레이 (중첩 Callout)

```
MarkdownBasicTextFieldCore(depth=0)
  └─ CalloutOverlay
       └─ MarkdownBasicTextFieldCore(depth=1)  ← body 재귀 렌더링
            └─ CalloutOverlay (중첩)
                 └─ MarkdownBasicTextFieldCore(depth=2)
                      └─ (depth >= MAX_OVERLAY_DEPTH(3) → 오버레이 미생성, 인라인만)
```

### State 관리 전략

- `remember`(키 없음): sync로 인한 textRange 변경 시 state 재생성 방지
- `key(textRange.first)`: 부모 overlay 루프에서 블록 identity 관리
- `rememberUpdatedState(data)`: sync LaunchedEffect에서 항상 최신 오프셋 참조
- `LaunchedEffect(Unit)`: 한 번만 시작, state 재생성 없이 계속 동작

### Body → Raw prefix 규칙

중첩 `>` prefix는 간결하게 유지:
```kotlin
if (line.startsWith(">")) ">$line" else "> $line"
// ">[!note] title" → ">>[!note] title"  (공백 없음)
// "plain text"     → "> plain text"
```

---

## 8. Smart Enter (Blockquote 연속 입력)

**파일**: `state/EditorInputTransformation.kt`

```
BLOCKQUOTE_REGEX = """(?:> ?)+"""
```

- `> ` 또는 `>` 패턴을 1회 이상 반복 매칭 (다중 depth 지원)
- depth 계산: `prefix.count { it == '>' }`
- 연속 입력 형식: `">".repeat(depth) + " "` (예: `>> `, `>>> `)
- prefix만 있는 줄(내용 없음)에서 Enter → prefix 제거, 빈 줄로 변환

---

## 9. 관련 파일 요약

| 파일 | 역할 |
|---|---|
| `state/MarkdownPatternScanner.kt` | Callout 블록 감지, depth/type 추출, BlockRange 등록 |
| `state/InlineStyleScanner.kt` | 헤더 마커 숨김, 제목 bold, 본문 prefix 숨김, 인라인 스캔 |
| `state/OverlayBlockParser.kt` | Raw text → CalloutData 파싱 (type, title, bodyLines) |
| `state/RawMarkdownOutputTransformation.kt` | 비활성 Callout에 blockTransparent, 활성 판별 |
| `state/EditorInputTransformation.kt` | Smart Enter blockquote 연속 입력 |
| `service/MarkdownStyleConfig.kt` | CalloutDecorationStyle, 타입별 색상 맵 |
| `ui/block/CalloutOverlay.kt` | 오버레이 Composable, 양방향 sync, 키보드 내비게이션 |
| `ui/block/BlockOverlay.kt` | 블록 타입별 오버레이 라우팅 |
| `ui/BlockDecorationDrawer.kt` | 활성 Callout 배경/테두리 DrawBehind |
| `ui/OverlayPositionCalculator.kt` | TextLayoutResult → 뷰포트 Rect 변환 |
| `ui/MarkdownBasicTextField.kt` | 오버레이 루프, FocusRequester, onRequestActivation 콜백 |
