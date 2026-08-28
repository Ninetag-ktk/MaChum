# MaChum (맞춤)

MaChum은 Obsidian vault와 함께 사용하는 Compose Multiplatform 마크다운 집필 앱입니다. Android와 Desktop(JVM)을 지원하며, 현재 블록 기반 편집기 안정화와 폴더-존 탐색 구조를 개발하고 있습니다.

## 문서

- [현재 아키텍처](docs/architecture.md)
- [제품 모델과 기능 로드맵](docs/product-roadmap.md)
- [블록 기반 마크다운 에디터 설계](docs/markdown-editor.md)

위 세 문서가 활성 설계의 source of truth입니다. 기술 버전은 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)을 기준으로 합니다.

## 실행과 검증

```bash
# Desktop 실행
./gradlew :desktopApp:run

# Desktop hot reload
./gradlew :composeApp:runDesktop -t

# JVM 컴파일과 테스트
./gradlew :composeApp:compileKotlinJvm :composeApp:jvmTest

# Android APK
./gradlew :androidApp:assembleDebug

# 전체 테스트
./gradlew test
```

## 현재 개발 단계

- workflow 기반 네비게이션은 은퇴하고 dormant 코드만 유지합니다.
- 블록 기반 Text, Callout, Code, Table 편집은 구현되어 있습니다.
- cross-block selection과 dissolve는 구현 범위를 넓히며 검증 중입니다.
- 폴더-존 스키마와 구조화 frontmatter는 구현됐지만 폴더 탐색 UI에는 아직 연결되지 않았습니다.
- 커밋과 버전 추적은 폴더·파일 정체성 모델이 안정된 뒤 진행합니다.

[개발 과정 블로그](https://ninetag.tistory.com/category/%EB%A7%9E%EC%B6%A4_MaChum%20%28%EA%B8%80%EC%93%B0%EA%B8%B0%20Workflow%20%EC%95%B1%29)
