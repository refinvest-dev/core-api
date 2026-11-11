# Analysis Review·Metric 응답 리뷰 (DRAFT)

상태: **ADR-078·AP02/03 미채택**. [상위 리뷰](TRADING_REVIEW_ANALYSIS_CONTRACT_DRAFT.md)를 함께 읽는다.
현재 required fields를 삭제/보정하거나 Compute payload·hash/vector를 변경하지 않는다. 신규 version/path/미디어 타입 literal은 미확정이다.

## AP02 — Review 통계와 출처

현재 TradingReview는 전역 populationCount/eligibleCount/excludedCount/exclusionReasonCounts와 정확히 세 Metric을 요구한다.
정본 [Analytics](TRADING_ANALYTICS.md)는 각 Metric의 기간 anchor·모집단/비교군이 다르고,
[integrity](TRADING_ANALYTICS_RESULT_INTEGRITY.md)는 Metric별 count와 section 구조 count를 정의한다.
서로 다른 Metric의 count를 sum/첫 Metric 복사·중복을 없앤 union으로 전역 분모에 대입하는 규칙은 없다.

우선 후보는 **명시 versioned Review에서 per-Metric 통계를 권위로 두고 미정의 전역 count를 분리/제거**하는 것이다.
현재 schema에서 required를 몰래 생략하거나 0/임의 공통 count로 성공시키지 않는다.
대안인 공통 population은 후보 포함/제외·시간 anchor·중복·상호 partition·version/hash·Compute 생산을 별도 정의해야 한다.
그 통계를 Go가 원 결과에서 다시 계산하는 방식으로 도입하지 않는다. 기존 client의 전역 통계 해석/응답 이력을 먼저 조사한다.

| versioned Review의 논리 요소 | 우선 후보 의미 | 확인할 근거 |
|---|---|---|
| source/result | 원 AnalysisRun/Revision·AnalysisResult resultHash/evidenceHash·exact config/version/policy | 당시 영속 descriptor·immutable 결과와 binding |
| metrics | 정확히 세 원 typed Metric·각 definition/status/eligibility·per-Metric counts | 현재 V1 sections/참조 closure·metricHash |
| findings | disabled rule set에서는 [] | 새 rules를 기존 결과에 적용하지 않음 |
| quality/capability | 원 Revision의 완전 TradingQualityReport·pinned source capability | stripped base·각 Metric requiredCapabilities와 구분 |
| createdAt | Go 결과 최초 성공 publication Tx가 고정/저장한 stamp | 실제 commit 벽시각/Compute transport/observation time 아님 |
| lineage | verified ordinary known-none과 재처리 DAG·legacy unknown 구분 | 같은 owner/Book·source/target/version/hash·실제 이력 |

publication UNKNOWN/재전송·조회 때 이미 commit한 시각을 다시 찍지 않는다. legacy에 publication anchor/기존 의미가 없거나 충돌하면
분류·복구/compatibility 정책을 먼저 정하고 Run.createdAt/현재 clock/Snowflake 시각으로 채우지 않는다.
lineage unknown을 []/null로 known-none 처리하지 않는다. 이 원칙은 LP01/03과 연계하되 ADR-077 후보 채택을 뜻하지 않는다.
full quality/Metric arrays를 승인 budget에서 완전 제공할 수 없으면 명시 versioned paging/detail 계약을 함께 설계한다.
표시 편의로 근거·필수 count/issue를 truncate하지 않는다. Compute TTL 뒤에도 정상 retained 결과는 재계산 없이 읽는다.

## AP03 — typed Metric read wrapper

현재 public BehaviorMetric은 generic value(decimal/ratio/percentagePoint/duration/count)·resultHash·opaque reference를 요구한다.
반면 internal typed Metric은 definition별 복수 값·분포·currency/pairs·scalarInputs/refs·metricHash를 가진다.
nullable semantics도 다르다. 예를 들어 unavailable exposure의 per-pair data는 남고 primary aggregate는 null일 수 있다.
generic scalar 하나로 축소하거나 non-AVAILABLE이므로 모든 내부 근거를 지우는 방식은 정보 손실이다.

우선 후보는 **versioned read wrapper 안에 원 typed Metric projection을 보존**하는 것이다.
다음 표는 논리 구성이며 신규 schema/field literal을 확정한 것이 아니다.

| 요소 | 후보 의미 |
|---|---|
| Run/result context | analysisRunId·정확한 source/result/config/version binding·AnalysisResult 전체 hash |
| typed metric | 현재 세 definition-discriminated Metric의 전체 값·nullable·unit·count·필수 refs·metricHash |
| identity | V1 metricId=definitionId를 Run/result 안에 bind. 매 item Snowflake/새 전역 Metric ID 없음 |
| selection/read links | result-bound 문맥과 별도 bounded 근거 조회. wrapper는 권한 token이 아님 |

metricHash는 원 typed Metric에서 metricHash만 제외한 정본 projection의 hash다. AnalysisResult.resultHash는 6개 section digest manifest의 hash다.
원 typed object에 analysisRunId·링크·현재 masking/display 값·page metadata를 붙인 뒤 metricHash를 재계산하지 않는다.
Run ID 등 wrapper 정보는 기존 logical result hash를 바꾸지 않는다. 새 wrapper 자체의 hash가 필요하면 별도 명명/version으로 설계한다.
current BehaviorMetric.resultHash가 어느 의미였는지 기존 payload/consumer로 확인하기 전 새 의미를 대입하거나 이름만 바꾸지 않는다.

| definition | 보존할 값의 예 | 금지 |
|---|---|---|
| ENTRY_WITHIN_WINDOW_AFTER_LOSS | rate·elapsed distribution·loss/related entry·same/different counts | rate만 남기고 분모/근거·복수 entry를 제거 |
| INITIAL_EXPOSURE_CHANGE_AFTER_LOSS | median ratio·pair/ratio/percentage distribution·currency별 absolute delta·baseline reason | currency 합산·primary 없음의 0 대체·baseline 부족으로 primary 무조건 실패 |
| WIN_LOSS_HOLDING_DURATION_DIFFERENCE | win/loss distributions·signed rational microsecond difference·ratio/zero denominator reason | unsigned duration·rounding·null을 zero ratio로 대체 |

정확한 primary/보조 measure는 [계산 정본](TRADING_ANALYTICS.md)에 따른다. Go는 financial formula/모집단을 재실행하지 않는다.
reduced rational·Decimal lexeme·safe integer·UTC source time·UTF-16 정렬과 JCS hash oracle을 유지한다.
logical result의 count/metric/evidence hashes와 transport SHA는 다른 계약이다.

대안은 generic primary response를 유지하면서 모든 세부 값/근거를 별도 resource에서 제공하는 것이다.
이 경우 primary 선택·단위/null·link 접근/수명·consumer roundtrip/한도를 완전히 정의해야 한다.
현재 generic shape를 그대로 두고 값 일부를 버리는 구현은 대안이 아니다. 새/legacy decoder/cache·hash oracle와
retained result를 새 view로 재현할 충분한 원 자료가 확인되기 전 version 전환을 활성화하지 않는다.
FindingEligibility는 유지하되 V1 findings=[]를 바꾸지 않는다. 미래 Finding은 별도 rule version·새 Run이며 과거 결과를 수정하지 않는다.
