# Workflow request/response·receipt DTO 권장 후보

상태: **ADR-079/WP01~04 미채택 문서 모델**, 2026-11-13. [HTTP registry](TRADING_REVIEW_WORKFLOW_HTTP_DRAFT.md)·
[WP 접수](TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md)·[identity/child](TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md)를 구체화한다.
현재 active OpenAPI를 유지한다. 후속 [OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)는 별도 DRAFT이며 wire를 활성화하지 않는다.
아래 JSON 블록은 최초 문서의 **부분 projection 또는 synthetic 오류 예시**다. 완전한 schema 예제는 후속 YAML을 읽는다.
완전한 request/실행/계산 fixture·schema 검증 성공으로 해석하지 않는다.

## RequestReprocessingV2Draft

| 필드/위치 | 후보 형식·필수/NULL | 검증·기존 차이 |
|---|---|---|
| tradingBookId / path | typed existing opaque ID, 필수 | 현재 owner/Account/Book 소속·Guard. body owner/Book 중복 입력 없음 |
| Idempotency-Key / header | 기존 형식의 key, 필수 | 원 request namespace 유지. Go가 browser key를 Compute key로 사용하지 않음 |
| sourceLedgerRevisionId | opaque ID, 필수/non-null | 정확 source pointer·현재 접근·내용/version pin. hash가 같아도 다른 Revision으로 재라벨 금지 |
| sourceAnalysisRunId | optional/non-null일 때 exact ID; omitted/null은 parent Analysis 없음 | supplied면 같은 owner/Book/Revision과 호환 결과 관계 확인. 자동 latest 선택 없음 |
| reprocessingType | 현재 5종 enum, 필수/non-null | 아래 type/outputs·지원 source/target/실행 surface를 함께 검증 |
| targetVersionSet | 기존 TargetTradingVersionSet 의미의 object, 필수/non-null | exact component/hash·compatibility/registry 가용성. schema가 hash만 허용해도 실행 준비 증거 아님 |
| requestedOutputs | 필수/non-null 후보 enum | `LEDGER_ONLY`, `LEDGER_AND_ANALYSIS`, `ANALYSIS_ONLY`. 기존 request에 없음 |
| reasonCode | 기존 public USER_REQUESTED/BUG_FIX/DEFINITION_UPGRADE/SUPPORT_RECOVERY, 필수/non-null | 운영 reason을 넣어 operator 권한을 얻지 않음 |
| analysisConfig | Analysis 포함이면 완전 Config 필수/non-null, LEDGER_ONLY에서는 생략 후보 | 기존 optional/null과 호환성 차이. source/default/current를 조용히 채우지 않음 |
| publishAsLatest | optional boolean/non-null, omitted=true 후보 | 현재 default 유지. true가 Ledger-only/경합 정책을 정하지는 않음 |
| retryOfReprocessingRunId | optional/non-null ID 후보; 최초 요청에서는 생략 | 새 key+정확 FAILED parent·동일 full binding/logical·단일 successor 검증. 기존에는 response 필드만 있음 |

추가 key·중복 key/type coercion·path/query ambiguity를 거절하는 strict decoder를 권장한다. 정확 ID 길이/typed catalog·body 상한은 후속 schema/profile gate다.
Member sub의 decimal 형식과 모든 resource ID를 동일 규칙으로 파싱하지 않는다. 신규 Snowflake·legacy UUID/opaque 참조를 보존한다.
JSON 큰 ID/금액을 float로 왕복하거나 hash 시각/default를 재작성하지 않는다. 원 `AnalysisConfig`·VersionSet/Decimal/hash projection은 현재 정본을 그대로 따른다.

| type | requestedOutputs 후보 | 추가 조건 |
|---|---|---|
| REPARSE_SOURCE | LEDGER_ONLY / LEDGER_AND_ANALYSIS | 유효 raw/TTL·exact metadata/terms·별도 child profile |
| REBUILD_LEDGER / RERUN_RECONSTRUCTION | LEDGER_ONLY / LEDGER_AND_ANALYSIS | canonical/terms 또는 등록 migration·실제 지원 Compute 계약 |
| RERUN_ANALYTICS | ANALYSIS_ONLY | retained published V2/완전 source proof·정확 Analytics config/target |
| REEVALUATE_FINDINGS | ANALYSIS_ONLY | exact compatible Metric/evidence/source·Finding-only 실제 지원. full analytics 자동 대체 없음 |

LEDGER_ONLY라도 원 target tuple을 일부 삭제/재hash하지 않는다. 요청 outputs·publish/retry field는 canonical logical root에 임의 추가하지 않는다.
LEDGER_AND_ANALYSIS는 R2+A2 전체 준비 후 단일 공개다. ANALYSIS_ONLY의 target Revision은 원 source이며 가짜 R2를 생성하지 않는다.
Ledger-only latest·현재 latest와 다른 source의 A2·동시 writer 승자는 여전히 WR-G05다.

```json
{"requestedOutputs":"LEDGER_ONLY","publishAsLatest":false}
```

위 블록은 selector/의도만 보여준다. source/type/완전 target 등 필수 필드가 없으므로 유효한 전체 request가 아니다.

```json
{"retryOfReprocessingRunId":"725400000000000001","requestedOutputs":"LEDGER_AND_ANALYSIS"}
```

이 블록도 부분 projection이다. 새 header key·원 source/target/config·정확 FAILED/권한·미해결 effect 해소 조건 없이는 retry할 수 없다.
optional field의 omitted/null 정책이 다르므로 retry parent null·publishAsLatest null을 임의 omitted로 치환하지 않는다.

## ReprocessingRunV2Draft와 Page

기본은 현재 TradingReprocessingRun의 owner-filtered Run 의미다. 아래 추가/강화는 versioned 후보이며 기존 schema를 직접 교체하지 않는다.

| 묶음 | 후보 계약 |
|---|---|
| 원 identity/pin | id/Book/source IDs·type·원 source/target VersionSet·inputAvailability·reason/정책·createdAt/updatedAt·lineage의 정확 값 |
| 요청 의도 | requestedOutputs와 publishAsLatest를 명시 반환하는 안. 실행/조회 시 최신 설정으로 추정하지 않음 |
| 상태 | 현재 PENDING/VALIDATING_INPUT/RUNNING/COMPLETED/FAILED/CANCELLED·safe progress. 새 PREPARED/ACKNOWLEDGED public 상태 없음 |
| retry | 새 Run의 원 retryOf. 원 FAILED와 최초 public mapping을 그대로 유지 |
| created child IDs | 최종 공개 decision이 확정된 요청 산출물만 반환. 준비 중 private IDs를 정상 결과 링크로 노출하지 않음 |
| difference/failure | 원 pinned source/target 참조·승인 summary 또는 safe StructuredFailure. unknown 값을 0/[]/hash default로 채우지 않음 |
| Page | 기존 page=0, size=20/최대 100·items/total의 같은 snapshot 의미. 새 cursor/sort/상한 추가 없음 |

```json
{"status":"RUNNING","requestedOutputs":"LEDGER_AND_ANALYSIS","createdLedgerRevisionId":null,"createdAnalysisRunId":null,"differenceSummary":null}
```

위 블록은 private 준비 중 public projection 예시다. 전체 Run 필수 identity/version/lineage/time 필드는 생략돼 있다.
COMPLETED의 요청 child 조합·difference·terminal timestamp·failure 부재는 실제 final receipt와 일치해야 한다.
Analysis-only에는 source Revision을 target 참조로 사용하며 새 createdLedgerRevisionId를 발급하지 않는다.
정확 required/nullable·progress vocabulary·difference의 실제 지원/legacy가 schema/consumer gate다. `lineageParentRevisionIds=[]`로 미확인 provenance를 보정하지 않는다.

## ErrorResponseV2Draft와 원 admission receipt

기존 safe ErrorResponse의 code/message/traceId/retryable·allowlisted details를 유지하는 versioned 모델을 권장한다.
**추가 후보 `reprocessingRunId`**는 WP02의 원 FAILED/오류 receipt commit을 확인했고 같은 owner/current 접근이 승인된 경우에만 포함한다.
unknown/미접수·malformed/foreign/권한 거절·registry 통신 장애에서는 생략한다. null/0·추측 ID·마지막 Run을 넣지 않는다.
이는 안전한 operation 참조 후보이며 raw source identifier를 details에 넣는 예외가 아니다. 현재 ErrorResponse에 몰래 필드를 추가하지 않는다.

```json
{"code":"REPROCESSING_SOURCE_UNAVAILABLE","message":"재파싱에 필요한 원본을 사용할 수 없습니다.","traceId":"synthetic-trace-source","retryable":false,"reprocessingRunId":"725400000000000001"}
```

위 synthetic 410 예시는 접근 가능한 retained source의 raw 부재를 WP02로 commit한 경우다. Compute/new Revision/Analysis dispatch는 없다.
원 키는 그 failure receipt/Run을 가리킨다. 재업로드는 새로운 검증 source/lineage이며 같은 키를 새 Run에 다시 묶지 않는다.

```json
{"code":"ADMISSION_OUTCOME_UNCONFIRMED","message":"요청 접수 상태를 확인하고 있습니다.","traceId":"synthetic-trace-unknown","retryable":true}
```

이 503 code/message는 후보 literal이다. 서버는 확정 source 실패/새 key 허가로 해석하지 않고 같은 key/fence로 복구한다.
DB commit UNKNOWN이면 failure Run을 기록했다고 추측하지 않는다. 실제 code/status/중계와 trace 형식은 OpenAPI/registry 단계에서 검증한다.
same-key replay의 불변 대상은 admission 의미/status/code와 원 mapping이다. 현재 Run 상태·새 request traceId·안전한 메시지 렌더링/전송 headers까지 원 bytes로 고정하지 않는다.
처음 202 뒤 FAILED인 경우 202 receipt 의미를 유지하고 Run.failure로 현재 실패를 보여준다. WP02 410/422였으면 승인된 replay에서 원 오류 의미/Run을 유지한다.
현재 Member/Guard가 거절되면 원 receipt보다 현재 보안 판정이 먼저다. 저장된 Run ID가 있다는 이유로 존재를 노출하지 않는다.

## DeletionRequest·status는 기존 소유 계약 유지

삭제 body는 없고 target은 각 path에서 정한다. 기존 Account/Book·Import 내부 DTO/profile과 shared deletion status를 재사용한다.
id/targetType/opaque targetId/requestedScope·state·원 affectedResources/snapshotStatus·revokedAt·primary/object/runtime/backup status·
backup due·safe failure/retryable·request/start/update/complete 시각을 현재 정본과 대조한다.
원 count/provenance는 replay/remediation에도 불변이며 component 진행은 현재 verified checkpoint에서 읽는다. locator/key/lease/token은 노출하지 않는다.
삭제 202는 접수, status 200은 현재 진행이다. NOT_APPLICABLE/COMPLETED를 Adapter 미구현·count 0·ACK/TTL로 만들지 않는다.
현재 public DeletionRequest의 부족한 표기나 deletion control 보강은 해당 소유 후보에서 함께 리뷰하며 이 문서에 경쟁 schema를 만들지 않는다.

## 수용과 남은 결정

기존 WR-DRAFT-01~14에 필수 selector/type/config·retry parent/null·원 error Run 연결·same-key/current Guard·trace/relay의 assertion을 연결한다.
request/query/response의 후속 구조 예제는 [OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)에서 required/nullable/oneOf/refs·긍정/위험 변이로 검사한다.
JSON 문법 확인은 schema·JWT/authority·해시·DB/UNKNOWN·Compute child·consumer 호환성의 검증이 아니다.
legacy outputs/default·request/receipt profile migration·정확 literal·운영 budget/수명·서비스 write 선형화는 WR-G01~08 조건으로 남긴다.


## 순차 binding 논의 연결

[② 후보](TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md)는 이 문서의 omitted/null/default 의미를 원 profile에서 고정하고,
같은 key의 reason/parent 변경과 다른 key의 execution 수렴을 구분한다. 새 schema·binding hash를 추가하지 않는다.
Analysis 포함 후보의 Config 생략은 default로 보정하지 않는다. 기존 profile의 합법적인 default는 원 resolved pin이 필요하다.
reason 차이 수렴·원 request audit·retry chain은 미채택 검토 사항이며 error receipt/code는 ③에서 논의한다.


## 순차 receipt 논의 연결

[③ 후보](TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md)는 원 admission 의미/profile·현재 Run projection·현재 trace를 분리한다.
다른 key의 수렴에도 key별 request audit/receipt를 남기고 원 Run/기존 mapping을 덮지 않는 안이다.
HTTP body 전체 bytes·현재 failure/headers까지 불변으로 저장하거나 현재FAILED에서 원410/422를 추정하지 않는다.
기존 오류 Run 연결 schema는 유지한다. 새 No-Run receipt·새422·조회 endpoint·HTTP 상태를 추가하지 않으며 ①~③은 미채택이다.


## ④ No-Run 거절은 별도 versioned 계약 후보

[④ 후보](TRADING_REVIEW_REPROCESSING_SOURCE_PIN_POLICY_DRAFT.md)는 정상 legacy pin 부족에 대한 Run 없는 영속 거절을 제안한다.
현재 접수422의 RecordedFailure/Run 참조 필수 조건은 유지한다. 이를 Run ID 생략/null/0·일반 ErrorResponse fallback으로 바꾸지 않는다.
새 variant의 code/required/nullable·profile·receipt 수명/consumer는 미정이며 이번 YAML/DTO/schema 예제에는 추가하지 않는다.
pin이 확정된 raw 부재/compatibility 실패의 WP02 경로를 전부 No-Run으로 바꾸지 않는다. ①~④는 미채택이다.
