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
| 4b-1 | TradingImportSession 및 소속 Artifact | Root·typed ID·Mapper·Aggregate 저장, 업로드·삭제·retention CAS 보존 |
| 4b-2 | Session 소속 NormalizationRun | 소속 모델·Mapper·입력 pin·재시도·원자적 admission 보존 |
| 4c | LedgerRevision 및 publication | immutable 발행 모델, membership/hash/generation gate, bounded 저장 |
| 4d-1 | DeletionRequest 모델·저장 경계 | Root/typed ID·scope/checklist 정의·순수 Mapper·Aggregate 단위 요청 저장/복원 |
| 4d-2 | DeletionRequest 상태 전이·진행 증명 | worker 전이 CAS·component별 durable checkpoint·실패/완료 규칙 연결 |
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
| 2b | 완료·머지 | [PR #90](https://github.com/refinvest-dev/core-api/pull/90), Auth Aggregate·Mapper와 refresh family 경합 수정 |
| 3a | 완료·머지 | [PR #91](https://github.com/refinvest-dev/core-api/pull/91), `a824c26`·`270146d`, Strategy 복원·불변성과 저장/조회 Mapper 정리 |
| 3b | 완료·머지 | [PR #92](https://github.com/refinvest-dev/core-api/pull/92), `7483529`·`c5d363b`, Backtest Run/Result 복원·불변성과 저장/조회 Mapper 정리, R02/R04 회귀 검증 |
| 3c | 완료·머지 | [PR #93](https://github.com/refinvest-dev/core-api/pull/93), `ad3af75`·`36db90d`, R12 feature port 계약과 실제 classpath에서 타 feature domain 참조 차단 |
| 4a | 완료·머지 | [PR #94](https://github.com/refinvest-dev/core-api/pull/94), `96a1a6c`·`47d85c5`·`12aba18`, Account/Book Root·Mapper·Store/Reader와 삭제 cascade fence |
| 4b-1 | 완료·머지 | [PR #95](https://github.com/refinvest-dev/core-api/pull/95), `5771f96`·`5f66b63`·`f5ac6df`, Session Root·Artifact 슬롯 저장·순수 Mapper·retention CAS와 삭제 replay·grant retry 이력 보강 |
| 4b-2 | 완료·머지 | [PR #97](https://github.com/refinvest-dev/core-api/pull/97), `ce42b14`·`e4e4b38`, Session 소속 NormalizationRun·순수 Mapper·명시적 admission과 재시도 입력 고정 |
| 4c | 완료·머지 | [PR #98](https://github.com/refinvest-dev/core-api/pull/98), `d6df0c0`·`fd03cf3`, immutable LedgerRevision·순수 Mapper·전용 publication capability와 bounded 저장 |
| 4d-1 | 구현·검증 완료, PR 검토 대상 | [PR #99](https://github.com/refinvest-dev/core-api/pull/99), `e02feae`·`7107bf6`, DeletionRequest Root·typed scope/checklist 정의·Mapper·Aggregate 요청 저장/복원 |
| 4d-2 이후 | 미착수 | worker 상태 전이 CAS와 실제 component 진행 증명 연결, 이후 5a/5b/5c 순서 |

### 4d-1 범위와 후속 경계

- 기존 삭제 모델은 port의 문자열 기반 결과 DTO였으며 worker가 SQL로 상태를 전이했다. 4d를
  모델/저장 경계와 실행 상태/CAS 연결로 나눴다. 4d 전체 완료는 4d-2까지 포함한다.
- `DeletionRequest`는 `AggregateRoot<DeletionRequestId>`다. `DeletionScope`가 target type과 typed ID,
  requested scope를 묶고, 생성 시 scope별 영향 집계·owner·generation·초기 상태/시각을 검증한다.
  복원은 terminal metadata·failure code·attempt·시각의 일관성을 검증하며 업무 행위를 재실행하지 않는다.
- `DeletionPurgeChecklist`는 해당 scope에서 필요한 live component의 정의다. raw-only와 subtree의
  primary/derived 삭제 범위를 구분하고 object·key·temporary copy·Compute runtime을 포함한다.
  전체 request 상태나 resource count를 component 완료의 증거로 사용하지 않는다. backup deadline은
  별도 저장값을 유지하며, checklist가 실제 purge 진행이나 부재 검증을 수행했다는 뜻은 아니다.
- `DeletionRequestPersistenceMapper`는 로드된 row와 Domain을 순수 변환한다. Store의 ID/target 조회가
  같은 복원을 공유하며 이전 mapper가 결과 DTO에서 누락하던 attempt count도 Root에 보존한다.
  과거 `IMPORT_SUBTREE` label과 미집계 0 count를 현재 값으로 추정하거나 수정하지 않는다.
- Application은 검증된 Root를 생성해 `DeletionRequestStore`에 저장하고 기존 `TradingDeletion` inbound
  결과 DTO로 변환한다. Snowflake adapter와 generator port도 typed deletion ID를 사용한다. 새 Store는
  초기 REQUESTED 저장과 복원만 제공하고, 일반적인 상태 overwrite API를 추가하지 않는다.
- Worker의 기존 SQL 전이, component 진행 정보 부재와 public DTO의 전체 상태 기반 추정은 이번 단계에서
  해결됐다고 표시하지 않는다. 4d-2에서 전이/CAS·체크포인트·완료 증명을 연결하고, 5b에서 외부 I/O의
  transaction 분리와 durable 복구를 다룬다. read model/HTTP 실패 의미 정리는 5c 범위도 따른다.
- 관련 테스트 227건을 전체 실행으로 통과했다(실패·skip 0). Domain 24, Application 26,
  Persistence 57, Web·Compute·Storage 각 8, App 96건이며 실제 PostgreSQL은 Persistence 43건과
  App 23건이다. Domain 4건·Mapper 3건·실제 DB 1건·source 경계 1건을 추가했다.
- checklist에서 불필요한 backup 추정 보조 속성을 제거한 뒤 Domain 24건과 Persistence 57건을
  재검증했다. 이 추가 실행 중 기존 `bookCreationReplayAfterDeletionIsGone` 한 건이 test DB 연결
  단계의 Windows `Bad address: connect`로 실패했으며, 같은 최종 코드로 해당 항목을 별도 재실행해
  통과했다. 실패한 실행을 성공으로 계산하지 않으며, 최종 227개 고유 항목은 실패·skip 0이다.
  `verifyAggregateModuleBoundaries`·`verifyMemberSubscriptionBoundaries`와 `bootJar`도 통과했다.
- API·schema·계산 hash 의미와 계산 결정론 영향은 없다. 계약·hash vector·context 문서 25개
  동기화 검사를 통과했다. Worker와 Compute I/O 변경이 없어 실제 Core↔Compute runtime E2E는
  재실행하지 않았다. 기존 삭제·scope/owner 격리·idempotency·rollback·부분 실패/재시도·stale ACK
  DB 회귀는 포함한다. 원격 CI 결과는 별도 확인 대상이며 credential·fixture·로그·runner는 커밋하지 않는다.
- 도메인·순수 매핑(`e02feae`), Aggregate Store·typed generator·서비스/결과 경계와 DB 회귀
  (`7107bf6`), 로컬 검증 기록을 별도 커밋으로 나눴다. 다음 단계는 4d-2다.

### 4c 검증 기록과 후속 범위

- `LedgerRevision`을 `AggregateRoot<LedgerRevisionId>` 기반의 불변 모델로 정의했다. Application이
  검증된 입력 identity·결과 쌍·hash·count·section digest와 bounded metadata로 준비하고, 발행 capability가
  Book 잠금 안에서 순번과 발행 시각만 부여한다. 발행 후 재발행·내용 변경을 허용하지 않는다.
- `LedgerRevisionPersistenceMapper`는 전달받은 row와 Aggregate를 순수하게 변환하고 양방향 불변식을
  검증한다. VersionSet과 count/digest JSON의 중첩 key 순서를 정규화한다. command 복원 SQL은
  manifest의 reviewUnits·matches·issues와 quality의 validationIssues를 DB에서 제외한 뒤 JDBC로 읽는다.
  전체 membership을 Aggregate 복원에 적재하지 않는다. 독립적인 공개 summary projection은 전용 Reader다.
- 기존 staging Store의 개별 발행 인자를 제거하고 `LedgerPublication`에 Aggregate, 실행 snapshot,
  descriptor와 lease 조건을 묶었다. Revision Store/발행 adapter와 summary Reader를 분리했으며,
  수신·staging·ACK runtime capability는 유지한다. ACK row 작성만 공통 persistence helper로 공유한다.
- 발행은 transition gate와 Account→Book→Session→Run/dispatch/stage 잠금 순서를 따른다. 저장된 staging
  descriptor와 메타데이터, expected Run 버전·attempt·hash·결과 쌍, live lease·삭제 generation을 확인한다.
  잠금을 모두 얻은 후 Clock을 다시 읽으며, stale 요청은 Revision·membership·latest·ACK를 바꾸지 않는다.
- 대용량 membership·allocation·manifest 배열과 V2 supplement는 기존 INSERT SELECT/DB 집계를 유지한다.
  Session ACCEPTED·Revision/membership·Book latest·Run/dispatch 완료·ACK outbox·감사·stage 제거는 같은
  transaction이며 HTTP ACK는 commit 후 별도 runtime에서 처리한다. 중복 성공 조회도 기존 내용과
  ACK identity를 확인하고, 삭제가 시작된 Session은 성공 replay로 간주하지 않는다.
- 최종 코드의 관련 테스트 218개 고유 항목을 검증했다. Domain 20, Application 26, Persistence 53,
  Web·Compute·Storage 각 8, App 95건이며, 실제 PostgreSQL 검증은 Persistence 42건과 App 23건이다.
  새 도메인/매핑 6건과 source 경계 1건, 실제 잠금 대기 중 lease 만료 및 별도 Session membership
  격리 2건을 추가했다. 기존 DB 발행 시나리오에도 descriptor·메타데이터·Run 버전·lease 변조,
  pause·rollback·삭제 후 replay의 무변경 검증과 bounded 복원을 추가했다.
- Mapper 왕복 테스트에서 중첩 VersionSet의 JSON key 순서가 달라지는 것을 확인해 정규화했다.
  수정 후 Domain·Application·Persistence·Web·Compute·Storage 전체와 App 94건이 통과했고,
  기존 `ComputeHttpTimeoutTest.submitTimesOutBeforeHeadersAndDuringBodyDelivery` 한 건의 Windows
  `Bad address: listen` 오류는 같은 코드로 항목을 재실행해 통과했다. 위 218건은 한 번의 전체
  Gradle 실행 성공을 뜻하지 않으며 실패한 실행은 성공으로 계산하지 않는다. 최종 고유 항목은
  실패·skip 0이고 source/실제 Gradle 모듈 경계 검사와 `bootJar`도 통과했다.
- 도메인·순수 매핑(`d6df0c0`)과 발행 capability·실행 fence·DB 회귀(`fd03cf3`), 로컬 검증 기록을
  별도 커밋으로 나눈다. API·schema·계산 hash 의미 변경과 계산 결정론 영향은 없다. 구현 전후
  OpenAPI·hash vector·context 문서 25개 동기화 검사를 통과했다. 원격 CI 결과는 별도 확인 대상이다.
- `fd03cf3`의 검증 JAR로 실제 Core↔Compute runtime을 통과했다. `primary`·`result_integrity`·
  `ledger_publication`은 PASS, ACK는 `ACKNOWLEDGED_AFTER_RETRY`, publication identity·evidence references·
  idempotency는 PASS다. 증적의 source commit과 artifact SHA-256이 테스트한 JAR와 일치한다.
  로컬 증적은 integration의
  `.integration-data/trading-normalization/7fd12d4fc2e64071ab62bb5027b615de/evidence.json`이다.
- 통합 검증은 분리된 `refinvest_integration` DB와 합성 fixture를 사용했다. 로컬 wrapper에서 ACK
  간격을 20초→60초, 후속 주기 대기를 22초→62초로 조정하고, publication의 Session 대기 잠금을
  풀기 전에 Compute API를 중지해 ACK 장애를 확실히 주입했다. Windows `WinError 10014`의 API
  기동·재기동만 제한 재시도했으며, 진단용 파이프 대기는 동일 API의 추가 재기동으로 해소했다.
  기존 assertion 93개가 AST 기준 동일함을 확인했고 추가 API 재기동 뒤에도 ACK·Revision·count·latest
  불변성 검사를 통과했다. 준비 단계 오류·장애 주입 시점 경합·프로세스 제어 오류로 실패한 실행은
  통과 기록에서 제외했다. 이번 실행은 합성 기능 검증이며 대형 payload의 heap 성능 측정은 아니다.
  검증 프로세스와 임시 환경 파일은 정리했고 credential·fixture·로그·wrapper는 커밋하지 않는다.
- 후속 4d에서는 DeletionRequest를 명시적 상태 모델로 정리한다. scheduler 진입점과 파일·암호화·HTTP
  기술 구현 이동은 5a, 삭제/retention 실행 절차 분리는 5b로 남긴다.

### 4b-2 검증 기록과 후속 범위

- NormalizationRun은 Session 소속 `DomainEntity<NormalizationRunId>`다. Root의
  `admitNormalization`이 최초 생성·명시적 재시도와 입력/결과 pin을 검증한다. 공개 생성·복사와
  일반 Run 삽입 포트를 제거했고, Session 복원은 현재 Run 하나만 적재한다.
- `NormalizationRunPersistenceMapper`는 로드한 row와 소속 입력을 양방향 변환한다. Store/Reader는
  같은 매핑을 공유한다. 과거 identity·delivery policy·완료 시각 누락을 현재 값으로 추정하지 않으며,
  복원 중 업무 행위를 재실행하지 않는다. 새 실행의 상태·identity·descriptor 불변식은 검증한다.
- 명시적 admission capability는 Account→Book→Session 잠금과 transition gate, expected Session·이전
  Run 버전, raw 보관 만료와 삭제 generation을 확인한다. Session 포인터·Run·attempt·dispatch·감사
  이력을 같은 transaction에 저장하며 stale 요청과 rollback은 어느 row도 남기지 않는다.
- worker의 lease·attempt·receipt 전이는 별도 runtime capability로 유지하고 조건을 모델로 묶었다.
  UUID 생성은 순수 feature port와 `adapter:uuid`로 분리했으며 실제 Gradle 의존성 검사에 포함했다.
- 명시적 재시도가 저장된 instrument terms 대신 현재 설정을 사용하던 문제를 PostgreSQL 회귀
  테스트로 재현했다. 기존 설정과 결과 V1/V2 pin을 유지하도록 수정하고 Root에서도 변경을 거절한다.
- 최종 코드에서 cleanTest 후 관련 테스트 209건을 한 실행으로 통과했다(실패·skip 0).
  Domain 17, Application 26, Persistence 48, Web·Compute·Storage 각 8, App 94건이다.
  실제 PostgreSQL 검증은 Persistence 40건과 App 23건이며, admission의 실제 잠금 대기·commit/rollback
  경합, stale 이전 Run, 만료·삭제·pause gate와 V2 재시도를 포함한다. source 경계 검사와
  `verifyAggregateModuleBoundaries`, `verifyMemberSubscriptionBoundaries`, `bootJar`도 통과했다.
- 수정 전 검증 중 Windows 소켓의 `Bad address`로 실패한 실행은 통과 기록에 포함하지 않는다.
  최종 위 실행은 재시도 결과를 합산한 수치가 아니다. 원격 CI 통과를 뜻하지 않는다.
- 최종 코드 `e4e4b38`의 검증 JAR로 Core↔Compute 실제 runtime 검증을 통과했다.
  `primary`·`result_integrity`·`ledger_publication`은 PASS, publication의 identity·evidence references·
  idempotency는 PASS, ACK는 `ACKNOWLEDGED_AFTER_RETRY`다. 검증 증적의 source commit과 artifact
  SHA-256이 테스트한 JAR와 일치한다. 로컬 증적은 integration의
  `.integration-data/trading-normalization/cfa135385a684f8183951b59d073c37a/evidence.json`이다.
- runtime 첫 실행은 Compute 준비 단계의 Windows `WinError 10014`로 실패했으며 통과로 계산하지
  않는다. 같은 제품 코드·JAR·assertion으로 재실행해 통과했다. 검증 wrapper에서 ACK 간격을
  20초→60초, 후속 전체 주기 대기를 22초→62초로 조정했으며 검증 assertion을 제거하지 않았다.
  실행 프로세스와 임시 환경 파일은 정리했다. credential·fixture·로그·runner는 커밋하지 않는다.
- 구현 전후 계약/OpenAPI·V1/V2 hash vector·context 문서 25개 동기화 검사를 통과했다.
  API·schema·계산 hash 의미 변경은 없으며 계산 결정론 영향은 없다.
- 구현(`ce42b14`)과 재현 후 입력 고정 수정(`e4e4b38`), 로컬 검증 기록을 나눠 커밋한다.
  다음 4c에서는 immutable LedgerRevision과 publication capability·Mapper를 정리하고 membership,
  hash, generation, latest pointer 원자성과 stream/batch 저장을 유지한다. scheduler 진입점과
  파일·암호화·HTTP 기술 구현 이동은 5a 범위로 남긴다.

### 4b-1 검증 기록과 후속 범위

- 4b를 Session·Artifact의 업로드/삭제 저장 경계와 NormalizationRun의 admission/재시도 경계로
  나눴다. 두 소속 모델을 독립 Aggregate로 승격하지 않는다. 4b 전체 완료는 4b-2까지 포함한다.
- ADR-018/061/063/064와 `TR-I03`~`TR-I06`·`TR-I14`~`TR-I24`를 기준으로 Session을
  `AggregateRoot<TradingImportSessionId>`, Artifact를 소속 `DomainEntity`로 정리했다. 공개 생성자와
  `copy`를 제거하고 생성·복원의 상태/시간/policy·version pin, role·ID 중복과 소속을 검증한다.
  raw descriptor의 키와 Artifact collection은 외부 변경을 차단한다. 복원은 업무 행위·이벤트를 재실행하지 않는다.
- Application은 Root의 `recordUpload`·`requestArtifactDeletion`·`requestDeletion` 뒤 Session Store를
  호출한다. Root와 최대 두 Artifact를 같은 transaction에서 저장하며 하위 Entity를 직접 저장하는
  command port를 제거했다. Store/Reader는 같은 순수 `TradingImportSessionPersistenceMapper`를
  사용하고 JDBC row 추출과 SQL 컬럼 분해는 adapter가 소유한다. 기존 JDBC와 대용량 result 저장을 유지한다.
- 새 Session 저장은 Account→Book 순서로 잠그고 활성 parent·owner·mode를 검사한다. 변경 저장은
  Session 잠금·expected Root/Artifact version, immutable metadata·입력 pin·created Revision pointer와
  허용된 상태/generation을 검사한다. stale 저장은 Root·Artifact 모두 변경하지 않는다. 빈 collection을
  저장해 기존 Artifact를 지우거나 일반 저장으로 발행 pointer를 바꿀 수 없다.
- Raw purge는 별도 capability/adapter로 분리했다. 갱신된 claim의 Artifact version과 다른 늦은
  완료·실패 응답은 상태나 실패 횟수를 바꾸지 않는다. 기존 idempotency·원자적 admission/publication,
  삭제 generation과 DB transaction 밖의 object I/O를 보존한다.
- 실제 PostgreSQL에서 삭제 요청 직후 생성 key를 재전송하면 삭제 대기 Root를 반환하는 회귀를
  재현했다. 수정 후 요청 직후와 purge 이후 모두 `410 RESOURCE_DELETED`를 검증했다.
- 실제 HTTP smoke에서 ADR-064의 grant retry가 새 attempt에 기존 Compute job ID를 보존하지 않는
  `CORE_ATTEMPT_DRIFT`도 발견했다. PostgreSQL 회귀는 `[same-job, same-job]` 대신
  `[same-job, null]`로 실패했다. 같은 job의 grant 갱신에서만 현재 job ID를 새 attempt에 저장하도록
  수정했다. 새 Compute job을 만드는 재시도는 ID를 상속하지 않으며, 잘못된/만료 lease·stale token
  거절과 Run/attempt/audit rollback을 함께 검증한다. 과거 attempt 이력의 자동 보정은 포함하지 않는다.
- 전체 관련 테스트는 domain 12개·application 26개·persistence 41개·web 8개·compute 8개·storage
  8개·app 93개로 196개 통과, 실패·오류·skip 0개다. security/snowflake는 테스트 source가 없다.
  재전송 수정 후 application·persistence·app 160개를 다시 실행했고 나머지 36개는 앞선 성공 결과를
  재사용했다. 실제 PostgreSQL 59개(Trading Review 36개·app 23개)를 포함한다. 소스 경계와 실제
  Gradle 의존성 검사, bootJar도 통과했다.
- grant retry 이력 수정 뒤 persistence 41개·Gradle 경계·bootJar를 추가 실행해 통과했다. 나머지
  155개는 앞선 성공 결과를 재사용했다. 관련 테스트 합계는 중복 실행을 합산하지 않은 196개다.
- 새 회귀는 immutable pin·Artifact identity 변경/누락 거절, stale snapshot 슬롯 보존, parent 삭제,
  Root/Artifact 전체 rollback과 purge claim 교체를 검증한다. 기존 업로드 경합·replay, admission·retry,
  삭제 후 grant 차단, V1/V2 publication·ACK 회귀도 통과했다. 기존 만료 fixture의 시간 역전과
  service/worker Clock 불일치는 유효한 시간 흐름으로 수정했다. Windows Gradle 소켓 bind 오류로
  테스트가 시작되지 않은 실행과 수정 전 실패는 성공으로 세지 않았다.
- 실제 Core↔Compute `test-trading-normalization-runtime-contract.ps1 -Publication`의 업로드·정규화,
  result 무결성, Ledger 발행, Compute 중단/재기동 뒤 ACK 재전송과 한 scheduler 주기 뒤 재조회가
  통과했다. `primary`·`result_integrity`·`ledger_publication` 모두 PASS이며 ACK는
  `ACKNOWLEDGED_AFTER_RETRY`, idempotency는 PASS다. membership/hash·evidence reference·건수,
  latest pointer와 staging 정리를 확인했다. 검증한 bootJar의 SHA-256은 실행 증적과 일치한다.
- 검증 runner는 이미 빌드한 JAR를 사용하고 ACK fault를 설치할 수 있도록 scheduler 주기를
  로컬에서 60초로, 후속 확인 대기를 62초로 맞췄다. 원래 assertion은 유지했다. 장시간 실행 중단 뒤
  인증 만료로 마지막 조회가 401이 된 실행, grant retry 이력 수정 전 실패와 Windows 소켓 오류로
  기동되지 못한 실행은 통과로 세지 않았다. 전용 프로세스·임시 환경 파일을 정리했다.
- PR #96의 최신 정본 문서 동기화는 main 병합으로 반영했다. 실행 코드는 HTTP 검증 커밋
  `f5ac6df`와 같으며 이후 변경은 문서뿐이다. 병합 후 계약·정본 문서 25개 동기화와
  `git diff --check`도 통과했다. API·schema·Compute 요청과 계산 hash 의미 변경은
  없으며 계산 결정론 영향은 없다. 새 저장/CAS 검증이 유지되도록 쓰기·retention 워커 인스턴스를 함께
  갱신한다. credential·fixture·로그·검증 runner는 커밋하지 않는다.
- 다음 4b-2에서는 NormalizationRun을 Session 소속 모델로 정리하고 Root를 통한 생성/재시도,
  pure Mapper와 명시적 admission capability를 구현한다. exact input·result/version pin,
  active Run uniqueness와 attempt/lease/deletion generation fence를 유지한다. 4c 발행 모델과
  5a scheduler·파일/암호화 port 구현 이동은 후속 단계다.

### 4a 검증 기록과 적용 기준

- ADR-018/061/063, `Create/Get/List/DeleteTradingAccount`·`Create/Get/List/DeleteTradingBook`과
  `TR-I01`·`TR-I02`·`TR-I14`~`TR-I16`·`TR-I19`·`TR-I24`를 기준으로 정리했다. Account와 Book은
  각각 독립 AggregateRoot이며 소속 Book 전체를 Account의 메모리 collection으로 적재하지 않는다.
  ID는 Identifier 기반 value object로 분리하고 Root 생성자를 private으로 유지한다.
- 생성·복원 모두 이름, timezone, version, deletion generation·상태 조합과 timestamp 순서를
  검증한다. 수정 전 공통 기반 누락과 잘못된 복원 허용의 회귀 실패 3개를 확인했다. 복원은
  새 업무 행위나 이벤트를 실행하지 않는다. rename·삭제는 기존처럼 새로운 불변 snapshot을 반환한다.
- JDBC는 유지했다. Account/Book별 Store·Reader와 PersistenceMapper/Record를 분리했으며
  삭제 journal과 idempotency adapter도 각각 독립시켰다. Mapper는 준비된 record만 변환하고
  조회·시각 결정·ID 생성·상태 전이는 수행하지 않는다. Book의 기존 latest Ledger reference도
  typed ID로 복원하며 Ledger body나 전체 하위 row를 조회하지 않는다.
- 일반 저장은 기존 version CAS에 immutable identity·현재 lifecycle·generation·timestamp와
  Book pointer 조건을 더했다. 과거 snapshot이나 복원된 다른 parent/timezone·terminal 상태로
  저장할 수 없다. Book 생성은 현재 Account를 잠가 ACTIVE·owner·venue를 검증한다. 일반 저장은
  publication pointer를 갱신하지 않으며 기존 streaming publication/ACK/purge 경로를 보존한다.
- Account 삭제의 일괄 Book 차단은 owner·generation·저장 후 version·요청 시각을 묶은 원자적
  capability로 분리했다. 같은 transaction의 Account fence를 잠가 검증하고 stale 요청은 아무것도
  바꾸지 않는다. 이미 삭제 중인 Book의 generation은 재증가시키지 않으며 affected count는 archived/
  deleting Book도 포함한 요청 scope를 센다. 시계 차이로 자식 updatedAt이 역전되는 실패도 재현했고
  기존 시각과 요청 시각 중 늦은 값을 유지해 복원 가능한 상태를 보존했다.
- 실제 PostgreSQL에서 삭제 중인 Account/Book이 같은 생성 키 재전송으로 반환되는 실패 2개를
  재현하고 `410 RESOURCE_DELETED`로 차단했다. 외부 owner의 `404`와 기존 정상 replay는 유지한다.
  `TR-ACC-DELETE-006/007/011`의 접근 차단·scope·중복 generation 부분을 검증했다. 전체 purge
  workflow와 DeletionRequest 모델 분리는 4d/5b에 남아 있으며 이 단계가 모두 완료했다는 뜻은 아니다.
- Domain 9개·application 26개·persistence 36개·web 8개·compute 8개·storage 8개와 app 92개,
  총 187개의 통과 결과를 확인했다. PostgreSQL은 Trading Review 33개와 기존 app DB/migration
  23개, 총 56개다. 초기 전체 실행의 DB 연결/worker 종료 `Bad address: connect/listen` 실패는
  성공으로 세지 않았다. 기존 통과 결과를 유지하고 해당 항목을 선택 재실행했으며 최종 변경 후
  persistence 36개와 실행 JAR를 다시 검증했다. 테스트 skip은 0개이고 컴파일 UP-TO-DATE는
  테스트 실행으로 세지 않는다. Security/Snowflake는 테스트 source가 없어 컴파일·경계만 확인했다.
- PostgreSQL 회귀는 실제 row 잠금 대기, owner 격리, immutable parent/timezone, stale 저장·cascade,
  rollback 시 Root/child/journal/idempotency 동시 복구와 삭제 generation 1회 증가를 확인했다.
  기존 실제 publication/purge 테스트에 latest pointer 복원·유지와 stale 저장 거절을 추가했다.
- Trading Review 9개 모듈의 소스 및 실제 Gradle 의존성 검사를 추가했다. security의 Member port
  전이 의존성을 제한하며 BOM metadata와 framework 구현 JAR를 구분한다. 기존 Compute scheduler의
  application 구현체 두 참조는 정확한 타입으로 추적하고 5a에서 제거한다. 새 Account/Book port와
  Mapper는 I/O 구현을 허용하지 않는다. 기존 port의 파일/암호화 구현과 inbound/HTTP 경계는 5a/5c다.
- schema·API 필드·hash·DSL·계산 결정론 변경은 없다. 정상 저장 데이터·idempotency·publication을
  유지한다. 최종 코드 `12aba18`의 실행 JAR로 합성 CSV 업로드부터 실제 Compute normalization,
  결과 무결성·Ledger 발행·latest pointer·ACK 장애/복구·재조회 idempotency의 원본 runtime assertion을
  통과했다. evidence의 Core commit과 JAR SHA-256도 현재 구현과 일치했다. 계약·문서 snapshot
  24개·Web snapshot과 context vector 검사도 통과했다.
- 최초 ACK 장애 주입 창을 놓친 실행과 DB/Compute 기동의 Windows 소켓 오류 실행은 성공으로
  세지 않았다. 로컬 harness의 ACK 주기를 20초에서 60초로 늘려 장애/복구와 원본 assertion을
  확인했다. ACK 이후 관찰도 62초로 맞춘 추가 실행은 Compute의 `WinError 10014` 기동·재기동
  실패로 끝났으며 통과로 기록하지 않는다. 이 추가 관찰은 환경이 안정된 곳에서 재검증할 수 있다.
  제품 코드는 검증을 위해 변경하지 않았고 assertion을 제거하지 않았다. 검증용 프로세스·임시
  환경 파일은 각 실행 후 정리했으며 credential·fixture·로그는 커밋하지 않는다. 다음 단계는
  4b ImportSession과 소속 Artifact/NormalizationRun이다.

### 3c 검증 기록과 적용 기준

- ADR-018/019/020과 로그인·refresh·현재 회원 조회, `RunBacktest`·`ListBacktestRuns`를 기준으로
  R12의 직접 domain 참조를 제거했다. 회원 역할과 구독 tier는 각 feature의 inbound port가
  소유하는 enum으로 전달하며 application에서 domain 값을 변환한다. MEMBER/ADMIN과
  FREE/PRO의 외부 값은 유지한다. Domain Aggregate와 command Store/Reader 경계는 유지한다.
- Backtest 실행은 Subscription의 `GetBacktestPolicyUseCase`를 통해 한도·허용 Asset을 받는다.
  tier 선택과 구독 부재 시 FREE 기본값, 실제 정책은 Subscription이 소유한다. Backtest는
  정책 결과로 entitlement·포함 일수·quota/capacity 예약을 검증하고 tier별 수치를 직접 결정하지
  않는다. 결과 Set은 복사해 소비자의 변경이 이후 정책 결정을 바꾸지 않도록 했다.
- 실행 이력의 소유권은 `LookupStrategyOwnerUseCase`로 확인한다. JPA의 소유자 컬럼 조회는
  Strategy 전체 버전·조건을 로딩하지 않는다. 존재하지 않는 전략과 타인 전략 모두 이력을
  읽기 전에 기존과 같은 not found로 처리한다. 큰 ID와 빈 전략의 소유자도 보존한다.
- 다른 feature port 의존성은 전이 의존성을 제한해 그 feature의 domain을 compile/test classpath에
  노출하지 않는다. 기존 Gradle 의존성 검사에 application과 Auth security의 실제 classpath 검사도
  추가했다. 수정 전 `member:application`에 `subscription:domain`이 유입되는 경계 실패를 확인했고
  수정 후 통과했다. Backtest/Auth 소스 검사에 있던 domain 타입 예외도 제거했다.
- feature 간 사용하는 port 계약 7개의 method signature·중첩 모델·generic·sealed 구현 타입을
  검사한다. 단순히 import를 없애고 domain 모델을 결과 안에 숨기는 변경도 거절한다.
- Member application 3개·Subscription application 9개·Auth application 11개·Auth security 3개,
  Strategy application 14개·persistence 24개, Backtest application 30개·persistence 43개와 app
  90개, 총 227개를 한 번의 실행에서 실제 검증했다. 실패·오류·테스트 skip은 0개다. 선택한
  모듈의 cleanTest 후 실행한 결과이며 컴파일 UP-TO-DATE를 테스트 실행으로 세지 않는다.
- 실제 PostgreSQL 검증은 Strategy 16개, Backtest quota/lease/Run 저장 30개와 기존 app DB/
  migration 23개, 총 69개다. 소유권 조회와 함께 immutable 저장, rollback, 월 경계 capacity,
  stale polling, refresh 회전·폐기·경합과 회원 생성/구독 업그레이드의 원자성을 재검증했다.
  소스·실제 Gradle 의존성·계약 모델 검사를 통과했고 실행 JAR도 빌드했다.
- 역할·구독 값의 JSON 표현, public/Compute API, schema, hash·DSL·계산 결정론에는 영향이 없다.
  현재 커밋의 실행 JAR와 합성 `integration-e2e-v1` fixture, local integration DB로 실제
  Core↔Compute HTTP smoke를 통과했다. 정상·재실행·실패 결과·타인 접근 거절과 terminal
  persistence를 확인했다. JAR artifact identity를 포함한 기존 topology preflight와 smoke
  assertion을 유지했다. 별도 포트/registry의 프로세스와 임시 환경 파일은 정리했다.
- 계약 snapshot·문서 snapshot 24개와 context vector 검사도 통과했다. 로컬 IPv4 옵션은 검증
  프로세스에만 적용하며 credential·fixture·로그는 커밋하지 않는다. 다음 단계는 4a
  TradingAccount/TradingBook Root·typed ID·Mapper·Store 분리와 ownership 보존이다.

### 3b 검증 기록과 적용 기준

- ADR-010/018/020과 `RunBacktest`·`GetBacktestResult`·`PollBacktestStatus`·`ListBacktestRuns`를
  기준으로 BacktestRun의 저장·복원을 정리했다. Root와 typed ID 및 소속 BacktestResult 경계를
  유지한다. `restore`도 완료 행위와 동일하게 결과의 Run ID·DatasetSnapshot ID를 검증하며,
  잘못된 errorCode는 상태를 바꾸기 전에 거절한다. 이 불변식과 중첩 목록 변경의 회귀 실패
  3개를 수정 전에 확인했다. 결과·benchmark curve·delay 분포는 입력 복사와 변경 불가 목록으로
  보호하며, JSON에 새 Aggregate/Entity ID 필드를 추가하지 않는다.
- `BacktestRunPersistenceMapper`와 `BacktestResultPersistenceMapper`는 adapter가 조회한
  Root/Result row를 받아 전체 Aggregate를 복원한다. Repository 조회·시각 결정·업무 전이는
  Mapper에서 수행하지 않는다. 독립적인 상태·목록 조회는 `BacktestRunReadModelMapper`를
  사용하고, 결과 Reader는 Store와 같은 결과 매핑을 공유한다. nullable 지표, ZERO sample,
  optional benchmark, 큰 ID, 소수 정밀도와 기존 legacy 결과 재계산 안내를 보존한다.
- 이전 adapter로 stale RUNNING 저장이 이미 commit된 COMPLETED를 덮어쓰는 PostgreSQL
  회귀 실패를 재현했다. command 조회와 저장은 Root row를 잠그고 기존 JPA cache도 새로
  로딩한다. 요청 입력·execution pin·terminal 상태/결과 변경은 거절하며, 동일 terminal 저장은
  기존 Result row/OID와 완료 지표를 변경하지 않는다. Result는 append-only로 저장한다.
  DB timestamp와 fee 컬럼의 기존 정밀도를 고려해 원래 입력의 정상 재저장을 허용한다.
- Store의 변경용 조회→행위→저장은 같은 command transaction 안에서 수행한다. Dispatch의
  요청 정보 조회는 짧은 transaction으로 끝내고 Compute HTTP는 transaction 밖에서 호출한다.
  영구 거절 반영은 현재 Run을 다시 잠가 복원한 후 상태·capacity를 함께 저장한다. 결과 조회는
  read-only transaction에서 PostgreSQL LOB를 읽고 Run의 snapshot과 결과의 일치를 확인한다.
- Backtest domain·port·application·persistence·compute·web·snowflake를 소스 및 실제 Gradle
  project 의존성 경계 검사에 추가했다. R12의 기존 직접 domain 참조 3개 타입만 명시적으로
  추적하며 3c에서 제거한다. API/schema/hash 및 계산 결정론에는 영향이 없다.
- domain 10개·application 30개·persistence 43개·compute 10개와 app 89개, 총 182개 테스트의
  통과를 확인했다. 테스트 skip은 0개다. 한 번의 전체 실행 성공을 뜻하지 않는다. domain/
  application/compute 통과 결과를 유지하고, 수정 후 persistence 및 app을 재실행했다. Windows
  `Bad address: listen` 및 DB 연결 소켓 오류로 실패한 실행은 성공으로 세지 않았다. 마지막으로
  실패했던 HTTP timeout/중복 예약 회귀 1개를 선택 재실행해 Gradle 정상 종료를 확인했다.
- 실제 PostgreSQL 검증은 quota 9개·polling lease/영구 거절 12개·Aggregate 저장 9개와 기존
  app DB/migration 23개, 총 53개다. stale 요청의 Run·capacity·dispatch 보존, 실제 row 잠금
  대기와 cache 갱신, 다른 Root의 독립 저장, rollback과 terminal Result/OID 보존을 확인했다.
  Mapper 6개는 전체 상태/결과와 불법 저장 상태, JSON shape 및 정밀한 소수 비교를 검증했다.
- 앱의 Spring 프록시·HTTP 회귀 89개와 실제 Gradle project 의존성 검사를 통과했고 실행 JAR도
  빌드했다. 컴파일 UP-TO-DATE는 테스트 실제 실행과 구분한다. IPv4 옵션과 loopback bind는
  로컬 검증 프로세스에만 적용했다. 로컬 credential·fixture·로그는 커밋하지 않는다.
- domain 보강(`7483529`)과 Mapper·저장 보호·회귀/경계 검증(`c5d363b`)을 나눠 커밋했다.
  잠금 보장은 모든 writer가 새 저장 경로를 사용할 때 적용된다. 다음 단계는 3c feature port
  경계 정리다.
- 계약 snapshot과 문서 snapshot 24개, context vector 검사를 통과했다. 현재 구현의 실행 JAR로
  local integration DB와 `integration-e2e-v1` 합성 fixture에서 실제 Core↔Compute HTTP smoke를
  통과했다. 정상 실행·재실행·실패 결과·타인 접근 거절 및 terminal persistence를 확인했다.
  JAR 실행의 artifact identity를 topology preflight에 전달하고 기존 smoke assertion을 유지했다.
  별도 포트/registry로 기동한 세 프로세스와 임시 환경 파일은 정리했으며 harness 산출물은
  커밋하지 않았다. API/schema/hash·계산 입력 및 결정론 영향은 없다.

### 3a 검증 기록과 적용 기준

- ADR-013/018과 `CreateStrategy`·`DefineStrategyVersion`·`GetStrategy`·`ListStrategies`를 기준으로
  Strategy의 저장·복원과 조회 매핑을 정리했다. 기존 AggregateRoot·typed ID를 유지하며
  `Strategy.restore`·`StrategyVersion.restore`가 생성/추가 행위를 재실행하지 않고 같은 불변식을
  검증한다. Root는 이름·child 소유권·버전 ID 중복을 검사하고 입력 버전 목록을 복사한다.
- 수정 전 Version의 조건 목록을 MutableList로 변환해 변경할 수 있는 회귀 실패를 재현했다.
  조건은 입력을 복사한 변경 불가 목록으로 보관해 생성·복원된 immutable 정의를 보호한다.
- `StrategyPersistenceMapper`는 Aggregate·Version·Condition의 양방향 변환과 child 관계를 만든다.
  새 Aggregate 저장만 전체 그래프를 생성하며 기존 Root는 잠근 뒤 새 버전만 append한다. R03의
  기존 metadata/definition 변경 거절, 다른 Root의 버전 ID 충돌 거절과 DB timestamp 정밀도 처리를
  adapter에 보존했다. 기존 버전·조건 collection을 교체하거나 row를 재생성하지 않는다.
- `StrategyReadModelMapper`는 Root를 복원하지 않고 상세·목록과 Backtest 소비 projection으로
  직접 변환한다. 독립적인 버전 조회의 owner·조건 row는 adapter가 먼저 준비한다. 조건 순서와
  생성 시각·숫자 ID 기준 버전 정렬을 명시해 R05의 최신 선택·동률 기준을 유지한다.
- domain 17개·application 14개·persistence 23개·snowflake 1개와 app 87개, 총 142개가 통과했다.
  실패·오류·테스트 skip은 0개다. Strategy 대상 55개와 앱의 나머지 40개 통과 결과를 유지하고,
  최종 실행에서 기존 앱 회귀 47개를 실제 재실행한 합계다. 컴파일 UP-TO-DATE와 테스트 결과
  재사용 여부를 구분하며, 전체 142개가 한 번의 task 실행에서 통과했다고 표시하지 않는다.
- Mapper 테스트 5개는 전체 정의·큰 ID·timestamp round-trip, nullable window/combinator,
  Literal/Metric operand, child 관계·순서와 잘못된 저장 상태의 거절을 검증했다. PostgreSQL
  Strategy 테스트 15개로 동시 추가·rollback·기존 row/metadata 보존·최신 선택을 재검증했다.
  이미 로딩된 그래프를 세션 종료 후 변환하고 복원한 Aggregate를 다시 저장해도 row가 바뀌지
  않는 것도 확인했다. App의 기존 PostgreSQL 검증 23개도 실제 실행했으므로 DB 검증은 총 38개다.
- PostgreSQL fixture는 작은 연결 풀을 사용하며 경쟁 작업을 시작하기 전에 별도 물리 연결을
  확보한다. 기존 실제 잠금 대기 assertion을 유지했고 전용 schema와 연결 풀을 정리했다.
- Windows에서 `Bad address: listen`이 Tomcat 및 Gradle test worker 기동 중 반복됐다. 해당
  실패·미기동 실행은 성공으로 세지 않았다. 최종 앱 47개는 로컬 프로세스의
  `SERVER_ADDRESS=127.0.0.1`, `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`로 검증했다.
  이 옵션은 로컬 실행 환경에만 적용했다.
- Strategy domain·port·application·persistence·web·snowflake를 소스 및 실제 Gradle 의존성 경계
  검사에 추가했다. 실행 진입점은 기존 `verifyAggregateModuleBoundaries`를 사용한다.
- 계약 snapshot, 문서 snapshot 24개와 git diff 검사를 통과했다. API/schema/hash·계산 입력 및
  계산 결정론 영향은 없다. Core↔Compute 호출 변경이 없어 해당 E2E는 재실행하지 않았으며
  Backtest 소비 projection은 회귀 검증에 포함했다. 로컬 credential·fixture·로그는 커밋하지 않는다.
- domain 복원·불변성 보강(`a824c26`)과 Mapper·조회·회귀/경계 검증(`270146d`)을 나눠 커밋했다.
  다음 단계는 3b이며, Backtest Run/Result의 저장·복원을 정리하면서 R02 quota/capacity 예약과
  R04 polling lease의 stale 응답 차단을 유지한다.

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
