# TRADING_REVIEW_PERSISTENCE.md — Trading Review Logical Persistence Contract

이 문서는 Trading Review의 논리적 저장 경계, 소유권, uniqueness, immutability와 transaction boundary를
정의한다. 실제 table/column/index type, ORM과 migration syntax는 구현 저장소가 정한다. 정확한 HTTP schema는
[`../openapi/core-api.yaml`](../openapi/core-api.yaml)과
[`../openapi/compute-api.yaml`](../openapi/compute-api.yaml), API 흐름은
[`TRADING_REVIEW_API.md`](TRADING_REVIEW_API.md), lifecycle/version 계산 의미는 각 정본 문서를 따른다.

---

## 1. Aggregate 관계와 identity

```text
Member
└── TradingAccount
    └── TradingBook
        ├── TradingImportSession
        │   ├── TradingSourceArtifact
        │   └── createdLedgerRevision?
        ├── LedgerRevision
        │   ├── LedgerRevisionRecord ──> TradingRecord
        │   ├── ReconciliationManifest
        │   └── SourceEvidenceSnapshot (record provenance를 통해 참조)
        ├── TradingAnalysisRun
        │   └── TradingAnalysisResult
        ├── TradingReprocessingRun
        └── DeletionRequest
```

- 모든 identity는 service가 발급하는 opaque typed ID다. source UID나 Trade ID를 primary identity로 사용하지
  않는다.
- 하나의 `TradingRecord` row는 정확히 하나의 owner/Book에 속하지만 여러 immutable Revision이 같은 record를
  참조할 수 있다. record content가 같은 경우에만 같은 Book 안에서 재사용한다.
- Revision membership은 `LedgerRevisionRecord(revisionId, recordId, semanticOrderKey)` 별도 relation이다.
  따라서 record를 복제하지 않고도 각 Revision의 고정 집합과 canonical ordering을 표현한다.
- artifact provenance는 `SourceEvidenceSnapshot`의 artifact/row와 record-provenance relation을 통해 여러
  record가 공유한다. 한 source trade row가 Execution/Fee/VenueReportedPnl record를 만들 수 있으므로
  snapshot을 record에 inline 복제하지 않는다.
- reprocessing child Revision은 parent ID 배열을 가진 DAG lineage edge로 연결한다. parent는 같은 owner/Book,
  cycle 없음이 transaction precondition이다.
- `TradingBook.latestRevisionId`와 `latestAnalysisRunId`는 mutable publication pointer이며 immutable Revision/
  Result 본체와 분리한다. 함께 바뀌어야 하는 reprocessing은 한 final publication transaction에서 쌍으로
  갱신한다.
- cross-user physical/logical deduplication은 hash가 같아도 금지한다. owner별 object, DEK, record와 evidence를
  격리한다.

## 2. Core logical model matrix

`immutable field`는 생성/accept/completion 뒤 수정 금지인 의미 필드다. purge는 update가 아니라 lifecycle
삭제이며 tombstone/control record만 남길 수 있다.

| Logical model | Owner / Aggregate Root | Primary / parent identity | Mutable fields | Immutable fields | Lifecycle status | Unique constraint | Hash / idempotency / provenance | Retention, locking, transaction boundary |
|---|---|---|---|---|---|---|---|---|
| `TradingAccount` | Core / self | account ID / Member | display name, `ACTIVE/ARCHIVED`, deletion generation | owner, venue, createdAt | active/archive/deleting | `(owner, accountId)`; optional external alias는 owner scope | raw account ID 저장/노출 금지 | account lifecycle; optimistic lock 필요; create 단일 tx |
| `TradingBook` | Core / self | Book ID / Account | display name, latest pointers, deletion generation | owner/account, venue/product/mode/settlement, createdAt | active/deleting | `(accountId, bookId)` | lineage owner scope | Book lifecycle; pointer CAS/optimistic lock; publication tx |
| `TradingImportSession` | Core / self | session ID / Book | status, validation timestamps, failure, created Revision pointer | timezone/mode, pinned policy/version/generation, artifact role inputs after validation start | `RECEIVED→VALIDATING→ACCEPTED/REJECTED` | public idempotency key in owner+operation scope; one active validation logical key | validation logical key; source session provenance | accepted: Book lifecycle, rejected: 30d; optimistic lock; validate transition+dispatch tx |
| `TradingSourceArtifact` | Core / ImportSession | artifact ID / session | upload/retention state, detected dialect, raw locator/key availability until purge | role, byte size, raw SHA-256, content type, independent observed coverage `{earliest?,latest?,rowCount,sourceTimezone,SECOND,NOT_PROVEN}` after validation | upload status + artifact retention state | exactly one artifact per `(sessionId, role)`; same role/hash replay | raw content hash; upload idempotency key; source of snapshots | metadata session lifecycle; raw ADR-061 TTL; CAS retention; object+DB saga |
| `SourceEvidenceSnapshot` | Core / ImportSession | snapshot ID / artifact+row | only raw availability projection; snapshot payload never rewritten | role,row,canonical row hash, allowlist, masked IDs, schema/masking version | available/deleted raw marker | `(artifactId, sourceRowNumber, evidenceSchemaVersion)` | source row hash; artifact provenance | accepted Book lifecycle; immutable, no optimistic lock; import publication tx |
| `TradingRecord` | Core / TradingBook ledger | record ID / Book | none | canonical typed content, source scale, source execution identity, provenance, schema/version | accepted or purge only | Book-scoped source identity; same identity/content reuse; same identity/different content conflict | canonical record hash; one-to-many provenance | Book/revision dependency lifecycle; immutable; import publication tx |
| `LedgerRevision` | Core / self | revision ID / Book | none; latest pointer는 Book에 있음 | revision number, membership, hashes, role별 artifact coverage와 derived ledger coverage, versions, terms, capability, quality, lineage | staged then published immutable | `(bookId, revisionNumber)`; deterministic success uniqueness `(bookId, contentHash, reconstructionVersionSetHash, manifestHash)` | content/manifest/version hashes; import/reprocessing provenance | Book lifecycle; immutable; final publication tx |
| `LedgerRevisionRecord` | Core / LedgerRevision | `(revisionId, recordId)` / Revision | none | membership and semantic order key | Revision과 동일 | pair unique; order key may repeat only where semantic set ordering permits | record content hash referenced | Revision lifecycle; immutable; same publication tx |
| `ReconciliationManifest` | Core / LedgerRevision | manifest ID / Revision | none | complete allocation/status/tolerance/capability/provenance payload | staged/published then purge | exactly one per Revision; hash unique only within owner/Book deterministic input | manifest hash + input/version hash | Revision lifecycle; immutable object + descriptor; publication tx |
| `TradingAnalysisRun` | Core / self | run ID / Book+Revision | status/progress/failure/timestamps; no completed payload mutation | pinned Revision/manifest, versions/config/hash, logical key, generation | `PENDING→RUNNING→COMPLETED/FAILED` | at most one active attempt per analysis logical key; one successful deterministic result | analysis logical key, config/input/version hash | Book/revision lifecycle; optimistic lock; create+dispatch and terminal tx |
| `TradingAnalysisResult` | Core / AnalysisRun | result ID / Run | none | quality, units, observations, metrics, findings, evidence membership, hashes, lineage | published only when complete | exactly one completed result per Run; successful deterministic uniqueness by logical key+result hash | result/evidence hash; Revision/manifest/source provenance | Run lifecycle; immutable object+descriptor; terminal publication tx |
| `TradingReprocessingRun` | Core / self | reprocessing run ID / Book | state/progress/failure/timestamps; child IDs assigned at publication | source/target/type/reason/availability/logical key/generation/attempt/retry lineage | `PENDING→VALIDATING_INPUT→RUNNING→COMPLETED/FAILED/CANCELLED` | one active attempt per logical key; one successful deterministic output | ADR-062 logical key, source/target/result hashes, DAG lineage | Book lifecycle; optimistic lock; admission+dispatch and publication tx |
| `DeletionRequest` | Core / self | deletion request ID / owner scope | state/checklist/attempt/failure/purge times | target type/ID, requested scope, generation, key, requestedAt | request deletion state machine | `(owner, scopeType, scopeId, deletionGeneration)`; request key unique | deletion logical identity; payload-free audit provenance | completion/recovery +180d; CAS/optimistic lock; tombstone tx then saga |
| `ArtifactRetentionState` | Core / Artifact | artifact ID / artifact | status, due/attempt/failure/deletedAt | policy/version, original expiry | artifact retention state machine | one current state per artifact | deletion request/generation link | artifact metadata lifecycle; CAS; each transition tx |
| `DurableDispatch` | Core / owning Run or ImportSession | dispatch ID / logical aggregate | dispatch state, Compute job ID, attempt, lease/retry, ack | operation kind, logical ID, Compute key, canonical request fingerprint, generation | ready/dispatching/accepted/terminal/acknowledged/abandoned | `(operationKind, logicalAggregateId, attempt)`; one active dispatch; Compute key unique | request/input/version hash; terminal payload hash and ack | parent lifecycle/audit; optimistic lock; parent creation same tx, HTTP outside tx |

### 2.1 Required field-by-field contract

아래 표의 열은 구현 schema review에서 각각 독립적으로 확인한다. 위 compact matrix의 결합 항목을 생략 가능한
것으로 해석하지 않는다.

| Model | Owner service | Aggregate Root | Primary identity | Parent identity | Mutable field | Immutable field | Lifecycle status | Unique constraint | Content/version hash | Idempotency key | Provenance | Retention/deletion | Optimistic locking | Transaction boundary |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---:|---|
| TradingAccount | Core | self | account ID | Member ID | name,status,generation | owner,venue,createdAt | ACTIVE/ARCHIVED/deleting | owner+ID | — | create transport key | authenticated principal | Account lifecycle/cascade | 예 | create/update local tx |
| TradingBook | Core | self | Book ID | Account ID | name,latest pointers,generation | product/mode/settlement/owner | active/deleting | Account+ID | latest target hashes referenced | create key | Account chain | Book lifecycle/cascade | 예 | create; final publication pointer tx |
| TradingImportSession | Core | self | session ID | Book ID | status,failure,timestamps,created Revision ID | timezone/mode/policy/version/generation after validation | RECEIVED/VALIDATING/ACCEPTED/REJECTED | public key; validation logical key | input/version set hash | create+validation keys | artifact/session/Book | accepted Book lifecycle; rejected P30D | 예 | validation transition+dispatch; terminal tx |
| TradingSourceArtifact | Core | ImportSession | artifact ID | session ID | upload/retention state,raw locator until purge | role,size,SHA-256,content type,validated independent observed coverage | upload + retention states | session+role | raw SHA-256,dialect/schema fingerprint | upload key | source object/session | metadata session lifecycle; raw TTL/raw-only delete | 예(CAS) | object saga then slot-fill tx |
| SourceEvidenceSnapshot | Core | ImportSession | snapshot ID | artifact+row | raw availability projection only | row hash,allowlist,masked IDs,versions | retained/purged | artifact+row+schema version | canonical row hash,mask version | validation key inherited | artifact/row/source key | Book lifecycle; session cascade | 아니오 | accepted import publication tx |
| TradingRecord | Core | TradingBook | record ID | Book ID | none | typed content,scale,identity,provenance,schema | accepted/purged | Book source identity+content guard | canonical record/schema hash | validation logical key inherited | one-or-more snapshots | Book/session dependency purge | 아니오 | accepted import publication tx |
| LedgerRevision | Core | self | Revision ID | Book ID | none | number,membership,terms,versions,hashes,artifact/ledger coverage,quality,lineage | staged/published/purged | Book+revision number; deterministic success | content/manifest/version hashes | validation/reprocessing logical key | imports,parent DAG | Book/session dependency purge | 아니오 | final publication tx |
| LedgerRevisionRecord | Core | LedgerRevision | Revision ID+record ID | Revision and record IDs | none | membership,semantic order | Revision lifecycle | pair unique | referenced record content hash | parent logical key | record→snapshot | Revision cascade | 아니오 | Revision publication tx |
| ReconciliationManifest | Core | LedgerRevision | manifest ID | Revision ID | none | allocations,status,tolerance,capability,provenance | staged/published/purged | exactly one per Revision | input/version/manifest hash | parent logical key | record/allocation/snapshot | Revision cascade | 아니오 | object staging + Revision publication tx |
| TradingAnalysisRun | Core | self | analysis Run ID | Book+Revision | status,progress,failure,timestamps | pinned input/version/config/logical key/generation | PENDING/RUNNING/COMPLETED/FAILED | one active + one success per logical key | input/version/config/result hash | public key + analysis logical key | Revision/manifest | Book/Revision cascade | 예(CAS) | create+dispatch; terminal publication tx |
| TradingAnalysisResult | Core | AnalysisRun | result ID | Run ID | none | units,metrics,findings,evidence,quality,lineage | completed/purged | exactly one completed per Run; deterministic success | result/evidence hash | analysis logical key | Revision→record→snapshot | Run/Revision cascade | 아니오 | object staging + terminal tx |
| TradingReprocessingRun | Core | self | reprocessing Run ID | Book+source Revision/Run | status,progress,failure,child IDs,timestamps | source/target/type/reason/key/generation/attempt | PENDING/VALIDATING_INPUT/RUNNING/COMPLETED/FAILED/CANCELLED | one active/one success per logical key | source/target/config/result hashes | public key + ADR-062 logical key | DAG parents,retryOf,imports | Book lifecycle/deletion wins | 예(CAS) | admission+dispatch; final publication tx |
| DeletionRequest | Core | self | deletion request ID | owner scope target | state,checklist,attempt,failure,times | scope,generation,key,requestedAt | REQUESTED/CANCELLING_JOBS/PURGING_PRIMARY/PURGING_OBJECTS/COMPLETED/FAILED | owner+scope+generation | checklist/version metadata | delete key | tombstone/target scope | completion/recovery+P180D | 예(CAS) | tombstone tx; component saga |
| ArtifactRetentionState | Core | Artifact | artifact ID | artifact ID | state,attempt,failure,deletedAt | policy,expiry,generation | ACTIVE/RETENTION_SCHEDULED/DELETION_PENDING/DELETED/DELETION_FAILED | one state per artifact | policy version | deletion request identity | artifact/deletion | artifact metadata lifecycle | 예(CAS) | one state transition tx |
| DurableDispatch | Core | owning session/run | dispatch ID | logical Aggregate ID | state,Compute job,attempt,retry,ack | kind,request fingerprint,key,generation | ready/dispatching/accepted/terminal/acknowledged/abandoned | one active per Aggregate; Compute key unique | request/input/version/payload hashes | Compute dispatch key | Core Aggregate+terminal payload | parent lifecycle/audit | 예 | parent create same tx; HTTP outside |

### 2.2 Revision and record constraints

1. `revisionNumber`는 Book 안에서 1부터 증가하며 unique다. number allocation과 Revision insert는 같은
   transaction에서 Book-level serialization 또는 equivalent atomic allocator를 사용한다.
2. `TradingRecord`의 Book-scoped `sourceExecutionIdentity`가 같고 canonical content hash가 같으면 기존 record를
   참조하고 provenance relation을 추가할 수 있다.
3. 같은 identity에서 content hash가 다르면 `RECORD_CONFLICT`; 기존 record를 update하지 않는다.
4. Position History row identity는 `(artifactId, sourceRowNumber)`이며 value-based deduplication을 하지 않는다.
5. artifact raw SHA-256는 owner/Book/session scope에서만 duplicate 판정에 사용한다. 같은 hash의 다른 owner
   object를 공유하지 않는다.
6. Revision content hash는 sorted record content identities/membership, terms와 canonical schema를 고정한다.
   DB-generated record/Revision ID, insert order와 createdAt은 hash에서 제외한다.

## 3. Compute runtime matrix

| Logical model | Owner / Aggregate Root | Primary / parent identity | Mutable / immutable | Lifecycle / uniqueness | Hash, retention, locking and transaction |
|---|---|---|---|---|---|
| `ComputeJob` | Compute / self | Compute job ID / Core logical aggregate ID | state/progress/lease는 mutable; canonical request fingerprint, Core ID, kind, version/input hash, generation은 immutable | `PENDING→RUNNING→COMPLETED/FAILED/CANCELLED`; Compute idempotency key unique; one active logical job | terminal max 24h; optimistic/CAS claim; create+idempotency atomic |
| `ComputeJobAttempt` | Compute / ComputeJob | `(jobId, attemptNumber)` / job | lease owner/heartbeat/outcome mutable until terminal; attempt token immutable | one live lease per job, attempt number unique | parent terminal 뒤 max 24h; lease claim/heartbeat tx; stale attempt cannot finish job |
| `ComputeTerminalPayload` | Compute / ComputeJob | payload ID / terminal job | acknowledgement/purge marker만 mutable; bytes/hash/media type/size immutable | exactly one accepted terminal descriptor per job attempt; duplicate same hash no-op, different hash conflict | terminal max 24h, ack 뒤 cleanup; encrypted; completion+descriptor atomic |
| `ArtifactLease` | Compute / ComputeJob | lease ID / job+Core artifact | consumed/cancelled mutable; role/checksum/grant expiry/generation immutable | one grant per job attempt/artifact role; expiry max 5m | raw input scratch는 job/runtime 범위, backup 금지; signed URI/path log 금지 |

| Model | Owner service | Aggregate Root | Primary identity | Parent identity | Mutable field | Immutable field | Lifecycle status | Unique constraint | Content/version hash | Idempotency key | Provenance | Retention/deletion | Optimistic locking | Transaction boundary |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---:|---|
| ComputeJob | Compute | self | job ID | Core logical ID | status,progress,lease summary,terminal metadata | kind,request fingerprint,input/version/generation | PENDING/RUNNING/COMPLETED/FAILED/CANCELLED | Compute key unique; one active logical job | request/input/version/result hash | Compute dispatch key | Core logical ID/attempt | terminal max PT24H; deletion cancel/purge | 예(CAS) | job+idempotency atomic create; terminal tx |
| ComputeJobAttempt | Compute | ComputeJob | job ID+attempt number | job ID | lease owner,heartbeat,outcome until terminal | attempt token,input/generation | queued/leased/terminal | attempt number; one live lease | attempt input/result hashes | parent key+attempt token | parent job/worker attempt | parent terminal max PT24H | 예(lease CAS) | claim/heartbeat/finish tx |
| ComputeTerminalPayload | Compute | ComputeJob | payload ID | terminal job+attempt | ack/purge marker | media type,size,bytes,payload/result hashes,expiry | available/acknowledged/purged | one accepted payload per job attempt/hash | payload/input/version/result hashes | ack key | job/Core logical Aggregate | ack cleanup; hard max PT24H; no backup | 예(CAS ack) | job completion+descriptor atomic; ack tx |
| ArtifactLease | Compute | ComputeJob | lease ID | job attempt+Core artifact | consumed/cancelled | role,checksum,grant expiry,generation | active/expired/consumed/cancelled | one per attempt+role | raw checksum/input hash | attempt token | Core artifact opaque ID | max PT5M; runtime/deletion purge | 예(CAS consume) | lease register/consume tx; object read outside |

Compute는 Core의 `TradingRecord`, Revision, AnalysisResult를 자기 장기 record로 복제하지 않는다. terminal payload는
Core가 검증·복사할 때까지의 transport/runtime artifact일 뿐 source of truth가 아니다.

Normalization `ComputeJob`은 생성 시 계산 pin과 최초 attempt token,
`grantRenewalCutoffAt`을 고정하고 `grantRevision=0`에서 시작한다.
생성 요청의 URI/만료시각은 계산 fingerprint가 아니지만
저장된 grant를 생성 replay로 교체하지 않는다. `grantRevision`, 현재 attempt token과 grant
locator/expiry는 별도 grant-update transaction에서만 바뀐다. 필요 동작
(`NONE | REPLACE_QUEUED_GRANTS | START_RETRY_ATTEMPT`)과
`grantRefreshDeadlineAt`은 claim·expiry·grant-update transaction에서 상태에 따라 바뀐다. 이전 token과
ArtifactLease 이력은 삭제 전까지 보존하며 한 revision에 role별 grant가 정확히 하나다.
Compute job의 `PENDING/RUNNING`은 grant 대기에도 유지되고 active capacity를 소비한다.
job terminal 뒤에는 grant revision·token을 수정하지 않는다.

## 4. Persistence ownership matrix

| Logical data | Owner | Writer | Reader | Retention | Immutable |
|---|---|---|---|---|---:|
| Account/Book/import metadata | Core | Core | Core public use cases | parent lifecycle | identity/pin은 예 |
| encrypted raw artifact | Core namespace | Core artifact adapter/deletion worker | Core grant로 승인된 Compute job만 | accepted 7d, rejected/non-terminal 24h | bytes는 예 |
| evidence snapshot/canonical record | Core | Core terminal publication | Core; Compute에는 bounded analysis input | Book/session lifecycle | 예 |
| Revision/membership/manifest | Core | Core terminal publication | Core; Compute bounded input | Book/session lifecycle | 예 |
| Analysis run/result/evidence membership | Core | Core orchestration/publication | Core | Book/revision lifecycle | completed result 예 |
| Reprocessing/deletion/dispatch | Core | Core workers/use cases | Core | parent lifecycle; deletion ledger 180d | request pin은 예 |
| Market reference data | Compute | Compute ingestion | Compute API | 해당 정본 | snapshot 예 |
| Compute job/attempt/input lease | Compute | Compute API/worker | Compute; Core는 API로 상태만 | terminal 최대 24h | request pin은 예 |
| Compute terminal payload | Compute runtime | Compute worker | Core authenticated download | ack 또는 terminal+24h까지 | bytes/hash 예 |

동일 logical record를 두 서비스가 동시에 소유하지 않는다. Core 장기 저장에는 `computeJobId`, Core Aggregate
ID, terminal payload hash와 acknowledgement time을 남긴다. acknowledgement 후 Compute payload는 지워져도
Core 결과의 유효성에 영향이 없다.

## 5. Idempotency와 deterministic uniqueness

### 5.1 Logical keys

```text
importValidationLogicalKey = SHA-256(canonical JSON {
  ownerScope, importSessionId,
  artifactsByRole[{role, rawSha256}],
  normalizationReconstructionVersionSetHash,
  dataPolicyVersion, deletionGeneration
})

analysisLogicalKey = SHA-256(canonical JSON {
  ownerScope, ledgerRevisionContentHash,
  reconciliationManifestHash,
  metricDefinitionVersionSetHash,
  analysisConfigHash, deletionGeneration
})

reprocessingLogicalKey = SHA-256(canonical JSON {
  ownerScope, tradingBookId,
  sourceRevisionContentHash,
  sourceAnalysisResultHash?,
  targetVersionSetHash,
  analysisConfigHash?, reprocessingType
})

computeDispatchIdentity = SHA-256(canonical JSON {
  operationKind, coreLogicalAggregateId,
  logicalRequestKey, initialAttemptToken,
  targetVersionSetHash, deletionGeneration
})

computeGrantUpdateIdentity = independent Idempotency-Key + canonical update fingerprint {
  jobId, mode, expectedGrantRevision, expectedAttemptToken, nextAttemptToken,
  expectedInputHash, versionSetHash, deletionGeneration,
  artifactsByRole[{coreArtifactId, role, rawSha256, byteSize, contentType, accessUri, expiresAt}]
}
```

각 identity/fingerprint의 필드 선택은 위 식과 API 계약을 따르고 canonical 정렬/스칼라 직렬화는
`TRADING_VERSIONING.md` §16을 사용한다. public transport
`Idempotency-Key`는 이 logical key와 별도로 보존하며 같은 key/different fingerprint를 거절한다.
Compute 생성 fingerprint는 immutable 계산 입력과 **최초** `attemptToken`을 포함하지만 signed
`accessUri`/`expiresAt`, header, runtime UUID/시각은 제외한다. 생성 key는 job lifetime 동안
불변이고 이후 token 변경은 이 key를 다시 계산하지 않는다. Grant update fingerprint는
URI/expiry까지 포함하되 idempotency record에는 fingerprint hash만 저장·비교한다.
현재 grant locator는 실행에 필요한 기간 동안 별도 암호화된 runtime field에 보관하며
원문을 log/metric에 남기지 않는다.
같은 update key/body는 최초 receipt를 재반환하고 key 재사용·CAS 불일치는 `409`다.

### 5.2 Required uniqueness

| Boundary | Logical constraint |
|---|---|
| revision number | unique `(bookId, revisionNumber)` |
| artifact role | unique `(importSessionId, artifactRole)` |
| artifact hash | raw SHA-256 fixed after upload; duplicate scope에 owner/Book/session 포함 |
| execution identity | unique Book-scoped source identity with content conflict guard |
| validation | one logical dispatch/result per validation key; terminal session cannot reopen |
| analysis | at most one active attempt per logical key; one successful deterministic result |
| reprocessing | at most one active attempt per logical key; one successful output; retry lineage explicit |
| Compute dispatch | one accepted Compute job per Compute idempotency key/fingerprint |
| Compute grant update | one receipt per independent update key/fingerprint; `(jobId, grantRevision)` unique, revision 단조 증가 |
| terminal payload | one `(jobId, attemptToken, resultHash)`; conflicting hash rejected |
| deletion | unique scope/generation request; duplicate key returns same request |
| deterministic publication | same canonical input/version/config cannot publish a second different hash |

실패한 Analysis/Reprocessing Run은 immutable하게 남긴다. 허용된 retry는 same logical key 아래 새 run 또는
새 attempt를 만들고 `retryOf`를 기록한다. 성공 결과가 이미 있으면 새 결과를 만들지 않는다.

## 6. Immutability and latest pointers

- accepted `TradingRecord`, `LedgerRevision`, `LedgerRevisionRecord`, pinned instrument terms와
  `ReconciliationManifest`는 수정하지 않는다.
- completed `TradingAnalysisResult`, BehaviorObservation/Metric/Finding와 evidence membership은 수정하지 않는다.
- source correction, version/config 변경은 영향에 따라 새 Revision 또는 Run을 만든다.
- `latestRevisionId`와 `latestAnalysisRunId`만 mutable하며 fully verified completed artifact를 가리킨다.
- failed/cancelled/staging output은 latest pointer 대상이 아니다.
- 삭제는 immutable content에서 row를 빼는 update가 아니라 tombstone/access revocation 후 dependency scope
  전체 purge다.

## 7. Transaction boundaries

원격 HTTP, object storage와 장시간 계산을 단일 DB/distributed transaction으로 묶지 않는다. 각 단계는 durable
state, idempotency, generation/hash 검증과 retry로 연결한다.

### 7.1 Import

1. **Session create transaction**: owner/Book 특성, policy/version을 resolve하고 session과 public idempotency
   mapping을 만든다.
2. **Artifact upload saga**: DB 밖에서 encrypted object를 streaming write/verify한다. 성공 뒤 짧은 DB
   transaction에서 empty role slot을 artifact metadata로 채운다. conflict/rollback 시 orphan cleanup을 enqueue한다.
3. **Validation admission transaction**: owner, two roles, raw availability, version/policy/generation을 검증하고
   `RECEIVED→VALIDATING`, validation logical key와 `DurableDispatch`를 함께 commit한다.
4. dispatcher는 transaction 밖에서 Compute를 호출한다. 생성 timeout/503은 같은 Compute key와
   최초 token/body로 retry한다. 생성 응답 유실 replay는 grant/token을 바꾸지
   않는다. Compute status의 `grantAction`을 보고 필요한 경우 별도 update key를 durable 기록한
   뒤 transaction 밖에서 grant update를 전송한다.
5. **Accepted publication transaction**: terminal payload의 attempt/input/version/generation/hash를 검증하고
   canonical records/provenance/snapshots, manifest, Revision/membership, session `ACCEPTED`, created Revision ID와
   Book latest Revision pointer를 함께 commit한다. 어느 일부만 저장된 accepted import도 허용하지 않는다.
6. rejection은 complete quality/failure를 저장하며 `REJECTED`로 한 transaction에서 전이하고 Revision을 만들지
   않는다.

대형 manifest/record payload는 Core-owned immutable object에 먼저 staged할 수 있다. DB publication 전에는
정상 query에 보이지 않으며 transaction rollback 시 orphan cleanup 대상이다.

### 7.2 Analysis

1. **Admission transaction**: owner/Revision/compatibility/generation과 logical uniqueness를 검증하고
   `TradingAnalysisRun(PENDING)` + dispatch를 만든다.
2. Compute acceptance 기록 뒤 Run을 `RUNNING`으로 CAS 전이한다.
3. **Terminal publication transaction**: result/evidence object가 Core storage에 검증·staged된 뒤 current
   generation, attempt, input/version/config/result/evidence hash와 complete payload를 재검사한다. Result
   descriptor, Run `COMPLETED`, optional latest Analysis pointer를 함께 commit한다.
4. result 없는 `COMPLETED`, 일부 Metric/evidence 누락과 hash mismatch는 금지한다. Run을 structured
   `FAILED`로 끝내고 기존 result/pointer를 보존한다.

### 7.3 Reprocessing

1. **Admission transaction**: source availability, exact target compatibility, deletion generation과 logical key를
   고정해 Run+dispatch를 만든다.
2. child Revision/Analysis output은 staging이며 정상 read/latest에 노출하지 않는다. 장시간 단계는 각자의
   dispatch/attempt로 실행할 수 있다.
3. **Final publication transaction**: 모든 requested child, DAG lineage, hashes와 generation을 검증하고 child
   descriptors, ReprocessingRun `COMPLETED`, 선택한 latest Revision/Analysis pointer 쌍을 원자적으로 publish한다.
4. failure/cancel은 staging cleanup을 enqueue하고 기존 Revision/Result/latest를 바꾸지 않는다.

### 7.4 Deletion

1. **Deletion commit transaction**: owner/scope equivalence를 검증하고 generation 증가, tombstone,
   `DeletionRequest(REQUESTED)`, fixed purge checklist를 함께 commit한다. 이 순간 normal access와 신규 job/retry를
   막는다.
2. worker는 active dispatch/Compute job cancel을 요청하고, Core DB/read model/object/DEK/runtime을 checklist
   순서로 idempotently purge한다. 각 component completion은 별도 transaction이다.
3. session 삭제는 해당 session/provenance를 포함하는 Revision과 descendant result 전체를 purge한다. Revision
   membership을 수정해 남기지 않는다. unaffected latest를 찾는 pointer update 또는 null 설정은 같은 primary
   purge transaction에서 한다.
4. 모든 live component absent verification 뒤에만 `COMPLETED`; partial/error는 access block을 유지한
   `FAILED`다. backup expiry는 별도 `backupPurgeDueAt`로 추적한다.
5. 늦은 terminal result는 current generation/tombstone 검사에서 거절하고 payload cleanup을 acknowledge한다.

### 7.5 Compute normalization grant/attempt

1. **Create transaction**: Compute 생성 key/fingerprint와 Job, 최초 token,
   `grantRevision=0`, `grantRenewalCutoffAt`, 두 role grant를 원자적으로 기록한다. 같은 key/fingerprint 조회는
   fresh-grant validation보다 먼저 하며 저장된 grant를 갱신하지 않는다. 신규 job에만
   Trading Review pool capacity를 원자적으로 예약한다.
2. **Grant update transaction**: 독립 update key/fingerprint의 기존 receipt를 먼저 조회한다.
   신규 update면 job row를 잠그고 current revision/token, pinned input/version/generation,
   허용된 `grantAction`, live lease 부재, deadline와 retry budget을 검증한다. 두 grant를
   함께 교체하고 이전 ArtifactLease를 닫으며 revision 증가·새 token/attempt 등록·receipt
   저장을 하나의 transaction으로 commit한다. 원격 object read는 이 transaction 밖이다.
3. **Claim/expiry transaction**: fresh한 미소비 grant 둘이 있을 때만 claim한다. claim
   후 실패·부분 소비·crash/lease expiry는 이전 attempt/lease를 닫고
   `START_RETRY_ATTEMPT`와 DB-clock deadline을 기록한다. 첫 claim 전 만료는
   `REPLACE_QUEUED_GRANTS`다. worker가 없어도 expiry sweep 또는 status의 짧은 row-lock
   transaction이 처음 관측한 DB 시각에 action/deadline을 한 번만 기록한다. deadline은
   동작별 wait window와 생성 시 고정한 `grantRenewalCutoffAt` 중 이른 시각이다. 이후
   polling은 저장된 deadline을 반환하며 연장하지 않는다. 기한·budget을 넘기면 safe terminal `FAILED`다.
4. **Terminal transaction**: lease owner, 최종 token, revision, generation, input/version
   hash와 취소 marker를 CAS로 확인한다. 완료 payload/descriptor와 `COMPLETED`를
   원자적으로 publish한다. stale attempt는 아무 payload도 publish하지 못한다.
   `FAILED`는 status의 structured failure만 저장한다.
5. **Acknowledgement transaction**: job/payload/최종 token·generation 및 전송 bytes/result
   hash를 함께 검증한다. `PERSISTED`와 deletion 이후 `DISCARDED_STALE`을 구별하고
   동일 ack key/body는 no-op이다. Ack가 결과 hash를 수정하지 않으며 payload는
   ack 이후 또는 terminal+24시간 중 먼저 오는 cleanup 대상이다.

## 8. Optimistic locking policy

| Model | Required | Reason |
|---|---:|---|
| Account/Book | 예 | archive/delete/latest pointer 경쟁 |
| ImportSession | 예 | upload/validation/terminal transition 경쟁 |
| ArtifactRetentionState | 예(CAS) | TTL/explicit delete/remediation 경쟁 |
| AnalysisRun/ReprocessingRun | 예(CAS) | polling worker, retry, cancel, deletion 경쟁 |
| DeletionRequest | 예(CAS) | checklist retry와 remediation 경쟁 |
| DurableDispatch/ComputeJob/Attempt | 예(CAS/lease) | duplicate dispatcher/worker claim |
| accepted record/Revision/manifest/completed result/snapshot payload | 아니오 | update 자체 금지; membership/unique/hash constraint로 보호 |

## 9. Retention and deletion behavior summary

- raw artifact: accepted `acceptedAt + P7D`, rejected `rejectedAt + PT24H`, non-terminal
  `uploadedAt + PT24H`; application backup/object versioning 제외.
- rejected ImportSession/artifact metadata: `rejectedAt + P30D`.
- evidence/canonical/Revision/result: Book lifecycle 또는 session/상위 explicit deletion까지.
- Compute runtime/terminal payload/idempotency: terminal 후 최대 `PT24H`; 장기 backup 없음.
- operational log: `P30D`; audit/deletion ledger: `P180D`; financial payload 금지.
- backup snapshot: 생성 후 최대 `P30D`; deletion replay 전 restore traffic 금지.
- raw-only delete는 snapshot/ledger/result를 유지한다. session 삭제는 종속 immutable Revision/result 전체를
  purge한다. Book/Account/Member는 subtree를 cascade한다.

## 10. Implementation handoff checklist

Core 구현은 table syntax를 선택하기 전에 다음을 schema-level로 강제해야 한다.

- owner/parent foreign-key 또는 동등한 aggregate scope 검증
- Book별 revision number, session별 role, public idempotency mapping과 logical key unique constraint
- record identity/content conflict guard와 revision membership relation
- completed Run↔Result exactly-one, result 없는 COMPLETED 방지
- latest pointer가 published completed child만 가리키는 check/application invariant
- deletion generation/tombstone을 모든 admission와 terminal publication predicate에 포함
- dispatch와 parent Aggregate의 atomic creation
- object descriptor hash/size/media type과 acknowledgement 보존

삭제 시 lineage 보존 범위는 payload retention과 같다. raw-only 삭제는 Revision/Run lineage를 유지하고 raw
availability만 `DELETED`로 바꾼다. ImportSession 삭제는 그 session 또는 provenance에 transitive하게 의존하는
Revision/Result와 lineage edge를 함께 purge하며 surviving node가 purged parent를 정상 reference로 남기지
않는다. Book/Account/Member 삭제는 해당 subtree의 product lineage를 모두 purge한다. 삭제 control plane에는
scope type/opaque ID, generation, 상태, count와 시각만 180일 남기고 version/result/financial payload를 복제하지
않는다. 다른 owner와 독립된 session/Revision lineage는 어떤 경우에도 변경하지 않는다.

Compute 구현은 다음을 강제해야 한다.

- job/idempotency row atomic create, one active attempt/lease
- grant update key/receipt와 `expectedGrantRevision + expectedAttemptToken` CAS, 두 role grant의
  atomic replacement, 이전 ArtifactLease 폐쇄; live lease/terminal/generation/input/version 변경 거절
- claim 전 grant 만료는 `PENDING/REPLACE_QUEUED_GRANTS`, claim 후 부분 소비·crash/만료는
  `RUNNING/START_RETRY_ATTEMPT`; 소비된 single-read grant 자동 재사용 금지
- DB 시각으로 고정한 grant 대기 deadline을 status에 노출하고 기한/attempt budget 초과는
  safe terminal `FAILED`; 대기 중 active capacity 유지
- attempt token/generation/version/input hash를 terminal payload에 고정
- completed descriptor와 FAILED status도 최종 attempt token/generation을 제공; `resultHash`는
  runtime attempt metadata를 제외하고 `payloadSha256`은 전송 bytes를 검증
- completed descriptor와 payload atomic visibility
- ack는 job/payload/token/generation/hash와 disposition을 검증; 삭제 후 stale payload는
  `DISCARDED_STALE`로만 acknowledge, 정상 장기 저장은 `PERSISTED`
- terminal job 갱신 금지; 허용된 FAILED retry는 새 Compute job/key와 Core `retryOf` dispatch,
  같은 ImportSession으로 연결
- ack idempotency와 terminal `PT24H` hard ceiling cleanup
- Core table write/read 금지와 payload/log redaction
