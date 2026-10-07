# TRADING_VERSIONING.md — Trading Review Versioning & Reprocessing Contract

이 문서는 Trading Review의 version compatibility, 재처리, 결과 lineage와 publication의 상세 정본이다.
제품·Aggregate 경계는 [`TRADING_REVIEW.md`](TRADING_REVIEW.md), 복원 계산은
[`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md), Metric 계산은
[`TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md), 보관·삭제는
[`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md)를 따른다. 요약 불변식은
[`DOMAIN.md`](DOMAIN.md), 결정과 이유는 [`DECISIONS.md`](DECISIONS.md)의 ADR-062가 정본이다.

이 계약은 persistence schema, OpenAPI, queue topology, worker 구현, 과거 binary 보관 인프라와 과금 정책을
정하지 않는다. `VersionSet`, hash payload와 상태는 논리 계약이며 구현 transport나 테이블 모양을 뜻하지
않는다.

---

## 1. 원칙과 책임

1. accepted canonical record, `LedgerRevision`, 완료된 `TradingAnalysisResult`는 수정하지 않는다.
2. source/normalization/reconstruction/reconciliation/terms 변경은 새 `LedgerRevision`, Metric/Finding/config
   변경은 기존 Revision을 입력으로 새 `TradingAnalysisRun`을 만든다. 한 요청이 둘 다 요구하면 둘 다 새로
   만든다.
3. 재처리는 기존 `TradingImportSession`이나 `TradingAnalysisRun`을 다시 열지 않고 Core 소유
   `TradingReprocessingRun`으로 추적한다.
4. Core는 admission, ownership, 삭제 세대, idempotency, lineage, immutable 장기 결과와 latest pointer를
   소유한다. Compute는 요청에 고정된 버전의 compatibility를 검증하고 계산하며 장기 제품 상태를 소유하지
   않는다.
5. Raw CSV 삭제 후 `SourceEvidenceSnapshot`은 사용자 근거 조회용 allowlist snapshot이지 Adapter 입력의
   byte-equivalent 대체물이 아니다.
6. 배포만으로 기존 결과를 자동 교체하지 않는다. 새 결과가 완전히 검증되어 `COMPLETED`되기 전에는 기존
   Review와 latest pointer를 유지한다.
7. 과거 결과 재현과 최신 정의 재계산은 별도 기능이다. implementation이 없어 replay할 수 없어도 보관 중인
   결과와 version metadata는 조회 가능하다.

## 2. Version Taxonomy

`version`은 의미 정의와 구현 artifact를 구분한다. 하나의 `analyticsVersion`으로 아래 경계를 숨기지 않는다.

| Version | 의미 | 소유 서비스 | 변경 조건 | 입력 compatibility | 결과 영향 | 재처리 | 새 LedgerRevision | 새 AnalysisRun | 과거 조회 | 폐기 |
|---|---|---|---|---|---|---|---:|---:|---|---|
| `sourceDialectVersion` | source bytes/header/value의 해석 계약 | Compute 정의, Core pin | provider export 문법·의미 변경 | Adapter registry가 exact 지원 선언 | parse 가능성과 canonical input | raw reparse | 예 | 필요 시 예 | 영향 없음 | 신규 실행 중단 가능, metadata 유지 |
| `adapterVersion` | dialect detection/parsing/mapping 정의 | Compute | parser mapping 또는 validation 의미 변경 | 지원 dialect 목록과 exact match | canonical record/provenance | raw reparse | 예 | 필요 시 예 | 영향 없음 | 동일 |
| `normalizerVersion` | parsed field를 canonical record로 만드는 의미 | Compute | field normalization/identity/분해 규칙 변경 | dialect와 target canonical schema 명시 | canonical record/content hash | 보통 raw 필요 | 예 | 필요 시 예 | 영향 없음 | 동일 |
| `canonicalSchemaVersion` | canonical `TradingRecord` logical schema | Core 계약, Compute producer | field 의미·필수성·serialization 변경 | reconstruction별 지원 범위 | ledger content/capability | migration 또는 raw reparse | 예 | 필요 시 예 | 영향 없음 | 읽기 decoder 유지 또는 opaque payload 조회 |
| `instrumentTermsVersion` | step/tick/multiplier/settlement precision 의미 | Core pin, Compute 사용 | terms 값·출처·해석 변경 | product/policy가 exact 지원 | allocation/tolerance/notional | canonical에서 rebuild | 예 | 필요 시 예 | 영향 없음 | 참조가 남으면 삭제 금지, unavailable 표시 |
| `reconstructionPolicyVersion` | record→episode/allocation 규칙 | Compute | state transition, eligibility, allocation 변경 | canonical schema/terms 명시 | ReviewUnit·capability·manifest | rebuild | 예 | 필요 시 예 | 영향 없음 | 신규 실행 중단 가능 |
| `reconciliationVersion` | candidate/tolerance/match/status 규칙 | Compute | matching 또는 tolerance 의미 변경 | reconstruction output/schema 명시 | manifest/eligible population | rebuild | 예 | 필요 시 예 | 영향 없음 | 신규 실행 중단 가능 |
| `reviewUnitSchemaVersion` | analytics 입력인 ReviewUnit logical schema | Core 계약, Compute producer/consumer | field 또는 의미 변경 | reconstruction producer와 Metric consumer 명시 | Metric input/evidence | rebuild 또는 호환 시 analytics | 조건부 | 예 | 영향 없음 | reader/metadata 유지 |
| `metricDefinitionVersion` | population, capability, relation, formula 의미 | Compute | 계산 의미 변경 | ReviewUnit schema/capability exact range | Metric 값·status·evidence | analytics rerun | 아니오 | 예 | 영향 없음 | 신규 실행 중단 가능 |
| `findingRuleSetVersion` | Metric에서 Finding을 선택·서술하는 규칙 | Compute | gate/rank/template 의미 변경 | Metric ID/version 명시 | Finding 집합·설명 | finding reevaluation | 아니오 | 예 | 영향 없음 | 신규 실행 중단 가능 |
| `analysisConfigVersion` | config schema/default 의미 | Core 계약, Compute consumer | config field/schema/default 변경 | Metric/Finding 지원 schema 명시 | period/threshold/outcome 비교 | analytics rerun | 아니오 | 예 | 영향 없음 | config payload와 hash 유지 |
| `evidenceSchemaVersion` | result→ReviewUnit→record evidence 구조 | Core 계약, Compute producer | membership/provenance schema 변경 | producer/reader 지원 범위 | evidence artifact/hash | 영향 단계 재실행 | 조건부 | 예 | 기존 evidence 조회는 pinned reader 범위 | reader 없으면 opaque metadata 조회 |
| `dataPolicyVersion` | retention/masking/deletion 정책 묶음 | Core | lifecycle 의미 변경 | 모든 import에 exact pin | lifecycle/access, 계산값 아님 | 계산 재실행 안 함 | 아니오 | 아니오 | 강화 정책 제외 기존 pin 표시 | ADR-061에 따름 |

`필요 시 예`는 새 Revision이 만들어졌거나 사용자가 새 ledger 결과까지 요청할 때 그 Revision을 대상으로 새
AnalysisRun을 생성한다는 뜻이다. 새 Revision만 만드는 `REBUILD_LEDGER`는 분석 없이 완료할 수 있다.

Binance Position timestamp를 기존 분 구간 의미에서 second-precision exact instant로 교정하고 physical dialect,
artifact coverage와 lossless symbol identity를 확정한 변경은 단순 문구 수정이 아니다. 구현 registry에서는
source dialect/adapter/normalizer/canonical schema/reconstruction/reconciliation의 새 compatible set을 명시하고
기존 accepted Revision을 in-place 수정하지 않는다. OpenAPI `0.3.0`은 이 새 payload 의미를 표현한다.

### 2.1 Version identifier

- 사용자와 reviewer가 의미를 구분해야 하는 definition/schema/policy는 immutable 명시 버전 ID를 사용한다.
  예: `BINANCE_USDS_TRADE_HISTORY_V1`, `CANONICAL_TRADING_RECORD_V1`, metric definition `1`.
- Semantic Version는 사람에게 공개하는 bundle의 호환성 약속이 실제로 정의된 경우에만 보조 표기로 사용할 수
  있다. `major/minor/patch`만 보고 단계 간 compatibility를 추론하지 않고 registry 선언을 정본으로 삼는다.
- 실행 구현은 content-addressed `implementationDigest`를 기록한다. 배포 환경에서는 container image digest
  같은 immutable build digest를 사용하며 mutable tag나 source commit만 사용하지 않는다.
- CI의 immutable build ID는 진단·artifact lookup metadata로 함께 기록할 수 있지만 digest를 대신하지 않는다.
- source commit은 진단 metadata로 추가할 수 있지만 runtime version의 유일한 식별자가 아니다.
- `AnalysisConfig`와 합성 `VersionSet`은 아래 canonical serialization의 SHA-256 hash로 식별한다.
- rule/schema ID와 version은 content hash로 대체하지 않는다. ID/version은 의미, hash는 payload 동일성을
  설명한다.

```text
NormalizerVersion
├── sourceDialectVersionByArtifactRole
├── adapterVersion
├── normalizerVersion
├── canonicalSchemaVersion
└── implementationDigest

ReconstructionVersion
├── instrumentTermsVersionByInstrument
├── reconstructionPolicyVersion
├── reconciliationVersion
├── reviewUnitSchemaVersion
├── evidenceSchemaVersion
└── implementationDigest

AnalyticsVersion
├── metricDefinitionVersionsByMetricId
├── findingRuleSetVersion
├── analysisConfigVersion
├── analysisConfigHash
├── evidenceSchemaVersion
└── implementationDigest
```

`TargetVersionSet`은 요청 시 위 applicable field를 전부 명시한다. `latest`, 빈 값, mutable image tag는 저장된
target이 될 수 없다. admission에서 registry alias를 exact immutable ID로 resolve한 뒤 그 값을 run에 고정한다.

## 3. Version Registry와 Availability

Core가 사용자·lineage 상태를 소유하고 Compute가 실행 가능 구현과 compatibility manifest를 제공한다. Core의
registry snapshot은 실행 admission 때 고정되며 registry 변화가 이미 시작한 run의 target을 바꾸지 않는다.

| 상태 | 신규 실행 | 기존 결과 조회 | 동일 version replay | 최신 version 재처리 | 사용자 안내 | 운영 조치 |
|---|---|---|---|---|---|---|
| `ACTIVE` | 가능 | 가능 | 입력이 있으면 가능 | 가능 | 현재 지원 | 없음 |
| `DEPRECATED` | 명시적 pin/승인 시 가능, default 선택 금지 | 가능 | 구현·입력이 있으면 가능 | ACTIVE target 권고 | 지원 종료 예정과 대안 표시 | retirement plan 필요 |
| `READ_ONLY` | 불가 | 가능 | 불가 | 호환되는 ACTIVE target으로만 가능 | 과거 결과 조회 전용 | migration은 별도 plan |
| `UNAVAILABLE` | 불가 | 가능 | 불가, `VERSION_UNAVAILABLE` | 입력·target compatibility가 있으면 가능 | 구현 부재, 결과 자체는 유효할 수 있음 | artifact 복구를 가정하지 않음 |

과거 binary를 영구 보관한다고 가정하지 않는다. availability는 version component별이며 합성 VersionSet은 한
필수 component라도 실행 불가하면 그 실행에 대해 불가다. `TARGET_VERSION_RETIRED`는 registry가 target을
`READ_ONLY`로 바꾼 뒤의 신규 요청, `VERSION_UNAVAILABLE`은 구현이나 terms 자체가 없어 실행할 수 없는 경우다.

## 4. Compatibility Contract

| From → To | producer/consumer 선언 | 허용 | unknown field | forward compatibility | 불일치 failure |
|---|---|---|---|---|---|
| source dialect → Adapter | Adapter가 artifact role별 dialect exact ID 목록 선언 | 선언된 ID만 | dialect가 허용한 extra column만 warning+fingerprint | 기본 금지 | `VERSION_INCOMPATIBLE` 또는 import schema code |
| Adapter → normalizer | Adapter output schema와 normalizer input schema exact/range | registry에 명시된 조합 | schema가 ignorable로 선언한 field만 | 기본 금지 | `VERSION_INCOMPATIBLE` |
| normalizer → canonical schema | normalizer가 생성 schema exact ID 선언 | exact producer contract | 보존/무시 정책이 schema에 명시된 field만 | 암묵 허용 금지 | `CANONICAL_SCHEMA_INCOMPATIBLE` |
| canonical schema → reconstruction | policy가 지원 schema ID 목록 선언 | exact listed schema | 계산 의미에 영향 없는 field만 ignore 가능 | 기본 금지 | `CANONICAL_SCHEMA_INCOMPATIBLE` |
| reconstruction → reconciliation | episode/allocation schema와 reconciliation version 조합 선언 | registry 조합만 | 금지, extension point 명시 시만 허용 | 기본 금지 | `VERSION_INCOMPATIBLE` |
| reconciliation → ReviewUnit schema | producer schema exact ID 선언 | exact listed schema | evidence-preserving extension만 선언적으로 허용 | 기본 금지 | `VERSION_INCOMPATIBLE` |
| ReviewUnit schema/capability → Metric | Metric별 required schema IDs와 capability set 선언 | 둘 다 충족 | Metric 의미와 무관하다고 선언된 field만 | 기본 금지 | schema는 `VERSION_INCOMPATIBLE`, capability는 기존 `MISSING_CAPABILITY` |
| Metric → Finding rule | rule이 metric ID와 definition version set 선언 | 모두 exact match | 알 수 없는 Metric을 자동 포함하지 않음 | 금지 | `VERSION_INCOMPATIBLE` |
| result → evidence schema | result producer와 reader가 schema ID 선언 | exact 또는 registry의 lossless reader | 알 수 없는 evidence member를 버리고 성공 금지 | 기본 금지 | `VERSION_INCOMPATIBLE` |

호환되지 않는 입력을 자동 변환하거나 가장 가까운 version으로 실행하지 않는다. 변환이 필요하면 registry에
source/target과 lossless 여부가 등록된 명시적 migration을 실행하며 새 Revision/Run과 lineage를 만든다.

## 5. Change Impact Matrix

| 변경 | Raw 필요 | 새 LedgerRevision | 새 AnalysisRun | 기존 결과 |
|---|---:|---:|---:|---|
| source dialect/Adapter | 원칙적으로 필요 | 예 | 새 ledger를 분석할 때 예 | 보존 |
| normalizer | 원칙적으로 필요 | 예 | 새 ledger를 분석할 때 예 | 보존 |
| canonical schema | migration 가능 시 canonical, 아니면 Raw | 예 | 새 ledger를 분석할 때 예 | 보존 |
| instrument terms | canonical record + 새 pinned terms | 예 | 새 ledger를 분석할 때 예 | 보존 |
| reconstruction policy | canonical record | 예 | 새 ledger를 분석할 때 예 | 보존 |
| reconciliation rule | canonical record | 예 | 새 ledger를 분석할 때 예 | 보존 |
| Metric definition | 불필요 | 아니오 | 예 | 보존 |
| Finding rule | 불필요 | 아니오 | 예 | 보존 |
| AnalysisConfig | 불필요 | 아니오 | 예 | 보존 |
| data policy | 불필요 | 아니오 | 아니오 | lifecycle 정책 적용 |

예외는 다음과 같이 닫는다.

- Adapter/normalizer 결과가 byte-for-byte 같을 것이라는 선언만으로 기존 Revision을 재사용하지 않는다. 변경을
  검증하려면 raw reparse와 새 Revision을 만든다. raw가 없으면 그 검증은 불가능하다.
- canonical migration은 registry에 등록된 결정론적·lossless migration이고 source provenance/evidence를
  보존할 때만 raw를 대신한다. in-place migration은 금지한다.
- reconstruction/reconciliation은 canonical record만으로 충분해야 하지만 target policy가 더 새 canonical
  field나 instrument terms를 요구하면 `CANONICAL_SCHEMA_INCOMPATIBLE` 또는
  `INSTRUMENT_TERMS_UNAVAILABLE`로 실패한다.
- Finding-only 재평가도 immutable result boundary와 evidence hash를 보존하기 위해 새 AnalysisRun을 만든다.
- 보안·삭제 강화는 version pin보다 우선할 수 있지만 계산 result를 rewrite하지 않고 접근/보관에 적용한다.

## 6. TradingReprocessingRun Aggregate

```text
TradingReprocessingRun
├── id
├── ownerId
├── tradingBookId
├── sourceLedgerRevisionId
├── sourceAnalysisRunId?
├── type
├── trigger
├── sourceVersionSet
├── targetVersionSet
├── reasonCode                         # 자유형 금융 payload 금지
├── initiatedBy
├── inputAvailability
├── deletionGeneration
├── idempotencyKey
├── status
├── attempt
├── createdLedgerRevisionId?
├── createdAnalysisRunId?
├── differenceSummary?
├── failure?
├── createdAt
└── completedAt?
```

Core가 소유한다. `sourceAnalysisRunId`는 analytics/finding rerun이나 diff 대상이 있을 때만 필요하다. 한 run은
exact source/target/type을 고정하며 target 변경은 새 run이다.

```text
PENDING → VALIDATING_INPUT → RUNNING → COMPLETED
        └───────────────────────→ FAILED
        └───────────────────────→ CANCELLED
```

- `COMPLETED`, `FAILED`, `CANCELLED`는 terminal이며 다시 열지 않는다.
- retry는 같은 logical idempotency key와 증가한 attempt로 수행한다. 성공 결과가 있으면 그 결과를 반환하고,
  terminal failure 뒤 다시 실행하면 새 run ID를 만들되 `retryOfReprocessingRunId` lineage로 연결한다.
- cancellation은 best effort다. terminal publication transaction 전이면 output을 저장하지 않고
  `CANCELLED`; 이미 `COMPLETED`면 취소가 결과를 되돌리지 않는다.
- 삭제 요청 commit은 generation을 올리고 admission/callback 저장을 막는다. running run은 cancel/discard하며
  삭제가 reprocessing보다 우선한다.
- 부분 성공을 publish하지 않는다. 새 Revision과 그 Revision을 대상으로 한 AnalysisRun을 함께 요청했다면
  Revision은 내부적으로 생성될 수 있어도 Analysis까지 성공하고 hash/generation 검증을 통과할 때만
  reprocessing 전체를 `COMPLETED`로 publish한다. 실패 시 새 artifact는 latest가 되지 않으며 cleanup 대상이다.
- Revision 생성과 Analysis 계산 자체를 하나의 장기 DB transaction으로 묶지 않는다. Core의 최종 publication
  transaction이 ownership, generation, hashes, lineage, terminal status를 함께 검증해 원자적으로 visible
  pointer를 바꾼다.
- callback은 run ID, target VersionSet hash, deletion generation, attempt token을 모두 일치시킨다. 과거 attempt
  또는 삭제 전 callback은 `STALE_RESULT_AFTER_DELETION` 또는 `REPROCESSING_CONFLICT`로 discard한다.

## 7. Reprocessing Types

| Type | 필요한 source | 필요한 version | 생성 Aggregate | lineage | 주요 실패 | 사용자 설명 |
|---|---|---|---|---|---|---|
| `REPARSE_SOURCE` | active raw bytes + artifact metadata | dialect/adapter/normalizer/canonical + downstream target | 새 LedgerRevision, 요청 시 새 AnalysisRun | source import/revision → reprocessing → target | source unavailable, dialect incompatible | 원본 파일을 새 parser 정의로 다시 해석 |
| `REBUILD_LEDGER` | canonical source set 또는 registered migration input | schema/terms/reconstruction/reconciliation | 새 LedgerRevision, 요청 시 새 AnalysisRun | parent revision(s) → target revision | schema/terms unavailable | 보존된 canonical 기록으로 장부를 다시 구성 |
| `RERUN_RECONSTRUCTION` | canonical records + terms | reconstruction/reconciliation/ReviewUnit/evidence | 새 LedgerRevision, 요청 시 새 AnalysisRun | source revision → target revision | incompatible canonical | 복원·조정 규칙으로 새 장부 결과 생성 |
| `RERUN_ANALYTICS` | retained LedgerRevision/manifest | Metric/Finding/config/evidence | 새 TradingAnalysisRun | source revision 및 optional parent run → target run | version/capability incompatible | 같은 장부를 선택한 분석 정의로 재계산 |
| `REEVALUATE_FINDINGS` | retained Metric result와 evidence, 또는 같은 Revision | exact Metric definitions + target Finding rules/config | 새 TradingAnalysisRun | parent run → target run | source Metric incompatible | 지표는 유지 가능한 범위에서 Finding 규칙만 재평가 |

`REEVALUATE_FINDINGS`가 기존 Metric payload를 재사용하려면 rule이 요구하는 exact Metric definition/evidence
schema와 source config가 같아야 한다. 그렇지 않으면 `RERUN_ANALYTICS`로 승격하지 않고 실패한다.

## 8. Raw Source Availability

```text
ReprocessingInputAvailability
├── RAW_AND_CANONICAL
├── CANONICAL_ONLY
├── RESULT_ONLY
└── DELETED
```

| Input Availability | Adapter Reparse | Reconstruction | Metric Recalculation | Existing Result View |
|---|---:|---:|---:|---:|
| `RAW_AND_CANONICAL` | 가능, TTL/generation 확인 | 가능 | 가능 | 가능 |
| `CANONICAL_ONLY` | 불가 | target schema/terms 호환 시 가능 | retained Revision 호환 시 가능 | 가능 |
| `RESULT_ONLY` | 불가 | 불가 | 불가 | 가능, retained payload/metadata만 |
| `DELETED` | 불가 | 불가 | 불가 | lifecycle scope에 따라 불가 |

- raw TTL 만료 뒤 Adapter reparse는 성공으로 처리하지 않고 `REPROCESSING_SOURCE_UNAVAILABLE`이다.
- `SourceEvidenceSnapshot`은 raw를 대체하지 않는다. canonical migration/reconstruction의 provenance 확인에는
  쓸 수 있어도 Adapter에 입력하지 않는다.
- 오래된 canonical schema가 target reconstruction과 호환되지 않고 registered migration도 없으면
  `CANONICAL_SCHEMA_INCOMPATIBLE`이다.
- 재업로드는 새 `TradingImportSession`이다. owner와 Book ownership을 다시 검증하고 artifact content hash,
  canonical source identity와 overlap/conflict 검사를 통과한 경우에만 `reuploadOfImportSessionId`와 parent
  Revision edge를 기록한다. hash 유사성만으로 자동 연결하지 않는다.
- 삭제된 artifact나 key를 backup에서 재처리 목적으로 복원하지 않는다.

## 9. Trigger와 Automatic Policy

| Trigger | 사용자 동의 | 자동 실행 | quota/비용 | 우선순위 | 알림 | 취소 | audit |
|---|---|---|---|---|---|---|---|
| `USER_REQUESTED` | 요청 자체가 동의 | 가능 | 제품 설정, 이번 계약에서 과금 미확정 | 일반 | 시작/완료/실패 | terminal 전 가능 | 필수 |
| `SYSTEM_MIGRATION` | 승인된 migration plan에 명시 | batch만 가능 | 운영 budget | plan에 따름 | 영향 사용자에게 결과/실패 | batch 정책 | plan/actor/범위 필수 |
| `BUG_FIX` | 기본은 요청 필요 | incident migration 승인 시만 | 운영 결정 | correctness에 따라 | 필수 | 안전하면 가능 | defect/version/범위 필수 |
| `SECURITY_FIX` | incident plan이 정함 | 승인된 plan만 | quota 미차감 기본 | deletion/security hold 다음 | 필수, 공개 범위는 incident plan | plan이 정함 | 최고 수준, payload 금지 |
| `DEFINITION_UPGRADE` | 기본 요청 필요 | 불가 | 제품 설정 | 일반 | 완료/비교 가능성 안내 | 가능 | 필수 |
| `SUPPORT_RECOVERY` | owner 요청/승인 ticket | 자동 불가 | 운영 결정 | 일반 | 필수 | 가능 | ticket/actor/scope 필수 |

확정 정책은 다음과 같다.

- 새 Adapter 배포는 기존 import를 자동 reparse하지 않는다.
- reconstruction bug fix는 기존 ledger를 자동 rebuild하지 않는다. correctness/security incident라면 승인된
  migration plan으로 별도 실행한다.
- Metric/Finding version 배포는 자동 rerun하지 않는다.
- 결과 화면 접근을 trigger로 하는 lazy reprocessing은 금지한다. 읽기 요청이 계산이나 latest 변경을
  일으키지 않는다.
- 대규모 migration은 대상 query, 고정 target VersionSet, dry-run/용량/rate limit, cancellation, notification,
  audit와 rollback이 아니라 publication 중단 계획을 먼저 승인한다.
- 실패 retry는 bounded backoff와 capacity admission을 사용한다. 수치값은 운영 configuration이며 의미를
  바꾸지 않는다.
- 결과는 모든 산출물·hash·lineage·deletion generation 검증 뒤 한 번에 publish한다.

우선순위는 `Deletion > Security hold > Reprocessing > New analysis`다. 법률상 hold는 현재 확정하지 않으며
별도 법률/ADR 없이는 만들지 않는다.

여기서 `Security hold`는 incident 대응 중 새 계산/publication을 일시 중단하는 운영 gate이며 데이터의 법적
장기 보존을 뜻하지 않는다. retention을 늘리는 legal hold는 확정된 계약이 아니다.

## 10. Idempotency

```text
idempotencyKey = SHA-256(canonical JSON {
  ownerId,
  tradingBookId,
  sourceRevisionContentHash,
  sourceAnalysisResultHash?,
  targetVersionSetHash,
  analysisConfigHash?,
  reprocessingType
})
```

- `reason`, trigger, 생성 시각, random ID, worker, attempt는 logical identity에 넣지 않는다.
- map key는 Unicode code point 순, set은 정해진 semantic key 순으로 정렬한다. config 입력 순서나 JSON
  whitespace는 hash에 영향 주지 않는다.
- 같은 key가 진행 중이면 기존 run을 반환한다. 성공한 결과가 있으면 새 결과를 만들지 않고 기존
  `COMPLETED` run/result를 반환한다.
- terminal failure 뒤 retry는 같은 key를 유지하고 새 run/attempt를 failure lineage에 연결한다. 동시에 하나의
  active attempt만 허용한다.
- target version 또는 config의 의미 있는 값이 달라지면 hash와 key가 달라진다.
- 같은 key로 서로 다른 source pointer나 target payload가 제시되면 `REPROCESSING_CONFLICT`다.

## 11. Lineage

lineage는 기존 결과를 수정하는 연결이 아니라 새 immutable 결과의 생성 관계다.

```text
TradingSourceArtifact → TradingImportSession → LedgerRevision v1
                                            └→ LedgerRevision v2
                                                ├→ TradingAnalysisRun metric-v1
                                                └→ TradingAnalysisRun metric-v2
```

Revision/Run lineage edge는 최소 다음을 가진다.

```text
parentLedgerRevisionIds[]
parentAnalysisRunId?
sourceImportSessionIds[]
reprocessingRunId
reprocessingType
reasonCode
sourceVersionSet
targetVersionSet
initiatedBy
inputAvailability
resultHash
createdAt
```

여러 ImportSession을 포함하거나 registered migration이 여러 source Revision을 합성할 수 있으므로 lineage는
단일 parent tree가 아닌 DAG다. cycle은 금지하며 모든 parent는 같은 owner/Book이어야 한다. 일반 단일
Revision 재처리도 배열 크기 1인 edge로 표현한다. lineage payload와 result 내부 provenance가 다르면
`LINEAGE_INCONSISTENT`다.

## 12. Publication, Historical Review와 Difference

### 12.1 Publication

- 새 Revision/Run이 `FAILED` 또는 `CANCELLED`여도 기존 Review와 latest pointer는 변하지 않는다.
- 성공 산출물은 검증 전 staging 상태이며 사용자 Review에 부분 노출하지 않는다.
- `COMPLETED` publication transaction만 immutable target을 visible로 만들고 latest pointer를 선택적으로
  갱신한다. pointer 변경 정책은 요청에 고정하며 시스템이 deprecated/unavailable 이유만으로 자동 전환하지
  않는다.
- Review latest pointer가 AnalysisRun을 가리키면 그 Run이 참조하는 LedgerRevision과 한 쌍으로 바뀌어야 한다.
  Revision만 먼저 current로 보이게 하거나 Run을 다른 Revision과 조합하지 않는다.
- 사용자는 특정 completed Revision/Run을 pin해 조회할 수 있다. 화면 계약에는 `CURRENT`, `HISTORICAL`,
  `REPROCESSING` label과 source/definition/config/created time을 제공할 데이터가 있어야 한다.
- canonical result hash가 callback 선언값, Core 재계산값 또는 같은 idempotency key의 기존 성공 hash와
  다르면 publish하지 않고 `RESULT_HASH_MISMATCH`로 실패시킨다.

### 12.2 Historical Review

보관·접근 가능한 각 결과는 당시 계산된 source Revision, 모든 definition VersionSet, canonical
`AnalysisConfig`와 hash, reprocessing reason/trigger, 생성 시점, parent result와 `outcomeChanged`를 표시할 수
있어야 한다. old implementation이 `UNAVAILABLE`이어도 payload와 metadata 조회 상태를 구분한다. 과거 결과를
최신 정의로 계산된 것처럼 표시하지 않는다.

### 12.3 Difference summary

```text
ReprocessingDifferenceSummary
├── sourceRevisionId
├── targetRevisionId
├── sourceAnalysisRunId?
├── targetAnalysisRunId?
├── canonicalRecordCountDelta
├── episodeCountDelta
├── eligibleCountDelta
├── excludedCountDelta
├── exclusionReasonChanges
├── metricChanges
├── evidenceChanges
├── sourceVersionSet
├── targetVersionSet
└── outcomeChanged
```

이 summary는 재처리 전후 결과 차이를 기술할 뿐 원인을 자동 단정하지 않는다. 서로 다른 Metric definition의
값은 `metricChanges`에 old/new version과 각각의 값을 병렬로 둘 수 있지만 기간 trend point로 연결하지 않는다.
장기 저장의 필수 계약은 summary와 양쪽 immutable result reference다. 전체 generic payload diff 저장은
필수가 아니며 운영/제품 후속 결정이다.

## 13. Trend Comparability

두 point는 다음 `TrendComparisonKey`가 모두 같을 때만 같은 기간 trend에 포함한다.

```text
metricDefinitionVersion
trendConfigHash                      # canonical AnalysisConfig에서 analysisPeriod만 제외
outcomeBasis
comparisonDefinition
reviewTimezone
timezoneDatabaseVersion
requiredCapabilitySet
reviewUnitSchemaVersion
```

`analysisConfigHash` 비교에서는 series가 의도적으로 바꾸는 `analysisPeriod`만 제외한
`trendConfigHash`를 별도로 canonicalize한다. 다른 값이 하나라도 다르면 `NOT_COMPARABLE_VERSION`이며 가장
가까운 version이나 config로 연결하지 않는다. 최신 VersionSet으로 모든 과거 기간을 각각 다시 계산해 모두
완료한 경우에만 새 comparable series를 publish한다. 일부 기간만 성공한 series는 complete trend로 노출하지
않는다.

## 14. Failure Taxonomy

| Failure | 발생 조건 | 사용자 의미 | retry | 기존 결과 영향 | 새 Revision/Run | alert | audit / masking |
|---|---|---|---|---|---|---|---|
| `VERSION_UNAVAILABLE` | 필요한 implementation/terms/reader 없음 | 선택 버전으로 재실행 불가 | version 복구/target 변경 시 | 없음 | 없음 | 반복/ACTIVE 오표시 시 | version ID만, payload 금지 |
| `VERSION_INCOMPATIBLE` | registry에 조합 없음 | 입력과 선택 버전이 호환되지 않음 | target/input 변경 시 | 없음 | 없음 | migration batch면 | source/target IDs |
| `REPROCESSING_SOURCE_UNAVAILABLE` | 필요한 raw/canonical/result 없음 | 필요한 원본 또는 계산 입력이 없음 | 재업로드 가능 시 | 없음 | 없음 | 아니오 | availability만 |
| `REPROCESSING_NOT_SUPPORTED` | type/product 조합 미정의 | 해당 재처리 방식 미지원 | 지원 추가 전 불가 | 없음 | 없음 | 아니오 | bounded type/version |
| `CANONICAL_SCHEMA_INCOMPATIBLE` | target reconstruction이 schema 미지원 | 보존 기록 형식이 새 복원과 맞지 않음 | migration/raw가 있으면 | 없음 | 없음 | batch면 | schema IDs |
| `INSTRUMENT_TERMS_UNAVAILABLE` | target terms를 고정할 수 없음 | 상품 계약 근거 부족 | terms 등록 시 | 없음 | 없음 | 예 | instrument는 safe synthetic/opaque ID |
| `TARGET_VERSION_RETIRED` | target이 READ_ONLY/retired | 신규 실행할 수 없는 버전 | ACTIVE target 선택 | 없음 | 없음 | stale client 반복 시 | version ID |
| `REPROCESSING_CONFLICT` | active duplicate 불일치, stale attempt, publication 경쟁 | 다른 재처리와 충돌 | 상태 확인 후 가능 | 없음 | publish 없음 | 반복 시 | opaque run/attempt |
| `REPROCESSING_CANCELLED` | 사용자/삭제/migration plan 취소 | 결과가 게시되지 않음 | 삭제가 아니면 새 요청 | 없음 | publish 없음 | 아니오 | actor/reason code |
| `RESULT_HASH_MISMATCH` | 같은 결정 입력의 hash 불일치 | 결과 검증 실패, 게시 안 됨 | 원인 조사 뒤 | 없음 | publish 없음 | 즉시 | hash prefix 최대 12자, 값 금지 |
| `STALE_RESULT_AFTER_DELETION` | 낮은 deletion generation callback | 삭제 뒤 결과를 저장하지 않음 | 금지 | 삭제 정책 따름 | 없음 | rate threshold | run/generation만 |
| `LINEAGE_INCONSISTENT` | parent/owner/Book/version/provenance 불일치 또는 cycle | 결과 연결을 검증하지 못함 | 입력 수정 뒤 | 없음 | publish 없음 | 즉시 | opaque IDs/version만 |
| `NOT_COMPARABLE_VERSION` | `TrendComparisonKey` 불일치 | 같은 추세로 비교할 수 없음 | 동일 VersionSet/config로 전 기간 재계산 시 | 없음 | 없음 | 아니오 | version/config hash만 |

모든 failure message/log는 `TRADING_DATA_LIFECYCLE.md`의 allowlist를 따른다. raw row, UID, filename, Symbol,
거래 시각·수량·가격·fee·PnL을 넣지 않는다.

## 15. Lifecycle Interaction

- `now < rawExpiresAt`이고 artifact가 `ACTIVE`이며 deletion generation이 일치할 때만 reparse를 admit한다.
- raw 삭제/TTL과 reparse가 경쟁하면 TTL을 연장하지 않고 cancel/discard한다.
- deletion pending인 Revision/Book/Account에는 신규 reprocessing을 admit하지 않는다.
- reprocessing 중 삭제 요청은 tombstone/generation을 먼저 commit하고 run을 취소한다. 늦은 결과는 저장하지
  않는다.
- 삭제 완료 뒤 callback과 backup에서 돌아온 오래된 job은 `STALE_RESULT_AFTER_DELETION`으로 discard한다.
- restore는 deletion ledger replay와 삭제 scope 0건 검증 뒤에만 job admission을 연다.
- retention policy 변경은 기존 raw TTL을 소급 연장하지 않는다. masking/access/deletion 강화는 ADR-061대로
  즉시 적용할 수 있다.
- `SourceEvidenceSnapshot`만 남은 상태는 Adapter reparse 불가이고 canonical이 남은 경우에만 reconstruction/
  analytics가 가능하다.
- deleted Member/Account/Book은 migration target에서 제외하며 삭제 artifact를 재처리 때문에 복원하지 않는다.

## 16. Deterministic Hash Contract

```text
CanonicalSourceSet
+ InstrumentTerms
+ TargetVersionSet
+ AnalysisConfig
= CanonicalResultHash
```

단계별로 `LedgerContentHash`, `ReconciliationManifestHash`, `AnalysisResultHash`를 만들고 최종 result가 해당
input hash를 참조한다. canonical serialization은 ADR-059/060을 재사용한다.

- UTF-8 canonical JSON, object key Unicode code point 순, semantic array ordering, whitespace 없음.
- Decimal은 `{"unscaled":"...","scale":n}`, rational은 기약분수/양수 denominator, timestamp는 UTC RFC
  3339 microsecond 6자리다.
- timezone 변환에는 pinned timezone database version을 사용한다.
- runtime/random DB ID, request/reprocessing ID, `createdAt`/`generatedAt`, worker, storage path, retry attempt,
  UI rounding은 hash에서 제외한다. 결정론적 episode/evidence identity는 포함한다.
- `implementationDigest`와 모든 의미 version/config hash는 입력에 포함한다.
- Compute completion 시 자체 검증하고, Core publication 시 선언된 input/result hash와 기존 성공 hash를 다시
  검증한다. 같은 결정 입력에서 다르면 `RESULT_HASH_MISMATCH`; 결과를 publish하거나 latest로 지정하지 않는다.

## 17. Invariants

`DOMAIN.md`의 `TR-I25`~`TR-I38`이 요약 정본이다. 구현과 fixture는 최소 다음을 강제한다.

1. 기존 Revision/Result를 수정하지 않는다.
2. 재처리는 새 Revision 또는 Run을 만든다.
3. 같은 idempotency key의 성공 결과를 중복 생성하지 않는다.
4. 실패한 재처리는 기존 정상 결과와 latest를 변경하지 않는다.
5. raw가 없으면 Adapter reparse를 성공시키지 않는다.
6. incompatible version을 대체하거나 자동 변환하지 않는다.
7. 삭제 요청 이후 새 재처리를 시작하지 않는다.
8. 삭제 뒤 늦은 결과를 저장하지 않는다.
9. 결과에서 source/target VersionSet과 lineage를 확인할 수 있다.
10. 서로 다른 Metric definition/config 결과를 자동 trend로 연결하지 않는다.
11. 같은 input/version/config는 같은 canonical result hash를 만든다.
12. implementation unavailable과 existing result readable을 별도 상태로 표현한다.
13. 재업로드 lineage 연결 전에 owner/Book/source identity를 검증한다.
14. latest pointer는 fully validated `COMPLETED` result에만 바꾼다.

## 18. Scenario Expected Results

아래 `생성`은 publication 전 staging 산출물을 포함하지 않고 최종적으로 보존되는 Aggregate를 뜻한다.

| # / 상황 | source state / availability | source → target | command / trigger | 생성 | idempotency | lineage | 기존 결과 | terminal / 조회 | failure |
|---|---|---|---|---|---|---|---|---|---|
| 1 Adapter bug fix | accepted / RAW_AND_CANONICAL | adapter v1→v2 | REPARSE_SOURCE / BUG_FIX | Revision v2, 요청 시 Run | 새 key, 반복은 reuse | import+v1→v2 | 보존 | COMPLETED, old/new 표시 | — |
| 2 Raw TTL 후 reparse | accepted / CANONICAL_ONLY | adapter v1→v2 | REPARSE_SOURCE / USER_REQUESTED | 없음 | failed run 재사용/새 retry edge | source v1 기록 | 무변경 | FAILED, 재업로드 안내 | REPROCESSING_SOURCE_UNAVAILABLE |
| 3 canonical reconstruction upgrade | accepted / CANONICAL_ONLY | recon v1→v2 | RERUN_RECONSTRUCTION | Revision v2, optional Run | 새 key | v1→v2 | 보존 | COMPLETED | — |
| 4 incompatible canonical | accepted / CANONICAL_ONLY | schema v1→recon v3 | RERUN_RECONSTRUCTION | 없음 | failed | v1 source 기록 | 무변경 | FAILED | CANONICAL_SCHEMA_INCOMPATIBLE |
| 5 instrument terms 변경 | accepted / CANONICAL_ONLY | terms v1→v2 | REBUILD_LEDGER | Revision v2, optional Run | 새 key | v1→v2 | 보존 | COMPLETED | — |
| 6 Metric v1→v2 | completed / CANONICAL_ONLY | metric v1→v2 | RERUN_ANALYTICS | AnalysisRun v2 | 반복 reuse | same Revision, run v1→v2 | 보존 | COMPLETED, diff 가능 | — |
| 7 Finding만 변경 | completed / RESULT_ONLY 또는 CANONICAL_ONLY | finding v1→v2 | REEVALUATE_FINDINGS | compatible면 Run v2 | 반복 reuse | run v1→v2 | 보존 | COMPLETED | — |
| 8 AnalysisConfig 변경 | completed / CANONICAL_ONLY | config h1→h2 | RERUN_ANALYTICS | Run h2 | 새 key | same Revision, old run parent | 보존 | COMPLETED, trend 분리 | — |
| 9 동일 요청 중복 | running/completed | 동일 | 같은 command | 기존 run/result | 같은 key 반환 | 동일 | 무변경 | 기존 status | — |
| 10 실패 후 retry | failed, source retained | 동일 | retry | 새 retry run, 성공 시 target | 같은 key/attempt 증가 | retryOf 연결 | 무변경 | FAILED 후 COMPLETED 가능 | 원 failure 보존 |
| 11 target unavailable | retained | active source→unavailable target | any | 없음 | failed | source 기록 | 무변경 | FAILED | VERSION_UNAVAILABLE |
| 12 old version read-only | result retained | READ_ONLY target | replay request | 없음 | failed | old result 유지 | 보존 | 기존 조회 가능 | TARGET_VERSION_RETIRED |
| 13 old implementation unavailable | result retained / RESULT_ONLY | old→old replay | replay | 없음 | failed | metadata 유지 | 보존 | 기존 조회 가능, replay 불가 | VERSION_UNAVAILABLE |
| 14 재처리 중 삭제 | RUNNING / any | fixed target | deletion | target publish 없음 | active attempt cancel | deletion audit | lifecycle purge | CANCELLED/삭제 조회만 | REPROCESSING_CANCELLED |
| 15 삭제 후 늦은 callback | tombstoned / DELETED | fixed target | callback | 없음 | callback 종료 | generation audit | 없음/삭제됨 | discard | STALE_RESULT_AFTER_DELETION |
| 16 hash mismatch | running / canonical | same inputs | completion | publish 없음 | key 실패 | attempted edge audit | 무변경 | FAILED | RESULT_HASH_MISMATCH |
| 17 system batch migration | selected active scopes | pinned batch target | SYSTEM_MIGRATION | 대상별 새 Revision/Run | scope별 key | plan+parents | 보존 | 대상별 terminal, 부분 batch를 한 결과로 위장 금지 | 대상별 code |
| 18 최신 version 사용자 요청 | completed / canonical | old→ACTIVE latest-resolved ID | USER_REQUESTED | 영향별 Revision/Run | resolved target key | old→new | 보존 | COMPLETED 후 선택적 current | — |
| 19 과거와 최신 비교 | two completed results | old↔new | difference query | DifferenceSummary | 계산 입력 hash | 양쪽 참조 | 무변경 | old/new 병렬 표시 | — |
| 20 다른 version 월별 trend | completed points | metric v1/v2 | trend query | series 없음 | 해당 없음 | 각 result 유지 | 무변경 | NOT_COMPARABLE 표시 | NOT_COMPARABLE_VERSION |
| 21 raw 재업로드 연결 | old raw absent, new session raw active | source identity 검증 | REPARSE_SOURCE | 새 Import/Revision | 새 content key | ownership+reupload+parent edge | 보존 | 검증 성공 시 COMPLETED | 불일치면 LINEAGE_INCONSISTENT |
| 22 완료 전 latest 변경 | RUNNING | any | pointer update 시도 | 없음 | active run 유지 | 없음 | current 유지 | 거절 | REPROCESSING_CONFLICT |
| 23 기존 정상, 새 결과 실패 | completed old / source retained | old→new | any | 실패 run만 | failed key | attempt 기록 | old/current 유지 | FAILED, old 조회 정상 | 원 failure |
| 24 같은 입력 반복 실행 | completed | identical versions/config | repeated command | 새 result 없음 | 성공 reuse | 기존 lineage 반환 | 무변경 | 같은 hash/result | hash 다르면 RESULT_HASH_MISMATCH |

## 19. 결정 분류와 Review Answers

### 확정된 도메인 계약

Version taxonomy/identifier, compatibility fail-fast, 영향별 새 Revision/Run, Aggregate와 상태, input availability,
idempotency, DAG lineage, publication, historical view, trend comparability, failure/lifecycle 우선순위와 hash 계약은
확정값이다.

### 운영 configuration

retry 횟수/backoff, 동시 실행·rate limit·capacity, notification channel, batch 크기와 alert threshold는 운영
설정이다. 상태 의미, 삭제 우선순위, immutable 결과, compatibility와 hash 판정을 바꿀 수 없다.

### Migration plan 필요

system-wide bug/security/correctness fix, canonical schema migration, deprecated version retirement와 대규모
재계산은 대상·동의/고지·용량·실패 처리·publication을 적은 승인 plan이 필요하다.

### 후속 구현 결정

persistence/OpenAPI shape, queue, worker lease, registry 배포 방식, generic full diff 보관, UI와 과금은 후속이다.

Review 질문의 답은 다음과 같다. Adapter 변경은 자동 reparse하지 않고 raw가 있을 때 명시적으로 새 Revision을
만든다. raw가 없으면 compatible canonical로 reconstruction/analytics만 가능하다. reconstruction은 manifest,
episode와 capability를 바꾸므로 새 Revision이고 Metric 변경은 기존 compatible Revision으로 새 Run이다. 기존
결과는 lifecycle 삭제 전까지 조회할 수 있지만 old implementation 부재 시 replay는 불가하다. 중복 요청은
idempotency key로 기존 run/result를 반환한다. 새 결과 실패 시 기존 Review/latest는 유지되고 latest는 완전한
`COMPLETED` publication 뒤에만 바뀐다. 결과 차이는 VersionSet, lineage와 DifferenceSummary로 추적한다. 다른
Metric/config는 trend 비교 불가다. 삭제가 항상 우선하며 재업로드 연결은 ownership/source identity 검증이
필수다. 같은 결정 입력의 hash 불일치는 `RESULT_HASH_MISMATCH`로 publish를 차단한다.
