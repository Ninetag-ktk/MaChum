# markdown/ 패키지 최적화 검토

코드 수정 없이 현재 상태를 검토하여, 최적화 가능한 항목을 정리한다.

---

## 1. 심각도 높음 (버그/데이터 손실 위험)

### TableOverlay 셀 상태 재생성 (데이터 손실)

**파일**: `ui/block/TableOverlay.kt`

`remember(data.blockRange.textRange)`로 셀 상태를 생성 중.
테이블 위에서 텍스트를 편집하면 `textRange`가 변경 → 셀 상태 전부 재생성 → 사용자 입력 소실.

```kotlin
// 현재 (문제)
val cellStates = remember(data.blockRange.textRange) { ... }

// 수정 방향: 키 없이 remember, 부모 key(type, index)로 identity 관리
val cellStates = remember { ... }
```

CalloutOverlay에서는 이미 키 없는 `remember`로 수정 완료됨. TableOverlay도 동일 패턴 적용 필요.

### DIALOGUE Callout overlayDepth 하드코딩

**파일**: `ui/block/CalloutOverlay.kt`

DIALOGUE 분기에서 `overlayDepth = 1`로 하드코딩. 일반 Callout은 `overlayDepth + 1` 사용.
중첩 DIALOGUE 콜아웃에서 depth 계산이 틀릴 수 있음.

```kotlin
// 현재 (DIALOGUE)
overlayDepth = 1

// 수정 방향
overlayDepth = overlayDepth + 1
```

---

## 2. 심각도 중간 (성능)

### 스크롤 시 오버레이 전체 재계산

**파일**: `ui/MarkdownBasicTextField.kt`

`overlayBlocks`가 `derivedStateOf`로 계산되며 `scrollOffset`에 의존.
스크롤 시 매 프레임마다 모든 오버레이의 위치를 재계산 + 가시성 판별.

수정 방향:
- 오버레이 데이터(파싱 결과)와 위치(Rect)를 분리
- 파싱 결과는 텍스트 변경 시에만 재계산
- 위치는 스크롤/레이아웃 변경 시에만 재계산

### MarkdownPatternScanner 반복 할당

**파일**: `state/MarkdownPatternScanner.kt`

`scan()` 호출 시마다 `text.split('\n')`으로 줄 리스트를 새로 생성.
`RawMarkdownOutputTransformation`이 이미 텍스트 변경 여부를 캐싱하지만, 줄 분할 결과는 캐싱하지 않음.

수정 방향: 줄 분할 결과를 `cachedLines`로 캐싱하거나, sequence 기반 처리로 할당 최소화.

### InlineStyleScanner 인라인 스캔 복잡도

**파일**: `state/InlineStyleScanner.kt`

`scanInline()`에서 각 마커(`***`, `**`, `~~`, `==` 등)마다 `line.indexOf()`를 호출.
긴 줄에 많은 인라인 서식이 있으면 O(n×m) 복잡도.

수정 방향: 단일 패스 상태 머신으로 전환하면 O(n)으로 개선 가능. 단, 현재 성능이 문제되지 않으면 우선순위 낮음.

### TableOverlay 셀 동기화 LaunchedEffect 과다

**파일**: `ui/block/TableOverlay.kt`

`LaunchedEffect(cellStates)` 내에서 모든 셀에 대해 중첩 루프로 snapshotFlow를 수집.
5×5 테이블이면 25개 이상의 flow 수집기가 동시 동작.

수정 방향: 모든 셀 변경을 단일 flow로 병합하거나, 셀별 LaunchedEffect를 각 셀 Composable 내부로 이동.

### CalloutOverlay 이중 debounce

**파일**: `ui/block/CalloutOverlay.kt`

title과 body에 각각 독립적인 `LaunchedEffect(Unit) + debounce(300ms)`가 동작.
동시 편집 시 title과 body가 서로 다른 타이밍에 sync → 중간 상태의 raw가 저장될 수 있음.

수정 방향: 단일 LaunchedEffect에서 title+body를 combine하여 한 번에 sync.

---

## 3. 심각도 낮음 (코드 품질)

### 미사용 코드

| 파일 | 항목 | 비고 |
|---|---|---|
| `state/RawMarkdownOutputTransformation.kt` | `forceAllOverlaysInactive` 프로퍼티 | 선언만 있고 사용처 없음 |
| `service/MarkdownStyleConfig.kt` | `codeBlock` SpanStyle | `codeInline`만 사용, `codeBlock`은 미사용 |

### Regex 패턴 중복

`calloutHeaderRegex`가 두 파일에 각각 정의:
- `state/MarkdownPatternScanner.kt`
- `state/OverlayBlockParser.kt`

패턴이 미묘하게 다름 (Scanner는 depth 지원, Parser는 단순 매칭).
공용 상수 객체로 추출하면 일관성 유지 가능.

### 안전한 substring 추출 중복

`rawText.substring(range.first.coerceIn(...), (range.last + 1).coerceIn(...))`
패턴이 `MarkdownBasicTextField.kt`와 여러 곳에서 반복됨.
유틸리티 함수로 추출 가능:

```kotlin
fun String.safeSubstring(range: IntRange): String =
    substring(range.first.coerceIn(0, length), (range.last + 1).coerceIn(0, length))
```

### MarkdownBasicTextFieldCore 책임 과다

**파일**: `ui/MarkdownBasicTextField.kt`

`MarkdownBasicTextFieldCore` 함수가 담당하는 역할:
- TextStyle 정규화
- InputTransformation/OutputTransformation 생성
- 오버레이 블록 계산
- BasicTextField + 오버레이 렌더링

오버레이 상태 관리를 `rememberOverlayBlocks()` 같은 커스텀 훅으로 분리하면 가독성 향상.

### 네이밍 일관성

- `cachedSpans` / `cachedBlocks` (OutputTransformation) vs `ScanResult(spans, blocks)` — ScanResult를 통째로 캐싱하면 분리 불필요
- `isCalloutFocused` (Callout) vs 별도 포커스 추적 없음 (Table) — 패턴 불일치

---

## 4. 우선 순위 정리

| 순위 | 항목 | 영향 |
|---|---|---|
| 1 | TableOverlay 셀 상태 remember 키 제거 | 데이터 손실 방지 |
| 2 | DIALOGUE overlayDepth 하드코딩 수정 | 중첩 정확성 |
| 3 | 오버레이 데이터/위치 분리 (스크롤 최적화) | 대용량 문서 성능 |
| 4 | 미사용 코드 제거 | 코드 정리 |
| 5 | Regex/substring 중복 제거 | 유지보수성 |
| 6 | Scanner 줄 분할 캐싱 | 메모리/성능 |
| 7 | TableOverlay sync 개선 | 대형 테이블 성능 |
| 8 | Core 함수 분리 | 가독성 |
