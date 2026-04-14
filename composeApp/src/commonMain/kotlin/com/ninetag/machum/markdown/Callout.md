# Callout 구현 문서

> **v2 전환 시 이 문서는 CLAUDE_sub.md의 CalloutBlockEditor 섹션으로 통합 예정.**
> 현재 v1(overlay) 구현의 참고 문서로 유지.

---

## v2에서의 Callout 구조

```kotlin
data class Callout(
    val calloutType: String,
    val titleState: TextFieldState,
    val bodyBlocks: List<EditorBlock>,  // 재귀적 블록 리스트
) : EditorBlock()
```

- 독립 Composable: `CalloutBlockEditor`
- body는 `MarkdownBlockEditor` 재귀 호출 → 중첩 Callout 자연스럽게 지원
- 높이 제한 없음 (overlay/clipToBounds 불필요)
- `toMarkdown()`으로 직렬화 (양방향 sync 불필요)

### 지원 타입

| 타입 | 배경색 | 테두리색 |
|---|---|---|
| NOTE | 파랑 계열 | 파랑 |
| TIP | 청록 계열 | 청록 |
| IMPORTANT | 보라 계열 | 보라 |
| WARNING | 주황 계열 | 주황 |
| DANGER / CAUTION | 빨강 계열 | 빨강 |
| QUESTION | 남색 계열 | 남색 |
| SUCCESS | 초록 계열 | 초록 |
| DIALOGUE | Row 레이아웃 (title + body 가로 배치) | 타입별 |

스타일 설정: `service/MarkdownStyleConfig.kt` → `calloutDecorationStyle(type)`

### 마크다운 문법

```markdown
> [!NOTE] 제목
> 본문

>> [!TIP] 중첩 Callout
>> 본문
```

### v1 Callout 구현 (overlay 방식) — 참고

v1에서의 양방향 sync, 키보드 내비게이션, 스크롤 포워딩 등의 상세 내용은
git history에서 이전 버전의 이 파일을 참조.
