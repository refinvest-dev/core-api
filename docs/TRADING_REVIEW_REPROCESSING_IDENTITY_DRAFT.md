# 재처리 replay·retry·child 실행 identity 후보 (DRAFT)

상태: **ADR-079·WP03/04 미채택**. [접수/산출물](TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md)과 함께 검토한다.
현재 Compute create/grant/result/ACK·hash bytes·ID를 변경하지 않는다. semantic equality와 새 wire 정의를 구분한다.

후속 [순차 논의 ① identity/hash](TRADING_REVIEW_REPROCESSING_IDENTITY_POLICY_DRAFT.md)는 Member owner/ownerId·신규 문자열/null·
profile/legacy 보존의 구체적 권장 후보다. 아래 기존 의미를 대체하거나 숫자/생략과의 byte 동등성·WR-G02 완료를 주장하지 않는다.

## WP03 — replay와 explicit retry

| identity | 소유/의미 | 보존할 값 |
|---|---|---|
| public request namespace | owner+operationId+Idempotency-Key | 최초 semantic request·resolved pin·접수/오류 receipt·원 Run |
| ADR-062 logical key | Record의 동일 계산/재처리 family | source content/optional Analysis result·target/config·type와 원 owner projection |
| full binding | 같은 logical key의 source/publish 충돌 방지 | exact source IDs·parent/result·outputs·target/config 전체·publication 의도 |
| reprocessing attempt | terminal Run을 재개하지 않는 새 실행 | 새 Run ID·증가한 attempt·retryOfReprocessingRunId |
| child/Compute/create/grant/ACK | 각 모듈/Compute 실행·전송 | 원 child ID·create key/body·token·grant revision/update key·disposition receipt |

Versioning §10의 ownerId와 Persistence §5.1의 ownerScope는 byte projection이 같다고 증명되지 않았다.
ownerScope를 object/string으로 추측하거나 새 hash oracle을 만들지 않는다. exact canonicalization·nullable field
presence·targetVersionSetHash 계산 대상·legacy key/alias를 원 이력/고정 vector로 확인해야 한다(WR-G02).
reason/trigger/시각/random ID/worker/attempt는 기존 logical key에서 제외한다. source pointer·outputs·publish 차이는
원 hash를 보존한 별도 equality로 conflict 처리하는 우선안이다. authority/현재 삭제 scope proof는 매번 재확인한다.

| 요청/상태 | 우선 후보 |
|---|---|
| known public key·동일 의미 | 최초 receipt/원 Run replay. 실패 후에도 새 실행 없음; retry로 생긴 새 Run으로 mapping 이동 없음 |
| known public key·다른 의미 | conflict. 같은 hash·다른 source pointer/outputs/publish도 동일 |
| 새 key·동일 full binding의 active/success | 기존 active/성공 Run 수렴. 새 child·latest 부작용 없음 |
| 새 key·terminal FAILED·retry 의도 없음 | 원 실패 Run 연결/기존 실패 의미. 키 변경만으로 새 attempt를 만들지 않음 |
| 새 key·명시 retry·retryable FAILED parent | 현재 접근/자원/registry/기존 원격 실행 fence를 재검증한 뒤 같은 logical key·새 Run/attempt·retryOf |
| source 영구 삭제·target unavailable 지속·hash/lineage 불일치 | 자동 retry 불가. 새 검증 upload/source lineage나 target 변경은 별도 request/key |
| parent CANCELLED 또는 commit/create UNKNOWN 미해소 | FAILED retry 규칙을 자동 적용하지 않음. UNKNOWN은 원 effect 조정, cancel 재접수는 별도 채택 조건 |

명시 retry는 **기존 POST에 새 public key와 실패 parent 참조를 함께 주는 versioned request**를 우선 제안한다.
parent 참조 필드명/schema는 후속 계약에서 정하며 현재 request에 없는 필드를 보내거나 새 retry endpoint를 만들지 않는다.
retry parent는 같은 owner/Book·logical/full binding의 정확 FAILED Run이어야 한다. 이미 후속 active/성공 attempt가 있으면
그 Run으로 수렴하고 중복 successor를 만들지 않는다. attempt 증가는 Worker retry/lease 횟수와 별도다.
admission fence 아래 현재 terminal parent/active/success·원 공개 receipt를 함께 검사하며 최대 하나의 active attempt를 유지한다.
retry 결과의 public mapping·lineage·새 Run/Dispatch는 같은 Tx다. 이전 실패 Run/receipt와 원 child ID를 덮어쓰지 않는다.

local FAILED는 원격 종료 증거가 아니다. 새 attempt 전 원 dispatch/claim/publication 차단, unresolved create/grant,
late payload disposition·cleanup 책임을 확인한다. 현재 token/generation을 위조하거나 일반 실패를 DISCARDED_STALE 사유로
만들지 않는다. 정상 terminal FAILED 뒤 새 Compute job의 create key와 기존 active job의 grant-update/worker retry는 다르다.
key retention이 끝나 mapping이 안 보이는 것은 미접수 증거가 아니다. 영속 logical/receipt 복구 없이 새 계산하지 않는다.

## WP04 — accepted Session과 분리된 child binding

기존 Core Normalization lifecycle은 Session VALIDATING+activeNormalizationRunId를 검사하고 publication에서
Session ACCEPTED/createdRevision을 갱신한다. Core V3 active-session unique는 일반 Import 동시 실행을 제한한다.
이는 **Core 저장 제약**이다. Compute가 같은 Session unique를 소유한다고 잘못 치환하지 않는다.
현재 Compute normalization은 coreNormalizationRunId와 원 source Session/artifact identity·최초 token을 요구한다.

우선 후보는 Record가 소유한 **재처리 child 실행 intent와 별도 versioned execution profile**이다.
기존 NormalizationRun 모델을 재사용할지 별도 child 모델로 저장할지는 Go 설계 사항이지만 다음은 보장해야 한다.

| binding 후보 | 책임/검증 |
|---|---|
| parent ReprocessingRun ID/attempt·child role/ID·owner/Book | Record의 durable intent와 현재 Guard 확인; caller struct 자체는 권위 아님 |
| exact source Revision/optional Analysis·원 source Session/artifact set·type | source provenance 보존; 새 가상 ImportSession·accepted Session 재개 없음 |
| target/input/result version/hash pin·정책·deletion scope proof | Compute와 full equality; 원 source generation과 현재 접근 Guard를 분리 |
| 최초 Compute create ID/key/body·token·후속 grant CAS | BindOnce, create UNKNOWN에 새 key 금지; 재시작에도 원 mapping 복구 |
| preallocated private R2 identity·sealed stage incarnation·검증 receipt | Analysis 입력에 쓴 Revision ID/bytes/hash를 최종 공개까지 보존 |
| child decision·ACK/정리 checkpoint | parent terminal과 실제 공개 원자성; 실패/삭제 뒤 late resource 추적 |

새 Go Aggregate ID는 Snowflake, 기존 ID/Compute job/token/hash는 그대로 보존한다. source Session은 provenance이며
현재 child execution의 진행 권위가 아니다. original Session 상태·createdRevision·일반 validation claim은 변경하지 않는다.
기존 active-session unique를 제거해 동시성을 푸는 대신 ordinary Import와 child의 명시 namespace/제약 및
parent logical active fence를 후속 Go 물리 모델에서 검증한다. 부분 unique/별도 table 중 하나를 여기서 채택하지 않는다.

### 계산 surface별 지원 조건

| 실행 | 현재 source에서 확인한 surface | 채택/활성화 전 |
|---|---|---|
| REPARSE_SOURCE | normalization은 두 raw role+원 Session input identity로 normalize_and_reconstruct | 별도 child create/result/grant/cancel/ACK/delete binding·provenance·지원 exact target 검증 |
| REBUILD_LEDGER / RERUN_RECONSTRUCTION | Python 순수 복원 함수와 raw 기반 normalization은 존재 | canonical/terms 또는 registered migration 입력의 durable Compute 실행 계약이 있다고 간주하지 않음 |
| RERUN_ANALYTICS | 현재 Analytics create는 Revision ID·두 artifact·config/version/input hash에 binding | retained source eligibility·지원 exact target·Record authority/입력 export 확인 |
| R2에 대한 A2 | Analytics wire의 Revision ID는 opaque이지만 Go prepared-source 경로는 미구현 | 같은 private ID·V2 supplement/manifest·bounded export/grant·current Guard; public Reader로 우회 금지 |
| REEVALUATE_FINDINGS | 현재 Analytics DTO의 findingRuleSetVersion은 TRADING_FINDING_RULES_DISABLED_V1 literal | Metric/evidence reuse 및 Finding-only 실행 계약/target 지원 증거 필요. full analytics 자동 대체 금지 |

별도 profile은 child binding·지원 type/입력/version·replay/오류·cancel/result/ACK·삭제 closure를 함께 정의해야 한다.
새 field를 strict extra=forbid DTO에 억지로 넣거나 새 operationKind를 기존 fingerprint/hash root에 추가하지 않는다.
원 semantic input/result hash를 유지할 수 있는지는 fixed vector로 검증하고 실제 변경이면 새 version을 명시한다.
재처리 purpose만으로 기존 grant를 재사용하거나 raw TTL을 연장하지 않는다.

### private source와 최종 공개

Analysis는 Record의 purpose-bound prepared read/export capability로 exact private R2를 소비한다.
R2 ID가 최종 공개 때 바뀌면 이미 고정된 AnalyticsInputIdentity/결과를 손상시키므로 ID를 임의 재할당하지 않는다.
public FK·namespace·stage materialization 비용은 WF-G03, Ledger-only latest/전체 writer lock graph는 WF-G04다.
두 child/lineage/pointer·parent COMPLETED/logical success·각 disposition/ACK Outbox는 같은 실제 PostgreSQL Tx에서 확정한다.
외부 Compute/Storage는 Tx 밖이며 각 모듈은 같은 Tx-bound scope의 자기 저장 능력만 제공한다.

UNKNOWN은 두 child/원 receipt/outbox로 조정하고 unresolved 자원을 orphan으로 지우지 않는다.
prepare는 PERSISTED가 아니다. ACK는 commit 뒤 전송하며 실패로 재계산/반쪽 공개하지 않는다.
[ADR-073 결과 수명](TRADING_REPROCESSING_RESULT_LIFECYCLE_DRAFT.md)은 여전히 미채택이다.
새 custody/무기한 private 보관/TTL 연장은 허용하지 않는다. Analytics 실제 부재는
[Normalization 전용 삭제](TRADING_RUNTIME_DELETION.md)로 증명할 수 없으며 WF-G05/06이 열린다.


## 순차 논의 ② — full binding의 비교 목적

[Request/execution binding 후보](TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md)는 위 full binding을
원 request의 비교와 다른 key의 실행 수렴/명시 retry 자격으로 나눈다. reason·retry parent는 원 request/audit에서 보존하며,
execution equality에서는 제외하고 권한·exact FAILED parent/chain·effect 해소는 별도 필수 검증하는 권장안이다.
위 '같은 full binding' 표현을 모든 필드의 일괄 동일 비교로 구현하지 않는다. ①/② 모두 명시 채택·byte/consumer 검증은 별도다.
