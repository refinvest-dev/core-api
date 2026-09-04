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

## 5. Domain organization

- Domain은 Aggregate 중심으로 구성한다. Aggregate Root는 feature `domain` package 최상위에 둔다.
- Value Object, Policy, Domain Service, Event, Exception은 성격별 하위 package로 분리한다.
  독립적인 domain concept는 기본적으로 별도 파일에 두되, 구현에 강하게 결합된 private/helper type은
  같은 파일에 둘 수 있다.
- Aggregate Root가 아닌 Entity는 전역 `entity` directory에 모으지 않는다. 특정 Aggregate에
  강하게 결합된 Entity와 그 구성 타입은 해당 Aggregate 경계를 기준으로 하위 package에 둔다.
- 불필요하게 깊은 package hierarchy나 단순한 타입만을 위한 Aggregate 하위 package는 만들지 않는다.

## 6. Gradle conventions

- 모든 Kotlin/JVM 모듈의 공통 설정과 테스트 의존성은 included build `build-logic`의
  `kotlin-common-conventions`를 사용한다.
- Domain은 `domain-conventions`로 framework 의존성 금지를 강제한다. Spring adapter는
  `spring-adapter-conventions`, Spring Boot 실행 모듈은
  `spring-boot-application-conventions`를 사용한다.
- Java toolchain, Kotlin compiler option, JUnit Platform 설정을 개별 module build script에
  반복하지 않는다. 버전과 공통 좌표는 `gradle/libs.versions.toml`에서 관리한다.
- JPA, AI SDK 등 기술 의존성은 공통 convention이 아니라 가장 좁은 technology adapter에
  둔다.

## 7. Cross-repository integration

- `../integration`은 Core의 runtime dependency나 Git submodule이 아닌, sibling checkout을
  조립해 검증하는 local E2E harness다. Core source와 Gradle settings에 포함하지 않는다.
- Core↔Compute 호출 또는 OpenAPI snapshot에 영향을 주는 변경은 context 정본을 먼저
  확인하고, 구현 전후 `../integration/scripts/verify-contract-sync.ps1`을 실행한다.
- Core↔Compute 동작 변경은 integration의 분리된 DB와 non-production fixture로 smoke를
  검증한다. 로컬 credential, fixture 산출물, compatibility evidence는 각 저장소에
  커밋하지 않는다.
- integration harness의 기동 절차·환경 변수·실행 증적은 `../integration/README.md`와
  `../integration/AGENTS.md`를 정본으로 하며, 이 파일에는 중복해서 관리하지 않는다.
