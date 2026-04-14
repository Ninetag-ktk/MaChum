# Callout Overlay 높이 불일치 문제 — 아카이브

> **이 문서는 v1(overlay 아키텍처)의 문제 분석 기록이다.**
> v2(블록 에디터)에서는 각 블록이 독립 Composable이므로 이 문제가 발생하지 않는다.
> v2 설계는 **CLAUDE_sub.md** 참고.

## 요약

v1에서 overlay UI 높이와 raw markdown text 높이가 불일치하는 문제.
원인: overlay chrome(border+padding+icon)이 raw text에 없음 + SpanStyle로 paragraph lineHeight를 줄일 수 없는 Compose 제약.

## 검토한 해결 방향

1. fontSize 역산 → 부분적 유효 (chrome 오차 남음)
2. lineHeight ParagraphStyle 보정 → OutputTransformation에서 ParagraphStyle 미지원
3. onHeightChanged 콜백 → 차선책 (1프레임 지연)
4. **블록 에디터로 전환 (v2)** → 근본 해결 (채택)
5. BasicTextField 자체 커스텀 → 비현실적

상세 분석은 git history 참조.
