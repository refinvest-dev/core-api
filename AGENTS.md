# AGENTS.md — core-api local conventions

이 문서는 core-api 작업 트리의 로컬 규칙만 정의한다. 제품 도메인, ADR, API 계약의 정본은
외부 `context` 저장소이며, 이 저장소에는 동기화된 스냅샷만 둔다.

## 1. 문서와 계약

- 작업 전 다음 순서로 읽는다: `docs/AI_AGENT.md`, `docs/DECISIONS.md`,
  `docs/DOMAIN.md`, `docs/ARCHITECTURE.md`, `docs/USECASES.md`,
  `docs/GLOSSARY.md`, `docs/ROADMAP.md`, `docs/GIT_WORKFLOW.md`.
- `docs/`와 `openapi/`는 context 저장소에서 동기화되는 파일이다. 이 저장소에서 직접
  수정하지 않는다. 변경이 필요하면 context 저장소에서 수정하고 동기화한다.
- 작업 트리의 `/context/`는 개발자가 편의상 둘 수 있는 로컬 checkout일 뿐이다. Git
  submodule 또는 core-api의 추적 대상에 추가하지 않는다.
- Web 계약은 `openapi/core-api.yaml`, Compute 호출 계약은 `openapi/compute-api.yaml`을
  기준으로 한다.

## 2. Feature-centric Gradle modules

- Feature는 `<feature>/{domain,port,application,adapter/<technology>}`로 구성한다.
- Gradle project path는 `:<feature>:<layer>` 또는
  `:<feature>:adapter:<technology>`를 사용하며 `projectDir` 재매핑을 만들지 않는다.
- 각 모듈은 표준 `src/main/kotlin`, `src/test/kotlin` source layout을 사용한다.
- 순수 공통 primitive는 `:shared:kernel`, 공통 기술 구현은
  `:shared:infrastructure`, 실행과 composition은 `:app`에 둔다.

## 3. Ports, services, and adapters

- Inbound use case와 Command/Query/Result는 `<feature>:port`에 기능 단위로 둔다.
  전역 `command/`, `query/`, `result/`, `usecase/` 디렉터리는 만들지 않는다.
- Outbound capability interface도 `<feature>:port`에 둔다. Command persistence는
  `{Domain}Store`, 독립적인 read-only 조회는 `{Domain}Reader`를 사용한다. Aggregate를
  검증하기 위한 command-side 조회는 Store 책임이다.
- Application service는 `<feature>:application`에서 inbound port를 구현하고 outbound
  port만 의존한다. concrete adapter, JPA, PostgreSQL, Web 기술을 직접 의존하지 않는다.
- HTTP adapter는 `<feature>:adapter:web`에 둔다. Controller는 resource 단위로 두고
  inbound port만 의존한다. Request/Response는 web DTO이며 domain/JPA entity를 직접
  노출하지 않는다.
- Persistence, Snowflake, Compute 등은 기술 또는 외부 시스템별 adapter 모듈로 분리한다.
  Adapter를 하나의 포괄 모듈로 만들지 않는다.
- 기본 의존 방향은 `domain ← port ← application ← adapter`이다. 다른 feature의
  application/service 구현체를 코드 재사용 목적으로 직접 의존하지 않는다.

## 4. Spring and identifiers

- 일반적인 application service와 adapter는 component scanning을 사용한다. `:app`은
  Spring Boot 진입점과 composition root이며, 명시적 생성이 필요한 객체만 `@Bean`으로
  등록한다.
- Domain은 typed ID만 소유하며 Snowflake 같은 생성 방식을 알지 않는다. Feature별 ID
  generator port는 feature port에, Snowflake 구현은 `:shared:infrastructure`, feature ID
  변환 adapter는 `<feature>:adapter:snowflake`에 둔다.

## 5. Gradle conventions

- 모든 Kotlin/JVM 모듈의 공통 설정과 테스트 의존성은 included build `build-logic`의
  `kotlin-common-conventions`를 사용한다.
- Domain은 `domain-conventions`로 framework 의존성 금지를 강제한다. Spring adapter는
  `spring-adapter-conventions`, Spring Boot 실행 모듈은
  `spring-boot-application-conventions`를 사용한다.
- Java toolchain, Kotlin compiler option, JUnit Platform 설정을 개별 module build script에
  반복하지 않는다. 버전과 공통 좌표는 `gradle/libs.versions.toml`에서 관리한다.
- JPA, AI SDK 등 기술 의존성은 공통 convention이 아니라 가장 좁은 technology adapter에
  둔다.
