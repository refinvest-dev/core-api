# TRADING_REVIEW_ACCEPTANCE.md — Trading Review Acceptance Contract

Analytics runtime Scenario `TR-ACC-ANALYSIS-016`~`039`는 ADR-069와 [Job Protocol](TRADING_ANALYTICS_JOB_PROTOCOL.md), [Input Identity](TRADING_ANALYTICS_INPUT_IDENTITY.md), [Result Integrity](TRADING_ANALYTICS_RESULT_INTEGRITY.md), [Result Delivery](TRADING_ANALYTICS_RESULT_DELIVERY.md)를 따른다. 기존 Scenario ID는 유지한다.

이 문서는 Trading Review TR-0G의 사용자 관점 완료 조건과 Aggregate 상태 전이를 정의한다. Integration
harness는 이 문서의 Scenario ID, fixture 상태와 `Then`을 assertion 이름으로 재사용한다. 계산식은
[`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md)와
[`TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md), lifecycle은
[`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md), version/reprocessing은
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)가 정본이며 여기서 다시 정의하지 않는다.

이 문서는 Use Case와 도메인 상태·비동기 handoff의 acceptance 계약이다. HTTP method/path/status와
JSON field의 정본은 `openapi/`, 논리 저장 경계는 `TRADING_REVIEW_PERSISTENCE.md`다.
Scenario는 그 계약을 검증하되 queue/worker 구현 방식을 정하지 않는다.

---

## 1. 공통 판정 규칙

### 1.1 Acceptance Outcome

`AcceptanceOutcome`은 한 사용자 요청 또는 비동기 작업의 관찰 결과다. Aggregate status, `MetricStatus`,
reconstruction/reconciliation status, HTTP status와 별도다.

| Outcome | 의미 | 빈 성공 허용 여부 |
|---|---|---|
| `SUCCEEDED` | 요청한 작업이 모든 필수 검증을 통과해 완전한 산출물을 만들거나 조회했다. | 결과가 정의상 0인 것은 허용하지만 필수 artifact/result 누락은 불가 |
| `ACCEPTED_WITH_EXCLUSIONS` | Import/Analysis 자체는 완료됐고 부적격 episode/measure가 명시적 이유와 evidence로 보존됐다. | 제외를 버리거나 0으로 바꾸는 것은 불가 |
| `REJECTED` | 입력·precondition·conflict 때문에 작업을 시작하지 않거나 Import 전체가 `REJECTED`됐다. | 불가 |
| `FAILED_RETRYABLE` | 일시적 실행/저장 실패다. 동일 logical command의 새 attempt가 가능하고 성공으로 표시하지 않는다. | 불가 |
| `FAILED_TERMINAL` | 현재 입력/version으로 완료할 수 없는 terminal failure다. 수정 입력·target 또는 새 logical request가 필요하다. | 불가 |
| `CANCELLED` | publication 전 작업이 취소됐고 새 산출물/latest 변경이 없다. | 불가 |
| `NOT_AUTHORIZED` | 인증, CSRF 또는 승인된 operator grant가 없어 resource 해석 전에 거절됐다. | 불가 |
| `NOT_FOUND_OR_HIDDEN` | resource가 없거나 caller가 owner가 아니며 두 경우를 외부에서 구분할 수 없다. | 불가 |

### 1.2 공통 V1 pin과 fixture 표기

`BASE-V1`은 `BINANCE_USDS_TRADE_HISTORY_V1`, `BINANCE_USDS_POSITION_HISTORY_V1`,
`BINANCE_USDS_LINEAR_PERPETUAL_ONE_WAY_V1`, `TRADING_BEHAVIOR_METRICS_V1`, 각 Metric definition `1`,
`AnalysisConfig V1`, `SOURCE_EVIDENCE_MASK_V1`, `TRADING_DATA_POLICY_V1`과 immutable implementation digest를
뜻한다. Scenario의 `Pinned Versions`가 차이를 명시하지 않으면 `BASE-V1` 전부를 고정한다. `AnalysisConfig
V1-default`는 period를 제외한 [`TRADING_ANALYTICS.md` §3](TRADING_ANALYTICS.md#3-analysisconfig-v1)의
기본값이다.

- `FIXTURE_AVAILABLE`: 현재 저장소에 실제 fixture가 있다.
- `FIXTURE_PLANNED`: Integration 저장소에 만들 fixture ID이며 아직 검증 완료로 간주하지 않는다.
- `FIXTURE_NOT_REQUIRED`: 상태/authorization/callback 계약으로 CSV fixture가 필요 없다.

현재 이 저장소에는 Trading Review fixture가 없으므로 이 문서의 모든 파일 fixture는
`FIXTURE_PLANNED`다. 숫자·ID·상품명은 fixture ID 또는 정본의 synthetic scenario만 가리키며 실제 사용자
값을 포함하지 않는다.

### 1.3 공통 actor, ownership과 observability

- Web은 owner command/query와 polling만 수행하고 Compute를 직접 호출하지 않는다.
- Core는 ownership, Aggregate 상태, artifact metadata, canonical ledger, deletion generation,
  idempotency, lineage, latest pointer와 장기 result를 소유한다.
- Compute는 pinned request의 normalization/reconstruction/reconciliation/analytics와 최대 24시간 runtime만
  소유하며 Core Aggregate/table을 직접 수정하지 않는다.
- Object Storage는 Core 소유 encrypted raw/manifest/result object를 보관한다. PostgreSQL table은
  [`ARCHITECTURE.md` §4](ARCHITECTURE.md#4-데이터베이스--단일-postgres-테이블-소유권-분리)를 따른다.
- Scenario의 `Observability: SAFE`는 opaque internal ID, status/code/version, count, duration만 허용하고 raw
  UID/row/value, fingerprint, Symbol, 거래 시각·수량·가격·fee·PnL, filename/path/URL은 log/metric에서
  금지한다. `ALERT`가 추가되면 정본의 alert 조건도 검증한다.

### 1.4 공통 terminal handoff와 failure assertion

모든 Compute dispatch/polling/terminal publication은 Core가 고정한 run/import ID, 최종 attempt
token, target version hash와
`deletionGeneration`을 검증한다. 같은 terminal payload/hash의 중복 callback은 `NO_OP`; 다른 payload,
hash, attempt 또는 generation은 conflict/stale failure로 discard한다. Failure scenario는 다음을 반드시
assert한다.

```text
failureCode
retryable
aggregateTerminal
existingResultEffect
userAction
safeLogFields
alertRequired
```

`Expected Failure or Exclusion`에 이 순서로 축약해 기록한다.

Acceptance orchestration이 정본 failure taxonomy에 추가로 사용하는 safe code는 두 개뿐이다.

| Code | 의미 | Aggregate 영향 |
|---|---|---|
| `MISSING_REQUIRED_ARTIFACT` | 두 artifact가 준비되기 전에 validation command가 호출됨 | session은 `RECEIVED`; terminal 아님 |
| `INCOMPLETE_ANALYSIS_RESULT` | completion payload에 quality/result/Metric/evidence/hash 중 필수 요소가 없음 | terminal attempt는 `FAILED`; `COMPLETED` 금지 |

---

## 2. Idempotency Contract

| Command | Logical key input | 진행 중 중복 | 성공 후 중복 | 실패 후 retry | 불일치 payload |
|---|---|---|---|---|---|
| `UploadTradingImportArtifact` | owner, session, role, raw SHA-256 | `RETURN_EXISTING` | `RETURN_EXISTING` | 저장 실패는 `CREATE_NEW_ATTEMPT` | 같은 key/role에 다른 hash는 `REJECT_CONFLICT` |
| `ValidateTradingImportSession` | owner, session, 두 role/hash, normalization/reconstruction VersionSet, data policy, generation | `RETURN_EXISTING` | terminal이면 `NO_OP` | `VALIDATING` 내부 compute attempt만 `CREATE_NEW_ATTEMPT`; `PROCESSING_FAILED`는 별도 retry command | `REJECT_CONFLICT` |
| `RetryTradingImportSession` | owner, session, original inputSetHash/VersionSet, generation | 기존 active Run 반환 | 성공 Revision 반환 | `PROCESSING_FAILED`와 active raw면 새 Run/attempt, 같은 logical fingerprint | `REJECT_CONFLICT` |
| `RunTradingAnalysis` | owner, Book, Ledger content/manifest hash, Analytics VersionSet, config hash, generation | `RETURN_EXISTING` | completed logical result `RETURN_EXISTING` | failed Run은 immutable; 같은 key의 새 Run/attempt `CREATE_NEW_ATTEMPT` | `REJECT_CONFLICT` |
| `RequestTradingReprocessing` | ADR-062 canonical key | `RETURN_EXISTING` | completed run/result `RETURN_EXISTING` | 같은 key, 새 run/attempt와 `retryOf`로 `CREATE_NEW_ATTEMPT` | `REJECT_CONFLICT` |
| delete commands | owner, scope type, opaque scope ID, deletion generation | 같은 request `RETURN_EXISTING` | 같은 terminal request `RETURN_EXISTING` | non-terminal 미완료 checklist만 `CREATE_NEW_ATTEMPT`; failed remediation은 새 request | 다른 scope/generation은 `REJECT_CONFLICT` |
| terminal callback | aggregate/run, attempt token, input/result hash, generation | 동일 payload `NO_OP` | 동일 payload `NO_OP` | producer retry도 `NO_OP` | conflict/stale callback discard |

같은 raw bytes와 다른 filename은 filename이 logical identity가 아니므로 같은 session/role에서
`RETURN_EXISTING`이다. 새 artifact/object/LedgerRevision을 만들지 않으며 upload-attempt audit에 filename을
남기지 않는다. 다른 ImportSession에서의 재업로드는 새 session이지만 canonical execution identity와 content가
같으면 Book-scoped record를 재사용할 수 있다. 새 Revision membership은 새 Session 입력만 포함하고 이전
Revision 전체와 병합하지 않는다. content hash가 같아도 inputSetHash/provenance는 다르다.

---

## 3. State Transition Contract

### 3.1 `TradingImportSession`

`RECEIVED`는 artifact 0~2개를 받을 수 있는 준비 상태다. 별도 `AWAITING_ARTIFACTS` 상태를 추가하지 않는다.

| Current State | Command/Event | Preconditions | Next State | Side Effects | Invalid Result |
|---|---|---|---|---|---|
| `RECEIVED` | first/second artifact stored | owner, expected role, encrypted object verified | `RECEIVED` | role slot과 retention metadata 고정 | storage failure면 slot 미등록 |
| `RECEIVED` | duplicate same role/content upload | same session/role/hash | `RECEIVED` | 기존 artifact 반환, 새 object 없음 | — |
| `RECEIVED` | different content for occupied role | role already occupied | `RECEIVED` | 없음 | `REJECT_CONFLICT`; 새 session 사용 |
| `RECEIVED` | `ValidateTradingImportSession` | 정확히 두 role, timezone/mode/policy/version/generation 고정 | `VALIDATING` | exactly one logical dispatch | 준비 전 command `REJECTED`, 상태 불변 |
| `VALIDATING` | artifact upload/replace | any | `VALIDATING` | 없음 | `REJECT_CONFLICT`; validation input 불변 |
| `VALIDATING` | retryable Compute failure | retry budget/generation valid | `VALIDATING` | 새 attempt, 같은 logical input | budget 소진 시 `PROCESSING_FAILED` |
| `VALIDATING` | retry budget exhausted | versioned policy 소진 | `PROCESSING_FAILED` | Run `TERMINAL_FAILURE`, Revision 없음 | raw TTL 별도 검증 |
| `VALIDATING` | accepted callback | input/hash/generation 일치, complete records/manifest/quality/policy | `ACCEPTED` | 정확히 한 새 Revision publication | 불완전 callback discard/fail |
| `VALIDATING` | input rejection callback | source rejection code와 safe structured failure | `REJECTED` | LedgerRevision 없음, latestRevisionId 불변, 미계산 qualitySummary=null | — |
| `PROCESSING_FAILED` | `RetryTradingImportSession` | active raw, same input/VersionSet/generation | `VALIDATING` | 새 Run/attempt와 audit, 기존 failure 보존 | raw expired면 `SOURCE_ARTIFACT_EXPIRED`; 삭제면 410 |
| `ACCEPTED`/`REJECTED` | validate/retry | terminal | 동일 | 동일 callback은 `NO_OP` | 다른 payload `REJECT_CONFLICT`; 재검증은 새 session |
| any non-deleted | raw-only delete | owner, scope/generation | session 상태는 유지; validating이면 `PROCESSING_FAILED` | artifact retention→pending, output discard | `SOURCE_ARTIFACT_DELETED` |
| any | session/상위 delete | owner, scope commit | 정상 상태 조회 차단 | deletion request와 tombstone; cascade purge | callback discard |

Trade History만 또는 Position History만 있는 동안 validation은 시작하지 않는다. 두 artifact가 준비돼도
명시적 validation command 전에는 자동 시작하지 않는다.

### 3.1a `NormalizationRun` (Core 내부)

| Current | Event / gate | Next | Publication / audit |
|---|---|---|---|
| — | validation admission 또는 명시적 retry, pinned input/VersionSet/policy/generation | `DISPATCH_PENDING` | Session `VALIDATING`, durable dispatch와 Run을 같은 transaction에 생성 |
| `DISPATCH_PENDING` | Compute create accepted 또는 replay | `SUBMITTED` | Compute job ID/dispatch 기록 |
| `SUBMITTED` | worker claim | `RUNNING` | current attempt와 token 기록 |
| `DISPATCH_PENDING`/`SUBMITTED`/`RUNNING` | retryable timeout, 5xx, grant expiry, storage failure; policy 잔여 | `RETRY_WAIT` | Session `VALIDATING`, 실패 attempt/audit 기록 |
| `RETRY_WAIT` | 재제출 또는 fresh grant/attempt CAS | `SUBMITTED` 또는 `RUNNING` | logical input fingerprint 유지, attempt 증가 |
| non-terminal | policy 소진 또는 결정적 source rejection | `TERMINAL_FAILURE` | 운영 실패면 `PROCESSING_FAILED`, 입력 거부면 `REJECTED`; Revision 없음 |
| `RUNNING` | Compute completed descriptor와 payload size/hash 확인 | `RESULT_READY` | staging만 저장, 공개 조회 금지 |
| `RESULT_READY` | current Run/attempt/input/VersionSet/generation 및 manifest/quality/capability 검증 | `PUBLISHING` | publication transaction 시작 |
| `PUBLISHING` | Revision/membership/Session/latest atomic commit | `PUBLISHED` | Session `ACCEPTED`; commit 뒤 `PERSISTED` ACK |
| non-published | 더 최신 attempt, input/version 불일치 또는 deletion generation 변경 | `OBSOLETE` | staging 폐기; 삭제면 `DISCARDED_STALE` ACK, 그 외 ACK 없음 |
| `PUBLISHED` | ACK 전송 실패 | `PUBLISHED` | ACK만 멱등 재시도, Revision insert 금지 |

terminal Run은 다시 열지 않는다. `PROCESSING_FAILED`의 명시적 retry는 새 Run을 `retryOf`로 연결한다.
RetryPolicy는 versioned configuration이며 숫자·delay는 배포 설정이다. 운영 자동 재개도 동일한 전이와
audit event를 기록한다. 삭제 tombstone 뒤에는 새 Run/attempt를 admit하지 않는다.

### 3.2 `TradingAnalysisRun`

| Current State | Command/Event | Preconditions | Next State | Side Effects | Invalid Result |
|---|---|---|---|---|---|
| — | `RunTradingAnalysis` | owner, accepted retained Revision, exact compatible versions/config/generation | `PENDING` | logical run과 durable dispatch 생성 | validation rejection, Run 없음 |
| `PENDING` | Core dispatch accepted | exact pinned request | `RUNNING` | Compute runtime job | duplicate dispatch는 기존 job |
| `PENDING`/`RUNNING` | retryable Compute attempt failure | budget/generation valid | 동일 | new attempt token; Run identity 유지 가능 | stale attempt discard |
| `RUNNING` | success callback | result + quality report + complete Metric/evidence + hashes | `COMPLETED` | immutable result 저장/publication | result 없는 completion 금지 |
| `PENDING`/`RUNNING` | terminal failure | structured failure | `FAILED` | result 없음; prior runs unchanged | 빈 completed result 금지 |
| `COMPLETED`/`FAILED` | callback/retry | terminal | 동일 | exact duplicate `NO_OP` | state reversal/conflicting callback 금지 |
| `PENDING`/`RUNNING` | scope deletion commit | generation increments | purge 대상; 별도 `CANCELLED` 상태 추가 안 함 | cancel/discard, deletion workflow가 상태/row 제거 | result 저장 금지 |

`COMPLETED`에는 quality report, 전체 population/eligible/excluded와 reason, 세 Metric의 status/value-or-null,
전체 evidence membership/artifact hash, canonical result hash가 모두 필요하다. Finding 0개는 허용한다.

### 3.3 `TradingReprocessingRun`

| Current State | Command/Event | Preconditions | Next State | Side Effects | Invalid Result |
|---|---|---|---|---|---|
| — | request admitted | owner/source/target/type/key/generation valid | `PENDING` | immutable request pin | incompatibility/deletion이면 Run 없음 또는 failed admission record |
| `PENDING` | validation starts | registry snapshot pinned | `VALIDATING_INPUT` | source availability/compatibility 검사 | — |
| `VALIDATING_INPUT` | valid | required raw/canonical/result present | `RUNNING` | 필요한 child Revision/AnalysisRun을 staging으로 생성 | unavailable이면 `FAILED` |
| `RUNNING` | complete callback | all requested stages, hash/lineage/generation pass | `COMPLETED` | 새 immutable artifact publish, optional latest atomic update | partial publication 금지 |
| non-terminal | terminal execution failure | structured failure | `FAILED` | staging cleanup, old result/latest 유지 | — |
| non-terminal | cancel or deletion | publication 전 | `CANCELLED` | output discard; deletion이면 purge 우선 | completed result rollback 금지 |
| terminal | callback/retry/cancel | terminal | 동일 | exact duplicate `NO_OP` | 다시 열기/상태 변경 금지 |

새 Revision/child AnalysisRun은 input validation 성공 뒤 `RUNNING`에서 staging으로 생성한다. 장기 보존과
latest pointer 변경은 `COMPLETED` publication transaction에서만 일어난다.

### 3.4 `DeletionRequest`

| Current State | Command/Event | Preconditions | Next State | Side Effects | Invalid Result |
|---|---|---|---|---|---|
| — | delete commit | owner/scope equivalence, no existing key | `REQUESTED` | tombstone, generation 증가, 접근/job/retry 즉시 차단 | non-owner hidden |
| `REQUESTED` | worker starts | checklist fixed | `CANCELLING_JOBS` | queue/runtime cancel | — |
| `CANCELLING_JOBS` | cancellation acknowledged or bounded cleanup | output discard enforced | `PURGING_PRIMARY` | Core primary/read model purge | 지연은 retry/failure |
| `PURGING_PRIMARY` | primary verified absent | checklist primary complete | `PURGING_OBJECTS` | object/DEK/runtime purge | partial success 유지 |
| `PURGING_OBJECTS` | live scope verified absent | all checklist complete | `COMPLETED` | `backupPurgeDueAt` 추적 | backup 존재는 정상 접근 허용 근거 아님 |
| any non-terminal | retry budget/SLA exhausted | safe failure | `FAILED` | tombstone/access block 유지 | 성공 표시 금지 |
| non-terminal | same command/retry | same key/generation | 동일 또는 다음 합법 단계 | 미완료 checklist만 실행 | 완료 item 재생성 금지 |
| `FAILED` | operator remediation | approved new request, same scope/generation | 원 request `FAILED` 유지 | 새 remediation request | 원 request를 되돌리지 않음 |
| `COMPLETED`/`FAILED` | callback | terminal | 동일 | exact duplicate `NO_OP` | 상태 reversal 금지 |

### 3.5 `ArtifactRetentionStatus`

| Current State | Command/Event | Preconditions | Next State | Side Effects | Invalid Result |
|---|---|---|---|---|---|
| `ACTIVE` | TTL scheduled | policy clock reached/scheduled | `RETENTION_SCHEDULED` | purge enqueue | TTL 연장 금지 |
| `ACTIVE` | explicit delete | deletion commit | `DELETION_PENDING` | read/reparse 차단 | — |
| `RETENTION_SCHEDULED` | worker claim | due | `DELETION_PENDING` | object/DEK purge | — |
| `DELETION_PENDING` | verified object+key absent | both checks pass | `DELETED` | raw availability false | 한쪽만 삭제면 금지 |
| `DELETION_PENDING` | retry exhausted | partial/failed | `DELETION_FAILED` | access block 유지 | `DELETED` 금지 |
| `DELETION_FAILED` | approved remediation succeeds | remaining checklist verified | `DELETED` | failure audit 유지 | `ACTIVE` 복귀 금지 |
| `DELETED` | any upload/reparse event | terminal | `DELETED` | 없음 | 새 session/reupload 필요 |

### 3.6 Failure semantics matrix

`terminal`은 현재 command/attempt 기준이다. terminal Aggregate를 재개하지 않으며 허용된 retry는 새 attempt,
Run, session 또는 remediation request다.

| Failure code/outcome | Retryable | Terminal | Existing result effect | User action | Safe operational log | Alert |
|---|---:|---:|---|---|---|---:|
| `MISSING_REQUIRED_ARTIFACT` | artifact 추가 뒤 예 | 아니오 | 없음 | 누락 role 업로드 | session ID, role, code | 아니오 |
| `FILE_FORMAT_INVALID` | 수정 파일로 예 | 예 | 기존 Book 결과 무변경 | 올바른 export로 새 session | artifact/session ID, row/field, code | 반복 시 |
| `REQUIRED_COLUMN_MISSING` | 수정 파일로 예 | 예 | 무변경 | required field 포함 export | role/field/code | 아니오 |
| `UNSUPPORTED_SOURCE_SCHEMA` | 지원 export로 예 | 예 | 무변경 | supported dialect 사용 | dialect/fingerprint/count/code | 반복 시 |
| `UNSUPPORTED_PRODUCT` / `UNSUPPORTED_POSITION_MODE` | 지원 입력으로 예 | 예 | 무변경 | MVP 범위 입력 사용 | product/mode/code | 반복 시 |
| `RECORD_CONFLICT` | source 수정 뒤 예 | 예 | 기존 Revision/latest 무변경 | export/identity 확인 | opaque row/hash ref, code | 반복 시 예 |
| `ARTIFACT_REFERENCE_EXPIRED` | fresh grant로 예 | 아니오 | 무변경 | 없음 | opaque artifact/attempt/code | threshold |
| `SOURCE_ARTIFACT_EXPIRED` | 같은 Session은 아니오 | 예 | 무변경 | 새 Session에 재업로드 | artifact retention/code | 아니오 |
| `SOURCE_CONTENT_LENGTH_MISMATCH` / `SOURCE_CONTENT_HASH_MISMATCH` | 원인 조사 뒤 새 attempt | 현재 attempt 예 | 무변경 | 운영 복구 대기 | opaque artifact/expected-actual hash prefix/code | 예 |
| `ARTIFACT_IDENTITY_CONFLICT` | 동일 ID로 불가 | command terminal | 무변경 | 운영 복구 대기 | opaque artifact ID/code | 예 |
| `INCOMPLETE_ANALYSIS_RESULT` | 새 corrected attempt | 예 | 기존 Run/result/latest 무변경 | 상태 확인 또는 재실행 | run/version/missing component/code | 예 |
| bounded transient Compute/storage code | 자동 bounded retry | budget 소진 전 아니오 | 무변경 | 소진 뒤 명시적 retry 또는 운영 복구 | opaque job/attempt/code/duration | threshold |
| `REPROCESSING_SOURCE_UNAVAILABLE` | verified reupload 뒤 | 예 | 기존 Review/latest 유지 | 재업로드 또는 가능한 type 선택 | availability/type/version | 아니오 |
| `VERSION_UNAVAILABLE` / `VERSION_INCOMPATIBLE` | target/input 변경 뒤 | 예 | 기존 결과 유지 | ACTIVE compatible target 선택 | version IDs/code | registry 이상 시 |
| `NOT_COMPARABLE_VERSION` | 전 기간 동일 key 재계산 뒤 | query terminal | 각 결과 유지 | comparable rerun | version/config hash | 아니오 |
| `ARTIFACT_DELETION_FAILED` / `DELETION_PARTIALLY_COMPLETED` | remediation 예 | 원 request 예 | 접근 차단 유지 | 상태 확인/지원 | deletion ID/component/code | 예 |
| `STALE_RESULT_AFTER_DELETION` | 아니오 | 예 | payload discard | 없음 | run/old-current generation/code | rate threshold |
| `NOT_AUTHORIZED` | 올바른 인증/CSRF/grant 뒤 | request terminal | 없음 | 인증 또는 승인 | actor class/correlation/reason code | abuse threshold |
| `NOT_FOUND_OR_HIDDEN` | 올바른 owner resource로 | request terminal | 없음 | owned resource 사용 | caller class/correlation only | enumeration threshold |

어느 log에도 raw row/value, UID/fingerprint, Symbol, 거래값, filename/path/URL을 넣지 않는다.

---

## 4. Acceptance Scenarios

### 4.0 Import normalization orchestration (ADR-065)

아래 ID는 후속 Integration fixture/assertion의 stable ID다. `BASE-V1` synthetic 두 Artifact를 쓰며 실제 CSV
fixture는 모두 `FIXTURE_PLANNED`, 장애·경쟁 시나리오는 `FIXTURE_NOT_REQUIRED`다. `—`는 공개 failure 없음,
`N/A`는 ACK할 terminal payload 없음이다. 모든 행은 owner/Book/Session, role별 artifact ID/hash,
inputSetHash, VersionSet, attempt, deletion generation을 audit/evidence로 대조한다. 성공 Revision의
provenance는 source Session, 두 artifact ID/role/SHA-256, pinned versions, coverage, manifest hash,
quality/capability, publishedAt을 포함한다. 실패는 raw/URI/credential을 노출하지 않는다.

| Scenario / Given → When → Then | 최종 Session / Run | Revision / retryable / 공개 failure·userAction | Evidence·stale / ACK |
|---|---|---|---|
| `TR-ACC-IMPORT-ORCH-001` 두 정상 Artifact → validation 완료 → 완전 검증·원자적 publication | ACCEPTED / PUBLISHED | 정확히 1 / false / — | role별 hash, inputSetHash, manifest·quality·capability 고정; stale 없음 / commit 뒤 PERSISTED |
| `TR-ACC-IMPORT-ORCH-002` 같은 입력·VersionSet의 retry 또는 terminal replay → 동일 성공 조회 → 중복 발행 없음 | ACCEPTED / PUBLISHED | 기존 1 / false / — | 같은 logical key·result hash, 추가 membership 없음; stale replay NO_OP / 기존 ACK 재전송만 |
| `TR-ACC-IMPORT-ORCH-003` 첫 Revision 뒤 두 번째 Session 업로드 → 검증 → 첫 membership 미합산 | 두 Session ACCEPTED / 각 PUBLISHED | 각 1 / false / — | 서로 다른 inputSetHash/source Session, 공유 record 허용, latest는 둘째 범위만; stale 없음 / 각 commit 뒤 ACK |
| `TR-ACC-IMPORT-ORCH-004` 시작·끝 경계 불완전 입력 → 검증 → exclusion과 제한된 Capability로 발행 | ACCEPTED / PUBLISHED | 1 / false / — | LEFT/RIGHT/BOTH_CENSORED 또는 INDETERMINATE, role별 NOT_PROVEN, quality reason; stale 없음 / PERSISTED |
| `TR-ACC-IMPORT-ORCH-005` staging 전 grant 만료 → Core fresh grant CAS → 같은 job 재시도 성공 | ACCEPTED / PUBLISHED | 1 / false / — | ARTIFACT_REFERENCE_EXPIRED attempt audit, bytes size/hash 검증; 이전 grant 폐기 / PERSISTED |
| `TR-ACC-IMPORT-ORCH-006` 새 grant의 URI/expiry 변경 → fingerprint 계산 → inputSetHash·logical key 불변 | ACCEPTED / PUBLISHED | 1 / false / — | 원 artifact ID/role/hash/VersionSet 동일, grant URI 저장 금지; stale 없음 / PERSISTED |
| `TR-ACC-IMPORT-ORCH-007` 같은 artifactId에 다른 SHA-256 → grant/update 시도 → 409 conflict | RECEIVED 또는 VALIDATING 불변 / 기존 Run 불변 | 0 / false / ARTIFACT_IDENTITY_CONFLICT·WAIT_FOR_RECOVERY | 두 hash의 불일치 audit만, 원본 무변경; 결과 폐기 / N/A |
| `TR-ACC-IMPORT-ORCH-008` Compute 5xx·timeout → policy budget 내 자동 재시도 → 계속 대기 | VALIDATING / RETRY_WAIT | 0 / true / TEMPORARY_DEPENDENCY_FAILURE·WAIT_FOR_RECOVERY | 동일 inputSetHash, attempt 증가, raw 무변경; stale 없음 / N/A |
| `TR-ACC-IMPORT-ORCH-009` 자동 재시도 예산 소진 → terminal 전이 → 데이터 거부 없음 | PROCESSING_FAILED / TERMINAL_FAILURE | 0 / true(active raw) / TEMPORARY_DEPENDENCY_FAILURE·RETRY | policyVersion·attempt·elapsed audit; partial staging 비공개 / N/A |
| `TR-ACC-IMPORT-ORCH-010` 실패 Session과 active raw → 명시적 retry → 새 Run 성공 | ACCEPTED / 이전 TERMINAL_FAILURE, 새 PUBLISHED | 1 / false / — | retryOf, 같은 inputSetHash·VersionSet, 새 attempt/audit; 이전 결과 OBSOLETE / commit 뒤 PERSISTED |
| `TR-ACC-IMPORT-ORCH-011` raw TTL 만료된 실패 Session → retry command → 410 재업로드 안내 | PROCESSING_FAILED / TERMINAL_FAILURE 불변 | 0 / false / SOURCE_ARTIFACT_EXPIRED·REUPLOAD_REQUIRED | retention status/expiry만 공개, grant 없음; 늦은 결과 폐기 / N/A |
| `TR-ACC-IMPORT-ORCH-012` 필수 컬럼 누락 등 결정적 입력 위반 → validation → 데이터 거부 | REJECTED / TERMINAL_FAILURE | 0 / false / REQUIRED_COLUMN_MISSING·REUPLOAD_REQUIRED | safe failure, qualitySummary=null, latestRevisionId 불변, 원본 값 없음; stale 없음 / N/A |
| `TR-ACC-IMPORT-ORCH-013` Compute 실행 중 Session 삭제 tombstone commit → 늦은 결과 도착 → 발행 금지 | 정상 Session 조회 차단 / OBSOLETE | 0 / false / 공개 resource 410 RESOURCE_DELETED | generation 증가·payload discard, artifact/Run purge; stale 기록 / DISCARDED_STALE |
| `TR-ACC-IMPORT-ORCH-014` retry의 새 execution 뒤 이전 결과 도착 → attempt 비교 → 이전 결과 폐기 | VALIDATING 또는 ACCEPTED / 이전 OBSOLETE, 현 Run active/PUBLISHED | 현 성공 전 0 또는 후 1 / 상황별 / 이전 failure 비공개 | attemptToken·run ID mismatch audit, 이전 payload 불가시화 / 이전 결과 ACK 금지(삭제면 DISCARDED_STALE) |
| `TR-ACC-IMPORT-ORCH-015` Core publication commit 뒤 ACK 전송 실패 → ACK 재시도 → 중복 Revision 없음 | ACCEPTED / PUBLISHED | 1 / false / — | commit ID·result/payload hash 동일, latest 불변; stale 없음 / 같은 PERSISTED ACK 재전송 |
| `TR-ACC-IMPORT-ORCH-016` terminal input hash 또는 VersionSet 불일치 → publication 시 검증 → 발행 금지 | PROCESSING_FAILED / OBSOLETE 또는 TERMINAL_FAILURE | 0 / false / INPUT_SET_HASH_MISMATCH 또는 VERSION_INCOMPATIBLE·WAIT_FOR_RECOVERY | hash prefix·version ID만 audit, staging 비공개; stale 폐기 / PERSISTED 금지 |
| `TR-ACC-IMPORT-ORCH-017` Context OpenAPI의 Core serializer 요청 → 실제 Compute runtime DTO 역직렬화 → 모든 required field 수용 | VALIDATING / SUBMITTED | 0 / N/A / — | `coreNormalizationRunId`, `retryPolicyVersion`, nested identity·VersionSet 유지; extra-field 거부 없음 / N/A |
| `TR-ACC-IMPORT-ORCH-018` Context V1 고정 vector → Core·Compute 각각 canonicalize/hash → oracle 일치 | VALIDATING / DISPATCH_PENDING | 0 / N/A / — | canonical bytes와 `1122481b2c208afc1cae751f952f1b9a670f446f404d42c1e581a3b83f25552e` 일치 / N/A |
| `TR-ACC-IMPORT-ORCH-019` 같은 logical identity, 다른 locator/expiry → create replay/refresh → 같은 job/hash | VALIDATING / SUBMITTED | 0 / N/A / — | idempotency conflict 없음; 최초 create replay는 저장된 grant 유지, 변경은 CAS update / N/A |
| `TR-ACC-IMPORT-ORCH-020` expectedInputSetHash와 Compute 재계산값 불일치 → safe FAILED | PROCESSING_FAILED / TERMINAL_FAILURE | 0 / false / INPUT_SET_HASH_MISMATCH·WAIT_FOR_RECOVERY | 성공 payload 없음, LedgerRevision 없음, source CSV rejection 아님 / N/A |
| `TR-ACC-IMPORT-ORCH-021` Core Run ID 전송 → job 저장·worker restart·terminal 생성·재조회 | ACCEPTED / PUBLISHED | 1 / false / — | 같은 `coreNormalizationRunId`가 status/descriptor/payload까지 보존; exact input/VersionSet/generation 확인 / commit 뒤 PERSISTED |
| `TR-ACC-IMPORT-ORCH-022` source bytes SHA-256 불일치와 terminal result hash 불일치 각각 주입 | PROCESSING_FAILED / TERMINAL_FAILURE | 0 / false / SOURCE_CONTENT_HASH_MISMATCH 또는 RESULT_HASH_MISMATCH·WAIT_FOR_RECOVERY | source mismatch는 normalize 시작 전 Compute FAILED; result mismatch는 Core publication 차단; 어느 쪽도 input mismatch code로 대체 금지 / N/A |

### 4.0 Trading Import quality status (ADR-068)

아래 ID는 비민감 synthetic fixture로 검증한다. source rejection code 여섯 개는 같은 terminal
assertion을 parameterized로 적용한다. `FIXTURE_PLANNED`는 실제 실행 증거가 생기기 전까지 완료가 아니다.

| Scenario | Given | When | Then | Fixture state |
|---|---|---|---|---|
| `TR-ACC-IMPORT-QUALITY-001` source rejection | 필수 column이 빠진 Binance Trade History; Compute `FAILED/REQUIRED_COLUMN_MISSING`, safe structured failure, terminal success payload 없음 | Core가 terminal failure를 처리하고 공개 Import status 조회 | Session `REJECTED`, `failure.code=REQUIRED_COLUMN_MISSING`, `qualitySummary` key 존재·값 `null`, LedgerRevision 0, Book `latestRevisionId` 불변, `PERSISTED` ACK 없음; rejected raw retention 적용 | `FIXTURE_PLANNED` |
| `TR-ACC-IMPORT-QUALITY-002` no synthetic quality | schema 검증 도중 중단된 Import | 공개 Import status 조회 | 미계산 row count, capability, reconciliation count를 0 또는 빈 정상 결과로 합성하지 않고 `qualitySummary=null` | `FIXTURE_PLANNED` |
| `TR-ACC-IMPORT-QUALITY-003` accepted quality | Compute `COMPLETED` payload와 완전하고 검증된 quality report | Core가 publication 완료 | Session `ACCEPTED`, `qualitySummary` non-null, `createdLedgerRevisionId` 존재, 공개 summary가 발행된 LedgerRevision report와 동일; report 누락 시 publication 금지 | `FIXTURE_PLANNED` |
| `TR-ACC-IMPORT-QUALITY-004` processing failure | `RESULT_HASH_MISMATCH` 또는 retry exhaustion | Core가 terminal failure 처리 | Session `PROCESSING_FAILED`와 non-null failure, `REJECTED`로 전환하지 않음, LedgerRevision 0, Book `latestRevisionId` 불변 | `FIXTURE_PLANNED` |
| `TR-ACC-IMPORT-QUALITY-005` source code mapping | Compute `FAILED`와 `FILE_FORMAT_INVALID`, `REQUIRED_COLUMN_MISSING`, `UNSUPPORTED_SOURCE_SCHEMA`, `UNSUPPORTED_PRODUCT`, `UNSUPPORTED_POSITION_MODE`, `RECORD_CONFLICT` 중 하나를 각 case로 주입 | Core가 각 terminal failure 처리 | 각 case는 `REJECTED`, 동일 failure code 저장, `qualitySummary=null`, Revision 0, latest 불변; integrity·delivery·운영 failure code는 `REJECTED` 금지 | `FIXTURE_PLANNED` |

### 4.0.1 Result integrity publication (ADR-066)

`fixtures/trading-normalization/result-integrity-v1.json`은 비민감 고정 oracle이다. 각 행은
Given / When / Then 순서이며 `TR-I04`, `TR-I06`, `TR-I40`, `TR-I41`을 적용한다.
성공은 Session `ACCEPTED`/Run `PUBLISHED`, 실패는 Session `PROCESSING_FAILED`/
Run `TERMINAL_FAILURE`/Revision 0/`retryable=false`/`WAIT_FOR_RECOVERY`다. 실패 시 staging
폐기, latest 불변, `PERSISTED` ACK 금지, Compute TTL cleanup이며 source CSV `REJECTED`가
아니다. 삭제 generation 증가만 `DISCARDED_STALE` 경계를 따른다. 각 실패의 운영 로그는
opaque Run ID, section/code, hash prefix 최대 12자만 허용한다.

| Stable Scenario ID | Given | When | Then |
|---|---|---|---|
| `TR-ACC-IMPORT-INTEGRITY-001` | V1 모든 section/count/manifest/digest가 vector와 일치 | Core가 수신 manifest hash, staging 실제 count, section/overall hash를 독립 검증 | 모두 일치; Revision 하나 발행; commit 뒤 PERSISTED |
| `TR-ACC-IMPORT-INTEGRITY-002` | canonical record 하나 누락, declared count 유지 | staging count 검사 | `RESULT_COUNT_MISMATCH`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-003` | Evidence snapshot 하나 누락, declared count 유지 | Evidence count 검사 | `RESULT_COUNT_MISMATCH`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-004` | record 수 동일, 가격만 변경 | canonical section 재계산 | `RESULT_SECTION_HASH_MISMATCH`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-005` | 공개 manifest 내용 변경, hash 유지 | manifest 재계산 | `RECONCILIATION_MANIFEST_HASH_MISMATCH`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-006` | section 자체는 유효, 선언 section digest만 변경 | overall digest manifest 재계산 | `RESULT_HASH_MISMATCH`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-007` | 같은 semantic item을 다른 page 크기/수신 순서로 보냄(V2 transport harness) | Core가 전체 unique item 정렬 | section/result hash 동일; 발행 허용 |
| `TR-ACC-IMPORT-INTEGRITY-008` | 같은 page identity/bytes 재수신(V2 transport harness) | Core가 중복을 무시 | unique count/hash 불변; 발행 허용 |
| `TR-ACC-IMPORT-INTEGRITY-009` | 같은 `recordId`에 다른 공개 payload | Core가 identity conflict 검사 | `RESULT_ITEM_CONFLICT`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-010` | Core 미지원 result hash version | version gate 실행 | `RESULT_SCHEMA_UNSUPPORTED`, PROCESSING_FAILED; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-011` | 존재하지 않는 canonical record를 Evidence/allocation이 참조 | 참조 무결성 검사 | `EVIDENCE_REFERENCE_INVALID`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-012` | response entity bytes와 descriptor SHA-256 불일치 | 동일 payloadId 한 번 재조회 | 복구되면 semantic 검증 진행; 반복되면 `RESULT_TRANSPORT_HASH_MISMATCH`; 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-013` | 유효한 V1 manifest의 수신 `counts` 한 값을 바꾸고 기존 `manifestHash` 유지 | 수신 manifest에서 `manifestHash`만 제외하고 `counts` 포함 hash 재계산 | `RECONCILIATION_MANIFEST_HASH_MISMATCH`; staging 폐기, Revision 없음, latest 불변 |
| `TR-ACC-IMPORT-INTEGRITY-014` | 수신 `counts` 한 값을 변경하고 manifest hash, manifest section digest, 전체 resultHash 및 관련 선언값을 그 값에 맞게 다시 계산; 실제 section item은 그대로 유지 | manifest hash 일치 확인 후 staging 실제 count와 수신 `counts` 비교 | `RESULT_COUNT_MISMATCH`; manifest hash 성공을 count 검증 성공으로 취급하지 않고 발행 금지 |
| `TR-ACC-IMPORT-INTEGRITY-015` | V1 `ReconciliationManifestPayload`에서 required `counts` 누락 또는 null | payload schema 검증 | 정상 manifest가 아니므로 발행 금지; hash 입력을 임의의 재계산 count로 채우지 않음 |
| `TR-ACC-IMPORT-INTEGRITY-016` | 유효한 V1 manifest의 논리값은 같고 JSON object field 순서만 다름 | JCS 적용 후 manifest hash 비교 | 기존 `manifestHash`와 동일; count와 다른 검증도 통과하면 발행 허용 |

V1은 단일 resource이며 007/008의 실제 page/chunk runtime은 새 schema/endpoint 이후의
후속 Integration 요구사항이다. 현재 `verify-contract-sync.ps1`과 input identity runtime test는
이 결과 무결성 chain을 검증하지 않는다. V1 후속 harness는 Compute 생성→단일 resource 전송→
Core staging→수신 manifest hash→실제 count→section/overall hash 검증을 관통해야 한다.

### 4.0.2 Bounded V1 result delivery (ADR-067)

아래 stable ID는 `TR-I40`~`TR-I41`과 [delivery 정본](TRADING_NORMALIZATION_RESULT_DELIVERY.md)에 연결한다. 모두 비민감 synthetic fixture로 수행한다. 성공은 Session `ACCEPTED`/Run `PUBLISHED`/Revision 1과 commit 후 `PERSISTED` ACK다. 실패는 별도 명시가 없으면 Session `PROCESSING_FAILED`/Run `TERMINAL_FAILURE`/Revision 0, staging·spool 삭제, latest 불변, `PERSISTED` ACK 금지, Compute payload TTL cleanup이다. 자동 재조회 중에는 Session `VALIDATING`이며 삭제 우선순위는 `DISCARDED_STALE`을 따른다. 각 행은 후속 Integration `FIXTURE_PLANNED`다.

| Stable Scenario ID | Given | When | Then |
|---|---|---|---|
| `TR-ACC-IMPORT-DELIVERY-001` | policy 한도 내 V1 result | Core가 실제 Compute HTTP stream 수신 | 전체 body/tree heap 적재 없이 protected spool+incremental hash; 확인 후 streaming parse·batch staging·semantic 검증; publication 가능 |
| `TR-ACC-IMPORT-DELIVERY-002` | Content-Length > Core advertised 한도 | Core가 header 검사 | body 전체 읽기 전 `RESULT_PAYLOAD_LIMIT_EXCEEDED`, 발행 금지 |
| `TR-ACC-IMPORT-DELIVERY-003` | Content-Length 없음 | Core가 actual bytes count | 한도 이내면 성공; 넘는 즉시 `RESULT_PAYLOAD_LIMIT_EXCEEDED`로 중단 |
| `TR-ACC-IMPORT-DELIVERY-004` | descriptor/header/actual 길이 불일치 | stream EOF 또는 선언보다 큰 bytes | `RESULT_PAYLOAD_LENGTH_MISMATCH`, 같은 resource 최대 1회 전체 재조회 후 실패, 발행 금지 |
| `TR-ACC-IMPORT-DELIVERY-005` | one-byte 변조, length 동일 | incremental transport hash 비교 | parse/staging 시작 전 차단, 같은 resource 최대 1회 재조회, 반복 시 `RESULT_TRANSPORT_HASH_MISMATCH`; spool 삭제 |
| `TR-ACC-IMPORT-DELIVERY-006` | Compute 실제 serialized bytes가 effective maximum 초과 | terminal finalization | `FAILED/RESULT_PAYLOAD_LIMIT_EXCEEDED`, 성공 payload/descriptor 없음; Core 다운로드 없음 |
| `TR-ACC-IMPORT-DELIVERY-007` | hash 유효, 공개 schema로 parsing 불가 | Core streaming parse | `RESULT_PAYLOAD_PARSE_FAILED`, 부분 staging 발행 금지, spool 삭제 |
| `TR-ACC-IMPORT-DELIVERY-008` | item 수 > batch 크기 | streaming staging 및 재검증 | bounded heap, 여러 batch, 최종 count/section/overall hash 동일 |
| `TR-ACC-IMPORT-DELIVERY-009` | spool 생성 뒤 Core crash | orphan sweeper 실행 | retention 상한 내 spool 삭제, LedgerRevision 없음 |
| `TR-ACC-IMPORT-DELIVERY-010` | 수신 중 Session 삭제·generation 상승 | Core가 stream/transaction 재검사 | stream 취소, spool/staging 삭제, publication 금지, stale 정책 우선 |
| `TR-ACC-IMPORT-DELIVERY-011` | 큰 synthetic payload와 제한 JVM heap | 실제 Compute→Core 전달 | OOM 없이 성공 또는 명시적 limit failure; heap 사용량이 payload 크기에 선형 증가하지 않음 |
| `TR-ACC-IMPORT-DELIVERY-012` | gzip 등 V1 미지원 encoding | header/descriptor 검사 | `RESULT_CONTENT_ENCODING_UNSUPPORTED`, publication 금지 |
| `TR-ACC-IMPORT-DELIVERY-013` | 응답 stream 중단/truncation | EOF/예외 처리 | 예외는 `RESULT_PAYLOAD_STREAM_ABORTED` 재조회, 정상 EOF 길이 부족은 `RESULT_PAYLOAD_LENGTH_MISMATCH`; 모두 부분 publish 금지 |

Harness는 실제 Compute result endpoint와 Core receiver를 연결해 Content-Length 유무/초과, actual 초과, truncate, 변조, encoding, crash, 제한 heap, 삭제 경쟁을 주입한다. 단위 verifier나 fixture bytes 복사 검사만으로 완료 처리하지 않는다. `FIXTURE_PLANNED`를 실제 실행 전 PASS로 표시하지 않는다.

`TR-ACC-IMPORT-ORCH-014`의 이전 payload는 Compute cleanup TTL 또는 명시적 cancellation으로 정리한다.
`DISCARDED_STALE` ACK는 삭제 generation이 증가한 경우에만 사용한다. `TR-ACC-IMPORT-ORCH-016`의
VersionSet 불일치는 `VERSION_INCOMPATIBLE`이며 원본 데이터 `REJECTED`로 만들지 않는다.
`017`~`022`는 runtime contract 요구사항이다. V1 hash vector는 Context에 고정돼 있으나
실제 Core→Compute HTTP/DTO→terminal→Core 경계 fixture/E2E는 후속 Integration 작업이므로
`FIXTURE_PLANNED`다. 특히 OpenAPI가 required로 선언한 값을 `extra=forbid` DTO가 거부하면
snapshot sync가 통과해도 `017` 실패다.

각 scenario는 아래 18개 필드를 모두 가진다. `Evidence`는 assertion에 필요한 chain 또는 state evidence이고,
`Deferred HTTP Contract`는 transport 의미만 분류한다.

### A. Import

#### TR-ACC-IMPORT-001 — Dual-artifact normal import

- **Purpose / Use Case / Actors:** 정상 원자 수입; `CreateTradingImportSession`, `UploadTradingImportArtifact`, `ValidateTradingImportSession`; owner, Web, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** active owner/Book, generation 일치; `BASE-V1`; `FIXTURE_PLANNED fixtures/trading-review/binance-usds-v1/simple-long`.
- **Given / When / Then:** 두 role artifact와 timezone/mode/policy가 고정됨 / validation 실행 / session `ACCEPTED`, 정확히 한 Revision, `COMPLETE+EXACT` episode, quality eligible 1/excluded 0.
- **Aggregate State Changes / Created or Updated Artifacts:** `RECEIVED→VALIDATING→ACCEPTED`; 두 artifact metadata, canonical records, manifest, snapshot, LedgerRevision 생성.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; 없음.
- **Idempotency Result:** 같은 validation/callback `NO_OP`, 새 Revision 없음.
- **Evidence / Applied Invariants:** Metric 이전에도 episode→allocation→record→두 source row; `TR-I01`~`TR-I09`, `TR-I21`.
- **Service Ownership / Observability / Deferred HTTP Contract:** 공통 소유권; `SAFE`; command accepted asynchronously, terminal status query.

#### TR-ACC-IMPORT-002 — Accepted import with excluded episodes

- **Purpose / Use Case / Actors:** 일부 불완전 episode를 숨기지 않고 정상 record를 보존; import Use Case; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** 한 eligible episode와 한 right-censored episode; `BASE-V1`; `FIXTURE_PLANNED fixtures/trading-review/binance-usds-v1/accepted-with-exclusions`.
- **Given / When / Then:** 두 artifact valid / validation / session `ACCEPTED`, Revision 하나, eligible 1/excluded 1, reason `EPISODE_RIGHT_CENSORED`, Analytics에는 eligible만 전달.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted 전이; excluded unit과 quality report도 manifest/Revision에 생성.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; episode exclusion은 import rejection이 아님.
- **Idempotency Result:** `RETURN_EXISTING`/callback `NO_OP`.
- **Evidence / Applied Invariants:** excluded episode도 source row까지 추적; `TR-I03`, `TR-I04`, `TR-I06`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** 공통; `SAFE` exclusion count/code; command accepted asynchronously.

#### TR-ACC-IMPORT-003 — Position History uploaded first

- **Purpose / Use Case / Actors:** artifact 순서 비의존과 준비 gate; upload/query; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** empty `RECEIVED`; `BASE-V1`; `FIXTURE_PLANNED fixtures/trading-review/binance-usds-v1/simple-long`.
- **Given / When / Then:** Position role 먼저 업로드 / 상태 조회 / session은 `RECEIVED`, dispatch 0, Revision 0; Trade 업로드 뒤에도 명시적 validation 전 dispatch 0.
- **Aggregate State Changes / Created or Updated Artifacts:** session 상태 불변; Position artifact만 먼저 생성.
- **Expected Outcome / Expected Failure or Exclusion:** upload `SUCCEEDED`; validation 미실행.
- **Idempotency Result:** 같은 Position upload `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** role slot과 hash; `TR-I03`, `TR-I04`, `TR-I21`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core만 상태 수정; `SAFE`; command accepted, status query.

#### TR-ACC-IMPORT-004 — Explicit validation after both artifacts

- **Purpose / Use Case / Actors:** 준비와 실행을 분리; `ValidateTradingImportSession`; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** 두 artifact `ACTIVE`, session `RECEIVED`; `BASE-V1`; `FIXTURE_PLANNED .../simple-long`.
- **Given / When / Then:** dispatch 0 / validation command / atomic `VALIDATING`, dispatch 정확히 1, 두 artifact/version/generation 변경 불가.
- **Aggregate State Changes / Created or Updated Artifacts:** `RECEIVED→VALIDATING`; durable dispatch/attempt 생성, Revision 아직 0.
- **Expected Outcome / Expected Failure or Exclusion:** command `SUCCEEDED`; terminal outcome pending.
- **Idempotency Result:** 진행 중 반복 `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** pinned dispatch input hash; `TR-I03`, `TR-I06`, `TR-I11`, `TR-I21`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core dispatch, Compute runtime; `SAFE`; command accepted asynchronously.

#### TR-ACC-IMPORT-005 — Trade History missing

- **Purpose / Use Case / Actors:** one-file validation 금지; validation command; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** Position만 등록; `BASE-V1`; `FIXTURE_PLANNED .../missing-trade-history`.
- **Given / When / Then:** session `RECEIVED` / validate / 상태·dispatch·Revision 불변.
- **Aggregate State Changes / Created or Updated Artifacts:** 없음.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `MISSING_REQUIRED_ARTIFACT`, retryable true after upload, terminal false, existing none, user uploads Trade, safe role/code, no alert.
- **Idempotency Result:** 반복 `NO_OP`; missing artifact 업로드 뒤 새 logical validation 가능.
- **Evidence / Applied Invariants:** role inventory; `TR-I03`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core precondition; `SAFE`; validation rejected synchronously.

#### TR-ACC-IMPORT-006 — Position History missing

- **Purpose / Use Case / Actors:** dual-artifact atomicity; validation; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** Trade만 등록; `BASE-V1`; `FIXTURE_PLANNED .../missing-position-history`.
- **Given / When / Then:** one role / validate / `RECEIVED`, dispatch 0, Revision 0.
- **Aggregate State Changes / Created or Updated Artifacts:** 없음.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `MISSING_REQUIRED_ARTIFACT`, true, false, none, upload Position, role/code only, no alert.
- **Idempotency Result:** scenario 005와 동일.
- **Evidence / Applied Invariants:** artifact role list; `TR-I03`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; validation rejected synchronously.

#### TR-ACC-IMPORT-007 — Source timezone missing

- **Purpose / Use Case / Actors:** timezone 추론 금지; create/validate import; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** timezone 없는 session request; `BASE-V1`; `FIXTURE_PLANNED .../missing-source-timezone`.
- **Given / When / Then:** offset 없는 source time / command / session acceptance와 Compute dispatch 없음.
- **Aggregate State Changes / Created or Updated Artifacts:** session 생성 자체를 거절하거나 existing `RECEIVED` 유지; Revision 0.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `FILE_FORMAT_INVALID`, false for same request, terminal false/session not validated, none, supply IANA timezone, field/code only, no alert.
- **Idempotency Result:** 같은 invalid command `NO_OP`; corrected input은 새 logical command/session.
- **Evidence / Applied Invariants:** validation issue without source value; `TR-I03`, `TR-I11`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core validation; `SAFE`; validation rejected synchronously.

#### TR-ACC-IMPORT-008 — Required column missing

- **Purpose / Use Case / Actors:** schema fidelity; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** one V1 artifact omits required logical field; `BASE-V1`; `FIXTURE_PLANNED .../required-column-missing`.
- **Given / When / Then:** both files present / validation / `REJECTED`, Revision 0, raw rejected TTL starts.
- **Aggregate State Changes / Created or Updated Artifacts:** `RECEIVED→VALIDATING→REJECTED`; safe validation report only.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `REQUIRED_COLUMN_MISSING`, false, true, none, export correct files/new session, field/code only, no alert.
- **Idempotency Result:** duplicate callback `NO_OP`; terminal revalidation forbidden.
- **Evidence / Applied Invariants:** artifact/role/field name, no value; `TR-I03`, `TR-I18`, `TR-I21`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute validates, Core transitions; `SAFE`; terminal failure by status query.

#### TR-ACC-IMPORT-009 — Unsupported source dialect

- **Purpose / Use Case / Actors:** parser 범위 확장 추정 금지; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** unregistered dialect/header semantics; `BASE-V1`; `FIXTURE_PLANNED .../unsupported-dialect`.
- **Given / When / Then:** two artifacts / validate / `REJECTED`, no canonical record/Revision.
- **Aggregate State Changes / Created or Updated Artifacts:** terminal rejected report.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `UNSUPPORTED_SOURCE_SCHEMA`, false, true, none, use supported export, dialect/code, no alert.
- **Idempotency Result:** `NO_OP` terminal retry.
- **Evidence / Applied Invariants:** schema fingerprint/count; `TR-I03`, `TR-I04`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal failure status query.

#### TR-ACC-IMPORT-010 — Unsupported Position status

- **Purpose / Use Case / Actors:** non-Closed row를 전체 rejection과 구분; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** valid files, one Position status not `Closed`; `BASE-V1`; reconstruction scenario O.2 #22, `FIXTURE_PLANNED .../unsupported-position-status`.
- **Given / When / Then:** valid schema / validate / `ACCEPTED`, one Revision, affected episode `COMPLETE+SUMMARY_MISMATCH`, excluded reason `UNSUPPORTED_POSITION_STATUS`.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted ledger/manifest/quality report.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; no import failure.
- **Idempotency Result:** `RETURN_EXISTING`/`NO_OP`.
- **Evidence / Applied Invariants:** Position row→issue and Trade allocations; `TR-I04`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal status query.

#### TR-ACC-IMPORT-011 — Hedge overlap

- **Purpose / Use Case / Actors:** One-way incompatibility 전체 거절; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** summaries require simultaneous opposite positions; `BASE-V1`; reconstruction O.2 #21, `FIXTURE_PLANNED .../hedge-overlap`.
- **Given / When / Then:** declared One-way / validate / `REJECTED`, Revision 0, provisional issue retained in report only.
- **Aggregate State Changes / Created or Updated Artifacts:** terminal rejection.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `UNSUPPORTED_POSITION_MODE`, false, true, old results unchanged, upload One-way source, opaque refs/code, alert only repeated/systemic.
- **Idempotency Result:** terminal callback `NO_OP`.
- **Evidence / Applied Invariants:** conflicting execution/summary refs; `TR-I02`, `TR-I03`, `TR-I09`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal status query.

#### TR-ACC-IMPORT-012 — COIN-M product

- **Purpose / Use Case / Actors:** MVP product boundary; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** non-USDⓈ linear product; `BASE-V1`; `FIXTURE_PLANNED .../coin-m`.
- **Given / When / Then:** unsupported terms / validate / `REJECTED`, no records/Revision.
- **Aggregate State Changes / Created or Updated Artifacts:** rejected report.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `UNSUPPORTED_PRODUCT`, false, true, none, supported source required, product family/code only, no alert.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** dialect/product metadata; `TR-I02`, `TR-I03`, `TR-I09`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal failure query.

#### TR-ACC-IMPORT-013 — Same execution identity, different content

- **Purpose / Use Case / Actors:** source conflict를 dedupe로 숨기지 않음; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** same fingerprint/Symbol/Trade ID, different canonical content; `BASE-V1`; reconstruction O.2 #18, `FIXTURE_PLANNED .../identity-content-conflict`.
- **Given / When / Then:** conflicting rows / validate / `REJECTED`, record/Revision 0.
- **Aggregate State Changes / Created or Updated Artifacts:** rejection report with row/hash refs.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `RECORD_CONFLICT`, false, true, existing Book revision unchanged, inspect exports/new session, row/hash only, `ALERT` on repeated/systemic conflicts.
- **Idempotency Result:** same conflict `NO_OP`; content change requires new session.
- **Evidence / Applied Invariants:** both provenance refs, no values; `TR-I03`, `TR-I04`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE ALERT`; terminal failure query.

#### TR-ACC-IMPORT-014 — Trade and Position files incompatible

- **Purpose / Use Case / Actors:** 두 oracle 불일치를 조용히 보정하지 않음; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** schema/product valid but no unique compatible matches; `BASE-V1`; `FIXTURE_PLANNED .../incompatible-pair`.
- **Given / When / Then:** both files parse / validate / session `ACCEPTED`, Revision one, affected units `SUMMARY_MISMATCH` or `INCOMPLETE_TRADES`, eligible 0, exclusions preserved.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted ledger and complete quality report.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; reasons include `POSITION_SUMMARY_MISSING`, `POSITION_SUMMARY_WITHOUT_TRADES`, or constraint mismatch as fixture asserts.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** unmatched candidate graph and source refs; `TR-I04`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; accepted terminal query.

#### TR-ACC-IMPORT-015 — Invalid source Decimal lexeme

- **Purpose / Use Case / Actors:** locale/float correction 금지; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** exponent/thousands separator/whitespace/NaN; `BASE-V1`; `FIXTURE_PLANNED .../invalid-decimal`.
- **Given / When / Then:** invalid required value / validate / `REJECTED`, no canonical record/Revision.
- **Aggregate State Changes / Created or Updated Artifacts:** rejected validation report.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `FILE_FORMAT_INVALID`, false, true, none, corrected export/new session, row/field/code no value, no alert.
- **Idempotency Result:** terminal `NO_OP`.
- **Evidence / Applied Invariants:** masked row number and field; `TR-I03`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal failure query.

#### TR-ACC-IMPORT-016 — Same artifact re-upload

- **Purpose / Use Case / Actors:** byte-identical duplicate storage 방지; upload; owner, Core, Object Storage.
- **Preconditions / Pinned Versions / Input Fixture:** role/hash already registered in `RECEIVED`; policy V1; `FIXTURE_PLANNED .../simple-long`.
- **Given / When / Then:** same bytes/role / upload again / same artifact ID, object count unchanged, provenance upload attempt auditable without filename/value.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** artifact hash/role; `TR-I04`, `TR-I14`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core/object; `SAFE`; command accepted.

#### TR-ACC-IMPORT-017 — Same content, different filename

- **Purpose / Use Case / Actors:** filename을 identity/provenance key로 사용하지 않음; upload; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** same session/role/raw hash, changed client filename; policy V1; `FIXTURE_PLANNED .../same-content-different-name`.
- **Given / When / Then:** existing artifact / upload / artifact/object/revision count unchanged; filename does not enter logical hash or long-lived audit.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** hash equality; `TR-I04`, `TR-I18`, `TR-I21`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; command accepted.

#### TR-ACC-IMPORT-018 — Overlapping new Import remains a separate Revision

- **Purpose / Use Case / Actors:** 같은 Book의 immutable record 재사용과 Revision 입력 집합 분리; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** existing accepted Revision and new session with overlapping same-identity/same-content execution plus new rows; `BASE-V1`; `FIXTURE_PLANNED .../overlapping-export`.
- **Given / When / Then:** owner/Book match / validate / 새 Session의 중복 행과 새 행만 가진 Revision이 ACCEPTED된다. 같은 내용 record는 재사용할 수 있지만 첫 Revision의 다른 행을 새 Revision에 복사하지 않고, 각 Revision 안에서만 한 번 배분한다.
- **Aggregate State Changes / Created or Updated Artifacts:** old Revision immutable; new Revision/manifest/quality created and optionally latest updated.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED` or 새 입력의 명시적 exclusion. Session 사이 기간 병합·중복 제거는 수행하지 않는다.
- **Idempotency Result:** same new session validation `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** execution→both artifacts/source rows; `TR-I04`~`TR-I08`, `TR-I35`, `TR-I37`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async accepted.

#### TR-ACC-IMPORT-019 — Same identity and same content rows

- **Purpose / Use Case / Actors:** in-artifact/source dedupe; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** duplicated row identity/content; `BASE-V1`; reconstruction O.2 #17, `FIXTURE_PLANNED .../duplicate-same-content`.
- **Given / When / Then:** duplicates / validate / one canonical record set and one allocation, all provenance refs retained, Revision accepted.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision with dedupe quality count.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; no conflict/exclusion.
- **Idempotency Result:** canonical content independent of row duplication; same hash where canonical source set is equal.
- **Evidence / Applied Invariants:** one record→multiple row refs; `TR-I04`, `TR-I07`, `TR-I11`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async accepted.

#### TR-ACC-IMPORT-020 — Different content across Sessions stays separate

- **Purpose / Use Case / Actors:** cross-session conflict 해소를 Dataset composition으로 유보; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** Book already contains identity with different content in new session; `BASE-V1`; `FIXTURE_PLANNED .../overlap-conflict`.
- **Given / When / Then:** 다른 Session의 identity collision / validate / 새 Session 범위만 가진 Revision `ACCEPTED`; 기존 Revision 불변. 같은 Session 내부의 다른 내용 충돌이면 `REJECTED/RECORD_CONFLICT`다.
- **Aggregate State Changes / Created or Updated Artifacts:** 다른 내용은 별도 immutable record/Revision; cross-session resolution 없음.
- **Expected Outcome / Expected Failure or Exclusion:** cross-session `SUCCEEDED`; 같은 Session 내부 충돌은 `REJECTED/RECORD_CONFLICT`.
- **Idempotency Result:** 같은 Session 재검증은 기존 결과 `NO_OP`; 서로 다른 Session은 별도 inputSetHash.
- **Evidence / Applied Invariants:** 두 Session의 분리된 artifact/row/content hash refs; `TR-I03`~`TR-I06`, `TR-I39`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core record/Revision, Compute 한 Session 정규화; `SAFE`; terminal query.

#### TR-ACC-IMPORT-021 — Repeated validation command

- **Purpose / Use Case / Actors:** duplicate dispatch/Revision 방지; validation; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** exact key already `VALIDATING` or `ACCEPTED`; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** same key / command repeated / active or terminal session returned, dispatch logical count 1, accepted Revision count 1.
- **Aggregate State Changes / Created or Updated Artifacts:** none beyond original attempt.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** running `RETURN_EXISTING`, accepted `NO_OP`/existing result.
- **Evidence / Applied Invariants:** idempotency key and attempt history; `TR-I05`, `TR-I06`, `TR-I11`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; command accepted asynchronously.

#### TR-ACC-IMPORT-022 — Duplicate normalization terminal handoff

- **Purpose / Use Case / Actors:** terminal polling/download/ack 재시도 안전성; internal completion; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** session already terminal from identical payload; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** same attempt/input/result/payload hash / terminal handoff repeated / status, Revision, records, latest and audit business counts unchanged.
- **Aggregate State Changes / Created or Updated Artifacts:** none; transport attempt count may increment.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; conflicting hash is `RECORD_CONFLICT`/`FAILED_TERMINAL` and never overwrites.
- **Idempotency Result:** exact duplicate `NO_OP`; conflicting payload `REJECT_CONFLICT`.
- **Evidence / Applied Invariants:** terminal hashes/generation; `TR-I05`, `TR-I06`, `TR-I11`, `TR-I16`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core verifies; `SAFE`; internal polling/result/ack.

### B. Reconstruction and Reconciliation

#### TR-ACC-RECON-001 — Simple Long episode

- **Purpose / Use Case / Actors:** flat-to-flat Long oracle; import validation; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #1; `BASE-V1`; `FIXTURE_PLANNED fixtures/trading-review/binance-usds-v1/simple-long`.
- **Given / When / Then:** matching Trade/Position rows / validate / `COMPLETE`, `EXACT`, eligible true, no exclusion, accepted Revision quality 1/1.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted session, episode with OPEN/CLOSE allocations and manifest.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** same input same episode/manifest hash.
- **Evidence / Applied Invariants:** both executions and summary; `TR-I04`, `TR-I06`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async import.

#### TR-ACC-RECON-002 — Partial entry and partial exit

- **Purpose / Use Case / Actors:** allocation/average/conservation; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** combines reconstruction O.2 #3/#4; `BASE-V1`; `FIXTURE_PLANNED .../partial-entry-exit`.
- **Given / When / Then:** multiple fills / validate / one `COMPLETE+EXACT` episode; OPEN/INCREASE/REDUCE/CLOSE quantities, fee/PnL sums and exact rational averages match golden.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision/manifest/quality.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; eligible true.
- **Idempotency Result:** same hashes.
- **Evidence / Applied Invariants:** every allocation to source row; `TR-I04`, `TR-I07`, `TR-I08`, `TR-I11`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async import.

#### TR-ACC-RECON-003 — Long to Short reversal

- **Purpose / Use Case / Actors:** disjoint reversal allocation; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #7; `BASE-V1`; `FIXTURE_PLANNED .../long-short-reversal`.
- **Given / When / Then:** oversize SELL / validate / two `COMPLETE+EXACT` episodes, close/open quantities disjoint, fee complement and PnL-close-only conservation, common reversal group.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision with two units.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; both eligible, reversal-open flagged for analytics relation exclusion only.
- **Idempotency Result:** deterministic group/manifest hash.
- **Evidence / Applied Invariants:** one execution→two non-overlap allocations; `TR-I04`, `TR-I07`~`TR-I09`, `TR-I13`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async import.

#### TR-ACC-RECON-004 — Left-censored episode

- **Purpose / Use Case / Actors:** 시작 누락 추정 금지; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #9; `BASE-V1`; `FIXTURE_PLANNED .../left-censored`.
- **Given / When / Then:** first opening row has non-zero PnL/earlier summary / validate / shell `LEFT_CENSORED` plus issue, `INCOMPLETE_TRADES`, eligible false, reason `EPISODE_LEFT_CENSORED`.
- **Aggregate State Changes / Created or Updated Artifacts:** session accepted, excluded shell and quality report preserved.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; import not rejected.
- **Idempotency Result:** deterministic exclusion/hash.
- **Evidence / Applied Invariants:** Trade+Position issue refs; `TR-I04`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; accepted status query.

#### TR-ACC-RECON-005 — Right-censored episode

- **Purpose / Use Case / Actors:** 열린 끝 경계 추정 금지; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #10; `BASE-V1`; `FIXTURE_PLANNED .../right-censored`.
- **Given / When / Then:** final signed quantity non-zero / validate / `RIGHT_CENSORED+INCOMPLETE_TRADES`, eligible false, `EPISODE_RIGHT_CENSORED`, `closedAt=null`.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted ledger with excluded episode.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** ordered executions; `TR-I04`, `TR-I09`, `TR-I10`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; accepted query.

### C. Analysis

#### TR-ACC-ANALYSIS-001 — Calculate all three V1 Metrics

- **Purpose / Use Case / Actors:** 정상 analytics 완료; `RunTradingAnalysis`; owner, Web, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** accepted compatible Revision with required samples; `BASE-V1`, `AnalysisConfig V1-default`; `FIXTURE_PLANNED fixtures/trading-review/analytics-v1/all-metrics-available`.
- **Given / When / Then:** pinned Revision/config / run / `PENDING→RUNNING→COMPLETED`; 세 Metric `AVAILABLE`, 각 population/eligible/excluded count와 exact result/evidence 존재.
- **Aggregate State Changes / Created or Updated Artifacts:** AnalysisRun과 immutable result/evidence artifact 생성; 기존 runs 불변.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; quality exclusion이 있으면 `ACCEPTED_WITH_EXCLUSIONS`지만 completion은 유지.
- **Idempotency Result:** same logical request `RETURN_EXISTING`; same canonical hash.
- **Evidence / Applied Invariants:** Metric→Observation→episode→allocation→record→source; `TR-I04`, `TR-I10`~`TR-I13`, `TR-I33`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async accepted and status query.

#### TR-ACC-ANALYSIS-002 — Re-entry lower/upper boundary contract

- **Purpose / Use Case / Actors:** `(0, PT30M]` 고정; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** analytics §13.1 #1~#4; Metric version 1/config window PT30M; `FIXTURE_PLANNED .../reentry-boundaries`.
- **Given / When / Then:** loss와 29:59, 30:00, 30:00.000001, same-instant candidates / run / first two selected, latter two excluded, population/eligible/excluded reason counts exact.
- **Aggregate State Changes / Created or Updated Artifacts:** completed result/relations.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; `ENTRY_AFTER_WINDOW`, `ENTRY_AT_SAME_TIMESTAMP` evidence; valid rate may be zero.
- **Idempotency Result:** same result/evidence hash.
- **Evidence / Applied Invariants:** all selected/excluded candidates; `TR-I04`, `TR-I10`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; result status query.

#### TR-ACC-ANALYSIS-003 — Exactly 30-minute entry

- **Purpose / Use Case / Actors:** inclusive upper boundary single assertion; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** analytics §13.1 #2; Metric definition 1/PT30M; `FIXTURE_PLANNED .../reentry-exact-boundary`.
- **Given / When / Then:** one eligible loss and entry at +1800s / run / `eligibleLossCount=1`, `lossWithEntryCount=1`, rate exact 1, elapsed 1,800,000,000µs, `AVAILABLE`.
- **Aggregate State Changes / Created or Updated Artifacts:** completed relation/result.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** loss/entry opening-closing allocations; `TR-I10`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-004 — Reversal excluded from rapid re-entry

- **Purpose / Use Case / Actors:** reversal relation 분리; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** analytics §13.1 #5; Metric definition 1; `FIXTURE_PLANNED .../reentry-reversal`.
- **Given / When / Then:** loss reversal-close and same-execution reversal-open / run / loss subject eligible, regular related set empty, rate 0 `AVAILABLE`, reversal evidence `REVERSAL_SEPARATE`.
- **Aggregate State Changes / Created or Updated Artifacts:** completed Metric/Observation.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; `ENTRY_IS_REVERSAL` is relation exclusion, not missing capability.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** reversal group and disjoint allocations; `TR-I07`, `TR-I10`~`TR-I13`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-005 — Same-timestamp cross-symbol entries

- **Purpose / Use Case / Actors:** artificial global order 금지; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** analytics §13.1 #8; Metric definition 1; `FIXTURE_PLANNED .../cross-symbol-earliest-set`.
- **Given / When / Then:** two eligible entries at same earliest instant / run / unordered related set size 2, numerator 1, same/different counts each 1, canonical sort only.
- **Aggregate State Changes / Created or Updated Artifacts:** completed relations/evidence.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** input row order does not change hash.
- **Evidence / Applied Invariants:** loss and both entries; `TR-I04`, `TR-I11`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-006 — Net PnL capability unavailable

- **Purpose / Use Case / Actors:** gross fallback 금지; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** analytics §13.1 #11; Metric V1; `FIXTURE_PLANNED .../missing-net-pnl-capability`.
- **Given / When / Then:** structurally in-scope outcome null / run / affected Metric `MISSING_CAPABILITY`, scalar null, population and missing count/evidence retained.
- **Aggregate State Changes / Created or Updated Artifacts:** `COMPLETED` result with unavailable status.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; `OUTCOME_UNAVAILABLE`/`MISSING_FEES`, not 0.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** capability snapshot and fee rows; `TR-I08`, `TR-I10`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-007 — Minimum sample not met

- **Purpose / Use Case / Actors:** sample 부족과 0 구분; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** analytics §13.3 #22/#23; V1 minimums; `FIXTURE_PLANNED .../insufficient-sample`.
- **Given / When / Then:** one group below minimum / run / `INSUFFICIENT_SAMPLE`, scalar null, raw group/population/eligible/excluded counts present.
- **Aggregate State Changes / Created or Updated Artifacts:** completed result.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; insufficient sample is not failure.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** all group members; `TR-I10`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-008 — Actual Metric value zero

- **Purpose / Use Case / Actors:** valid zero 보존; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** eligible loss with no qualifying entry or equal exposure; Metric V1; `FIXTURE_PLANNED .../available-zero`.
- **Given / When / Then:** required capability/minimum satisfied / run / `AVAILABLE`, rate/value exact 0, scalar non-null, eligible count non-zero.
- **Aggregate State Changes / Created or Updated Artifacts:** completed result.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; zero is not unavailable.
- **Idempotency Result:** deterministic zero/hash.
- **Evidence / Applied Invariants:** subject and excluded candidate audit; `TR-I10`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-009 — No comparison population

- **Purpose / Use Case / Actors:** comparison absent 의미 명시; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** no eligible loss or missing opposite holding group; Metric V1; `FIXTURE_PLANNED .../no-comparison-population`.
- **Given / When / Then:** population lacks required comparison / run / `INSUFFICIENT_SAMPLE`, scalar null, comparison IDs empty, counts/reasons explicit.
- **Aggregate State Changes / Created or Updated Artifacts:** completed result/evidence.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; no fake zero/group.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** population membership; `TR-I10`~`TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; completed query.

#### TR-ACC-ANALYSIS-010 — Completed result and evidence persisted

- **Purpose / Use Case / Actors:** complete publication gate; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** valid terminal payload; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** Run `RUNNING` / success callback / Core verifies hashes, quality, all Metric status/value, membership and evidence then atomically stores result and sets `COMPLETED`.
- **Aggregate State Changes / Created or Updated Artifacts:** immutable result/evidence and optional latest pointer.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** duplicate callback `NO_OP`.
- **Evidence / Applied Invariants:** result hash/pointer and full chain; `TR-I11`, `TR-I12`, `TR-I36`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core persists, Compute never writes Core tables; `SAFE`; terminal status query.

#### TR-ACC-ANALYSIS-011 — Completion callback without result

- **Purpose / Use Case / Actors:** empty success 금지; terminal callback; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** `RUNNING`, callback claims completed but lacks result or quality/evidence; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** incomplete payload / callback / `COMPLETED` transition rejected, no result/latest change; Run becomes `FAILED` with structured failure when attempt is terminal.
- **Aggregate State Changes / Created or Updated Artifacts:** failure only.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; `INCOMPLETE_ANALYSIS_RESULT`, retryable false for payload/true only via new corrected attempt, terminal true, old results unchanged, rerun after fix, code/version only, `ALERT`.
- **Idempotency Result:** repeated invalid callback `NO_OP` after failure; cannot fill terminal Run.
- **Evidence / Applied Invariants:** missing-component validation record; `TR-I10`~`TR-I12`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core rejects; `SAFE ALERT`; terminal failure query.

#### TR-ACC-ANALYSIS-012 — Duplicate logical analysis request

- **Purpose / Use Case / Actors:** Run/result 중복 방지; `RunTradingAnalysis`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** identical logical key active/completed; exact versions/config; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** request repeated / command / same active/completed Run returned; run/result/latest counts unchanged.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** key/input/result hash; `TR-I11`, `TR-I33`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; async accepted/existing returned.

#### TR-ACC-ANALYSIS-013 — Duplicate analysis terminal callback

- **Purpose / Use Case / Actors:** callback delivery 중복 안전; internal callback; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** Run already terminal with same payload; exact pins; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** identical callback / receive / no state/artifact/pointer change.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; conflicting result hash is `RESULT_HASH_MISMATCH`, publish 없음.
- **Idempotency Result:** exact duplicate `NO_OP`, conflict `REJECT_CONFLICT`.
- **Evidence / Applied Invariants:** callback/commit hash; `TR-I11`, `TR-I12`, `TR-I33`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core verifies; `SAFE ALERT` on mismatch; internal callback.

#### TR-ACC-ANALYSIS-014 — Compute retry then success

- **Purpose / Use Case / Actors:** transient failure가 빈 실패/중복 결과를 만들지 않음; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** first attempt retryable, budget/generation valid; exact pins; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** attempt 1 fails transiently / retry / one later callback completes; exactly one Run logical result, attempt history preserved, hash deterministic.
- **Aggregate State Changes / Created or Updated Artifacts:** `PENDING→RUNNING→COMPLETED`; attempt count increases, result once.
- **Expected Outcome / Expected Failure or Exclusion:** intermediate `FAILED_RETRYABLE`, final `SUCCEEDED`; transient code, true, false before exhaustion, old result unchanged, automatic retry, safe metadata, alert threshold only.
- **Idempotency Result:** `CREATE_NEW_ATTEMPT`, not new successful result.
- **Evidence / Applied Invariants:** attempt tokens and final hash; `TR-I11`, `TR-I12`, `TR-I16`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; status remains running until terminal.

#### TR-ACC-ANALYSIS-015 — Terminal analysis failure

- **Purpose / Use Case / Actors:** 실패를 완료/빈 결과로 표현하지 않음; analysis; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** non-retryable compatibility/calculation failure; pinned request; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** Run active / terminal failure / `FAILED`, structured failure, result absent, other completed runs/latest unchanged.
- **Aggregate State Changes / Created or Updated Artifacts:** failed Run only.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; exact safe code, false, true, old result unchanged, choose compatible input/new request, versions/code only, alert per code.
- **Idempotency Result:** terminal Run immutable; retry is `CREATE_NEW_ATTEMPT`/new Run.
- **Evidence / Applied Invariants:** failure/attempt/version; `TR-I10`~`TR-I12`, `TR-I27`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal failure status query.

### C.1 Analytics Job Protocol V1 (ADR-069)

아래의 모든 Scenario는 별도 표기가 없으면 동일한 accepted `LedgerRevision=LR-V1`, 두 immutable artifact의 identity/hash, `ANALYSIS_CONFIG_V1`, exact Analytics VersionSet(`findingRuleSetVersion=TRADING_FINDING_RULES_DISABLED_V1`), `TRADING_ANALYTICS_INPUT_HASH_V1`, result schema/hash V1, retry/delivery V1, current attempt A1, deletion generation G1을 pin한다. `R`은 Compute job `PENDING`/Core Run `RUNNING`, `C`는 Compute `COMPLETED`/Core Run `COMPLETED`, `F`는 Compute `FAILED`/Core Run `FAILED`, `S`는 Compute completed stale/Core Run 미발행을 뜻한다. Evidence 기본값은 request identity, 두 artifact byte digest, pinned Revision의 ReviewUnit→allocation→record→source evidence chain과 terminal descriptor/payload/ACK이다. 기본 invariant는 `TR-I10`~`TR-I12`, `TR-I16`, `TR-I27`, `TR-I36` 및 ADR-069이다. `FIXTURE_PLANNED`는 실행 검증 완료가 아니다.

| Stable ID / Given→When→Then | Pinned difference / request→terminal | Publication / retry | Evidence / invariant / safe failure / idempotency |
|---|---|---|---|
| `TR-ACC-ANALYSIS-016` create | base; new key / R→R | no / no | create fingerprint and A1/G1; base; —; CREATE_NEW |
| `TR-ACC-ANALYSIS-017` same create replay | same key/fingerprint, changed locator only / R→R | no / no | original grant revision unchanged; base; —; RETURN_EXISTING |
| `TR-ACC-ANALYSIS-018` conflicting replay | same key, changed config/hash / R→R | no / no | fingerprint conflict; base; `IDEMPOTENCY_CONFLICT`; REJECT_CONFLICT 409 |
| `TR-ACC-ANALYSIS-019` two artifact bytes | valid exact bytes and manifest / R→C | yes / no | both SHA/size, ledger/manifest independent hashes; `TR-I08`; —; one result |
| `TR-ACC-ANALYSIS-020` expected hash mismatch | wrong expectedInputHash / R→F | no / no | independent JCS root; base; `INPUT_HASH_MISMATCH`; no result |
| `TR-ACC-ANALYSIS-021` expired queued grant | A1 grant expired, same identities / R→R | no / replacement | grant revision 0→1, token A1; base; `INPUT_REFERENCE_EXPIRED`; update replay same receipt |
| `TR-ACC-ANALYSIS-022` explicit retry | retryable failed A1, fresh grants, A2/G1 / R→R→C | yes once / yes | attempt and lease fencing; `TR-I16`; transient code; new attempt only |
| `TR-ACC-ANALYSIS-023` old attempt result | A2 current, A1 completed later / R→S | no / no | A1/A2 mismatch despite equal resultHash; `TR-I16`; `STALE_ATTEMPT`; stale ACK |
| `TR-ACC-ANALYSIS-024` deletion in worker | G1 worker, G2 tombstone / R→S | no / no | deletion journal/generation; `TR-I14`~`TR-I24`; `DELETED_GENERATION`; stale ACK when payload exists |
| `TR-ACC-ANALYSIS-025` three Metrics | result vector: all three typed definitions / R→C | yes / no | counts=3 and metric hashes, provenance; `TR-I12`; —; one immutable result |
| `TR-ACC-ANALYSIS-026` missing capability | manifest lacks required capability / R→C | yes / no | per-Metric MISSING_CAPABILITY/exclusion; `TR-I11`; —; same result hash |
| `TR-ACC-ANALYSIS-027` insufficient sample | one exposure pair, minimum two / R→C | yes / no | exact pair and status; `TR-I11`; —; same result hash |
| `TR-ACC-ANALYSIS-028` actual zero | eligible win/loss equal median duration / R→C | yes / no | `0/1`, AVAILABLE and nonzero sample; `TR-I11`; —; same result hash |
| `TR-ACC-ANALYSIS-029` Finding disabled | rule version disabled, `findings=[]` / R→C | yes / no | findingCount=0 and section digest; `TR-I12`; —; same result hash |
| `TR-ACC-ANALYSIS-030` transport byte mismatch | descriptor byteSize or SHA wrong / R→F | no / no | exact spool bytes; base; `RESULT_TRANSPORT_HASH_MISMATCH`; no ACK |
| `TR-ACC-ANALYSIS-031` section mismatch | one Metric value changed without digest / R→F | no / no | metric and section hash recompute; base; `RESULT_SECTION_HASH_MISMATCH`; no ACK |
| `TR-ACC-ANALYSIS-032` invalid reference | membership record missing in pinned Revision / R→F | no / no | evidence chain resolution; `TR-I12`; `EVIDENCE_REFERENCE_INVALID`; no ACK |
| `TR-ACC-ANALYSIS-033` result hash mismatch | manifest hash changed / R→F | no / no | six section digests and JCS manifest; base; `RESULT_HASH_MISMATCH`; no ACK |
| `TR-ACC-ANALYSIS-034` persisted ACK | current A1/G1 and committed AnalysisResult / R→C | yes / ACK retry only | transaction/outbox; `TR-I16`; —; PERSISTED replay no-op |
| `TR-ACC-ANALYSIS-035` stale discard ACK | completed A1/G1, current A2 or G2 / R→S | no / no | stale reason/current identity; `TR-I16`; —; DISCARDED_STALE replay no-op |
| `TR-ACC-ANALYSIS-036` duplicate ACK | same identity/hash/disposition / C→C | once / ACK only | receipt/tombstone; base; —; NO_OP; changed tuple 409 |
| `TR-ACC-ANALYSIS-037` payload cap | effective limit smaller than full bytes / R→F | no / no | serialization count; base; `RESULT_PAYLOAD_TOO_LARGE`; no ACK |
| `TR-ACC-ANALYSIS-038` resource expiry | 24h passed, no ACK / C Compute→F Core | no / new Run only | expiry timestamp; lifecycle; `RESULT_PAYLOAD_EXPIRED`; no publication |
| `TR-ACC-ANALYSIS-039` worker restart | A1 lease expires, no committed failure / R→R→C | yes same attempt / reclaim | lease generation CAS, one payload; `TR-I16`; `LEASE_LOST` for old worker; one result |

### D. Evidence

#### TR-ACC-EVIDENCE-001 — Query subject episode from Metric

- **Purpose / Use Case / Actors:** subject drill-down; `GetTradingReview`, `GetReviewUnitEvidence`; owner, Web, Core.
- **Preconditions / Pinned Versions / Input Fixture:** completed result with subject membership; result-pinned versions; `FIXTURE_PLANNED .../all-metrics-available`.
- **Given / When / Then:** owner selects Metric subject / query / exact subject IDs, observation role/scalars and episode returned without mutation.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; none.
- **Idempotency Result:** repeated query `NO_OP`.
- **Evidence / Applied Invariants:** Metric→Observation→subject episode; `TR-I01`, `TR-I04`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core read model only; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-002 — Query comparison episode from Metric

- **Purpose / Use Case / Actors:** comparison 정의 검증; evidence queries; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** completed result with comparison members; pinned result; `FIXTURE_PLANNED .../all-metrics-available`.
- **Given / When / Then:** comparison selected / query / role (`SELECTED_EARLIEST`, baseline, WIN/LOSS group 등), membership and scalar inputs exactly returned.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** Metric→Observation→comparison episode; `TR-I01`, `TR-I04`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-003 — Episode to PositionAllocation

- **Purpose / Use Case / Actors:** derived unit 구성 근거; `GetReviewUnitEvidence`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** retained episode/manifest; reconstruction pin; `FIXTURE_PLANNED .../partial-entry-exit`.
- **Given / When / Then:** episode query / drill down / ordered allocations, action, quantities, asset-specific fee/PnL and reversal group returned; sums conserve.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** episode→allocations; `TR-I04`, `TR-I07`, `TR-I08`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core reads pinned manifest; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-004 — Allocation to canonical record

- **Purpose / Use Case / Actors:** quantity/cash-flow source 연결; evidence query; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** retained allocation/record; canonical schema pin; `FIXTURE_PLANNED .../partial-entry-exit`.
- **Given / When / Then:** allocation selected / query / exactly linked Execution/Fee/VenueReportedPnl records and allocated portions returned; cross-owner record impossible.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** allocation→record; `TR-I01`, `TR-I04`, `TR-I07`, `TR-I08`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-005 — Canonical record to source evidence

- **Purpose / Use Case / Actors:** canonical transform provenance; evidence query; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** retained record/snapshot; evidence schema/mask pin; `FIXTURE_PLANNED .../simple-long`.
- **Given / When / Then:** record selected / query / artifact role, 1-based row, opaque key/hash and raw-or-snapshot evidence returned; UID/full IDs hidden.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** record→artifact/source row; `TR-I01`, `TR-I04`, `TR-I17`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-006 — Evidence while raw CSV remains

- **Purpose / Use Case / Actors:** raw availability를 inline raw exposure와 구분; evidence query; owner, Core, Object Storage.
- **Preconditions / Pinned Versions / Input Fixture:** artifact `ACTIVE`, before TTL; data policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** owner queries evidence / read / snapshot+canonical shown with `rawAvailability=AVAILABLE`; raw line/bytes/full IDs are not inline and no public URL exists.
- **Aggregate State Changes / Created or Updated Artifacts:** none; optional signed access only for job, not owner inline view.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** retention state and snapshot; `TR-I17`, `TR-I18`, `TR-I21`, `TR-I23`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core/object; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-007 — Evidence after raw deletion

- **Purpose / Use Case / Actors:** retained snapshot honesty; evidence query; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** artifact `DELETED`, snapshot/ledger/result retained; mask V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** owner drills down / query / allowlist snapshot and canonical record returned with `rawAvailability=DELETED`, masking version and explicit limitation; never called raw row.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** snapshot hash/masked IDs; `TR-I17`, `TR-I18`, `TR-I23`, `TR-I28`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core only; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-008 — Evidence owned by another user

- **Purpose / Use Case / Actors:** existence hiding; evidence query; non-owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** valid evidence ID under another owner; auth/data policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** non-owner guesses ID / query / no evidence or existence distinction returned, no side effect.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_FOUND_OR_HIDDEN`; ownership failure is non-retryable unless caller changes, terminal request, no result effect, use own resource, caller/correlation only, security threshold alert.
- **Idempotency Result:** repeated query same hidden result.
- **Evidence / Applied Invariants:** authorization decision only; `TR-I01`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core auth before lookup payload; `SAFE`; resource hidden from non-owner.

#### TR-ACC-EVIDENCE-009 — Excluded episode evidence

- **Purpose / Use Case / Actors:** exclusion도 설명 가능; evidence query; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** accepted Revision with excluded unit; result pins; `FIXTURE_PLANNED .../accepted-with-exclusions`.
- **Given / When / Then:** owner selects exclusion / query / reconstruction/reconciliation status, all exclusion reasons, candidates/allocations/records/source evidence returned; no synthetic completion.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; excluded status preserved.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** quality→unit→source; `TR-I04`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource query.

#### TR-ACC-EVIDENCE-010 — Version metadata query

- **Purpose / Use Case / Actors:** replayability와 readability 구분; `GetTradingResultHistory`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** retained result; one implementation may be unavailable; stored VersionSet/config/lineage; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** owner queries history / read / source/target VersionSet, digests, config hash, lineage, availability and readable/replayable states returned; payload remains readable when implementation unavailable.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; replay request separately may fail `VERSION_UNAVAILABLE`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** retained metadata/result hash; `TR-I31`, `TR-I34`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core registry/read model; `SAFE`; resource query.

### B. Reconstruction and Reconciliation (continued)

#### TR-ACC-RECON-006 — Incomplete trades

- **Purpose / Use Case / Actors:** missing execution evidence 보존; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #13; `BASE-V1`; `FIXTURE_PLANNED .../incomplete-trades`.
- **Given / When / Then:** summary needs absent close / validate / `INCONSISTENT+INCOMPLETE_TRADES`, eligible false, reasons `MISSING_EXECUTION_SUSPECTED` and censor reason.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision, exclusion/quality.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** all available rows and missing constraint; `TR-I04`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; status query.

#### TR-ACC-RECON-007 — Position PnL mismatch

- **Purpose / Use Case / Actors:** oracle overwrite 금지; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #23; `BASE-V1`; `FIXTURE_PLANNED .../pnl-mismatch`.
- **Given / When / Then:** delta above tolerance / validate / episode `COMPLETE+SUMMARY_MISMATCH`, eligible false, `PNL_MISMATCH`, both values/delta/tolerance preserved.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision with excluded complete unit.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`.
- **Idempotency Result:** same manifest hash.
- **Evidence / Applied Invariants:** Trade PnL and Position PNL refs; `TR-I04`, `TR-I08`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; status query.

#### TR-ACC-RECON-008 — Same-exact-timestamp ambiguous position match

- **Purpose / Use Case / Actors:** nearest match heuristic 금지; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** exact opened/closed timestamp와 numeric evidence가 같은 candidate graph가 non-unique; `BASE-V1`; `FIXTURE_PLANNED .../same-exact-timestamp-ambiguous`.
- **Given / When / Then:** numeric Trade ID 순서로 같은 second 안에 닫힌 2 episode와 2×2 indistinguishable summary candidates / validate / affected units `AMBIGUOUS`, eligible false, candidate keys/deltas retained.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision, exclusions.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; reconciliation `AMBIGUOUS`, candidate-selection exclusion.
- **Idempotency Result:** deterministic candidate set/hash.
- **Evidence / Applied Invariants:** all candidate rows; `TR-I04`, `TR-I09`, `TR-I11`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; status query.

#### TR-ACC-RECON-009 — Difference within derived tolerance

- **Purpose / Use Case / Actors:** scale/terms-based tolerance acceptance; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #24; `BASE-V1`; `FIXTURE_PLANNED .../within-tolerance`.
- **Given / When / Then:** delta <= derived tolerance / validate / `COMPLETE+WITHIN_ROUNDING_TOLERANCE`, eligible true, warning/delta/tolerance recorded.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; warning is not exclusion.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** scale/terms and source refs; `TR-I06`, `TR-I08`, `TR-I11`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; status query.

#### TR-ACC-RECON-010 — Difference above derived tolerance

- **Purpose / Use Case / Actors:** excess delta exclusion; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #25; `BASE-V1`; `FIXTURE_PLANNED .../above-tolerance`.
- **Given / When / Then:** delta > tolerance / validate / `COMPLETE+SUMMARY_MISMATCH`, eligible false, `PNL_MISMATCH`.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision with exclusion.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** delta/tolerance input refs; `TR-I06`, `TR-I08`, `TR-I09`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; status query.

#### TR-ACC-RECON-011 — Fee in another asset without valuation

- **Purpose / Use Case / Actors:** import acceptance와 net capability 분리; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #20; `BASE-V1`; `FIXTURE_PLANNED .../foreign-fee-asset`.
- **Given / When / Then:** fee asset differs/no valuation / validate / `COMPLETE+EXACT`, import accepted, `FEES` kept, `CLOSED_OUTCOME` absent, net null, net-required analytics excluded/status missing capability.
- **Aggregate State Changes / Created or Updated Artifacts:** accepted Revision/manifest/quality.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; `MISSING_CAPABILITY`, not zero.
- **Idempotency Result:** deterministic.
- **Evidence / Applied Invariants:** fee asset/source amount and allocation refs; `TR-I04`, `TR-I08`, `TR-I10`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; accepted query.

#### TR-ACC-RECON-012 — Complete Trade episode after Position observed coverage

- **Purpose / Use Case / Actors:** artifact 기간 차이를 정상 reconciliation로 숨기지 않음; import; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** reconstruction O.2 #14; role별 coverage가 다름; `BASE-V1`; `FIXTURE_PLANNED .../episode-after-position-coverage`.
- **Given / When / Then:** Position latest observed timestamp 뒤 complete Trade episode와 matching summary 없음 / validate / episode `COMPLETE+SUMMARY_MISMATCH`, eligible false, `POSITION_SUMMARY_MISSING`; role별 coverage와 `NOT_PROVEN` completeness 보존.
- **Aggregate State Changes / Created or Updated Artifacts:** session accepted, Revision/manifest/quality에 episode와 coverage exclusion 생성.
- **Expected Outcome / Expected Failure or Exclusion:** `ACCEPTED_WITH_EXCLUSIONS`; 0건 또는 정상 reconciliation로 집계하지 않음.
- **Idempotency Result:** 같은 input/coverage는 같은 manifest와 exclusion count.
- **Evidence / Applied Invariants:** Trade allocations와 두 artifact coverage; `TR-I03`, `TR-I04`, `TR-I06`, `TR-I09`, `TR-I10`, `TR-I12`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE` count/reason only; accepted status query.

### E. Data Lifecycle

#### TR-ACC-DELETE-001 — Accepted raw artifact TTL expires

- **Purpose / Use Case / Actors:** accepted raw 7-day retention; policy trigger; Core, Object Storage.
- **Preconditions / Pinned Versions / Input Fixture:** accepted artifact `ACTIVE`; data policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** `acceptedAt+P7D` reached / retention worker / `RETENTION_SCHEDULED→DELETION_PENDING→DELETED`; raw/filename/DEK absent, snapshot/ledger/result remain.
- **Aggregate State Changes / Created or Updated Artifacts:** retention state only; Review immutable.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; reparse unavailable, Review available.
- **Idempotency Result:** repeated trigger `NO_OP`/unfinished purge only.
- **Evidence / Applied Invariants:** policy clock, object/key verification; `TR-I17`, `TR-I21`, `TR-I23`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core/object; `SAFE`; status query.

#### TR-ACC-DELETE-002 — Rejected raw artifact TTL expires

- **Purpose / Use Case / Actors:** rejected raw 24-hour retention; policy trigger; Core, Object Storage.
- **Preconditions / Pinned Versions / Input Fixture:** rejected artifact active; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** `rejectedAt+PT24H` / worker / raw deleted, safe failure metadata retained for its policy period.
- **Aggregate State Changes / Created or Updated Artifacts:** artifact→`DELETED`; session stays `REJECTED`.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP` after verified deletion.
- **Evidence / Applied Invariants:** due/actual times and checklist; `TR-I19`, `TR-I21`, `TR-I23`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core/object; `SAFE`; status query.

#### TR-ACC-DELETE-003 — Immediate raw-only deletion

- **Purpose / Use Case / Actors:** user raw deletion without Review purge; `DeleteTradingSourceArtifact`; owner, Core, storage.
- **Preconditions / Pinned Versions / Input Fixture:** active artifact/result; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** owner deletes raw / commit+worker / access/reparse blocked immediately, artifact `DELETED`, snapshot/records/Revision/result retained.
- **Aggregate State Changes / Created or Updated Artifacts:** DeletionRequest completes; generation/checklist audit.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** scope/checklist; `TR-I14`~`TR-I19`, `TR-I23`, `TR-I24`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core/storage; `SAFE`; async deletion status.

#### TR-ACC-DELETE-004 — Review after raw deletion

- **Purpose / Use Case / Actors:** raw와 result lifecycle 분리; `GetTradingReview`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** raw deleted, result/snapshot retained; mask V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** owner reads Review / query / metrics and snapshot evidence available, raw deleted/reparse unavailable limitation explicit.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** snapshot/version; `TR-I17`, `TR-I28`, `TR-I34`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource query.

#### TR-ACC-DELETE-005 — Adapter reparse after raw deletion

- **Purpose / Use Case / Actors:** snapshot-as-raw 금지; `RequestTradingReprocessing`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** `CANONICAL_ONLY`, artifact deleted; target adapter exact; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** `REPARSE_SOURCE` requested / validate / no Compute dispatch/new Revision; failed reprocessing status.
- **Aggregate State Changes / Created or Updated Artifacts:** failed ReprocessingRun only; old result unchanged.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; `REPROCESSING_SOURCE_UNAVAILABLE`, false, true, old unchanged, reupload, availability/version only, no alert.
- **Idempotency Result:** same failed logical request returned; retry only after new source lineage.
- **Evidence / Applied Invariants:** retention/input availability; `TR-I28`, `TR-I29`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core admission; `SAFE`; conflict/terminal query.

#### TR-ACC-DELETE-006 — Delete TradingBook

- **Purpose / Use Case / Actors:** Book subtree purge; `DeleteTradingBook`; owner, Core, Compute, storage.
- **Preconditions / Pinned Versions / Input Fixture:** active Book with data/jobs; deletion policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** delete committed / workflow / immediate hidden, jobs cancelled, all Book sessions/raw/snapshots/records/Revisions/runs/results purged; Account/other Books remain.
- **Aggregate State Changes / Created or Updated Artifacts:** deletion state sequence to `COMPLETED` only after live verification.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** scope counts/checklist/generation; `TR-I14`~`TR-I16`, `TR-I19`, `TR-I22`~`TR-I24`, `TR-I30`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common lifecycle; `SAFE`; async deletion status.

#### TR-ACC-DELETE-007 — Delete TradingAccount

- **Purpose / Use Case / Actors:** Account cascade; `DeleteTradingAccount`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** Account with multiple Books; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** delete / workflow / Account and every Book subtree purged, other Accounts/Member retained.
- **Aggregate State Changes / Created or Updated Artifacts:** one scope-equivalent request and child checklist.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** child counts; `TR-I14`~`TR-I16`, `TR-I19`, `TR-I22`~`TR-I24`, `TR-I30`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; async status.

#### TR-ACC-DELETE-008 — Delete Member

- **Purpose / Use Case / Actors:** Trading Review 전체 member cascade; `DeleteMember`; owner, user module, tradingreview module.
- **Preconditions / Pinned Versions / Input Fixture:** Member with Accounts; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** member deletion / orchestration / all Trading Review scopes purge before bounded-context completion; no Trading Review access remains.
- **Aggregate State Changes / Created or Updated Artifacts:** bounded-context deletion completion evidence.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED` or truthful `FAILED_RETRYABLE/TERMINAL`, never partial success.
- **Idempotency Result:** same orchestration request reused.
- **Evidence / Applied Invariants:** scope equivalence; `TR-I14`~`TR-I16`, `TR-I19`, `TR-I20`, `TR-I22`~`TR-I24`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core modules via Use Cases; `SAFE`; async status.

#### TR-ACC-DELETE-009 — Delete during normalization

- **Purpose / Use Case / Actors:** deletion precedence before ledger creation; delete/import; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** session `VALIDATING`; generation g; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** raw/scope deletion commits g+1 / cancel / output discarded, session lifecycle reason `SOURCE_ARTIFACT_DELETED` when retained, no Revision, runtime/raw purged.
- **Aggregate State Changes / Created or Updated Artifacts:** DeletionRequest proceeds; import cannot accept.
- **Expected Outcome / Expected Failure or Exclusion:** deletion `SUCCEEDED`; raw-only 삭제에서 남는 import는 `PROCESSING_FAILED`, Session scope 삭제면 정상 조회 차단.
- **Idempotency Result:** callback `NO_OP` stale; delete `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** old/current generation; `TR-I15`, `TR-I16`, `TR-I19`, `TR-I30`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; deletion status.

#### TR-ACC-DELETE-010 — Delete during analysis

- **Purpose / Use Case / Actors:** late analysis publication 차단; delete/analysis; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** AnalysisRun `RUNNING`, generation g; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** Book/Account delete commits / callback arrives / generation check rejects result, scope/result purged, latest not changed.
- **Aggregate State Changes / Created or Updated Artifacts:** deletion workflow only; AnalysisRun removed with scope.
- **Expected Outcome / Expected Failure or Exclusion:** deletion `SUCCEEDED`; callback `STALE_RESULT_AFTER_DELETION` discard.
- **Idempotency Result:** callback `NO_OP`, no retry.
- **Evidence / Applied Invariants:** generation audit; `TR-I15`, `TR-I16`, `TR-I19`, `TR-I30`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`, rate alert; deletion status.

#### TR-ACC-DELETE-011 — Duplicate deletion request

- **Purpose / Use Case / Actors:** duplicate scope/purge 방지; delete command; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** same key request active/terminal; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** command repeated / process / same request/status returned; completed checklist not rerun, generation increments once.
- **Aggregate State Changes / Created or Updated Artifacts:** none beyond unfinished attempt.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`; incomplete items only `CREATE_NEW_ATTEMPT`.
- **Evidence / Applied Invariants:** request/key/checklist; `TR-I14`, `TR-I19`, `TR-I24`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; async status.

#### TR-ACC-DELETE-012 — Object storage deletion failure

- **Purpose / Use Case / Actors:** partial deletion honesty; deletion worker; Core, storage.
- **Preconditions / Pinned Versions / Input Fixture:** primary purge success, object or DEK remains; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** retry budget exhausted / verify / request `FAILED`, artifact `DELETION_FAILED`, access/tombstone retained, no completed label.
- **Aggregate State Changes / Created or Updated Artifacts:** partial checklist/failure audit.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_RETRYABLE`; `ARTIFACT_DELETION_FAILED`/`DELETION_PARTIALLY_COMPLETED`, true via remediation, terminal original true, no old access, support/remediation, component/code only, `ALERT`.
- **Idempotency Result:** new remediation request, completed items not recreated.
- **Evidence / Applied Invariants:** object/key verification; `TR-I19`, `TR-I23`, `TR-I24`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core/storage; `SAFE ALERT`; terminal failure status.

#### TR-ACC-DELETE-013 — Late Compute callback after deletion

- **Purpose / Use Case / Actors:** stale result non-resurrection; internal callback; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** tombstone generation > callback generation; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** terminal callback / receive / acknowledged/discarded, no records/results/latest, callback retry stopped.
- **Aggregate State Changes / Created or Updated Artifacts:** payload-free audit only.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; `STALE_RESULT_AFTER_DELETION`, false, terminal delivery, none, no user action, run/generation only, rate alert.
- **Idempotency Result:** repeated stale callback `NO_OP`.
- **Evidence / Applied Invariants:** generation/tombstone; `TR-I16`, `TR-I18`, `TR-I20`, `TR-I30`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; internal callback.

#### TR-ACC-DELETE-014 — Backup restore replays deletion tombstone

- **Purpose / Use Case / Actors:** deleted data restore 금지; disaster restore; restore service, Core operator.
- **Preconditions / Pinned Versions / Input Fixture:** backup contains later-deleted scope, deletion ledger retained; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** restore / quarantine→ledger replay→purge→verify / traffic opens only after tombstoned scope count 0; deleted scope remains inaccessible.
- **Aggregate State Changes / Created or Updated Artifacts:** restore verification report, no resurrected Aggregate.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; verification failure keeps service quarantined/`FAILED_TERMINAL`.
- **Idempotency Result:** replay is idempotent.
- **Evidence / Applied Invariants:** deletion ledger/report; `TR-I14`, `TR-I20`, `TR-I23`, `TR-I24`.
- **Service Ownership / Observability / Deferred HTTP Contract:** restore control plane/Core; `SAFE ALERT` on failure; no public resource until verified.

### F. Reprocessing

#### TR-ACC-REPROCESS-001 — Adapter reparse from active raw

- **Purpose / Use Case / Actors:** parser upgrade의 immutable 재해석; `RequestTradingReprocessing`; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** `RAW_AND_CANONICAL`, raw active before TTL, adapter v1→v2 exact target; `FIXTURE_PLANNED .../adapter-reparse`.
- **Given / When / Then:** request / validate+run / new Revision (and requested Run) published only after full validation; old artifacts/results retained.
- **Aggregate State Changes / Created or Updated Artifacts:** ReprocessingRun completes, lineage/diff/new hashes; optional latest atomic update.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED` or explicit exclusions in new quality report.
- **Idempotency Result:** same key `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** import/parent/target DAG; `TR-I25`~`TR-I31`, `TR-I33`, `TR-I36`~`TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async accepted.

#### TR-ACC-REPROCESS-002 — Adapter reparse without raw

- **Purpose / Use Case / Actors:** input availability honesty; reprocessing; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** `CANONICAL_ONLY`; adapter target; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** request / validation / `FAILED`, no new Revision/Run/latest change.
- **Aggregate State Changes / Created or Updated Artifacts:** failed ReprocessingRun only.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; `REPROCESSING_SOURCE_UNAVAILABLE`, false, true, old preserved, reupload, availability/version only, no alert.
- **Idempotency Result:** failed key returned; new verified upload is new lineage/request.
- **Evidence / Applied Invariants:** input availability; `TR-I27`, `TR-I28`, `TR-I34`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; terminal status.

#### TR-ACC-REPROCESS-003 — Reconstruction upgrade from canonical records

- **Purpose / Use Case / Actors:** reconstruction version 새 ledger; reprocessing; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** `CANONICAL_ONLY`, compatible schema/terms, recon v1→v2; `FIXTURE_PLANNED .../reconstruction-upgrade`.
- **Given / When / Then:** `RERUN_RECONSTRUCTION` / run / new manifest+Revision, optional new AnalysisRun, old immutable results retained.
- **Aggregate State Changes / Created or Updated Artifacts:** completed ReprocessingRun/lineage/diff.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** parent Revision→target; `TR-I25`~`TR-I27`, `TR-I29`, `TR-I31`, `TR-I33`, `TR-I36`, `TR-I37`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async accepted.

#### TR-ACC-REPROCESS-004 — Metric v1 to v2 rerun

- **Purpose / Use Case / Actors:** Metric 의미 변경 이력; `RERUN_ANALYTICS`; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** retained compatible Revision/result v1, target v2 active; `FIXTURE_PLANNED .../metric-v2`.
- **Given / When / Then:** rerun / complete / new AnalysisRun/result v2 and difference summary; Revision/v1 result unchanged.
- **Aggregate State Changes / Created or Updated Artifacts:** completed reprocessing + new Run; optional latest update after completion.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** both versions/memberships; `TR-I25`~`TR-I27`, `TR-I31`~`TR-I34`, `TR-I36`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async.

#### TR-ACC-REPROCESS-005 — AnalysisConfig change

- **Purpose / Use Case / Actors:** config-specific immutable result; `RERUN_ANALYTICS`; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** same Revision/definitions, config h1→h2; `FIXTURE_PLANNED .../config-change`.
- **Given / When / Then:** request / complete / new Run with h2; h1 retained; trend keys differ unless only period varies under comparable rules.
- **Aggregate State Changes / Created or Updated Artifacts:** new result/lineage/diff.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** h2 key distinct; repeats return existing.
- **Evidence / Applied Invariants:** config payload/hash; `TR-I25`~`TR-I27`, `TR-I31`~`TR-I33`, `TR-I36`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async.

#### TR-ACC-REPROCESS-006 — Finding rule change

- **Purpose / Use Case / Actors:** finding-only immutable reevaluation; `REEVALUATE_FINDINGS`; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** exact compatible Metric/evidence/config, finding v1→v2; `FIXTURE_PLANNED .../finding-v2`.
- **Given / When / Then:** request / validate+run / new AnalysisRun/result, Metric payload reuse only if exact compatible, v1 retained.
- **Aggregate State Changes / Created or Updated Artifacts:** new Run/lineage/diff.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; incompatibility is `VERSION_INCOMPATIBLE`, no implicit analytics upgrade.
- **Idempotency Result:** `RETURN_EXISTING`.
- **Evidence / Applied Invariants:** parent run/versions; `TR-I25`~`TR-I27`, `TR-I29`, `TR-I31`, `TR-I36`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async.

#### TR-ACC-REPROCESS-007 — Duplicate reprocessing request

- **Purpose / Use Case / Actors:** duplicate target 방지; reprocessing command; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** identical canonical key active/completed; exact source/target; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** command repeated / submit / same Run/result; target Revision/Run/latest counts unchanged.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `RETURN_EXISTING`; mismatched payload `REJECT_CONFLICT`.
- **Evidence / Applied Invariants:** idempotency key; `TR-I26`, `TR-I33`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; async/existing.

#### TR-ACC-REPROCESS-008 — Target version unavailable

- **Purpose / Use Case / Actors:** nearest version fallback 금지; reprocessing; owner, Core, Compute registry.
- **Preconditions / Pinned Versions / Input Fixture:** target component `UNAVAILABLE`; exact target; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** request / validation / failed, no target artifact/latest change; old result remains readable.
- **Aggregate State Changes / Created or Updated Artifacts:** failed Run metadata.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; `VERSION_UNAVAILABLE`, false until target changes, true, old preserved, choose active target, version only, alert if registry inconsistency.
- **Idempotency Result:** failed request returned; changed target new key.
- **Evidence / Applied Invariants:** registry snapshot; `TR-I27`, `TR-I29`, `TR-I34`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core registry; `SAFE`; terminal status.

#### TR-ACC-REPROCESS-009 — New run fails; existing result remains

- **Purpose / Use Case / Actors:** failure isolation; any reprocessing; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** old completed current, target execution fails; exact pins; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** target run fails / publication attempted / ReprocessingRun `FAILED`, staging not visible, old Review/latest byte-identical.
- **Aggregate State Changes / Created or Updated Artifacts:** failed attempt/lineage audit only.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL` or retryable by code; old result unaffected.
- **Idempotency Result:** retry `CREATE_NEW_ATTEMPT` with `retryOf`.
- **Evidence / Applied Invariants:** pointer before/after; `TR-I25`~`TR-I27`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; terminal status.

#### TR-ACC-REPROCESS-010 — Completion changes latest atomically

- **Purpose / Use Case / Actors:** completed-only publication; reprocessing completion; Core.
- **Preconditions / Pinned Versions / Input Fixture:** staging Revision+Run fully valid, pointer update requested; exact pins; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** final callback / publish transaction / result/hash/lineage/generation verified, Revision+Run visible as pair, latest changes once.
- **Aggregate State Changes / Created or Updated Artifacts:** `RUNNING→COMPLETED`; target artifacts published.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; partial pointer update forbidden.
- **Idempotency Result:** duplicate publication `NO_OP`.
- **Evidence / Applied Invariants:** commit/hash/pointer; `TR-I31`, `TR-I33`, `TR-I36`~`TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core publication; `SAFE`; terminal status.

#### TR-ACC-REPROCESS-011 — Deletion during reprocessing

- **Purpose / Use Case / Actors:** deletion priority; delete/reprocess; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** ReprocessingRun non-terminal at generation g; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** deletion commits g+1 / cancel / reprocessing `CANCELLED` when retained until purge, output discarded, no new/latest artifact; lifecycle purge proceeds.
- **Aggregate State Changes / Created or Updated Artifacts:** deletion request; reprocessing cancellation audit.
- **Expected Outcome / Expected Failure or Exclusion:** reprocess `CANCELLED`; `REPROCESSING_CANCELLED`/late `STALE_RESULT_AFTER_DELETION`.
- **Idempotency Result:** late callback `NO_OP`; no retry admission.
- **Evidence / Applied Invariants:** generation/cancel; `TR-I15`, `TR-I16`, `TR-I27`, `TR-I30`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; deletion status only after purge.

#### TR-ACC-REPROCESS-012 — Trend across different Metric versions

- **Purpose / Use Case / Actors:** non-comparable series 금지; `GetComparableTradingTrend`; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** completed points metric v1/v2; retained metadata; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** trend requested / compare keys / no combined series; each result remains queryable with mismatch dimensions.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `NOT_COMPARABLE_VERSION`, false, query terminal, no result effect, rerun all periods, version/config hash only, no alert.
- **Idempotency Result:** query `NO_OP`.
- **Evidence / Applied Invariants:** both comparison keys; `TR-I32`, `TR-I34`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; conflict/query result.

#### TR-ACC-REPROCESS-013 — Recompute all periods into a comparable series

- **Purpose / Use Case / Actors:** latest definition comparable trend; multiple `RERUN_ANALYTICS` then trend query; owner, Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** all period Revisions retained/compatible, exact same target key except period; `FIXTURE_PLANNED .../comparable-series-v2`.
- **Given / When / Then:** every period rerun completes / publish series / all points have equal TrendComparisonKey and completed result; any failed period prevents complete-series publication.
- **Aggregate State Changes / Created or Updated Artifacts:** per-period new Runs/results/lineage; old series retained.
- **Expected Outcome / Expected Failure or Exclusion:** all complete `SUCCEEDED`; partial batch `FAILED_TERMINAL` as complete series while individual results remain valid.
- **Idempotency Result:** per-period keys return existing.
- **Evidence / Applied Invariants:** each result/key/period; `TR-I26`, `TR-I27`, `TR-I31`~`TR-I33`, `TR-I36`, `TR-I38`.
- **Service Ownership / Observability / Deferred HTTP Contract:** common; `SAFE`; async runs then query.

### G. Authorization and Security

#### TR-ACC-AUTH-001 — Owner reads own Review

- **Purpose / Use Case / Actors:** 정상 owner authorization; Review/evidence queries; owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** authenticated owner and retained resource; auth policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** owner queries / authorize / exact resource returned with masking/lifecycle limits.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`.
- **Idempotency Result:** `NO_OP`.
- **Evidence / Applied Invariants:** owner chain; `TR-I01`, `TR-I17`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource query.

#### TR-ACC-AUTH-002 — Another user queries TradingBook

- **Purpose / Use Case / Actors:** Book existence hiding; query; non-owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** guessed existing ID; auth policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** query / ownership check / same external result as absent ID, no metadata/count/timing distinction promised.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_FOUND_OR_HIDDEN`; non-retryable, no effect, use owned ID, caller/correlation only, abuse threshold alert.
- **Idempotency Result:** same hidden result.
- **Evidence / Applied Invariants:** auth decision; `TR-I01`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; resource hidden.

#### TR-ACC-AUTH-003 — Another user guesses artifact ID

- **Purpose / Use Case / Actors:** raw metadata/access isolation; artifact query/access; non-owner, Core/storage.
- **Preconditions / Pinned Versions / Input Fixture:** existing foreign artifact; auth policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** guessed ID / request / no metadata, signed URL, object access or existence signal.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_FOUND_OR_HIDDEN`; safe auth failure, abuse alert threshold.
- **Idempotency Result:** hidden result.
- **Evidence / Applied Invariants:** authorization audit only; `TR-I01`, `TR-I14`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core before storage; `SAFE`; hidden.

#### TR-ACC-AUTH-004 — Another user queries evidence

- **Purpose / Use Case / Actors:** evidence chain owner isolation; evidence query; non-owner, Core.
- **Preconditions / Pinned Versions / Input Fixture:** foreign evidence ID; auth policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** query / authorize / no membership/source values/existence exposed.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_FOUND_OR_HIDDEN`.
- **Idempotency Result:** hidden result.
- **Evidence / Applied Invariants:** auth decision only; `TR-I01`, `TR-I04`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; hidden.

#### TR-ACC-AUTH-005 — Unauthenticated upload

- **Purpose / Use Case / Actors:** auth before artifact intake; upload; anonymous, Core.
- **Preconditions / Pinned Versions / Input Fixture:** no valid session; auth policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** bytes submitted / auth / no session/artifact/temp/object created.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_AUTHORIZED`; retry after auth, request terminal, no effect, authenticate, correlation/code only, abuse alert.
- **Idempotency Result:** no key/resource allocated.
- **Evidence / Applied Invariants:** auth audit without payload; `TR-I01`, `TR-I18`, `TR-I21`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; authorization rejection.

#### TR-ACC-AUTH-006 — Unauthenticated Review query

- **Purpose / Use Case / Actors:** result confidentiality; Review query; anonymous, Core.
- **Preconditions / Pinned Versions / Input Fixture:** no valid session; auth policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** resource ID / query / no lookup payload/result/existence disclosure.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_AUTHORIZED`.
- **Idempotency Result:** none.
- **Evidence / Applied Invariants:** auth audit; `TR-I01`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; authorization rejection.

#### TR-ACC-AUTH-007 — CSRF failure on state-changing command

- **Purpose / Use Case / Actors:** cookie session CSRF protection; any Trading Review command; authenticated browser without valid CSRF, Core.
- **Preconditions / Pinned Versions / Input Fixture:** valid cookie, invalid/missing CSRF/origin; ADR-019 policy; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** command / security filter / domain Use Case not invoked, no state/object/dispatch created.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_AUTHORIZED`; retry with valid token/origin, terminal request, no result effect, refresh page/session, safe security metadata, abuse alert.
- **Idempotency Result:** logical domain key not consumed.
- **Evidence / Applied Invariants:** security audit without payload; `TR-I01`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core security boundary; `SAFE`; authorization rejection.

#### TR-ACC-AUTH-008 — Direct raw object URL access

- **Purpose / Use Case / Actors:** private/job-bound raw access; object read; owner/non-owner browser, storage/Core.
- **Preconditions / Pinned Versions / Input Fixture:** guessed path or expired/non-job-bound grant; data policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** direct request / verify / denied; no public object/redirect; URL/path not logged.
- **Aggregate State Changes / Created or Updated Artifacts:** none.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_AUTHORIZED`; valid Compute job may obtain max-5m single-object read grant only.
- **Idempotency Result:** repeated denial.
- **Evidence / Applied Invariants:** grant/job/expiry decision; `TR-I01`, `TR-I15`, `TR-I18`, `TR-I23`.
- **Service Ownership / Observability / Deferred HTTP Contract:** storage via Core grant; `SAFE`; authorization rejection.

#### TR-ACC-AUTH-009 — Operator ordinary access

- **Purpose / Use Case / Actors:** operator default deny; evidence/raw query; operator without grant, Core.
- **Preconditions / Pinned Versions / Input Fixture:** staff identity, no approved break-glass; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** query / authorize / financial payload denied; owner behavior unchanged.
- **Aggregate State Changes / Created or Updated Artifacts:** safe denial audit only.
- **Expected Outcome / Expected Failure or Exclusion:** `NOT_AUTHORIZED`; requires approved break-glass, no result effect, request ticket, actor/scope/action only, security alert threshold.
- **Idempotency Result:** repeated denial.
- **Evidence / Applied Invariants:** operator auth audit; `TR-I01`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core; `SAFE`; authorization rejection.

#### TR-ACC-AUTH-010 — Audited break-glass access

- **Purpose / Use Case / Actors:** 최소·시한 operator access; evidence query; approved operator, Core.
- **Preconditions / Pinned Versions / Input Fixture:** MFA, ticket, approval, minimal scope, unexpired ≤30m grant; policy V1; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** operator reads approved scope / authorize / only scoped view permitted, every action audited, grant expiry blocks later access; audit excludes financial payload.
- **Aggregate State Changes / Created or Updated Artifacts:** access audit/grant expiry only; domain data unchanged.
- **Expected Outcome / Expected Failure or Exclusion:** `SUCCEEDED`; out-of-scope/expired request `NOT_AUTHORIZED`.
- **Idempotency Result:** reads `NO_OP`; renewal requires new approval.
- **Evidence / Applied Invariants:** actor/ticket/approver/scope/start/end/action; `TR-I01`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core operator boundary; `SAFE`; privileged resource query.

---

### H. Normalization Job Grant and Attempt Protocol

이 그룹은 CSV 계산 fixture 없이 합성 grant/job 상태로 검증한다. `attemptToken`은 Core가
발급한 attempt identity, `grantRevision`은 Compute job 안에서 단조 증가하는 grant CAS 값이다.
`PENDING/RUNNING` grant 대기는 terminal 성공으로 판정하지 않는다.

#### TR-ACC-NORMJOB-001 — Lost creation response replay

- **Purpose / Use Case / Actors:** 접수 응답 유실 뒤 중복 job 방지; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** 최초 생성은 commit됐고 응답 유실, 최초 grant는 이후 만료; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** 같은 생성 key와 최초 token/계산 입력으로 POST replay / 최초 `202/jobId` 반환; job/idempotency count 1, grant revision/token 불변. 다른 token 또는 계산 입력의 같은 key는 `409`.
- **Aggregate State Changes / Created or Updated Artifacts:** 신규 Job/Revision 0.
- **Expected Outcome / Expected Failure or Exclusion:** 기존 접수 `SUCCEEDED`; key 충돌은 `REJECTED`, safe `IDEMPOTENCY_PAYLOAD_CONFLICT`.
- **Idempotency Result:** exact replay `RETURN_EXISTING`; grant URI/expiry 차이도 암묵 교체 없음.
- **Evidence / Applied Invariants:** 생성 fingerprint/최초 receipt; `TR-I05`, `TR-I06`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute runtime; `SAFE`; create/status.

#### TR-ACC-NORMJOB-002 — Five-minute queued grant expiry

- **Purpose / Use Case / Actors:** worker claim 전 만료의 명시적 교체; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** `PENDING`, live lease 없음, grant 만료, raw active; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** status poll / `REPLACE_QUEUED_GRANTS`, 현재 token/revision/deadline 표시; Core가 같은 token과 두 fresh grant를 CAS update / revision +1, job ID·status `PENDING` 유지, 이전 grant 폐쇄.
- **Aggregate State Changes / Created or Updated Artifacts:** Compute grant revision 1건; Core Import 상태 불변.
- **Expected Outcome / Expected Failure or Exclusion:** 갱신 `SUCCEEDED`; deadline 초과 시 `FAILED/GRANT_REFRESH_DEADLINE_EXCEEDED`, safe failure만.
- **Idempotency Result:** 생성 replay는 교체하지 않음; update는 독립 key.
- **Evidence / Applied Invariants:** DB 시각 expiry, grant revision, role별 lease; `TR-I06`, `TR-I15`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core 발급·Compute CAS; `SAFE`; status/grant update.

#### TR-ACC-NORMJOB-003 — One artifact consumed before attempt failure

- **Purpose / Use Case / Actors:** single-read 재사용 차단; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** worker가 한 role을 소비하고 다른 role에서 retryable 실패; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** attempt 실패 / 두 이전 ArtifactLease 폐쇄, `RUNNING/START_RETRY_ATTEMPT`와 deadline 노출; Core가 새 token·두 새 grant를 CAS 등록 / 이전 grant read 재시도 0건.
- **Aggregate State Changes / Created or Updated Artifacts:** 같은 job의 새 attempt/revision; ledger publication 0.
- **Expected Outcome / Expected Failure or Exclusion:** 실패 attempt `FAILED_RETRYABLE`; budget 내 재시도 가능, raw 값 노출 없음.
- **Idempotency Result:** 새 grant update key; 생성 key 불변.
- **Evidence / Applied Invariants:** role별 consume/close audit, token lineage; `TR-I06`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute attempt/lease, Core grant; `SAFE`; status/grant update.

#### TR-ACC-NORMJOB-004 — Worker crash and expired lease

- **Purpose / Use Case / Actors:** 불명확한 소비 상태에서 안전한 재시도; Compute, Core.
- **Preconditions / Pinned Versions / Input Fixture:** live worker가 응답 없이 crash; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** lease 만료 / 이전 attempt·grant 폐쇄, status는 `RUNNING/START_RETRY_ATTEMPT`; old worker terminal write 거절, 새 token·grant 전 자동 재claim 없음.
- **Aggregate State Changes / Created or Updated Artifacts:** 같은 job의 새 attempt만 가능; terminal payload 0.
- **Expected Outcome / Expected Failure or Exclusion:** 이전 attempt `FAILED_RETRYABLE`; stale write `REJECTED`/safe conflict.
- **Idempotency Result:** 새 token update만 적용; stale claim은 no-op.
- **Evidence / Applied Invariants:** lease owner/expiry/token/revision; `TR-I06`, `TR-I16`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute runtime; `SAFE ALERT`; status/grant update.

#### TR-ACC-NORMJOB-005 — Duplicate grant update

- **Purpose / Use Case / Actors:** update 응답 유실 뒤 이중 revision 방지; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** 첫 update commit 뒤 응답 유실; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** 같은 update key/body 재전송 / 최초 receipt 재반환, revision·grant·attempt count 불변; 같은 key와 다른 URI/expiry/token은 `409`.
- **Aggregate State Changes / Created or Updated Artifacts:** 추가 row/lease 0.
- **Expected Outcome / Expected Failure or Exclusion:** exact replay `SUCCEEDED`; 다른 body `REJECTED/IDEMPOTENCY_PAYLOAD_CONFLICT`.
- **Idempotency Result:** `RETURN_EXISTING` 또는 `REJECT_CONFLICT`.
- **Evidence / Applied Invariants:** update fingerprint/receipt; `TR-I05`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute; `SAFE`; grant update.

#### TR-ACC-NORMJOB-006 — Delayed old grant update

- **Purpose / Use Case / Actors:** 이전 요청의 늦은 도착이 최신 grant를 되돌리지 않음; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** revision 2/token B 확정 후 revision 0/token A 조건 요청 도착; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** old CAS update / `409 GRANT_UPDATE_CONFLICT`; revision 2/token B 및 두 grant 불변, old grant read 불가.
- **Aggregate State Changes / Created or Updated Artifacts:** 없음.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `GRANT_UPDATE_CONFLICT`, false, false, result 없음, status 재조회, opaque ID/code만, 반복 시 alert.
- **Idempotency Result:** 다른 old key는 conflict; 이미 성공한 같은 key replay는 과거 receipt만 반환.
- **Evidence / Applied Invariants:** revision/token CAS; `TR-I06`, `TR-I16`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute; `SAFE ALERT`; grant update/status.

#### TR-ACC-NORMJOB-007 — Live worker lease rejects update

- **Purpose / Use Case / Actors:** 실행 중 grant/token 변경 차단; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** `RUNNING`과 유효 lease; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** grant update / `409 JOB_STATE_CONFLICT`, 현재 lease/attempt/grant 불변; worker의 원래 token 결과만 terminal CAS 대상.
- **Aggregate State Changes / Created or Updated Artifacts:** 없음.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `JOB_STATE_CONFLICT`, false, false, result 없음, status 재조회, safe code만, 반복 시 alert.
- **Idempotency Result:** 거절은 성공 receipt를 만들지 않음.
- **Evidence / Applied Invariants:** lease owner/expiry; `TR-I06`, `TR-I16`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute; `SAFE`; grant update/status.

#### TR-ACC-NORMJOB-008 — Cancellation and deletion generation race

- **Purpose / Use Case / Actors:** 삭제가 갱신/publication보다 우선; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** token A/generation 3의 active job, Core tombstone generation 4; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** Core tombstone commit 뒤 cancel/update가 경합 / cancel이 먼저면 update `409`; 이전 generation update가 먼저면 임시 갱신은 가능하지만 Core가 추가 grant 발급을 중단하고 현재 generation 검사로 old output 장기 저장 0. stale completed payload는 `DISCARDED_STALE` ack, 삭제 scope 접근 차단.
- **Aggregate State Changes / Created or Updated Artifacts:** deletion checklist만 진행; Revision/latest 불변.
- **Expected Outcome / Expected Failure or Exclusion:** `CANCELLED` 또는 terminal stale discard; `STALE_RESULT_AFTER_DELETION`, false, true, 기존 결과 불변, 삭제 상태 조회, opaque ID/code만, 지연 시 alert.
- **Idempotency Result:** cancel/discard ack exact duplicate no-op; old update 재적용 없음.
- **Evidence / Applied Invariants:** tombstone, payload/현재 generation, final token; `TR-I15`, `TR-I16`, `TR-I30`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core deletion 우선, Compute cancel/ack; `SAFE ALERT`.

#### TR-ACC-NORMJOB-009 — Terminal job rejects grant update

- **Purpose / Use Case / Actors:** terminal 불변식; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** 각 `COMPLETED`, `FAILED`, `CANCELLED` job; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** fresh key/revision의 grant update / 모두 `409 JOB_STATE_CONFLICT`; status/result/terminal hash 불변.
- **Aggregate State Changes / Created or Updated Artifacts:** 없음.
- **Expected Outcome / Expected Failure or Exclusion:** `REJECTED`; `JOB_STATE_CONFLICT`, false, true, result 불변, terminal status 확인, safe code만, 불필요.
- **Idempotency Result:** terminal resurrection 없음.
- **Evidence / Applied Invariants:** terminal CAS, immutable payload; `TR-I06`, `TR-I36`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute; `SAFE`; grant update.

#### TR-ACC-NORMJOB-010 — Duplicate terminal publication and acknowledgement

- **Purpose / Use Case / Actors:** 결과 중복·stale ack 차단; Compute, Core.
- **Preconditions / Pinned Versions / Input Fixture:** token B/generation 3의 completed payload 1건; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** 같은 hash 재publication/ack / payload·Core Revision 1건, exact ack no-op; 다른 hash 또는 token A/generation 2 ack / `409`, 원 결과 불변.
- **Aggregate State Changes / Created or Updated Artifacts:** 정상 ack marker만 최초 1회.
- **Expected Outcome / Expected Failure or Exclusion:** exact duplicate `SUCCEEDED`; 다른 result hash는 `RESULT_HASH_MISMATCH`로 publication 금지·Session `PROCESSING_FAILED`, stale token/generation ACK는 `409` conflict이며 Session 상태를 `REJECTED`로 바꾸지 않음.
- **Idempotency Result:** terminal/ack exact duplicate `NO_OP`; conflict 거절.
- **Evidence / Applied Invariants:** descriptor/payload final token/generation, input/result/payload hash; `TR-I05`, `TR-I06`, `TR-I16`, `TR-I33`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core publication, Compute immutable terminal; `SAFE`; result/download/ack.

#### TR-ACC-NORMJOB-011 — Grant wait deadline and retry budget

- **Purpose / Use Case / Actors:** 무기한 active 대기 방지; Compute, Core.
- **Preconditions / Pinned Versions / Input Fixture:** `grantAction` 필요, 고정 deadline 또는 bounded attempt budget 도달; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** DB deadline 경과/예산 소진 / safe `FAILED`, capacity 해제, 별도 failure payload 없음; 중복 polling/update가 deadline을 연장하거나 terminal을 재개하지 않음.
- **Aggregate State Changes / Created or Updated Artifacts:** terminal status/failure 1건, Core Revision 0.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_TERMINAL`; `GRANT_REFRESH_DEADLINE_EXCEEDED`, false, true, 기존 결과 불변, raw 재확인 후 새 dispatch 판단, code/stage만, alert.
- **Idempotency Result:** 같은 생성 key는 terminal job replay; 새 job만 새 key.
- **Evidence / Applied Invariants:** DB deadline/attempt budget, safe failure; `TR-I06`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute; `SAFE ALERT`; status.

#### TR-ACC-NORMJOB-012 — Separate Trading Review capacity

- **Purpose / Use Case / Actors:** grant 대기 capacity와 Backtest 경계; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** Trading Review pool full, Backtest pool 독립; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** 신규 normalization create / `503 TRADING_REVIEW_CAPACITY_EXCEEDED`와 초 단위 `Retry-After`, job/key row 0; 기존 job replay/grant update는 신규 예약 0; grant 대기 PENDING/RUNNING은 active로 계산.
- **Aggregate State Changes / Created or Updated Artifacts:** 거절된 job 0, Backtest reservation 불변.
- **Expected Outcome / Expected Failure or Exclusion:** `FAILED_RETRYABLE`; capacity, true, false, result 없음, 같은 key로 Retry-After 후 재시도, code/count만, alert.
- **Idempotency Result:** 거절된 생성 key는 미소비; accepted replay는 기존 job.
- **Evidence / Applied Invariants:** pool별 active count/reservation; `TR-I06`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Compute; `SAFE ALERT`; create.

#### TR-ACC-NORMJOB-013 — New job after terminal failure

- **Purpose / Use Case / Actors:** terminal 실패를 되살리지 않는 Core lineage; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** 첫 Compute job `FAILED`, Core raw/version/generation 재확인 통과; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** Core가 새 dispatch ID/key/최초 token으로 생성 / 새 Compute job ID, 같은 `coreImportSessionId`, Core `retryOf`가 old job을 가리킴; old job/실패 status 불변.
- **Aggregate State Changes / Created or Updated Artifacts:** 새 Compute job 1, Core Import는 동일 logical validation 범위.
- **Expected Outcome / Expected Failure or Exclusion:** 새 접수 `SUCCEEDED`; raw 만료면 `SOURCE_ARTIFACT_EXPIRED`, deletion gate 실패면 접수 금지; 기존 결과 불변.
- **Idempotency Result:** old key replay는 old FAILED job; new key는 new job.
- **Evidence / Applied Invariants:** Core durable dispatch lineage, 두 job ID; `TR-I05`, `TR-I06`, `TR-I16`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core lineage, Compute jobs; `SAFE`; create/status.

#### TR-ACC-NORMJOB-014 — Pre-creation job-bound grant

- **Purpose / Use Case / Actors:** Compute job ID 발급 전에도 정확한 raw scope 제한; Core, Compute.
- **Preconditions / Pinned Versions / Input Fixture:** Core logical/import/artifact IDs, role, hash, token, generation 확정; Compute job ID 없음; `BASE-V1`; `FIXTURE_NOT_REQUIRED`.
- **Given / When / Then:** Core signed grant 발급·Compute redeem / 서명 claim 전부 일치하고 한 번만 read 허용; 다른 role/token/generation 또는 두 번째 read는 거절, engine 실행 0.
- **Aggregate State Changes / Created or Updated Artifacts:** Core grant consumption audit만.
- **Expected Outcome / Expected Failure or Exclusion:** 정상 read `SUCCEEDED`; 불일치 `REJECTED/ARTIFACT_ACCESS_DENIED`, 금융값 없음.
- **Idempotency Result:** 같은 grant는 single-read, 새 시도는 새 grant.
- **Evidence / Applied Invariants:** signed claim/role/size/hash, opaque audit; `TR-I03`, `TR-I15`, `TR-I18`.
- **Service Ownership / Observability / Deferred HTTP Contract:** Core signs/redeems, Compute verifies bytes; `SAFE`; internal artifact grant.

---

## 5. Traceability and Completion

### 5.1 Scenario group index

| Group | IDs | Use Case focus | Fixture state | Expected outcomes |
|---|---|---|---|---|
| Import | `IMPORT 001–022` | create/upload/validate/status | planned or not required | success, accepted exclusions, rejection |
| Reconstruction | `RECON 001–012` | import normalization/reconciliation | planned | success or accepted exclusions |
| Analysis | `ANALYSIS 001–015` | run/status/result callback | planned or not required | success, completed unavailable, failure |
| Evidence | `EVIDENCE 001–010` | review/evidence/history query | planned or not required | success or hidden |
| Lifecycle | `DELETE 001–014` | retention/delete/restore | not required | success or truthful failure |
| Reprocessing | `REPROCESS 001–013` | reparse/rebuild/rerun/trend | planned or not required | success, failure, cancelled/rejected |
| Authorization | `AUTH 001–010` | owner/auth/CSRF/operator | not required | success, unauthorized, hidden |
| Normalization job protocol | `NORMJOB 001–014` | create replay/grant CAS/lease/terminal/ack/capacity | not required | success, retry wait, conflict, safe failure |

### 5.2 Invariant coverage matrix

| Invariant | Acceptance scenarios |
|---|---|
| `TR-I01` | AUTH-001~010, EVIDENCE-001~008 |
| `TR-I02` | IMPORT-011~012 |
| `TR-I03` | IMPORT-001~015; RECON-012; NORMJOB-014 |
| `TR-I04` | IMPORT-001~002, 013, 018~020; RECON-001~012; EVIDENCE-001~009 |
| `TR-I05` | IMPORT-018, 020~022; REPROCESS-001~006; NORMJOB-001, 005, 010, 013 |
| `TR-I06` | IMPORT-001~004, 021~022; RECON-001~012; NORMJOB-001~004, 006~007, 009~013 |
| `TR-I07` | RECON-001~003; EVIDENCE-003~004 |
| `TR-I08` | RECON-001~003, 007, 009~011; EVIDENCE-003~004 |
| `TR-I09` | IMPORT-002, 010~014; RECON-004~012; EVIDENCE-009 |
| `TR-I10` | RECON-005, 011~012; ANALYSIS-001~009, 011, 015 |
| `TR-I11` | IMPORT-004, 019, 021~022; RECON-001~012; ANALYSIS-001~015 |
| `TR-I12` | IMPORT-002, 010, 014; RECON-001~012; ANALYSIS-001~015; EVIDENCE-001~009 |
| `TR-I13` | RECON-003; ANALYSIS-001, 004 |
| `TR-I14` | DELETE-003, 006~008, 011, 014; AUTH-003 |
| `TR-I15` | DELETE-003, 006~010, 013; REPROCESS-011; AUTH-008; NORMJOB-002, 008, 014 |
| `TR-I16` | DELETE-003, 006~010, 013; REPROCESS-011; NORMJOB-004, 006~008, 010 |
| `TR-I17` | EVIDENCE-005~007; DELETE-001, 004 |
| `TR-I18` | all failure/auth scenarios; EVIDENCE-005~008; NORMJOB-001~014 |
| `TR-I19` | DELETE-001~003, 006~014 |
| `TR-I20` | DELETE-008, 013~014 |
| `TR-I21` | IMPORT-001, 003~004, 016~017; DELETE-001~002 |
| `TR-I22` | DELETE-006~008 |
| `TR-I23` | EVIDENCE-006~007; DELETE-001~003, 012, 014; AUTH-008 |
| `TR-I24` | DELETE-003, 006~008, 011~012, 014 |
| `TR-I25` | REPROCESS-001~006, 009 |
| `TR-I26` | REPROCESS-001~007, 013 |
| `TR-I27` | REPROCESS-002~006, 009, 011, 013 |
| `TR-I28` | EVIDENCE-007; DELETE-004~005; REPROCESS-002 |
| `TR-I29` | REPROCESS-003, 006, 008 |
| `TR-I30` | DELETE-006, 009~010, 013; REPROCESS-011; NORMJOB-008 |
| `TR-I31` | EVIDENCE-010; REPROCESS-001, 003~006, 010, 013 |
| `TR-I32` | REPROCESS-004~005, 012~013 |
| `TR-I33` | ANALYSIS-001, 012~014; REPROCESS-001, 003~005, 007, 010, 013; NORMJOB-010 |
| `TR-I34` | EVIDENCE-010; DELETE-004; REPROCESS-002, 004, 008, 012 |
| `TR-I35` | IMPORT-018, 020 |
| `TR-I36` | ANALYSIS-001, 010~015; REPROCESS-001, 003~011, 013; NORMJOB-009~010 |
| `TR-I37` | IMPORT-018; REPROCESS-001, 003, 010 |
| `TR-I38` | EVIDENCE-010; REPROCESS-001, 004~006, 008, 010, 012~013 |
| `TR-I39` | IMPORT-ORCH-001~003, 005~007, 010, 017~020 |
| `TR-I40` | IMPORT-ORCH-001, 013~016, 021~022 |
| `TR-I41` | IMPORT-ORCH-008~012, 020, 022 |

Scenario reference에서 group prefix `TR-ACC-`는 생략했다. 모든 `TR-I01`~`TR-I41`은 최소 하나의 정상 또는
위반 acceptance에 연결된다.

### 5.3 TR-0 acceptance completion matrix

| TR-0 완료 조건 | Assertion scenarios |
|---|---|
| canonical row provenance | IMPORT-001, 019; EVIDENCE-003~007 |
| reconciliation mismatch exclusion | IMPORT-014; RECON-004~010 |
| deterministic result hash | IMPORT-021~022; ANALYSIS-012~014; REPROCESS-007, 010 |
| normalization grant/attempt retry | NORMJOB-001~011, 013~014; IMPORT-ORCH-019 |
| normalization runtime identity/hash agreement | IMPORT-ORCH-017~022 |
| normalization capacity | NORMJOB-002, 012 |
| quantity/fee/PnL conservation | RECON-002~003; EVIDENCE-003~004 |
| unavailable과 실제 0 구분 | RECON-011; ANALYSIS-006~009 |
| Metric population/comparison | ANALYSIS-001~009; EVIDENCE-001~002 |
| evidence drill-down | EVIDENCE-001~010 |
| duplicate request/terminal handoff | IMPORT-016~022; ANALYSIS-012~014; DELETE-011; REPROCESS-007; NORMJOB-001, 005~006, 010 |
| retry | ANALYSIS-014~015; DELETE-012; REPROCESS-009; NORMJOB-002~004, 011~013 |
| deletion | DELETE-001~014 |
| reprocessing/version/trend | REPROCESS-001~013 |
| authorization/security | AUTH-001~010; EVIDENCE-008 |

---

## 6. Integration Harness Rules

1. Scenario ID는 assertion suite와 PR evidence의 stable key다. 기존 ID를 재사용하거나 의미를 바꾸지 않고 새
   경우는 group의 다음 번호를 추가한다.
2. fixture가 생성되기 전 `FIXTURE_PLANNED`를 `FIXTURE_AVAILABLE`로 바꾸지 않는다. fixture package는 scenario
   ID, input artifact hash, pinned version/config, expected state/artifact/count/hash/evidence를 포함한다.
3. `Then`의 count는 저장 전 staging artifact가 아니라 최종 Core-owned artifact count다. Compute runtime
   row/object는 장기 결과로 세지 않는다.
4. failure는 success payload나 빈 Metric으로 assertion하지 않는다. `MetricStatus`, null, zero와 aggregate
   terminal 상태를 각각 검사한다.
5. callback test는 exact duplicate와 conflicting/stale payload를 분리한다. terminal 상태를 되돌리는 fixture는
   모두 실패해야 한다.
6. authorization fixture는 existing/absent foreign ID의 외부 결과를 같게 검사하고, operational log에 민감
   값이 없는지도 검사한다.
7. OpenAPI E2E는 후속 작업에서 이 contract를 transport assertion으로 매핑한다. 이 문서의
   `Deferred HTTP Contract`를 endpoint/status/schema로 역추론해 고정하지 않는다.
8. `verify-contract-sync.ps1`은 Context와 sibling OpenAPI snapshot의 텍스트 일치만 확인한다.
   runtime DTO/serializer 적합성은 보증하지 않는다. 후속 Integration suite는 실제
   Core serializer → HTTP request → Compute DTO → Compute input hasher → terminal payload
   serializer → Core DTO를 `IMPORT-ORCH-017`~`022`로 검증한다.

## 7. Review Answers

1. CSV 하나만 있으면 validation을 시작하지 않고 `RECEIVED`에 머문다.
2. schema/product/session-level 검증을 통과했다면 일부 episode exclusion과 함께 Import를 accept할 수 있다.
3. format/schema/product/mode/identity conflict와 필수 policy/input 위반은 전체 Import를 reject한다.
4. 같은 session/role 파일 재업로드는 새 artifact/Revision을 만들지 않는다. 새 session의 overlap import는
   identity/content를 검증하고 새 immutable Revision을 만들 수 있다.
5. 동일 terminal callback은 `NO_OP`; 다른 hash/payload는 저장하지 않는다.
6. `COMPLETED` Analysis에는 quality report, 모든 Metric 상태/값-or-null, population/exclusion, evidence와
   canonical result hash가 필요하다.
7. 실제 0은 `AVAILABLE` non-null scalar이고 계산 불가는 non-`AVAILABLE` status와 null이다.
8. raw 삭제 뒤에는 masking version이 고정된 `SourceEvidenceSnapshot`으로 조회하며 원본 행이라고 부르지 않는다.
9. analysis 중 삭제가 commit되면 결과를 저장하지 않고 stale output을 폐기한다.
10. 재처리 실패 시 기존 Review와 latest pointer는 유지된다.
11. 다른 사용자에게 resource 존재 여부를 노출하지 않고 `NOT_FOUND_OR_HIDDEN`으로 다룬다.
12. 각 scenario의 fixture 상태와 `Applied Invariants`가 위 블록과 matrix에 명시돼 있다.
13. harness는 각 `Given/When/Then`, state/artifact count, outcome/failure/idempotency/evidence를 별도 assertion으로
    옮길 수 있다.

## 8. Out of Scope

OpenAPI path/payload/status, persistence schema, application/Integration code, CSV fixture 작성, Metric 계산식 변경,
Web 화면, queue, AI Summary, storage/deletion worker와 migration 실행은 이 문서의 범위 밖이다.
