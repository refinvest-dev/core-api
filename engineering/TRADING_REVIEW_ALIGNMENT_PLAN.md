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
| Web 구성 | Strategy의 기능/resource별 web package와 DTO | Trading Review의 Controller 8개와 여러 request/response가 두 포괄 파일 등에 모여 있음 |

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
  아래에 기록하며, 기능별 Service 분리·일반 저장의 JPA 전환·큰 실행 구현 정리는 순서 2~6으로 남는다.

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
