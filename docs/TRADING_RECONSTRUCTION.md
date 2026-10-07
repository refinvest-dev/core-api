# TRADING_RECONSTRUCTION.md — Binance Futures Trade Reconstruction Specification

이 문서는 `BINANCE_USDS_LINEAR_PERPETUAL_ONE_WAY_V1` reconstruction과 reconciliation의 기술 정본이다.
`MUST`, `MUST NOT`, `SHOULD`는 각각 필수, 금지, 권고를 뜻한다. 구현, fixture, golden expected result는
이 문서의 식, 정렬, 상태와 실패 결과를 그대로 사용한다. 제품 경계는 [`TRADING_REVIEW.md`](TRADING_REVIEW.md),
요약 불변식은 [`DOMAIN.md`](DOMAIN.md), 결정 이유는 [`DECISIONS.md`](DECISIONS.md) ADR-059가 정본이다.

---

## A. Scope and Terminology

| 용어 | 규범 정의 |
|---|---|
| Execution | Trade History 한 행에서 정규화한 실제 fill인 `ExecutionRecord`. Backtest의 모의 `execution`/`Trade`와 타입을 공유하지 않는다. |
| Signed Position Quantity | symbol별 현재 One-way position. Long은 양수, Short는 음수, flat은 정확히 `0`이다. |
| Position Allocation | execution의 수량과 연결 fee/reported PnL을 하나의 episode action에 귀속한 불변 근거. reversal 한 행만 두 allocation을 가질 수 있다. |
| Futures Position Episode | signed quantity가 정확히 `0`에서 non-zero가 된 뒤 다시 `0`이 될 때까지의 상품별 `ReviewUnit`. censored shell은 알 수 없는 경계를 nullable로 명시한다. |
| Reversal | 한 execution이 기존 position 전량을 닫고 남은 수량으로 반대 방향 position을 여는 부호 변경. |
| Venue Position Summary | Position History 한 행의 종료 포지션 요약인 `VenuePositionSummaryRecord`. 시점별 snapshot이 아니다. |
| Reconstruction | ordered execution과 pinned instrument terms만으로 state transition, episode와 allocation을 생성하는 과정. |
| Reconciliation | reconstructed episode와 Venue Position Summary를 일대일 constraint matching하는 과정. summary 값으로 execution state를 고쳐 쓰지 않는다. |
| Reconciliation Manifest | import에서 생성하는 versioned canonical 결과. 입력 record hash, episode/allocation, match, status, tolerance와 exclusion을 포함하며 `LedgerRevision`이 hash와 reconstruction version을 고정한다. |
| Censored Episode | 관측 범위 밖에서 시작한 `LEFT_CENSORED` 또는 범위 끝에도 열려 있는 `RIGHT_CENSORED` episode. 알 수 없는 경계나 수량을 추정하지 않는다. |
| Eligible Episode | `ReconstructionStatus=COMPLETE`이며 `ReconciliationStatus`가 `EXACT` 또는 `WITHIN_ROUNDING_TOLERANCE`이고 required capability를 가진 episode. |

`ExecutionRecord`, `FuturesPositionEpisode`, `PositionAllocation`은 Backtest `Trade` 또는 그 execution 타입과
직렬화 모델, ID namespace, persistence 타입을 공유해서는 안 된다.

## B. Input Preconditions

### B.1 Session과 dialect

하나의 `TradingImportSession`에는 다음 artifact가 각각 정확히 하나 있어야 한다.

- `BINANCE_USDS_TRADE_HISTORY_V1`
- `BINANCE_USDS_POSITION_HISTORY_V1`

required logical column은 [`TRADING_REVIEW.md` §6](TRADING_REVIEW.md#6-import와-canonical-tradingrecord)를
따른다. 순서 변경과 알 수 없는 추가 column만 허용한다. required header 누락은
`REQUIRED_COLUMN_MISSING`, duplicate required header나 이름/대소문자 변경은
`UNSUPPORTED_SOURCE_SCHEMA`다. byte decoding, delimiter, quoting 또는 row width가 유효하지 않으면
`FILE_FORMAT_INVALID`다. 확인된 실제 익명 표본은 두 role 모두 UTF-8 BOM + comma + CRLF이고 quote
character가 없었다. V1 parser는 UTF-8 optional 단일 leading BOM을 header 전에 제거하고 comma delimiter,
CRLF/LF record terminator와 RFC 4180 double-quote escaping을 처리해야 한다. BOM이 있는 첫 Trade header를
`\uFEFFUid`로 해석해서는 안 된다. BOM 필수 여부, quote 유무와 Binance export version별 물리 변화는 한
표본으로 일반화하지 않는다.

### B.2 Required canonical fields

| Record | 필수 canonical 값 |
|---|---|
| `ExecutionRecord` | source execution key, instrument, UTC `occurredAt`, `side`, positive `quantity`, positive `price`, source `amount`, buyer/maker, raw Trade ID, raw Order ID, provenance |
| `FeeRecord` | source execution key, signed `sourceAmount`, fee asset, `COST\|REBATE\|ZERO`, `balanceEffect`, provenance |
| `VenueReportedPnlRecord` | source execution key, signed realized profit, settlement asset, provenance |
| `VenuePositionSummaryRecord` | instrument, direction, margin mode, entry/close price, max quantity, closed volume, closing PnL, exact UTC `openedAt`/`closedAt`, status, provenance |

Trade row 하나는 앞의 세 Trade-derived record를 모두 만들며, Position row 하나는 summary 하나를 만든다.
원본 행은 artifact ID, 1-based data row number, source record key와 canonical content hash로 추적 가능해야 한다.
Trade `Fee` cell은 `decimalLexeme + asset`으로 파싱하며 asset은 `^[A-Z][A-Z0-9]*$`다. leading decimal
lexeme와 남은 asset suffix가 유일하게 분리되지 않으면 `FILE_FORMAT_INVALID`다.

### B.3 Product, mode, timezone과 enum

- source symbol은 Unicode를 포함할 수 있는 lossless opaque identifier다. trim, case conversion, Unicode
  normalization과 ASCII-only validation 없이 exact value를 pinned `InstrumentDescriptor` registry에 조회한다.
  registry가 증명하는 Binance USDⓈ-M Linear Perpetual이어야 하며 COIN-M, Spot, Options 또는 terms가 없는
  symbol은 `UNSUPPORTED_PRODUCT`다. fee asset의 ASCII grammar를 symbol에 적용하지 않는다.
- 사용자가 `ONE_WAY`와 IANA `sourceTimezone`을 명시해야 한다. browser/server/file로 timezone을 추론하지
  않는다. filename의 timezone 표시도 일반 parser evidence가 아니다. 존재하지 않거나 DST에서 모호한 local
  time은 offset 선택을 추정하지 않고 `FILE_FORMAT_INVALID`다.
- Trade `Time`과 Position `Opened`/`Closed`는 모두 exact `yyyy-MM-dd HH:mm:ss` source local timestamp다.
  pinned timezone을 적용해 second-precision UTC instant로 변환한다.
- Trade coverage는 `Time`의 min/max, Position coverage는 `Opened`의 min과 `Closed`의 max다. role별
  coverage는 `earliestObservedAt`, `latestObservedAt`, `rowCount`, pinned `sourceTimezone`,
  `timestampPrecision=SECOND`, `coverageCompleteness=NOT_PROVEN`으로 독립 계산한다. min/max가 연속적이거나
  완전한 거래소 coverage를 증명한다고 해석하지 않는다.
- 수락에는 `USER_DECLARED_ONE_WAY`와 execution/summary가 만든
  `OBSERVED_ONE_WAY_COMPATIBLE`가 모두 필요하다. 동시 long/short가 필요한 allocation graph는
  `HEDGE_OVERLAP_DETECTED`이며 session을 `UNSUPPORTED_POSITION_MODE`로 거절한다.
- Trade `Side`는 정확히 `BUY|SELL`; boolean은 `true|false`; Position Side는 `Long|Short`다.
- `Margin Mode`는 정확히 `Isolated|Cross`를 허용하고 evidence로 보존한다. 다른 값은
  `UNSUPPORTED_SOURCE_SCHEMA`다. leverage, collateral 또는 ROE를 추론하지 않는다. 같은 episode를
  요구하는 summary들이 서로 다른 supported margin mode를 주장하면 값을 고르지 않고
  `AMBIGUOUS`/`CONTRADICTORY_POSITION_SUMMARY`로 제외한다.
- Position `Status`는 `Closed`만 V1 reconciliation 대상이다. 다른 non-empty status row는 import를 버리지
  않고 `UNSUPPORTED_POSITION_STATUS` exclusion으로 보존한다. 값을 `Closed`로 보정하지 않는다.

### B.4 Canonical ordering 결정

Trade ID 원문은 문자열로 보존한다. V1은 ASCII 정규식 `^[0-9]+$`만 허용하며 부호, 공백, 소수점, 지수,
locale digit를 허용하지 않는다. source symbol을 exact pinned instrument에 매핑하고 deduplicate/conflict 검사를
마친 뒤 execution 정렬 key는
`(instrumentId, occurredAt, arbitraryPrecisionInteger(rawTradeId))`다. state machine은 instrument partition별로
실행하므로 첫 항목은 deterministic partition key이지 서로 다른 instrument fill의 경제적 전역 순서가 아니다.
64-bit 변환이나 lexical ordering을 사용해서는 안 된다. 같은 account/symbol에서 서로 다른 원문 ID가 같은
정수값(예: leading zero 차이)을 만들면 identity가 모호하므로 timestamp와 관계없이 `RECORD_CONFLICT`로
session을 거절한다. 같은 source identity와 같은 content는 provenance를 합쳐 먼저 deduplicate하고, 같은
identity와 다른 content도 `RECORD_CONFLICT`다. 그 다음 정렬하며 서로 다른 symbol에는 전역 tie-break를
만들지 않는다. 이 identity guard 때문에 instrument/time/numeric Trade ID 뒤에 source row 순서나 `Order ID`
tie-breaker는 필요하지 않다. derived canonical record의 serialization만 같은 fill 안에서 고정된 `recordType`
순서를 마지막 key로 사용한다. 동일 Order ID의 여러 row는 각각 독립 fill이며 하나의 주문이나 execution으로
합치지 않는다.

### B.5 Decimal parsing과 row validity

수치는 source lexeme과 scale을 보존하는 arbitrary-precision Decimal로 읽는다. 허용 문법은
`^-?(0|[1-9][0-9]*)(\.[0-9]+)?$`이며 `+`, exponent, thousands separator, whitespace, `NaN`, infinity를
허용하지 않는다. quantity/price/max quantity/closed volume은 음수일 수 없고 execution quantity와 price는
`> 0`이어야 한다. fee와 PnL은 signed 또는 zero다. instrument step/tick/settlement precision 검증은 F절을
따른다. lexical/필수 값 오류는 `FILE_FORMAT_INVALID`, schema 의미가 다른 dialect는
`UNSUPPORTED_SOURCE_SCHEMA`다.

## C. Signed Position State Machine

deduplication과 B.4 정렬 뒤 symbol별로 독립 실행하며 첫 execution 직전 provisional state는 exact Decimal
`0`으로 초기화한다. Position summary는 이 state를 직접 변경하지 않고 left-censor evidence와 reconciliation
constraint만 제공한다.

```text
signedDelta = BUY ? +quantity : -quantity
nextPosition = previousPosition + signedDelta
```

`p=previousPosition`, `d=signedDelta`, `n=p+d`, `q=abs(d)`다. `sign(x)`는 `x>0`일 때 `+1`, `x<0`일 때
`-1`이며 `0`에는 적용하지 않는다.

| 조건 | transition/action | episode | allocated quantity | 경계와 quantity | reported PnL / fee |
|---|---|---|---:|---|---|
| `p=0,n>0` | Long `OPEN` | 새 Long | `q` | `openedAt=execution.time`; `initial=max=q` | PnL은 반드시 0; fee 전액 |
| `p=0,n<0` | Short `OPEN` | 새 Short | `q` | 위와 같음 | 위와 같음 |
| `sign(p)=sign(d)` | `INCREASE` | 현재 | `q` | `max=max(oldMax,abs(n))` | PnL은 반드시 0; fee 전액 |
| `sign(p)≠sign(d),0<abs(n)<abs(p)` | `REDUCE` | 현재 | `q` | 경계 불변; max 불변 | reported PnL 전액; fee 전액 |
| `p≠0,n=0` | `CLOSE` | 현재 종료 | `q=abs(p)` | `closedAt=execution.time` | reported PnL 전액; fee 전액 |
| `p≠0,n≠0,sign(n)≠sign(p)` | `REVERSAL_CLOSE` + `REVERSAL_OPEN` | 기존 종료 + 새 반대 방향 | `abs(p)` + `abs(n)` | 기존 closed/new opened 모두 같은 execution time; 새 initial/max=`abs(n)` | PnL은 close 전액/open 0; fee는 수량 비율 배분 |

`p=0,d=0`은 positive execution quantity precondition 때문에 존재할 수 없다. 모든 transition 후 `n`은 step
size grid 위에 있어야 한다. exact zero만 flat이다. `openedAt`은 첫 opening allocation, `closedAt`은 position을
0으로 만든 closing allocation의 execution 초 단위 시각이다.

`initialQuantity`는 첫 `OPEN|REVERSAL_OPEN` 뒤의 `abs(position)`, `maxQuantity`는 episode 중 각 transition
후 `abs(position)`의 최댓값이다. `initialNotional=initialQuantity*firstOpeningPrice*contractMultiplier`다.
`maxNotional`은 max quantity를 처음 만든 transition 직후의 moving average entry price를 사용하며 동률이면
가장 이른 transition을 선택한다. 이는 source leverage나 margin이 아니다.

opening action에서 non-zero reported PnL이 관측되면 이전 position이 누락됐다는 증거다. 해당 provisional
episode는 `INCONSISTENT`, 관련 Position summary shell은 `LEFT_CENSORED`/`INCOMPLETE_TRADES`로 남기며 이를
정상 OPEN으로 고쳐 쓰지 않는다. symbol의 후속 execution은 state machine 결과와 issue를 모두 보존하되
V1은 같은 artifact 안에서 임의의 flat boundary를 찾아 resynchronize하지 않는다. conflict가 발생한
execution부터 해당 symbol의 나머지 provisional episode를 모두 `INCONSISTENT`로 제외한다. 더 이른 범위를
포함한 새 import만 다시 `COMPLETE`를 증명할 수 있다.

## D. Partial Entry and Exit

### D.1 Moving cost와 평균 가격

각 episode는 exact Decimal `entryWeightedSum=Σ(openQty*price)`와 `openingVolume=Σ(openQty)`를 보존한다.

```text
averageEntryPrice = entryWeightedSum / openingVolume
averageExitPrice  = Σ(closeQty * closePrice) / Σ(closeQty)
```

평균은 exact numerator/denominator pair로 manifest에 보존하고 비교는 cross multiplication한다. display Decimal
변환만 F절의 rounding을 사용한다. 현재 position cost basis는 다음처럼 갱신한다.

```text
same-direction add:
  nextAverageCost = (abs(p)*averageCost + q*price) / abs(n)
reduce:
  nextAverageCost = averageCost
close:
  averageCost = undefined
reversal open:
  newAverageCost = execution.price
```

| 상황 | 규칙과 expected action |
|---|---|
| 분할 진입 | 첫 fill은 `OPEN`, 같은 방향 후속 fill은 `INCREASE`; 모든 opening volume으로 average entry 계산 |
| 분할 청산 | 반대 side가 position보다 작으면 각 fill은 `REDUCE`; 마지막 flat fill은 `CLOSE` |
| 포지션 확대/축소 | same-sign delta는 `INCREASE`, opposite delta이되 부호 유지면 `REDUCE`; 주문 의도나 UI label을 사용하지 않음 |
| 전량 청산 | `abs(d)=abs(p)`일 때만 `CLOSE` |
| 손실/수익 중 추가 진입 | transition은 모두 `INCREASE`. 직전 average cost 대비 execution price로 관측 label만 계산한다(Long: 낮으면 loss/higher면 profit, Short는 반대). unrealized PnL이나 의도를 outcome에 합산하지 않음 |
| 동일 timestamp 여러 execution | 같은 symbol은 arbitrary-precision Trade ID 오름차순. 다른 symbol은 독립 처리하며 전역 순서를 만들지 않음 |

linear contract의 자체 계산 closing PnL은 allocation 직전 average cost로 다음과 같이 진단용 계산한다.

```text
calculatedPnl = directionSign * closeQty * (executionPrice - averageCostBeforeClose)
                * contractMultiplier
directionSign = LONG ? +1 : -1
```

이는 `VenueReportedPnlRecord`를 덮어쓰지 않는다. 차이는 reconciliation evidence/issue로 남긴다.

## E. Reversal

기존 Long `3`, `SELL 5`이면 다음과 같다.

```text
closingQuantity = min(abs(previousPosition), executionQuantity) = 3
openingQuantity = executionQuantity - closingQuantity = 2
nextPosition = +3 - 5 = -2
```

- 기존 episode에 `REVERSAL_CLOSE(3)`, 새 Short episode에 `REVERSAL_OPEN(2)`를 만든다.
- 두 episode의 종료/시작 시각은 같은 execution timestamp다.
- source reported realized PnL 전액은 closing allocation, opening allocation은 source scale의 `0`이다.
- fee는 `3/5`, `2/5` 비율로 배분한다. exact 비율이 source fee scale에 표현되지 않으면 closing share를
  source scale로 `ROUND_DOWN`(0 방향)한 뒤 opening share를 `sourceFee-closingShare`로 둔다. 따라서 잔여
  최소 단위는 opening allocation에 귀속된다. 음수 rebate에도 같은 규칙을 적용한다.
- reversal pair는 공통 `reversalGroupId=SHA-256(sourceExecutionKey + reconstructionPolicyVersion)`로 연결하고
  일반 rapid re-entry observation에서 제외해 `IMMEDIATE_REVERSAL`로 분리한다.

보존식은 다음과 같다.

```text
executionQuantity = closingQuantity + openingQuantity
sourceFee(asset) = closingAllocatedFee(asset) + openingAllocatedFee(asset)
sourceReportedPnl = closingAllocatedPnl + openingAllocatedPnl
openingAllocatedPnl = 0
```

한 execution record가 두 episode를 참조할 수 있으나 동일 quantity unit을 중복 배정한 것은 아니다.

## F. Decimal and Zero Rules

1. quantity, price, amount, fee, PnL과 weighted sum은 binary floating point가 아닌 arbitrary-precision Decimal을
   사용한다. 나눗셈 결과는 exact rational pair로 보존한다.
2. source lexeme의 scale은 canonical record에 보존한다. `1.0`과 `1.00`은 수치 비교에서는 같지만
   provenance/허용 오차에서는 서로 다른 scale이다.
3. `nextPosition == 0`은 Decimal exact equality다. epsilon, `isclose`, display rounding으로 zero를 만들지 않는다.
4. `quantity / stepSize`와 `price / tickSize`가 exact integer여야 한다. 위반 execution은
   `ReconstructionStatus=INCONSISTENT`, 잘못된 pinned instrument terms나 모든 row에 걸친 위반은
   `UNSUPPORTED_PRODUCT`로 import를 거절한다. summary의 rounded 값은 tolerance 비교 대상이지만 execution
   grid 위반을 tolerance로 통과시키지 않는다.
5. reconciliation tolerance는 zero 판정과 분리한다. tolerance 안의 non-zero position을 flat으로 닫지 않는다.
6. fee reversal split 외의 계산 중간값은 반올림하지 않는다. 그 split만 source fee scale과 `ROUND_DOWN`을
   사용하고 complement로 보존한다.
7. canonical 평균값은 numerator/denominator를 보존한다. 최종 표시가 필요하면 price는
   `max(sourcePriceScale,tickSizeScale)`, money는 settlement precision, quantity는
   `max(sourceQuantityScale,stepSizeScale)`로 `ROUND_HALF_EVEN`한다. UI 표시 반올림값은 manifest match/hash
   입력이 아니다.

## G. Fee Allocation

```text
sourceAmount > 0  → COST
sourceAmount < 0  → REBATE
sourceAmount = 0  → ZERO
balanceEffect = -sourceAmount
```

- reversal이 아닌 execution은 fee 전액을 그 execution의 유일한 allocation에 둔다. 따라서 partial close의
  fee도 해당 `REDUCE` allocation에 전액 귀속된다.
- reversal만 E절 비율로 close/open에 나눈다.
- 합계는 asset별 signed source amount로 유지한다. 서로 다른 fee asset을 서로 더하지 않는다.
- fee asset이 settlement asset과 같으면 `tradingFees`에 직접 합산한다. 다르면 pinned immutable valuation과
  valuation version/time이 있는 경우에만 환산한다.
- valuation이 없으면 `FEES` capability와 asset별 fee evidence는 유지하되 affected episode의
  `CLOSED_OUTCOME`(net PnL) capability를 부여하지 않는다. import 전체는 수락할 수 있다.
- rebate는 음수 `tradingFees`이므로 `netTradingPnl=grossTradingPnl-tradingFees`에서 net을 증가시킨다.

```text
∀ feeAsset: Σ allocation.allocatedSourceFee[feeAsset]
            = Σ linked FeeRecord.sourceAmount[feeAsset]
```

## H. Realized PnL

```text
grossTradingPnl = Σ allocated VenueReportedPnlRecord.realizedProfit
venuePositionPnl = matched VenuePositionSummaryRecord.closingPnl
tradingFees = Σ FeeRecord.sourceAmount converted to settlement asset when possible
netTradingPnl = grossTradingPnl - tradingFees
fundingTreatment = EXCLUDED
```

- `OPEN`, `INCREASE`, `REVERSAL_OPEN`의 allocated reported PnL은 정확히 0이어야 한다.
- `REDUCE`, `CLOSE`에는 row의 reported PnL 전액을 배정한다.
- reversal row의 PnL 전액은 `REVERSAL_CLOSE`, open에는 0을 배정한다. 수량 비율로 PnL을 나누지 않는다.
- fee rebate는 G절대로 fee에서 처리하며 gross PnL에 섞지 않는다.
- Position History `Closing PNL`은 closing allocation의 `Realized Profit` 합계와 조정하는 gross 값이다. opening
  fee와 closing fee 어느 것도 차감하지 않으며 net PnL로 해석하지 않는다.
- Trade-reported PnL, Position closing PNL, D절 자체 계산 PnL은 역할이 다르다. 불일치 시 어느 값도 다른
  값으로 자동 보정하지 않고 status, delta와 provenance를 기록한다.

## I. Censored and Incomplete Episodes

| 조건 | ReconstructionStatus | ReconciliationStatus 기본값 | 분석 | exclusion reason |
|---|---|---|---|---|
| summary `openedAt`이 해당 instrument의 earliest Trade보다 앞서거나 첫 opening row에 non-zero PnL이 있어 시작을 관측하지 못함 | `LEFT_CENSORED` shell; reconstructed `openedAt=null` | `INCOMPLETE_TRADES` | 제외 | `EPISODE_LEFT_CENSORED` |
| 마지막 execution 뒤 signed quantity가 non-zero | `RIGHT_CENSORED`, `closedAt=null` | `INCOMPLETE_TRADES` | 제외 | `EPISODE_RIGHT_CENSORED` |
| summary 수량/PnL은 추가 execution을 요구하거나 opening action PnL이 non-zero | `INCONSISTENT` | `INCOMPLETE_TRADES` | 제외 | `MISSING_EXECUTION_SUSPECTED` |
| execution quantity/price가 pinned step/tick grid 위반 | `INCONSISTENT` | `SUMMARY_MISMATCH` | 제외 | `INSTRUMENT_RULE_VIOLATION` |
| Position summary만 있고 trade candidate 없음 | `LEFT_CENSORED` 또는 `INCONSISTENT` shell | `INCOMPLETE_TRADES` | 제외 | `POSITION_SUMMARY_WITHOUT_TRADES` |
| complete trade episode만 있고 summary 없음(특히 Position latest observed timestamp 뒤) | `COMPLETE` | `SUMMARY_MISMATCH` | 제외 | `POSITION_SUMMARY_MISSING` |
| 같은 logical position을 주장하는 summary가 상충 | affected unit `INCONSISTENT` | `AMBIGUOUS` 또는 `SUMMARY_MISMATCH` | 제외 | `CONTRADICTORY_POSITION_SUMMARY` |
| 한 execution quantity unit을 복수 summary/episode가 요구 | `INCONSISTENT` | `UNSUPPORTED_OVERLAP` | 제외; hedge evidence면 import 거절 | `DUPLICATE_EXECUTION_ASSIGNMENT` |
| 지원하지 않는 product/mode/policy | `UNSUPPORTED` | `UNSUPPORTED_OVERLAP` | 제외 또는 import 거절 | `UNSUPPORTED_PRODUCT_OR_MODE` |

한 unit에 여러 reconstruction condition이 적용되면 단일 status precedence는
`UNSUPPORTED > INCONSISTENT > LEFT_CENSORED > RIGHT_CENSORED > COMPLETE`다. 낮은 우선순위 조건도
`exclusionReasons[]`에 모두 보존한다. 따라서 missing execution 증거와 non-zero end가 함께 있으면 status는
`INCONSISTENT`, reasons에는 `MISSING_EXECUTION_SUSPECTED`와 `EPISODE_RIGHT_CENSORED`가 함께 든다.

`LEFT_CENSORED` shell은 summary/provenance와 관측 가능한 close evidence만 담으며 unknown opening quantity,
`openedAt`, average entry를 채우지 않는다. summary 값으로 누락 execution을 합성하지 않는다. state machine이
다음 predicate를 모두 만족할 때만 `COMPLETE`가 가능하다.

```text
initializedAtExactZero AND no left-censor evidence exists
AND first allocation is OPEN or REVERSAL_OPEN with allocatedReportedPnl = 0
AND endedAtObservedExactZero
AND openedAt and closedAt are execution instants
AND every execution quantity unit is allocated exactly once
AND fee conservation by asset passes
AND reported PnL conservation passes
AND every execution is on the pinned step/tick grid
AND no missing-boundary, overlap, unsupported-mode, or unresolved-order evidence exists
```

Position summary match만으로 `COMPLETE`를 만들지는 않는다. reconciliation이 실패해도 trade reconstruction의
flat-to-flat predicate 자체는 `COMPLETE`일 수 있으나 분석에는 포함되지 않는다.

left-censored summary와 Position coverage 뒤 complete episode는 artifact 기간 차이로 예상 가능한 quality
exclusion이며 Import 전체를 실패시키지 않는다. 그러나 후자를 정상 reconciliation이나 episode 0건으로
바꾸지 않는다. quality report는 role별 coverage, 포함/제외 count와 위 reason을 함께 제공해야 한다.

## J. Reconciliation Algorithm

### J.1 Constraint와 candidate

각 supported `Closed` summary와 episode에 대해 아래 순서로 처리한다.

1. **Hard partition**: 동일 pinned instrument와 lossless source symbol, 동일 direction이어야 한다.
2. **Temporal primary constraint**: episode `openedAt`과 summary `openedAt`, episode `closedAt`과 summary
   `closedAt`이 각각 exact UTC instant equality여야 한다. timestamp tolerance나 candidate range를 적용하지
   않는다.
3. **Numeric primary constraint**: `maxQuantity`, `closedVolume`, `grossTradingPnl`이 K절의 각 tolerance 이내다.
4. 위 조건을 모두 만족하는 pair만 primary candidate edge다. 하나라도 초과하면 delta/reason을 진단에 남긴다.
5. 동일 connected component에 edge가 여러 개이면 summary entry price와 average close price를 secondary
   equality constraint로 적용한다. 둘 다 존재할 때 K절 price tolerance 이내인 edge만 남긴다. secondary
   값이 없거나 여전히 여러 edge면 nearest distance를 계산하거나 선택하지 않는다.

평균 entry/exit price는 부분 진입·청산 순서와 venue rounding 영향을 받으므로 primary truth가 아니다. 다만
동일 exact timestamp에 동일 direction episode가 여러 개 생겨 primary constraint가 같은 경우, 명시적 tolerance equality로
후보를 제거하는 보조 증거가 된다. 가장 가까운 값을 고르는 score로 사용해서는 안 된다.

`closedVolume=Σ(REDUCE,CLOSE,REVERSAL_CLOSE).allocatedQuantity`이며 complete episode에서는
`openingVolume=closedVolume`이어야 한다. max quantity는 state transition 후 absolute position의 최댓값이다.

### J.2 One-to-one 판정

candidate graph의 각 connected component에서 episode-summary one-to-one matching을 열거한다.

- 전체 component를 덮는 perfect matching이 정확히 하나면 그 pair들을 유일하게 매칭한다.
- 가능한 perfect matching이 2개 이상이면 관련 모든 pair는 `AMBIGUOUS`다. source row 순서, 최소 delta,
  가장 가까운 시간으로 tie-break하지 않는다.
- edge가 있지만 vertex 수 불일치 등으로 component 전체를 덮는 perfect matching이 없으면 해당 component를
  부분적으로 채택하지 않는다. 복수 candidate를 가진 vertex는 `AMBIGUOUS`, candidate가 없는 episode/summary는
  아래의 mismatch/incomplete 상태를 사용한다.
- candidate가 없는 summary는 `INCOMPLETE_TRADES`; candidate가 없는 complete episode는
  `SUMMARY_MISMATCH`다.
- structural/time pair는 있으나 primary numeric constraint가 tolerance를 넘으면 `SUMMARY_MISMATCH`다.
- 동일 execution allocation이 둘 이상의 summary에 필요하거나 opposite directions의 동시 position이
  필요하면 `UNSUPPORTED_OVERLAP`이다.
- 값이 같은 Position row도 `artifactId+sourceRowNumber`가 다른 별도 record다. 두 duplicate row가 같은
  episode를 요구하면 자동 deduplicate하지 않고 component가 복수 matching이면 `AMBIGUOUS`다.

유일 match에서 모든 primary/사용된 secondary numeric 값이 exact Decimal equality면 `EXACT`, 적어도 하나가
exact는 아니지만 tolerance 이내면 `WITHIN_ROUNDING_TOLERANCE`다. 앞의 두 상태만 eligible이다.
한 unit에 여러 reconciliation condition이 적용될 때 단일 status precedence는
`UNSUPPORTED_OVERLAP > AMBIGUOUS > INCOMPLETE_TRADES > SUMMARY_MISMATCH > WITHIN_ROUNDING_TOLERANCE > EXACT`며
모든 낮은 우선순위 issue도 manifest에 남긴다.

## K. Oracle and Tolerance

### K.1 Oracle 역할

1. Binance Trade History execution과 realized profit
2. Position History summary
3. 이 문서의 reconstruction 계산식
4. 수작업 검증 golden fixture
5. Binance UI 표시값

이는 overwrite 우선순위가 아니다. Trade History는 execution/allocation/gross PnL의 oracle이고 Position History는
종료 포지션 aggregate constraint의 oracle이다. 자체 계산은 두 source의 차이를 탐지하며, golden fixture는
구현을 검증한다. UI의 반올림 표시가 source record를 고치지 않는다.

### K.2 Derived tolerance

`ulp(scale)=10^-scale`, pinned `stepSize`, `tickSize`, `contractMultiplier`, settlement precision `sp`를 사용한다.
두 source 값 `a,b`의 scale을 `sa,sb`라 한다.

```text
sourceBound(a,b) = ulp(sa)/2 + ulp(sb)/2
quantityTolerance(a,b) = max(sourceBound(a,b), stepSize/2)
priceTolerance(a,b) = max(sourceBound(a,b), tickSize/2)
moneyTolerance(a,b) = max(sourceBound(a,b), 10^-sp/2)
```

각 tolerance와 구성 입력은 manifest에 Decimal로 기록한다. quantity에는 quantity tolerance, entry/close price에는
price tolerance, gross PnL에는 money tolerance만 적용한다. tick/step을 money tolerance에 임의 전파하지
않는다. self-calculated PnL을 source PnL과 비교할 때만 별도 진단 bound를 다음처럼 기록한다.

```text
selfPnlDiagnosticTolerance = moneyTolerance
  + (tickSize/2 * closedVolume * contractMultiplier)
  + (stepSize/2 * max(abs(entryPrice),abs(exitPrice)) * contractMultiplier)
```

`abs(a-b)=0`은 exact, `0<abs(a-b)<=tolerance`는 within, 그보다 크면 mismatch다. 고정 magic number,
percentage tolerance, binary float를 사용하지 않는다.

### K.3 Failure outcome

| 상황 | 결과 |
|---|---|
| B절 session/schema/product/mode/identity fatal precondition 위반 | Import 전체 `REJECTED`; LedgerRevision 없음 |
| 일부 episode censored/inconsistent/ambiguous/mismatch | LedgerRevision과 manifest 생성 가능; 해당 episode 제외 |
| 유일 match가 derived tolerance 안 | warning/delta와 함께 분석 허용 (`WITHIN_ROUNDING_TOLERANCE`) |
| tolerance 초과 또는 summary 누락 | LedgerRevision 생성 가능; episode 제외 |
| Analysis에서 pinned manifest hash/version 불일치 또는 보존식 위반 | Analysis `FAILED`; 새 추정 manifest로 계속하지 않음 |

## L. Failure Taxonomy

민감정보는 모든 표에서 동일하게 처리한다. 원본 UID, filename의 사용자 식별 부분, raw row, account
fingerprint 전체, storage reference는 user message와 일반 log에 넣지 않는다. log에는 import/session ID,
artifact role, source row number, code, hash prefix(최대 12 hex), synthetic-safe instrument ID만 허용한다.

### L.1 ImportFailureCode

| 값 | 발생 조건 | 사용자 메시지 의미 | 재시도 | Import 실패 | Episode 제외 | log |
|---|---|---|---|---|---|---|
| `FILE_FORMAT_INVALID` | decode/CSV/decimal/time/Trade ID row 문법 오류 | 파일을 읽거나 값 형식을 검증할 수 없음 | 수정 파일로 가능 | 예 | 해당 없음 | WARN; parser stack은 DEBUG |
| `REQUIRED_COLUMN_MISSING` | required logical column 부재 | 필수 열이 없음 | 올바른 export로 가능 | 예 | 해당 없음 | WARN |
| `UNSUPPORTED_SOURCE_SCHEMA` | header 중복/변경, 미지원 dialect 의미 | 지원하는 Binance V1 형식이 아님 | 지원 export로 가능 | 예 | 해당 없음 | WARN |
| `UNSUPPORTED_PRODUCT` | non-USDⓈ linear perpetual 또는 pinned terms 없음 | 현재 상품 범위가 아님 | 지원 상품 파일로 가능 | 예 | 해당 없음 | WARN |
| `UNSUPPORTED_POSITION_MODE` | One-way 미선언/비호환, hedge overlap | One-way 자료로 확인되지 않음 | 올바른 mode 자료로 가능 | 예 | 해당 없음 | WARN |
| `DUPLICATE_SOURCE_ARTIFACT` | 한 role artifact가 2개 이상 등록됨 | Trade/Position 파일은 각각 하나여야 함 | session 재구성 가능 | 예 | 해당 없음 | INFO |
| `RECORD_CONFLICT` | 동일 identity 다른 content 또는 numeric-equivalent raw Trade ID | 같은 체결 증거가 서로 모순됨 | source 확인 후 가능 | 예 | 해당 없음 | ERROR; 값 대신 row/hash만 |

### L.2 ReconstructionStatus

| 값 | 발생 조건 | 사용자 메시지 의미 | 재시도 | Import 실패 | Episode 제외 | log |
|---|---|---|---|---|---|---|
| `COMPLETE` | 관측된 flat-to-flat이며 보존/semantic 검증 통과 | 시작과 종료 체결이 관측됨 | 불필요 | 아니오 | reconciliation에 따름 | INFO summary |
| `LEFT_CENSORED` | 시작이 coverage 이전/누락 | 시작 체결을 확인할 수 없음 | 더 이른 export로 가능 | 아니오 | 예 | INFO |
| `RIGHT_CENSORED` | coverage 끝에 non-zero | 종료 체결을 확인할 수 없음 | 더 늦은 export로 가능 | 아니오 | 예 | INFO |
| `INCONSISTENT` | grid/PNL/allocation/summary 증거 모순 | 체결 기록으로 일관된 episode를 만들 수 없음 | 수정/확장 export로 가능 | 보통 아니오 | 예 | WARN |
| `UNSUPPORTED` | 정책이 의미를 정의하지 않음 | 현재 복원 범위 밖 | 지원 확대 전 불가 | fatal mode/product면 예 | 예 | WARN |

### L.3 ReconciliationStatus

| 값 | 발생 조건 | 사용자 메시지 의미 | 재시도 | Import 실패 | Episode 제외 | log |
|---|---|---|---|---|---|---|
| `EXACT` | unique match, 모든 비교 exact | 두 파일이 정확히 일치 | 불필요 | 아니오 | 아니오 | INFO summary |
| `WITHIN_ROUNDING_TOLERANCE` | unique match, derived tolerance 안 차이 | source 정밀도 범위에서 일치 | 불필요 | 아니오 | 아니오 | INFO + delta |
| `AMBIGUOUS` | 복수 one-to-one matching | 어떤 요약인지 유일하게 정할 수 없음 | 더 정밀한 자료로 가능 | 아니오 | 예 | WARN |
| `INCOMPLETE_TRADES` | summary를 설명할 execution 부족 | Trade History가 불완전해 보임 | 더 넓은 export로 가능 | 아니오 | 예 | WARN |
| `SUMMARY_MISMATCH` | summary 없음 또는 constraint 초과 | Position History와 일치하지 않음 | 올바른 pair로 가능 | 아니오 | 예 | WARN |
| `UNSUPPORTED_OVERLAP` | 중복 allocation/hedge 요구 | One-way 규칙으로 조정할 수 없음 | One-way 자료면 가능 | hedge evidence면 예 | 예 | ERROR |

### L.4 MetricStatus

| 값 | 발생 조건 | 사용자 메시지 의미 | 재시도 | Import 실패 | Episode 제외 | log |
|---|---|---|---|---|---|---|
| `AVAILABLE` | capability와 최소 표본 충족 | 값이 계산됨; 값 0도 유효 | 불필요 | 아니오 | 아니오 | INFO summary |
| `INSUFFICIENT_SAMPLE` | eligible 표본이 definition minimum 미만 | 자료는 있으나 표본 부족 | 더 많은 기간으로 가능 | 아니오 | 아니오 | INFO |
| `MISSING_CAPABILITY` | required capability 없음 | 필요한 종류의 근거가 없음 | 더 완전한 source로 가능 | 아니오 | affected sample 제외 | INFO |
| `NOT_APPLICABLE` | 상품/상황에 metric 정의가 적용되지 않음 | 0이 아니라 적용 대상 아님 | 같은 입력으로 불가 | 아니오 | metric population 제외 | INFO |

## M. Capability Generation

Capability는 먼저 episode scope에서 만들고 LedgerRevision은 episode별 map과 전체 보유 종류의 union을
고정한다. metric은 revision-level 이름만 보고 모든 episode에 있다고 간주해서는 안 된다.

| Capability | episode 생성 조건 |
|---|---|
| `CLOSED_OUTCOME` | `COMPLETE` + eligible reconciliation + 모든 fee의 settlement valuation이 있어 net PnL 계산 가능 |
| `INITIAL_EXPOSURE` | observed opening allocation과 pinned multiplier로 initial notional 계산 가능 |
| `MAX_EXPOSURE` | 전체 state path와 pinned terms로 max notional 계산 가능 |
| `POSITION_INCREASES` | complete ordered path가 있고 INCREASE 유무를 0과 구분 가능 |
| `FEES` | 연결된 모든 FeeRecord를 asset별로 보존/배분하고 fee conservation 통과 |
| `VENUE_REPORTED_PNL` | 연결된 VenueReportedPnlRecord 전부 배분하고 PnL conservation 통과 |
| `VENUE_POSITION_SUMMARY` | supported status summary와 unique match |
| `POSITION_MODE_USER_DECLARED` | session에 One-way 선언이 고정됨 |
| `POSITION_MODE_OBSERVED_COMPATIBLE` | 전체 allocation graph가 One-way와 양립하고 overlap 없음 |
| `EXECUTION_ORIGIN_KNOWN` | canonical evidence가 origin을 명시할 때만; CSV MVP에서는 부여하지 않음 |

다른 asset fee valuation이 없으면 `FEES`는 유지하고 해당 episode의 `CLOSED_OUTCOME`만 누락한다. source
import를 실패시키거나 fee를 0으로 만들지 않는다.

## N. Invariants and Manifest Hash

```text
N1  ∀ execution: execution.quantity = Σ allocations.allocatedQuantity
N2  ∀ episode: openingVolume = closingVolume  (COMPLETE only)
N3  ∀ fee asset: Σ allocatedFee = Σ source FeeRecord.sourceAmount
N4  Σ allocatedReportedPnl = Σ source VenueReportedPnlRecord.realizedProfit
N5  한 execution quantity unit은 정확히 한 allocation에만 속한다. reversal의 disjoint split은 허용한다.
N6  한 Position summary는 최대 한 eligible episode, 한 eligible episode는 정확히 한 summary에 매칭된다.
N7  same canonical input + terms + manifest schema/reconstruction version = same manifest hash
N8  ambiguous/censored/inconsistent/unsupported data는 추정으로 COMPLETE/eligible이 되지 않는다.
N9  모든 episode/allocation/match/issue는 canonical record를 거쳐 source artifact row로 추적된다.
N10 서로 다른 symbol의 동시 execution 순서는 결과/hash에 의미 있는 전역 순서를 만들지 않는다.
```

Manifest hash payload는 다음을 포함한다: `manifestSchemaVersion`, `normalizerVersion`,
`reconstructionPolicyVersion`, pinned instrument terms hash, source artifact content hash/role,
canonical record content hash, tolerance inputs/derived values, episode ID/status/capability, ordered allocation,
summary match/status/delta, issue/exclusion code와 provenance reference. runtime-generated DB ID, `generatedAt`, worker,
storage path, manifest hash 자체는 제외한다.

serialization은 UTF-8 canonical JSON을 사용한다. object key는 Unicode code point 오름차순, array는 명시된
semantic key(artifact role/hash; instrument ID; UTC time/Trade ID integer; episode ID; source row)로 정렬하고
공백을 넣지 않는다. Decimal은 JSON number가 아니라 `{"unscaled":"-502299","scale":8}` 형태, timestamp는
UTC RFC 3339 고정 microsecond 6자리, enum은 이 문서의 uppercase 값을 사용한다. hash는 serialization
byte의 SHA-256 lowercase hex다. episode ID도 policy version, instrument ID와 ordered allocation identity의
canonical bytes로 결정론적으로 생성한다.

### N.1 TR-I01~TR-I13 적용 확인

| 불변식 | 이 명세의 강제 지점 |
|---|---|
| `TR-I01` | manifest/provenance는 같은 session/book의 record만 참조하며 cross-owner 결합은 import 전 거절 |
| `TR-I02` | B.3의 venue/product/mode/settlement와 pinned terms partition |
| `TR-I03` | B.1의 dual-artifact precondition과 L.1의 session-level rejection |
| `TR-I04` | B.2, N9와 O.3의 source row provenance |
| `TR-I05` | correction은 기존 canonical record/manifest를 수정하지 않고 새 revision에서 수행 |
| `TR-I06` | N의 manifest payload/hash와 pinned versions/terms |
| `TR-I07` | C/E와 N1, N2, N5의 quantity conservation |
| `TR-I08` | E/G/H와 N3, N4의 asset별 cash-flow conservation |
| `TR-I09` | I/J의 censored, mismatch, ambiguity와 COMPLETE predicate |
| `TR-I10` | G/M/O.3의 missing capability/null/zero 분리 |
| `TR-I11` | B.4 정렬과 N7 canonical hash |
| `TR-I12` | L/M/O.3의 quality, eligible/excluded, status와 evidence expected result |
| `TR-I13` | D의 loss/profit context는 관찰 label이며 origin, 의도, 인과를 추정하지 않음 |

## O. Scenario Tables

### O.1 기계 판독용 표기

모든 값은 합성값이다. 기본 instrument는 `SYNTHUSDT`, `step=1`, `tick=0.01`, multiplier `1`, settlement
precision `2`, source timezone `UTC`다. execution 표기는
`time/id side quantity@price;rp;feeAsset:fee`다. `p0→pN`은 시작/종료 signed quantity, `A[...]`는 action과
allocated quantity다. summary `S(dir,openedAt,closedAt,max,closed,pnl)`은 supported `Closed` row이며 두 timestamp는
second precision exact local value를 pinned timezone으로 canonicalize한 instant다.
별도 언급이 없으면 provenance는 `T:rN`(Trade artifact row)과 `P:rN`(Position artifact row)을 모두 가진다.
축약 execution에 time/ID가 없으면 표의 왼쪽부터 `10:00:01`에서 1분 간격, Trade ID는
`scenarioNumber*100 + 1-basedOrdinal`, fee는 `USDT:0.00`을 사용한다. `summary` 또는 `matching summary`는
그 execution에서 계산한 direction/exact openedAt/closedAt/max/closed/gross PnL, average entry/close price와
`Status=Closed`, `Margin Mode=Isolated`를 정확히 가진다. anomaly로 다른 값이나 summary 부재를 명시한 행은
이 기본값을 사용하지 않는다. 나머지 required Trade field는 합성 `Uid=1`, `Order ID=Trade ID*10`,
`Amount=Price*Quantity`, `Buyer=(Side=BUY)`, `Maker=false`다. 따라서 축약 표기도 별도 heuristic 없이 완전한
합성 row로 전개할 수 있다.
Capabilities 약어는 `CO,IE,ME,PI,F,VP,VS,UD,OC` = `CLOSED_OUTCOME`, `INITIAL_EXPOSURE`,
`MAX_EXPOSURE`, `POSITION_INCREASES`, `FEES`, `VENUE_REPORTED_PNL`, `VENUE_POSITION_SUMMARY`,
`POSITION_MODE_USER_DECLARED`, `POSITION_MODE_OBSERVED_COMPATIBLE`다. `EXECUTION_ORIGIN_KNOWN`은 어느
시나리오에도 없다.

### O.2 정상·경계 시나리오

| # / 상황 | 합성 execution / summary | p0→pN; allocation | expected episode 핵심 | Reconstruction | Reconciliation | capability | 분석 / exclusion | provenance |
|---|---|---|---|---|---|---|---|---|
| 1 Long 전량 청산 | `10:00:01/101 BUY 2@10.00;0;USDT:0.02`, `10:05:01/102 SELL 2@11.00;2.00;USDT:0.02`; `S(Long,10:00:01,10:05:01,2,2,2.00)` | `0→0`; `OPEN2,CLOSE2` | Long, initial/max 2, avg entry 10, exit 11, gross 2, fee .04, net 1.96 | `COMPLETE` | `EXACT` | 전부(`OC` 포함) | 포함 / — | T:r1-r2, P:r1 |
| 2 Short 전량 청산 | `10:00:01/201 SELL 3@20;0;USDT:0.03`, `10:06:01/202 BUY 3@18;6;USDT:0.03`; matching summary | `0→0`; `OPEN3,CLOSE3` | Short initial/max 3, gross 6 | `COMPLETE` | `EXACT` | 전부 | 포함 / — | T:r1-r2,P:r1 |
| 3 분할 Long 진입 | `BUY 1@10`, `BUY 2@13`, `SELL 3@14;6`; matching summary | `0→0`; `OPEN1,INCREASE2,CLOSE3` | avg entry `36/3=12`, initial 1,max 3 | `COMPLETE` | `EXACT` | 전부 | 포함 | 각 row |
| 4 분할 청산 | `BUY 4@10`, `SELL 1@11;1`, `SELL 3@12;6`; summary closed 4 pnl 7 | `0→0`; `OPEN4,REDUCE1,CLOSE3` | avg exit `47/4=11.75`, gross 7 | `COMPLETE` | `EXACT` | 전부 | 포함 | 각 row |
| 5 확대와 축소 | `BUY2@10`,`BUY3@12`,`SELL1@13;1.8`,`SELL4@11;-0.8`; matching summary | `0→0`; `OPEN2,INCREASE3,REDUCE1,CLOSE4` | initial2,max5, avg entry11.2, gross1; 두 번째 fill은 profit-context increase | `COMPLETE` | `EXACT` | 전부 | 포함 | 각 row+summary |
| 6 손실 중 추가 진입 | Long `BUY2@10`, then `BUY1@8`, close `SELL3@11;5`; summary | `0→0`; `OPEN2,INCREASE1,CLOSE3` | pre-add price 8 < avg10: loss-context label; avg entry `28/3` | `COMPLETE` | `EXACT` | 전부 | 포함; 인과/의도 없음 | 각 row |
| 7 Long→Short reversal | `BUY3@10;0;USDT:0.00`, `SELL5@9;-3;USDT:0.05`, `BUY2@8;2;USDT:0.00`; Long/Short matching summaries | `0→-2→0`; `OPEN3,REV_CLOSE3+REV_OPEN2,CLOSE2` | Long closes and Short opens at same time; reversal fee `0.03/0.02`; reversal group | 둘 다 `COMPLETE` | 둘 다 `EXACT` | 전부 | 둘 다 포함; rapid re-entry 제외 | reversal row가 disjoint allocation으로 두 episode 참조 |
| 8 Short→Long reversal | `SELL4@10;0;USDT:0.00`, `BUY6@11;-4;USDT:0.06`, `SELL2@12;2;USDT:0.00`; two matching summaries | `0→+2→0`; `OPEN4,REV_CLOSE4+REV_OPEN2,CLOSE2` | reversal fee `0.04/0.02`, opening PnL 0 | 둘 다 `COMPLETE` | 둘 다 `EXACT` | 전부 | 포함; reversal 분리 | 각 row |
| 9 CSV 전 이미 열린 position | earliest `BUY2@9;rp=2`와 `S(Short,09:50:00,10:00:00,2,2,2)` | state 기준 provisional `0→+2`; 정상 OPEN으로 승격 금지 | summary shell의 reconstructed `openedAt=null`; provisional unit issue | `LEFT_CENSORED` shell + `INCONSISTENT` | `INCOMPLETE_TRADES` | `F,VP,UD`; CO/IE/ME 없음 | 제외 / `EPISODE_LEFT_CENSORED` | T:r1,P:r1 모두 issue에 연결 |
| 10 CSV 종료 열린 position | `BUY2@10`만, closed summary 없음 | `0→+2`; `OPEN2` | Long `closedAt=null` | `RIGHT_CENSORED` | `INCOMPLETE_TRADES` | `IE,ME,PI,F,VP,UD,OC` | 제외 / `EPISODE_RIGHT_CENSORED` | T:r1 |
| 11 동일 timestamp 다중 체결 | `id=302 SELL1`, `id=301 BUY2`, 같은 초; numeric ID order는 301→302 | `0→+1`; `OPEN2,REDUCE1` | Long right-censored; lexical/file row order 무관 | `RIGHT_CENSORED` | `INCOMPLETE_TRADES` | CO/VS 없음 | 제외 | both rows ordered by ID |
| 12 동일 timestamp 잘못된 Trade ID | `10:00:01/31A BUY1@10`, `10:00:01/32 SELL1@11` | state 실행 안 함 | episode/manifest 없음 | 해당 없음 | 해당 없음 | 없음 | Import 거절 / `FILE_FORMAT_INVALID` | artifact row number만 masked error에 보존 |
| 13 누락 execution | `BUY3@10`, `SELL2@11;2`; summary `S(Long,10:00:01,10:02:01,3,3,3)` | `0→+1`; `OPEN3,REDUCE2` | missing close evidence; right-censored reason도 보존 | `INCONSISTENT` | `INCOMPLETE_TRADES` | CO 없음 | 제외 / `MISSING_EXECUTION_SUSPECTED`,`EPISODE_RIGHT_CENSORED` | trade+summary rows |
| 14 Position coverage 뒤 Trade episode | Position latest observed timestamp 뒤 `BUY1@10`, `SELL1@11;1`; matching Position row 없음 | `0→0`; `OPEN1,CLOSE1` | trade episode complete; coverage completeness는 `NOT_PROVEN` | `COMPLETE` | `SUMMARY_MISMATCH` | VS 없음, CO 없음 | 제외 / `POSITION_SUMMARY_MISSING` | Trade rows + artifact coverage |
| 15 Position summary만 존재 | no trade; one `S(Long,10:00:00,10:05:00,1,1,1)` | no state/allocation | summary shell, no synthesized execution | `INCONSISTENT` | `INCOMPLETE_TRADES` | `VS,UD`; OC 없음 | 제외 / `POSITION_SUMMARY_WITHOUT_TRADES` | P:r1 |
| 16 동일 exact timestamp ambiguous | 네 execution이 모두 `10:00:01`: `1601 BUY1@10`,`1602 SELL1@11;1`,`1603 BUY1@10`,`1604 SELL1@11;1`; exact timestamp와 numeric 값이 같은 summary 2개 | numeric ID 순으로 두 번 `0→0`; 각 `OPEN1,CLOSE1` | primary/secondary graph가 2×2이고 두 perfect matching 가능 | 둘 다 `COMPLETE` | 둘 다 `AMBIGUOUS` | VS/CO 없음 | 제외 / candidate ambiguity | 모든 candidate row/delta |
| 17 동일 execution 중복 row | `BUY1@10` row가 같은 Trade artifact에서 같은 key/content로 2회, 이후 `SELL1@11;1`; matching summary | dedup 후 `0→0`; `OPEN1,CLOSE1` | 한 episode; duplicated canonical record provenance 2개 | `COMPLETE` | `EXACT` | 전부 | 포함 | 두 T provenance가 한 record에 연결 |
| 18 동일 identity 다른 content | same synthetic Trade ID `1801` row가 `BUY1@10`과 `BUY2@10`을 주장 | state 실행 안 함 | 없음 | 해당 없음 | 해당 없음 | 없음 | Import 거절 / `RECORD_CONFLICT` | row/hash만 log |
| 19 fee rebate | `BUY1@10;0;USDT:0.02`, `SELL1@12;2;USDT:-0.01`; matching summary | `0→0`; `OPEN1,CLOSE1` | tradingFees `0.01`, net `1.99`; rebate는 gross에 미포함 | `COMPLETE` | `EXACT` | 전부 | 포함 | FeeRecord sign까지 추적 |
| 20 다른 fee asset | `BUY1@10;0;FEECOIN:0.001`, `SELL1@12;2;USDT:0.00`; matching summary, valuation 없음 | `0→0`; `OPEN1,CLOSE1` | asset fee 보존, net null | `COMPLETE` | `EXACT` | `F,VP,VS,IE,ME,PI,UD,OC`; `CO` 없음 | net-required metric 제외 / `MISSING_CAPABILITY` | fee asset source row |
| 21 Hedge overlap | `10:00:01/2101 BUY1@10;0;USDT:0.00`, `10:05:01/2102 SELL1@11;1;USDT:0.00`, `10:10:01/2103 BUY1@10;0;USDT:0.00`; summaries는 Long `10:00:01–10:10:01`, Short `10:05:01–10:10:01`을 동시에 주장 | summary 만족에는 2102가 Short OPEN이면서 Long 상태와 겹쳐야 함 | provisional issue만 | `UNSUPPORTED` | `UNSUPPORTED_OVERLAP` | `OC` 없음 | Import 거절 / `UNSUPPORTED_POSITION_MODE` | conflicting execution/summary refs |
| 22 미지원 Position status | `BUY1@10`, `SELL1@11;1`; otherwise matching summary의 status만 `Open` | `0→0`; `OPEN1,CLOSE1` | trade episode는 flat-to-flat; summary는 candidate 제외 | `COMPLETE` | `SUMMARY_MISMATCH` | VS/CO 없음 | 제외 / `UNSUPPORTED_POSITION_STATUS` | Trade와 P:r1 issue |
| 23 Position PnL mismatch | `BUY1@10.00`, `SELL1@12.00;2.00`; summary PNL `2.50`, money tolerance `0.01` | `0→0`; `OPEN1,CLOSE1` | delta `0.50` 보존, 자동 보정 없음 | `COMPLETE` | `SUMMARY_MISMATCH` | VS/CO 없음 | 제외 / `PNL_MISMATCH` | both PnL records |
| 24 rounding tolerance 내부 | `BUY1@10.00;0.000`, `SELL1@12.00;2.004`; summary PNL `2.00`; scales 3/2, settlement precision 2 → tolerance `0.0055` | `0→0`; `OPEN1,CLOSE1` | delta `0.004` | `COMPLETE` | `WITHIN_ROUNDING_TOLERANCE` | 전부 | 경고와 함께 포함 | tolerance inputs/delta 포함 |
| 25 rounding tolerance 초과 | `BUY1@10.00;0.000`, `SELL1@12.00;2.006`; summary PNL `2.00`; delta `0.006>0.0055` | `0→0`; `OPEN1,CLOSE1` | source 값 모두 보존 | `COMPLETE` | `SUMMARY_MISMATCH` | VS/CO 없음 | 제외 / `PNL_MISMATCH` | tolerance inputs/delta 포함 |

### O.3 Golden expected result 필수 필드

후속 machine-readable fixture는 각 scenario에 최소 다음을 명시해야 한다.

```text
scenarioId
input.artifacts[{role,dialect,encoding,bom,delimiter,lineEnding,quoting,syntheticRows,sha256}]
input.sourceTimezone
input.declaredPositionMode
input.instrumentTerms{stepSize,tickSize,contractMultiplier,settlementPrecision}
expected.import{accepted,failureCode?,warnings[]}
expected.artifactCoverages[{role,earliestObservedAt?,latestObservedAt?,rowCount,sourceTimezone,
  timestampPrecision:SECOND,coverageCompleteness:NOT_PROVEN}]
expected.orderedExecutionKeysByInstrument
expected.episodes[{deterministicKey,direction,openedAt?,closedAt?,initialQuantity?,maxQuantity?,
  openingVolume,closingVolume,averageEntry{numerator,denominator}?,averageExit{numerator,denominator}?,
  grossTradingPnl?,tradingFeesByAsset,netTradingPnl?,reconstructionStatus,reconciliationStatus,
  capabilities[],eligible,exclusionReasons[],provenance[]}]
expected.allocations[{executionKey,episodeKey,action,quantity,feeByAsset,reportedPnl,reversalGroupId?}]
expected.summaryMatches[{summarySourceRow,episodeKey?,status,deltas,tolerances,candidateEpisodeKeys[]}]
expected.conservation{quantity,feesByAsset,reportedPnl,passed}
expected.manifest{schemaVersion,reconstructionPolicyVersion,canonicalHash}
```

`null`, absent, zero를 서로 바꿔 쓰지 않는다. 알려진 실제 값 0은 scale을 포함한 Decimal로, 알 수 없거나
capability가 없는 값은 `null`과 명시적 status/reason으로 기록한다.
