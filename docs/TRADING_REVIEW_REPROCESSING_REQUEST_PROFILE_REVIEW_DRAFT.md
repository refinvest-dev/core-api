# ADR-082 채택 범위 검토·수정 권고

상태: **채택 전 검토안**, 2026-11-16. 사용자의 다음 작업 요청에 따라 후보와 실제 source를 대조했다.
이 문서/PR merge는 정책 채택이 아니다. ADR-082는 DRAFT이고 ADR-080/081의 확정 namespace·원 receipt/No-Run·보존 기간은 유지한다.
[후보](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE_DRAFT.md)·[admission 계약](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)을 함께 읽는다.

## 채택 범위 권고

| 항목 | 권고 | 범위와 이유 |
|---|---|---|
| 재처리 전용 versioned closed nested | 채택 권고 | 현재 Compute transport가 nested extra를 거절. shared/legacy schema를 일괄 축소하지 않고 dynamic instrument map은 명시 유지 |
| 입력 검증 원칙 | 채택 권고 | duplicate/coercion·손실 보정 금지, profile별 presence 유지, byte/depth/cardinality 예산은 bounded하게 검증 |
| 형식/자원 한도와 business decision 분리 | 채택 권고 | 새로운 형식/한도 오류를 Run/No-Run422로 저장하지 않음.413 채택 시 registry·relay·client 함께 반영 |
| 동일 시점의 다른 표기 자동 동등화 | 기존 권고 수정 | 현재 Config hash는 시각 문자열을 보존. 같은 시점만으로 hash/execution 동등성을 선언하면 안 됨 |
| 신규 profile의 시각 표기 | 아래 수정안 채택 권고 | UTC Z, 소수초0이면 생략·비영이면 정확6자리인 canonical 입력만 허용하는 방향. server 자동 정규화 아님 |
|1MiB/depth16/문자열256/zone128/terms4096 | 수치 채택 보류 | 후보값이며 실제 caller/legacy inventory·bounded decoder/메모리/consumer 근거가 없음 |
| minimums int32 upper bound | 채택 보류 | 현재 DTO/hash 함수가2^31도 허용. 저장 타입 편의를 새 제품 제한으로 삼지 않음 |

이 표는 결정 요청을 위한 구체 권고다. 승인 전 정책 상태를 바꾸거나 새 YAML/runtime에 적용하지 않는다.
수치 보류는 무제한 입력 허용 권고가 아니다. 해당 신규 profile의 정확 상한은 실제 구현/활성화 전에 문서/schema/registry에서 확정해야 한다.

## source 대조

| 고정 source | 관측과 한계 |
|---|---|
| [Compute hash](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/analytics/identity.py) | analysis_config_hash는 Config 문서의 JCS hash. datetime 문자열을 파싱해 UTC로 바꾸지 않음. safe integer 범위 검증은 int32와 다름 |
| [Compute DTO](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/schemas/trading_analytics_jobs.py) | nested extra forbid·정확 세 Metric key·minimums 양수 strict integer. 이 필드들의 int32 상한은 없음 |
| [Compute route](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/api/routes/trading_analytics_jobs.py)·[admission](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/application/analytics_jobs.py) | model_dump(mode=json,by_alias=True) 이후 identity 검사. 기존 route를 새 Go 재처리 HTTP 지원 증거로 보지 않음 |
| [Core builder](https://github.com/refinvest-dev/core-api/blob/54892e23b3fca9d6e624176b17cc57ff40c01776/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/TradingAnalyticsInputBuilder.kt)·[canonicalizer](https://github.com/refinvest-dev/core-api/blob/54892e23b3fca9d6e624176b17cc57ff40c01776/tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/NormalizationResultIntegrity.kt) | Config JSON을 object로 읽어 canonical hash. inputIdentity 경로가 AnalysisPeriod 문자열을 UTC 정규화한다고 관측하지 않음 |
| [고정 fixture](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/tests/fixtures/trading_review/analytics_protocol/v1/input-identity-v1.json) | 원 Config 시각은 소수초 없는 Z. 기존 hash/vector는 수정하지 않음 |
| [Web client](https://github.com/refinvest-dev/web/blob/9354dfe321ee8543c10e0914249f06d6643ca6db/src/shared/api/http.ts) | status/code/message 중심 오류 변환. 새 reprocessing profile/413 소비나 trace/retryable 보존을 실제 검증한 것은 아님 |

repository source/고정 fixture·로컬 순수 함수/DTO 관측이다. 운영/외부 consumer inventory·HTTP/DB/Compute job·새 업무 semantic 수용을 실행하지 않았다.

## 시각/hash 반례 — 실제 로컬 관측

기존 Compute hash 함수를 원 fixture Config로 호출해 expected hash 일치를 확인한 뒤 시각 표기만 바꿨다.

| Config 시각 | stdlib 시점 비교 | 원 fixture와 Config hash |
|---|---|---|
|2025-01-01T00:00:00Z (원본) | 기준 | expected hash 일치 |
|2025-01-01T09:00:00+09:00 | 동일 시점 | 다름 |
|2025-01-01T00:00:00.000000Z | 동일 시점 | 다름 |

Config의 object key 순서만 바꾸면 hash는 같았다. 이는 JCS key 순서 동등성이지 timestamp 문자열 동등성이 아니다.
새로 만든 HTTP test/계산 fixture가 아니라 기존 함수에 synthetic 변형을 전달한 국소 관측이다.

기존 가상환경의 Pydantic2.13.4에서 AnalysisConfigTransport.model_validate/model_dump를 직접 호출한 결과:

| 입력 startInclusive | model_dump 결과 | 입력 Config 대비 hash |
|---|---|---|
|...00Z |...00Z | 유지 |
|...00.000000Z |...00Z | 달라짐 |
|...09:00:00+09:00 |...09:00:00+09:00 | 유지 |
|...00.1Z |...00.100000Z | 달라짐 |
|...00.100000Z |...00.100000Z | 유지 |
|...00.000001Z |...00.000001Z | 유지 |

관측한 dependency 버전을 기록한 것이며 배포 환경/모든 Pydantic 버전 보장이 아니다. 기존 route가 이 projection 뒤 identity를 검사하므로 실제 전체 wire 왕복 확인이 필요하다.
본문 hash만 재계산해 원 inputIdentity/expectedInputHash를 남기면 일관성이 깨진다. 원 Compute identity/fingerprint·legacy pin을 한쪽만 보정하지 않는다.

## 수정 권고 — 신규 profile의 고정된 입력 표기

입력부터 protocol이 정한 한 표기만 허용하는 방향을 권고한다. DTO library의 기본 출력을 정본으로 채택하는 뜻이 아니다.

- UTC `Z`·대문자 T/Z만 사용한다. 초 이하 값이0이면 소수초를 생략하고, 비영이면 정확6자리로 표현한다.
- 예: `2025-01-01T00:00:00Z`, `2025-01-01T00:00:00.100000Z`, `2025-01-01T00:00:00.000001Z`.
- offset 표기·짧은 fraction·`.000000Z`를 서버에서 자동 고쳐 접수하지 않는다. 이 새 profile을 채택하면 형식 거절400 후보이며 지금 서버에 적용하지 않는다.
- 유효 원 시각과 변환된 UTC 시각의 year0001~9999·정밀도를 검증하고 leap second/unknown offset·초과 정밀도를 truncate/round하지 않는다.
- 정당한 원 Config를 그대로 계산/DTO·hash·inputIdentity/fingerprint에 사용한다. Go/New/Restore가 최신 clock/default로 원 Config를 재작성하지 않는다.
- start < end와 업무 Config 검증·원 code/receipt는 별도다. 새 표기 계약은 canonical 입력/producer/consumer/hash vector 왕복을 먼저 검증한다.

기존 profile과 모든 과거 ID/hash/key/vector는 그대로 보존한다. 같은 instant의 legacy 표기를 새 profile로 바꾸고 기존 execution과 자동 수렴시킬 수 없다.
다른 대안으로 offset 등을 받고 hash 전 정규화할 수 있으나, 이 경우 정규화/Config hash·transport profile 전체를 별도 version 계약으로 설계해야 한다. 이번 권고는 그 자동 변환을 도입하지 않는다.
result timestamp 직렬화 규칙을 Config 입력 문자열에 일괄 적용하지 않는다. 원 fixture가 소수초 없는 Z라는 사실도 함께 보존한다.

## 수치·오류 판단

기존 pure hash 함수와 DTO는 minimums의2^31을 허용했다. hash 함수의2^53은 INPUT_SCHEMA_UNSUPPORTED로 거절됐다.
이는 실제 Metric 실행 적격성/제품 maximum을 검증한 것이 아니다. 기존 JCS safe integer 한계·도메인 양수 조건과 새 제품 상한은 구분한다.
1MiB 등 후보는 코드/문서에 이미 있을 법하다는 추정으로 확정하지 않는다. body JSON octet·content encoding/해제 예산·root depth/array/object 계산·UTF8 byte/문자 길이·동적 map을 각각 정의해야 한다.
413의 status/code/cache/현재 trace/relay·Web 처리와 byte 오류/형식 오류 우선순위를 함께 설계한다. body가 너무 커 decoder에 도달하지 못한 상황을 No-Run/UNKNOWN receipt로 추정하지 않는다.
원 receipt replay도 현재 Member/Guard가 먼저이며, 저장 profile/원 decision을 신규 validation으로 덮지 않는다.

## 후속 순서와 미실행

먼저 위 채택/수정/보류 범위를 결정한다. 그 뒤 versioned profile 식별·local closed schema·canonical 입력 vectors·registry/consumer를 일관되게 설계한다.
수치·운영 inventory 및 정확 Run/control FK·권한/DB history는 별도 gate다. DB lock 기술 실험은 채택과 독립적으로 계획할 수 있으나 업무/운영 활성화 증거는 아니다.
현재 새 YAML/code/DB·새 golden fixture·HTTP/SQL/경합/복원/부하/E2E는 추가·실행하지 않았다. local hash/DTO 관측을 그 수용 성공으로 확대하지 않는다.

## 논의 정리·요청 예산 후속

[요청 예산 검토](TRADING_REVIEW_REPROCESSING_REQUEST_BUDGET_REVIEW_DRAFT.md)는 기존200자 제한·UTF8 bytes·terms/body/depth 후보의 조합을 실제 synthetic 측정으로 보완한다.
int32에 대해서는 단순 수치 보류를 넘어 **저장 타입 때문에 별도 제품 상한을 도입하지 않는 방향**을 권고한다. 기존 JSON/hash 안전 범위·양수 조건은 유지한다.
구조/검증 원칙·수정 시각 입력의 채택 여부는 명시 결정 대기이며, 정확 크기 수치는 여전히 후보다. 측정/문서 merge를 채택으로 해석하지 않는다.
