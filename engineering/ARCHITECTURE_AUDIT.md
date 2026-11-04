# Core Architecture Audit

## Structure Alignment Review — 2026-11-04

[Core #113](https://github.com/refinvest-dev/core-api/pull/113)~[#120](https://github.com/refinvest-dev/core-api/pull/120)
병합 뒤 main `a4f5717`을 기준으로 후속 순서 6을 점검했다. 아래는 현재 구조의 재점검이며,
이후의 최초 6c-1~3 기록은 당시 검증 이력으로 유지한다.

| Feature | Domain | Port | Application | Adapter | 확인한 경계 |
|---|---:|---:|---:|---:|---|
| Member | 2 | 10 | 2 | 7 | immutable Member Root·Mapper, 생성/조회 port와 Subscription inbound 연동 |
| Subscription | 3 | 18 | 5 | 6 | Root 저장·회원 command 잠금, Reader·정책·usage 결과 분리 |
| Auth | 6 | 18 | 4 | 33 | 두 Root의 Store/Mapper, refresh family fence·회전, raw credential inbound·Web 쿠키 처리 |
| Strategy | 16 | 40 | 8 | 35 | Root 경유 Version 추가, command 잠금·immutable append, 정렬된 조회와 cross-feature projection |
| Backtest | 21 | 41 | 10 | 49 | Run/소속 Result 복원, quota·lease capability, Compute 외부 호출과 commit 뒤 결과 반영 |
| Asset | 11 | 12 | 3 | 13 | Compute 소유 참조 조회, inbound/outbound 분리, provider 중립 조회 옵션·실패 의미 |
| Trading Review | 44 | 153 | 41 | 104 | 다섯 Root·Session 소속 Artifact/Run, JPA 일반 저장, JDBC 발행·lease·삭제와 외부 I/O 분리 |

수치는 production Kotlin 파일 수다. Shared 7개·app 7개를 포함해 총 729개, Gradle 모듈 46개다.
Root 11개·PersistenceMapper 13개, inbound use case 61개, Controller 12개·Scheduler 7개 파일을
목록화했다. 타 feature를 참조하는 import 40개는 모두 port 계약이며, foreign domain/application
classpath 차단은 Gradle 검사로 별도 확인한다. 파일 목록화와 정적 검사만으로 모든 업무 동작을 보장하지 않는다.

### Findings and Changes

| ID | 재점검 근거 | 보완 |
|---|---|---|
| R21 | Asset `GetSeriesQuery`가 outbound `SeriesMetric`을, Strategy Reader projection이 inbound `StrategyVersionForBacktest`를 참조 | 실제 양쪽에서 사용하는 중립 값 모델만 feature `port/common/series`, `port/common/backtest`로 옮김. Command/Query/Result와 Store/Reader의 방향·소유권은 유지 |
| R22 | Asset `AssetSeriesExceptionHandler`가 outbound `SeriesDataErrorException`을 직접 참조 | provider와 호출자가 공유하는 failure 의미를 Asset `port/common/failure`에 두고 HTTP status 매핑은 Web에 유지 |

공유 소유권의 범위는 세 가지로 제한한다. `SeriesMetric`은 두 query가 사용하는 시계열 조회 옵션이며,
`SeriesDataErrorCode/Exception`은 data provider의 실패와 호출자 오류 처리가 공유하는 안정된 의미다.
`StrategyVersionForBacktest`와 그 구성 값은 Reader와 Backtest용 cross-feature 조회 결과가 함께 사용하는
불변 정의다. 별도 저장 상태나 inbound 실행 요청이 아니며, 공유 모델은 어느 방향의 port도 참조하지 않는다.
기존 Trading Review의 `common/failure`와 같은 기준을 적용하고 업무 모델을 일괄 common으로 옮기지 않는다.

새 `CoreModuleBoundaryTest`는 feature port source root를 자동 탐색해 모든 inbound/outbound 상호 참조와
common의 방향 의존을 차단한다. Web adapter 전체도 outbound 계약을 참조할 수 없다. 수정 전 두 검사에서
실제 Strategy Reader와 Asset advice가 실패한 XML을 보존했다. 기존 검사만으로는 잡지 못한 구조 문제이며,
실제 사용자 장애를 재현했다는 의미는 아니다. 모델 필드·enum·실패 의미·HTTP 동작을 변경하지 않는다.

### Preserved Boundaries and Remaining Scope

- 일반 Account/Book/Session/DeletionRequest 저장은 JPA Store/Reader·명시적 Mapper를 사용한다.
  Session은 역할 슬롯과 현재 Run만 복원하며 Ledger 전체를 적재하지 않는다. Scalar projection·잠금 후
  snapshot 재조회와 version/generation 검증을 유지한다. Mapper는 조회·ID/시각 생성·업무 전이를 하지 않는다.
- Ledger의 bounded staging/publication, quota·refresh·admission·retention·grant 소비·worker lease와
  삭제 checkpoint는 원자적 capability다. JDBC와 `JdbcLedgerRevisionPersistenceAdapter`의 같은 Revision
  Store/publication 구현은 이 경계에 따른다. 여러 Aggregate를 모은 adapter로 해석하거나, 파일 크기만으로
  분리해 publication transaction을 바꾸지 않는다. JPA/JDBC의 같은 transaction 참여 회귀를 유지한다.
- Trading Review의 public CRUD와 유지보수 29개 inbound, 기능별 service, resource별 Controller,
  Scheduler의 inbound 진입과 purge/remediation·grant DB/서명/storage 경계를 다시 대조했다.
  외부 HTTP/object/file 작업은 DB transaction 밖에서, 진행 반영·publication/ACK는 기존 순서로 처리한다.
- Domain·schema·OpenAPI·hash/결정론 변경은 없다. AnalysisRun/Reprocessing의 신규 orchestration·public API,
  Finding/Review 제품 완료와 실제 운영 적용은 이 점검의 완료 범위에 포함하지 않는다. ADR-071은 초안이며
  기존 Analytics 실행 정책을 바꾸는 근거로 사용하지 않는다.

구조 보완은 `3b5c95a`이며 [Core #121](https://github.com/refinvest-dev/core-api/pull/121)에서 검토한다.
병합된 main과 이번 보완 branch의 검증을 [Handoff](REFACTORING_HANDOFF.md)에 구분해 기록한다.
보완 구현의 Core CI는 새 경계 검사 2개를 포함한 575개와 PostgreSQL 154개를 통과했다.
로컬 최초 전체 실패·제한 재실행 결과와 main 통합 증적은 서로 구분한다.

## Scope

최초 6c-1 점검은 Core `7ff4dbf` 이후 상태를 대상으로 했다. ADR-018, ADR-019~020과 로컬 AGENTS 규칙을 기준으로
당시 Gradle 46개 모듈의 production Kotlin 550개 파일을 목록화하고 계층 의존성, Aggregate 저장/복원,
검증 진입점을 대조했다. 파일 목록화와 자동 경계 검사는 모든 업무 불변식의 수학적 증명을 뜻하지 않는다.
제품 계약과 향후 미구현 Use Case를 새로 정의하지 않는다.
6c-2/3 후속 변경은 Core #109/#110으로 머지됐으며 최종 main 조합은
[Refactoring Handoff](REFACTORING_HANDOFF.md)에서 별도로 기록한다.

Subscription 등과의 후속 비교에서 port 방향·서비스 책임·일반 persistence 구성의 일관성 항목을
추가 식별했다. 구체적인 T01~T12와 PR 순서는 [Trading Review Structure Alignment](TRADING_REVIEW_ALIGNMENT_PLAN.md)를
따른다. 아래 R01~R20의 기존 완료 기록과 당시 남겨 둔 후속 구조 개선 범위는 구분한다.
후속 순서 1은 [Core #113](https://github.com/refinvest-dev/core-api/pull/113)에서 port 방향·조회 결과 소유권과
grant/spool의 inbound 진입점을 구현했다. 전체 inbound 29개 및 Web/Scheduler의 경계를 검사했다.
당시 남겨 둔 기능별 서비스·JPA 일반 저장·실행 책임은 #114~#120으로 반영됐으며 현재 재점검은 위 기록을 따른다.

## Aggregate and Persistence

| Feature | 현재 Root | 저장/복원 경계 |
|---|---|---|
| Member | Member | MemberStore와 MemberPersistenceMapper, Reader도 같은 복원 사용 |
| Subscription | Subscription | SubscriptionStore와 Mapper, 회원 기준 command 잠금 |
| Auth | SocialIdentity, RefreshSession | Root별 Store/Mapper, refresh family fence와 원자적 회전 capability |
| Strategy | Strategy | StrategyStore/Mapper, Version·Condition은 소속 Entity, immutable append |
| Backtest | BacktestRun | Run Store/Mapper, 소속 Result는 별도 저장 모델/Mapper와 조회 projection, quota/lease capability |
| Trading Review | TradingAccount, TradingBook, TradingImportSession, LedgerRevision, DeletionRequest | Root별 Store/Mapper, Session 소속 Artifact·NormalizationRun, bounded publication과 deletion/retention CAS |
| Asset | 없음 | Compute 소유 참조 데이터를 조회하는 port/adapter; Core 저장 Aggregate를 만들지 않음 |

공통 `AggregateRoot`를 상속한 Root 11개와 `*PersistenceMapper` 13개를 확인했다. `NormalizationRun`과
`BacktestResult`는 정본상 소속 모델이므로 독립 Root로 승격하지 않는다. 대용량 Ledger를 저장하기 위해
전체 row를 Aggregate에 적재하거나 JPA로 바꾸지 않는다. Mapper는 전달받은 저장 상태만 변환한다.
원자적 quota·refresh·lease·publication·삭제 처리는 일반 컬럼 갱신 Store와 구분한 capability로 유지한다.

## Verification Gaps Found

1. 기존 Gradle 검사 목록은 37개 모듈뿐이었다. Asset 5개, Auth Web, Shared 2개와 app의 9개가
   빠졌으며 새 모듈이 추가돼도 목록 누락을 거절하지 않았다.
2. Asset application에 Strategy application 의존성을 주입해도 기존 검사는 성공했다. Production build 파일을
   바꾸지 않는 임시 init script로 재현했다. 새 검사는 금지 의존성과 검사 미등록 모듈을 거절한다.
3. Auth Web의 Subscription port와 Backtest persistence 테스트의 Strategy port가 foreign domain까지
   classpath에 노출했다. 호출에 필요한 port만 남기고 다른 feature의 transitive dependency를 차단했다.
4. Core 자체 PR workflow가 없어 선행 PR #107의 check-run은 0개였다. Integration 후보 CI 성공이 Core
   전체 단위/DB 회귀의 지속 실행을 대신하지는 않는다. Core PR workflow와 JUnit 실행 결과 gate를 추가한다.

검사는 실제 Gradle project 의존성과 compile/test classpath, 순수 계층의 compile/runtime 기술 의존성을
대조한다. 같은 feature의 persistence 통합 테스트가 자기 application을 조립하는 것은 허용하되,
production adapter가 application 구현을 참조하거나 test가 다른 feature application을 끌어오는 것은 거절한다.
기존 feature별 source 검사에 Asset·공통 계층과 전체 persistence Mapper 검사를 추가했다.
Source 정규식 검사는 정적 참조 검사이며 reflection·동적 로딩까지 보장하지 않는다.

## Findings and Regression Coverage

R01~R18의 기존 수정은 아래 검증 경로와 대조한다. 이 표는 새 기능의 구현 완료 선언이 아니다.

| 항목 | 확인한 경계와 회귀 근거 |
|---|---|
| R01 | Account/Book 삭제 commit 뒤 grant 발급·사용 차단, `PostgresTradingReviewStoreTest` |
| R02/R04 | quota 월 경계·capacity 예약과 polling lease fence, `PostgresBacktestQuotaStoreTest`, `PostgresBacktestPollingLeaseTest` |
| R03/R05 | Strategy command 잠금·immutable append와 정렬된 Reader, `PostgresStrategyStoreTest`, `JpaStrategyReaderAdapterTest` |
| R06 | 공통 bounded HTTP client·실제 지연/연결 실패와 retry, `ComputeHttpTimeoutTest`, Backtest/Asset compute adapter 테스트 |
| R07/R08 | Root 불변식·복원과 순수 Mapper, 각 domain·Mapper 테스트와 전체 Mapper source 검사 |
| R09/R10/R11 | Aggregate Store와 publication/lease/deletion capability 분리, port/worker 경계 검사·실제 PostgreSQL 삭제/발행 회귀; 기존 전체 Trading 후보 CI와 구분 |
| R12 | 모든 feature의 foreign domain/application classpath 검사, 이번 Auth Web·Backtest test 노출 제거 |
| R13 | web 실패 DTO와 checkpoint 기반 삭제 Reader, `PostgresTradingReviewStoreTest` 및 app HTTP 회귀 |
| R14 | V1~V21와 Hibernate validate, `CoreSchemaMigrationTest`·기존 Flyway/refresh fence migration 회귀 |
| R15 | 공개 health·명시적 node ID와 서로 다른 node ID 비중복, `OperationsConfigurationTest`, health/metrics 및 Snowflake 테스트 |
| R16 | 전체 46개 모듈 coverage·실제 classpath와 Core 자체 CI, 실행 리포트 gate는 6c-1의 29개에서 6c-3의 30개 테스트 모듈로 확대 |
| R17/R18 | 정책 시계·late publication 차단과 요청 시점 삭제 count, `PostgresTradingReviewStoreTest` 및 선행 전체 Trading 후보 CI |

| ID | 근거 | 판단과 후속 작업 |
|---|---|---|
| R19 | `auth/adapter/web/.../AuthController.kt`, web `SecurityConfiguration`과 security `JwtConfiguration` | 6c-2/[PR #109](https://github.com/refinvest-dev/core-api/pull/109)에서 raw credential을 inbound로 받고 application이 parser를 호출한다. 검증된 요청만 DB transaction에 진입하며 HTTP helper는 web이 소유한다. Web의 업무 의존성은 inbound port이며 security adapter 구현체와 직접 의존하지 않는다. 최신 회귀 검증은 PR 참조. |
| R20 | `tradingreview/port/.../account`, `book`, `importsession`, `normalization` | 6c-3/[PR #110](https://github.com/refinvest-dev/core-api/pull/110)에서 기능별 CQR/inbound 16개와 capability별 계약 파일로 분리했다. 결과 그래프는 Aggregate/Entity와 storage key를 노출하지 않으며 HTTP DTO는 port 결과에서 변환한다. Store/Reader와 원자적 capability는 유지한다. 최종 전체 검증과 리뷰 상태는 PR 참조. |

6c-1은 전체 검증의 누락과 위 classpath 노출을 보완했다. 6c-2의 R19는 #109로 머지됐다.
6c-3의 R20 코드 정리와 전체 회귀 검증도 #110으로 머지됐다. 이번 점검에서 추가로
식별한 R19/R20의 구현 완료와 아래 운영 제약 해소를 구분한다.
정본에만 있는 향후 AnalysisRun/Reprocessing 기능의 미구현은 이번 리팩토링에서 새 기능으로 구현하지 않는다.

## Operational Constraints

- Snowflake는 동시 writer마다 node ID를 다르게 배정해야 한다. Node 중복 할당과 프로세스 재시작 전후의
  timestamp high-water mark를 분산 검증하지 않는다. 재사용 시 이전 writer 종료와 시계 역행 방지 절차가 필요하다.
- schema 전환은 [Schema Migration Rollout](SCHEMA_MIGRATION_ROLLOUT.md), health/node 설정은
  [Operations Configuration](OPERATIONS_CONFIGURATION.md)을 따른다.
- Compute 0017의 단일 transaction migration 진단은 SQLSTATE `55006`으로 rollback되는 기존 제약이다.
  기존 `psql -f` 경계의 migration/replay 통과와 구분하며 Core 변경으로 해결됐다고 표시하지 않는다.
- Integration #49는 `801241f`로 main에 반영됐고 #50도 포함한다. 이전 후보 CI와 머지 후 main 조합의
  검증은 [완료·인계 기록](REFACTORING_HANDOFF.md)에서 구분한다. Integration 반영 대기를 현재 제약으로 남기지 않는다.
- 실제 운영 DB 전환·writer 배정과 배포 환경 점검 증거는 아직 확인하지 않았다. 인계 기록의 적용 전
  확인 항목과 기존 운영 절차를 따르며 CI 성공을 운영 배포 완료로 해석하지 않는다.

## Validation

- 로컬에서 `cleanTest test`를 실행하고 최종 JUnit 리포트의 29개 모듈·499개 테스트 통과를 확인했다.
  실제 PostgreSQL 10개 suite·144개 테스트를 포함하며 실패·오류·skip은 0개다. 최초 전체 실행은
  Trading domain worker의 소켓 연결과 Strategy PostgreSQL 연결 오류로 실패했고, 두 범위만 다시 실행해
  통과했다. 최초 실행의 native 종료 1과 실패 증적을 보존하며 전체 명령이 한 번에 성공했다고 표시하지 않는다.
- 새 Asset·공통·전체 Mapper source 테스트 3개와 결과 gate 검증 6개가 통과했다. 기존 검사의 금지 의존성
  누락 재현 후 새 검사의 해당 의존성 거절을 확인했다. 임시 모듈 추가 시 coverage 누락 거절도 확인했다.
- Core 자체 [PR #108](https://github.com/refinvest-dev/core-api/pull/108)의 Linux CI가 전체 모듈 경계·
  PostgreSQL 회귀·실행 JAR와 report gate를 검사한다. 최초 CI는 전용 컨테이너의 DB 이름이 migration
  테스트의 안전 조건과 달라 실패했고 `refinvest_integration`으로 맞췄다. 테스트 안전 조건은 유지했다.
  수정한 [Linux CI](https://github.com/refinvest-dev/core-api/actions/runs/41482472152)는 `63c8cde`에서
  29개 모듈·499개 테스트(실제 PostgreSQL 10개 suite·144개)를 실제 실행해 통과했다. 실패·오류·skip은
  0개이며 실행 JAR도 빌드했다. 이후 순수 계층의 기술 의존성 차단 보완은 로컬 경계 검사를 통과했다.
  최신 head의 CI 상태는 PR check에서 확인한다.
- 6c-1은 Context 문서 snapshot 26개가 일치하며 Production Kotlin·업무 API/schema/hash·ID 형식·계산 결정론
  변경은 없다. 따라서 6b의 실제 JAR·API/Web·전체 Trading [후보 CI](https://github.com/refinvest-dev/integration/actions/runs/41471547547)를
  기존 runtime 증적으로 참조하고 이번 CI와 구분한다. R19/R20 정리 뒤 필요한 통합 회귀를 다시 수행한다.
- 6c-2는 Auth application 단위 23개·web 단위 5개와 신규 source 경계를 검사한다. Credential 검증 실패의
  DB transaction 미시작과 쿠키 전송 속성을 검증하며, Spring Boot HTTP·PostgreSQL·API/Web 후보 CI를
  [PR #109](https://github.com/refinvest-dev/core-api/pull/109)에서 확인한다. 설정 키·claim·wire 의미와 계산
  결정론은 바꾸지 않는다. 로컬 소켓 오류와 테스트 설정 가정 수정은 통과 결과와 구분한다.
- Credential·fixture·임시 init script와 실행 증적은 커밋하지 않는다.
- 6c-3은 로컬 port/application/web/persistence/app 경계 137개가 통과했으며 PostgreSQL Trading Review
  70개를 포함한다. 정상 outbound compute port를 구현체로 오인한 경계 검사 실패 후 허용 범위를 해당
  port로 한정하고 다시 통과했다. 결과 그래프·단일 CQR 입력, 원본 만료 정각과 role projection을 검증한다.
  `537d25e`의 [Core CI](https://github.com/refinvest-dev/core-api/actions/runs/41605083219)는 30개 모듈·514개 테스트,
  실제 PostgreSQL 10개 suite·144개와 실행 JAR·결과 gate를 통과했다. 실패·오류·skip은 0개다.
  [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41605084847)와 [API/Web·전체 Trading 후보 CI](https://github.com/refinvest-dev/integration/actions/runs/41605088749)도
  같은 Core 코드에서 통과했다. API smoke·브라우저 11개와 전체 Trading raw-policy/schema fixture를
  포함하며 고정 ref는 실행 계획의 6c-3 기록을 따른다. 업무 API/schema/hash·ID와 계산 결정론 변경은 없다.
  문서 후속 커밋을 포함한 최신 head 검사는 #110에서 확인한다.
