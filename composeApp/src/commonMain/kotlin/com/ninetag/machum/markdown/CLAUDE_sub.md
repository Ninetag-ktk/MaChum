# MarkdownTextField — 구현 참고 문서

CLAUDE.md의 설계를 기반으로 실제 구현 시 참고할 상세 내용을 정리한다.

---

## 핵심 컴포넌트

| 컴포넌트 | 파일 | 비고 |
|---|---|---|
| raw text 홀더 | `MarkdownEditorState` | TextFieldState 래퍼 |
| 문서 스캔 | `MarkdownPatternScanner` | 기호 범위만 반환 (BlockType, BlockRange, ScanResult 포함) |
| 출력 변환 | `RawMarkdownOutputTransformation` | 커서 줄 기반 조건부 적용 |
| BasicTextField 래퍼 | `MarkdownBasicTextField` | value/onValueChange 시그니처 |
| Material3 래퍼 | `MarkdownTextField` | Material3 테마 |
| 서식 토글 | `RawStyleToggle` | Phase 4 구현 완료 |
| Undo/Redo | (미구현) | raw text 기반 재설계 필요 |
| Smart Enter + 블록 토글 | `EditorInputTransformation` + `RawStyleToggle` | Phase 4 구현 완료 |
| 키보드 단축키 | `EditorKeyboardShortcuts` | Phase 4 구현 완료 |

---

## Phase 2 구현 완료 (현재 상태)

### 아키텍처

```
TextFieldState ← raw markdown text ("# 가나다\n**볼드**")
                  ↓
OutputTransformation (RawMarkdownOutputTransformation)
  ├─ 활성 줄 (커서): 변환 없음 → "# 가나다" 그대로 표시
  └─ 비활성 줄: "# " → 투명, "가나다" → H1 SpanStyle
                  ↓
저장: textFieldState.text.toString() → raw text 그대로
```

### 기호 숨김 방식

비활성 줄의 서식 기호에 적용하는 MARKER 스타일:

```kotlin
SpanStyle(fontSize = 0.01.sp, color = Color.Transparent)
```

- `fontSize = 0.01.sp` → 공간을 거의 차지하지 않음
- `Color.Transparent` → 보이지 않음
- 완전히 0으로 하지 않는 이유: 일부 렌더러에서 fontSize=0을 처리하지 못하거나 커서 hit-testing이 깨질 수 있음

### 커서 줄 감지

`OutputTransformation.transformOutput()` 내부에서 `selection.start`로 실시간 계산:

```kotlin
val cursorPos = selection.start
val cursorLineStart = text.lastIndexOf('\n', cursorPos - 1) + 1
val cursorLineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
```

커서가 이동할 때마다 `selection` 변경 → `transformOutput()` 재실행 → 즉각 반응.

### 스캐닝 캐시

`RawMarkdownOutputTransformation`은 내부에 `cachedText`/`cachedSpans`를 보관한다.
텍스트가 변경될 때만 `MarkdownPatternScanner.scan()`을 호출하고,
커서만 이동한 경우 캐시된 스팬 목록을 재사용한다.

### MarkdownPatternScanner 동작

문서 전체를 줄 단위로 스캔:

1. `` ``` `` 펜스 감지 → 코드 블록 추적
2. `#` + 공백 → 헤딩 레벨 감지
3. 나머지 → 일반 텍스트

각 블록 타입에 맞는 `MarkdownBlock` 인스턴스를 생성하여 `InlineStyleScanner.computeSpans()`에 위임.

### InlineStyleScanner 스타일 매핑

```
MARKER (투명)  → SpanStyle(fontSize = 0.01.sp, color = Color.Transparent)
BOLD           → SpanStyle(fontWeight = FontWeight.Bold)
ITALIC         → SpanStyle(fontStyle = FontStyle.Italic)
BOLD_ITALIC    → SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
STRIKE         → SpanStyle(textDecoration = TextDecoration.LineThrough)
HIGHLIGHT      → SpanStyle(background = Color(0xFFFFEB3B))
CODE_INLINE    → SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))
CODE_BLOCK     → SpanStyle(fontFamily = FontFamily.Monospace)
LINK           → SpanStyle(color = Color(0xFF1565C0))
H1             → SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
H2             → SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
H3~H6          → 점진적 축소
```

---

## EditorPage 연동

```kotlin
// EditorPage.kt — Material3 래퍼 사용
key(file.name) {
    MarkdownTextField(
        value = noteFile.body,          // raw 마크다운 텍스트
        onValueChange = { markdown ->
            pendingMarkdown.value = markdown  // raw text 그대로
        },
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    )
}
```

`onValueChange`는 `textFieldState.text.toString()` 결과를 받는다 (raw text).
디바운싱은 EditorPage에서 담당 (500ms).

---

## 특수 블록 렌더링 (Phase 3 — 오버레이 Composable 아키텍처) ✅ 구현 완료

SpanStyle만으로 표현할 수 없는 블록들. 단일 BasicTextField 위에 오버레이 Composable을 배치한다.
새 API(TextFieldState 기반)에서는 `InlineTextContent` 가 지원되지 않으므로, Box 레이아웃 내
절대 위치 오버레이 방식을 사용한다.

> Callout, Table 오버레이 구현 완료. CodeBlock은 raw 표시 (오버레이 제거). Embed 미구현.

### 핵심 구조

```
Box(clipToBounds) {
    BasicTextField(state = textFieldState)  ← 모든 raw markdown 포함
    // 비활성 블록에 오버레이 Composable 배치
    for (block in overlayBlocks) {
        BlockOverlay(
            data = block,                   ← 파싱된 블록 데이터
            textFieldState = ...,           ← raw markdown 동기화용
            modifier = Modifier.offset(block.viewportRect)
        )
    }
}
```

### 투명 텍스트로 줄 높이 보존

특수 블록의 raw text에 `blockTransparent` SpanStyle 적용:
```kotlin
val blockTransparent = SpanStyle(color = Color.Transparent)
// fontSize는 지정하지 않음 → 기본 폰트 크기 유지 → 줄 높이 보존
```

- 인라인 마커(`**`, `~~` 등): `marker` (fontSize=0.01sp) → 공간 제거
- 블록 전체(Callout, Table): `blockTransparent` → 색상만 투명, 줄 높이 유지
- 오버레이 Composable이 투명 텍스트 위에 정확히 겹쳐 배치됨

### 오버레이 내 직접 편집 + raw markdown 동기화

오버레이 내부에 별도 TextField를 배치하여 직접 편집한다 (raw 전환 아님).
편집 결과는 raw markdown 형식으로 재구성하여 `TextFieldState.edit { replace(...) }`로 동기화.

```
[오버레이 내 편집]
  사용자가 Callout 제목 수정
      ↓ onValueChange
      ↓ 변경된 제목 + 기존 내용을 raw markdown으로 재구성
      ↓ "> [!NOTE] 새 제목\n> 내용1\n> 내용2"
      ↓ TextFieldState.edit { replace(blockStart, blockEnd, newRawText) }
      ↓ Scanner 재실행 → ScanResult 갱신 → 오버레이 자동 갱신
```

raw 전환은 **블록을 걸쳐서 선택할 때만** 발생 (selection.min..selection.max가 블록 경계와 교차).

### 오버레이 위치 계산

`TextLayoutResult`의 `getLineTop()`/`getLineBottom()`으로 블록의 콘텐츠 좌표를 얻고,
`scrollState.value`를 빼서 뷰포트 좌표로 변환한다.

```kotlin
fun computeOverlayRect(layout: TextLayoutResult, range: IntRange, scrollOffset: Float): Rect? {
    val firstLine = layout.getLineForOffset(range.first)
    val lastLine = layout.getLineForOffset(range.last)
    val top = layout.getLineTop(firstLine) - scrollOffset
    val bottom = layout.getLineBottom(lastLine) - scrollOffset
    return Rect(0f, top, layout.size.width.toFloat(), bottom)
}
```

뷰포트 밖 블록은 오버레이를 생성하지 않음 (성능 최적화).

### 오버레이 깜빡임 방지

오버레이 위치/데이터 계산 시 두 가지 기법으로 깜빡임을 방지한다.

**1. 안정적인 key 사용**
오버레이 루프에서 `key(block.blockRange.type, index)` 사용.
`textRange.first`를 key로 사용하면, 블록 위에서 타이핑할 때마다 오프셋이 변경되어 오버레이가 파괴→재생성된다.
블록 타입 + 순서 인덱스는 내용이 같으면 안정적으로 유지되어 recomposition만 발생 (재생성 아님).

**2. stale TextLayoutResult 시 이전 데이터 유지**
텍스트 변경 후 `TextLayoutResult`는 한 프레임 뒤에 갱신된다.
`layout.layoutInput.text.length != rawText.length`이면 stale layout으로 판단하고,
`lastValidOverlayBlocks`(이전 유효 데이터)를 반환하여 오버레이가 사라졌다 나타나는 것을 방지한다.
다음 프레임에서 새 TextLayoutResult가 도착하면 정확한 위치로 갱신.

```kotlin
// MarkdownBasicTextFieldCore 내 overlayBlocks derivedStateOf
if (layout.layoutInput.text.length != rawText.length)
    return@derivedStateOf lastValidOverlayBlocks  // 이전 데이터 유지
```

---

### Callout `> [!type]` ✅ 구현 완료 (Phase 5 재귀 오버레이 + UI 개선)

RoundBox(배경 + 라운드 테두리) + 타입별 아이콘(18×18) + Title/Body.
body는 `MarkdownBasicTextFieldCore(depth+1, fontSize=0.9em)` → 중첩 Callout/Table 오버레이 지원.
상세 문서: **Callout.md** 참고.

오버레이 구성:
```kotlin
Column(background(roundedShape) + border(1.dp, accentColor, roundedShape)) {
    Row { Icon(calloutIcon, 18.dp) + BasicTextField(titleState, bold) }
    MarkdownBasicTextFieldCore(bodyEditorState, fontSize=0.9em, depth+1)
}
```

키보드 내비게이션:
- Title ↓/Enter → Body 시작, Body ↑(첫 줄) → Title 끝
- Body Backspace(position 0) / LongPress → `onRequestActivation`으로 raw 전환

fontSize 누적: depth 0 title=16sp, body=14.4sp → depth 1 title=14.4sp, body=12.96sp

---

### Embed `![[파일명]]` 📌 미구현

**LazyColumn 내 별도 블록으로 분리.** 콘텐츠 크기를 예측할 수 없으므로 투명 텍스트 방식 불가.

EditorPage에서 문서를 Embed 위치 기준으로 세그먼트 분할:
```
세그먼트 1: MarkdownTextField(일반 텍스트 + 오버레이 블록)
EmbedBlock:  FileManager 비동기 로딩 → 기존 EmbedRenderer 재활용
세그먼트 2: MarkdownTextField(나머지)
```

지원 형식:
```
![[파일명]]           전체 파일 임베드
![[파일명#헤딩]]      헤딩 단위
![[파일명^블록ID]]    블록ID 단위
```

읽기 전용. 원본 파일 편집은 파일 이동 후 별도 수행.

---

### CodeBlock ` ``` ` ✅ 오버레이 복원

라운드 배경 + monospace TextField. 펜스 줄은 marker(0.01sp)로 축소.
축소된 펜스 높이를 `resolveLineHeightDp(textStyle)` 만큼 상하 패딩으로 보정.
`CodeBlockOverlay.kt`에서 구현. LongPress → `onRequestActivation`으로 raw 전환.

---

### Table `|` ✅ 구현 완료

오버레이 Composable: 그리드 레이아웃 + 셀별 TextField. `TableOverlay.kt`에서 구현.

```
┌─ TableOverlay ─────────────────────────┐
│ [헤더1 TF] │ [헤더2 TF] │ [헤더3 TF]  │
│ [셀1 TF]   │ [셀2 TF]   │ [셀3 TF]   │
│ [셀4 TF]   │ [셀5 TF]   │ [셀6 TF]   │
└────────────────────────────────────────┘
```

편집 → raw 동기화: 셀 변경 시 `|` 구분자 포함 전체 테이블을 재구성.

---

## Phase 4 구현 완료

### 스마트 Enter (리스트 자동 continuation)

`EditorInputTransformation`에 `handleSmartEnter()` 추가.
`\n` 삽입 감지 시 이전 줄의 블록 prefix를 자동 continuation.
prefix-only 줄이면 prefix를 제거하여 빈 줄로 변환.

### 서식 토글 (RawStyleToggle)

`TextFieldState`에서 마크다운 마커를 직접 삽입/제거하는 유틸리티.
- `toggleInlineStyle(state, marker)`: `**`, `*`, `` ` ``, `~~`, `==`
- `toggleHeading(state, level)`: 현재 줄의 `#` prefix
- `toggleBlockPrefix(state, prefix)`: 현재 줄의 `- `, `> ` 등

### 하드웨어 키보드 단축키 (EditorKeyboardShortcuts)

`MarkdownBasicTextField.kt`의 `BasicTextField`에 `Modifier.onPreviewKeyEvent` 추가.
Ctrl/Cmd+B/I/E, Ctrl/Cmd+Shift+S/X/H → `RawStyleToggle` 호출.

---

## Phase 5: 재귀적 오버레이 + UI 개선 (✅ 구현 완료)

Callout body에 `MarkdownBasicTextFieldCore`를 사용하여 중첩 오버레이 지원.
Callout에만 재귀 적용 (Table/CodeBlock 내부는 재귀하지 않음).

### 구현 구조

```
MarkdownBasicTextField (public API, value/onValueChange)
  └─ MarkdownBasicTextFieldCore (internal, MarkdownEditorState 직접 사용)
       ├─ BasicTextField + RawMarkdownOutputTransformation
       ├─ drawBehind: BlockDecorationDrawer (Callout RoundBox, Inline Code RoundRect 등)
       ├─ depth=0: Box + clipToBounds
       └─ depth>0: OverlayAwareLayout (overlay offset+height 부모 측정 반영)
            └─ overlayBlocks → key(type, index) { BlockOverlay → CalloutOverlay/CodeBlockOverlay/TableOverlay }
                 └─ MarkdownBasicTextFieldCore (재귀, depth < MAX_OVERLAY_DEPTH=10)
```

### 핵심 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `MarkdownBasicTextField.kt` | Core 분리, `overlayDepth`/`parentScrollState`/`contentPadding` 파라미터, depth>0 OverlayAwareLayout, `key(type,index)` |
| `RawMarkdownOutputTransformation.kt` | `isFocused`, `applyBlockTransparent`, `inlineCodeRanges` |
| `BlockDecorationDrawer.kt` | `isNested`, `inlineCodeRanges`. Callout RoundBox(Stroke), Inline Code RoundRect |
| `BlockOverlay.kt` | `overlayDepth`, `onRequestActivation`, `contentPadding` → 모든 Overlay에 전달. Dialogue Callout만 contentPadding 제외 |
| `CalloutOverlay.kt` | RoundBox + 아이콘 + body fontSize×0.9 + lineHeight×0.9(depth>=1) + FocusRequester 내비게이션 |
| `CodeBlockOverlay.kt` | 라운드 배경 + monospace + lineHeight 패딩 보정 |
| `TableOverlay.kt` | lineHeight/2 상하 패딩 (구분자 줄 축소 보상) |

### 주요 설계 결정

1. **`isFocused` 기본값 `false`**: body 생성 시 커서가 텍스트 끝에 위치(`setMarkdown()` → `TextRange(length)`). `isFocused=true`이면 마지막 줄이 raw zone이 되어 마크다운 코드 노출. 포커스 없으면 raw zone 없이 모든 줄 서식 적용.

2. **`fillMaxWidth()` (depth>0)**: depth=0은 `fillMaxSize()`로 화면 전체 채움. depth>0에서 `fillMaxSize()` 사용 시 Column 내 무한 확장 → 오버레이가 아래 전체 덮음.

3. **`isNested` DrawBehind 제한**: 중첩 에디터에서 CodeBlock/Callout/Embed 배경 데코레이션 스킵 (오버레이가 자체 배경 처리).

4. **`overlaysAvailable` SideEffect 방식 불가**: `SideEffect`에서 OutputTransformation 프로퍼티 변경 → BasicTextField 재레이아웃 트리거 안 됨 → `transformOutput()` 재실행 안 됨. 생성자에서 고정 설정만 가능.

5. **포커스 이탈 즉시 동기화**: `LaunchedEffect(isCalloutFocused)` — debounce 300ms 대기 중인 변경 사항을 즉시 flush. 없으면 메인 에디터가 stale raw 텍스트로 렌더링.

6. **`remember` 키 없이 state 생성**: `remember(data.blockRange.textRange)`로 키잉하면 overlay→raw sync가 textRange를 변경할 때마다 state 재생성 → 사용자 입력 소실. 특히 body에서 `>[!note]` + Enter 시 Smart Enter 결과가 사라짐. 대신 `remember`(키 없음) + 부모 `key(textRange.first)` 조합으로 identity 관리.

7. **`rememberUpdatedState(data)`**: LaunchedEffect 내 sync 함수가 항상 최신 `data.blockRange.textRange` 사용. LaunchedEffect(Unit)으로 한 번만 시작하므로, data 파라미터 캡처 시 stale offset 사용 방지.

8. **편집 중 scroll forwarder 비활성화**: body에서 Enter → 높이 증가 → Column 리사이즈 → `overlayScrollForwarder`가 부모 ScrollState에 delta 전달 → 스크롤 점프. `isCalloutFocused=true`일 때 forwarder를 Modifier로 대체하여 방지.

9. **`onRequestActivation` 콜백**: 부모 `MarkdownBasicTextFieldCore`의 `FocusRequester`로 포커스 + 커서 이동. LongPress/Backspace에서 공통 사용. 기존 `textFieldState.edit { selection = ... }`만으로는 부모에 포커스가 없어 블록 활성화 안 됨.

10. **`parentScrollState`**: 중첩 오버레이의 스크롤 포워딩이 depth>0의 빈 scrollState로 전달되던 문제 해결. `overlayForwardScrollState = parentScrollState ?: scrollState`로 항상 루트까지 전달.

11. **CodeBlock 오버레이 제거**: 오버레이 sync 복잡도 대비 이점 부족. raw 마크다운 그대로 표시. 스캐너에서 펜스 감지만 유지 (코드 블록 내부 인라인 스캔 방지).

12. **Inline Code RoundRect 배경**: `SpanStyle.background`는 직사각형만 지원. `codeInlineBackground` 색상을 별도 분리하고, `inlineCodeRanges`를 OutputTransformation에서 노출, DrawBehind에서 `drawRoundRect(4dp)`로 그리기.

13. **`LineHeightStyle` 정규화**: Bold/Italic SpanStyle이 폰트 메트릭 차이로 줄 높이를 변경. `normalizedTextStyle`에 `LineHeightStyle(Proportional, Trim.Both)` 적용, `lineHeight` 미설정 시 `1.5.em` 기본값.

14. **Callout body fontSize 0.9em**: `textStyle.merge(TextStyle(fontSize = 0.9.em))`로 body에 전달. 중첩 시 부모 body의 textStyle이 자식 CalloutOverlay에 전달되므로 누적 축소 (0.9 → 0.81 → 0.729).

---

## 미구현 기능 — 향후 참고

### Undo / Redo

Phase 2에서는 raw text 기반이므로 텍스트 + 커서 스냅샷만 저장하면 된다.

### 툴바 UI

`RawStyleToggle`을 연동하여 버튼 UI를 추가.
로직은 Phase 4에서 구현 완료, UI만 추가하면 됨.

### 클립보드 직렬화

raw text 기반이므로 선택 범위의 텍스트를 그대로 복사하면 마크다운이 유지된다.
