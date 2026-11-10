# Ledger evidence page·selection 계약 리뷰 (DRAFT)

상태: **ADR-077·LP05 미채택 후보**. [상위 리뷰](TRADING_REVIEW_LEDGER_CONTRACT_DRAFT.md)와 함께 읽는다.
현재 flat public schema·page/size/total·계산 결과/해시·masking을 바꾸지 않는다. 새 endpoint/version literal은 아직 정하지 않는다.

## 현재 계약의 두 간극

[API §7](TRADING_REVIEW_API.md#7-evidence-query)은 allocation→record→snapshot bundle과 allocation chain의 page 비분리를 요구한다.
반면 [현재 OpenAPI](../openapi/core-api.yaml)의 ReviewUnitEvidenceItem은
membershipRole + allocation + tradingRecord + sourceEvidence의 flat item이다. PositionAllocationEvidence에는 allocationId가 없다.
allocation 하나의 근거가 여러 record/snapshot을 포함하는 경우 item 수·size·total과 chain 보장이 충돌할 수 있다.
검토한 source를 넘어 모든 실제 payload가 다대일이라는 주장은 아니다. 단일 근거 표본만으로 일반 fan-out 계약을 생략할 수 없다.

또한 ListReviewUnits는 metricId/membershipRole 선택자가 있지만 현재 GetReviewUnitEvidence 요청은 page/size만 가진다.
동일 unit이 metric별 서로 다른 membershipRole/exclusionReason을 가질 수 있어 run+unit만으로 metric 문맥이 유일하지 않다.
다른 metric의 role을 가져오거나 모든 item을 SUPPORTING으로 채우는 방식은 허용하지 않는다.

## LP05 우선안 — 명시 versioned allocation bundle

아래는 schema가 아닌 **논리 shape 검토표**다. 필드명·required/nullability·path/media type·version명은 후속 schema 리뷰에서 확정한다.

| 논리 요소 | 우선안 의미 |
|---|---|
| selection context | AnalysisRun + ReviewUnit + 명시 metric/selection binding, 동일 result/config/version의 membership 근거 |
| 한 page item | 원 allocationId + allocation 내용 + 그 allocation의 완전한 record/snapshot evidence set |
| page/size/total | selection 안의 allocation bundle 수 기준. record 수나 page 수로 total을 바꾸지 않음 |
| role/exclusion | 선택한 문맥에서 결과에 고정된 실제 값/근거. unit 공통 속성으로 추정하지 않음 |
| identity/순서 | 원 allocation/record/snapshot identity와 version에 고정된 stable order. 임의 복합 key 재발급 없음 |
| availability | 현재 lifecycle에 따른 masked retained evidence와 raw 상태. raw bytes/URL/전체 row inline 없음 |

allocationId는 persisted/public result projection에서 증명된 원 identity를 써야 한다.
tradingRecordId+action은 여러 allocation/동일 action·reversal/다른 unit에 유일하다고 보장되지 않으므로 key로 합성하지 않는다.
Page 안의 한 allocation chain을 분리하지 않고 모든 record와 snapshot 참조의 Revision/owner/Book binding·중복/누락을 검사한다.
Record는 immutable chain을 제공하고 Analysis가 result-bound selection/role과 연결한다. Record가 metric 정의를 해석하지 않는다.

allocation 순서는 현재 실제 result의 semantic order/ordinal을 조사해 version 규칙으로 고정한다.
DB 반환 순서나 string ID 숫자 정렬을 근거 순서로 삼지 않는다. 배열 order가 hash에 들어가는지와 공개 정렬은 별도 명시한다.
한 bundle이 너무 클 수 있으므로 page cap 외에 fan-out·bytes·조회/조립/heap·proxy budget을 검증한다.
1개 bundle도 제공할 수 없는 정상 데이터가 존재하면 이 우선안만으로 구현하지 않는다. bounded complete-group 전달이나
별도 evidence resource 참조/상세 조회의 의미를 다시 설계하고 consumer/hash/chain 정합성을 함께 검토한다.
한도를 넘은 근거를 생략·잘라내거나 실제보다 작은 total/완전함 표시를 반환하지 않는다.

## selection context 선택

우선 방향은 versioned 요청에 **명시 selection 문맥**을 제공하는 것이다. 단순 metricId만으로 충분한지,
subject/comparison/excluded·boundary와 selected membership까지 구분해야 하는지 실제 result를 대조한다.
서버가 검증하는 opaque selection reference를 쓰는 대안도 비교한다. 어느 쪽도 이 초안에서 wire를 확정하지 않는다.

검증은 같은 AnalysisRun/result·config/version·ReviewUnit과 선택된 membership 관계에 한정한다.
다른 Run/metric/unit에서 얻은 reference를 재사용하지 못하게 하고 공개 식별자를 권한 token으로 취급하지 않는다.
문맥 누락/모호함·다른 role 요청은 승인된 schema/오류로 처리해야 하며 첫 metric/임의 기본값으로 성공시키지 않는다.
기존 요청을 계속 지원하려면 현재 flat 의미의 유일한 해석을 입증하거나 명시 legacy compatibility 경로를 둬야 한다.
새 selector를 optional additive로 붙이는 것만으로 기존 요청의 모호성이 해결됐다고 보지 않는다.

대안은 metric과 무관한 canonical evidence만 반환하고 metric role/exclusion은 다른 result resource에 두는 것이다.
이 경우 현재 EvidenceItem의 required membershipRole과 consumer join을 변경해야 하므로 역시 명시 schema/version 전환이 필요하다.
어느 문맥에서도 추정한 role을 저장된 계산 결과인 것처럼 표현하지 않는다.

## flat group 유지 대안과 호환성

기존 flat item을 유지하면서 allocation group 전체를 한 page에 넣는 대안이 있다.
그러나 size가 flat item 수이면 경계 초과/빈 page, total이 flat 수인지 allocation 수인지와 offset 계산을 다시 정의해야 한다.
allocationId 없이 group 식별도 어렵다. 원 chain 보장을 약화시키거나 size 의미를 조용히 바꿔서는 안 된다.
명시 versioned bundle은 pagination 단위를 선명하게 하므로 우선 제안하지만 consumer 비용·fan-out 검증 전 채택하지 않는다.

현재 decoder는 flat allocation/record/snapshot을 기대할 수 있다. 같은 path/버전에서 nested bundle로 바꾸지 않는다.
새/legacy 병행·전환 순서, Core proxy passthrough/typed DTO, Web·외부 client와 SDK strict decoder,
cache key(selection 포함)·폐기/rollback 정책을 실제 consumer 조사와 함께 정한다.
version 전환이 기존 결과의 자동 재계산·Revision 재발급을 뜻하지 않는다. retained proof로 새 view를 재현할 수 있는지 먼저 확인한다.

## evidence hash·접근·수용 gate

기존 canonical evidenceHash·normalization resultHash/ledgerContentHash·Analytics inputHash는 서로 다른 계약이다.
HTTP bundle을 일반 JSON hash로 다시 계산해 저장된 evidenceHash를 대체하지 않는다.
shape/selection/order/masking이 기존 hash projection을 바꾸는 경우 정확한 definition/evidence schema version과 oracle을
[Versioning](TRADING_VERSIONING.md)에 맞춰 별도 등록한다. 단순 transport wrapper만 바뀌는 경우도 hash 불변 근거를 명시한다.
기존 immutable 결과/hash를 수정하거나 최신 projection을 과거 version에 적용하지 않는다.

현재 owner/Guard와 retained masking allowlist를 조회 때 검증한다. raw-only는 retained Ledger/Analysis를 보존하되
raw 접근을 복원하지 않는다. 이미 시작한 조회/Signed URL 철회는 별도 lifecycle 정책이며 이 페이지 계약이 철회를 보장하지 않는다.
민감 identifier·raw cell·locator·전체 source hash를 로그/trace에 복제하지 않는다.

채택에는 [LR-G05~08 및 미실행 계획](TRADING_REVIEW_LEDGER_CONTRACT_ACCEPTANCE_DRAFT.md)의 실제 consumer·
schema/decoder·hash oracle·경계 fan-out/부하·삭제/권한 검증이 필요하다. 현재는 Markdown/source 비교만 수행한다.
