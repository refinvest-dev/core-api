# TRADING_REVIEW_API.md — Trading Review API Contract

Analytics create/poll/input-grant/retry/terminal/ACK 의미는 ADR-069와 [Analytics Job Protocol](TRADING_ANALYTICS_JOB_PROTOCOL.md), [Result Delivery](TRADING_ANALYTICS_RESULT_DELIVERY.md)가 정본이다. 정확한 wire 필드와 vendor media type은 `openapi/compute-api.yaml`을 따른다. 공개 Core API는 이 내부 Compute endpoint를 노출하지 않는다.

이 문서는 Trading Review의 공개 Web→Core API와 내부 Core→Compute API의 의미, 흐름, 상태 및 오류
계약이다. 정확한 HTTP path와 schema는 [`../openapi/core-api.yaml`](../openapi/core-api.yaml)과
[`../openapi/compute-api.yaml`](../openapi/compute-api.yaml)이 정본이다. 논리적 저장 구조와 transaction
경계는 [`TRADING_REVIEW_PERSISTENCE.md`](TRADING_REVIEW_PERSISTENCE.md), 결정과 이유는
[`DECISIONS.md`](DECISIONS.md)의 ADR-063을 따른다. 계산, lifecycle, version과 acceptance 의미를 이 문서에서
다시 정의하지 않는다.

---

## 1. 경계와 공통 원칙

```text
Web ── public JWT Cookie + CSRF ──> Core
Core ── durable dispatch + Internal API Key ──> Compute
Core <──────────── polling + terminal payload ── Compute
```

- Web은 Core만 호출한다. Compute URL, job ID, artifact grant, payload handle은 public API에 노출하지 않는다.
- Core가 owner authorization, 장기 Aggregate, raw object, canonical ledger, immutable result, latest pointer,
  deletion과 idempotency를 소유한다.
- Compute는 normalization/reconciliation/analytics와 `ComputeJob`, attempt, 최대 24시간 terminal payload만
  소유한다. Core table을 읽거나 쓰지 않는다.
- 공개/내부 비동기 흐름은 callback이 아니라 polling이다. Core는 기존 durable dispatch 패턴을 재사용한다.
  Acceptance 문서의 “terminal callback” assertion은 별도 inbound callback endpoint가 아니라 Core가 polling으로
  읽은 terminal descriptor/payload를 적용하는 동일한 verification boundary를 뜻한다.
- `BacktestRun`, Backtest `Trade`와 Trading Review run/record는 타입, endpoint와 저장 경계를 공유하지 않는다.
- 모든 ID는 opaque string이다. 실제 Member UID, raw account identifier, full Trade/Order ID와 storage path를
  request/response에 넣지 않는다.

## 2. Raw artifact upload와 전달

### 2.1 선택한 MVP 흐름

MVP는 **Web이 multipart로 Core에 업로드하는 방식(Option A)**을 사용한다.

1. Web은 `POST /trading-import-sessions/{importSessionId}/artifacts`에 metadata JSON part와 CSV file part를
   전송한다.
2. Core는 인증/owner/CSRF/idempotency/role slot을 검사하고, streaming SHA-256과 byte size를 검증하며,
   artifact별 envelope encryption으로 private object storage에 기록한다.
3. object write, encryption과 read-after-write 검증이 모두 끝난 뒤에만 artifact를 `UPLOADED`로 등록한다.
   실패하거나 끊긴 upload는 role slot을 소비하지 않으며 incomplete object를 cleanup한다.
4. 두 role이 `UPLOADED`여도 자동 validation하지 않는다. Web이
   `POST /trading-import-sessions/{importSessionId}/validation`을 명시적으로 호출한다.
5. Core는 normalization dispatch 시 각 object에 대해 **최대 5분**, single-read, job-bound인 signed internal
   grant를 발급한다. public URL이나 영구 download URL은 만들지 않는다.
6. claim 전 대기 중 만료는 같은 Compute job의 `REPLACE_QUEUED_GRANTS`로, claim 후 만료·부분 소비·
   worker crash는 `START_RETRY_ATTEMPT`로 처리한다(ADR-064). 생성 POST를 다시 보내 token/grant를
   교체하지 않는다. Core는 raw TTL과 deletion generation을 확인한 뒤 별도 grant update key로
   재발급하며 terminal job은 갱신하지 않는다.

생성 전에도 grant의 job binding을 검증할 수 있도록 Core는 `coreLogicalJobId`,
`coreImportSessionId`, `coreArtifactId`, role, raw SHA-256, `attemptToken`,
`deletionGeneration`을 signed grant에 묶는다. Compute `jobId`는 발급 전 존재하지 않으므로
필수 서명 claim이 아니다. Core는 redemption에서 claim/만료/단일 read/삭제를 검사하고 Compute는
job의 pinned descriptor와 받은 bytes의 role·size·SHA-256을 검사한다.

grant 발급·소비·거절은 actor class, opaque artifact/job ID, action과 시각만 audit한다. signed URI, filename,
checksum 전체와 거래 payload는 audit/log에 복제하지 않는다.

Core를 upload termination 지점으로 두면 browser에 object-storage credential을 주지 않고 기존 JWT Cookie와
CSRF 경계를 그대로 적용할 수 있다. MVP의 두 CSV와 수동 upload 규모에서는 이 단순성이 upload URL
발급/finalize 상태를 추가하는 것보다 중요하다. 부하 측정 결과 Core streaming이 병목이면 새 ADR/API version으로
제한된 direct upload를 검토한다.

### 2.2 확정된 upload 계약

| 항목 | 계약 |
|---|---|
| 파일 수 | session role별 정확히 하나, `TRADE_HISTORY`와 `POSITION_HISTORY` |
| 최대 크기 | artifact 하나당 50 MiB(52,428,800 bytes). 초과는 `413 FILE_TOO_LARGE` |
| Content-Type | outer `multipart/form-data`; file part는 `text/csv` 또는 `application/vnd.ms-excel`만 허용 |
| filename | 선택 metadata이며 path로 사용하지 않는다. basename 표시도 raw lifecycle 동안만 보관하고 raw와 함께 purge |
| checksum | client가 raw bytes의 lowercase SHA-256을 metadata에 필수 제공; Core streaming hash 불일치는 `422 CHECKSUM_MISMATCH` |
| dialect | client는 `AUTO` 또는 role에 맞는 V1 candidate를 선언할 수 있다. Compute의 실제 detection 결과가 정본 |
| 완료 판정 | encrypted object write와 verification 완료 뒤 `uploadStatus=UPLOADED` |
| 부분 upload | artifact/resource를 만들지 않고 temporary bytes를 삭제; 같은 idempotency key로 재시도 가능 |
| 중복 | 같은 session/role/hash는 기존 artifact 반환; 같은 slot의 다른 hash는 `409 ARTIFACT_ROLE_CONFLICT` |
| malware | CSV/text allowlist, size/encoding/parser 제한과 storage isolation을 적용한다. 범용 malware 판정은 MVP 보장 아님 |
| formula injection | source cell은 실행하지 않는다. 향후 export/UI는 `=`, `+`, `-`, `@` 시작 cell을 formula로 실행하지 않게 escape |
| logging | raw bytes/row/value, filename, signed grant, URL/path와 checksum 전체를 log하지 않는다 |

50 MiB는 공개 계약 상한이다. reverse proxy/object storage의 infrastructure limit은 이보다 낮을 수 없고,
상한 변경은 OpenAPI와 client를 함께 변경한다.

## 3. Public Core operations

모든 목록은 기존 `page`/`size` offset pagination을 사용한다. `size`는 기본 20, 최대 100이다.

| Use Case | Method and path | Success |
|---|---|---|
| `CreateTradingAccount` | `POST /trading-accounts` | `201` |
| `GetTradingAccount` | `GET /trading-accounts/{tradingAccountId}` | `200` |
| `ListTradingAccounts` | `GET /trading-accounts` | `200` page |
| `CreateTradingBook` | `POST /trading-accounts/{tradingAccountId}/books` | `201` |
| `GetTradingBook` | `GET /trading-books/{tradingBookId}` | `200` |
| `ListTradingBooks` | `GET /trading-accounts/{tradingAccountId}/books` | `200` page |
| `CreateTradingImportSession` | `POST /trading-books/{tradingBookId}/imports` | `201` |
| `UploadTradingImportArtifact` | `POST /trading-import-sessions/{importSessionId}/artifacts` | `201` or replayed `200` |
| `ValidateTradingImportSession` | `POST /trading-import-sessions/{importSessionId}/validation` | `202` |
| `RetryTradingImportSession` | `POST /trading-import-sessions/{importSessionId}/retry` | `202` |
| `GetTradingImportSession` | `GET /trading-import-sessions/{importSessionId}` | `200` |
| `GetTradingImportQualityReport` | `GET /trading-import-sessions/{importSessionId}/quality-report` | `200` |
| `ListTradingImports` | `GET /trading-books/{tradingBookId}/imports` | `200` page |
| `GetLedgerRevision` | `GET /ledger-revisions/{ledgerRevisionId}` | `200` |
| `ListLedgerRevisions` | `GET /trading-books/{tradingBookId}/ledger-revisions` | `200` page |
| `RunTradingAnalysis` | `POST /ledger-revisions/{ledgerRevisionId}/analyses` | `202` |

| `PollTradingAnalysisStatus` | `GET /trading-analysis-runs/{analysisRunId}` | `200` |
| `ListTradingAnalysisRuns` | `GET /trading-books/{tradingBookId}/analysis-runs` | `200` page |
| `GetTradingReview` | `GET /trading-analysis-runs/{analysisRunId}/review` | `200` |
| `GetBehaviorMetric` | `GET /trading-analysis-runs/{analysisRunId}/metrics/{metricId}` | `200` |
| `ListReviewUnits` | `GET /trading-analysis-runs/{analysisRunId}/review-units` | `200` page |
| `GetReviewUnit` | `GET /trading-analysis-runs/{analysisRunId}/review-units/{reviewUnitId}` | `200` |
| `GetReviewUnitEvidence` | `GET /trading-analysis-runs/{analysisRunId}/review-units/{reviewUnitId}/evidence` | `200` page |
| `RequestTradingReprocessing` | `POST /trading-books/{tradingBookId}/reprocessing-runs` | `202` |
| `PollTradingReprocessingStatus` | `GET /trading-reprocessing-runs/{reprocessingRunId}` | `200` |
| `ListTradingReprocessingRuns` | `GET /trading-books/{tradingBookId}/reprocessing-runs` | `200` page |
| `CancelTradingReprocessing` | `POST /trading-reprocessing-runs/{reprocessingRunId}/cancellation` | `202` |
| `DeleteTradingSourceArtifact` | `DELETE /trading-source-artifacts/{artifactId}` | `202` |
| `DeleteTradingImportSession` | `DELETE /trading-import-sessions/{importSessionId}` | `202` |
| `DeleteTradingBook` | `DELETE /trading-books/{tradingBookId}` | `202` |
| `DeleteTradingAccount` | `DELETE /trading-accounts/{tradingAccountId}` | `202` |
| `GetTradingDeletionRequest` | `GET /trading-deletion-requests/{deletionRequestId}` | `200` |

`RunTradingAnalysis`는 LedgerRevision의 검증된 V2 `analyticsInputSupplement` 보존 여부를 admission에서 확인한다. 없는 V1 Revision은 `422` 공통 `ErrorResponse` (`code=ANALYTICS_INPUT_UNAVAILABLE`, `retryable=false`, 안전한 message `이 거래 원장 버전에는 분석 입력 근거가 없습니다.`, 민감정보 없는 `traceId`)로 응답하고 Run/artifact/Compute job을 만들지 않는다. 조회는 계속 가능하다. compatible source/terms가 있으면 명시적 ledger reconstruction/rebuild로 새 V2 Revision을 발행한 뒤 분석할 수 있고, 자동 재해석이나 기존 Revision 수정은 없다.

`FinalizeTradingImportArtifact`는 없다. Core가 multipart stream을 끝까지 읽고 encrypted object를 검증하는
transactional boundary가 finalize 역할을 한다. `GetTradingResultHistory`, reprocessing difference와 comparable
trend는 TR-2 public surface이므로 이번 OpenAPI에는 추가하지 않는다. version/lineage metadata는 현재
Revision/Run/Review 응답에서 손실 없이 제공한다.

## 4. Public protocol semantics

### 4.1 Authentication, ownership와 CSRF

- 기본 security는 기존 `REFINVEST_ACCESS_TOKEN` HttpOnly JWT Cookie다.
- 모든 public state-changing Trading Review operation은 `X-XSRF-TOKEN`을 필수로 한다. file upload도 예외가
  아니다.
- owner는 client payload의 owner/member ID가 아니라 authenticated principal로 결정한다. request schema에
  owner ID가 없다.
- 존재하지 않는 resource와 다른 owner의 resource는 모두 `404 RESOURCE_NOT_FOUND`다. storage lookup이나
  payload parsing 전에 authorization scope를 확인한다.
- owner가 deletion을 이미 요청한 resource는 tombstone 보관 중 `410 RESOURCE_DELETED`이며, 다른 caller에게는
  계속 `404`다. deletion status endpoint만 former owner가 request ID로 조회할 수 있다.

### 4.2 Idempotency

Trading Review의 모든 public command는 `Idempotency-Key`가 필수다. 값은 1~128자의 opaque ASCII
`[A-Za-z0-9._~-]`이며 owner + operationId scope다. key 자체는 계산 hash가 아니다.

| 상황 | 결과 |
|---|---|
| same key + same canonical payload, 진행 중 | 최초 resource와 현재 상태 반환; 새 dispatch 없음 |
| same key + same payload, 완료 | 최초 resource/result 반환 |
| same key + different payload | `409 IDEMPOTENCY_PAYLOAD_CONFLICT` |
| transient pre-acceptance failure | resource가 없으면 같은 key retry 가능; acceptance가 있으면 기존 resource 반환 |
| terminal domain failure | 기존 terminal resource 반환. 새 corrected logical request는 새 key/session/run 사용 |

transport replay record의 최소 보관은 24시간이다. 그 뒤에도 upload/validation은 session/role unique constraint,
analysis/reprocessing은 logical key와 successful deterministic result unique constraint, deletion은 180일 deletion
ledger로 중복 생성을 막는다. Aggregate가 더 오래 남는 동안 logical mapping도 함께 남긴다.

### 4.3 Decimal, ratio, duration과 time

- 금융 Decimal은 JSON number가 아니라 `DecimalString`을 사용한다. 문법은
  `^-?(0|[1-9][0-9]*)(\.[0-9]+)?$`이며 exponent와 leading `+`를 허용하지 않는다.
- `Money`는 `amount`와 `currency`, `Quantity`와 `Price`는 `value`와 source scale을 보존하는
  `sourceScale`, 비율/percentage point는 exact `Rational(numerator, denominator)`로 구분한다.
- duration은 non-negative integer microseconds와 필요할 때 ISO-8601 duration을 함께 사용한다.
- API timestamp는 offset을 포함한 RFC 3339 `date-time`이다. canonical instant는 UTC `Z`다.
- source local timestamp는 `localValue`와 `sourceTimezone`을 분리한다.
- Trade `Time`과 Position `Opened`/`Closed`는 모두 second-precision exact local timestamp다. pinned
  `sourceTimezone`을 적용한 canonical UTC instant로 반환하며 filename에서 timezone을 추론하지 않는다.

### 4.4 Error envelope

Trading Review error는 공통 `ErrorResponse`를 사용한다.

```text
code, message, traceId, retryable, details?
```

`details`는 field/role/상태/count처럼 allowlist된 구조만 포함한다. raw source value, UID/fingerprint, Symbol,
거래 시각·수량·가격·fee/PnL, filename/path/URL은 금지한다. 비동기 command의 `202`는 job 접수 성공일 뿐
domain 성공이 아니다. 이후 terminal rejection/failure는 session/run status의 구조화된 `failure`로 조회한다.

## 5. Import와 LedgerRevision

### 5.1 Import request/status

`CreateTradingImportSession`은 Book ID(path), IANA `sourceTimezone`, IANA `reviewTimezone`,
`declaredPositionMode=ONE_WAY`와 optional exact `dataPolicyVersion`을 받는다. 생략하면 Core가 admission 시
현재 immutable policy ID를 resolve해 session에 저장한다. mutable `latest` 문자열은 저장하지 않는다.

Import status는 다음을 함께 반환한다.

- `RECEIVED | VALIDATING | ACCEPTED | REJECTED | PROCESSING_FAILED`
- 두 artifact의 role, safe filename metadata, content type, byte size, checksum, dialect candidate/detected,
  upload/retention 상태와 `rawExpiresAt`
- role별 earliest/latest observed instant, row count, source timezone, `SECOND` precision,
  `coverageCompleteness=NOT_PROVEN`; min/max를 complete export 보장으로 표현하지 않음
- ignored non-trading row count, validation issues(row number + field + safe code, raw value 없음)
- reconciliation/quality summary와 capability
- `createdLedgerRevisionId`(ACCEPTED에서 필수)
- pinned Normalizer/Reconstruction/Data Policy version sets와 implementation digest
- structured failure, created/validation-started/completed timestamps

| Import 상태 | failure | qualitySummary | LedgerRevision |
|---|---|---|---|
| `RECEIVED` | 없음 | null 또는 미평가 | 없음 |
| `VALIDATING` | 없음 | null 또는 미평가 | 없음 |
| `ACCEPTED` | 없음 | 검증된 Compute `COMPLETED` payload의 non-null report 필수 | 존재; `createdLedgerRevisionId` 필수 |
| `REJECTED` | non-null 필수 | 필드는 필수, 완전한 report가 없으면 null | 없음 |
| `PROCESSING_FAILED` | non-null 필수 | 요구하지 않음; 노출 시 null | 없음 |

`ACCEPTED`의 공개 qualitySummary는 발행된 LedgerRevision의 quality report와 같아야 한다. report가
없으면 Core는 publication하지 않는다. `REJECTED`에서 null은 미계산·미완료이며 실제 0이 아니다.
Core는 `QualityStatus.REJECTED`를 근거로 빈 row count, capability, reconciliation count를 합성하지
않는다. V1 Compute `FAILED`는 safe structured failure만으로 유효하며 성공 payload나 quality report를
요구하지 않는다. Core는 source rejection code만 공개 `REJECTED`로, integrity·delivery·운영 실패는
`PROCESSING_FAILED`로 분류한다. 어느 실패도 LedgerRevision 또는 Book `latestRevisionId`를 변경하지 않는다.
행·필드 단위 rejection 진단은 향후 별도 versioned `RejectionDiagnostics` 계약에서 정의한다.

`PROCESSING_FAILED`는 bounded 자동 재시도를 소진한 Compute/네트워크/storage 운영 실패다. 같은 Session의
active raw와 exact VersionSet을 다시 확인한 명시적 retry만 `VALIDATING`으로 전이하고 새
`NormalizationRun`/attempt/audit event를 만든다. 동일 logical input fingerprint를 유지한다. raw가
만료됐으면 `410 SOURCE_ARTIFACT_EXPIRED`와 `REUPLOAD_REQUIRED`를 반환한다. 삭제 tombstone 이후에는
retry를 받지 않는다. 공개 failure는 `code`, `retryable`, `userAction`, `occurredAt`을 포함하며
`RETRY | REUPLOAD_REQUIRED | WAIT_FOR_RECOVERY`를 구분한다. 내부 exception, endpoint, object key,
signed URI/query 및 stack trace는 반환하지 않는다.

`RECEIVED`에서 두 role이 모두 `UPLOADED`인지 artifact 목록으로 확인한다. 누락 상태에서 validation command는
동기 `422 MISSING_REQUIRED_ARTIFACT`; session은 `RECEIVED`에 남는다. Compute가 schema/product/conflict를
발견한 경우 command 자체는 `202`, 이후 session은 `REJECTED`다. 일부 episode만 부적격이면 session과
Revision은 accepted되고 quality/exclusion을 명시한다.

### 5.2 LedgerRevision response

Revision은 ID, Book ID, Book별 revision number, inputSetHash, role별 artifact ID/SHA-256,
source/review timezone와 선언된 position mode/product,
content hash, source ImportSession IDs(MVP 정확히 하나), coverage start/end/completeness,
canonical record/episode count, pinned `InstrumentDescriptor`/terms version, Normalizer/Reconstruction
version sets, reconciliation manifest hash, capability/quality, parent Revision IDs, reprocessing lineage와
`createdAt`/`publishedAt`을
반환한다. full canonical record나 full manifest를 기본 response에 inline하지 않는다.

두 번째 Import는 첫 Revision에 누적되지 않는다. latestRevisionId는 전체 계정 이력이 아니다.
분석은 명시적으로 고정된 Revision 또는 AnalysisDatasetSnapshot만 사용한다. 복수 Revision 통합은 후속
Dataset composition 책임이다. 불완전 coverage는 그 자체로 거부가 아니며 Capability를 제한할 수 있다.

Import/quality response는 사용자 선언과 same-session upload, reconciliation evidence를 actual account identity
proof와 구분한다. Trade UID는 public response/evidence/log에 반환하지 않으며 Position artifact에 UID가 없으므로
CSV pair만으로 동일 account/sub-account를 검증했다고 표시하지 않는다.

하나의 accepted Revision과 record membership은 불변이다. canonical record 자체의 content identity가 같은
경우 한 owner/Book 안에서 여러 Revision이 같은 immutable record를 참조할 수 있지만 membership은 별도
`LedgerRevisionRecord` 관계로 고정한다. 같은 source identity에 다른 content가 서로 다른 Session에서
나오면 별도 record/Revision으로 보존하며 이 API의 import validation에서 두 Revision을 합치거나
충돌 해소하지 않는다. 같은 Session 내부 충돌만 `RECORD_CONFLICT`다. cross-owner deduplication은 금지한다.

이번 source correction은 OpenAPI `0.3.0`에서 Position summary의 `openedInterval`/`closedInterval`을 exact
`openedAt`/`closedAt`으로 교체하고 role별 `ArtifactCoverage`를 추가한 의도적 계약 변경이다. 기존 interval
payload를 새 exact timestamp로 암묵 변환하지 않는다. 구현 시 `canonicalSchemaVersion`, adapter와
reconstruction/reconciliation compatibility를 새 exact version으로 등록하고 기존 accepted Revision이 있다면
ADR-062의 immutable reprocessing 경계를 적용한다. 이 의미는 `0.4.0`에도 유지되며 Backtest schema에는 영향이 없다.

## 6. Analysis와 Review

`RunTradingAnalysis`는 path의 Revision, exact `AnalyticsVersionSet` 또는 server-selected current set,
canonical `AnalysisConfig`를 고정한다. server-selected set도 admission에서 exact IDs/hash로 resolve해 Run에
저장한다.

```text
analysisLogicalKey = SHA-256(canonical JSON {
  owner scope,
  ledgerRevisionContentHash,
  reconciliationManifestHash,
  analyticsVersionSetHash,
  analysisConfigHash,
  deletionGeneration
})
```

Run status에는 Run/Book/Revision ID, `PENDING | RUNNING | COMPLETED | FAILED`, progress stage, pinned version
tuple/config hash, quality status, structured failure와 timestamps가 있다. `COMPLETED`에는 `resultId`와
`resultHash`가 반드시 있다. result 없는 completion payload는 Core가 `INCOMPLETE_ANALYSIS_RESULT`로 거절하고
Run을 `FAILED`로 만든다.

Review/Metric은 최소 다음을 반환한다.

- analysis period, population/eligible/excluded count와 reason counts
- capability snapshot과 quality summary
- 세 `BehaviorMetric`, 각 `MetricStatus`, definition version, finding eligibility
- subject/comparison/excluded membership reference와 evidence reference
- exact Decimal/rational/duration scalar, result/evidence hash
- `fundingTreatment=EXCLUDED`, `outcomeBasis=NET_TRADING_PNL`
- source Revision, VersionSet, config, lineage와 result availability

Finding이 0개인 `COMPLETED`는 허용한다. unavailable과 실제 0은 schema의 nullable value와 MetricStatus로
구분한다.

## 7. Evidence query

MVP는 **단계별 resource endpoint + paged evidence chain**을 사용한다.

1. `GetBehaviorMetric`이 subject/comparison/excluded membership의 opaque references와 count를 반환한다.
2. `ListReviewUnits`/`GetReviewUnit`이 `FuturesPositionEpisode`와 outcome/reconstruction/reconciliation을
   반환한다.
3. `GetReviewUnitEvidence`가 paged `PositionAllocation → TradingRecord → SourceEvidenceSnapshot` bundle을
   반환한다. 한 allocation이 가리키는 chain은 page 경계에서 분리하지 않는다.

각 evidence item은 `SUPPORTING | COMPARISON | EXCLUDED_CANDIDATE | SELECTION_BOUNDARY` membership role,
artifact role, source row number/hash, masked source identifiers와 versioned allowlisted source fields를 가진다.
원본 UID/account fingerprint/full ID는 반환하지 않는다.

raw가 남아 있으면 `rawAvailability=AVAILABLE`만 표시하며 raw bytes/full row/download URL을 inline하지 않는다.
raw 삭제 뒤에도 snapshot은 조회 가능하고 `rawAvailability=DELETED`, `evidenceKind=RETAINED_SNAPSHOT`,
masking policy version과 `rawReparseAvailable=false`를 반환한다. 삭제된 snapshot/상위 scope 자체는 조회할 수
없다.

## 8. Reprocessing

request는 source Revision, optional source AnalysisRun, exact target VersionSet, reprocessing type, bounded
reason code, optional AnalysisConfig와 `publishAsLatest`를 고정한다. `reason`은 자유형 금융 payload가 아니라
enum/bounded code다.

```text
reprocessingLogicalKey = SHA-256(canonical JSON {
  owner scope,
  tradingBookId,
  sourceRevisionContentHash,
  sourceAnalysisResultHash?,
  targetVersionSetHash,
  analysisConfigHash?,
  reprocessingType
})
```

status는 input availability, source/target versions, `PENDING | VALIDATING_INPUT | RUNNING | COMPLETED | FAILED |
CANCELLED`, progress, created child Revision/AnalysisRun, difference summary, failure, lineage와 timestamps를
반환한다. raw가 없는 `REPARSE_SOURCE`는 `202`로 받아 실행시키지 않고 admission에서 동기
`410 REPROCESSING_SOURCE_UNAVAILABLE`로 거절한다. registry/compatibility처럼 즉시 판정 가능한 오류도
`422`; 실행 중 발견되는 failure는 Run terminal status다.

취소는 best effort다. publication 전에는 같은 Run을 `CANCELLED`로 만들며, 이미 `COMPLETED`이면 existing
terminal Run을 반환하고 결과를 되돌리지 않는다. child Revision/Run과 latest pointer는 모든 hash, lineage,
generation 검증을 통과한 final Core transaction에서 함께 publish한다.

## 9. Deletion

각 delete path가 target type/ID를 결정하므로 request body로 임의 target을 받지 않는다. `Idempotency-Key`가
logical deletion request identity다. response/status는 다음을 반환한다.

- deletion request ID, target type과 opaque target ID, requested scope summary
- `REQUESTED | CANCELLING_JOBS | PURGING_PRIMARY | PURGING_OBJECTS | COMPLETED | FAILED`
- `revokedAt`, primary/object/Compute runtime purge status와 affected resource counts
- backup status와 `backupPurgeDueAt`
- safe failure, retryable, request/start/update/complete timestamps

storage path, object key와 provider error payload는 반환하지 않는다. delete transaction은 tombstone과
generation 증가를 먼저 commit해 access/admission/retry를 즉시 차단한다. DB/object/runtime은 분산 transaction으로
묶지 않고 checklist와 retry로 purge한다. `COMPLETED`는 live scope가 실제로 없을 때만 가능하다.

## 10. Internal Compute API와 job protocol

### 10.1 Operations

| Internal operation | Method and path |
|---|---|
| normalization/reconciliation create | `POST /trading-review/normalization-jobs` |
| normalization grant update | `POST /trading-review/normalization-jobs/{jobId}/grant-updates` |
| normalization status | `GET /trading-review/normalization-jobs/{jobId}` |
| normalization terminal result | `GET /trading-review/normalization-jobs/{jobId}/result` |
| normalization cancel | `POST /trading-review/normalization-jobs/{jobId}/cancellation` |
| analytics create | `POST /trading-review/analytics-jobs` |
| analytics status | `GET /trading-review/analytics-jobs/{jobId}` |
| analytics terminal result | `GET /trading-review/analytics-jobs/{jobId}/result` |
| analytics cancel | `POST /trading-review/analytics-jobs/{jobId}/cancellation` |
| terminal payload download | `GET /trading-review/terminal-payloads/{payloadId}` |
| terminal result acknowledgement | `POST /trading-review/jobs/{jobId}/acknowledgement` |

모두 기존 `X-Internal-Api-Key` 하나와 network isolation/TLS를 사용한다. public JWT나 두 번째 key를 만들지
않는다.

### 10.2 Request/result content

Normalization request는 Core logical import/dispatch ID와 `coreNormalizationRunId`,
`expectedInputSetHash`, `NormalizationInputIdentity`(Book ID, source Session set, role별
Artifact ID/SHA-256, source/review timezone, product/mode, hash specification version,
**exact nested** normalizer+reconstruction target VersionSet), 두 job-bound
`artifactAccessGrants`, pinned instrument terms, `retryPolicyVersion`, deletion generation,
attempt token과 Run에 pin한 `resultDeliveryPolicy`를 포함한다.
`resultSchemaVersion`/`resultHashSpecificationVersion`의 정확한 V1/V1 또는 V2/V2 쌍도
Run admission에서 pin해 create의 필수 필드로 보낸다. 새 create에서 누락은
`422 REQUEST_INVALID`, 지원 값의 교차 조합은 `422 RESULT_VERSION_PAIR_INVALID`,
미지원 값/쌍은 `422 RESULT_SCHEMA_UNSUPPORTED`이며 어느 경우에도 Job을 만들지 않는다.
기존 key와 다른 지원 쌍을 제시하면 fingerprint 충돌 `409 IDEMPOTENCY_PAYLOAD_CONFLICT`다.
`inputIdentity`만 RFC 8785 V1
`inputSetHash` 대상이다. V1 policy는 `RESULT_DELIVERY_V1`/`SINGLE_RESOURCE`/advertised
`maxAcceptedPayloadBytes`/`[identity]`이며 semantic resultHash와도 분리한다. Compute는
실제 직렬화 bytes에 Core advertised 한도와 자체 한도의 최소값을 적용한다. Terminal descriptor는
적용한 policy version, delivery mode, media type, encoding, byteSize, payloadSha256와
schema/hash version을 제공한다. Core 수신과 초과 failure는 [delivery 정본](TRADING_NORMALIZATION_RESULT_DELIVERY.md)을 따른다.
Compute accepted와 PENDING/RUNNING/COMPLETED/FAILED/CANCELLED status는 Job에 저장한
결과 쌍을 필수로 반환한다. Compute는 독립 재계산과 grant/identity 일치를 검증한다. grant locator/expiry,
execution/attempt/Run ID, retry policy와 generation은 hash 대상이 아니다. Member profile이나
raw account identifier는 포함하지 않는다. OpenAPI required field를 runtime `extra=forbid`
DTO가 거부하면 계약 위반이다(`TR-ACC-IMPORT-ORCH-017`).

Normalization `COMPLETED` descriptor/payload는 요청의 `coreNormalizationRunId`를 그대로
반환하고 job/logical ID, 최종 attempt token과 deletion generation, status, 독립 재계산한
`inputSetHash`, hash specification version, exact nested target VersionSet,
canonical records, `SourceEvidenceSnapshot`, manifest/manifestHash, capability,
validation/quality report, result schema/hash specification version, 모든 declared count,
section digest, 공개 result digest manifest, 별도 ledger content/result/payload hash와 timestamps를 가진다.
Run ID는 job 저장·worker 재시작·terminal 재조회에서 유지한다. `FAILED`는 별도 payload가
아니라 status에 같은 correlation identity/VersionSet/`expectedInputSetHash`와 structured
safe failure가 있다. 지원 규격에서 재계산한 경우에만 `inputSetHash`를 추가하고,
`COMPLETED`에는 반드시 검증된 `inputSetHash`가 있다.
Compute는 받은 bytes의 content length/SHA-256을 모두 검증한 뒤에만 계산한다. `accessUri`/`expiresAt`은
logical inputSetHash에 포함하지 않고, 다운로드를 완료한 staging도 grant 갱신으로 변경되지 않는다.
Compute staging은 완료/취소 뒤 삭제하고 늦어도 runtime TTL에 purge한다. grant는 단일 Artifact,
read-only, 최대 5분이며 Core Artifact Storage port에서 발급한다. 영구 object key/credential은 전달하지
않는다. 내부 서비스 인증은 하나의 `REFINVEST_INTERNAL_API_KEY`를 `X-Internal-Api-Key`로 보낸다.

Analytics request는 Core AnalysisRun ID, LedgerRevision content handle와 hash, manifest handle/hash,
Analytics VersionSet, AnalysisConfig/hash, capability, deletion generation, attempt token을 포함한다. 결과는
ReviewUnit/Observation/Metric/Finding, full evidence membership, population/exclusion, version/config/result hash,
structured failure와 timestamps를 가진다.
Analytics create 최초 접수는 `202 TradingAnalyticsJobAccepted`를 반환한다. 같은
`Idempotency-Key`와 create fingerprint의 재전송은 `200 TradingAnalyticsJobStatusResponse`로
기존 job의 현재 `PENDING | RUNNING | COMPLETED | FAILED | CANCELLED` 상태, 현재 attempt와
grant revision을 반환한다. 재전송은 grant·attempt·capacity 예약·job 상태를 변경하지 않으며,
같은 key와 다른 fingerprint는 `409`다.

### 10.3 Large payload와 retention

create/status response에는 canonical record/result payload를 inline하지 않는다. terminal `result` endpoint도
descriptor만 반환하고, canonical UTF-8 JSON payload는 opaque `payloadId`, media type, byte size, SHA-256와
expiry로 참조한다. Core는 authenticated payload endpoint에서 stream-download하고 hash/size/schema를 검증해
자기 장기 storage에 저장한다. object URL/path는 어느 API에도 반환하지 않는다.

Core는 장기 저장 transaction이 완료된 뒤 declared payload hash와 Core Aggregate/result ID를 담은 ack를
보낸다. Compute는 ack 전 또는 terminal 후 24시간 중 먼저 도달한 시점까지 retry 가능한 payload를 유지한다.
ack 뒤에는 cleanup 대상이며 24시간을 넘겨 보관하지 않는다. ack 유실은 같은 body로 idempotently 재전송한다.

### 10.4 Job state, duplicate와 stale result

Compute job은 `PENDING → RUNNING → COMPLETED | FAILED | CANCELLED`만 허용한다. Core logical run 하나는 여러
attempt를 가질 수 있지만 동시에 active Compute job은 하나뿐이다.

- create의 `Idempotency-Key`는 Core durable dispatch identity다. canonical fingerprint에는
  모든 immutable 계산 입력·artifact descriptor/hash, Core IDs, 최초 attempt token, 결과
  schema/hash pin과 deletion
  generation을 포함한다. `accessUri`/`expiresAt`, 인증/멱등 header, runtime ID/시각은 제외한다.
  JSON object의 key/order 또는 header 표현 차이는 영향을 주지 않는다. Normalization의
  same key/fingerprint는 최초 `202/jobId`를 재반환한다. Analytics의 신규 접수는 `202`,
  same key/fingerprint 재전송은 현재 상태를 담은 `200`이다. 두 create replay 모두
  **기존 grant/token을 바꾸지 않는다**. 최초 grant가 만료된 뒤의 응답 유실 replay도
  기존 job을 반환한다. 같은 key와 다른 token/계산 입력은 `409`다.
- Compute status는 normalization에 한해 현재 `attemptToken`, `deletionGeneration`,
  `grantRevision`, `expectedInputSetHash`, optional 재계산 `inputSetHash`,
  `hashSpecificationVersion`, exact `targetVersionSet`, 저장된 결과 schema/hash pin,
  `grantAction`과 nullable
  `grantRefreshDeadlineAt`을 반환한다. `grantAction=NONE`이면 갱신이 필요 없다.
  `PENDING/REPLACE_QUEUED_GRANTS`는 claim 전 대기 grant가 만료됐음을,
  `RUNNING/START_RETRY_ATTEMPT`는 이전 worker attempt가 실패·만료되고 lease가 닫혔음을 뜻한다.
  status 조회는 worker가 멈춘 경우에도 DB 시각의 유효 만료/lease 상태를 반영한다.
- Core는 별도 `grant-updates` operation에서 현재 revision/token을 CAS로 지정한다.
  `REPLACE_QUEUED_GRANTS`는 같은 token과 두 fresh grant, `START_RETRY_ATTEMPT`는
  이전에 사용하지 않은 새 token과 두 fresh grant를 요구한다. 독립 `Idempotency-Key`의
  exact replay는 최초 update receipt를 반환하고 다른 body는 `409`다. 늦은 이전 update는
  revision/token mismatch로 `409`이며 새 grant를 되돌리지 않는다. 두 artifact grant는
  role별 정확히 하나이고 pinned ID/hash/size/content type이 변할 수 없다.
- live lease, terminal/cancelled, deletion generation·input/version mismatch는 update
  `409`다. 이전 attempt/ArtifactLease를 닫고 새 revision/grant를 원자적으로 기록한다.
  소비 여부가 불명확한 single-read grant도 재사용하지 않는다. 첫 claim 전 교체는 같은
  attempt token을 유지하지만 claim 뒤 실패/worker crash는 새 token이 필요하다.
  Compute는 요청 generation을 job의 pin과 비교한다. Core tombstone 직후 cancel 전의
  이전 generation 요청이 먼저 commit될 수 있으므로 Core는 grant 발급을 중단하고
  completed publication에서도 현재 generation을 재검증한다.
- worker lease owner/heartbeat는 Compute 내부다. expired lease는 기존 grant를 자동 재claim하지
  않고 attempt를 닫아 `START_RETRY_ATTEMPT`로 관측한다. bounded budget이 남아 있는 동안
  `RUNNING` 상태로 grant를 기다리며 같은 Compute job ID를 유지한다. 현재 대기 기한은
  해당 동작이 처음 필요해진 DB 시각의 bounded window와 admission 시 고정한
  `grantRenewalCutoffAt` 중 이른 시각이다. 같은 대기의 replay/중복 update로 연장하지
  않으며 반복 갱신도 admission cutoff를 넘기지 못한다. `grantRefreshDeadlineAt` 경과나 budget 소진은 safe
  `FAILED/GRANT_REFRESH_DEADLINE_EXCEEDED` 또는 원인별 terminal failure다. 새 grant가
  기한 전에 접수돼도 유효기간·single-read 조건을 다시 검증한다.
- Core는 status를 polling한다. `COMPLETED`인데 terminal descriptor/payload/hash가 없으면 acceptance하지 않는다.
- Core publication은 logical run ID, **completed descriptor와 payload의 최종 attempt token**,
  recomputed input set hash/exact nested VersionSet/config hash, deletion generation과
  별도 result hash를 모두 비교한다. descriptor, payload root 및 result digest manifest의
  결과 쌍이 서로 같고 Run admission pin 및 Job status pin과 정확히 같아야 한다.
  불일치는 `RESULT_VERSION_PIN_MISMATCH`로 publication과 `PERSISTED` ACK를 금지한다.
  `resultHash`는 [결과 무결성 정본](TRADING_NORMALIZATION_RESULT_INTEGRITY.md)의 공개 digest
  manifest hash이고, `payloadSha256`은 identity content-encoding의 응답 entity body exact bytes hash다.
  Core는 count/section/manifest/result/reference를 staging에서 각각 재계산한다. duplicate same payload는
  `NO_OP`; old attempt/generation은 discard하며 conflicting hash는
  `RESULT_ITEM_CONFLICT` 또는 해당 digest failure로 publish하지 않는다. input identity mismatch는
  `INPUT_SET_HASH_MISMATCH`, 미지원 규격은 `INPUT_HASH_SPECIFICATION_UNSUPPORTED`,
  다운로드 source SHA-256 mismatch는 `SOURCE_CONTENT_HASH_MISMATCH`로 구분한다.
- acknowledgement는 `jobId/payloadId`, payload SHA-256, result hash, 최종 token/generation을
  함께 검사한다. 다른 attempt의 ack는 `409`다. 정상 장기 저장은 `PERSISTED`, 삭제
  tombstone으로 폐기한 stale completed payload는 `DISCARDED_STALE` disposition으로
  구분한다. 후자는 Core가 현재 generation이 payload보다 큼을 확인하고 장기 저장 없이
  보낸다. 둘 다 exact duplicate ack는 no-op이며 ack 후 payload는 cleanup 대상이다.
- cancellation은 best effort다. completed payload를 취소로 되돌리지 않으며 Core deletion tombstone이 항상
  publication보다 우선한다.
- terminal `FAILED` 뒤 재시도가 허용되면 새 Compute key/job와 새 Core logical dispatch ID를
  만들고 같은 `coreImportSessionId`의 Core durable dispatch `retryOf`로 연결한다.
  이전 job은 terminal로 남으며 grant update로 되살리지 않는다.

### 10.5 Capacity와 privacy

Normalization 결과 버전 pin 공동 전환 중에는 신규 validation·retry admission과
durable dispatch의 새 Compute create를 함께 중지한다. 이때 새 공개 validation·retry
요청은 대기시키지 않고 기존 `503 TEMPORARY_DEPENDENCY_FAILURE`로 응답한다.
Session 상태와 Run·dispatch·public idempotency record는 바꾸지 않으며, 전환 검증
후 같은 key로 재시도할 수 있다. 이미 접수된 요청의 조회와 status/terminal 복구는
계속한다. 재개 조건과 이전 Core 인스턴스 drain은
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)의 공동 전환 절차를 따른다.

ADR-044의 Backtest 20개 pool, `BACKTEST_CAPACITY_EXCEEDED`, 30초 `Retry-After`는
그대로다. Trading Review normalization/analytics는 Backtest와 분리된 bounded pool을
공유한다. positive 상한과 재시도 초는 배포 설정이며 `PENDING`/lease 없는
`RUNNING` grant 대기까지 active로 센다. 신규 Trading Review job이 자기 pool에서
거절되면 `503 TRADING_REVIEW_CAPACITY_EXCEEDED`와 필수 초 단위
`Retry-After`를 반환하고 job/idempotency row를 만들지 않는다. Core는 같은 생성 key로
재시도한다. 기존 생성 replay와 grant update는 새 capacity를 예약하지 않는다.

인증된 암호화 terminal payload에는 정본 allowlist의 canonical/evidence 거래 필드를
완전하게 보존한다. raw CSV 전체 행, UID, full Trade/Order ID, filename과 signed URI는
operational log·metric·오류 응답에 넣지 않는다. `FAILED`는 status의 safe structured
failure만 제공하며 별도 payload descriptor는 없다.

## 11. Status and error mapping

| Domain event/failure | Public API semantics | Internal API semantics | Retryable |
|---|---|---|---:|
| command accepted | `202`, poll public run/session/request | `202`, poll Compute job | n/a |
| malformed JSON/header | `400 REQUEST_INVALID` | `400 REQUEST_INVALID` | 아니오 |
| unauthenticated | `401 AUTHENTICATION_REQUIRED` | `401 INTERNAL_AUTHENTICATION_FAILED` | credential 수정 |
| CSRF failure | `403 CSRF_VALIDATION_FAILED` | 해당 없음 | token 갱신 |
| hidden/nonexistent resource | `404 RESOURCE_NOT_FOUND` | `404 JOB_NOT_FOUND` | 아니오 |
| deleted owner resource | `410 RESOURCE_DELETED` | stale generation은 terminal failure | 아니오 |
| invalid idempotency key | `400 IDEMPOTENCY_KEY_INVALID` | `400 IDEMPOTENCY_KEY_INVALID` | 수정 후 |
| same key/different payload | `409 IDEMPOTENCY_PAYLOAD_CONFLICT` | `409 IDEMPOTENCY_PAYLOAD_CONFLICT` | 아니오 |
| stale grant revision/token 또는 live lease/terminal update | 해당 없음 | `409 GRANT_UPDATE_CONFLICT` 또는 `JOB_STATE_CONFLICT` | status 조회 후 |
| grant refresh 대기 기한 경과 | 자동 budget 내 `VALIDATING`, 소진 시 `PROCESSING_FAILED` | job `FAILED/GRANT_REFRESH_DEADLINE_EXCEEDED` | 새 attempt 또는 명시적 retry |
| raw TTL 만료 | `PROCESSING_FAILED/SOURCE_ARTIFACT_EXPIRED`, retry command `410` | 신규 job 없음 | 재업로드 |
| grant 만료 | `VALIDATING` 유지 | `ARTIFACT_REFERENCE_EXPIRED` | 자동 재시도 |
| source byte size/hash 불일치 | 검증 완료 전 발행 없음; 원인별 `PROCESSING_FAILED` | `SOURCE_CONTENT_LENGTH_MISMATCH` / `SOURCE_CONTENT_HASH_MISMATCH` | 원인 조사 뒤 |
| 같은 artifact ID와 다른 SHA-256 | `409 ARTIFACT_IDENTITY_CONFLICT` | `409 ARTIFACT_IDENTITY_CONFLICT` | 아니오 |
| Trading Review pool full | public command는 durable 대기 | `503 TRADING_REVIEW_CAPACITY_EXCEEDED` + `Retry-After`, job 미생성 | 예 |
| state/role conflict | `409 RESOURCE_STATE_CONFLICT` | `409 JOB_STATE_CONFLICT` | 상태 조회 후 |
| file too large | `413 FILE_TOO_LARGE` | grant/object input도 같은 상한 검증 | 작은 파일 |
| unsupported content type | `415 UNSUPPORTED_MEDIA_TYPE` | `415 UNSUPPORTED_MEDIA_TYPE` | 형식 수정 |
| missing artifact/config semantic error | `422` safe domain code | create 전 검증이면 `422` | 입력 수정 |
| required column/schema/product/mode/conflict | 최초 `202`, 이후 Import `REJECTED` | job `FAILED` structured code | 새 입력/session |
| missing capability/insufficient sample | completed MetricStatus, HTTP `200` | completed result | 아니오 |
| incomplete terminal result | Run `FAILED/INCOMPLETE_ANALYSIS_RESULT` | `500 TERMINAL_PAYLOAD_INVALID` | corrected attempt |
| version incompatible/unavailable | 동기 `422` 가능, 실행 후면 Run `FAILED` | `422` 또는 job `FAILED` | target 변경 |
| source raw unavailable for reparse | `410 REPROCESSING_SOURCE_UNAVAILABLE` | job 생성 안 함 | reupload 뒤 |
| result not yet available | `409 RESULT_NOT_AVAILABLE` | `409 RESULT_NOT_AVAILABLE` | 예 |
| stale result after deletion | 정상 결과로 저장하지 않음; deletion status만 | result discard, ack | 아니오 |
| deletion pending | resource `410`; deletion query `200` | cancel/purge 진행 | 상태 polling |
| deletion purge failure | deletion query `200 status=FAILED` | cleanup failure | remediation |
| temporary dependency/capacity | `503`, 필요 시 `Retry-After` | `503`, job 미생성 | 예 |
| unexpected server failure | `500 INTERNAL_ERROR` | `500 INTERNAL_ERROR` | 명시값에 따름 |

## 12. Acceptance traceability

아래 표는 stable Scenario ID를 하나 이상의 API operation에 연결한다. reconstruction/analytics
expected calculation은 HTTP가 아니라 정본 문서의 terminal payload assertion으로 검증한다.

| Scenario ID | Public operation | Internal operation | Aggregate | Expected API result |
|---|---|---|---|---|
| `ACC-IMPORT-001`, `ACC-IMPORT-002` | upload, validate, get import/quality/revision | create/poll/get normalization | ImportSession, Revision | accepted; exclusions 명시 |
| `ACC-IMPORT-003` | upload, get import | 없음 | ImportSession | `RECEIVED`, 한 role ready |
| `ACC-IMPORT-004` | validate, get import | normalization create/poll | ImportSession | `202`, 이후 terminal |
| `ACC-IMPORT-005`, `ACC-IMPORT-006`, `ACC-IMPORT-007` | validate | 없음(Core precondition) | ImportSession | `422`, 상태 불변 |
| `ACC-IMPORT-008`, `ACC-IMPORT-009`, `ACC-IMPORT-010`, `ACC-IMPORT-011`, `ACC-IMPORT-012`, `ACC-IMPORT-013`, `ACC-IMPORT-014`, `ACC-IMPORT-015` | validate, get import/quality | normalization create/poll/result | ImportSession | rejected 또는 exclusions |
| `ACC-IMPORT-016`, `ACC-IMPORT-017` | upload | 없음 | Artifact | 기존 artifact 반환 |
| `ACC-IMPORT-018`, `ACC-IMPORT-019`, `ACC-IMPORT-020` | validate, get revision/quality | normalization | Record, Revision | dedup/provenance 또는 conflict |
| `ACC-IMPORT-021` | validate, get import | duplicate normalization create | ImportSession, Dispatch | 기존 상태/resource |
| `ACC-IMPORT-022` | get import/revision | duplicate terminal result/ack | ImportSession, Revision | exact duplicate no-op |
| `ACC-NORMJOB-001`, `ACC-NORMJOB-002` | validate/poll import | normalization create/status/grant update | ImportSession, Dispatch, ComputeJob | replay는 최초 job; 대기 만료는 CAS 갱신 |
| `ACC-NORMJOB-003`, `ACC-NORMJOB-004` | poll import | normalization status/grant update | Dispatch, ComputeJobAttempt, ArtifactLease | 부분 소비/crash 뒤 새 token·두 grant |
| `ACC-NORMJOB-005`, `ACC-NORMJOB-006`, `ACC-NORMJOB-007` | poll import | normalization grant update/status | ComputeJob | 중복 receipt 또는 stale/live lease `409` |
| `ACC-NORMJOB-008`, `ACC-NORMJOB-009` | delete scope/poll import | normalization cancellation/grant update/status | DeletionRequest, ComputeJob | deletion 우선, terminal 갱신 거절 |
| `ACC-NORMJOB-010` | get import/revision | normalization result/payload/ack | ImportSession, Revision | exact duplicate no-op; stale ack `409` |
| `ACC-NORMJOB-011`, `ACC-NORMJOB-012` | poll import | normalization status/create | ComputeJob | deadline safe failure, 별도 pool `503` |
| `ACC-NORMJOB-013`, `ACC-NORMJOB-014` | validate/poll import | normalization create/status; Core grant redemption | Dispatch, Artifact | terminal 새 job/lineage; pre-create binding 검증 |
| `ACC-IMPORT-ORCH-017`~`ACC-IMPORT-ORCH-022` | validate/poll/get import/revision | normalization create/status/result/payload/ack | NormalizationRun, ComputeJob, Revision | runtime DTO, V1 hash, grant refresh, failure 분리, Core Run ID 왕복 |
| `TR-ACC-IMPORT-QUALITY-001`~`TR-ACC-IMPORT-QUALITY-005` | validate/poll/get import/revision | normalization status/result | ImportSession, NormalizationRun, Revision, Book | REJECTED nullable quality, ACCEPTED 검증 report, PROCESSING_FAILED 분리, latest 불변 |
| `ACC-RECON-001`, `ACC-RECON-002`, `ACC-RECON-003`, `ACC-RECON-004`, `ACC-RECON-005` | get quality/revision; 이후 review unit | normalization result | Manifest, Revision | expected status/allocation |
| `ACC-RECON-006`, `ACC-RECON-007`, `ACC-RECON-008`, `ACC-RECON-009`, `ACC-RECON-010`, `ACC-RECON-011`, `ACC-RECON-012` | get quality/revision; metric query | normalization result | Manifest, Revision | exclusion/tolerance/capability/coverage |
| `ACC-ANALYSIS-001`, `ACC-ANALYSIS-002`, `ACC-ANALYSIS-003`, `ACC-ANALYSIS-004`, `ACC-ANALYSIS-005`, `ACC-ANALYSIS-006`, `ACC-ANALYSIS-007`, `ACC-ANALYSIS-008`, `ACC-ANALYSIS-009` | run/poll/get review/metric | create/poll/get analytics | AnalysisRun, Result | completed status/value-or-null |
| `ACC-ANALYSIS-010` | poll/get review/evidence | analytics result/ack | AnalysisResult | complete persisted chain |
| `ACC-ANALYSIS-011` | poll analysis | invalid analytics terminal result | AnalysisRun | failed, no result |
| `ACC-ANALYSIS-012` | run/poll | duplicate analytics create | AnalysisRun | existing run/result |
| `ACC-ANALYSIS-013` | poll/get review | duplicate terminal result/ack | AnalysisRun, Result | no-op 또는 hash conflict |
| `ACC-ANALYSIS-014` | poll | analytics retry attempts | AnalysisRun | running then completed |
| `ACC-ANALYSIS-015` | poll | analytics terminal failure | AnalysisRun | failed + safe failure |
| `ACC-EVIDENCE-001`, `ACC-EVIDENCE-002` | get metric, get review unit | 없음 | AnalysisResult read model | subject/comparison chain |
| `ACC-EVIDENCE-003`, `ACC-EVIDENCE-004`, `ACC-EVIDENCE-005` | get review unit/evidence | 없음 | Manifest, Record, Snapshot | paged full chain |
| `ACC-EVIDENCE-006`, `ACC-EVIDENCE-007` | get evidence | 없음 | ArtifactRetention, Snapshot | raw availability truthful |
| `ACC-EVIDENCE-008` | get evidence | 없음 | ownership boundary | hidden `404` |
| `ACC-EVIDENCE-009` | get metric/review unit/evidence | 없음 | AnalysisResult | excluded membership |
| `ACC-EVIDENCE-010` | get revision/run/review | 없음 | lineage/version metadata | readable vs replayable |
| `ACC-DELETE-001`, `ACC-DELETE-002` | get import/artifact retention | 없음(retention worker event) | ArtifactRetention | deleted raw state |
| `ACC-DELETE-003` | delete artifact, get deletion/evidence | cancel job if active | DeletionRequest | `202`, snapshot retained |
| `ACC-DELETE-004` | get review/evidence | 없음 | Snapshot, Result | retained snapshot |
| `ACC-DELETE-005` | request reprocessing | 없음(Core admission) | ReprocessingRun | `410` source unavailable |
| `ACC-DELETE-006`, `ACC-DELETE-007`, `ACC-DELETE-008` | delete Book/Account/member orchestration, get deletion | cancel active jobs | DeletionRequest | async cascade status |
| `ACC-DELETE-009`, `ACC-DELETE-010` | delete scope, get deletion | cancellation/status | DeletionRequest, Run | output discard |
| `ACC-DELETE-011` | delete scope, get deletion | duplicate cancellation | DeletionRequest | existing request |
| `ACC-DELETE-012` | get deletion | cleanup/cancel | DeletionRequest | failed/partial, retryable |
| `ACC-DELETE-013` | get deletion | stale terminal result/ack | tombstone | discard, no publication |
| `ACC-DELETE-014` | get deletion | 없음(restore control) | deletion ledger | normal API blocked until verified |
| `ACC-REPROCESS-001`, `ACC-REPROCESS-002`, `ACC-REPROCESS-003`, `ACC-REPROCESS-004`, `ACC-REPROCESS-005`, `ACC-REPROCESS-006` | request/poll reprocessing; get revision/run | normalization 또는 analytics jobs | ReprocessingRun | new immutable child or failure |
| `ACC-REPROCESS-007` | request/poll | duplicate job create | ReprocessingRun | existing run/result |
| `ACC-REPROCESS-008`, `ACC-REPROCESS-009` | request/poll | create validation/terminal failure | ReprocessingRun | failed, old result preserved |
| `ACC-REPROCESS-010` | poll/get Book/review | terminal result/ack | ReprocessingRun, pointers | atomic latest publication |
| `ACC-REPROCESS-011` | delete scope/get deletion | cancel jobs/stale result | ReprocessingRun, DeletionRequest | cancelled, deletion wins |
| `ACC-REPROCESS-012` | get metric/review metadata | 없음 | AnalysisResult | not comparable metadata |
| `ACC-REPROCESS-013` | repeated request/poll/get reviews | analytics jobs | multiple Runs | all-completed comparable inputs |
| `ACC-AUTH-001` | any owner query, especially get review | 없음 | ownership | `200` |
| `ACC-AUTH-002`, `ACC-AUTH-003`, `ACC-AUTH-004` | get Book/artifact/evidence | 없음 | ownership | hidden `404` |
| `ACC-AUTH-005` | upload | 없음 | security boundary | `401`, no resource |
| `ACC-AUTH-006` | get review | 없음 | security boundary | `401` |
| `ACC-AUTH-007` | any public command | 없음 | security boundary | `403`, key not consumed |
| `ACC-AUTH-008` | public storage URL attempt | 없음 | artifact access | no public operation/deny |
| `ACC-AUTH-009`, `ACC-AUTH-010` | owner API는 operator access를 부여하지 않음 | 없음 | operator grant/audit | deny or separately audited break-glass |

연결되지 않은 Scenario는 없다. `ACC-AUTH-010`의 operator break-glass endpoint와 `ACC-DELETE-014`의 restore
endpoint는 public product API가 아니며 운영 control-plane assertion으로 유지한다.

## 13. Contract/configuration 분류

### 확정된 계약

public/internal 분리, Option A upload, 50 MiB와 media type, explicit validation, polling, pre-create
logical job binding의 5분 single-read grant, 독립 grant update/CAS와 final attempt metadata,
opaque terminal payload, Core 장기 ownership, Compute 최대 24시간 runtime, immutable result/latest pointer 분리,
idempotency semantics, evidence/deletion/reprocessing endpoint, Decimal/time/error 표현은 계약이다.

### Server configuration

poll interval, bounded retry 횟수/backoff, worker lease/heartbeat, bounded grant 대기 기간과
Trading Review pool 상한/Retry-After 초, page default 이하의 client 선택, alert threshold와
cleanup 실행 주기는 configuration이다. 상태/retention/hash 의미를 바꿀 수 없다.

### Infrastructure limit

reverse proxy request buffering은 비활성 또는 streaming이어야 하고 body limit은 50 MiB + multipart overhead
이상이어야 한다. storage timeout, KMS와 queue capacity는 배포 선택이며 API success 의미를 바꾸지 않는다.

### 후속 성능 검증

50 MiB upload의 Core memory 사용, normalization terminal payload 크기/streaming 처리량, evidence page query와
object cleanup latency를 Integration에서 측정한다. 측정 결과만으로 계약을 조용히 변경하지 않는다.

## 14. Review answers

1. Web은 multipart로 Core에 두 CSV를 업로드한다.
2. ImportSession의 artifact 목록에서 두 role이 모두 `UPLOADED`인지 확인한다.
3. 별도 validation operation을 호출하며 자동 시작하지 않는다.
4. Import status와 quality report에서 `ACCEPTED`/`REJECTED`, exclusion을 구분한다.
5. Revision은 content/manifest hash와 전체 Normalizer/Reconstruction/Data Policy pin을 노출한다.
6. AnalysisRun은 하나의 Revision, manifest, Analytics VersionSet과 canonical config/hash를 고정한다.
7. `COMPLETED`에는 result ID/hash가 필수이므로 result 없는 완료는 불가능하다.
8. Metric→ReviewUnit→allocation→record→snapshot을 paged endpoint로 조회한다.
9. raw 삭제 뒤에는 versioned allowlist snapshot과 `rawAvailability=DELETED`를 반환한다.
10. 같은 key/payload는 진행/완료된 최초 resource를 반환한다.
11. Core durable dispatch가 logical run, Compute job, attempt와 idempotency key를 추적하고 polling한다.
12. attempt/version/input/generation/hash를 검증해 duplicate는 no-op, stale/conflict는 discard한다.
13. 다른 사용자의 resource는 존재하지 않는 것과 같은 `404`다.
14. 금융값은 Decimal string/exact rational, Trade/Position source time은 second-precision exact instant다.
15. 삭제와 재처리는 각각 별도 async Run/Request 상태를 반환한다.
16. §12가 모든 110개 Acceptance Scenario를 operation에 연결한다.
17. Core는 장기 product/ledger/result를, Compute는 최대 24시간 runtime/terminal payload만 저장한다.
