# TRADING_REVIEW_API.md — Trading Review API Contract

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
6. grant가 소비 전 만료되면 Compute attempt는 retryable `ARTIFACT_REFERENCE_EXPIRED`로 끝난다. Core는 같은
   logical dispatch와 Compute idempotency key를 유지하면서 새 attempt token/grant로 재시도한다. raw TTL이나
   deletion generation이 이미 막으면 재발급하지 않는다.

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

- `RECEIVED | VALIDATING | ACCEPTED | REJECTED`
- 두 artifact의 role, safe filename metadata, content type, byte size, checksum, dialect candidate/detected,
  upload/retention 상태와 `rawExpiresAt`
- role별 earliest/latest observed instant, row count, source timezone, `SECOND` precision,
  `coverageCompleteness=NOT_PROVEN`; min/max를 complete export 보장으로 표현하지 않음
- ignored non-trading row count, validation issues(row number + field + safe code, raw value 없음)
- reconciliation/quality summary와 capability
- `createdLedgerRevisionId`(ACCEPTED에서 필수)
- pinned Normalizer/Reconstruction/Data Policy version sets와 implementation digest
- structured failure, created/validation-started/completed timestamps

`RECEIVED`에서 두 role이 모두 `UPLOADED`인지 artifact 목록으로 확인한다. 누락 상태에서 validation command는
동기 `422 MISSING_REQUIRED_ARTIFACT`; session은 `RECEIVED`에 남는다. Compute가 schema/product/conflict를
발견한 경우 command 자체는 `202`, 이후 session은 `REJECTED`다. 일부 episode만 부적격이면 session과
Revision은 accepted되고 quality/exclusion을 명시한다.

### 5.2 LedgerRevision response

Revision은 ID, Book ID, Book별 revision number, content hash, source ImportSession IDs, coverage, canonical
record count, pinned `InstrumentDescriptor`/terms version, Normalizer/Reconstruction version sets,
reconciliation manifest hash, capability/quality, parent Revision IDs, reprocessing lineage와 `createdAt`을
반환한다. full canonical record나 full manifest를 기본 response에 inline하지 않는다.

Import/quality response는 사용자 선언과 same-session upload, reconciliation evidence를 actual account identity
proof와 구분한다. Trade UID는 public response/evidence/log에 반환하지 않으며 Position artifact에 UID가 없으므로
CSV pair만으로 동일 account/sub-account를 검증했다고 표시하지 않는다.

하나의 accepted Revision과 record membership은 불변이다. canonical record 자체의 content identity가 같은
경우 한 owner/Book 안에서 여러 Revision이 같은 immutable record를 참조할 수 있지만 membership은 별도
`LedgerRevisionRecord` 관계로 고정한다. cross-owner deduplication은 금지한다.

이번 source correction은 OpenAPI `0.3.0`에서 Position summary의 `openedInterval`/`closedInterval`을 exact
`openedAt`/`closedAt`으로 교체하고 role별 `ArtifactCoverage`를 추가한 의도적 계약 변경이다. 기존 interval
payload를 새 exact timestamp로 암묵 변환하지 않는다. 구현 시 `canonicalSchemaVersion`, adapter와
reconstruction/reconciliation compatibility를 새 exact version으로 등록하고 기존 accepted Revision이 있다면
ADR-062의 immutable reprocessing 경계를 적용한다. Backtest schema에는 영향이 없다.

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

Normalization request는 Core logical import/dispatch ID, Book product/mode, 두 artifact의 Core opaque ID,
role/checksum와 job-bound signed grant, source/review timezone, dialect/Normalizer/Reconstruction target,
pinned instrument terms, expected input hash, deletion generation, attempt token을 포함한다. Member profile이나
raw account identifier는 포함하지 않는다.

Normalization terminal result는 job/logical ID, status/version, canonical records,
`SourceEvidenceSnapshot`, manifest, capability, validation/quality report, input/content/manifest/result hash,
structured failure와 timestamps를 가진다.

Analytics request는 Core AnalysisRun ID, LedgerRevision content handle와 hash, manifest handle/hash,
Analytics VersionSet, AnalysisConfig/hash, capability, deletion generation, attempt token을 포함한다. 결과는
ReviewUnit/Observation/Metric/Finding, full evidence membership, population/exclusion, version/config/result hash,
structured failure와 timestamps를 가진다.

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

- create의 `Idempotency-Key`는 Core durable dispatch identity다. same key/payload는 같은 Compute job ID를,
  different payload는 `409`를 반환한다.
- worker attempt, lease owner와 heartbeat는 Compute 내부다. expired lease는 bounded retry attempt를 만들되 job
  logical identity를 바꾸지 않는다.
- Core는 status를 polling한다. `COMPLETED`인데 terminal descriptor/payload/hash가 없으면 acceptance하지 않는다.
- Core publication은 logical run ID, attempt token, expected input/version/config hash, deletion generation과
  result hash를 모두 비교한다. duplicate same payload는 `NO_OP`; old attempt/generation은 discard/ack하며,
  conflicting hash는 `RESULT_HASH_MISMATCH`로 publish하지 않는다.
- cancellation은 best effort다. completed payload를 취소로 되돌리지 않으며 Core deletion tombstone이 항상
  publication보다 우선한다.

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

아래 표는 96개 stable Scenario ID를 모두 하나 이상의 API operation에 연결한다. reconstruction/analytics
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

public/internal 분리, Option A upload, 50 MiB와 media type, explicit validation, polling, job-bound 5분 grant,
opaque terminal payload, Core 장기 ownership, Compute 최대 24시간 runtime, immutable result/latest pointer 분리,
idempotency semantics, evidence/deletion/reprocessing endpoint, Decimal/time/error 표현은 계약이다.

### Server configuration

poll interval, bounded retry 횟수/backoff, worker lease/heartbeat, page default 이하의 client 선택, alert threshold와
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
16. §12가 모든 96개 Acceptance Scenario를 operation에 연결한다.
17. Core는 장기 product/ledger/result를, Compute는 최대 24시간 runtime/terminal payload만 저장한다.
