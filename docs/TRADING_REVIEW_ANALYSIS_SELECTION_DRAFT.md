# Analysis selection·Unit·근거 리뷰 (DRAFT)

상태: **ADR-078·AP04 미채택**. [상위 리뷰](TRADING_REVIEW_ANALYSIS_CONTRACT_DRAFT.md)·
[응답 후보](TRADING_REVIEW_ANALYSIS_RESPONSE_DRAFT.md)와 함께 읽는다.
현재 optional query·4종 public role·required outcome·flat evidence를 변경하지 않는다.

## selection의 문맥과 기본 set

우선 후보는 versioned 조회에 result-bound selection을 명시하는 것이다.
selection의 논리 identity는 Run/resultHash·정확한 Metric(있다면)·role/reason filter·단위(Unit/membership/allocation/direct evidence)다.
원 result가 불변이어도 서로 다른 selection을 같은 cache key/reference로 읽지 않는다.

| versioned Unit 요청 | 후보 set·판정 |
|---|---|
| metric/role 모두 없음 | 해당 result의 outcomeClassifications ReviewUnit set. 각 unit의 source facts와 결과의 classification 결합 |
| metric만 있음 | 그 Metric의 모든 membership이 참조한 distinct UnitID set. 여러 role/reason이어도 unit total은 한 번 계산 |
| metric+명시 role | 그 Metric의 해당 role membership에서 distinct UnitID. 필요 시 reason을 명시 필터로 구분 |
| role만 있음 | metric 문맥을 요구하는 후보. 현재 optional schema를 이 문서만으로 400 거부로 바꾸지 않음 |
| 직접 Get Unit | result의 classification 또는 membership이 참조한 Unit set의 멤버만. arbitrary source-only unit을 결과 멤버로 승격하지 않음 |

versioned Unit view는 Record의 immutable unit facts와 **nullable result classification**을 분리하는 방향을 권장한다.
classification=null은 이 결과에서 분류하지 않았다는 뜻이고, classification.outcome=UNAVAILABLE는 계산이 분류한 불가 상태다.
prior baseline/candidate 등 source evidence에는 유효하나 classification이 없는 unit을 BREAKEVEN/UNAVAILABLE·0 PnL로 합성하지 않는다.
현재 ReviewUnit.outcome required와는 다른 versioned 의미이므로 decoder/schema 전환이 필요하다.
대안인 source manifest 전체 기본 목록은 모든 미분류 unit·관측 범위·Outcome absent의 의미를 따로 정의해야 한다.

Analysis가 set·dedup·total·순서를 먼저 결정한다. Unit page 순서는 pinned UnitID의 UTF-16 code-unit ASC를 우선 제안한다.
동일 응답의 result/selection·현재 parent 접근·items/total은 같은 논리 snapshot이고 서로 다른 page 사이 snapshot 동결은 별도 계약이다.
Record는 bounded exact UnitIDs+RevisionID의 immutable 사실/완전성만 반환한다. Record page 뒤 필터·누락 skip/total 재계산을 하지 않는다.
source에 있어야 할 unit/참조가 없으면 저장/일관성 오류이며 정상 empty page/다른 latest Revision으로 바꾸지 않는다.

## internal role과 versioned 공개 role

원 membership은 SUBJECT, COMPARISON, EXCLUDED, RELATION_CANDIDATE, RELATED_ENTRY, PRIOR_BASELINE,
SELECTION_BOUNDARY의 7종이다. 새 read version에서는 이 의미를 유지하는 selection role을 노출하는 방향을 권장한다.
기존 SUPPORTING/COMPARISON/EXCLUDED_CANDIDATE/SELECTION_BOUNDARY 4종 API는 legacy 계약으로 유지하고 자동 cast하지 않는다.
이는 Engine role을 새로 정의하는 것이 아니다. 현재 Compute의 Engine→internal projection은 그대로 유지한다.

membershipId는 metricId:unitId:role:reasonOrEmpty이며 여러 exclusion reason은 reason별 별도 membership이다.
selected entry의 candidate/related entry·prior baseline 겹침을 collapse하면 counts/근거/hash가 달라진다.
Unit total은 distinct unit 수, membership total은 원 membership 수, excludedCount는 Metric의 distinct excluded unit 수로 구분한다.
reason count 합이 excludedCount와 같아야 한다는 제약은 만들지 않는다. 새 filter로 원 membership 내용을 바꿔 저장하지 않는다.
대안인 4종 role 유지는 7종과의 손실 없는 대응·보조 type/문맥·legacy consumer를 모두 정의해야 한다.

## MembershipSummary·reference

현재 summary는 deterministic first page/items·total·opaque evidenceReference를 요구하지만 크기/순서·소비 경로가 불명확하다.
우선 후보는 versioned summary에 명시 selection/단위와 page/size/total을 포함하고 같은 selection의 정식 조회로 이어지게 하는 것이다.
first page는 현재 Page/Size 기본값 0/20과 같은 규칙을 우선 검토하며 새 임의 cap을 도입하지 않는다.
membership page는 membershipId의 정본 UTF-16 순서, unit page는 위 UnitID 순서를 구분한다.
reference는 Run/result/선택 조건에 bind하고 서버가 그 관계를 검증한다. signed/opaque locator를 쓰더라도 권한 증명이 아니며
현재 owner/Guard를 다시 확인한다. raw token/PII/locators를 embed하거나 internal membershipId 하나로 전체 selection을 대신하지 않는다.
wire의 selection reference/경로·서명·수명은 별도 schema/ingress 설계며 이 문서에서 발급 protocol을 만들지 않는다.

## allocation chain과 direct evidence

[LP05](TRADING_REVIEW_LEDGER_EVIDENCE_DRAFT.md)는 원 allocationId를 포함한 완전 allocation bundle과 명시 문맥을 우선 제안한다.
AP04도 이 방향을 따르되 allocation이 없는 정당한 direct evidenceRecordIds를 별도 다룬다.
우선 후보는 **allocation bundle page와 direct-record evidence page를 구분**하고 각자 단위/total/order/접근 문맥을 명시하는 것이다.
기존 page/size 하나의 의미를 몰래 두 단위로 바꾸거나 direct evidence에 가짜 allocationId를 붙이지 않는다.
직접 근거 page는 동일 selection 안의 canonical RecordID와 그 완전 masked snapshot 연결을 보존한다.
구체 response 구조·별도 request/경로 여부는 LP05와 함께 후속 versioned schema에서 결정한다.

대안은 allocation/direct record의 tagged group을 단일 page에 담는 것이다. 이 경우 total은 혼합 group 수가 되고 LP05의
allocation-count 의미와 달라지므로 명시 joint version/consumer 변경이 필요하다. 이 초안이 그 대안을 현재 API에 적용하지 않는다.
allocation 하나의 전체 record/snapshot chain을 page 경계에서 분리하지 않는다. 한 group이 승인 byte/fan-out budget을 넘으면
완전 group/resource 전달 구조를 재검토한다. truncation·누락 수 보정·완전하다는 거짓 표시를 허용하지 않는다.
recordId+action은 allocation identity를 대신하지 않는다. multiple records·reversal·direct refs·같은 unit의 다른 Metric role을 보존한다.

## hash·privacy·수용

현재 evidenceHash는 전체 EVIDENCE_MEMBERSHIPS section hash다. 필터된 selection/page hash나 HTTP bundle JSON hash가 아니다.
wrapper/selection/링크·current raw availability가 원 immutable membership/hash를 바꾸지 않는다.
role/단위/order/masking 변화가 canonical projection을 바꾸면 evidence schema/hash/version·고정 oracle을 별도 등록한다.
현재 V1 결과/hash를 수정하거나 최신 view로 재계산한 값을 V1이라고 반환하지 않는다.
retained masking allowlist·현재 owner/Guard·Book/Raw scope를 확인하고 raw-only 뒤 다운로드 URL/bytes를 복구하지 않는다.
이미 시작한 조회/발급 URL 철회는 별도 lifecycle 정책이며 selection reference가 철회를 보장하지 않는다.

[AR-G04~07](TRADING_REVIEW_ANALYSIS_CONTRACT_ACCEPTANCE_DRAFT.md)의 실제 result/consumer·decoder·hash/팬아웃·삭제/권한 검증이 필요하다.
default set·classification absent·7종 role·reference·두 evidence page는 하나의 coherent read version으로 검토하며 일부만 몰래 교체하지 않는다.
