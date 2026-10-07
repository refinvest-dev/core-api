# TRADING_ANALYTICS.md — Trading Behavior Analytics 계산 계약

이 문서는 Trading Review MVP의 초기 세 Behavior Metric 계산 정본이다. Binance One-way 체결 복원,
allocation, reconciliation과 capability 생성의 선행 의미는
[`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md)를 따르며 여기서 다시 정의하지 않는다.
제품 경계는 [`TRADING_REVIEW.md`](TRADING_REVIEW.md), 불변식은 [`DOMAIN.md`](DOMAIN.md)의
`TR-I01`~`TR-I13`, 결정 요약은 [`DECISIONS.md`](DECISIONS.md)의 ADR-060이 정본이다.

---

## 1. 범위와 버전

MVP bundle ID는 `TRADING_BEHAVIOR_METRICS_V1`이고 다음 definition을 포함한다.

| Metric ID | Metric Definition Version |
|---|---|
| `ENTRY_WITHIN_WINDOW_AFTER_LOSS` | `1` |
| `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS` | `1` |
| `WIN_LOSS_HOLDING_DURATION_DIFFERENCE` | `1` |

같은 `LedgerRevision`, pinned `ReconciliationManifest`, 위 definition version과 canonical
`AnalysisConfig`는 구현자, worker와 실행 시점에 관계없이 같은 population, exclusion, scalar와 evidence를
만들어야 한다. reconstruction 결과를 다시 매칭하거나 현재 instrument metadata로 보완하지 않는다.

이 문서는 Metric 계산까지만 다룬다. Finding ranking/confidence, Golden Fixture 파일, OpenAPI,
persistence, UI, 월별 trend와 causal inference는 범위 밖이다.

## 2. 공통 용어와 모집단 경계

- **Population**: metric의 시간 anchor가 analysis period에 들어오는 전체 후보 `FuturesPositionEpisode`.
  quality나 capability가 부족한 후보도 exclusion을 설명하기 위해 센다.
- **Eligible**: metric별 reconstruction, reconciliation, capability, 기간과 비교 조건을 모두 만족한 후보.
- **Excluded**: population 또는 relation 후보였지만 열거된 이유로 계산에서 제외된 항목.
- **Subject**: 관찰 대상 episode. re-entry/exposure에서는 loss, holding duration에서는 win/loss episode다.
- **Comparison**: subject와 비교하는 entry episode, 과거 baseline episode 또는 반대 outcome group.
- **Evidence**: scalar에 실제 사용되거나 후보 선택·제외를 결정한 episode, allocation, canonical record와
  source row의 연결이다.

`analysisPeriod`는 UTC instant의 반열린 구간 `[startInclusive, endExclusive)`다. 사용자가 local date/time을
입력하면 pinned `reviewTimezone`과 timezone database version으로 한 번 UTC로 변환한 값을 config에 저장한다.
Metric window와 duration은 UTC instant 차이이므로 DST나 표시 timezone의 영향을 받지 않는다.

공통 quality eligibility는 다음을 모두 요구한다.

```text
reviewUnit.type = FUTURES_POSITION_EPISODE
reconstructionStatus = COMPLETE
reconciliationStatus in {EXACT, WITHIN_ROUNDING_TOLERANCE}
```

그 외 episode는 분석 대상으로 승격하지 않고 각각
`RECONSTRUCTION_NOT_COMPLETE`, `RECONCILIATION_NOT_ELIGIBLE`로 제외한다. 하나의 후보에 이유가 여러 개면
모두 보존하되 `excludedCount`는 episode를 한 번만 센다.

## 3. AnalysisConfig V1

아래 값은 canonical config에 모두 명시한다. default는 편의를 위한 versioned default일 뿐 영구 도메인
상수가 아니다. 값이 달라지면 새 `TradingAnalysisRun`으로 재분석해야 하며 결과를 같은 config의 trend처럼
비교하지 않는다.

```text
AnalysisConfig V1
├── schemaVersion: 1
├── analysisPeriod: {startInclusive, endExclusive}
├── reviewTimezone
├── timezoneDatabaseVersion
├── outcomeBasis: NET_TRADING_PNL
├── fundingTreatment: EXCLUDED
├── eligibleReviewUnitPolicy: COMPLETE_AND_RECONCILED_ONLY
├── reversalTreatment: SEPARATE
├── reentryWindow: PT30M
├── baselinePolicy: PRIOR_PERIOD_SAME_INSTRUMENT_DIRECTION
├── metricMinimums
│   ├── reentryEligibleLosses: 1
│   ├── exposurePairs: 1
│   ├── priorBaselineEpisodes: 3
│   ├── holdingWins: 2
│   └── holdingLosses: 2
└── findingMinimums                 # 노출 gate일 뿐 confidence가 아님
    ├── reentryEligibleLosses: 5
    ├── exposurePairs: 5
    ├── holdingWins: 5
    └── holdingLosses: 5
```

`reentryWindow`는 양수여야 한다. period가 비었거나 config enum/threshold가 유효하지 않으면 Metric status가
아니라 Analysis validation failure다. V1 default threshold는 실제 표본 실험 후 config default version을
올려 바꿀 수 있다. 계산 의미, 모집단 또는 formula가 바뀌면 config가 아니라 Metric Definition Version을
올린다.

## 4. 공통 Outcome Classification

Outcome은 arbitrary-precision Decimal `netTradingPnl`을 동일 settlement currency 안에서만 판정한다.

```text
netTradingPnl < 0  -> LOSS
netTradingPnl = 0  -> BREAKEVEN
netTradingPnl > 0  -> WIN
netTradingPnl null -> UNAVAILABLE
```

- `netTradingPnl = sum(allocated realizedProfit) - sum(allocated sourceFeeAmount)`다.
- 양수 fee는 cost이고 음수 fee는 rebate이므로 rebate는 위 식에서 net PnL을 증가시킨다.
- Decimal zero는 scale과 무관한 수학적 exact zero다. epsilon, 표시 rounding 또는 tolerance로 outcome을
  바꾸지 않는다.
- 연결된 fee가 빠졌거나 fee asset을 settlement currency로 환산할 pinned valuation이 없거나 conservation이
  실패하면 `netTradingPnl=null`, classification은 `UNAVAILABLE`이다.
- gross realized PnL 또는 Position History `Closing PNL`로 조용히 대체하지 않는다.
- Funding은 모든 V1 outcome에서 제외하며 `fundingTreatment=EXCLUDED`를 결과에 반복 기록한다.

Outcome이 필요한 metric의 structurally in-scope population에 `UNAVAILABLE`이 하나라도 있으면 전체 scalar는
부분 모집단 값으로 발행하지 않고 `MISSING_CAPABILITY`로 둔다. available/excluded raw count와 evidence는
그대로 제공한다. 이 보수적 gate는 fee가 있는 episode만 골라 분모가 바뀌는 선택 편향을 막는다.

## 5. Decimal, 시간과 집계 규칙

- 돈·수량·가격·notional은 source scale을 보존하는 arbitrary-precision Decimal이다.
- 비율과 rate는 canonical result에서 exact rational `{numerator, denominator}`로 보존한다. UI rounding은
  계산 결과가 아니며 canonical hash에 포함하지 않는다.
- timestamp는 pinned manifest의 UTC instant다. V1 CSV의 관측 정밀도는 초지만 비교 로직은 저장된 instant의
  전체 정밀도를 사용한다. 지속시간은 non-negative integer microseconds다.
- 중앙값은 정렬 후 홀수 표본의 가운데 값, 짝수 표본의 두 가운데 값 산술평균이다. duration의 짝수 중앙값은
  exact rational microseconds가 될 수 있다.
- `p25`/`p75`는 nearest-rank `sorted[ceil(p*n)-1]`이다. mean은 exact 합/표본 수다.
- outlier를 제거, winsorize 또는 cap하지 않는다. primary에 median을 사용하고 raw min/max와 count를 남긴다.

## 6. MetricStatus와 FindingEligibility

모든 Metric은 다음 상태만 사용한다.

```text
AVAILABLE
INSUFFICIENT_SAMPLE
MISSING_CAPABILITY
NOT_APPLICABLE
```

판정 우선순위는 다음과 같다.

1. `NOT_APPLICABLE`: ReviewUnit/product/policy에 definition 자체가 적용되지 않음
2. `MISSING_CAPABILITY`: structurally in-scope 후보 중 required capability가 누락됨
3. `INSUFFICIENT_SAMPLE`: capability는 있으나 eligible subject/comparison이 config minimum 미만
4. `AVAILABLE`: required capability와 minimum을 충족해 scalar를 계산함

Import failure, `ReconstructionStatus`, `ReconciliationStatus`와 `MetricStatus`를 섞지 않는다. quality 부적격
episode는 명시적으로 제외하되 그 존재만으로 metric을 `MISSING_CAPABILITY`로 만들지는 않는다.
`INSUFFICIENT_SAMPLE`에서도 population/eligible/excluded/raw group count는 제공하지만 해석 scalar는
`null`이다. 반대로 minimum을 충족한 분자 0, delta 0, duration 0은 `AVAILABLE`인 실제 값이다.

Finding 노출은 별도 필드다.

```text
FindingEligibility = ELIGIBLE | INELIGIBLE_SAMPLE | INELIGIBLE_METRIC_STATUS
```

Metric이 `AVAILABLE`이고 `findingMinimums`를 충족할 때만 `ELIGIBLE`이다. 이것은 finding을 반드시 만들거나
confidence가 높다는 뜻이 아니다.

## 7. Capability Matrix

`STABLE_EPISODE_TIME`을 analysis capability로 추가한다. pinned manifest의 episode가 exact execution
allocation에서 non-null `openedAt`/`closedAt`을 갖고 `closedAt >= openedAt`이며 Position summary timestamp로
exact execution 시각을 대체하지 않았을 때만 episode scope에 부여한다. 이는 reconstruction을 다시 계산하지 않고 manifest
필드를 검증해 파생한다.

| Metric / measure | Required capability | Missing result |
|---|---|---|
| `ENTRY_WITHIN_WINDOW_AFTER_LOSS` rate/elapsed/symbol dimensions | subject: `CLOSED_OUTCOME`, `FEES`, `STABLE_EPISODE_TIME`; entry: `STABLE_EPISODE_TIME` | `MISSING_CAPABILITY` |
| `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS.NEXT_ENTRY_VS_LOSS_EPISODE_RATIO` | 위 capability + loss/entry `INITIAL_EXPOSURE` | `MISSING_CAPABILITY` |
| `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS.AFTER_LOSS_VS_PRIOR_BASELINE_RATIO` | primary capability + baseline `INITIAL_EXPOSURE`, `STABLE_EPISODE_TIME` | supporting measure `null`; primary status는 유지 |
| `WIN_LOSS_HOLDING_DURATION_DIFFERENCE` | `CLOSED_OUTCOME`, `FEES`, `STABLE_EPISODE_TIME` | `MISSING_CAPABILITY` |

`MAX_EXPOSURE`, `POSITION_INCREASES`와 `EXECUTION_ORIGIN_KNOWN`은 세 metric의 질문에 필요하지 않다.
`VENUE_REPORTED_PNL`, `VENUE_POSITION_SUMMARY`, `POSITION_MODE_USER_DECLARED`,
`POSITION_MODE_OBSERVED_COMPATIBLE`은 reconstruction/admission 증거이고 metric formula에 중복 요구하지 않는다.
다만 선행 reconciliation이 eligible하지 않으면 공통 quality gate에서 제외된다.

Capability는 episode scope로 판정한다. LedgerRevision capability 이름의 union만 보고 모든 episode가 해당
capability를 가진 것으로 간주하지 않는다.

---

## 8. `ENTRY_WITHIN_WINDOW_AFTER_LOSS`

### Metric ID

`ENTRY_WITHIN_WINDOW_AFTER_LOSS`

### Metric Definition Version

`1`

### Purpose

선택한 과거 기간에서 net trading loss로 종료된 episode 뒤에 일반 신규 episode가 config window 안에
열렸는지를 기술한다. 의도, 충동, 원인 또는 execution origin을 추정하지 않는다.

### Required Capabilities

Loss subject는 `CLOSED_OUTCOME`, `FEES`, `STABLE_EPISODE_TIME`, entry candidate는
`STABLE_EPISODE_TIME`이 필요하다.

### Analysis Config

`analysisPeriod`, `reentryWindow`(V1 default `PT30M`), 공통 quality/reversal/outcome policy와 sample
threshold를 사용한다.

### Eligibility

Loss subject는 공통 quality eligibility를 만족하고 `closedAt`이 period에 있으며 outcome이 `LOSS`이고,
`closedAt + reentryWindow <= endExclusive`여야 한다. 마지막 조건은 entry가 이미 관측됐더라도 모든 loss에
같은 관찰 길이를 보장한다. `closedAt`은 position을 exact zero로 만든 마지막 `CLOSE` 또는
`REVERSAL_CLOSE` execution instant다. reversal로 종료된 loss도 subject가 될 수 있다.

Entry candidate는 공통 quality eligibility를 만족하고 `openedAt`이 period에 있으며 첫 allocation이
`OPEN`이어야 한다. `REVERSAL_OPEN`으로 시작한 episode는 `IMMEDIATE_REVERSAL` evidence로 분리하고 일반
re-entry candidate에 넣지 않는다. analysis 시작 전에 열린 episode는 candidate가 아니다.

### Population

- Loss-side population: `closedAt`이 period에 있는 모든 FuturesPositionEpisode.
- Entry-side candidate universe: `openedAt`이 period에 있는 모든 FuturesPositionEpisode.
- Win/breakeven은 loss subject가 아니며 `OUTCOME_NOT_LOSS`; unavailable은
  `OUTCOME_UNAVAILABLE`; quality/capability/window 문제는 해당 exclusion reason으로 집계한다.

### Event Definition

각 eligible loss `L`에 대해 다음을 계산한다.

```text
candidateEntries(L) = {
  E | E is an eligible non-reversal entry
      and L.closedAt < E.openedAt
      and E.openedAt <= L.closedAt + reentryWindow
}
earliestEntryTime(L) = min(E.openedAt for E in candidateEntries(L))
relatedEntrySet(L) = {E in candidateEntries(L) | E.openedAt = earliestEntryTime(L)}
```

정확히 30분은 포함하고 같은 instant는 제외한다. period/timezone의 표시 형식과 무관하게 instant 차이를
비교한다. 중간에 다른 trade, close 또는 episode가 있어도 candidate 관계를 끊지 않는다. first qualifying
instant 이후 window 안의 entry는 relation에 연결하지 않지만 earliest 판정을 위한 audit evidence로 남긴다.

### Comparison Group

각 loss의 comparison은 `relatedEntrySet(L)`이다. symbol과 direction을 제한하지 않는다. `sameInstrument`,
`sameDirection`, `elapsedMicroseconds`는 dimension이다. 같은 earliest instant의 여러 instrument는 순서 없는
한 set으로 모두 연결한다. 한 relation 안에서 loss와 entry는 다른 episode다. 같은 entry가 서로 다른
instrument의 여러 loss에 대해 각각 earliest라면 여러 loss relation의 comparison이 될 수 있고, 한 episode가
앞선 loss의 entry인 동시에 나중 loss relation의 subject가 될 수도 있다. relation은 loss별로 독립 계산한다.

### Formula

```text
eligibleLossCount = count(eligible loss subjects)
lossWithEntryCount = count(L where relatedEntrySet(L) is non-empty)
entryWithinWindowRate = lossWithEntryCount / eligibleLossCount
elapsed(L) = earliestEntryTime(L) - L.closedAt       # one value per matched loss
elapsedTimeDistribution = count,min,p25,median,p75,max of elapsed(L)
sameSymbolCount = count(L whose relatedEntrySet contains same instrument)
differentSymbolCount = count(L whose relatedEntrySet contains different instrument)
```

`sameSymbolCount`와 `differentSymbolCount`는 같은 loss에 동시에 증가할 수 있으므로 합이
`lossWithEntryCount`일 필요가 없다. 관계 member 수는 별도 `relatedEntryCount`,
`sameInstrumentRelatedEntryCount`, `differentInstrumentRelatedEntryCount`로 제공한다. loss 한 건은 entry가
몇 개이든 numerator를 최대 한 번만 증가시킨다.

### Boundary Conditions

- `0 < elapsed <= reentryWindow`; 정확히 30분 포함, 0과 초과는 불포함이다.
- candidate가 없지만 eligible loss가 있으면 rate는 실제 `0`이고 status는 `AVAILABLE`이다.
- `eligibleLossCount=0`이면 rate `null`, `INSUFFICIENT_SAMPLE`이지 `0%`가 아니다.
- reversal close loss는 포함할 수 있지만 같은 execution의 reversal open은 일반 entry가 아니다.
- 서로 다른 symbol의 동시 entry에 event sequence를 부여하지 않는다.
- entry는 `openedAt`이 period에 있으면 되고 `closedAt`은 period 뒤일 수 있다. 다만 retrospective input에서
  이미 `COMPLETE`/eligible이어야 하며 scalar에는 opening evidence만 사용한다. 아직 닫히지 않은 동일 거래는
  현재 LedgerRevision에서 제외되고 이후 Revision 재분석에서만 달라질 수 있다.

### Exclusion Reasons

`RECONSTRUCTION_NOT_COMPLETE`, `RECONCILIATION_NOT_ELIGIBLE`, `OUTSIDE_ANALYSIS_PERIOD`,
`OUTCOME_NOT_LOSS`, `OUTCOME_UNAVAILABLE`, `MISSING_FEES`, `MISSING_STABLE_EPISODE_TIME`,
`WINDOW_RIGHT_CENSORED`, `ENTRY_AT_SAME_TIMESTAMP`, `ENTRY_AFTER_WINDOW`, `ENTRY_IS_REVERSAL`.

### Minimum Sample

Metric minimum은 `reentryEligibleLosses`(default 1), Finding minimum은 별도
`findingMinimums.reentryEligibleLosses`(default 5)다.

### Output Status

공통 우선순위를 따른다. Loss-side structurally in-scope candidate의 outcome/fee/time capability 누락은
`MISSING_CAPABILITY`다. Entry-side time capability가 필요한 window 후보에서 누락돼 관계 여부를 정할 수
없는 경우도 같다.

### Output Shape

`eligibleLossCount`, `lossWithEntryCount`, exact `entryWithinWindowRate`, elapsed distribution,
same/different-instrument loss/member counts, relation list와 common result metadata를 제공한다.

### Evidence Links

각 relation은 loss ID, 모든 candidate entry ID, `SELECTED_EARLIEST | LATER_NOT_LINKED |
SAME_TIMESTAMP | REVERSAL_SEPARATE` 역할, opened/closed scalar, allocation과 source row reference를 가진다.

### Deterministic Ordering

Loss는 `(closedAt, instrumentId, episodeId)`로 출력한다. related entry set은 의미상 unordered이며 canonical
serialization만 `(instrumentId, episodeId)`로 정렬한다. 이 정렬은 cross-symbol event order가 아니다.

---

## 9. `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`

### Metric ID

`INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`

### Metric Definition Version

`1`

### Purpose

loss 뒤 첫 일반 entry의 initial notional이 직전 loss episode의 initial notional과 어떻게 달랐는지 기술한다.

### Required Capabilities

Metric 1 capability와 loss/entry의 `INITIAL_EXPOSURE`가 필요하다.

### Analysis Config

Metric 1의 loss→entry relation을 그대로 재사용한다. 별도 relation을 찾지 않는다. baseline은
`PRIOR_PERIOD_SAME_INSTRUMENT_DIRECTION`이고 최소 표본은 config로 고정한다.

### Eligibility

Metric 1의 eligible relation member 중 loss와 entry의 initial exposure가 모두 있고 settlement currency가
같은 pair만 primary comparison에 포함한다.

### Population

Metric 1의 loss population과 candidate entry universe를 그대로 사용한다. relation이 없거나 initial
exposure/currency 조건을 만족하지 못한 matched loss/entry는 이유와 함께 exposure comparison에서 제외한다.

### Event Definition

```text
initialQuantity = reconstruction이 고정한 첫 OPEN 또는 REVERSAL_OPEN 뒤 abs(position)
initialPrice = 그 opening allocation의 execution price
initialNotional = initialQuantity * initialPrice * pinned contractMultiplier
```

같은 timestamp의 이후 `INCREASE` allocation은 initial exposure에 합산하지 않는다. 같은 timestamp에 열린
서로 다른 related episode도 합산하지 않고 각각 pair를 만든다. reversal-open은 Metric 1 relation에 없으므로
primary pair에도 없다.

### Comparison Group

Primary `NEXT_ENTRY_VS_LOSS_EPISODE_RATIO`는 각 `(loss, related entry)` pair다. same/different instrument,
same/different direction 모두 포함하되 settlement currency는 같아야 한다.

Supporting `AFTER_LOSS_VS_PRIOR_BASELINE_RATIO`의 entry별 baseline은 다음 episode set이다.

```text
same instrument, direction, settlement currency as subject entry
analysisPeriod.startInclusive <= baseline.openedAt
baseline.closedAt < related loss.closedAt
baseline is common-quality eligible and has INITIAL_EXPOSURE
baseline is neither the subject loss nor any related entry
```

따라서 baseline은 event 당시 이미 종료된 과거만 사용하고 미래 episode를 포함하지 않는다. 전체 분석 기간
median처럼 미래를 쓰는 descriptive baseline은 V1에 없으며 도입하면 별도 definition/version으로 만든다.

### Formula

각 pair에 대해 다음 exact 값을 계산한다.

```text
absoluteDelta = entry.initialNotional - loss.initialNotional
ratio = entry.initialNotional / loss.initialNotional
percentageDelta = ratio - 1
```

MVP primary output은 모든 eligible pair `ratio`의 중앙값인
`medianNextEntryVsLossEpisodeRatio`다. `absoluteDelta`는 currency별 distribution으로만 집계하고 서로 다른
currency를 합치지 않는다. supporting baseline은 다음과 같다.

```text
priorBaseline = median(initialNotional of prior baseline set)
afterLossVsPriorBaselineRatio = entry.initialNotional / priorBaseline
afterLossExposureDelta = entry.initialNotional - priorBaseline
```

primary에는 pair count, increased/equal/decreased count, ratio/pct-delta distribution과 currency별 absolute
delta distribution을 함께 제공한다.

### Boundary Conditions

- denominator가 exact 0이면 나누지 않고 해당 measure `value=null`, reason `ZERO_DENOMINATOR`다.
- 다른 settlement currency pair는 환산하지 않는다.
- 같은 earliest instant의 여러 entry는 unordered pair set이며 각각 한 observation이다.
- instrument 가격 수준 normalization은 initial notional이 담당한다. 별도 symbol price normalization을 하지 않는다.
- outlier를 제거하지 않고 median을 primary로 사용한다.
- subject entry는 baseline에 포함하지 않으며 미래 episode도 포함하지 않는다.

### Exclusion Reasons

Metric 1 이유에 더해 `NO_RELATED_ENTRY`, `MISSING_INITIAL_EXPOSURE`,
`SETTLEMENT_CURRENCY_MISMATCH`, `ZERO_DENOMINATOR`, `INSUFFICIENT_PRIOR_BASELINE`.

### Minimum Sample

Primary metric minimum은 eligible pair `exposurePairs`(default 1)다. supporting baseline은 entry마다
`priorBaselineEpisodes`(default 3)를 요구하며 부족해도 primary MetricStatus를 낮추지 않는다. Finding minimum은
`findingMinimums.exposurePairs`(default 5)다.

### Output Status

Primary capability/sample에 공통 우선순위를 적용한다. baseline만 부족하거나 denominator가 0이면 supporting
measure가 `null`이고 reason을 가지며 primary status는 유지된다.

### Output Shape

Primary median ratio, pair별 ratio/percentage/absolute delta, increased/equal/decreased count, same/different
instrument dimension, supporting prior-baseline 값/상태, common metadata를 제공한다.

### Evidence Links

각 pair와 baseline membership은 initial quantity, price, multiplier, notional, currency, exact numerator/
denominator와 이를 만든 opening allocation/TradingRecord/source row를 연결한다.

### Deterministic Ordering

Metric 1 relation order를 사용한다. baseline은 `(closedAt, openedAt, instrumentId, episodeId)`로 canonical
serialization하되 strict time predicate만 membership을 정한다.

---

## 10. `WIN_LOSS_HOLDING_DURATION_DIFFERENCE`

### Metric ID

`WIN_LOSS_HOLDING_DURATION_DIFFERENCE`

### Metric Definition Version

`1`

### Purpose

선택 기간에 종료된 winning episode와 losing episode의 execution-based holding duration 분포 차이를
기술한다.

### Required Capabilities

`CLOSED_OUTCOME`, `FEES`, `STABLE_EPISODE_TIME`.

### Analysis Config

`analysisPeriod`, outcome/funding policy와 win/loss group minimum을 사용한다.

### Eligibility

공통 quality eligibility를 만족하고 `closedAt`이 analysis period에 있으며 required capability가 있어야 한다.
period 시작 전 열렸지만 period 안에 닫힌 complete episode는 포함한다. left/right-censored episode는 공통
quality gate에서 제외한다.

### Population

`closedAt`이 period에 있는 모든 FuturesPositionEpisode다.

### Event Definition

```text
holdingDurationMicroseconds = closedAt - openedAt
```

Position History timestamp가 아니라 첫 opening allocation과 exact-zero closing allocation의 Trade History
execution instant를 사용한다. partial entry/exit은 경계를 바꾸지 않는다. 같은 instant의 서로 다른 Trade ID로
열고 닫았다면 duration 0은 유효하다.

### Comparison Group

- subject group: `WIN` episode
- comparison group: `LOSS` episode
- `BREAKEVEN`은 어느 group에도 넣지 않고 별도 count로 제공한다.
- `UNAVAILABLE`은 어느 group에도 넣지 않으며 metric을 `MISSING_CAPABILITY`로 만든다.

Instrument, direction과 settlement currency로 group을 분할하지 않는다. Duration은 currency-independent이고
V1 primary 질문은 전체 eligible episode의 descriptive comparison이다. 이를 분할한 metric은 별도
definition/version이어야 한다.

### Formula

```text
winMedian = median(duration of WIN episodes)
lossMedian = median(duration of LOSS episodes)
medianDifference = lossMedian - winMedian
medianRatio = lossMedian / winMedian
```

MVP primary measure는 `medianDifferenceMicroseconds`다. 양수는 이 표본에서 loss group의 중앙 보유시간이
더 길었다는 뜻일 뿐 원인이나 조언이 아니다. Supporting measure는 group별 count/min/p25/median/p75/max/
mean과 `medianRatio`다.

### Boundary Conditions

- duration 0을 허용한다.
- reversal-close episode와 reversal-open episode도 각각 quality/outcome 조건을 만족하면 holding group에
  포함한다. reversal 분리는 rapid re-entry 관계에만 적용된다.
- `winMedian=0`이면 ratio는 `null`/`ZERO_DENOMINATOR`지만 primary difference는 계산할 수 있다.
- outlier를 제거하지 않는다.
- 짝수 표본 median은 가운데 두 값의 exact 평균이다.

### Exclusion Reasons

`RECONSTRUCTION_NOT_COMPLETE`, `RECONCILIATION_NOT_ELIGIBLE`, `OUTSIDE_ANALYSIS_PERIOD`,
`OUTCOME_BREAKEVEN`, `OUTCOME_UNAVAILABLE`, `MISSING_FEES`, `MISSING_STABLE_EPISODE_TIME`.

### Minimum Sample

Metric은 `holdingWins`와 `holdingLosses`를 각각 충족해야 한다(V1 default 각 2). Finding은 별도
`findingMinimums.holdingWins/holdingLosses`(default 각 5)를 사용한다.

### Output Status

한 group이라도 metric minimum 미만이면 `INSUFFICIENT_SAMPLE`이고 primary/supporting comparison scalar는
`null`이다. raw win/loss/breakeven/unavailable count는 제공한다.

### Output Shape

Outcome group count와 duration distribution, primary median difference, supporting median ratio,
FindingEligibility와 common metadata를 제공한다.

### Evidence Links

각 group membership은 episode ID, outcome Decimal/classification, openedAt/closedAt/duration과 opening/closing/
fee/PnL allocation에서 source row까지 이어진다.

### Deterministic Ordering

각 group은 `(durationMicroseconds, closedAt, instrumentId, episodeId)` 순으로 serialization한다. aggregation은
episode ID나 source row order에 의존하지 않는다.

---

## 11. 공통 Output과 Evidence 계약

각 Metric의 logical canonical result는 최소 다음을 포함한다.

```text
analysisPeriod
definitionId
definitionVersion
analysisConfig
populationCount
eligibleCount
excludedCount
excludedReasonCounts
subjectEpisodeIds
comparisonEpisodeIds
relations[]                       # loss/entry 또는 outcome-group membership
scalarInputs[]                    # timestamp, Decimal, rational, duration
result
metricStatus
findingEligibility
capabilitySnapshotByEpisode
evidenceReference
```

Evidence chain은 다음을 만족한다.

```text
BehaviorMetric
→ subject/comparison membership
→ BehaviorObservation
→ FuturesPositionEpisode
→ PositionAllocation
→ TradingRecord
→ TradingSourceArtifact source row
```

후보 선택에 영향을 준 excluded/later entry도 exclusion/selection role과 함께 evidence에 포함한다. 원본 UID나
민감 source value를 public result에 복제하지 않고 opaque provenance reference를 사용한다.

대용량 결과에서 API 본문은 ID를 page로 나눌 수 있지만 logical result에서 생략할 수 없다. canonical evidence
artifact에는 전체 membership과 scalar가 들어가고 본문은 deterministic first page, total count,
`evidenceReference`와 artifact hash를 가진다. page size나 inline truncation은 metric value에 영향을 주지 않는다.

## 12. Canonical Result와 결정론

Canonical serialization은 reconstruction manifest의 UTF-8 canonical JSON/Decimal/timestamp 원칙을 재사용한다.
추가로 rational은 부호를 numerator에만 두고 최대공약수로 약분하며 denominator는 양수로 고정한다. set은
명시된 serialization key로 정렬하지만 그 정렬을 거래 순서로 해석하지 않는다. runtime ID, worker,
generated time, storage path와 UI rounding은 result hash에서 제외한다.

```text
same LedgerRevision content hash
+ same ReconciliationManifest hash
+ same metric definition IDs/versions
+ same canonical AnalysisConfig
= same population, exclusions, scalar inputs, result, evidence artifact hash
```

---

## 13. Boundary Scenario expected results

모든 ID와 instrument는 합성값이다. `A`, `B`는 synthetic instrument, `USDx`/`USDy`는 synthetic settlement
currency다. 별도 언급이 없으면 episode는 `COMPLETE + EXACT`, required capability 보유, initial notional
`100 USDx`, period는 `[09:00, 12:00)`, window `PT30M`이다. `L`/`B`/`W`는 LOSS/BREAKEVEN/WIN이며 시각은
UTC instant의 `HH:MM:SS` 축약이다. evidence의 각 episode ID는 해당 opening/closing/fee/PnL allocation과
합성 source row까지 연결된다는 뜻이다.

### 13.1 Re-entry

| # / 상황 | Input episodes | Eligibility / exclusion | Subject / comparison membership | Intermediate value | Expected MetricStatus | Expected result | Evidence episode IDs |
|---|---|---|---|---|---|---|---|
| 1. 29분 59초 | `L01 A close 10:00`, `E01 A open 10:29:59` | 둘 다 eligible | subject `{L01}`; comparison `{E01}` | elapsed `1,799s` | `AVAILABLE` | count `1/1`, rate `1`, sameSymbolCount `1` | `L01,E01` |
| 2. 정확히 30분 | `L02 close 10:00`, `E02 open 10:30` | upper bound 포함 | `{L02}` / `{E02}` | elapsed `1,800s` | `AVAILABLE` | count `1/1`, rate `1` | `L02,E02` |
| 3. 30분 초과 | `L03 close 10:00`, `E03 open 10:30:00.000001` | `E03=ENTRY_AFTER_WINDOW` | `{L03}` / `{}` | candidate set empty | `AVAILABLE` | count `0/1`, rate `0` | `L03,E03(excluded)` |
| 4. 같은 timestamp | `L04 close 10:00`, `E04 OPEN 10:00` | `E04=ENTRY_AT_SAME_TIMESTAMP` | `{L04}` / `{}` | elapsed `0` excluded | `AVAILABLE` | count `0/1`, rate `0` | `L04,E04(excluded)` |
| 5. reversal open | `L05 A REVERSAL_CLOSE 10:00`, `R05 A REVERSAL_OPEN 10:00` | loss eligible; `R05=ENTRY_IS_REVERSAL` | `{L05}` / `{}`; immediate reversal dimension `{R05}` | no regular relation | `AVAILABLE` | count `0/1`, rate `0` | `L05,R05(separate)` |
| 6. 같은 symbol | `L06 A close 10:00`, `E06 A open 10:10` | eligible | `{L06}` / `{E06}` | same instrument | `AVAILABLE` | rate `1`, sameSymbolCount `1`, different `0` | `L06,E06` |
| 7. 다른 symbol | `L07 A close 10:00`, `E07 B open 10:10` | eligible | `{L07}` / `{E07}` | different instrument | `AVAILABLE` | rate `1`, same `0`, differentSymbolCount `1` | `L07,E07` |
| 8. 동시 두 symbol | `L08 A close 10:00`, `E08A A open 10:10`, `E08B B open 10:10` | 모두 eligible | `{L08}` / unordered `{E08A,E08B}` | earliest tie set size 2 | `AVAILABLE` | numerator `1`; same `1`, different `1`, relatedEntryCount `2` | `L08,E08A,E08B` |
| 9. window 내 여러 entry | `L09 close 10:00`, `E09A open 10:05`, `E09B open 10:20` | `E09A=SELECTED_EARLIEST`; `E09B=LATER_NOT_LINKED` | `{L09}` / `{E09A}` | earliest `10:05` | `AVAILABLE` | numerator `1` 한 번, elapsed `5m` | `L09,E09A,E09B(audit)` |
| 10. 오른쪽 관찰 불가 | period end `10:20`; `L10 close 10:00` | `L10=WINDOW_RIGHT_CENSORED` | subject `{}` / `{}` | horizon requires `10:30` | `INSUFFICIENT_SAMPLE` | eligibleLossCount `0`, rate `null` | `L10(excluded)` |
| 11. fee capability 없음 | `L11 close 10:00`, net null; `E11 open 10:10` | `L11=OUTCOME_UNAVAILABLE/MISSING_FEES` | subject `{}`; candidate `{E11}` | loss classification unavailable | `MISSING_CAPABILITY` | scalar `null`, missing count `1` | `L11,E11` |
| 12. breakeven 뒤 entry | `B12 close 10:00`, `E12 open 10:10` | `B12=OUTCOME_NOT_LOSS` | subject `{}` / `{}` | breakeven count `1` | `INSUFFICIENT_SAMPLE` | eligibleLossCount `0`, rate `null` | `B12,E12(not evaluated)` |

### 13.2 Exposure

아래 direct result의 `ratio`는 `entry/loss`, `percentageDelta=ratio-1`이다. supporting baseline을 언급하지
않으면 prior eligible `A`/same-direction episodes가 세 건 이상 있어 baseline `100 USDx`를 만든다.

| # / 상황 | Input episodes | Eligibility / exclusion | Subject / comparison membership | Intermediate value | Expected MetricStatus | Expected result | Evidence episode IDs |
|---|---|---|---|---|---|---|---|
| 13. 증가 | `L13 initial=100`, `E13 initial=150` | eligible same currency | `{L13}` / `{E13}` | delta `+50`, ratio `3/2`, pct `+1/2` | `AVAILABLE` | primary median ratio `3/2`, increased `1` | `L13,E13,H13A,H13B,H13C` |
| 14. 감소 | `L14=100`, `E14=60` | eligible | `{L14}` / `{E14}` | delta `-40`, ratio `3/5`, pct `-2/5` | `AVAILABLE` | primary `3/5`, decreased `1` | `L14,E14,H14A,H14B,H14C` |
| 15. 동일 | `L15=100`, `E15=100` | eligible | `{L15}` / `{E15}` | delta `0`, ratio `1` | `AVAILABLE` | primary `1`, equal `1` | `L15,E15,H15A,H15B,H15C` |
| 16. baseline denominator 0 | direct `L16=100`,`E16=120`; synthetic prior baseline median `0` | direct eligible; baseline reason `ZERO_DENOMINATOR` | direct `{L16}`/`{E16}`; baseline members recorded | direct ratio `6/5`; baseline division 없음 | `AVAILABLE` | primary `6/5`; supporting baseline ratio `null` | `L16,E16,P16A-P16C` |
| 17. baseline 표본 부족 | `L17=100`,`E17=120`; prior baseline 2개, minimum 3 | direct eligible; baseline `INSUFFICIENT_PRIOR_BASELINE` | direct pair; baseline `{P17A,P17B}` | direct `6/5` | `AVAILABLE` | primary `6/5`; supporting baseline `null` | `L17,E17,P17A,P17B` |
| 18. 동시 복수 entry | `L18=100`; `E18A=150 A`, `E18B=50 B`, same earliest instant | two unordered eligible pairs | `{L18}` / `{E18A,E18B}` | ratios `{3/2,1/2}`, median `1` | `AVAILABLE` | primary `1`; increased `1`, decreased `1`; 합산 안 함 | `L18,E18A,E18B,H18A,H18B,H18C` |
| 19. 다른 settlement | `L19=100 USDx`, `E19=100 USDy` | pair `SETTLEMENT_CURRENCY_MISMATCH` | subject `{L19}` / comparison `{}` | conversion 없음 | `INSUFFICIENT_SAMPLE` | pairCount `0`, primary `null` | `L19,E19(excluded)` |
| 20. extreme outlier | pairs `L20A/E20A ratio=1`, `L20B/E20B=1`, `L20C/E20C=100` | 모두 eligible; 제거 없음 | 3 loss/entry pairs | sorted `{1,1,100}` | `AVAILABLE` | primary median `1`, min `1`, max `100` | `L20A,E20A,L20B,E20B,L20C,E20C` |

Scenario 16은 division guard의 합성 단위 경계다. Binance Linear Perpetual V1의 valid positive price/quantity라면
initial notional 0 episode는 정상적으로 생성되지 않지만 구현은 0 denominator를 무한대나 0으로 만들지 않는다.

### 13.3 Holding Duration

단위는 분으로 축약했지만 canonical scalar는 microseconds다. 별도 언급이 없으면 각 group minimum은 2다.

| # / 상황 | Input episodes | Eligibility / exclusion | Subject / comparison membership | Intermediate value | Expected MetricStatus | Expected result | Evidence episode IDs |
|---|---|---|---|---|---|---|---|
| 21. 양쪽 충분 | `W21A=10m,W21B=20m`; `L21A=30m,L21B=50m` | 모두 eligible | win `{W21A,W21B}` / loss `{L21A,L21B}` | medians `15m`,`40m` | `AVAILABLE` | primary loss-win `+25m`; ratio `8/3` | `W21A,W21B,L21A,L21B` |
| 22. win 부족 | `W22=10m`; `L22A=30m,L22B=50m` | capability 있음, win n=1<2 | win `{W22}` / loss `{L22A,L22B}` | raw medians 계산값은 노출 scalar로 사용 안 함 | `INSUFFICIENT_SAMPLE` | primary/ratio `null`, counts `1/2` | `W22,L22A,L22B` |
| 23. loss 부족 | `W23A=10m,W23B=20m`; `L23=30m` | loss n=1<2 | win 2 / loss 1 | minimum 미달 | `INSUFFICIENT_SAMPLE` | primary `null`, counts `2/1` | `W23A,W23B,L23` |
| 24. breakeven만 | `B24A=10m,B24B=20m` | 둘 다 `OUTCOME_BREAKEVEN` | win `{}` / loss `{}`; breakeven 2 | group empty | `INSUFFICIENT_SAMPLE` | primary `null`, breakevenCount `2` | `B24A,B24B` |
| 25. 같은 timestamp open/close | `W25A=0,W25B=0`; `L25A=5m,L25B=5m` | duration 0 valid | win 2 / loss 2 | medians `0`,`5m` | `AVAILABLE` | primary `+5m`; ratio `null/ZERO_DENOMINATOR` | `W25A,W25B,L25A,L25B` |
| 26. reversal episode | `L26A` reversal-close `10m`; `W26A` reversal-open same instant, later close `20m`; plus `L26B=10m,W26B=20m` | 모두 quality/outcome eligible | win 2 / loss 2; reversal relation은 별도 | medians `20m`,`10m` | `AVAILABLE` | primary `-10m` (loss-win) | `L26A,W26A,L26B,W26B` + reversal allocations |
| 27. 큰 outlier | wins `W27A=10m,W27B=10m,W27C=1000m`; losses `L27A=20m,L27B=20m,L27C=20m` | outlier 유지 | win 3 / loss 3 | medians `10m`,`20m`; win mean `340m` | `AVAILABLE` | primary `+10m`; distributions에 max/mean 노출 | `W27A,W27B,W27C,L27A,L27B,L27C` |
| 28. 짝수 median | wins `W28A=10m,W28B=20m`; losses `L28A=30m,L28B=50m` | eligible | win 2 / loss 2 | exact medians `(10+20)/2=15`, `(30+50)/2=40` | `AVAILABLE` | primary `+25m`, ratio `8/3` | `W28A,W28B,L28A,L28B` |

## 14. 구현/fixture 확인 목록

- gross PnL fallback이 없고 fee/rebate/funding 의미가 §4와 일치한다.
- exactly-30m, same-instant, reversal과 right-window censor가 표와 일치한다.
- 동일 earliest instant의 cross-instrument entry를 순서 없는 set으로 처리한다.
- primary exposure는 direct loss→entry ratio이고 baseline은 strict-prior supporting measure다.
- holding primary는 `lossMedian - winMedian`이며 group minimum과 denominator 0을 구분한다.
- `MISSING_CAPABILITY`, `INSUFFICIENT_SAMPLE`과 실제 scalar 0을 구분한다.
- 모든 membership/scalar/exclusion이 source row까지 연결되고 민감 UID를 노출하지 않는다.
- 같은 pinned input/version/config의 canonical result/evidence hash가 같다.
- 문구는 관찰된 동반 관계만 설명하고 심리, 성격, 의도, 인과 또는 투자 지시를 단정하지 않는다.
