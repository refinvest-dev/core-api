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
| 1c | 구현·검증 완료, PR 검토 대상 | `1258a2b`, R04 polling 응답 반영 전 lease 잠금·검증 |
| 1d 이후 | 미착수 | 다음 PR은 R03 Strategy 동시 version 추가와 기존 immutable version 보존 |

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
