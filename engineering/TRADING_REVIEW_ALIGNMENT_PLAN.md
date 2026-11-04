# Trading Review Structure Alignment

## Scope

기준은 Core main `bb9ec50`과 Subscription·Member·Auth·Strategy의 현재 구현이다. 기존 0~6c-3의
Aggregate/불변식·동시성·저장 경계 보완과 회귀 검증은 보존한다. 해당 단계의 완료가 Trading Review의
패키지·서비스·저장 코드까지 다른 bounded context와 일관되게 정리됐다는 의미는 아니었다.
이번 목록은 후속 구조 개선 범위이며, 코드 차이와 재현한 기능 오류를 구분한다.

제품 Aggregate 경계·retention/version/hash·public/internal API의 정본은 Context snapshot이다.
Context 문서나 OpenAPI를 Core에서 직접 수정하지 않는다. 이 계획은 신규 Analytics/Reprocessing 기능을
추가하지 않으며 기존 5개 Trading Review Root와 Session 소속 Artifact/NormalizationRun 경계를 유지한다.

## Reference Implementations

| 기준 | 실제 참고 구현 | Trading Review 차이 |
|---|---|---|
| Port 방향 | Subscription 18개·Member 10개·Auth 18개·Strategy 40개 production port 파일은 inbound/outbound로 구분 | 115개 파일이 account/book/importsession/normalization 등의 바로 아래에 있으며 방향 구분이 없음 |
| 기능별 진입점 | `subscription/application/{create,upgrade,get,usage,policy}`의 기능별 Service | `TradingReviewService`는 Account/Book 8개, `TradingImportService`는 6개 use case를 한 클래스에서 구현 |
| 일반 Aggregate 저장 | `SubscriptionStore.save(Subscription)`와 command 조회의 Member 잠금, 별도 Reader | `insert/update(Boolean)`와 SQL 조건·row 추출·parameter 구성이 각 adapter에 반복 |
| JPA 모델 변환 | Subscription·Member·Strategy도 별도 PersistenceMapper 사용 | Mapper 분리 자체는 차이가 아님. JDBC Record·ResultSet·SQL parameter를 함께 관리하는 비용을 비교해야 함 |
| 조회 모델 | Strategy는 outbound `StrategyPageReadModel`과 inbound `ListStrategiesResult`를 구분 | `TradingPage`, `TradingDeletion`, `PublishedLedgerSummary`가 inbound 결과와 outbound 조회에 연결돼 있음 |
| Web 구성 | Strategy의 기능/resource별 web package와 DTO | 당시 Trading Review의 Controller 7개와 여러 request/response가 두 포괄 파일 등에 모여 있음 |

파일 수는 기능 복잡도나 품질 지표로 사용하지 않는다. 위 숫자는 검토한 source 범위다. Subscription의
단순한 모델을 Ledger 발행·worker queue에 그대로 적용하지 않고, 일반 업무 구조의 일관성을 참고한다.

## Findings

| ID | 근거 | 수정 방향 |
|---|---|---|
| T01 | `tradingreview/port`: 다른 context와 달리 inbound/outbound 구분 없음 | 모든 계약을 호출 방향과 모델 소유권으로 분류. 기능 package는 방향 아래에 유지 |
| T02 | `port/deletion/TradingDeletionWork.kt`: Store·lease/item과 `TradingDeletionUseCase`, `RemediateTradingDeletionUseCase` 혼재 | inbound 실행 계약과 outbound 작업 저장 계약을 독립 파일·package로 분리 |
| T03 | `TradingArtifactGrantController`가 `ArtifactReadGrantRedeemer`를 직접 주입하며 구현은 `JdbcArtifactAccessGrants` | 명시적 inbound 조회/use case와 application 진입점을 두고 DB·서명·storage 처리를 outbound에 유지 |
| T04 | `DeletionRequestReader`가 `TradingDeletion`을 반환하고 `TradingImportSessionView`가 `PublishedLedgerSummary`를 포함 | outbound read model과 inbound result 소유권 구분. 디렉토리만 옮겨 inbound↔outbound 의존을 새로 만들지 않음 |
| T05 | 16개 public use case의 CQR 전환 뒤에도 maintenance를 포함하면 기존 UseCase interface는 27개. `RemediateTradingDeletionUseCase`는 Root를 직접 반환 | public/query/remediation 입력·결과의 기준을 정리하고 Entity/Root 비노출 검사 범위를 확대. 인자가 필요 없는 worker 실행에 빈 Command를 강제하지 않음 |
| T06 | `TradingReviewService`, `TradingImportService`의 다수 execute overload·private 업무 메서드와 11개/15개 생성자 의존성 | Subscription처럼 기능별 Service/package로 나누고 실제 필요한 outbound port만 주입 |
| T07 | Account/Book 일반 저장까지 JDBC. Session adapter는 Root 복원·자식 동기화·상태/version/generation 방어를 함께 수행 | 일반 저장을 JPA 기준으로 재구성하고 Store/Reader를 구분. ORM 전환과 상태 경합 방어 보존을 별도 검증 |
| T08 | `JdbcArtifactAccessGrants`가 발급·사용, 서명/시간, DB와 RawArtifactStorage를 함께 처리. deletion/result SQL adapter는 각각 510/472줄 | capability와 기술 책임에 따라 분해할 지점 검토. 파일 길이만으로 나누거나 DB 원자적 연산을 여러 transaction으로 분산하지 않음 |
| T09 | Account/Book·Import의 `validKey/fingerprint` 중복. Normalization dispatcher 등의 직접 UUID 생성과 service 내부 retry/failure 분기 | 의미가 같은 검증만 공유하고 hash byte 규칙 보존. 영속 dispatch/attempt ID 생성은 기존 generator port 활용. lease token 등 기술 식별자는 성격을 먼저 분류 |
| T10 | Web request/result가 domain enum·failure에 연결되고 Result companion에 Domain 변환이 있음 | 외부 계약 모델과 application 변환 책임을 검토. 안정적인 공통 typed ID/enum까지 기계적으로 복제하지 않음 |
| T11 | `TradingPortBoundaryTest`는 기존 16개 업무 인터페이스와 특정 파일 부재를 검사. source 검사 일부는 모든 `.port` 참조를 허용 | 실제 inbound/outbound 참조, Controller·Scheduler 진입점, 전체 inbound 결과의 경계를 검사. 파일명 변경만으로 통과시키지 않음 |
| T12 | Account/Book/Import 업무 검증이 큰 PostgreSQL fixture에 집중. application 단위 검증은 integrity/streaming/analytics 중심 | 기능별 service 테스트와 Aggregate별 DB fixture로 책임을 구분하며 기존 실제 경합·rollback·raw policy 시나리오는 보존 |

## Target Port Layout

```text
tradingreview/port/
  inbound/
    account/{create,get,list,delete}/
    book/{create,get,list,delete}/
    importsession/{create,get,list,upload,delete,deleteartifact}/
    normalization/{validate,retry,dispatch,poll,receive,acknowledge,cleanup}/
    deletion/{get,process,remediate}/
    retention/{expire,purge,cleanup}/
    artifactgrant/redeem/
  outbound/
    persistence/{account,book,importsession,ledger,deletion,normalization}/
    compute/{normalization,result,deletion}/
    storage/{raw,workspace,analytics}/
    artifactgrant/
    identity/
    member/
    idempotency/
    normalization/policy/
  common/failure/
```

- UseCase와 Command/Query/Result는 `inbound/<기능>`이 소유한다. 전체 command/query/result 디렉토리를
  새로 만들지 않는다. Inbound가 outbound 저장/외부 DTO를 public 결과로 그대로 반환하지 않는다.
- Store/Reader와 조회 모델·CAS/lease/admission/publication 요청은 `outbound/<capability>`가 소유한다.
  Reader가 inbound Result를 만들어 반환하지 않도록 application에서 필요한 변환을 수행한다.
- `TradingPage`·삭제 projection·발행 summary는 사용처를 먼저 분리한다. 방향을 맞추기 위해 모든 모델을
  `common`으로 이동하지 않는다. 실제 양방향 공통인 실패 의미 등의 위치만 근거와 함께 명시한다.
- Controller·Scheduler는 inbound를 호출한다. Application은 inbound를 구현하고 outbound capability를
  사용한다. Persistence·Compute·Storage 구현은 자기 outbound 계약을 구현한다.
- `ArtifactReadGrantRedeemer`는 현재 호출 위치만 보고 inbound로 이름을 바꾸지 않는다. 기존 DB/storage
  capability와 application 진입점을 구분하며 single-use·상위 삭제·stream lifetime 보장을 유지한다.

## Persistence Direction

| 대상 | 목표 | 반드시 보존할 조건 |
|---|---|---|
| Account/Book | Subscription을 기준으로 JPA Entity/JpaStore·Aggregate Store/Reader 구성 | owner·불변 필드·version·deletion generation, 부모 잠금과 동시 생성/삭제 fence |
| ImportSession | 일반 저장/복원은 JPA 적용 대상으로 설계 검토하고 Account/Book 전환 결과를 먼저 반영 | Artifact/Run 소속 경계, bounded 복원, 슬롯 uniqueness, retry pin·retention·generation |
| DeletionRequest | 요청 Root 저장·조회와 실행 작업 capability를 구분해 일반 저장의 JPA 적용 범위 검토 | 요청 시점 count/checklist, tombstone·remediation·backup projection |
| Ledger/대량 result | 기존 bounded/stream/batch 저장과 원자적 publication을 유지 | 전체 row 메모리 적재 금지, immutable membership/hash, latest pointer·Session·ACK 일괄 commit |
| Worker/원자적 연산 | 선점·lease·CAS·purge checkpoint 등에는 JDBC/native SQL 유지 | stale 요청 무변경, 잠금 순서·lease 재검증, 외부 HTTP/file 작업은 DB transaction 밖 |

Subscription도 외부 Mapper를 사용하므로 모든 변환을 JPA Entity 안으로 옮기는 작업을 기본값으로 잡지
않는다. Entity의 `fromDomain/toDomain/update`를 선택한다면 로컬 Mapper 규칙을 먼저 명시적으로 갱신하고
하나의 Aggregate에서 중복 매핑을 만들지 않는다. 두 방식 모두 변환 중 조회·ID/현재 시각 결정·새 업무
전이를 수행하지 않아야 한다.

JPA 전환은 `save()` 이름이나 `@Version` 추가만으로 완료되지 않는다. Domain snapshot의 expected version,
잠금을 획득하는 시점, 부모 lifecycle과 generation을 검증한다. ORM 재조회 시 오래된 Domain의 version을
현재 Entity version으로 덮어 충돌을 숨기지 않는다. JDBC와 JPA 혼용 시 동일 transaction 참여와 영속성
컨텍스트의 stale 상태도 검증한다. 일반 저장에 `orphanRemoval`을 일괄 적용하지 않는다.

Session SQL의 상태 비교를 모두 Domain으로 옮기는 방식도 피한다. 업무 불변식의 중복 정의는 정리하되,
저장 시점의 expected status/version/generation·lease 및 publication gate 검사는 원자적 저장 책임으로 남긴다.

## PR Sequence and Gates

| 순서 | PR 범위 | 완료·회귀 기준 |
|---|---|---|
| 0 | 이 비교·실행 계획과 로컬 port 규칙 | 실제 근거·기존 완료 범위·목표·후속 PR 순서 명시. Production 변경 없음 |
| 1 | Port inbound/outbound, 혼합 선언 분리와 read model/result 소유권, grant inbound 진입점 | 전체 호출부·reflection/source 검사 갱신. Controller-outbound 우회 제거, 모든 inbound 결과 경계 검사. grant single-use·owner·상위 삭제·stream 처리 회귀 |
| 2 | 기능별 application Service와 resource별 web 구성 | Account/Book 8개·Import 6개 등 각각의 책임과 transaction 명시. 생성/조회/삭제·idempotency/replay의 의미와 HTTP DTO 보존 |
| 3 | Account/Book의 JPA 저장 기준 구현 | 기존 schema에서 validate, Store/Reader/매핑 round-trip, optimistic/command lock·삭제와 생성 경합·rollback·stale snapshot 거절. DB schema 변경은 필요 근거가 있을 때만 별도 migration |
| 4 | Session 및 요청 Root의 일반 저장 정리 | PR 3의 방식으로 적용 범위를 확정. Artifact/Run·정규화 admission·retention/deletion CAS 보존. JPA collection 전체 로딩이나 cascade가 Aggregate 경계를 결정하지 않음 |
| 5 | Normalization·Deletion의 실행/정책·기술 capability 정리 | retry/pin/hash·lease/시간 재검증·정확히 한 번 발행과 ACK 순서, grant 발급/사용과 storage 책임. 큰 파일을 단순 분할하지 않음 |
| 6 | 전체 구조 재점검과 main 통합 인계 | Core 전체 PostgreSQL/JUnit 실행 gate, 계약 snapshot·고정 vector, API/Web·전체 Trading fixture. 신규 구조와 남은 미구현 기능/운영 제약 구분 |

테스트는 마지막 PR까지 미루지 않고 각 변경 PR에 포함한다. 단순 package 이동은 선언 보존과 컴파일/
경계 검사로 확인하며, 새로운 동작 테스트는 실제 책임·동시성 보장 변경을 검증하는 데 사용한다.
기존 514개 테스트의 성공만으로 새 구조 기준까지 완료됐다고 표시하지 않는다.

## Progress

- 계획·규칙은 [Core #112](https://github.com/refinvest-dev/core-api/pull/112)로 머지됐다.
- 순서 1은 [Core #113](https://github.com/refinvest-dev/core-api/pull/113)에서 구현했다. 실제 후속 검증 결과를
  아래에 기록한다. 순서 2는 [Core #114](https://github.com/refinvest-dev/core-api/pull/114)로 반영됐다.
  순서 3의 Account/Book JPA 구현은 [Core #115](https://github.com/refinvest-dev/core-api/pull/115)이며,
  Session/DeletionRequest 일반 저장·큰 실행 구현 정리는 순서 4~6으로 남는다.

### PR 1: Port Direction and Entry Points

- 기존 115개 파일의 선언 135개를 방향·capability별 독립 파일로 재배치했다. 첫 재배치에서 의도한 조회
  계약 5개 외 130개 선언과 persistence 42개 파일의 SQL 보존을 확인했다. 공통 위치는 양쪽이 사용하는
  실패 의미 두 타입으로 한정하고, 페이지·삭제·발행 모델을 common으로 몰아넣지 않았다.
- 페이지는 outbound `TradingPageReadModel`과 inbound `TradingPage`로 나눴다. 발행 summary도 별도
  `PublishedLedgerResult`로 변환한다. 삭제 Reader는 Subscription처럼 Domain을 반환하며 application이
  `TradingDeletion` Result를 만든다. Root 복원 SQL과 체크포인트 projection의 의미는 유지했다.
- grant Controller는 `RedeemTradingArtifactGrantUseCase`를 호출한다. 기존 atomic consume·owner/상위 삭제
  gate·single-use와 원본 stream을 보존한다. 새 application은 stream을 미리 읽거나 닫지 않고 DB
  transaction을 추가하지 않는다. 민감 token Command는 진단 문자열에 credential을 포함하지 않는다.
- 삭제 조회·복구를 Query/Command 기반 진입점으로 정리하고 remediation의 Root 반환을 제거했다.
  결과 spool의 기술 Scheduler도 inbound use case로 진입한다. application이 기존 spool/workspace 정리를
  조율하며 설정 키·retention 24시간 상한·동일 cutoff·순서와 100개 한도를 유지한다. 별도 DB transaction은 없다.
- 전체 inbound 29개에서 Entity/Root·outbound 모델 비노출을 검사한다. 인자가 있는 요청은 단일
  Command/Query를 받으며 인자가 없는 worker에 빈 Command를 만들지 않는다. Web/Scheduler의 outbound
  직접 참조, 양방향 계약 참조와 파일·package 소유권도 검사한다.
- 수정 전 확대 검사에서 Root 반환과 outbound 사용을 실패로 재현했다. 새 경로 이전의 source 검사 실패와
  Windows PostgreSQL `Bad address: connect` 실패를 보존하고, 최종 관련 147개 테스트를 다시 실행해
  실패·오류·skip 0개를 확인했다. Port 2개·application 30개·web 14개·persistence 85개·app 경계 16개다.
  grant의 상위 삭제 차단·owner 격리·single-use는 새 inbound service를 거쳐 실제 DB로 검증한다.
- [Core CI](https://github.com/refinvest-dev/core-api/actions/runs/41668865521), [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41668952961),
  [API/Web·전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41668959722)는 Core `34c52e0`를
  고정해 실행한다. Context `0bcdf79`, Compute `113b0e2`, Web `9354dfe`, Integration `801241f`를 조합하며
  최종 결과는 PR에 기록한다. Core CI는 30개 테스트 모듈·524개 테스트와 실행 JAR·결과 gate를 통과했다.
  실제 PostgreSQL 10개 suite·144개를 포함하며 실패·오류·skip은 0개다. 계약 CI도 통과했다.
  로컬 계약·Context snapshot 26개와 Normalization/Analytics vector도 일치했다.
- HTTP/OpenAPI·schema·SQL 조건·version/generation/lease·retention 및 계산 hash 의미 변경은 없으며
  계산 결정론 영향은 없다. 이 단계에서 JPA 전환이나 포괄 Service 분리를 완료했다고 표시하지 않는다.

### PR 2: Use Case Services and Resource Web Adapters

- `TradingReviewService`의 Account/Book 8개와 `TradingImportService`의 6개 진입점을 각각의
  `application/{account,book,importsession}/<기능>` Service로 분리했다. 각 서비스는 inbound 하나만
  구현하고 실제 사용하는 outbound port를 주입받는다. 조회는 2~6개, 명령은 5~10개 의존성을 가지며,
  기존 포괄 서비스에 다시 위임하는 production facade는 두지 않았다.
- 명령과 read-only 조회의 transaction 설정을 유지했다. 업로드의 파일 staging·발행과 deferred metadata는
  DB transaction 밖에 있으며, `TransactionTemplate` 안의 Account→Book→Session 잠금·Root 변경·CAS·
  idempotency 완료 순서를 보존했다. 재요청과 저장 충돌 때의 불필요한 파일 정리도 같은 경계에서 수행한다.
- 순수한 key/page 검증·실패 구분·request fingerprint만 공유한다. fingerprint의 UTF-8 byte 길이·NUL·
  필드 순서와 operation ID를 유지하고 한글 이름의 고정 벡터로 확인했다. 정규화의 별도 hash 규칙을
  이 helper로 통합하지 않는다. Import 조회의 발행 summary 변환과 페이지당 한 번의 시각 평가도 유지한다.
- Controller·Request·Response는 resource별 package로 옮겼다. Account 하위 Book collection은
  `book/TradingAccountBookController`로 분리해 전체 Controller는 8개가 됐다. 모든 Controller에 동일한
  안전한 예외 처리를 적용하며 URL·HTTP status·JSON 형태와 multipart stream 처리를 보존했다.
- 업무 메서드 13개와 Web 선언 20개의 보존을 비교했다. Book 목록의 기존 부모 조회·lifecycle 검사는
  다른 application service를 의존하지 않도록 해당 Service로 옮겼다. PostgreSQL의 기존 경합·삭제·
  재요청 시나리오는 테스트 전용 조립체를 통해 실제 새 서비스를 실행하며 production 호환용 클래스는 없다.
- 수정 전 포괄 서비스가 새 구조 검사에서 실패함을 확인했다. 새 검사에서는 기능별 구현·package·의존성·
  transaction과 Controller의 inbound 진입·공통 예외 처리 범위를 확인한다. 단위 테스트는 생성/재요청/
  저장 실패와 업로드의 metadata·외부 I/O·잠금·CAS 실패 정리를 검증한다.
- 최종 로컬 관련 테스트 157개가 통과했다. Port 2개·application 37개·web 15개·persistence 85개·app
  경계 18개이며 실패·오류·skip은 0개다. 모듈 경계와 실행 JAR도 확인했다. 초기 테스트 대역의 Kotlin
  typed ID JVM 표현과 한글 벡터 기대값을 바로잡았고, Windows `Bad address: connect/listen` 실패 실행을
  성공 집계에 포함하지 않았다. 수정·재실행 뒤 실제 suite 수와 native exit code를 확인했다.
- [Core 전체 CI](https://github.com/refinvest-dev/core-api/actions/runs/41675023226), [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41675098590),
  [API/Web·전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41675103472)는 Core `a34b7a2`를
  고정해 실행한다. Context `0bcdf79`, Compute `113b0e2`, Web `9354dfe`, Integration `801241f`를 사용한다.
  세 CI 모두 통과했다. Core는 30개 모듈·534개 테스트와 실행 JAR·결과 gate를 확인했으며, 실제 PostgreSQL
  10개 suite·144개를 포함해 실패·오류·skip이 0개다. API/Web의 브라우저 11개도 통과했다. 로컬 Context
  문서 26개·OpenAPI·Normalization/Analytics vector 동기화도 통과했다.
- 전체 Trading fixture의 artifact에서도 5개 저장소 고정 커밋 일치를 확인했다. Raw policy clock 4개·삭제
  count snapshot 2개·version pin 시나리오 8개는 모두 PASS이고 process control 13개는 종료 코드 0이다.
  worker 복구 이벤트는 없으며 fixture 실행 시간은 483.234초다. Integration 사전 검사의 Windows 전용
  1 skip은 Core 전체 테스트의 skip 0개와 구분한다. Compute atomic-runner migration의 SQLSTATE 55006
  진단은 기존 제약이며 이 구조 변경으로 해결됐다고 표시하지 않는다.
- SQL·schema·Domain·port·Core↔Compute 계약과 계산 결정론 규칙은 바꾸지 않았다. Normalization admission/
  worker·Deletion 실행 구조는 순서 5의 범위이며, 이 PR로 모든 application 정리가 끝났다고 표시하지 않는다.

### PR 3: Account and Book JPA Baseline

- Account/Book 각각에 `@Version` JPA Entity·`{Aggregate}JpaStore`·순수한 PersistenceMapper와 독립적인
  Store/Reader를 구성했다. Root 참조는 scalar ID이며 collection 연관관계나 cascade를 추가하지 않는다.
  신규 ID는 `persist`로 저장해 기존 행을 merge로 덮어쓰지 않는다. Domain·port·schema 변경은 없다.
- 수정은 owner 조건의 행 잠금 이후 최신 Entity와 Domain snapshot을 비교한다. Expected version·불변 필드·
  lifecycle·deletion generation·시각·latest pointer를 검증하고, 오래된 Domain version을 현재 Entity의
  version으로 바꾸지 않는다. Book 저장은 부모 Account의 잠금·활성 상태·소유권·venue도 확인한다.
- JPA Entity 잠금 조회가 cached version을 먼저 검사해 adapter의 stale 판정 전에 예외를 던지는 경우를
  실제 JDBC 갱신으로 재현했다. Scalar `FOR UPDATE` 이후 refresh·명시적 비교로 수정했다. 일반 조회·목록은
  cache를 재사용하지 않는 constructor projection을 같은 Mapper로 복원하며 추가 refresh 읽기를 하지 않는다.
- 일반 수정은 JPA dirty checking과 version을 사용하며 JDBC cascade·publication이 변경을 볼 수 있도록
  즉시 flush한다. 값이 같은 저장도 version이 정확히 한 번 증가하며 overflow는 저장 전에 거절한다.
  PostgreSQL의 microsecond 시각 정밀도도 유지한다. 원자적 행 잠금·tombstone 조회의 짧은 native query와
  Ledger·삭제·worker SQL은 유지하며 모든 SQL을 ORM으로 전환했다고 표시하지 않는다.
- 기존 PostgreSQL fixture의 Account/Book 호출을 실제 Spring Data/JPA adapter로 옮겼다. 독립 SQL-only
  lease fixture는 별도의 기존 연결을 유지한다. Mapping round-trip, 중복 ID·stale/no-op/overflow 저장,
  cache와 JDBC 혼용, 부모 삭제·자식 생성 경합·publication pointer·rollback을 검증한다. 실제 Hikari pool에서도
  JPA/JDBC의 backend 연결 일치와 Root·idempotency의 동시 rollback을 확인했다.
- 현재 fresh schema는 Account/Book을 포함해 validate한다. 과거 pre-Flyway schema 재현은 당시 Entity
  범위로 고정하며, ORM 추가 때문에 역사적 schema가 달라지지 않도록 했다. Migration은 추가하지 않았다.
- 최종 로컬 persistence 90개와 app schema·pool·경계 25개, 총 115개가 통과했다. 실패·오류·skip은 0개다.
  모듈 경계·실행 JAR, Context 문서 26개·OpenAPI·Normalization/Analytics vector 동기화도 확인했다.
  Cache 재현 실패, fixture 연결·source 검사 수정과 Windows 소켓 오류 실행을 최종 성공과 구분해 보존했다.
- [Core 전체 CI](https://github.com/refinvest-dev/core-api/actions/runs/41754858148), [계약 CI](https://github.com/refinvest-dev/integration/actions/runs/41754894620),
  [API/Web·전체 Trading CI](https://github.com/refinvest-dev/integration/actions/runs/41754897869)는 Core `8926787`과
  Context `0bcdf79`, Compute `113b0e2`, Web `9354dfe`, Integration `801241f`를 고정한다. 최종 실행 결과와
  fixture artifact의 커밋·정책·종료 증적은 [Core #115](https://github.com/refinvest-dev/core-api/pull/115)에 기록한다.
- Core 전체 CI는 30개 모듈·541개 테스트와 모듈 경계·실행 JAR·결과 gate를 통과했다. 실제 PostgreSQL
  10개 suite·150개를 포함하며 실패·오류·skip은 0개다. 계약 CI도 통과했다. 이후 문서만 추가한 최종 HEAD의
  필수 CI 결과와 API/Web·전체 Trading fixture 증적은 같은 PR에서 구분해 확인한다.
- Core 전체 CI는 30개 모듈·541개 테스트와 모듈 경계·실행 JAR·결과 gate를 통과했다. 실제 PostgreSQL
  10개 suite·150개를 포함하며 실패·오류·skip은 0개다. 계약 CI도 통과했다. 이후 문서만 추가한 최종 HEAD의
  필수 CI 결과와 API/Web·전체 Trading fixture 증적은 같은 PR에서 구분해 확인한다.
- JPA/JDBC 참여는 [Spring transaction 지원](https://docs.spring.io/spring-framework/reference/data-access/orm/jpa.html),
  값이 같은 저장의 즉시 version 증가는 [Hibernate 잠금 동작](https://docs.jboss.org/hibernate/stable/core/javadocs/org/hibernate/LockMode.html)을
  참고하고 실제 PostgreSQL로 검증했다. 계산 hash·wire 계약·결정론 영향은 없다. 기존 운영 적용 제약은
  별도 Handoff의 범위를 유지한다.

### Next: Session and Deletion Request General Persistence

1. Session Store의 일반 저장·복원과 admission/publication·retention/deletion CAS를 먼저 분류한다.
   Artifact/NormalizationRun은 기존 Session 소속 경계를 지키며, ORM 연관관계나 orphan removal을 일괄 적용하지 않는다.
2. 일반 저장의 JPA 적용 범위를 확정하고 Store/Reader·순수 Mapper를 구성한다. Root/child version·역할 슬롯·
   retry pin·부모 lifecycle/generation과 bounded 복원을 보존하며 대용량 Ledger/result를 적재하지 않는다.
3. DeletionRequest의 요청 Root 저장·조회와 실행 lease/item/checkpoint capability를 구분한다.
   요청 시점 count/checklist·tombstone·복구 projection을 유지하고 stale 작업이 진행 상태를 바꾸지 못하게 한다.
4. 실제 PostgreSQL의 경합·rollback과 혼용 transaction을 회귀 검증한다. Normalization·Deletion 실행/정책의
   더 큰 분해는 순서 5에 남기며, 이번 단계의 일반 저장 범위와 섞지 않는다.
