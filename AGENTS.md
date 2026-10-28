# AGENTS.md — core-api local conventions

이 문서는 core-api 작업 트리의 로컬 규칙만 정의한다. 제품 도메인, ADR, API 계약의 정본은
외부 `context` 저장소이며, 이 저장소에는 동기화된 스냅샷만 둔다.

## 1. 문서와 계약

- 작업 전 다음 순서로 읽는다: `docs/AI_AGENT.md`, `docs/DECISIONS.md`,
  `docs/DOMAIN.md`, `docs/ARCHITECTURE.md`, `docs/USECASES.md`,
  `docs/GLOSSARY.md`, `docs/ROADMAP.md`, `docs/GIT_WORKFLOW.md`.
- Trading Review 작업은 `docs/AI_AGENT.md`의 라우팅에 따라 관련 `docs/TRADING_*.md`와
  ADR을 추가로 읽는다.
- `docs/README.md`에 명시된 문서와 `openapi/`는 context 저장소에서 동기화되는
  파일이다. 이 저장소에서 직접 수정하지 않는다. 변경이 필요하면 context 저장소에서 수정하고 동기화한다.
  `docs/README.md`와 `docs/GIT_WORKFLOW.md`는 Core가 소유하는 로컬 문서다.
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
- Aggregate Root는 `shared:kernel`의 `AggregateRoot<ID>`를 상속하고, ID는 공통
  `Identifier`를 구현한 typed ID를 사용한다. 소속 Entity는 정본의 Aggregate 경계를 따른다.
- 생성·복원·상태 변경에서 불변식을 검증한다. 생성자, setter 또는 `copy`를 통해 허용되지 않은
  상태를 만들 수 없도록 한다. 복원은 새로운 업무 행위나 Domain Event를 재실행하지 않는다.
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
- Context 문서 snapshot을 갱신한 뒤에는 `scripts/verify-context-doc-sync.ps1`을 실행해
  정본과 byte-level 내용(개행 정규화 제외)이 일치하는지 확인한다.
- Core↔Compute 동작 변경은 integration의 분리된 DB와 non-production fixture로 smoke를
  검증한다. 로컬 credential, fixture 산출물, compatibility evidence는 각 저장소에
  커밋하지 않는다.
- integration harness의 기동 절차·환경 변수·실행 증적은 `../integration/README.md`와
  `../integration/AGENTS.md`를 정본으로 하며, 이 파일에는 중복해서 관리하지 않는다.

## 8. Aggregate persistence and mapping

- Application은 Command를 받아 Aggregate를 생성·변경하고 `{Aggregate}Store`에 저장한다.
  Command-side 저장과 복원은 Aggregate 단위로 표현하며, 하위 Entity의 업무 변경은 Root의
  행위를 통해 수행한다. 테이블 개수나 ORM 연관관계가 Aggregate 경계를 결정하지 않는다.
- Persistence adapter는 Aggregate 또는 독립적인 persistence capability별로 분리한다.
  여러 Aggregate의 Store와 idempotency 같은 기술 capability를 하나의 어댑터에 모으지 않는다.
  Spring Data 인터페이스는 기존 `{Aggregate}JpaStore` 명명을 유지한다.
- Domain과 persistence 모델의 양방향 변환은 persistence adapter 안의 명시적인 Mapper에 둔다.
  기본 이름은 `{Aggregate}PersistenceMapper`이며, 소속 Entity/Value Object 변환을 함께 구성한다.
  Store와 Reader에서 동일한 도메인 복원이 필요하면 같은 매핑을 공유한다. 독립적인 조회 projection은
  해당 read model에 직접 매핑할 수 있다.
- Mapper는 전달받은 모델을 변환한다. Repository 조회, 외부 I/O, ID 생성, 현재 시각 결정과
  업무 상태 전이를 수행하지 않는다. 필요한 row/entity는 adapter가 조회한 뒤 Mapper에 전달한다.
- Domain은 JPA/JDBC/JSON converter를 알지 않는다. Persistence 모델을 만들 때 필드를 컬럼으로
  분해하는 책임은 adapter에 둔다. ID·owner·page 같은 조회 조건은 Store/Reader 인자로 사용할 수 있다.
- 일반적인 상태 변경을 개별 컬럼 갱신 포트로 노출하지 않는다. quota 예약, lease claim,
  refresh 회전, publication처럼 원자성이 필요한 연산은 명시적 capability로 정의하고
  관련 상태 모델과 expected version/lease token/attempt/deletion generation 등 필요한 조건을 묶어
  검증한다. stale 요청은 상태·capacity·latest pointer를 변경하지 않는다.
- Mutable Aggregate 저장은 optimistic locking 또는 동등한 잠금/CAS로 변경 유실을 막는다.
  Immutable Revision/Result의 append-only 저장, idempotency와 deterministic uniqueness를 보존한다.
- Aggregate 저장에 JPA 전환이나 모든 하위 row의 메모리 적재를 전제하지 않는다. 대용량 ledger와
  result는 검증된 발행 모델과 stream/batch 저장을 사용하고, 논리 Aggregate 경계와 publication
  원자성을 지킨다. 외부 HTTP/object storage 작업은 DB transaction 밖에서 수행한다.
- Port는 inbound/outbound 계약과 해당 모델을 소유한다. 파일·암호화·HTTP 구현은 technology adapter에,
  HTTP status 매핑은 web adapter에 둔다. Scheduler와 Controller는 inbound port를 호출한다.
