# 에이전트 운영 원칙

공통 실행 지침은 [OpenAI 모델 가이드](https://developers.openai.com/api/docs/guides/latest-model)의 작업 완수·위임·검증 권고를 프로젝트에 맞게 적용한다. 아래 모델 배치는 사용자와 합의한 프로젝트 정책이다.

- 주 에이전트는 복잡한 설계, 의사결정, 작업 조율과 최종 검토를 담당한다.
- 기본 모델·추론 강도는 메인 `gpt-5.6-sol / high`, 일반 서브에이전트 `gpt-5.6-sol / medium`, `spark_worker`는 `gpt-5.6-luna / medium`, `hero_worker`는 `gpt-5.6-sol / high`, `code_reviewer`는 `gpt-5.6-sol / xhigh`로 운용한다. 메인·일반 서브에이전트 기본값은 `.codex/config.toml`, 역할별 모델·추론 강도는 `.codex/agents/*.toml`에 명시하며 `ultra`를 기본값으로 사용하지 않는다.
- 2026-09-14 모델 변경: 위 Sol 추론 강도는 복합 구현·설계 및 독립 검토 품질을 우선한 시작 설정이다. 모델 간 동등 성능을 보장하는 환산값은 아니며, 대표 구현·저장 동시성·코드 검토 작업의 결함 누락·재작업·시간·토큰 사용을 비교해 조정한다. `max`는 기본값으로 사용하지 않는다. 지원 범위와 조정 근거는 [GPT-5.6 공식 가이드](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-5.6)를 따른다.
- 2026-09-18 모델 변경: 현재 라이선스에서 Spark 모델을 사용할 수 없어 빠른 소규모 구현·탐색 역할인 `spark_worker`의 모델을 `gpt-5.6-luna / medium`으로 교체했다. 역할명과 배정 기준은 기존 자동화·문서 호환을 위해 유지한다.
- 사용자 의도와 완료 조건에 따라 이미 승인되었거나 요청에 포함된 작업을 끝까지 수행한다. 구현 요청을 계획 제시만으로 끝내지 않으며, 검토·수정 준비 요청은 그 범위의 산출물을 완성한다. 일상적인 가역적 판단은 자율적으로 하고 중요한 누락 정보가 있을 때만 질문한다. 답변에 의존하지 않는 작업은 계속한다.
- 시스템·개발자 지침과 실제 권한 안에서 사용자의 명시적 지시를 로컬 지침과 스킬의 일반 가이드보다 우선한다. 스킬 때문에 중단하거나 승인을 요청하면 정확한 SKILL.md 경로와 해당 지시, 적용 이유를 밝힌다. 이미 승인된 작업에 중복 승인을 요구하지 않는다.
- 작업 중 추가 지시는 기존 목표에 병합한다. 사용자가 목표를 취소하거나 대체하지 않는 한 질문에 답한 뒤 기존 작업을 이어간다. 보고는 결과·근거·남은 위험을 간결하게 전달하고 실제 실행하지 않은 검증을 성공으로 표현하지 않는다.
- `code_reviewer`는 주요 변경 단위마다 구현과 독립적으로 코드 안정성, 과도한 복잡도, 정책 준수와 테스트 타당성을 읽기 전용으로 검토한다. 상시 감시 대신 부모가 지정한 범위와 시점에 투입하며, 기존 서브에이전트 슬롯 안에서 운용한다.
- `code_reviewer`는 파일 수정, 빌드·테스트 실행, 하위 에이전트·별도 작업 생성을 하지 않는다. 문제의 위치·발생 조건·영향·근거·최소 수정 방향을 보고하고, 확인된 결함과 선택적 개선을 구분한다. 수정 필요성은 주 에이전트가 판단하고 실제 수정은 구현 담당자가 수행한다.
- 테스트는 시나리오·테스트 코드의 타당성 검토 → 관련 테스트 실행·수정 → 최종 회귀 검증 순서로 진행한다. 테스트 검토에서는 잘못된 구현도 통과하는 조건과 비동기 완료 전 검증을 우선 확인한다. 주 에이전트가 변경 영향과 위험도, 필수 검사에 따라 최종 회귀 범위를 정한다. 문서·설정 변경에는 내용 일관성과 설정 문법을 확인하고, 저장·동시성·공통 코드 변경에는 영향받는 전체 흐름을 검증한다. 구현을 그대로 재현하는 무의미한 테스트는 추가하지 않는다. 통과 후 재검증은 새 변경·실패·구체적인 미해결 우려가 있을 때 수행한다.
- 독립적이고 범위가 명확한 코드 탐색, 소규모 구현과 반복 테스트는 우선 `spark_worker`에 위임한다.
- `spark_worker`가 사용 한도 부족으로 중단되면 다른 사용 가능한 서브에이전트가 작업을 이어받는다. 기존 변경, 수정 파일 소유권, 남은 작업과 테스트 결과를 인계하고, 중단된 에이전트의 동시 수정을 막은 뒤 소유권을 이전한다. 한도 초기화만 기다리며 전체 작업을 멈추지 않는다.
- 시간 절약이나 품질 향상이 기대되는 독립 작업은 주 작업과 함께 적극적으로 병렬 진행한다. 배정에는 목표, 담당 파일·모듈, 완료 조건, 제약과 검증 범위를 명시한다. 부모는 다른 유용한 작업을 계속하고, 구체적인 불일치가 없으면 위임한 탐색을 반복하지 않는다. 서브에이전트는 주 에이전트가 직접 생성한 최대 5개를 상한으로 두되 실제 사용 가능한 슬롯 안에서 운용한다.
- 2026-09-11 사용자 변경 지시: `product-design`은 디자인 작업에 필요하면 별도 명시 요청 없이 사용할 수 있다. `computer-use`는 계속 사용자가 화면 확인·캡처·조작을 명시적으로 요청한 경우에만 사용한다. Product Design 사용 허용을 Computer Use 승인으로 확대하지 않는다. 실제 화면 검증은 이전 감사 요청만으로 재개하지 않는다. 후속 명시 요청으로 감사를 재개하더라도 실제 문서의 삭제·복원·초기화 등 데이터 변경 확정은 감사 범위에 포함하지 않는다.
- 팝업·드롭다운의 신규·수정 화면은 `docs/popup-dropdown-design-policy.md`의 표준 디자인 정책을 따른다. 공통 정책은 이전 화면별 개선 기획보다 우선하며, 예외는 목적과 차이를 문서화한다. 정책 수립을 구현 완료나 개별 화면 구성 변경의 확정으로 해석하지 않는다.
- `hero_worker`는 주 에이전트와 동급 사양의 보조 에이전트로, 독립된 복합 구현이나 교차 모듈 검토가 병렬로 필요할 때 사용한다.
- 서브에이전트 생성과 작업 배정은 주 에이전트만 수행한다. 모든 서브에이전트(`hero_worker`, `spark_worker`, `code_reviewer`, 일반 역할 포함)는 추가 서브에이전트를 생성하거나 다른 에이전트에 재위임하지 않는다. 작업 분리가 필요하면 주 에이전트에게 요청하고, 답변과 무관한 담당 작업은 계속한다.
- 같은 파일이나 밀접하게 연결된 코드를 여러 에이전트가 동시에 수정하지 않는다. 충돌 가능성이 있으면 순차적으로 조율한다.
- 서브에이전트는 중요한 설계 결정이나 모호한 정책을 임의로 확정하지 않고 부모 에이전트에게 보고한다.
- 구현 후에는 변경 범위와 위험도에 맞는 테스트를 수행한다.
- 2026-09-11 테스트 결과 보고: 통과 개수는 이번 수정과 직접 관련된 개별 테스트만 집계한다. 전체 JVM 등 기존 테스트 전체 실행 수를 이번 수정의 통과 개수로 보고하지 않는다. 전체 회귀 검사는 별도로 통과·실패 여부를 표시하며, 같은 테스트의 재실행을 중복 집계하지 않는다. 필요한 회귀 검사 범위 자체를 줄이라는 의미는 아니다.
- 현재 슬롯이 부족하면 기존 에이전트를 재사용하거나 순차 진행한다. 실제로 상향 가능한 설정 변경이 필요할 때만 충돌 없이 병렬화할 독립 작업 수를 기준으로 필요한 추가 슬롯 수와 이유를 산정해 사용자에게 먼저 요청한다. 사용자 승인만으로 실행 환경의 한도가 늘어난다고 가정하지 않는다.
- 2026-09-11 재확인: Computer Use는 사용자가 화면 확인·캡처·조작을 명시적으로 요청한 경우에만 사용한다. 일반적인 수정 요청이나 과거 화면 감사 승인을 새 작업의 화면 조작 승인으로 확대하지 않는다.
- 이후 사용자가 지시하는 세션 운영 정책은 이 문서에 계속 병합한다.
- 설계 문서의 같은 정책은 한 기준 문서에서 관리하고 다른 문서는 참조한다. 최신 명시 합의로 해소되지 않은 내용 충돌은 임의로 선택하지 않고 사용자에게 질문한다. 답변을 기다리는 동안 충돌과 무관한 중복 정리는 계속한다.

## 서브에이전트의 graphify·ponytail 활용

- 코드 탐색·영향 범위 분석을 맡은 서브에이전트는 `graphify-out/graph.json`이 있으면 `graphify query`, `graphify path`, `graphify explain`으로 범위를 먼저 좁힌 뒤 현재 소스와 diff를 확인한다.
- graphify 결과는 탐색 보조 자료다. 그래프에 아직 반영되지 않은 미커밋 변경이 있을 수 있으므로 최종 판단은 현재 소스와 diff를 기준으로 한다.
- 여러 에이전트가 동시에 `graphify update .`를 실행하지 않는다. 코드 변경과 검증이 합쳐진 뒤 주 에이전트 또는 명시적으로 지정된 한 담당자만 한 번 실행한다.
- `spark_worker`와 `hero_worker`는 기존 공통 함수·컴포넌트와 표준 라이브러리·플랫폼 기능을 먼저 찾고, ponytail 원칙에 따라 요구사항을 충족하는 가장 작은 변경을 선택한다. 불필요한 추상화·의존성·미래 확장용 구조를 추가하지 않는다.
- ponytail은 입력 검증, rollback, 데이터 무결성, 보안, 접근성, 사용자가 요구한 기능과 영향 범위에 필요한 테스트를 생략하는 근거로 사용하지 않는다.
- `code_reviewer`는 정확성·동시성·부분 실패·데이터 손실 검토를 우선한다. 과도한 복잡도는 확인된 결함과 구분해 선택적 개선으로 보고하며, `ponytail-review`는 부모가 검토 범위에 명시한 경우에만 적용한다.
- 읽기 전용 탐색·검토 담당자는 graphify 산출물이나 소스 파일을 수정하지 않고 `graphify update .`도 실행하지 않는다.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, the parent or one explicitly assigned update owner runs `graphify update .` once after concurrent changes are integrated and verified (AST-only, no API cost).
