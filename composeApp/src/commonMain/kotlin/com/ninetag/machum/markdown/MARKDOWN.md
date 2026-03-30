# MaChum 마크다운 엔진 구조 설명

이 문서는 MaChum의 마크다운 에디터 구현을 설명한다.
`reference_hyphen/` 디렉토리에 레퍼런스(Hyphen 라이브러리) 원본이 있으며, 설계 참고용으로만 사용한다.
MaChum은 이를 직접 사용하지 않고, 프로젝트에 최적화하여 별도 구현했다.

---

## 1. 전체 구조

### Phase 2 (현재) — Raw Text + 기호 투명화 방식

| 항목 | 설명 |
|---|---|
| 패키지 | `com.ninetag.machum.markdown.editor` |
| 내부 텍스트 | **raw text** (기호 유지: `# 가나다`, `**볼드**`) |
| 서식 정보 | `OutputTransformation`이 실시간 계산 |
| 화면 표시 | 활성 줄: raw text 그대로 / 비활성 줄: 기호 투명 + SpanStyle |
| 저장 형식 | raw text 그대로 저장 (직렬화 불필요) |

### 이전 v2 (폐기) — Clean Text + Spans 방식

| 항목 | 설명 |
|---|---|
| 내부 텍스트 | clean text (기호 제거: `가나다`, `볼드`) |
| 서식 정보 | `List<MarkupStyleRange>` 별도 관리 |
| 저장 형식 | `toMarkdown()` 직렬화 필요 |
| 폐기 이유 | 커서 줄에 기호를 다시 표시할 수 없는 구조적 한계, IME 조합 리셋 위험 |

### 미구현 기능 (레퍼런스에 있으나 MaChum에 없음)

| 기능 | 레퍼런스 파일 | 비고 |
|---|---|---|
| Undo/Redo | `state/HistoryManager.kt` | Phase 2 raw text 기반 재설계 필요 |
| 클립보드 직렬화 | `ui/EditorExtensions.kt` | raw text 복사로 대체 가능 |
| Checkbox | `model/MarkupStyle.kt` | MaChum에서 불필요 |

### Phase 4에서 구현 완료 (레퍼런스 참고 → MaChum 최적화 구현)

| 기능 | MaChum 파일 | 비고 |
|---|---|---|
| 스마트 Enter | `EditorInputTransformation.kt` | 리스트/인용구/체크박스 자동 continuation |
| 서식 토글 | `RawStyleToggle.kt` | 인라인/헤딩/블록 prefix 토글 유틸리티 |
| 하드웨어 키보드 단축키 | `EditorKeyboardShortcuts.kt` | Ctrl+B/I/E, Ctrl+Shift+S/X/H |

### MaChum 전용 기능 (레퍼런스에 없음)

| 기능 | 파일 | 비고 |
|---|---|---|
| WikiLink `[[target\|alias]]` | `InlineStyleScanner.kt` | 마커 숨김 + 링크 스타일 |
| ExternalLink `[text](url)` | `InlineStyleScanner.kt` | 마커 숨김 + 링크 스타일 |
| EmbedLink `![[file]]` | `InlineStyleScanner.kt` | 마커 숨김 + 링크 스타일 |
| Auto-close `[[`, `**`, `` ` `` 등 | `EditorInputTransformation.kt` | 자동 닫기 |

---

## 2. 핵심 데이터 흐름

```
[파일 로딩]
  .md 파일 (raw 마크다운)
      ↓ MarkdownEditorState.setMarkdown(markdown)
  TextFieldState ← raw text 그대로 주입

[사용자 입력]
  키 입력
      ↓ onPreviewKeyEvent → EditorKeyboardShortcuts
        - Ctrl+B/I/E, Ctrl+Shift+S/X/H → RawStyleToggle
      ↓ EditorInputTransformation
        - Smart Enter: 블록 prefix 자동 continuation
        - auto-close: [[ → [[|]], ** → **|** 등
        - Tab → 스페이스 2칸
      ↓ TextFieldState에 반영 (raw text)

[화면 표시]
  BasicTextField 렌더링 시
      ↓ RawMarkdownOutputTransformation.transformOutput()
        1. text != cachedText 이면 MarkdownPatternScanner.scan(text) 재실행
        2. selection.start 로 커서 줄 경계 계산
        3. for (range, style) in cachedSpans:
           - 커서 줄과 겹치면 → skip (활성 줄: raw text 그대로)
           - 아니면 → addStyle(style, start, end) (비활성 줄: 서식 적용)

[저장]
  textFieldState.text.toString()
      ↓ EditorPage 의 pendingMarkdown (500ms debounce)
      ↓ viewModel.updateBody()
      ↓ NoteFile.body에 저장
```

---

## 3. 파일별 역할

### 핵심 에디터 (editor/)

| 파일 | 역할 |
|---|---|
| `MarkdownEditorState.kt` | TextFieldState(raw text) 홀더. `setMarkdown()` 으로 초기화 |
| `MarkdownPatternScanner.kt` | 문서 전체 스캔 → `List<Pair<IntRange, SpanStyle>>` 반환. 줄 단위 블록 타입 감지 후 InlineStyleScanner에 위임 |
| `RawMarkdownOutputTransformation.kt` | `OutputTransformation` 구현. 커서 줄 판별 + 비활성 줄 서식 적용. 텍스트 변경 시만 재스캔 (캐시) |
| `InlineStyleScanner.kt` | 블록 타입별 SpanStyle 범위 계산. MARKER(투명) + 내용 서식. 헤딩/코드블록/인라인 마커 처리 |
| `MarkdownBasicTextField.kt` | BasicTextField 래퍼 Composable (Basic). `value`/`onValueChange` 시그니처. 내부에서 state + input/output transformation 조립 + `onPreviewKeyEvent` 키보드 단축키 |
| `MarkdownTextField.kt` | Material3 래퍼. 내부에서 `MarkdownBasicTextField`를 호출하며 Material3 테마를 자동 적용 |
| `EditorInputTransformation.kt` | Smart Enter (블록 prefix 자동 continuation) + auto-close (`[[`, `**`, `` ` `` 등) + Tab→space. `InputTransformation` 구현 |
| `RawStyleToggle.kt` | 서식 토글 유틸리티. 인라인 마커/헤딩/블록 prefix 삽입·제거. 키보드 단축키 및 향후 툴바 공용 |
| `EditorKeyboardShortcuts.kt` | 하드웨어 키보드 단축키. Ctrl/Cmd+B/I/E, Ctrl/Cmd+Shift+S/X/H |

### 파서/렌더러 (parser/, renderer/, token/)

| 디렉토리 | 역할 | Phase 2에서의 사용 |
|---|---|---|
| `token/` | `MarkdownBlock`, `InlineToken` sealed class | `MarkdownPatternScanner`가 블록 타입 인스턴스 생성에 사용 |
| `parser/` | `BlockSplitter` → `BlockParser` → `InlineParser` 파이프라인 | 에디터에서 직접 사용하지 않음 (파일 로딩 시 블록 판별용) |
| `renderer/` | 11개 블록별 Composable 렌더러 | Phase 3 특수 블록 렌더링에 재활용 예정 |

### 레퍼런스 (reference_hyphen/)

설계 참고용으로 복사한 Hyphen 라이브러리 원본.
`com.denser.hyphen` 패키지이며, MaChum 코드에서 직접 import하지 않는다.

| 디렉토리 | 포함 내용 |
|---|---|
| `markdown/` | `MarkdownConstants`, `MarkdownProcessor`, `MarkdownSerializer` |
| `model/` | `MarkupStyle`, `MarkupStyleRange`, `StyleSets` |
| `state/` | `HyphenTextState`, `SpanManager`, `BlockStyleManager`, `HistoryManager` |
| `ui/` | `HyphenBasicTextEditor`, `HyphenStyleConfig`, `EditorExtensions` |

---

## 4. 주요 설계 결정

### Raw Text 방식 선택 이유 (v2 → Phase 2)

v2(clean text)의 한계:
1. **Active line 불가**: 기호를 제거한 상태이므로 커서 줄에 기호를 다시 표시할 수 없음
2. **IME 리셋**: 기호 제거 시 `buffer.replace()`로 텍스트가 바뀌면 한국어 IME 조합 상태가 리셋
3. **직렬화 오버헤드**: 매 저장마다 clean text + spans → markdown 재조립 필요

Phase 2(raw text)의 장점:
1. **자연스러운 active line**: 기호가 항상 있으므로 투명만 해제하면 됨
2. **IME 안전**: 기호를 건드리지 않으므로 조합 상태 유지
3. **저장 단순화**: raw text 그대로 저장, 직렬화 불필요

### InlineStyleScanner 위임 방식

`MarkdownPatternScanner`는 줄 단위로 블록 타입을 감지한 후,
적절한 `MarkdownBlock` 인스턴스를 생성하여 `InlineStyleScanner.computeSpans()`에 위임한다.

이 방식의 이점:
- 인라인 스캔 로직(`scanInline`, `hideLinePrefix` 등)을 중복 구현하지 않음
- 블록 타입별 분기가 `InlineStyleScanner`에 캡슐화됨
- `MarkdownPatternScanner`는 문서 레벨 스캔(코드 블록 추적 등)에만 집중

### 입력 변환 (EditorInputTransformation)

`EditorInputTransformation`은 `InputTransformation`을 구현한다.

#### Smart Enter (Phase 4)

`\n` 삽입 감지 시 이전 줄의 블록 prefix를 자동 continuation:

| prefix | continuation | prefix-only 동작 |
|---|---|---|
| `- ` | `- ` | prefix 제거 |
| `* ` | `* ` | prefix 제거 |
| `> ` | `> ` | prefix 제거 |
| `숫자. ` | `(숫자+1). ` | prefix 제거 |
| `- [ ] ` / `- [x] ` | `- [ ] ` | prefix 제거 |

들여쓰기(indent)가 있으면 그대로 보존한다.

#### Auto-close + Tab

| 입력 | 결과 | 조건 |
|---|---|---|
| `[[` | `[[`\|`]]` | — |
| `![[` | `![[`\|`]]` | `[[`보다 먼저 체크 |
| `**` | `**`\|`**` | 앞·뒤 문자가 `*`가 아닐 때 |
| `~~` | `~~`\|`~~` | 앞·뒤 문자가 `~`가 아닐 때 |
| `==` | `==`\|`==` | 앞·뒤 문자가 `=`가 아닐 때 |
| `` ` `` | `` ` ``\|`` ` `` | 앞·뒤 문자가 `` ` ``가 아닐 때 |
| `*` | `*`\|`*` | 앞·뒤 문자가 `*`가 아닐 때 |
| Tab | 스페이스 2칸 | 항상 |

### 키보드 단축키 (EditorKeyboardShortcuts — Phase 4)

`MarkdownBasicTextField`의 `onPreviewKeyEvent`에서 호출.
`RawStyleToggle`을 통해 서식 마커를 삽입/제거한다.

| 단축키 | 동작 |
|---|---|
| Ctrl/Cmd + B | Bold `**` 토글 |
| Ctrl/Cmd + I | Italic `*` 토글 |
| Ctrl/Cmd + E | InlineCode `` ` `` 토글 |
| Ctrl/Cmd + Shift + S/X | Strikethrough `~~` 토글 |
| Ctrl/Cmd + Shift + H | Highlight `==` 토글 |

---

## 5. 파일 구조 요약

```
markdown/
├── reference_hyphen/          ← 레퍼런스 (직접 사용하지 않음, 설계 참고용)
│   ├── markdown/
│   │   ├── MarkdownConstants.kt
│   │   ├── MarkdownProcessor.kt
│   │   └── MarkdownSerializer.kt
│   ├── model/
│   │   ├── MarkupStyle.kt
│   │   ├── MarkupStyleRange.kt
│   │   └── StyleSets.kt
│   ├── state/
│   │   ├── HyphenTextState.kt
│   │   ├── SpanManager.kt
│   │   ├── BlockStyleManager.kt
│   │   ├── HistoryManager.kt
│   │   └── SelectionManager.kt
│   └── ui/
│       ├── HyphenBasicTextEditor.kt
│       ├── HyphenStyleConfig.kt
│       ├── EditorExtensions.kt
│       └── material3/HyphenTextEditor.kt
│
├── token/                     ← 블록/인라인 타입 정의
│   ├── MarkdownBlock.kt
│   └── InlineToken.kt
│
├── parser/                    ← 파서 파이프라인
│   ├── MarkdownParser.kt
│   ├── BlockSplitter.kt
│   ├── InlineParser.kt
│   ├── BlockParser.kt
│   ├── MarkdownParserImpl.kt
│   └── block/ (7개 sub-parser)
│
├── renderer/                  ← 블록별 Composable 렌더러
│   └── (11개 파일)
│
└── editor/                    ← Phase 2 에디터 + Phase 4 편집 기능
    ├── MarkdownEditorState.kt              raw text 홀더
    ├── MarkdownPatternScanner.kt           문서 전체 스캔 (블록 감지 + InlineStyleScanner 위임)
    ├── RawMarkdownOutputTransformation.kt  커서 줄 기반 OutputTransformation
    ├── InlineStyleScanner.kt               블록별 SpanStyle 범위 계산
    ├── MarkdownStyleConfig.kt              서식 커스터마이징 설정
    ├── MarkdownBasicTextField.kt           BasicTextField 래퍼 (Basic)
    ├── MarkdownTextField.kt                Material3 래퍼
    ├── EditorInputTransformation.kt        Smart Enter + auto-close + Tab→space
    ├── RawStyleToggle.kt                   서식 토글 유틸리티
    └── EditorKeyboardShortcuts.kt          하드웨어 키보드 단축키
```

---

## 6. v2 시절 알려진 버그 (참고 기록)

> v2(clean text + spans) 코드는 Phase 2 전환으로 삭제되었다.
> 아래 버그들은 v2 아키텍처의 한계로 발생했으며, Phase 2에서는 구조적으로 해결되었다.

### Bug 1: 첫 글자에만 서식 적용

**원인:** `SpanManager.shiftSpans`에서 `changeStart >= span.end` 사용. `span.end` 위치 입력 시 스팬이 확장되지 않음.
**수정 (v2):** `>=` → `>` 변경 + `reanchorHeadingSpans()` 추가.
**Phase 2:** spans 자체가 없으므로 해당 없음.

### Bug 2: 한국어 IME 자모 분리

**원인:** 패턴 감지 시 `buffer.replace(0, length, cleanText)` 전체 교체 → IME 조합 리셋.
**수정 (v2):** `applyMinimalEdits()` 추가 (공통 접두사/접미사 계산, 최소 범위 삭제).
**Phase 2:** 기호를 제거하지 않으므로 buffer 수정 자체가 없음. 구조적으로 해결.

### Bug 3: 다음 줄 서식 입력 시 Heading 소멸

**원인:** `SpanManager.mergeSpans`에서 `isBlock` 스팬을 무조건 교체. 다른 줄의 Heading이 사라짐.
**수정 (v2):** Heading은 겹치는 줄만 교체, 다른 줄 보존.
**Phase 2:** 스팬 병합 로직 자체가 없으므로 해당 없음.
