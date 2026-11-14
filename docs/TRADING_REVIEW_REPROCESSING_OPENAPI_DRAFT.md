# Reprocessing public/internal OpenAPI 후보

> **2026-11-15 채택 반영:** ①~④의 동작 원칙은 [채택 정책](TRADING_REVIEW_REPROCESSING_POLICY.md)·ADR-080을 따른다.
> 아래는 채택 전 검토 기록과 미확정 상세다. 과거 미채택/권고 문구가 이번 명시 채택을 취소하지 않는다.
> 정확 schema/code·receipt 수명/삭제·profile/legacy 적용은 관련 구현 전에 문서로 확정하며, 현재 wire/runtime은 유지한다.

## 채택 전 검토 기록과 남은 상세


상태: **ADR-079/WP01~04 DRAFT·미채택**. [HTTP registry](TRADING_REVIEW_WORKFLOW_HTTP_DRAFT.md)와
[DTO 후보](TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md)를 기계가 검사할 수 있는 검토 자료로 표현한다.
현재 `core-api.yaml`·Compute 계약·canonical hash/vector·업무 runtime·DB/운영 설정은 유지한다.
schema PASS나 문서 merge는 제품 정책·issuer/권한·client 지원·업무 구현 전환 승인이 아니다.

## 파일과 소유권

| 파일 | 역할 |
|---|---|
| [public 초안](../openapi/drafts/trading-review-reprocessing-public.yaml) | versioned public 4개 operation, DTO·synthetic 예제의 소유자 |
| [internal 초안](../openapi/drafts/trading-review-reprocessing.yaml) | 내부 4개 operation, public DTO 참조·별도 service/위임 profile |
| [정적 검사기](../scripts/verify-trading-review-reprocessing-draft.py) | exact registry/security·refs·schema/예제·위험 변이. HTTP handler가 아님 |

접수·Book별 목록·Run 조회·취소만 포함한다. method/path/operationId는 HTTP registry 후보의 literal을 따른다.
public prefix는 `/trading-review/v2`, internal prefix는 `/internal/trading-review/v1`이다.
삭제 4개·shared deletion status는 기존 Account/Book·Import 소유 draft를 재사용하며 여기서 다시 정의하지 않는다.
Worker/operator/Member cascade·prepare/publish·ACK·remediation endpoint도 추가하지 않는다.

## 요청과 버전 계약

`RequestReprocessingV2Draft`는 source Revision/type/targetVersionSet/requestedOutputs/reasonCode를 필수로 둔다.
root 추가 필드·numeric ID·필수/선택 non-null 필드의 NULL을 거절한다. opaque resource ID와 decimal Member subject는 별개다.
`sourceAnalysisRunId`는 omitted/null 허용, `retryOfReprocessingRunId`는 생략 또는 non-null ID,
`publishAsLatest`는 생략 시 true·명시 NULL 거절이다. reasonCode는 기존 public 4종을 유지한다.

| type | outputs | 필수 non-null target component |
|---|---|---|
| REPARSE_SOURCE | LEDGER_ONLY / LEDGER_AND_ANALYSIS | normalizer + reconstruction; Analysis 포함 시 analytics |
| REBUILD_LEDGER / RERUN_RECONSTRUCTION | LEDGER_ONLY / LEDGER_AND_ANALYSIS | reconstruction; Analysis 포함 시 analytics |
| RERUN_ANALYTICS / REEVALUATE_FINDINGS | ANALYSIS_ONLY | analytics |

8개 type/output 조합을 `oneOf`로 구분한다. Analysis 포함 시 완전한 기존 AnalysisConfig를 요구하고,
LEDGER_ONLY에서는 config의 생략을 요구한다. 필요한 component의 하위 필수 필드는 현재 정본 definition을 참조한다.
기존 TargetTradingVersionSet이 hash만으로 구조 검사를 통과할 수 있는 부분을 이 후보의 요청에서 강화한 것이다.

사용하지 않는 component가 원 tuple에 있으면 삭제하거나 NULL을 생략으로 바꾸지 않는다. Ledger-only도 원 full tuple/hash를 보존한다.
component 정의·VersionIdentifier·AnalysisConfig 의미 및 기존 logical SHA root는 재정의하지 않는다.
outputs/publish/retry는 full binding의 비교 항목이며 새 hash field·namespace로 충돌을 우회하지 않는다.
schema의 완전성은 version registry/compatibility·실행 지원·hash 정합성의 증거가 아니다.
canonical rebuild·Finding-only·private source profile의 실제 Compute 지원은 여전히 WR gate다.

root Request/Run/Error/Difference/tuple은 닫힌 객체다. 참조한 Config·component VO의 하위 허용 범위는 현재 정본을 따른다.
중첩 unknown field를 포함한 실제 Go decoder·생성 client 동작은 consumer gate에서 대조한다.
duplicate JSON/query·type coercion·취소 body 거절은 선언과 정적 사례이며 실제 HTTP decoder 구현은 별도다.

## Run·Page·NULL 표현

현재 Run의 6상태와 기존 필드 의미를 유지하면서 outputs/publish/reason·created child IDs/difference/failure/completedAt의
명시 반환을 요구하는 versioned 후보로 강화했다. 현재 optional 필드를 곧바로 필수 구현으로 간주하지 않는다.
progressStage는 기존 string이며 새 vocabulary를 채택하지 않는다. lineage는 최소 1개이며 미확인 값을 빈 배열로 보정하지 않는다.

| 상태 | 공개 child/difference | failure / completedAt |
|---|---|---|
| PENDING / VALIDATING_INPUT / RUNNING | 모두 NULL | NULL / NULL |
| FAILED | 모두 NULL | SafeFailure / non-null |
| CANCELLED | 모두 NULL | nullable SafeFailure / non-null |
| COMPLETED | 요청 산출물 조합의 ID·완전 Difference | NULL / non-null |

COMPLETED Ledger-only는 R2만, pair는 R2+A2, Analysis-only는 A2만 반환한다.
Analysis-only의 Difference targetRevisionId는 source Revision을 가리킨다. 실제 참조/버전 일치는 Application/저장 수용 대상이다.
준비 중 private ID·locator·grant·token·lease는 공개 DTO에 없다. immutable 최종 decision만 공개 child의 근거다.
FAILED/CANCELLED의 이전 공개 R1/A1은 보존하며 새 요청의 child 공개 ID와 혼동하지 않는다.

nullable 객체는 `oneOf: [객체, NullOnly]`로 명시하고, NullOnly는 typed nullable + `enum: [null]`이다.
검사기는 nullable을 type 허용 범위에만 반영하며 enum/allOf 등 다른 제약을 우회시키지 않는다.
이 표현은 [OpenAPI 3.0.3 Schema Object](https://spec.openapis.org/oas/v3.0.3#schema-object)의 nullable 의미를 따른다.
기존 canonical nullable 객체를 수정하지 않고 새 Difference의 필드/필수 목록·정본 component를 참조한다.
생성기/consumer의 union·required/nullable round-trip 지원은 별도 확인한다.

Page는 items/page/size/total, 요청 기본 page=0·size=20/최대100이다. 새 cursor/sort나 repair side effect는 없다.
page total/목록의 실제 동일 snapshot은 DB/Reader 검증 대상이다. schema integer 검사는 파싱된 query 값에 대한 검사다.

## 오류·receipt·권위

접수/취소 202, Query 200과 400/401/403/404/409/410/422/500/503의 code/retry 후보를 명시했다.
오류 literal은 제안 상태이며 Core 기존 처리·consumer와 대조 후 채택한다. 504 relay 정책·수치 budget은 확정하지 않는다.
내부 service/위임 거절과 public 브라우저 인증 거절은 분리하며 내부 401을 그대로 browser logout으로 번역하지 않는다.

일반 ErrorResponseV2Draft는 code/message/traceId/retryable·기존 safe details만 허용하고 Run ID를 허용하지 않는다.
접수 410의 확정 source 실패, 422의 확정 business rejection만 RecordedFailureV2Draft와
`reprocessingRunId`를 허용한다. WP02 실패 Run·원 receipt/mapping의 **commit 확인 + 현재 owner 접근 승인**이 전제다.
접수422는 RecordedFailure와 No-Run 전용 branch를 code로 구분한다. RecordedFailure의 Run 참조 필수 조건은 유지한다.
No-Run은 REPROCESSING_SOURCE_PIN_UNAVAILABLE/UnpinnedSourceRejectionV2Draft만 허용하며 영속 거절 commit·현재 접근 확인이 전제다.
기록 실패 code에서 Run 참조를 빼는 fallback이나 일반 error 완화는 없다.
이는 metadata/shape일 뿐 schema가 commit·소유권을 증명하지 않는다. malformed/foreign/auth/UNKNOWN·Query/취소 오류에는 Run ID가 없다.

`ADMISSION_OUTCOME_UNCONFIRMED` 503은 같은 key/fence 복구 의미이며 ID를 추측하거나 새 Run을 허용하지 않는다.
원 202 이후 FAILED가 되어도 원 admission 종류는 202이며 현재 failure는 Run에서 읽는다.
원 410/422도 현재 접근 승인 뒤 원 kind/Run을 replay한다. 현재 Member/Guard는 과거 receipt보다 먼저 적용한다.
요청마다 trace는 새로 전달하며 ACK의 고정 body 재전송과 구분한다. 요청 namespace는 기존 두 개를 유지한다.

민감 응답은 no-store, public Vary=Cookie, internal Vary=Authorization + delegation 후보다.
traceparent/tracestate는 선택 header이며 실제 검증/크기·전파 제한은 runtime gate다. public Location 의무를 새로 만들지 않는다.
service credential AND signed delegation·exact reprocessing audience/scope/caller·현재 Member와 Go owner/Guard를 함께 요구한다.
TTL/skew/issuer rotation·Member 차단/commit 선형화·Worker 권위는 미확정이다. 브라우저 abort는 durable 작업 취소가 아니다.

## 실제 정적 검증과 미실행

명령: `python -B scripts/verify-trading-review-reprocessing-draft.py`.
초기 후보에서8개 operation·152개 operation별 schema 예제 검증·177개 schema 사례·24개 거절 변이를 통과했다.
ADR-080 후속 No-Run branch의 새 결과는 아래 상세 계약 연결/로컬 검토 기록을 따른다.
예제 검증 수는 공유 예제를 public/internal의 여러 응답에서 다시 검증한 횟수이며 서로 다른 실행 수가 아니다.
Version/digest/hash는 명시적인 synthetic 값이다. 알려진 0/빈 차이도 synthetic 사실이며 unknown 값을 보정하는 서버 정책이 아니다.
WP02 source/version 실패·private 진행·3종 완료/취소·원202 replay·빈 Page 등은 완전한 transport 예제다.
이를 hash oracle·정합된 실제 DB dataset·실행 가능 registry/Compute profile로 사용하지 않는다.

위험 변이는 runtime flag·credential OR·audience/scope/namespace·CSRF·cancel body·추가 route/field·private child·
UNKNOWN 오류/Run ID·opaque ID·cache·잘못된 차이 참조/외부 ref·YAML/JSON 중복 key 등을 검사한다.
선택한 schema 제약과 예제 참조를 검사한 결과이며 모든 OpenAPI 도구·format/decoder 구현의 동등성을 증명하지 않는다.
WR-DRAFT-01~14는 **모두 semantic 미실행**이다. JWT/HTTP/현재 권위·Member/commit 선형화·hash/SQL/경합/UNKNOWN·
private R2/A2·Compute child/Finding·고정 ACK/수명·Analytics actual absence·consumer/legacy·운영 이관/E2E는 별도다.

## 전파와 다음 gate

[Integration #61](https://github.com/refinvest-dev/integration/pull/61)의 exact 분류/gate와
[Core #131](https://github.com/refinvest-dev/core-api/pull/131)의 앞선 문서 snapshot이 먼저 병합됐다.
새 후보는 Context 정적 CI/impact preview → Context merge → 최신 Core snapshot PR 검토/반영 → 최신 sync 검사를 따른다.
Core/Compute/Web snapshot 일치 전에 E2E를 진행하지 않는다. Go는 이 후보의 고정 commit 링크로 연결하며 snapshot 도입을 주장하지 않는다.

후속 [consumer/legacy 통합 리뷰](TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md)에 소스 관측·원 receipt/불명 pin·
Web/Core 오류·child projection·잔여 WF/WR gate를 연결했다. 다음은 identity/full binding·원 receipt/profile 상세화다.
현재 request→versioned request의 outputs/default/명시 retry 변환을 추측하지 않는다. WR-DRAFT ID·기존 TR-ACC/WUC/WFP 계획을 재사용하며
정적 테스트 수를 업무 수용 완료 수로 올리지 않는다. 정책 채택·첫 구현 범위는 별도로 확정한다.


## ADR-080 후속 admission 계약

[상세안](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)은 admit422에 한정한 No-Run 전용 branch·4필드/code·
원 request descriptor/facts·단계별 분류·same-key/현재 Guard/UNKNOWN을 연결한다. public/internal의 branch가 같은 의미다.
기존 RecordedFailure code에서 Run ID 생략·No-Run에 Run/receipt ID 추가·다른 status/Query/cancel에서 No-Run을 허용하는 변이를 거절한다.
수명은 [별도 선택 대기](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION_DRAFT.md)다. schema가30일/Book 수명·commit·원 source 부족을 증명하지 않는다.
wire profile은 DRAFT/runtime false이며 ADR-080 원칙 채택과 구분한다. 새 operation/namespace·hash/Compute·active API는 변경하지 않는다.


후속 실제 정적 결과: 8개 operation·154개 operation별 schema 예제 검증·275개 schema 사례·29개 거절 변이 통과.
예제 횟수는 공유 예제의 public/internal 반복 검증을 포함하며 서로 다른 업무 실행 수가 아니다.
검사기는 전체 resolved schema 내용으로 메타 검증을 bounded cache하며 원본 mutation과 다른 내용을 같은 cache key로 취급하지 않는다.
이 결과는 수명 선택·source 분류·commit/권위·HTTP/SQL·새 semantic/E2E 수용 증거가 아니다.
