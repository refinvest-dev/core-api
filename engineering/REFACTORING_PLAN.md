# Core API 아키텍처 리팩토링 실행 계획

이 문서는 Core가 소유하는 로컬 실행 목록이다. 제품 의미·Aggregate 경계·wire 계약은
[문서 정본 안내](../docs/README.md)의 context snapshot을 따른다. 구현 규칙은
[AGENTS.md](../AGENTS.md), 커밋·PR·머지 규칙은 [GIT_WORKFLOW.md](../docs/GIT_WORKFLOW.md)를 따른다.
이 문서의 작업 상태와 PR 링크는 작업을 완료할 때 갱신한다.

## 1. 목표와 변경 기준

기준은 ADR-018의 feature-centric Hexagonal 구조이며, 관련 업무 계약은 ADR-013,
ADR-019~020, ADR-047, ADR-061, ADR-063~070과 적용되는 Use Case다.

```text
Command -> Application -> Aggregate 생성/행위 -> AggregateStore
        -> PersistenceMapper -> JPA/JDBC 저장 모델

command-side load -> 저장 모델 조회 -> Mapper -> 검증된 Aggregate 복원
read-only query -> Reader -> read model -> Result -> web DTO
```

- Aggregate Root는 공통 AggregateRoot와 Identifier 기반 typed ID를 사용한다.
- 생성·복원·상태 전이가 불변식을 보장하고, 소속 Entity는 Root를 통해 업무 변경한다.
- Mapper는 persistence 모듈에 두며 I/O 없이 변환한다. 복원 시 새 업무 이벤트를 발생시키지 않는다.
- 조회용 projection과 command Aggregate 복원은 각각의 목적에 맞게 구현한다.
- CAS/잠금, idempotency, immutable membership, version pin, hash와 삭제 generation을 보존한다.
- NormalizationRun·TradingSourceArtifact는 정본상 ImportSession 소속이다. 저장 코드 분리를 위해
  독립 Aggregate로 승격하지 않는다. Account·Book·Session·LedgerRevision·DeletionRequest의
  Root 여부는 [Persistence 계약](../docs/TRADING_REVIEW_PERSISTENCE.md)의 matrix를 따른다.
- Trading Review의 JDBC를 우선 유지한다. JPA 전환은 별도 근거와 검증을 갖춘 작업으로 제안한다.
- 대용량 Revision/Result는 stream/batch로 처리한다. 저장 API를 단순화하려고 모든 row를 적재하지 않는다.
- schema/wire/hash 의미를 바꿔야 하면 context 정본을 먼저 변경·동기화한다.

## 2. 리뷰 발견 사항과 검증 상태

아래 항목은 코드·문서 대조 결과다. 동시성·장애 시나리오는 후속 PR에서 실제 회귀 테스트로 재현한다.
`코드 확인`은 운영 장애 재현이나 수정 완료를 뜻하지 않는다. 각 PR은 테스트 결과와 관련 계약을
기록하고, 재현 결과에 따라 수정 범위를 조정한다.

| ID | 대상 | 코드 확인 내용 | 후속 검증/목표 |
|---|---|---|---|
| R01 | Trading Review 접근 | grant redemption이 상위 Account/Book 삭제 상태를 검사하지 않음 | 상위 삭제 commit 직후 기존 토큰 거절, 다른 owner/scope 영향 없음 |
| R02 | Backtest capacity | monthly quota row에 active_count도 저장 | 이전 달 실행을 포함해 동시 한도 유지, terminal에서 정확히 한 번 해제 |
| R03 | Strategy 저장 | 전체 version collection 교체, optimistic version/command lock 부재 | 동시 version 추가 결과 모두 보존, 기존 immutable version 유지 |
| R04 | Backtest polling | Run 변경 뒤 dispatch claim token 검사 | 만료·교체된 lease의 응답이 Run/capacity/dispatch를 변경하지 않음 |
| R05 | Strategy 조회 | 정렬 없는 association의 lastOrNull로 최신 버전 선택 | 명시적 최신 기준과 동률 tie-break, 저장/조회 순서 독립 |
| R06 | Compute 호출 | Backtest/Asset 클라이언트에 명시적 connect/read timeout 부재 | 응답 지연/연결 실패의 bounded 종료와 retry, lease와 정합 |
| R07 | Trading Review domain | Root 공통 기반 누락, public Session 생성/copy로 상태 조합 가능 | 생성·복원·전이 불변식과 잘못된 상태 거절 |
| R08 | Persistence 매핑 | 변환 코드 분산·중복, Backtest 변환 내부의 결과 조회 | Aggregate별 Mapper, 완전한 복원, Mapper I/O 제거 |
| R09 | Trading Review 저장 | 여러 Store를 구현한 통합 adapter, SQL 중심 발행/삭제 상태 변경 | Aggregate별 adapter, 명시적 발행/삭제 모델과 원자적 저장 |
| R10 | Port/Application/Web | 포트의 파일 복호화·삭제 구현, 내부 HTTP status, scheduler의 구현체 호출 | 기술 구현 격리, 실패 의미와 HTTP 매핑 분리, inbound port 진입 |
| R11 | 삭제/보관 | persistence worker가 업무 절차 수행, object 작업이 DB transaction 안에 있음 | durable 단계별 처리, 짧은 DB transaction, 부분 실패 복구 |
| R12 | Feature 경계 | Backtest->Strategy/Subscription, Auth->Member domain 직접 참조 | feature port 계약으로 연동, 변경 모듈의 경계 검사 |
| R13 | Web/read model | domain failure 직접 응답, DTO에서 purge 상태 추정 | web DTO 변환, 실제 component 상태 기반 삭제 read model |
| R14 | Schema | Flyway와 Hibernate ddl-auto:update 병행 | 기존 모듈 migration 편입, 새 DB/기존 DB 검증 후 운영 validate 전환 |
| R15 | 운영 설정 | public health 상세 공개, 기본 Snowflake node-id=0 | health 공개 범위 제한, 다중 인스턴스 ID 설정·검증 |
| R16 | 검증 체계 | import 일부만 검사하는 경계 테스트, DB 조건부 테스트/캐시 | 변경 경계 검사 확대, 실제 PostgreSQL 및 integration 결과를 구분해 기록 |

주요 근거 파일:

- R01: `tradingreview/adapter/persistence/.../JdbcArtifactAccessGrants.kt`,
  `tradingreview/application/.../TradingReviewService.kt`
- R02/R04: `backtest/adapter/persistence/.../JpaBacktestQuotaStoreAdapter.kt`,
  `backtest/application/.../PollSubmittedBacktestsService.kt`,
  `backtest/adapter/persistence/.../JpaBacktestComputeDispatchStoreAdapter.kt`
- R03/R05: `strategy/adapter/persistence/.../JpaStrategyStoreAdapter.kt`,
  `StrategyJpaEntity.kt`, `JpaStrategyReaderAdapter.kt`
- R07~R13: `tradingreview/{domain,port,application,adapter}`, 각 feature의 persistence adapter
- R14~R16: `app/src/main/resources/application.yaml`, `build-logic`,
  `app/src/test/kotlin/com/refinvest/TradingReviewModuleBoundaryTest.kt`

## 3. 단계와 PR 단위

각 단계는 아래 순서로 진행한다. PR은 한 가지 목적과 독립 검증 범위를 갖고, 구현과 회귀 테스트를
함께 포함한다. 같은 feature에서 여러 계층이 함께 바뀌어도 동작 가능한 세로 단위로 묶는다.
큰 PR을 파일 종류만으로 기계적으로 나누지 않는다.

| 단계 | 권장 PR 단위/순서 | 완료 기준 |
|---|---|---|
| 0 | 로컬 규칙과 이 실행 목록 | 기준·소유권·검증 상태·후속 PR 순서 명문화 |
| 1a | R01 삭제 후 artifact 접근 차단 | 실제 DB에서 Account/Book 삭제 후 기존 grant 거절 |
| 1b | R02 월간 quota와 동시 capacity | 월 경계·동시 요청·rollback·중복 terminal 검증 |
| 1c | R04 polling lease와 상태 반영 | 느린 이전 worker와 새 claim 경합 검증 |
| 1d | R03 Strategy 동시 저장 | 동시 version 추가·rollback·기존 version 보존 검증 |
| 1e | R05 최신 버전 정렬 | 조회 순서와 시각 동률에 대한 deterministic 선택 |
| 1f | R06 HTTP timeout | 지연/실패 테스트, retry 및 lease 정합 |
| 2a | Member/Subscription 기준 구현 | Aggregate Store/Reader/Mapper와 복원 패턴 확립 |
| 2b | Auth 저장·행위/매핑 | refresh 회전·폐기·replay·경합 원자성 보존 |
| 3a | Strategy Aggregate 저장/조회 정리 | R03 수정 유지, immutable child와 Mapper/read model 정리 |
| 3b | Backtest Aggregate 저장/복원 정리 | Run/Result 완전한 복원, R02/R04 수정 유지 |
| 3c | R12 feature port 경계 | 직접 domain 참조 제거, 실제 의존성 검사 |
| 4a | TradingAccount/TradingBook | Root/typed ID/Mapper/Store 분리, ownership 보존 |
| 4b | TradingImportSession 및 소속 모델 | artifact/run 행위·입력 pin·재시도·원자적 admission 보존 |
| 4c | LedgerRevision 및 publication | immutable 발행 모델, membership/hash/generation gate, bounded 저장 |
| 4d | DeletionRequest | 명시적 상태 모델, scope/checklist/실패·완료 규칙 |
| 5a | Dispatch/polling/result/ACK 진입점 | inbound use case, port의 기술 구현 이동, ACK commit 순서 보존 |
| 5b | 삭제/retention 실행 흐름 | application orchestration, component별 durable 처리와 복구 |
| 5c | 실패/조회/web 경계 | 의미 기반 실패, web DTO, 정확한 purge read model |
| 6a | 기존 모듈 schema migration | 새 DB·기존 DB 업그레이드, 데이터 보존, validate 기동 |
| 6b | 운영 설정 | health/node ID 등 설정 검증, 개발·운영 동작 확인 |
| 6c | 전체 경계/회귀/integration | 미해결 목록 점검, PostgreSQL/E2E·계약 검증 결과 정리 |

새롭게 확인한 긴급 동작 문제는 근거와 의존성을 기록해 우선 처리한다. 구조 작업 때문에 필요한
schema 변경은 해당 단계에 포함하며, 6a까지 미뤄서 중간 commit의 기동을 깨뜨리지 않는다.
각 구조 단계에서 해당 모듈의 경계 검사를 함께 추가한다. 6c는 전체 누락 검증이다.

## 4. 검증과 리뷰 기준

- 동작 수정은 수정 전 실패와 수정 후 성공을 확인하는 회귀 테스트를 포함한다.
- Aggregate 테스트는 불법 생성·복원·전이, 소속 Entity 불변식과 immutable pin을 검증한다.
- Mapper 테스트는 전체 Aggregate round-trip과 nullable 상태, child 관계/order, version 보존을 확인한다.
  구현 대입문을 그대로 반복하는 테스트는 만들지 않는다.
- PostgreSQL locking/CAS/constraint/migration은 실제 분리된 테스트 DB에서 검증한다.
  H2나 mock 결과를 PostgreSQL 검증 완료로 기록하지 않는다.
- 테스트의 UP-TO-DATE/cache, skip과 실제 실행을 PR에 구분한다. 필요한 DB 환경이 없으면
  미검증 시나리오를 명시하고 후속 검증 없이 해당 완료 조건을 충족했다고 표시하지 않는다.
- Core↔Compute 변경에는 AGENTS.md §7의 계약 검사와 integration smoke를 적용한다.
  실행 방법·환경·증적 위치는 integration 정본을 참조한다.
- 새 snapshot은 context에서 동기화하고 byte-level 검사한다. 고정 vector의 기대값을 Core에서 수정하지 않는다.
- API/schema/hash/version 의미가 바뀌지 않는 리팩토링도 기존 wire와 저장 데이터 호환성을 확인한다.
- 각 PR에 관련 ADR/Use Case, 변경 이유, 검증 결과, Determinism 영향과 material limitation을 기록한다.
- GitHub PR 머지는 사람이 수행한다. 선행 PR 미병합 상태에서 다음 작업을 이어갈 경우
  stacked PR의 base/dependency를 명시하고, 머지된 변경을 중복 구현하지 않는다.

## 5. 진행 기록

| 단계 | 상태 | 커밋/PR 및 검증 |
|---|---|---|
| 0 | 완료·머지 | [PR #82](https://github.com/refinvest-dev/core-api/pull/82), 규칙과 실행 계획 |
| 1a | 완료·머지 | [PR #83](https://github.com/refinvest-dev/core-api/pull/83), R01 발급/사용의 상위 상태·소유권 검증 |
| 1b | 완료·머지 | [PR #84](https://github.com/refinvest-dev/core-api/pull/84), 월간 사용량과 실행 ID별 활성 예약 분리 |
| 1c | 완료·머지 | [PR #85](https://github.com/refinvest-dev/core-api/pull/85), R04 polling 응답 반영 전 lease 잠금·검증 |
| 1d | 완료·머지 | [PR #86](https://github.com/refinvest-dev/core-api/pull/86), R03 Strategy 잠금과 immutable version append-only 저장 |
| 1e | 완료·머지 | [PR #87](https://github.com/refinvest-dev/core-api/pull/87), R05 생성 시각·숫자 ID 기준 version 정렬과 최신 선택 |
| 1f | 완료·머지 | [PR #88](https://github.com/refinvest-dev/core-api/pull/88), R06 Backtest/Asset HTTP timeout과 retry·lease 정합 |
| 2a | 완료·머지 | [PR #89](https://github.com/refinvest-dev/core-api/pull/89), Member/Subscription Mapper와 구독 command 잠금 |
| 2b | 구현·검증 완료, PR 검토 대상 | [PR #90](https://github.com/refinvest-dev/core-api/pull/90), `67798c2`·`7ab5d1e`, Auth Aggregate·Mapper와 refresh family 경합 수정 |
| 3a 이후 | 미착수 | 다음 PR은 Strategy 저장·복원 Mapper와 read model 정리, R03/R05 동시 저장·최신 선택 규칙 유지 |

### 2b 검증 기록과 적용 순서

- ADR-018/019와 `SocialLogin`·`RefreshSession`·`Logout`을 기준으로 Auth Aggregate를 정리했다.
  SocialIdentity는 provider/subject 자연키의 typed ID, RefreshSession은 기존 UUID JTI의 typed ID를
  사용하며 공통 AggregateRoot를 상속한다. 공개 생성자·copy 대신 생성·복원·행위를 구분하고
  잘못된 수명·회전 상태, 다른 member/family 또는 같은 JTI의 교체를 거절한다. Domain에서
  ID나 현재 시각을 생성하지 않으며 저장된 provider/subject·JTI·family를 재발급하지 않는다.
- 명시적인 두 PersistenceMapper가 전체 상태를 변환한다. SocialIdentity의 DB surrogate ID는
  persistence 내부에 남기며 typed 자연키로 Aggregate를 조회한다. Application은 Aggregate가 만든
  검증된 회전 모델을 Store로 전달하고 family 폐기도 요청 모델로 묶는다. Mapper는 I/O·시계·ID
  생성과 업무 행위를 수행하지 않는다. 현재 Member role의 정본 조회와 provider별 identity
  유일성을 유지하며, Auth의 기존 Member role 직접 타입 참조 정리는 3c에 남긴다.
- 수정 전 실제 PostgreSQL에서 회전 transaction이 교체 credential을 저장하는 동안 로그아웃하면
  family 폐기가 교체 credential을 놓쳐 활성 row 1개가 남는 회귀 실패를 재현했다.
- V12의 `refresh_session_family_locks`는 별도 domain Aggregate가 아닌 원자적 family capability의
  기술 잠금·폐기 표식이다. family 사용 시 row를 생성해 최초 저장·회전·폐기를 같은 잠금으로
  직렬화하고, 폐기된 빈 family에도 새 credential을 추가하지 않는다. 회전은 잠금 대기 후 JPA의
  관리 중인 세션도 refresh해 expected 상태를 검사하며 JTI 충돌은 overwrite 대신 실패시킨다.
  family 폐기는 전체 이력을 적재하지 않고 bulk 갱신한다.
- PostgreSQL Auth 테스트 13개로 회전/로그아웃 양방향 경합, 동일 credential의 중복 refresh와
  replay에 따른 winner family 폐기, 최초 저장/폐기 경합, 다른 family 격리, 충돌·rollback과
  stale snapshot을 검증했다. 별도 물리 연결과 실제 DB 잠금 대기를 사용한다. 동시 최초 소셜
  로그인은 identity 하나만 commit하며 패한 요청의 Member/Subscription이 함께 rollback된다.
- 기존 Auth schema에 V12를 적용해 모든 기존 session/identity row 보존, Flyway 재실행 0건과
  Auth entity의 Hibernate validate를 확인했다. 기존 전체 Flyway 경로의 migration 12개 적용도
  실제 PostgreSQL에서 통과했다. Core 전체 schema 편입·운영 validate 전환은 6a에 남아 있다.
- 검증 합계는 domain 8개·Mapper 8개·application 18개·security 3개·app 85개로 총 122개 통과다.
  실패·오류·테스트 skip은 0개다. 앞선 단위 테스트 37개 결과를 유지하고, H2의 native UUID
  반환형 문제를 JPA entity 잠금 조회로 수정한 뒤 app 85개를 실제 재실행했다. app에는 PostgreSQL
  Auth Store 13개·Auth migration 1개·Member/Subscription 8개·기존 Flyway 1개, 총 23개의 실제 DB
  검증과 로그인·쿠키·CSRF·refresh·로그아웃 HTTP 회귀가 포함된다. 실패한 중간 실행을 통과로
  세지 않으며 컴파일의 UP-TO-DATE와 테스트 실행을 구분한다.
- 소스 경계와 실제 Gradle project/기술 의존성 검사에 Auth를 추가했다. 실행 진입점은
  `verifyAggregateModuleBoundaries`이며 기존 `verifyMemberSubscriptionBoundaries`도 alias로 유지한다.
  security adapter의 사용하지 않는 application 의존성을 제거했다.
- 적용 순서는 Auth 쓰기(로그인·refresh·로그아웃) 중지 → 기존 transaction 종료 → V12 적용 →
  모든 Auth writer의 수정 코드 교체 → 쓰기 재개다. 이전 writer와의 혼합 실행은 보호 범위에
  포함하지 않는다. V12는 기존 Auth 데이터를 변경하지 않으며 코드 rollback 시에도 새 테이블과
  Flyway 이력을 삭제하지 않는다. 이전 코드는 이번 경합 수정을 포함하지 않으므로 쓰기를 차단한
  상태에서 복구 코드를 검증하고 재개한다.
- API/hash·계산 입력 변경과 계산 결정론 영향은 없다. Core↔Compute 호출 변경이 없어 해당 E2E는
  재실행하지 않았다. 임시 schema·연결 풀을 정리했으며 로컬 credential·fixture·로그는 커밋하지
  않는다. Aggregate/Mapper 정리(`67798c2`)와 family 경합·migration·회귀 검증(`7ab5d1e`)을 나눠
  커밋했다. 다음 단계는 기존 R03/R05 수정을 보존하는 3a Strategy 매핑·조회 정리다.

### 2a 검증 기록과 적용 기준

- 기존 Member/Subscription은 이미 `AggregateRoot<MemberId>`와 private 생성자·`create/restore`를
  사용하고 있었다. 이번 단계는 이를 유지하면서 순수 `MemberPersistenceMapper`와
  `SubscriptionPersistenceMapper`에 domain ↔ JPA 변환을 모았다. Store는 Aggregate를 받고,
  단순 Aggregate 조회인 Reader와 command Store는 같은 복원 Mapper를 사용한다.
- `MemberRole`은 성격별 `valueobject` package로 이동했다. 신규 회원의 MEMBER 기본값과 DB에
  저장된 ADMIN 역할 복원을 구분하며, Auth의 직접 domain 타입 참조 정리는 예정된 3c에서 다룬다.
- 실제 PostgreSQL에서 PRO 업그레이드 후 기본 구독을 재생성하면 FREE로 바뀌는 회귀 실패를
  재현했다. 수정 후 생성 서비스는 Store로 조회한 기존 Aggregate를 그대로 반환한다.
- 구독 command는 회원 row를 잠근 뒤 조회·변경·저장한다. 구독 row가 없는 최초 생성도 같은
  회원 기준으로 직렬화하며 독립 Reader는 잠그지 않는다. command 조회는 활성 transaction이
  필요하고 조회→행위→저장은 같은 transaction에서 실행한다. 모든 구독 writer에 수정 코드를
  적용해야 이 규칙을 보장하며, 이전 writer와의 혼합 실행은 보호 범위에 포함하지 않는다.
- `cleanTest` 후 Mapper 4개·Member application 1개·Subscription application 6개·Auth application
  11개·app 69개, 총 91개를 실제 실행해 통과했다. 실패·오류·skip은 0개다. 컴파일 일부는
  UP-TO-DATE였으나 테스트 결과는 재사용하지 않았다.
- app의 PostgreSQL 테스트 8개로 회원/기본 구독 저장과 ADMIN 복원, PRO 재생성, 누락 구독
  업그레이드, 최초 생성↔업그레이드 양방향 경합, Reader/다른 회원 격리, command transaction
  경계와 회원·구독/업그레이드 rollback을 검증했다. 경합은 별도 물리 연결과 실제 잠금 대기로
  확인하며 전용 임시 schema를 정리했다. 기존 PostgreSQL Flyway 테스트도 실제 실행했다.
  Member/Subscription의 기존 schema를 Flyway로 편입하는 작업은 6a에 남아 있다.
- 소스 경계 검사 2개와 실제 Gradle project/기술 의존성 검사
  `verifyMemberSubscriptionBoundaries`를 추가하고 `:app:test`에 연결했다.
- 이번 변경은 API/schema/hash·계산 입력과 계산 결정론에 영향을 주지 않는다. Core↔Compute 호출
  변경이 없어 해당 E2E는 재실행하지 않았다. Windows Gradle 소켓 오류로 시작되지 않은 실행과
  fixture 준비 중 실패한 실행은 위 통과 결과에 포함하지 않았다. 로컬 credential·fixture·로그는
  커밋하지 않는다.
- 구현은 Member 기준 정리(`2acf644`)와 Subscription 매핑·동시성/회귀 검증(`3c2c4be`)으로
  나누었다. 다음 단계는 2b이며, refresh 회전·폐기·replay 의미와 경합 원자성을 보존하면서 Auth
  Aggregate 행위와 persistence 매핑을 정리한다.

### 1f 검증 기록과 운영 설정

- 수정 전 production HTTP constructor를 실제 지연 서버에 연결해, 250ms 설정에도 요청이 3초 검증
  경계까지 반환하지 못하는 회귀 실패를 재현했다. 수정 후 헤더·본문·계속 전달되는 본문과 TLS
  handshake 지연, 연결 거절을 실제 소켓으로 검증했다.
- 공통 기술 구현 `HttpClientTimeouts`는 `shared:infrastructure`에 두고 Backtest 제출·polling 및 Asset
  조회 adapter에 적용했다. 설정은 `refinvest.compute.connect-timeout` / `read-timeout`이며 환경 변수
  `COMPUTE_CONNECT_TIMEOUT=PT2S`, `COMPUTE_READ_TIMEOUT=PT20S`가 기본값이다.
- 두 timeout은 양수여야 하며 Backtest는 합이 dispatch/poll lease 60초 미만이어야 기동한다.
  application과 HTTP adapter가 같은 port의 lease 값을 참조한다. HTTP I/O는 DB transaction 밖에서 수행한다.
- 실제 PostgreSQL에서 응답 본문 timeout 뒤 동일한 저장 key·요청으로 제출을 재시도해 acceptance를
  기록하고, quota 재소비 없이 capacity를 유지함을 확인했다. polling timeout도 Run·Result·capacity를
  변경하지 않고 10초 뒤 재조회하며, 정상 terminal 완료 뒤 capacity를 해제한다.
- 관련 결과는 공통 3개·Backtest HTTP 10개·Asset HTTP 5개·Backtest application 29개·persistence 27개·
  app 59개로 133개 통과, 실패·오류·skip 0개다. 앞선 실행에서 통과한 47개는 재사용됐고, 최종 실행에서
  persistence 27개와 app 59개를 실제 실행했다. 실제 PG lease/timeout 11개·quota 9개와 app의 실제 PG
  migration을 포함한다. app의 실제 지연 HTTP 테스트 7개에는 JUnit 실행 목록에서 확인한 본문 진행 기한도 포함한다.
- 로컬 Gradle 컴파일 정체와 DB `Bad address: connect` 실패 실행은 성공으로 세지 않았다.
  검증용 compiler 프로세스를 격리하고 heap을 조정했다. PG fixture에는 작은 연결 풀을 적용했으며,
  quota 경합은 작업자 수만큼 물리 연결을 미리 확보해 동시 transaction 검증을 유지한다.
- 실제 Core↔Compute `run-api-smoke-ci.ps1`가 정상 Backtest·재실행·계산 실패·다른 사용자 조회 차단을
  통과했다. 추가 Asset 검증은 처음 쿠키 미전달로 401을 받았고, 쿠키 세션으로 고친 별도 재실행에서
  `/assets`와 QQQ availability·snapshot metadata를 검증해 통과했다. 전용 서비스·임시 환경 파일을 정리했다.
- 수정 전후 계약 snapshot, 문서 snapshot 24개와 `git diff --check`가 통과했다. credential·fixture·
  로그·heap dump는 커밋하지 않는다. wire/schema/hash·계산 입력 변경은 없으며 계산 결정론 영향은 없다.

### 1e 검증 기록

- 정본은 version이 없는 경우의 `latestVersionId=null`을 명시하며, 상세 version 배열의 정렬과
  생성 시각 동률의 선택 기준은 별도로 지정하지 않는다. Core read model에 `(createdAt, 숫자 ID)`
  오름차순과 마지막 version을 최신으로 선택하는 기준을 명시했다.
- 수정 전 회귀 테스트에서 같은 시각의 version이 `[20, 300, 9, 10]` 대신 `[20, 300, 10, 9]`로
  반환되는 실패를 재현했다. 기존 `lastOrNull` 선택은 이 collection 순서에 의존했다.
- 상세·목록 Reader의 공통 변환에서 정렬하며, 입력 persistence collection은 변경하지 않는다.
  ID가 더 커도 생성 시각이 오래된 version은 최신으로 선택하지 않는다.
- 조회 테스트 3개는 네 version의 24가지 순열, 숫자 `9`/`10`의 시각 동률, 빈 Strategy와 부재를 검증한다.
  실제 PostgreSQL 테스트 14개는 기존 동시 저장 회귀 11개와 재조회·사용자 격리·새 append 관측 3개를
  포함하며, version/condition row와 DSL 조건 순서 보존도 확인한다. 임시 schema는 테스트가 정리한다.
- application 14개와 app 52개를 포함해 관련 테스트 83개가 실제 실행돼 통과했고 실패·오류·skip은 0개다.
  HTTP 검증은 app의 H2·실제 서버에서 상세·목록의 최신 ID 일치를 확인하며, PostgreSQL 조회 검증과
  구분한다. app에는 기존 HTTP/모듈 경계 및 실제 PostgreSQL migration 검증도 포함된다.
- 새 HTTP fixture의 기존 ID 재사용으로 생긴 간섭을 전용 ID와 자체 정리로 해소한 뒤 app 52개를
  다시 실행해 통과했다. 중간 실패 실행을 성공으로 세지 않는다.
- 계약 snapshot, 문서 snapshot 24개와 `git diff --check`가 통과했다. 로컬 환경·fixture·실행 로그는
  커밋하지 않는다. wire schema·저장 데이터·Compute 요청·계산 hash 변경은 없으며 계산 결정론 영향은 없다.

### 1d 검증 기록

- 수정 전 실제 PostgreSQL·JPA 테스트에서 두 Aggregate snapshot이 각각 version을 추가하면
  `[11, 12, 13]` 대신 `[11, 13]`만 남는 유실을 재현했다.
- command-side 조회는 기존 transaction을 필수로 요구하고, version collection을 읽기 전에 Strategy
  row를 잠근다. 저장도 같은 Root를 잠근 뒤 새 version/condition만 `persist`하며, collection 교체와
  cascade merge/delete·orphan removal 경로를 제거했다. 누락된 과거 version도 삭제하지 않는다.
- 기존 version의 정의와 Root metadata 교체, 다른 Strategy의 version ID 재사용을 거절한다.
  Aggregate도 중복 version ID 추가를 거절한다. 같은 Aggregate 재저장은 기존 row를 다시 쓰지 않는다.
- Clock의 nanosecond와 PostgreSQL의 microsecond 저장 정밀도 차이로 같은 Aggregate 재저장이 거절되는
  추가 회귀를 확인하고 비교 정밀도를 보완했다. 저장된 과거 시각이나 API 시각을 다시 쓰지 않는다.
- 관련 테스트 결과 합계는 domain 13개·application 14개·PostgreSQL Store 11개·app 51개로
  89개 통과, 실패·오류·skip 0개다. domain/application은 최초 실행 후 후속 실행에서 재사용됐고,
  PostgreSQL 11개와 app 51개는 최종 수정 후 실제 실행했다. app에는 실제 PostgreSQL migration과
  기존 HTTP/모듈 경계 검증이 포함된다.
- PostgreSQL 테스트는 실제 잠금 대기를 `pg_stat_activity`로 확인한다. 동시 정의 둘 다 보존,
  rollback 뒤 대기 writer 진행, 기존 version/condition row와 참조 보존, 다른 Root의 독립 진행,
  definition 충돌·소유권·transaction 경계 및 정밀도를 검증하고 자체 임시 schema를 정리한다.
- Windows Gradle/Kotlin daemon 소켓 오류와 metrics HTTP 테스트 3개의 `Bad address: listen` 실패는
  성공으로 세지 않았다. 기본 JVM 설정으로 app을 다시 실행해 51개 모두 통과했다.
- 첫 HTTP smoke는 Strategy 생성·version 저장 뒤 Backtest POST의 로컬 연결 오류로 중단됐다.
  해당 시도는 성공으로 세지 않으며 전용 프로세스·임시 환경 파일을 정리했다.
- 새 프로세스의 `run-api-smoke-ci.ps1` 재실행은 실제 PostgreSQL·Core↔Compute HTTP에서 Strategy 생성·
  version 정의, 정상 Backtest 완료·재실행·계산 실패·다른 사용자 조회 차단을 검증하고 통과했다.
  기동한 전용 프로세스·임시 환경 파일은 정리했고 로컬 credential·fixture·실행 로그는 커밋하지 않는다.
- 수정 전후 계약 snapshot 검사, 문서 snapshot 24개 검사와 `git diff --check`도 통과했다.
- API/schema/hash·계산 의미 변경은 없으며 결정론 영향은 없다. 배포 시 모든 Strategy 쓰기 인스턴스에
  새 저장 경로를 적용해야 동시 저장 보장을 유지한다. Mapper/Reader 전반 정리는 3a에서 이어간다.

### 1c 검증 기록

- 수정 전 실제 PostgreSQL·JPA 경로에서 lease를 새 워커가 가져간 뒤 이전 404 응답이 Run을
  `FAILED`로 바꾸는 회귀 테스트 실패를 확인했다.
- 응답 반영 transaction의 첫 단계에서 dispatch row를 잠그고 현재 상태·token·Compute job과 lease
  만료를 검증한다. 잠금을 기다리는 동안 만료될 수 있으므로 시각은 잠금 획득 뒤에 읽는다.
- 검증은 기존 application transaction을 필수로 요구하며, Run·Result·capacity·dispatch commit까지
  잠금을 유지한다. 보호된 처리 중 deadline을 지나도 다른 워커는 commit 뒤 상태를 다시 확인한다.
  Compute HTTP는 transaction 밖에서 수행한다.
- 관련 persistence/application/app 검증 105개가 통과했다. PostgreSQL lease 테스트 9개는 만료/교체된
  7가지 응답, 오래된 워커와 정상 완료의 경합, commit까지의 잠금, 잠금 대기 중 만료, 전체 rollback과
  만료/교체 및 rollback 응답의 실패 observation 미발행을 확인했다. 잠금 대기는 `pg_stat_activity`로 확인한다.
- PostgreSQL 테스트의 임시 schema와 `@Lob` 대형 객체 정리를 보완하고 lease 테스트 9개를 다시
  실행해 통과했다. 로컬 환경·fixture·실행 로그는 커밋하지 않는다.
- HTTP smoke의 첫 실행은 PowerShell 전체 스트림 기록이 Python 경고를 오류로 취급해 사전검사에서
  중단됐다. 해당 실행은 성공으로 표시하지 않으며, 오류 스트림을 보존해 재실행한다.
- 재실행한 `run-api-smoke-ci.ps1`는 실제 Core↔Compute HTTP에서 정상 실행·재실행·계산 실패·다른
  사용자 조회 차단을 검증하고 통과했다. 수정 전후 계약 snapshot과 문서 snapshot 검사도 통과했다.
- API/schema/hash 의미 변경은 없으며 결정론 영향은 없다. 배포 시 모든 polling 인스턴스에 수정 코드가
  적용돼야 이 검증 경계를 보장할 수 있다.

### 1b 검증 기록과 배포

- 수정 전 실제 PostgreSQL에서 월이 바뀌면 동시 한도가 풀리는 회귀 테스트가 실패했다.
- 수정 후 persistence/application/app 테스트 96개가 통과했다. PostgreSQL quota 테스트 9개와
  Flyway migration 테스트도 실제 실행했으며 실패·오류·skip은 0개다.
- UTC 자정에서 바뀌는 Clock으로 접수 시각과 quota month의 일치를 추가 검증하고 application
  테스트를 재실행해 통과했다. 월간 거절, 최초 동시 접수, 월을 넘는 동시 예약, 중복 해제,
  사용자 격리, 접수/종료 rollback과 기존 데이터 backfill·불명 owner 실패를 검증했다.
- Windows Gradle 소켓 오류로 시작되지 않은 실행은 성공으로 세지 않았다. 숨김 프로세스 실행으로
  테스트를 완료했다.
- 계약 snapshot 검사와 실제 Core↔Compute `run-api-smoke-ci.ps1`를 통과했다. 정상 실행·재실행·계산
  실패·다른 사용자 조회 거절을 확인했다. 로컬 credential/fixture/증적은 커밋하지 않는다.
- `run-durable-dispatch-reliability.ps1`는 기존 contract 검사의 strict-mode
  `analyticsInputSupplement` 속성 오류로 서비스 기동 전 중단됐다. 이 장애 복구 harness는 검증 완료로
  표시하지 않으며, 통과한 일반 API smoke와 구분한다.
- V11은 기존 월간 사용량과 Run/Result를 보존하며 활성 Run을 새 예약으로 복원한다. 적용 시 이전 Core의
  접수·dispatch/polling을 중지하고 혼합 버전 기동을 피해야 한다. 상세 순서와 복구 제약은
  [Backtest capacity 전환 절차](BACKTEST_CAPACITY_ROLLOUT.md)를 따른다.
- API/Compute 계약과 계산 hash 의미는 변경하지 않았으며 결정론 영향은 없다.

### 1a 검증 기록

- `TR-ACC-DELETE-006/007`의 즉시 접근 차단·삭제 scope 격리 assertion을 회귀 테스트에 적용했다.
  삭제 워커가 실행되기 전 `REQUESTED` 상태에서 기존 토큰 사용과 새 발급을 거절한다.
- 수정 전 실제 PostgreSQL에서 새 회귀 테스트 2개가 모두 실패했다. 삭제 후 토큰 사용이
  원본 스트림을 반환하는 문제를 재현했다.
- 수정 후 `:tradingreview:adapter:persistence:test --tests '*PostgresTradingReviewStoreTest'`를
  실행해 26개 통과, 실패/오류/skip 0개를 확인했다. 대상 외 Book/Account/owner의 토큰과
  정상 토큰의 single-read 동작도 검증했다.
- 수정 전후 integration 계약 snapshot 검사를 통과했다. 합성 fixture와 분리된 integration DB의
  `test-trading-normalization-runtime-contract.ps1`도 통과했다. 로컬 증적은 integration에만 보관한다.
- API/schema/hash 의미 변경은 없으며 결정론 영향은 없다. 전체 삭제 workflow의 나머지 assertion은
  4d/5b에서 검증한다.

초기 리뷰에서 전체 `gradlew test` task는 성공했다. 당시 결과는 대부분 UP-TO-DATE였으며
리포트 합계 248개, 실패 0개, skipped 1개였다. PostgreSQL Store의 이전 실행 결과도 재사용됐고
Flyway migration 테스트는 건너뛰었다. 이 기록은 후속 변경의 실제 DB 검증을 대신하지 않는다.
