# Analysis versioned read·내부 HTTP 초안

상태: **ADR-078·AP01~06 검토 초안, 미채택·runtime 미구현**. active public/Compute API·현재 8개 operation·계산/hash/ACK는 유지한다.
새 path/audience/view/code literal은 리뷰 후보이며 실제 route·issuer/scope·gateway를 활성화하지 않는다.

## 계약 파일과 operation

- [Versioned read](../openapi/drafts/trading-review-analysis-read.yaml): `/trading-review/v2` 아래 9개 GET.
- [내부 HTTP](../openapi/drafts/trading-review-analysis.yaml): `/internal/trading-review/v1` 아래 접수 POST 1개·같은 read view 9개 GET.
- [정적 checker](../scripts/verify-trading-review-analysis-draft.py): refs/registry·schema/example·위험 변이를 검사한다.
- [정책 리뷰](TRADING_REVIEW_ANALYSIS_CONTRACT_DRAFT.md)·[응답](TRADING_REVIEW_ANALYSIS_RESPONSE_DRAFT.md)·
  [selection](TRADING_REVIEW_ANALYSIS_SELECTION_DRAFT.md)·[AR gate/미실행 계획](TRADING_REVIEW_ANALYSIS_CONTRACT_ACCEPTANCE_DRAFT.md)을 함께 적용한다.

| Public read suffix | public operationId | 내부 GET operationId | 단위 |
|---|---|---|---|
| /trading-analysis-runs/{analysisRunId} | getAnalysisRunV2 | ownerGetAnalysisRunV2 | 현재 4상태 Run |
| /trading-books/{tradingBookId}/analysis-runs | listAnalysisRunsV2 | ownerListAnalysisRunsV2 | full Run page |
| /trading-analysis-runs/{analysisRunId}/review | getAnalysisReviewV2 | ownerGetAnalysisReviewV2 | Review view |
| /trading-analysis-runs/{analysisRunId}/metrics/{metricId} | getAnalysisMetricV2 | ownerGetAnalysisMetricV2 | typed Metric wrapper |
| /trading-analysis-runs/{analysisRunId}/review-units | listAnalysisUnitsV2 | ownerListAnalysisUnitsV2 | distinct Unit |
| /trading-analysis-runs/{analysisRunId}/review-units/{reviewUnitId} | getAnalysisUnitV2 | ownerGetAnalysisUnitV2 | Unit + nullable classification |
| /trading-analysis-runs/{analysisRunId}/review-units/{reviewUnitId}/evidence/allocations | listAnalysisAllocationEvidenceV2 | ownerListAnalysisAllocationEvidenceV2 | allocation bundle |
| /trading-analysis-runs/{analysisRunId}/review-units/{reviewUnitId}/evidence/direct-records | listAnalysisDirectEvidenceV2 | ownerListAnalysisDirectEvidenceV2 | direct record bundle |
| /trading-analysis-runs/{analysisRunId}/metrics/{metricId}/memberships | listAnalysisMembershipsV2 | ownerListAnalysisMembershipsV2 | membership |

내부 `admitOwnerTradingAnalysis`는 POST `/internal/trading-review/v1/ledger-revisions/{ledgerRevisionId}/analyses`다.
현재 public `runTradingAnalysis`의 request·idempotency namespace를 보존하고 202/full Run으로 중계하는 후보이며 새 public POST는 추가하지 않는다.
새 GET 두 개는 기존 opaque summary reference와 direct 근거를 실제 bounded 조회로 연결하기 위한 후보다.
기존 7개 public GET/current POST는 현재 OpenAPI에 그대로 남는다. 새 read를 일부 handler에 몰래 섞거나 현재 flat schema를 교체하지 않는다.
`TRADING_ANALYSIS_READ_V2_DRAFT`는 read envelope 후보이며 Compute result/input/hash V2를 의미하지 않는다.

## 인증·권한·trace

Public read는 현재 Core의 Access Cookie 경계다. Core는 외부 internal credential/identity header를 제거하고 승인한 서비스 credential과 별도 서명 위임을 구성한다.
내부 security는 **ServiceCredential AND UserDelegation** 한 항목이다. OR/익명/operation override를 허용하지 않는다.
audience 후보는 `refinvest:trading-review:analysis:v1`, scope는 정확한 `trading-review:analysis:read` 또는 `trading-review:analysis:admit`다.
Import/Account-Book token·wildcard/admin scope를 자동 승격하지 않는다. RS256·issuer/key registry·act.sub/caller binding은 기존 설계 방향을 따르되 여전히 DRAFT다.
metadata가 JWT signature·issuer/clock/skew·실제 caller/권한 검증을 구현한 것은 아니다. 수치 TTL/회전·Go authority/commit 선형화는 별도 gate다.
현재 Member authority·Account/Book owner·Record Guard·published result/source binding을 확인하고 Worker도 현재 실행/발행 권위를 재검증한다.
raw body ownerId·traceparent/tracestate는 인증 근거가 아니다. trace는 선택 header이며 PII/row/token/locator를 담지 않는다.
모든 응답은 no-store이고 public Vary=Cookie, 내부 Vary=Authorization/UserDelegation이다. 실제 browser/relay/cache 검증은 미실행이다.

## req/res와 selection

| 경계 | 초안 표현 | 금지/미확정 |
|---|---|---|
| Admit | 현재 RunTradingAnalysisRequest·필수 Config·optional/null exact VersionSet·publishAsLatest default true | owner/body 추가·원 pin 덮기·새 namespace. AP01 byte projection/실패 재접수는 미채택 |
| Run/status/list | 현재 PENDING/RUNNING/COMPLETED/FAILED union | public CANCELLED·input binding/attempt token/Compute job 노출 |
| Review | per-Metric 통계·완전 quality·원 result/evidence hash·config/version·Go publication stamp | 미정의 전역 count 합성·현재 required 필드 몰래 제거 |
| Metric | 원 Compute typed object/metricHash + 별도 analysisResultHash/Run/summary wrapper | generic scalar 축소·Run ID를 원 metric에 붙여 hash 재생성 |
| Unit | Record facts와 nullable result classification 분리 | null classification을 UNAVAILABLE/0 PnL로 합성 |
| Summary/page | kind/Run/Revision/result/metric/role/reason/order + page/size/total | reference를 권한 token으로 사용·다른 result cache 혼합 |
| Allocation | 원 allocationId·allocation facts·membership IDs·완전 record/snapshot set | recordId+action으로 ID 생성·chain 분할/잘라내기 |
| Direct evidence | 별도 record page·membership IDs·완전 retained snapshot | 가짜 allocation으로 포장·allocation total에 합산 |

page/size 기본 0/20·size max 100, total은 비음수 int64다. 한 응답의 parent/selection/items/total snapshot과 checked offset이 필요하며 page 간 동결은 별도다.
Unit list는 default outcomeClassifications set, metric 지정은 해당 membership의 distinct unit set을 우선 제안한다.
role-only는 새 draft에서 metric을 요구하고 reason은 EXCLUDED role/metric 문맥에서만 허용한다. current optional schema를 이 초안으로 즉시 거부하지 않는다.
Evidence query는 metric을 명시하며 role/reason을 통해 실제 membership provenance를 고른다. membership page는 path의 metric을 selection에 bind한다.
7종 role·복수 reason·candidate/related/baseline 겹침을 보존하고 Unit/membership/allocation/direct record total을 구분한다.
서버가 Analysis selection/order/page를 먼저 정하고 Record는 bounded exact ID와 immutable facts/완전 chain을 제공한다. 후처리 filter/누락 skip·total 보정은 금지한다.
first membership summary는 page 0/size 20이며 같은 selection의 membership endpoint로 전체 집합을 읽는다. 실물 reference/cache·source closure 검증은 AR-G04/05다.

## 오류·수명·원자성

모든 operation은 400/401/403/404/409/410/422/500/503과 safe ErrorResponse를 명시한 **DRAFT 후보**다.

| 상황 | 초안 code 예 | 의미/한계 |
|---|---|---|
| invalid input/인증·권한/부재 | INVALID_REQUEST, AUTHENTICATION_REQUIRED, ACCESS_DENIED, RESOURCE_NOT_FOUND | foreign 존재 유출 방지·엄격 decoder·정확 relay 필요 |
| read 미완료/삭제/unsupported view | RESULT_NOT_AVAILABLE, RESOURCE_DELETED, READ_VIEW_UNSUPPORTED | source availability·실제 Go lifecycle, Compute TTL로 정상 retained result 차단 금지 |
| 내부 인증 실패 | SERVICE_OR_DELEGATION_REJECTED | browser 세션 오류로 바로 변환/logout하지 않음. Core의 안전한 upstream 변환 gate |
| admission conflict/version/source | IDEMPOTENCY_CONFLICT, ANALYSIS_REQUEST_CONFLICT, ANALYTICS_INPUT_UNAVAILABLE, VERSION_UNAVAILABLE | 원 key/effective pin/source/publish 의도 구분, current 422와 새 code의 compatibility 필요 |
| 의존성/UNKNOWN | DEPENDENCY_UNAVAILABLE, ADMISSION_OUTCOME_UNCONFIRMED | retryable은 same-key 복구/승인 정책 의미. 새 key/중복 접수 허가 아님 |
| 내부 integrity | INTERNAL_ERROR | raw driver/SDK/원문 값 노출 금지, 운영 원인 분류/복구는 별도 |

위 code/message·status 조합은 현재 registry에 채택되지 않았다. 특히 현재 public POST로의 새 내부 code 중계는 별도 compatibility 명세가 필요하다.
read schema/version 선택 자체가 raw payload의 hash projection을 바꾸지 않는다. actual capability/complete quality는 같은 원 Revision에서 결합한다.
Normalization qualityReport에는 capability가 별도 root일 수 있으므로 public full qualitySummary에 원 published capability를 명시 투영한다. 누락을 []/0으로 채우지 않는다.
V2 proof는 계속 Run/artifact/dispatch 이전이다. 이후 짧은 PENDING/Dispatch 접수·Worker 입력 준비·create 전 once-only full binding은 Go 설계 방향이다.
준비 실행/cleanup budget은 미확정이며 Compute Job 생성부터 24h cutoff와 다르다. 브라우저 abort는 durable 취소가 아니다.
일반/복합 발행은 module/Workflow 동일 실제 Tx·current Guard·sealed stage·원 receipt와 commit 후 모듈 ACK를 따른다. HTTP Publish/cancel route를 추가하지 않는다.
local FAILED/취소는 원격 종료나 새 stale ACK 원인이 아니다. generation/attempt 외 late payload 처리는 별도 채택 조건이다.

## 정적 검증 범위와 남은 gate

checker는 신규 YAML 중복 key·등록되지 않은/외부/이탈 refs·operation/security/namespace/scope·required/nullable/page·status별 example을 검사한다.
기존 원정본은 동일 값의 중복 선언과 string description annotation의 established parsing만 호환하며 상충하는 schema 선언은 거절한다.
새 draft의 모든 중복은 거절하고 기존 core/compute 파일 bytes는 수정하지 않는다. 이 호환은 원정본 전체 lint 정리 완료 주장이 아니다.
원 Metric 예제는 고정 Analytics oracle와 값/hash/첫 membership page를 비교한다. evidence 예제는 별도 synthetic Normalization projection으로 schema/참조 연결만 검증한다.
예제 전체가 하나의 실제 실행/DB/Compute pipeline에서 생성됐다고 주장하지 않는다. 기존 oracle checker의 hash 계산과 새 read checker의 equality/shape 검증도 구분한다.
JWT/authority·IANA/typed ID catalog/Snowflake node·financial 재계산·SQL/lock/UNKNOWN·실제 masking registry·팬아웃/메모리·부하/E2E는 미실행이다.
vendor extension은 verifier/서버 구현이 아니다. AR-G01~08·16개 제품 계획과 Go AUC/AND/LUC 계획은 그대로 미실행이다.

integration #59의 exact pair 분류/gate가 Context preview보다 먼저 main에 반영돼야 한다. Core #129 문서 snapshot도 별도 선행 의존이다.
candidate sync와 main sync를 구분하고 Context main 이후 새 두 schema/refs·docs를 Core snapshot에 전파한다. Go는 고정 링크를 쓰며 아직 snapshot 체계가 아니다.
신규 route/view·profile/code 채택/활성화·실제 DB/ID/checkpoint·단일 writer 인계는 별도다. 다음은 이 초안/consumer 리뷰와 Analysis 저장·Tx 설계다.
