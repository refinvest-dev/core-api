# TRADING_DATA_LIFECYCLE.md — Trading Review Data Lifecycle Contract

이 문서는 Trading Review 데이터의 분류, 수집 목적, 저장, 접근, 보관, 삭제, backup과 복원 경계를
정의하는 상세 정본이다. 제품·도메인·기술 정책이며 법률 준수를 단정하거나 개인정보 처리방침을
대체하지 않는다. 도메인 불변식 요약은 [`DOMAIN.md`](DOMAIN.md), 결정과 이유는
[`DECISIONS.md`](DECISIONS.md)의 ADR-061, version compatibility·재처리·lineage는
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md), 구현 순서는 [`ROADMAP.md`](ROADMAP.md)를 따른다.

MVP 정책 식별자는 다음과 같다.

```text
dataPolicyVersion: TRADING_DATA_POLICY_V1
retentionPolicy: TRADING_RETENTION_V1
maskingPolicyVersion: SOURCE_EVIDENCE_MASK_V1
deletionPolicyVersion: TRADING_DELETION_V1
```

기간 표기는 고정 duration이다. `P7D`는 168시간, `PT24H`는 24시간이며 서버별 임의 설정값으로
바꾸지 않는다.

---

## 1. 원칙과 소유권

1. Core는 `TradingAccount`, `TradingBook`, import와 artifact metadata, canonical ledger, evidence와 장기
   분석 결과의 소유 서비스다. Compute는 필요한 최소 입력으로 normalization/reconstruction/analytics와
   제한된 runtime만 수행한다. Web은 Core만 호출한다.
2. 삭제 권리는 accepted record와 Revision의 immutability보다 우선한다. 삭제는 불변 데이터를 고쳐 쓰는
   작업이 아니라 해당 scope 전체의 접근을 먼저 차단하고 저장물을 purge하는 작업이다.
3. source fidelity와 evidence traceability는 영구 raw 보관을 뜻하지 않는다. raw가 없으면 retained
   snapshot임을 명시하고 원본 행이라고 부르지 않는다.
4. 다른 owner의 object, record, evidence나 결과를 저장 단계부터 공유하지 않는다. 같은 hash여도
   cross-user physical deduplication을 하지 않는다.
5. 삭제 요청 이후 생성된 결과와 stale callback은 저장하지 않는다. 상태와 실제 저장물의 존재 여부를
   숨기지 않는다.
6. 운영 로그, metric label, exception과 AI 경계는 거래값을 기본 거부하고 allowlist로만 연다.

## 2. 데이터 분류

분류는 가장 민감한 포함 field를 기준으로 한다. 암호화되어 있거나 pseudonym을 사용한다는 이유로 접근
통제를 낮추지 않는다.

| 등급 | field 예시 | 허용 사용 | 금지 또는 제한 |
|---|---|---|---|
| `RESTRICTED_FINANCIAL` | raw CSV bytes/row, raw UID, full Trade ID/Order ID, original filename, 사용자 ID와 거래값을 함께 담은 row | owner import, 검증, 명시적 재파싱, deletion worker | 일반 운영 조회·로그·metric label·public API·AI·support 기본 접근 금지 |
| `PSEUDONYMIZED_FINANCIAL` | account fingerprint, canonical Trade/Order identity, Symbol, quantity, price, fee, realized PnL, position time, masked evidence snapshot | owner review, 결정론적 복원/분석, 승인된 Compute job | pseudonym을 익명으로 간주하거나 운영 metadata로 분류 금지 |
| `DERIVED_ANALYTICS` | analysis result, aggregate Metric, Observation, Finding, population/exclusion과 evidence link | owner review와 동일 정의 재분석 | 다른 owner와 결합, 추천·인과 추론, 로그 payload 또는 AI 전달 금지 |
| `OPERATIONAL_METADATA` | opaque internal ID, artifact SHA-256, row count, version, error code, correlation ID, duration | 상태 운영, 중복 판정, audit, 장애 진단 | 원본 value, Symbol, 거래 시각·금액을 섞으면 이 등급을 유지할 수 없음 |
| `PUBLIC_REFERENCE` | 공개 product family 코드, 공개 dialect 이름, versioned schema 이름, 공개 instrument terms | 문서, validation과 계산 | 사용자 보유·거래 사실과 결합되면 pseudonymized financial로 재분류 |

`Symbol`과 거래·포지션 시각은 사용자의 투자 활동을 드러내므로 `PSEUDONYMIZED_FINANCIAL`이다. artifact
hash도 동일 파일의 재등장을 연결할 수 있어 `OPERATIONAL_METADATA` 중 restricted-access 값으로 다룬다.
`userId` 단독은 operational metadata일 수 있지만 거래 데이터와 결합된 레코드는 최소
`PSEUDONYMIZED_FINANCIAL`이다. source value를 포함한 free-form error message는 원본 등급을 상속하므로
저장하지 않고 safe error code/field name으로 치환한다.

## 3. 데이터 inventory와 기본 lifecycle

`Book lifecycle`은 `DeleteTradingBook`, 상위 `DeleteTradingAccount`/`DeleteMember` 또는 아래의 종속
삭제 전까지를 뜻한다. 사용자 조회 가능은 정상 owner API 기준이며 operator 권한을 뜻하지 않는다.

| 데이터 | 소유/저장 위치 | 민감정보·분류 | 목적 | 암호화 | 기본 보관/clock | 삭제 trigger·방식 | backup | owner 조회 | AI |
|---|---|---|---|---|---|---|---|---|---|
| `TradingAccount` | Core/Postgres | owner 연결, venue; pseudonymized | venue account container | 전송·저장 | account lifecycle | account/member 삭제 시 row purge | 예 | 예 | 아니오 |
| `TradingBook` | Core/Postgres | 상품·mode·settlement; pseudonymized | 독립 복원 경계 | 전송·저장 | Book lifecycle | Book/account/member 삭제 시 purge | 예 | 예 | 아니오 |
| `TradingImportSession` | Core/Postgres | owner, timezone, 상태; pseudonymized | 원자적 import/audit | 전송·저장 | accepted: Book lifecycle; rejected: `rejectedAt + P30D` | session/상위 삭제 또는 rejected TTL에 purge | 예 | 예 | 아니오 |
| `TradingSourceArtifact` | Core/Postgres | role, hash, 크기, 정책; operational | raw locator, 중복·retention 상태 | 전송·저장 | accepted: Book lifecycle; rejected: `rejectedAt + P30D` | session/상위 삭제; raw-only 삭제 시 최소 metadata 유지 | 예 | 예, filename 제외 | 아니오 |
| Raw CSV Object | Core 소유/object storage | raw bytes, UID, IDs, 거래값; restricted | 최초 normalize와 제한된 재파싱 | TLS + artifact별 envelope encryption | accepted `acceptedAt + P7D`; rejected `rejectedAt + PT24H`; non-terminal `uploadedAt + PT24H` | TTL/즉시 삭제로 object와 temporary copy purge, key access 제거 | application backup 제외 | inline 조회 없음; 만료와 재파싱 가능 여부만 표시 | 금지 |
| Artifact Metadata | Core/Postgres | hash, role, dialect, size, role별 observed coverage/timezone/precision/completeness; operational | provenance, duplicate 판정, lifecycle·coverage 증거 | 전송·저장 | 위 `TradingSourceArtifact`와 동일 | scope purge; filename은 raw와 함께 먼저 purge | 예 | 예 | 아니오 |
| `SourceEvidenceSnapshot` | Core/Postgres | allowlisted 거래값; pseudonymized | raw 삭제 후 evidence drill-down | 전송·저장, field encryption | accepted: Book lifecycle | session/Book/account/member 삭제 시 purge | 예 | 예 | 금지 |
| Canonical `TradingRecord` | Core/Postgres | fingerprint, IDs, symbol, 수량·가격·손익·시각; pseudonymized | ledger와 재분석 | 전송·저장, identifier field encryption | Book lifecycle | session 종속 revision 또는 상위 scope purge | 예 | 예, public 응답은 masking | 금지 |
| `LedgerRevision` | Core/Postgres | record 집합/hash/capability; pseudonymized | immutable 분석 입력 | 전송·저장 | Book lifecycle | 종속 session/Book/account/member 삭제 시 전체 revision purge | 예 | 예 | 금지 |
| `ReconciliationManifest` | Core-owned immutable object, Core/Postgres hash·pointer | allocation, 상태, financial scalar; pseudonymized | 복원 재현·quality | 전송·저장 | Revision lifecycle | Revision과 함께 purge | 예 | 요약/evidence로 예 | 금지 |
| `TradingAnalysisRun` | Core/Postgres | config, scope, 상태; derived | 분석 orchestration | 전송·저장 | Book lifecycle | revision/Book/account/member 삭제 시 purge | 예 | 예 | 금지 |
| `TradingAnalysisResult` | Core-owned immutable object, Core/Postgres status·hash·pointer | 결과와 evidence membership; derived | 장기 Review | 전송·저장 | Book lifecycle | run/revision/상위 scope purge | 예 | 예 | 금지 |
| `BehaviorObservation` | Core `TradingAnalysisResult` object | episode 관계·시각; derived | 관찰 사실 | 전송·저장 | result lifecycle | result와 함께 purge | 예 | 예 | 금지 |
| `BehaviorMetric` | Core `TradingAnalysisResult` object | 집계·표본·금액 비율; derived | 결정론적 지표 | 전송·저장 | result lifecycle | result와 함께 purge | 예 | 예 | 금지 |
| `BehaviorFinding` | Core `TradingAnalysisResult` object | 선택 결과·evidence; derived | 검토 대상 노출 | 전송·저장 | result lifecycle | result와 함께 purge | 예 | 예 | 금지 |
| Compute Runtime | Compute durable queue/encrypted scratch | 최소 job payload; 원 분류 상속 | 제한된 계산·retry | TLS + 저장 암호화 | terminal 후 최대 `PT24H`; 삭제 tombstone 수신 시 즉시 purge queue에 등록 | job cleanup/deletion cancellation | 장기 backup 금지 | 아니오 | 금지 |
| Operational Log | 중앙 log store | allowlisted metadata만; operational | 상태·성능·장애 진단 | 전송·저장 | event time `+ P30D` | 자동 TTL | 별도 backup 금지 | 아니오 | 금지 |
| Audit Event | append-only audit store | actor, scope ID, action; operational | 접근·정책·삭제 증거 | 전송·저장 | event time `+ P180D` | TTL purge; financial payload 금지 | 예, backup copy는 최대 `P30D` | 일반 조회 아님; 삭제 상태는 별도 노출 | 금지 |
| Deletion Request/ledger | primary control table + restore control plane | opaque scope ID, generation, 상태; operational | idempotency, restore suppression | 전송·저장 | 완료/복구 `+ P180D`; failed는 복구 전 만료 금지 | audit TTL 후 purge | restore control copy 예 | request 상태 예 | 금지 |
| Backup Copy | 격리된 backup vault | 포함 원본 분류 상속 | 재해 복구 | 전송·저장, 별도 key | snapshot 생성 후 최대 `P30D` | rolling expiry; tombstone replay 전 restore 차단 | 자기 자신 | 아니오 | 금지 |

Raw object는 application backup과 object version history에서 제외한다. provider의 복제본·삭제 지연은 object
삭제 SLA와 최종 backup 물리 제거 기한 안에 포함한다. canonical record와 derived result에는 자동 장기
보존 TTL을 두지 않고 Book lifecycle에 연결한다. MVP 출시 전 휴면·탈퇴 계정의 별도 법정/제품 보관 기간은
법률 검토 항목이다.

scheduled backup은 primary Core DB와 Core-owned long-lived immutable object를 포함하고 raw object,
Compute runtime, operational log는 제외한다. audit와 deletion control plane은 각 서비스의 위 보관 기간을
지키되 그 backup snapshot 자체는 30일을 넘기지 않는다.

## 4. Raw artifact 정책

### 4.1 저장과 retention clock

- 두 raw CSV는 Core가 관리하는 private object storage에 저장한다. Compute나 Web이 장기 복사본을 소유하지
  않는다.
- accepted artifact의 `rawExpiresAt = acceptedAt + P7D`, rejected artifact는
  `rawExpiresAt = rejectedAt + PT24H`다. terminal이 되지 않은 session은 `uploadedAt + PT24H`에 job을
  취소하고 accept하지 않은 채 raw를 삭제한다.
- 사용자는 어느 상태에서든 TTL 전 즉시 raw-only 삭제를 요청할 수 있다. 요청 순간 정상 접근과 새 job을
  차단하며 실제 purge는 §8 SLA를 따른다.
- accepted raw의 재파싱 가능 기간은 남은 `P7D` 안이다. rejected raw는 실패 조사나 동일 payload retry를
  위해 최대 `PT24H`만 둔다. job 사용 중이라는 이유로 `rawExpiresAt`을 연장하지 않는다.
- 동일 artifact 재업로드 판정에는 SHA-256과 owner/Book scope의 metadata면 충분하다. raw bytes를 보관하거나
  다른 owner object와 공유하지 않는다.
- artifact SHA-256은 업로드한 raw bytes 전체로 계산하고 암호화 전후 hash와 구분한다. hash만으로 bytes를
  복원하거나 동일성을 육안 증명할 수 있다고 표현하지 않는다.
- TTL 뒤에는 최소 artifact metadata, `SourceEvidenceSnapshot`, canonical ledger와 결과만 남는다. Adapter
  변경으로 raw가 필요한 재파싱은 불가능하며 사용자 재업로드가 필요하다.
- owner UI는 `rawExpiresAt`, 현재 retention status와 `rawReparseAvailable`을 표시한다.

7일은 import 직후 adapter 결함을 발견해 재파싱할 현실적인 창을 주면서 민감한 원본의 장기 노출을 피하는
MVP 기본값이다. rejected 파일은 유효한 장기 결과를 만들지 않으므로 진단·retry 창을 24시간으로 제한한다.
기간 변경은 임의 configuration이 아니라 새 policy version 결정이다.

### 4.2 암호화와 접근

1. client→Core, Core→object storage, Core→Compute는 인증된 암호화 전송을 사용한다.
2. raw는 artifact별 data encryption key(DEK)로 envelope encryption한다. DEK를 보호하는 key-encryption
   key는 KMS 또는 동등한 독립 key-management 경계에 둔다.
3. application source, image, 일반 configuration과 environment file에 encryption key를 저장하지 않는다.
   ciphertext metadata에는 non-secret `keyVersion`만 기록한다.
4. rotation은 새 object에 새 key version을 쓰고 보관 중 object의 DEK를 re-wrap한다. rotation 실패가
   plaintext fallback을 허용하지 않는다. stable identity HMAC key는 raw encryption key와 분리한다.
5. 삭제는 object 삭제와 DEK 접근 제거를 모두 시도한다. 어느 한쪽만 성공하면 `DELETION_PARTIALLY_COMPLETED`
   이며 완료가 아니다.
6. public object URL은 만들지 않는다. Compute에는 opaque path의 단일 artifact read만 허용하는 최대 5분
   수명의 signed access와 job identity를 준다. URL, path, filename은 log에 남기지 않는다.
7. storage path는 random opaque artifact ID만 사용하며 UID, Symbol, user ID, filename을 포함하지 않는다.

## 5. Raw 삭제 후 source evidence

MVP는 Option B인 allowlist 기반 `SourceEvidenceSnapshot`을 사용한다. Option A의 full raw line 장기 보관은
raw TTL을 무력화하므로 기각한다. Option C의 row/hash만으로는 사용자가 canonical 변환의 근거값을 확인할
수 없어 evidence traceability가 부족하다.

```text
SourceEvidenceSnapshot
├── artifactId
├── artifactRole
├── sourceRowNumber
├── sourceRecordKey             # public에 노출하지 않는 opaque reference
├── sourceRowHash
├── dialect
├── allowlistedFields
├── maskingPolicyVersion
├── capturedAt
└── rawAvailability: AVAILABLE | DELETED
```

### 5.1 allowlist와 masking

| source role | snapshot에 남기는 field | 처리 |
|---|---|---|
| Trade History | `Time`, `Symbol`, `Side`, `Price`, `Quantity`, `Amount`, fee amount/asset, `Realized Profit`, `Buyer`, `Maker` | exact source string과 scale을 보존; `Time`은 second precision local lexeme이며 owner evidence에 표시 가능 |
| Trade History | `Trade ID`, `Order ID` | 원문 대신 owner+artifact scope HMAC alias(`tid_…`, `oid_…`)만 snapshot에 저장·표시 |
| Trade History | `Uid` | snapshot에 저장하지 않음; account fingerprint나 raw UID도 표시하지 않음 |
| Position History | `Symbol`, `Margin Mode`, `Position Side`, `Entry Price`, `Avg. Close Price`, `Max Open Interest`, `Closed Vol.`, `Closing PNL`, `Opened`, `Closed`, `Status` | exact source string과 scale을 보존; `Opened`/`Closed`는 second precision local lexeme이며 owner evidence에 표시 가능 |
| unknown extra column | 없음 | schema fingerprint와 unknown-column count만 metadata에 남김 |

full Trade ID/Order ID는 암호화된 canonical record에서만 보존한다. Trade UID가 identity 처리에 필요할 때는
암호화된 raw/canonicalization boundary 안에서 fingerprint를 만든 뒤 raw 값을 retained snapshot, public
response나 log에 남기지 않는다. ID alias는 원문을 복구할 수 없는 별도 masking key의 HMAC이고 다른
owner/artifact 간 상관관계를 만들지 않는다. Symbol, quantity, price, fee, PnL과 시각은 owner에게는 evidence로
표시할 수 있지만 operator·log·AI에는 숨긴다.

### 5.2 source row hash

`sourceRowHash`는 물리 CSV line byte hash가 아니라 dialect-aware canonical row hash다.

1. pinned dialect의 encoding과 CSV quoting 규칙으로 row를 decode한다. BOM은 header에서만 dialect 규칙대로
   제거한다.
2. 각 cell은 CSV unescape 뒤의 exact string을 사용한다. trim, case change, decimal/time normalization을
   하지 않는다.
3. `{"dialect":...,"cells":[{"index":0,"header":...,"value":...},...]}` 형태의 UTF-8 canonical JSON을
   만든다. cell은 원래 column index 순이며 unknown column도 포함하고 object key는 Unicode code point
   순, 공백은 없다.
4. 그 bytes의 SHA-256 lowercase hex를 저장한다.

따라서 line ending이나 CSV quote 표현이 달라도 같은 decoded row hash가 될 수 있다. 물리 파일 동일성은
별도의 artifact SHA-256이 담당한다.

### 5.3 UI와 표현

- owner drill-down은 `SourceEvidenceSnapshot`과 그로부터 생성된 canonical record를 나란히 보여준다. raw
  bytes나 full raw line을 inline 표시하지 않는다.
- raw가 남아 있으면 “원본 파일 보관 중”, 삭제되었으면 “원본 파일 삭제됨 — 아래는 가져올 때 보존한
  evidence snapshot”으로 표시한다. 삭제 뒤 snapshot을 “원본 행”이라고 부르지 않는다.
- raw-only 삭제 후 기존 Review와 snapshot은 유지할 수 있다. 다만 original byte 확인과 Adapter reparse는
  불가능하다는 limitation을 결과에 표시한다.

## 6. 사용자 삭제 의미

모든 command는 owner 확인 뒤 Core가 처리한다. 정상 resource API는 요청 즉시 접근을 막고, 별도 deletion
request query만 상태를 제공한다.

| Command | 삭제 범위 | 유지 범위/결과 |
|---|---|---|
| `DeleteTradingSourceArtifact` | 지정 artifact의 raw object, temporary copy, original filename, raw DEK 접근 | artifact 최소 metadata/hash, snapshot, canonical record, Revision과 result 유지. evidence limitation 표시 |
| `DeleteTradingImportSession` | 두 raw, artifact metadata/snapshot, session이 기여한 canonical records, 그 session을 포함하는 모든 LedgerRevision/manifest와 그 분석 run/result | 같은 Book의 독립된 session/revision만 유지. latest pointer는 가장 최신 unaffected revision 또는 null |
| `DeleteTradingBook` | Book 아래 모든 session/artifact/raw/snapshot/record/revision/manifest/run/result와 Book | TradingAccount와 다른 Book 유지 |
| `DeleteTradingAccount` | Account 아래 모든 Book과 그 전체 Trading Review 데이터, Account | Member와 다른 Account 유지 |
| `DeleteMember` | 모든 Account/Book과 Trading Review 데이터; Member 삭제 orchestration의 일부 | Trading Review에는 아무것도 유지하지 않음. 다른 bounded context 삭제는 각 정본을 따름 |

accepted session 삭제가 기존 `LedgerRevision` 내용을 고쳐 쓰지는 않는다. 해당 session 또는 그 provenance가
합쳐진 canonical record를 포함한 revision 전체와 derived data를 삭제한다. descendant revision이 그 record를
포함하면 함께 삭제된다. 다른 session의 revision은 삭제 session/provenance와 transitive reference가 전혀
없을 때만 독립된 것으로 본다. raw-only 삭제는 그 반대로 ledger/result를 유지하며 evidence가 snapshot으로
제한됨을 명시한다.

같은 SHA-256을 가진 다른 owner의 artifact는 별도 object, DEK와 ownership scope를 갖는다. cross-user
deduplication 또는 aggregate를 이유로 어느 deletion command도 다른 owner 데이터를 참조하거나 삭제하지
않는다.

## 7. 삭제 상태 모델

### 7.1 ArtifactRetentionStatus

```text
ACTIVE → RETENTION_SCHEDULED → DELETION_PENDING → DELETED
ACTIVE → DELETION_PENDING
DELETION_PENDING → DELETION_FAILED
DELETION_FAILED → DELETED              # operator remediation 성공만 허용
```

- explicit delete는 `ACTIVE`에서 바로 `DELETION_PENDING`으로 간다.
- `DELETED`는 object 부재를 storage에서 확인하고 DEK 접근도 제거했을 때만 사용한다.
- `DELETION_FAILED`는 자동 retry budget이 끝난 terminal attempt다. operator remediation은 상태를 뒤로
  돌리지 않고 남은 purge만 수행해 `DELETED`로 전이한다.
- 상태 전이는 compare-and-set이고 같은 command는 같은 deletion request를 반환한다.

### 7.2 DeletionRequest

```text
REQUESTED → CANCELLING_JOBS → PURGING_PRIMARY → PURGING_OBJECTS → COMPLETED
       각 non-terminal 단계 ─────────────────────────────────────→ FAILED
```

request는 `scopeType`, opaque `scopeId`, owner, `deletionGeneration`, requested/started/completed time,
purge checklist, attempt count, last safe error code, `backupPurgeDueAt`과 purge에 필요한 opaque object locator를
가진다. retry는 현재 단계의 미완료 item만 idempotently 수행하고 상태를 되돌리지 않는다. bounded
exponential retry 뒤 `FAILED`는 terminal이다. operator는 같은 scope/generation과 남은 checklist를 참조하는
새 remediation request를 만들며, 원 request는 `FAILED`, remediation request는 `COMPLETED`로 audit에 남긴다.

`COMPLETED`는 primary DB와 live object/Compute runtime에서 scope가 제거되고 정상 API로 읽을 수 없다는
뜻이다. rolling backup의 물리 만료는 `backupPurgeDueAt`까지 별도로 추적한다. backup에 남았다는 이유로
normal access를 허용하지 않는다.

일부 storage만 삭제되면 접근 차단과 tombstone은 유지하고 `FAILED` 및
`DELETION_PARTIALLY_COMPLETED`를 표시한다. 성공으로 표시하지 않는다. 전 owner가 request ID로 상태를
조회할 수 있고, normal resource 조회는 tombstone 보관 중 owner에게 `410 Gone`, 다른 caller에게 항상
`404`다. tombstone 만료 뒤에는 모두 `404`다.

## 8. 삭제 SLA와 backup/restore

| 경계 | 확정 MVP 값 |
|---|---|
| 정상 접근 차단 | deletion request commit 즉시 |
| primary DB purge | 요청 후 24시간 이내 |
| live object와 Compute runtime purge | 요청 후 24시간 이내 |
| backup snapshot 보관 | 생성 시점부터 최대 30일 |
| 삭제 데이터의 backup 최종 물리 제거 | primary/object deletion 완료 후 최대 30일 |
| operational log | 30일 |
| audit/deletion ledger | 180일, financial payload 없음 |

SLA를 넘기거나 retry budget을 소진하면 request는 `FAILED`이고 alert를 발생시킨다. 사용자는 “즉시 접근
불가”와 `backupPurgeDueAt`의 “최종 물리 삭제 예정”을 별도로 본다. backup snapshot에서 개별 row 삭제가
불가능하면 snapshot을 수정하지 않고 30일 rolling expiry까지 격리한다.

backup은 production identity와 분리된 restore service만 접근한다. 사람의 접근은 §11 break-glass를
요구한다. restore 절차는 다음 순서를 지킨다.

1. 복원 DB/object를 network quarantine에 둔다. application traffic을 연결하지 않는다.
2. production backup과 별도인 restore control plane의 deletion ledger를 복원 시점까지 replay한다.
3. tombstoned scope와 더 낮은 deletion generation의 row/result를 purge하고 object denylist/DEK revocation을
   재적용한다.
4. 검증 보고서가 삭제 scope 0건을 확인한 뒤에만 traffic을 전환한다.

deletion ledger는 backup 최대 기간보다 긴 180일을 보관하므로 모든 복원 가능한 snapshot을 덮는다.
삭제된 데이터를 restore 과정에서 다시 활성화하지 않는다.

## 9. 동시 job 계약

삭제 요청 transaction은 scope의 `deletionGeneration`을 증가시키고 tombstone을 기록한 뒤 신규 import,
analysis와 retry admission을 차단한다. 모든 Core→Compute job과 callback은 제출 시 generation을 포함한다.
Core는 terminal result 저장 transaction에서 현재 generation과 tombstone을 다시 검사한다.

| 경쟁 상황 | 결정론적 처리 |
|---|---|
| normalization 중 raw-only 삭제 | 신규 read를 막고 job 취소를 요청한다. 결과는 discard하고 session은 lifecycle reason `SOURCE_ARTIFACT_DELETED`로 `REJECTED`된다. object/runtime purge 후 삭제 완료 |
| reconstruction 중 Book 삭제 | Book tombstone 후 job/queue retry 취소, callback discard, Book 전체 purge |
| analysis result 저장 직전 Account 삭제 | 같은 transaction의 generation check가 저장을 거절하고 `STALE_RESULT_AFTER_DELETION` audit만 남김 |
| retry queue에 job 존재 | tombstoned scope job은 claim/admission하지 않고 cancelled cleanup 대상으로 전환 |
| Compute가 payload를 이미 수신 | cancel marker를 확인하고 가능한 즉시 중단; 중단 불가 구간의 output도 Core가 저장하지 않으며 runtime은 24시간 안에 purge |
| 삭제 완료 뒤 terminal callback | 성공 응답으로 장기 저장하지 않고 stale로 acknowledge/discard; callback 재시도를 멈춤 |
| 삭제된 Revision 재분석 | Core에서 `410 Gone`; Compute job을 만들지 않음 |
| raw TTL과 reparse job 경쟁 | `rawExpiresAt` 이전에 끝날 보장이 없는 job도 TTL을 연장하지 않음. 만료 시 cancel/discard하고 재업로드 요구 |

삭제 작업 자체는 active job cancellation을 기다리느라 정상 접근 차단을 늦추지 않는다. cancellation 확인이
지연되면 `DELETION_BLOCKED_BY_ACTIVE_JOB`으로 retry하고, SLA 초과 시 실패를 표시한다.

## 10. Logging과 observability

### 10.1 값 allowlist/denylist

| 운영 log/metric | 허용 | 금지 |
|---|---|---|
| identity | internal artifact/import/run ID, correlation ID | raw UID, full account fingerprint, user ID와 거래값의 결합 |
| source | masked artifact role, dialect/version, row count | raw CSV row, source value, full filename, storage path/URL |
| 거래 | aggregate failure/exclusion count | Symbol, position time, Trade ID, Order ID, quantity, price, fee, realized PnL |
| 실행 | normalizer/reconstruction/metric version, status, duration, safe error code | request/response payload, signed URL, encryption metadata 중 secret |
| metrics label | bounded status/code/version | user/artifact/run ID, Symbol, filename 등 high-cardinality 또는 sensitive 값 |

Symbol은 production operational log에 남기지 않는다. 사용자 화면의 structured validation issue는 row number,
logical field name과 safe error code만 포함하고 offending value는 포함하지 않는다.

### 10.2 강제 위치

- provider adapter가 exception을 만들기 전에 source value 대신 typed safe error를 생성한다. raw row를
  exception message/object의 `toString`에 넣지 않는다.
- Core/Compute logging facade가 structured allowlist를 적용하고 중앙 sink가 UID/ID/filename/financial field
  denylist를 두 번째로 검사한다. redaction 실패는 원문 기록이 아니라 log event drop과 alert로 처리한다.
- stack trace는 허용하지만 parser exception을 wrapping해 row content, signed URL과 storage path를 제거한다.
- production debug logging은 기본 비활성이다. incident ticket, 승인, 최대 30분의 time-bound flag로만 켤 수
  있고 동일 redaction을 우회하거나 payload logging을 활성화할 수 없다.
- operator audit에는 operator identity, ticket, 승인자, scope의 opaque ID, 목적, 시작/종료와 action만 남긴다.
  거래값이나 snapshot을 audit payload에 복제하지 않는다.

## 11. 접근 통제

- owner만 자신의 Trading Review 데이터를 조회한다. ownership 실패는 resource 존재 여부를 노출하지 않는다.
- Web은 Core만 호출한다. Compute는 owner API를 제공하지 않고 job에 필요한 artifact/ledger 최소 payload만
  받는다.
- signed artifact access는 §4의 최대 5분, 단일 object/read-only/job-bound 정책을 따른다.
- operator의 거래 데이터 접근은 기본 차단한다. support가 반드시 필요한 경우 MFA, incident/support ticket,
  명시적 승인, 최소 scope와 최대 30분의 break-glass grant를 요구하고 모든 조회를 audit한다.
- cross-user deduplication, aggregate 분석이나 support 편의를 이유로 ownership을 공유하지 않는다.
- cache/search index/read model도 원본 scope의 tombstone과 deletion generation을 적용하며 별도 장기 복사본을
  만들지 않는다.

## 12. AI 데이터 경계

AI Summary/Query는 MVP 범위 밖이며 이 작업은 AI 기능을 설계하거나 구현하지 않는다. 별도 ADR, 사용자
동의와 provider retention/no-training 계약 확인 전에는 `PUBLIC_REFERENCE`를 포함해 Trading Review job의
어떤 데이터도 외부 AI provider에 전송하지 않는다.

향후 검토 시에도 raw CSV/row, raw UID, account fingerprint, full Trade ID/Order ID와 source row 전체는
금지한다. 후보 입력은 계산 완료된 Metric과 제한된 evidence summary뿐이며 explicit allowlist schema,
Symbol·금액 masking, 최소화된 period/count를 지원해야 한다. AI 응답 log에 금융 데이터를 저장하지 않고
사용자 데이터를 provider 모델 학습에 사용할 수 있다고 추정하지 않는다. 이 기본 금지 경계를 바꾸려면
별도 ADR과 명시적 consent가 필요하다.

## 13. Reprocessing과 policy version

### 13.1 재처리 경계

- Adapter reparse는 raw가 `ACTIVE`이고 `now < rawExpiresAt`일 때만 가능하다.
- raw 삭제/만료 뒤에는 재업로드가 필요하다. canonical `TradingRecord`와 pinned manifest만 필요한 재분석은
  retained Revision으로 가능하며 raw reparse와 구분한다.
- reparse 중 raw가 만료되면 TTL을 연장하지 않고 §9에 따라 취소·discard한다.
- raw를 사용하는 중이라는 이유로 TTL을 무기한 또는 암묵적으로 연장하지 않는다.
- normalization/versioning과 전체 reprocessing 의미는 ADR-062와
  [`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)의 확정 계약을 따른다.
- 전체 version taxonomy, input availability, 재처리 Aggregate/type/idempotency와 publication은
  [`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)를 따른다. 이 문서의 deletion generation/tombstone이
  재처리보다 우선한다.

### 13.2 import에 고정하는 policy

```text
dataPolicyVersion
retentionPolicy
maskingPolicyVersion
rawExpiresAt
evidenceRetention
deletionPolicyVersion
```

`dataPolicyVersion`과 위 필드를 고정하지 않은 artifact/session은 `ACCEPTED`가 될 수 없다. 정책 변경은 다음
규칙을 따른다.

| 변경 | 기존 import | 신규 import |
|---|---|---|
| retention 기간 연장 | 소급 연장 금지 | 새 version부터 적용 |
| retention 기간 단축 | 자동 소급 금지. 보안 incident 등 별도 migration 결정이 있으면 override version과 audit를 기록 | 새 version부터 적용 |
| masking/redaction 강화 | owner evidence 의미를 깨지 않는 범위에서 즉시 전체 read/log path에 적용 | 새 version pin |
| 삭제 권리·access 차단 강화 | 즉시 모든 version에 적용 | 새 version pin |
| evidence allowlist 의미 변경 | 기존 snapshot을 조용히 rewrite하지 않음; migration/version 명시 | 새 version부터 적용 |

사용자의 explicit deletion은 pinned retention보다 항상 우선한다. 과거 artifact의 `rawExpiresAt`을 policy
변경으로 늦추지 않는다.

## 14. Failure taxonomy

모든 사용자 메시지는 safe error code와 상태만 전달하며 storage path, filename, source value를 포함하지 않는다.

| 오류 | 발생 조건 | 사용자 메시지 의미 | retry | Import/Analysis 영향 | 사용자 데이터 접근 | log / alert |
|---|---|---|---|---|---|---|
| `ARTIFACT_ENCRYPTION_FAILED` | plaintext 수신 뒤 암호화 완료 실패 | 파일을 안전하게 저장하지 못함 | 자동 bounded retry; 재업로드 가능 | import 시작 안 함 | plaintext 정상 조회 불가, temporary copy 즉시 purge | artifact/session ID, code; 즉시 alert |
| `ARTIFACT_STORAGE_FAILED` | encrypted object write/verify 실패 | 업로드 저장 실패 | 예 | import 시작 안 함 | incomplete object 비공개·cleanup | ID, code, attempt; 반복 시 alert |
| `ARTIFACT_ACCESS_DENIED` | signed grant/ownership/key version 검증 실패 | 파일에 안전하게 접근할 수 없음 | grant 갱신 또는 권한 수정 후 가능 | job 실패/중단 | 정상 API 원칙 유지 | ID, job ID, code; 반복 시 alert |
| `ARTIFACT_DELETION_FAILED` | live object 또는 DEK 접근 제거 실패 | 원본 삭제가 아직 완료되지 않음 | 자동/운영 retry | 신규 job 차단 | 즉시 정상 접근 차단 | deletion ID, storage class, code; alert |
| `DERIVED_DATA_DELETION_FAILED` | DB/read model/result purge 실패 | 파생 데이터 삭제가 아직 완료되지 않음 | 자동/운영 retry | 신규 import/analysis 차단 | scope 접근 차단 | deletion ID, component, code; alert |
| `DELETION_PARTIALLY_COMPLETED` | purge checklist 일부만 성공 | 일부 저장 위치 삭제가 남음 | 미완료 item만 retry | scope job 차단 | 전체 scope 접근 차단 | completed component names만; 즉시 alert |
| `DELETION_BLOCKED_BY_ACTIVE_JOB` | cancel acknowledgement가 지연됨 | 실행 중 작업 정리 후 삭제 계속 | 예 | active output discard | scope 접근 차단 | deletion/job opaque ID, state; SLA 근접 시 alert |
| `STALE_RESULT_AFTER_DELETION` | 낮은 generation callback/result 도착 | 삭제된 범위 결과를 저장하지 않음 | callback retry 금지 | result discard | 없음 | run ID, generation, code; rate threshold alert |
| `BACKUP_PURGE_PENDING` | live purge 완료, backup expiry 전 | 즉시 접근은 불가하며 backup 최종 만료 대기 | 작업 retry 아님; expiry 추적 | 없음 | 정상/restore 접근 불가 | deletion ID, dueAt; overdue만 alert |

암호화 실패 때 plaintext temporary bytes는 request scope를 벗어나지 않으며 cleanup 확인 전 success를 반환하지
않는다.

## 15. Lifecycle 불변식

기존 `TR-I01`~`TR-I13`의 의미를 바꾸지 않고 다음을 추가한다.

- `TR-I14 Deletion Scope Isolation`: deletion은 요청 owner/scope에 속한 데이터만 대상으로 하며 같은 hash나
  다른 owner의 artifact/evidence를 결합하거나 삭제하지 않는다.
- `TR-I15 Immediate Revocation`: deletion request commit 뒤 해당 scope는 정상 API, signed access, 신규 job과
  retry에서 즉시 접근 불가다.
- `TR-I16 Stale Result Rejection`: tombstone/deletion generation보다 오래된 Compute 결과는 장기 저장하지
  않는다.
- `TR-I17 Honest Retained Evidence`: raw TTL 뒤 evidence는 versioned allowlist snapshot임을 표시하며 raw
  row라고 표현하지 않는다.
- `TR-I18 Sensitive Observability Boundary`: raw UID, source row, Symbol과 거래값을 operational log/metric
  label에 기록하지 않는다.
- `TR-I19 Truthful Deletion Status`: 일부 purge나 terminal failure를 `DELETED`/`COMPLETED`로 표시하지 않는다.
- `TR-I20 Restore Non-Resurrection`: backup restore는 deletion ledger를 replay하기 전 서비스를 열지 않으며
  삭제된 데이터를 재활성화하지 않는다.
- `TR-I21 Pinned Data Policy`: `dataPolicyVersion`과 계산된 retention/masking/deletion 필드 없이 import를
  accept하지 않는다.
- `TR-I22 Immutable Purge Semantics`: accepted Ledger 내용을 수정해 source를 빼지 않고 종속 Revision과 결과
  전체의 접근 제거·purge로 삭제한다.
- `TR-I23 Storage-State Consistency`: `DELETED` artifact에는 readable live object나 usable DEK가 없고, object가
  남았으면 `DELETION_PENDING` 또는 `DELETION_FAILED`다.
- `TR-I24 Requested-Scope Equivalence`: command가 약속한 deletion scope와 purge checklist의 실제 scope가
  정확히 일치하고 사용자에게 표시된다.

재처리와 lifecycle의 결합 불변식 `TR-I25`~`TR-I38`은 [`DOMAIN.md`](DOMAIN.md)에 요약하며 상세 계약은
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)를 따른다. 특히 삭제 요청 commit 뒤 신규 재처리와 retry를
차단하고 낮은 generation output을 저장하지 않는 `TR-I30`은 위 `TR-I15`/`TR-I16`을 완화하지 않는다.

## 16. Scenario expected results

| # / 상황 | initial state | command/trigger | transition | 삭제 / 유지 | owner 조회 | retry | audit evidence | terminal |
|---|---|---|---|---|---|---|---|---|
| 1 accepted raw TTL | accepted, raw active | `acceptedAt+P7D` | scheduled→pending | raw/filename/DEK 삭제; metadata/snapshot/ledger/result 유지 | Review 가능, raw deleted·reparse 불가 표시 | object 실패 시 예 | policy/version, due/actual time | artifact `DELETED` |
| 2 rejected raw TTL | rejected, raw active | `rejectedAt+PT24H` | scheduled→pending | raw 삭제; safe failure metadata는 30일 유지 | rejected 상태, raw 없음 | 예 | session/artifact ID, code | artifact `DELETED` |
| 3 즉시 raw 삭제 | active raw | `DeleteTradingSourceArtifact` | active→pending | raw만 삭제; derived 유지 | snapshot Review, limitation 표시 | idempotent | owner, generation, checklist | `COMPLETED` + artifact `DELETED` |
| 4 raw 삭제 후 Review | raw deleted, result retained | `GetTradingReview` | 변화 없음 | 없음 | snapshot+canonical 표시, raw 원본 표현 금지 | 해당 없음 | read audit는 일반 정책 | 정상 조회 |
| 5 raw 삭제 후 reparse | raw deleted | Adapter reparse | admission reject | 없음 | 재업로드 필요 | 같은 요청 retry 불가 | safe rejection code | `410 Gone` |
| 6 Book 삭제 | active Book | `DeleteTradingBook` | requested→purge | Book 아래 전체 live data 삭제 | pending/failed/completed 상태만 | 예 | scope count/checklist | deletion `COMPLETED` |
| 7 Account 삭제 | 여러 Book | `DeleteTradingAccount` | requested→purge | Account와 모든 Book subtree 삭제 | 동일 | 예 | 각 child purge count | `COMPLETED` |
| 8 Member 삭제 | 여러 Account | `DeleteMember` | member orchestration→purge | Trading Review 전체 삭제 | request 상태만 | 예 | bounded-context completion | `COMPLETED` |
| 9 normalization 중 삭제 | job running/raw read | raw 또는 상위 삭제 | cancelling jobs→purge | runtime/raw 또는 scope 삭제, output discard | deletion pending | cancel/purge 예 | job/generation/cancel result | 완료 또는 명시적 실패 |
| 10 analysis 중 삭제 | analysis running | Book/account 삭제 | tombstone→cancel→purge | run/result와 scope 삭제 | deletion pending | 예 | run/generation | 완료 또는 실패 |
| 11 동일 삭제 재시도 | request non-terminal/terminal | 같은 idempotency key | 기존 request 반환 | 중복 scope 생성 없음 | 같은 상태 | 미완료 item만 | attempt count | 기존 terminal |
| 12 object 삭제 실패 | DB 접근 차단, object 남음 | delete worker | pending→failed | object/DEK 미완료; 이미 purge한 DB component는 되돌리지 않음 | 실패와 지원 상태 표시 | 예 | safe provider code, component | `FAILED`, 성공 표시 금지 |
| 13 DB 성공/object 실패 | primary purge 완료 | object purge 실패 | purging objects→failed | DB 삭제; object는 deny+retry | 정상 조회 불가 | object만 예 | partial checklist | `DELETION_PARTIALLY_COMPLETED` |
| 14 늦은 callback | deletion generation 증가 | terminal callback | stale reject/discard | callback payload/runtime purge | 결과 없음 | callback retry 금지 | old/current generation | `STALE_RESULT_AFTER_DELETION` |
| 15 backup restore | 삭제 scope 포함 snapshot | disaster restore | quarantine→replay→verify | tombstoned row 재삭제 | 검증 전 서비스 없음 | restore 재시도 예 | replay/verification report | 삭제 scope 0건 후 open |
| 16 policy version 변경 | V1 artifacts 존재 | V2 발행 | 기존 pin 유지 | 기존 TTL 연장 없음; 신규만 V2 | 각 import version 표시 | 해당 없음 | policy publication/migration | 각 version 유지 |
| 17 snapshot 조회 | raw deleted, snapshot retained | evidence query | 변화 없음 | 없음 | allowlist+masked ID+raw deleted 표시 | 해당 없음 | owner authorization | 정상 조회 |
| 18 다른 owner 동일 hash | owner별 object/DEK | 한 owner 삭제 | 해당 owner만 purge | 다른 owner data 유지 | 각 owner 자기 데이터만 | 예 | scope isolation check | 요청 scope만 완료 |
| 19 operator support | 정상 데이터 | support request | break-glass grant | 삭제 없음 | owner 동작 변화 없음 | 재승인 필요 | ticket/승인/scope/time/action | grant 만료 |
| 20 AI raw 접근 요청 | AI 미지원 | raw/context 요청 | policy reject | 전송 없음 | 기능 미지원 | 별도 ADR 전 불가 | rejection code, 금융 payload 없음 | 거절 |

## 17. 확정값, configuration과 open questions

### 확정된 MVP 기본값

- accepted raw `P7D`, rejected raw `PT24H`, non-terminal raw `PT24H`
- raw application backup/versioning 제외, Option B evidence snapshot
- operational log `P30D`, audit/deletion ledger `P180D`, backup `P30D`
- 접근 즉시 차단, primary/object purge 24시간 SLA, backup 최종 물리 제거 30일
- artifact별 DEK, 최대 5분 signed read, operator 최대 30분 break-glass
- raw-only 삭제와 session/Book/account/member cascade 의미

### 운영 configuration

queue retry 횟수, exponential backoff 간격, alert threshold와 storage adapter의 provider-specific key 이름은
운영 configuration이다. 이 값은 24시간 삭제 SLA, retention 기간, 상태 의미나 redaction allowlist를 바꿀 수
없다.

### 법률 검토 필요

- 출시 지역별 금융 거래기록·개인정보의 필수 보관 또는 즉시 삭제 의무
- 세무·분쟁·보안 audit에 필요한 최소 항목과 180일 기간의 적절성
- backup의 삭제 고지 문구와 data processor/subprocessor 계약
- Member 탈퇴, 미성년자, 법적 보존 요청(legal hold)이 필요한지와 그 권한·고지·만료 절차

법률 검토 전에는 legal hold나 장기 보존을 구현자 재량으로 추가하지 않는다.

### 후속 ADR 대상

- ADR-062에서 확정한 versioning/reprocessing 계약의 persistence/OpenAPI/worker 구현
- persistence schema, OpenAPI, deletion worker와 provider/KMS 선택
- AI 도입 시 별도 data-boundary/consent ADR
