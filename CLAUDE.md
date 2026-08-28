# MaChum 작업 가이드

이 파일은 저장소에서 작업할 때 필요한 최소 지침만 제공한다. 아키텍처와 진행 상태를 중복 기록하지 않는다.

## Source of truth

- 현재 코드 구조와 데이터 흐름: `docs/architecture.md`
- 제품 정책, 폴더-존, 기능 우선순위: `docs/product-roadmap.md`
- 블록 에디터의 파싱·포커스·selection·dissolve 계약: `docs/markdown-editor.md`
- 기술 버전: `gradle/libs.versions.toml`

## 검증 명령

```bash
./gradlew :composeApp:compileKotlinJvm
./gradlew :composeApp:jvmTest
./gradlew :desktopApp:run
./gradlew :androidApp:assembleDebug
./gradlew test
```

## 작업 규칙

1. 구현 상태는 `미구현 / 부분 구현 / 구현·검증 필요 / 검증 완료`로 구분한다.
2. 제품 우선순위는 `docs/product-roadmap.md` 한 곳에서만 갱신한다.
3. 에디터 동작 계약과 검증 시나리오는 `docs/markdown-editor.md` 한 곳에서만 갱신한다.
4. Kotlin, Compose, Android SDK 버전 숫자를 문서에 복사하지 않고 version catalog를 참조한다.
5. 기능을 변경하면 관련 자동 테스트 또는 수동 검증 기준도 함께 갱신한다.

## 주의할 코드 계약

- workflow 화면과 API는 라이브 흐름에서 은퇴했지만 삭제되지 않았다.
- `workflowSceen` 패키지명에는 기존 오타가 있으므로 임의 rename하지 않는다.
- 블록 에디터의 Lazy item callback에서 최신 block list나 index가 필요하면 `rememberUpdatedState`를 사용한다.
- dissolve된 `rawMode` Text는 편집 중 reparse하지 않는다.
- Embed 박스가 구현되기 전에는 parser의 Embed 변환을 활성화하지 않는다.
- frontmatter는 관리 키만 수정하고 미관리 키·주석·포맷을 보존한다.
- Android와 Desktop의 파일 동작은 expect/actual 양쪽을 함께 검토한다.
