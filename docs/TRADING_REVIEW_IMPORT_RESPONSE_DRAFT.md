# Import 응답 projection·오류 정합성 (DRAFT)

상태: **ADR-076/IC-P04·P05 검토 후보·미채택**. 현재 OpenAPI/schema·handler를 수정하지 않는다.
[전체 리뷰](TRADING_REVIEW_IMPORT_CONTRACT_DRAFT.md)·[replay](TRADING_REVIEW_IMPORT_REPLAY_DRAFT.md)를 함께 읽는다.

## IC-P04: Session의 상태별 값

public Session은 API DTO이며 Aggregate/DB row·Run/Dispatch/lease의 직접 직렬화가 아니다.
id/Book 참조·입력 timezone/mode·정책·versions·createdAt·artifact 최대 두 개와 기존 status union을 유지한다.
Run UUID/Compute job ID·원본 locator/DEK·private staging·내부 expected version은 자동 노출하지 않는다.

| 상태 | qualitySummary | failure | createdLedgerRevisionId·completedAt |
|---|---|---|---|
| RECEIVED/VALIDATING | 없거나 null, 미계산을 0/빈 정상 report로 합성하지 않음 | 없음/null | Revision 없음, completedAt 없음/null |
| ACCEPTED | 검증·발행된 report non-null 필수 | 없음/null | 해당 Session의 발행 Revision·completedAt 필수 |
| REJECTED | key 필수, 완전 검증 report 없으면 null | non-null 필수 | Revision 없음, completedAt 필수 |
| PROCESSING_FAILED | 노출하면 null | non-null 필수 | Revision 없음, completedAt 필수 |

qualityStatus=REJECTED만으로 row count/capability/reconciliation을 채우지 않는다.
ACCEPTED report가 없으면 조회에서 합성하지 않으며 publication도 허용하지 않는다.
failure는 기존 code/retryable/userAction/occurredAt이고 safe allowlist를 지킨다.
원자료 오류의 REJECTED와 운영/integrity/delivery PROCESSING_FAILED를 구분한다.
단순 nullable struct로 모든 상태의 잘못된 조합을 허용하지 않도록 mapper/수용 검증을 둔다.
retention 뒤 누락된 불변 입력·quality를 current 설정으로 다시 계산하지 않는다.

### 보조 role·canValidate 필드 후보

Core는 세 필드를 이미 내보내고 integration harness는 canValidate/uploadedArtifactRoles에 의존한다.
Web source에서 미발견이라는 사실만으로 삭제하지 않는다. public Session schema에 **optional additive** 정의를
추가하는 우선안이다. 신규 Go mapper는 아래 의미로 제공하고 구형 response와의 overlap 동안 consumer는 부재를 고려한다.
schema required로 즉시 승격하거나 undefined를 true로 간주하지 않는다.

| 후보 필드 | 의미·권장 계산 |
|---|---|
| uploadedArtifactRoles | 해당 Session의 ACTIVE Artifact role inventory. unique·role 이름 기준 안정 정렬. raw TTL 가용성까지 뜻하지 않음 |
| missingArtifactRoles | 두 필수 role 중 위 inventory에 없는 role. uploaded와 disjoint이며 합집합은 두 role |
| canValidate | snapshot 시점 RECEIVED·현재 접근/부모 lifecycle 허용·정확한 role/소속·두 ACTIVE raw가 실제 expiry 전인 구조적 준비 힌트 |

canValidate=true는 validate 권한·향후 Guard·version 실행 가능성/Compute capacity의 보증이 아니다.
read 위임을 validate 위임으로 승격하지 않는다. 실제 admission은 현재 owner/Guard와 모든 입력을 다시 검증한다.
RECEIVED라도 expiry equality에서는 false다. expired ACTIVE metadata는 inventory에 남을 수 있으므로
missing이 비어 있어도 canValidate=false일 수 있다. source canValidate는 TTL을 직접 받지 않아 이 후보와 차이가 있다.
새 응답의 의미 개선은 실제 consumer/clock 경계 검증과 함께 채택하며 raw TTL을 연장하지 않는다.

### Artifact coverage와 detection

public ArtifactCoverage는 현재 schema의 nullable key다. verified Compute result의 artifactCoverages를
동일 Session/input/Revision·role에 연결해 역할별 earliest/latest·rowCount·sourceTimezone·SECOND·NOT_PROVEN으로 투영한다.
Compute InternalArtifactCoverage의 artifactRole은 public 부모 Artifact.role로 매칭하고 public coverage 안에 임의 key를 복사하지 않는다.
다른 Session/latest Revision의 coverage를 끼워 넣지 않는다. min/max는 연속/완전 export의 증명이 아니다.

validation 전·유효한 관측이 없는 failure·legacy proof 부재는 null이며 0/빈 시간·다른 role로 합성하지 않는다.
raw-only/TTL 뒤에도 유지 대상 Revision의 verified coverage는 보존한다. public rawReparseAvailable은 현재 raw
보관/차단·expiry를 반영하며 coverage 보존이 raw 접근권을 부여하지 않는다.

detectedDialect/schemaFingerprint는 현재 public nullable 필드지만 현재 InternalArtifactCoverage에는 없다.
검증·영속된 별도 정본 근거가 없으면 null을 유지한다. dialectCandidate/AUTO/filename이나 coverage에서 추정하지 않는다.
non-null 제공이 필요하면 Compute versioned output·영속/무결성 계약을 먼저 설계한다.
현재 Core mapper의 모든 coverage=null을 그대로 강제할 이유와 detection null의 근거 부족을 구분한다.

### Quality endpoint와 크기

GET quality-report는 200 **완전한 TradingQualityReport** 또는 409 RESULT_NOT_AVAILABLE다.
REJECTED Session.qualitySummary=null은 이 endpoint의 200 null 허용이 아니다.
ACCEPTED는 정확히 그 Session의 publication report·capability와 같아야 하고, 미완성/손상 저장 자료를 빈 정상 결과로 치환하지 않는다.
현재 고정 Core source에는 전용 route/Use Case가 없었다. 이 문서가 endpoint 구현/배포를 완료한 것은 아니다.

quality report의 validationIssues/count map 등은 현재 schema에 새 페이지/항목 수 상한이 없다.
임의 truncation·새 wrapper/partial flag·분할 endpoint는 이 후보에서 도입하지 않는다.
큰 응답의 bounded 수신/저장·전송/취소와 admission/publication 제한을 측정한 뒤 필요한 versioned 계약을 별도 리뷰한다.
result payload 전체 heap 적재·모든 Run history 로드가 기존 public 응답의 요구사항은 아니다.

## IC-P05: 접수 commit과 응답 시점

기존 성공 status/schema를 유지하는 우선안이다: Create 201 Session, Validate/Retry 202 Session,
Upload 신규 201/replay 200 Artifact, Delete 202 DeletionRequest, 조회 200 Session/Page/QualityReport.
Use Case receipt를 이 public body로 그대로 교체하지 않는다. Core source의 command→Get처럼
성공 후 현재 Query/projection을 조립할 수 있으며 Go 계층의 구체 구조는 Go가 결정한다.

| 순서/상황 | 후보 응답·복구 의미 |
|---|---|
| commit 전 known failure | 해당 오류. 원자 접수 자료 없음·부분 Object cleanup 별도 |
| 접수 commit 후 현재 Query 성공 | 원 operation의 201/202/200과 현재 허용 full DTO. Worker가 이미 terminal이면 그 상태도 정상 |
| 같은 key replay, 이미 terminal | 같은 status 의미와 현재 허용 resource. 새 Run/dispatch/Revision 생성 없음 |
| commit 후 조회 전에 owned 삭제 | 410 현재 접근 정책. 이미 접수됐을 수 있으므로 client는 같은 key로 복구/허용 status 조회 |
| commit 후 응답 조립 dependency 실패 | 기존 안전한 503 등 해당 오류. 미접수 보장 아님·새 key 자동 재시도 금지 |
| 응답 유실/commit UNKNOWN | 동일 key/의미 입력으로 상태 복구. SELECT 0건만으로 rollback/새 접수 확정 금지 |
| deletion 접수 뒤 | 202는 차단/요청 접수, purge 완료 아님. 정상 data API 대신 정당한 deletion control 조회/replay |

202 설명의 VALIDATING은 접수 전이이며 **응답 시에도 반드시 VALIDATING일 조건은 제거하는 후보**다.
최초 Worker가 빠르게 ACCEPTED/REJECTED/PROCESSING_FAILED가 돼도 정확한 DTO를 반환한다.
Create replay도 처음 RECEIVED에 머문다는 보장은 없다. 이를 숨기려고 오래된 상태를 합성하지 않는다.
기존 harness의 `validated.status == VALIDATING` 단정은 채택 시 receipt+current-state 수용과 단일 접수 증거로 보완한다.
실제 fast-terminal/삭제/UNKNOWN 테스트 없이 이 후보를 기존 E2E와 호환 완료라고 표시하지 않는다.

### operation별 오류 선언 후보

아래 현재 열은 고정 `core-api.yaml`에 **명시된 error status**다. 새 공개 code/message를 만들지 않고
공통 lifecycle/예외·source의 가능한 의미를 operation responses에 명시하는 우선안이다.
내부 service/위임 오류를 user CSRF/권한 실패로 무조건 번역하지 않으며 정확한 내부 오류 profile은 IC-P06 후속이다.

| operationId | 현재 명시 error status | 추가 명시 권장 |
|---|---|---|
| createTradingImportSession | 400,401,403,404,409,422 | 410,500,503 |
| uploadTradingImportArtifact | 400,401,403,404,409,413,415,422,503 | 410,500 |
| validateTradingImportSession | 400,401,403,404,409,410,422,503 | 500 |
| retryTradingImportSession | 400,401,403,404,409,410,503 | 422,500 |
| getTradingImportSession | 401,404,410 | 400,500,503 |
| listTradingImports | 401,404 | 400,410,500,503 |
| getTradingImportQualityReport | 401,404,409,410 | 400,500,503 |
| deleteTradingImportSession | 400,401,403,404,409 | 410,500,503 |
| deleteTradingSourceArtifact | 400,401,403,404,409 | 410,500,503 |

400은 유효하지 않은 path/query/body/key 형식, 410은 해당 owner scope/raw의 접근 불가,
422는 지원하지 않는 exact version 등 의미 입력, 500은 safe unexpected failure, 503은 일시 의존/운영 gate다.
known deletion replay/control을 무조건 410으로 차단하라는 뜻은 아니다.
foreign/absent는 404, owned tombstone은 410이며 세부 상태를 다른 owner에게 누출하지 않는다.
archive는 Account/Book 미채택 정책과 함께 검토하며 여기서 삭제와 동일시하지 않는다.

일반 오류는 기존 ErrorResponse code/message/traceId/retryable/details allowlist다.
Retry의 410은 **TradingImportRetryError**: userAction/occurredAt도 필수, retryable=false,
SOURCE_ARTIFACT_EXPIRED→REUPLOAD_REQUIRED / RESOURCE_DELETED→NONE이다. 일반 envelope로 축약하지 않는다.
Run의 비동기 failure와 HTTP error를 구분하고 error가 있는 정상 success Result를 만들지 않는다.
Restore 손상·DB timeout/UNKNOWN·조건부 Update 0건을 일괄 400/409로 매핑하지 않는다.
원문 exception·DB/SDK·object key·grant·stack을 응답/trace에 반사하지 않는다.

traceparent/tracestate의 중계와 오류 traceId/Worker trace의 상관관계를 검증한다. trace/key는 인증 증명이 아니며
브라우저 abort는 commit된 작업 취소가 아니다. request timeout·한 Compute HTTP 요청·attempt/run 정책·lease·raw TTL을 분리한다.
새 deadline 수치/취소 endpoint·ACK disposition은 이 후보에서 추가하지 않는다.

source: [Session response](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/importsession/TradingImportSessionResponse.kt), [Artifact response](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/artifact/TradingSourceArtifactResponse.kt),
[Session result](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port/inbound/importsession/TradingImportSessionResult.kt), [Domain readiness](https://github.com/refinvest-dev/core-api/blob/8a537fa03ecd4ff14f2348acb6ff5a2c0a6e63c2/tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/TradingImportSession.kt),
[현재 Compute coverage](https://github.com/refinvest-dev/context/blob/8351e18e83c981d0199d14c74b165233ce82bd21/openapi/compute-api.yaml), [실제 harness caller](https://github.com/refinvest-dev/integration/blob/05980836db117bb3ec26e236698956e20a91ca07/scripts/test_trading_normalization_runtime_contract.py).
