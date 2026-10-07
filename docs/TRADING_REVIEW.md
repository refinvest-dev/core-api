# TRADING_REVIEW.md — 실제 거래 행동 분석 도메인

이 문서는 RefInvest의 `Trading Review` 제품 정체성, MVP 경계, 확장 가능한 도메인 모델과
미확정 쟁점을 정의한다. Binance One-way 복원 계산의 기술 정본은
[`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md), 초기 세 Behavior Metric의 계산 정본은
[`TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md), 데이터 분류·보관·삭제의 상세 정본은
[`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md), version compatibility·재처리·result lineage의
상세 정본은 [`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)다. Aggregate의 요약 불변식은 `docs/DOMAIN.md`, 확정된 결정은
`docs/DECISIONS.md`, 구현 순서는 `docs/ROADMAP.md`, 공개/내부 API의 최종 계약은 `openapi/`가
각각 정본이다.

---

## 1. 제품 정체성

RefInvest는 사용자가 **설계한 전략과 실제 거래 행동을 재현 가능한 데이터로 검증**하도록 돕는
개인 투자 분석 서비스다.

```text
Strategy Validation                  Behavior Review
가설 → 전략 정의 → 백테스트          거래 기록 → 복원 → 행동 발견
          ↓                                      ↓
     예상 실행/성과                         실제 행동/결과
          └──────────── Execution Gap ────────────┘  (향후)
```

Trading Review는 거래 일지나 AI 투자 코치가 아니다. 실제 체결 기록으로부터 반복 행동과 함께 나타난
결과를 결정론적으로 계산하고, 모든 관찰을 근거 체결까지 추적할 수 있게 하는 거래 행동 분석 도구다.

사용자가 답을 얻어야 하는 질문은 다음과 같다.

> 나는 실제로 어떻게 거래했고, 어떤 행동이 반복됐으며, 그 행동과 어떤 결과가 함께 나타났는가?

### 제공하는 가치

- 외부 거래 기록을 재현 가능한 내부 기록으로 정규화한다.
- 상품 의미에 맞게 포지션 또는 분석 단위를 복원한다.
- 관찰 가능한 행동과 결과를 분리해 계산한다.
- Finding의 표본, 비교군, 제외 사유와 근거 체결을 공개한다.
- 같은 정의로 다시 계산한 기간별 변화를 추적한다.
- 향후 사용자가 직접 선택한 행동 기준의 준수 여부를 측정한다.

### 제공하지 않는 것

- 종목, 방향, 진입·청산 시점 추천
- 심리 상태나 투자자 성격 진단
- 행동과 손익의 인과관계 단정
- LLM이 원본 거래에서 직접 계산한 수치
- 주문 실행과 자동매매
- 검증되지 않은 실시간 행동 개입

`행동 교정`은 장기 목표지만 초기 제품 약속은 `발견 → 근거 확인 → 반복 측정`이다. 사용자가
`BehaviorFocus`를 선택하고 후속 기간의 준수 여부를 측정할 수 있을 때 교정 영역으로 확장한다.

### 제품 루프

```text
Import → Verify → Discover → Inspect → Focus → Track
```

- `Verify`: 체결 수입과 포지션 복원이 원장과 맞는지 확인한다.
- `Discover`: 반복 행동과 함께 나타난 결과를 찾는다.
- `Inspect`: Finding을 근거 ReviewUnit과 Execution까지 drill-down한다.
- `Focus`: 사용자가 바꾸고 싶은 행동을 직접 선택한다(향후).
- `Track`: 동일한 분석 정의로 다음 기간의 변화를 비교한다(향후).

---

## 2. 현재 MVP 경계

| 구분 | 포함 |
|---|---|
| Venue | Binance |
| Product | USDⓈ-M Linear Perpetual |
| Position mode | 사용자가 One-way를 선언하고 거래 기록도 One-way와 양립하는 경우 |
| Input | 공식 Trade History CSV와 Position History CSV를 한 묶음으로 수동 업로드 |
| 지원 dialect | `BINANCE_USDS_TRADE_HISTORY_V1`, `BINANCE_USDS_POSITION_HISTORY_V1` |
| 분석 대상 | 두 파일이 유일하게 조정되고 시작과 종료가 모두 관측된 `COMPLETE` position episode |
| Outcome | Trade History의 realized profit에서 동일 통화 trading fee를 차감 |
| Funding | 제외하고 결과에 명시 |
| 초기 분석 | 손실 후 재진입, 손실 후 초기 명목 규모 변화, 수익/손실 보유시간 차이 |
| 결과 근거 | ReviewUnit → allocation → canonical record → source row |

MVP에서 제외한다.

- Hedge Mode, COIN-M, Portfolio Margin, Spot, Options
- Binance API key 연결과 자동 동기화
- 열린 포지션 분석, 실시간 알림, 주문 실행
- 펀딩 포함 손익과 증거금/레버리지 기반 ROE
- 임의 column mapping, 다른 언어 header, 확인하지 않은 Binance CSV 변형
- AI Summary/Query
- Strategy 매핑과 Execution Gap

---

## 3. Bounded Context와 소유권

```text
Import → Trading Record → Reconstruction → Behavior Analytics → Review
```

### Core 장기 소유

- `TradingAccount`, `TradingBook`
- `TradingSourceArtifact` metadata와 retention 상태
- `TradingImportSession`과 역할이 지정된 복수 `TradingSourceArtifact`
- canonical `TradingRecord`
- immutable `LedgerRevision`
- `TradingAnalysisRun`, 장기 `TradingAnalysisResult`
- 사용자 접근 권한, 삭제 상태와 Review read model

### Compute 계산 책임

- provider adapter와 schema validation
- canonical normalization 제안 결과 생성
- 상품별 reconstruction
- `ReviewUnit`, `BehaviorObservation`, `BehaviorMetric`, `BehaviorFinding` 계산
- 제한된 기간의 durable runtime job 상태

Compute는 사용자 계정과 거래 원장의 장기 정본을 소유하지 않는다. Web은 Core만 호출한다.

Import와 Analysis는 UX에서 연속 실행될 수 있지만 도메인에서는 분리한다.

```text
Import:
TradingImportSession(Trade + Position artifacts) → Normalize/Reconcile
→ TradingRecord + ReconciliationManifest → LedgerRevision

Analysis:
Pinned LedgerRevision/Manifest → ReviewUnit → Metrics/Findings
```

### 3.1 도메인 불변식 적용

`docs/DOMAIN.md`의 `TR-I01`~`TR-I38`이 요약 정본이다. 구현과 harness는 다음 경계에서 이를 강제한다.

| 경계 | 적용 불변식 | 정상 증거 | 위반 시 결과 |
|---|---|---|---|
| Account/Book 접근 | TR-I01, TR-I02 | owner와 Book 특성 일치 | 요청 거절, 다른 사용자의 존재도 노출하지 않음 |
| Import acceptance | TR-I03, TR-I04 | 모든 의미 행 정규화 또는 명시적 비거래 행 집계 | TradingImportSession `REJECTED` |
| Ledger 생성 | TR-I05, TR-I06 | 고정 record 집합과 canonical content hash | 기존 Revision 수정 금지, 새 Revision 생성 |
| Reconstruction | TR-I07, TR-I08, TR-I09 | 수량·비용·PnL 보존과 상태별 ReviewUnit | quality failure 또는 비적격 상태로 보존 |
| Metric 계산 | TR-I10, TR-I11 | pinned input/version/config와 명시적 MetricStatus | 숫자 `0`으로 대체하지 않음 |
| Review 노출 | TR-I12, TR-I13 | 모집단·제외 사유·evidence와 기술적 문구 | `COMPLETED`/Finding 노출 불가 |
| 보관·삭제 | TR-I14~TR-I24 | owner scope, pinned policy, tombstone/generation, purge와 restore 검증 | 접근 차단, stale result 폐기, 실패 상태 공개 |
| Version/reprocessing | TR-I25~TR-I38 | exact compatibility, immutable 새 결과, lineage/hash/publication | 기존 결과 유지, 새 결과 publish 금지 또는 명시적 실패 |

각 불변식에는 최소 하나의 정상 fixture와 위반 fixture 또는 문구 계약 검증을 둔다. MVP 조합처럼
상품 확대 시 바뀌는 허용 목록은 불변식이 아니라 scope/configuration으로 관리한다.

---

## 4. 계정과 거래 장부

### TradingAccount

사용자가 관리하는 외부 거래 계정의 논리적 컨테이너다. CSV만으로 실제 Binance account identity를
검증할 수 없다면 검증된 연결로 표현하지 않는다.

```text
TradingAccount
├── id
├── ownerId
├── venue: BINANCE
├── displayName
├── externalAccountReference?
├── status: ACTIVE | ARCHIVED
└── createdAt
```

### TradingBook

포지션과 비용을 독립적으로 복원할 수 있는 기록 경계다. 상품/포지션 모드/정산 자산이 다른 기록을
한 Book에 섞지 않는다.

```text
TradingBook
├── id
├── tradingAccountId
├── productFamily
├── positionMode
├── settlementAsset
├── reviewTimezone
├── latestRevisionId?
└── createdAt
```

현재 허용 조합은 `BINANCE + LINEAR_PERPETUAL + ONE_WAY + USDT`뿐이다. 향후 같은 Account 아래
USDⓈ-M, COIN-M, Spot Book을 별도로 추가할 수 있다.

---

## 5. Instrument와 상품 확장 경계

Backtest의 `AssetSymbol`과 실제 거래 상품인 `Instrument`는 다른 개념이며 타입과 저장 모델을 공유하지
않는다.

```text
InstrumentDescriptor
├── instrumentId                  # 예: BINANCE:USDS_FUTURES:BTCUSDT
├── venue
├── venueSymbol
├── productFamily
├── baseAsset
├── quoteAsset
├── settlementAsset
├── quantityUnit
├── priceUnit
├── contractMultiplier?
└── productTerms
```

현재는 `LinearPerpetualTerms`만 구현한다. Spot, Inverse Futures, Options는 공통 모델에 nullable 필드를
추가하지 않고 각자의 Terms와 ReconstructionPolicy로 확장한다. Instrument descriptor는 해당
LedgerRevision에 필요한 계약 정보를 고정해, 외부 상품 metadata 변경이 과거 분석을 조용히 바꾸지
않게 한다.

---

## 6. Import와 canonical TradingRecord

### TradingImportSession과 source artifact

하나의 import는 서로 보완하는 두 파일을 함께 검증하는 원자적 session이다.

```text
TradingImportSession
├── id
├── ownerId
├── tradingBookId
├── declaredPositionMode: ONE_WAY
├── sourceTimezone
├── reviewTimezone
├── artifacts
│   ├── TRADE_HISTORY
│   └── POSITION_HISTORY
├── status: RECEIVED | VALIDATING | ACCEPTED | REJECTED
├── validationReport
├── reconciliationSummary
├── createdLedgerRevisionId?
├── dataPolicyVersion
├── retentionPolicy
├── maskingPolicyVersion
├── evidenceRetention
├── deletionPolicyVersion
├── rawExpiresAtByArtifact
├── createdAt
└── completedAt?

TradingSourceArtifact
├── id
├── importSessionId
├── role: TRADE_HISTORY | POSITION_HISTORY
├── dialect
├── sha256
├── schemaFingerprint
├── coverage?
│   ├── earliestObservedAt?
│   ├── latestObservedAt?
│   ├── rowCount
│   ├── sourceTimezone
│   ├── timestampPrecision: SECOND
│   └── coverageCompleteness: NOT_PROVEN
├── originalFilename?             # raw lifecycle 동안만 유지하고 raw와 함께 삭제
├── contentType
├── byteSize
├── storageReference?
├── encryptionKeyVersion?
├── rawExpiresAt
├── retentionStatus
├── uploadedAt
└── deletedAt?
```

두 artifact가 모두 있어야 validation을 시작한다. 구조·schema·상품·position mode 위반은 session 전체를
거절한다. 정상적으로 해석했지만 일부 row가 reconciliation 조건을 충족하지 못한 경우에는 quality report와
제외 사유를 가진 LedgerRevision을 만들 수 있다. 분석에는 `EXACT` 또는
`WITHIN_ROUNDING_TOLERANCE`로 유일하게 조정된 episode만 포함한다.

policy field와 raw retention/deletion, 장기 `SourceEvidenceSnapshot` 의미는
[`TRADING_DATA_LIFECYCLE.md`](TRADING_DATA_LIFECYCLE.md)를 따른다. 이 필드가 고정되지 않은 session은
`ACCEPTED`가 될 수 없다.

### Binance CSV dialect V1

확인한 익명 실제 표본의 두 artifact는 모두 UTF-8 BOM, comma delimiter, CRLF이며 quote character가 없었다.
이는 해당 표본에서 관찰한 물리 형식이고 Binance의 모든 과거·지역별 export가 항상 BOM/CRLF/no-quote라는
주장은 아니다. V1 parser의 수용 계약은 다음과 같다.

- UTF-8을 사용하고 optional 단일 leading BOM을 header parsing 전에 제거한다. BOM이 있는 Trade 첫 header를
  `\uFEFFUid`가 아니라 정확히 `Uid`로 판정해야 한다. invalid UTF-8이나 content 중간 BOM은
  `FILE_FORMAT_INVALID`다.
- delimiter는 comma다. CRLF를 반드시 처리하며 LF record terminator도 transport 차이로 수용한다. bare CR은
  수용 계약이 아니다.
- RFC 4180 double-quote escaping을 처리한다. 현재 no-quote 표본만을 근거로 quoting을 금지하거나 모든
  Binance export에 quote가 없다고 일반화하지 않는다.
- header spelling과 대소문자는 아래 목록과 정확히 일치해야 한다. required header 누락은
  `REQUIRED_COLUMN_MISSING`, duplicate required header 또는 이름/대소문자 변경은
  `UNSUPPORTED_SOURCE_SCHEMA`, CSV row width/quoting 오류는 `FILE_FORMAT_INVALID`다.
- 알 수 없는 추가 column은 warning과 `schemaFingerprint`에 남기고 required field 의미가 유지되면 허용한다.

`BINANCE_USDS_TRADE_HISTORY_V1` required fields:

```text
Uid,Time,Symbol,Side,Price,Quantity,Amount,Fee,Realized Profit,Buyer,Maker,Trade ID,Order ID
```

합성 예시:

```csv
900000001,2026-09-29 14:27:00,ABCUSDT,SELL,25.115,1,25.115,0.00502299USDT,0,false,true,700000001,800000001
```

`BINANCE_USDS_POSITION_HISTORY_V1` required fields:

```text
Symbol,Margin Mode,Position Side,Entry Price,Avg. Close Price,Max Open Interest,Closed Vol.,Closing PNL,Opened,Closed,Status
```

합성 예시:

```csv
XYZUSDT,Isolated,Short,0.86536,0.85298717,414,414,5.12235,2026-09-14 15:34:00,2026-09-14 16:19:00,Closed
```

- column 순서는 바뀔 수 있다.
- 다른 언어 header와 사용자 임의 mapping은 V1 범위 밖이다.
- `Uid`, `Trade ID`, `Order ID`는 숫자 연산 없이 문자열로 보존한다.
- Trade `Side`는 `BUY | SELL`, `Buyer`/`Maker`는 `true | false`만 허용한다.
- Position `Position Side`는 `Long | Short`, `Margin Mode`는 `Isolated | Cross`, `Status`는 `Closed`만
  V1 지원 값으로 받는다. 값은 대소문자를 임의 보정하지 않는다.
- 모든 decimal은 source 문자열의 scale을 보존하는 decimal 타입으로 읽는다.
- `Time`, `Opened`, `Closed` lexeme은 모두 정확히 `yyyy-MM-dd HH:mm:ss`이고 초 단위 precision을 가진다.

source `Symbol`은 Unicode를 포함할 수 있는 lossless opaque identifier다. trim, case conversion, Unicode
normalization 또는 ASCII-only regex를 적용하지 않는다. exact source symbol을 pinned
`InstrumentDescriptor`/registry에 조회해 지원 상품과 terms를 결정하며 문자열 모양만으로 상품을 추론하지
않는다. `Fee` asset suffix의 별도 ASCII grammar는 source symbol 계약에 적용하지 않는다.

### 시간과 계정 mode 증거

CSV timestamp에는 offset이 없으므로 사용자가 `sourceTimezone`을 필수로 지정한다. Adapter가 브라우저,
서버, filename 또는 파일 내용만으로 timezone을 추론하지 않는다. export filename에 timezone이 표시되어도
일반 계약의 근거로 사용하지 않는다. `reviewTimezone`은 일별 집계 기준이며 source 해석 timezone과 별도다.

Trade History `Time`과 Position History `Opened`/`Closed`는 모두 second-precision exact source local
timestamp다. session에 고정된 `sourceTimezone`을 적용한 뒤 UTC instant로 canonicalize한다. 존재하지 않거나
DST상 모호한 local timestamp는 offset을 추정하지 않고 실패시킨다. episode의 `openedAt`/`closedAt`은 Trade
execution instant이고 Position summary의 `openedAt`/`closedAt`은 exact reconciliation constraint다.

두 artifact의 관측 기간이 같다고 가정하지 않는다. Trade coverage는 `Time`의 min/max, Position coverage는
`Opened`의 min과 `Closed`의 max다. role별 `ArtifactCoverage`에 이 earliest/latest observed instant, row
count, source timezone, `SECOND` precision과 `NOT_PROVEN` completeness를 독립 보존한다.
min/max는 관측 경계일 뿐 그 사이 또는 바깥에서 거래소 export가 완전하다는 증거가 아니다.

```text
PositionModeEvidence
├── USER_DECLARED_ONE_WAY
├── OBSERVED_ONE_WAY_COMPATIBLE
├── HEDGE_OVERLAP_DETECTED
└── VENUE_CONFIRMED_ONE_WAY        # 향후 API 연결
```

MVP 수락에는 `USER_DECLARED_ONE_WAY`와 `OBSERVED_ONE_WAY_COMPATIBLE`가 모두 필요하다. Position History의
`Position Side`는 Long/Short 방향이며 계정 mode 증거가 아니다. 같은 symbol에서 상반 방향 포지션이
겹친 증거가 있으면 `HEDGE_OVERLAP_DETECTED`로 거절한다. Position History timestamp가 겹친다는 이유만으로
판정하지 않고 execution reconstruction이 동시 반대 방향 수량을 요구할 때만 적용한다.

### Identity, duplicate와 provenance

Trade row의 안정 식별자는 다음과 같다.

```text
accountFingerprint = HMAC(stableIdentityKey, canonicalTuple(venue, Uid))
sourceExecutionKey = canonicalTuple(accountFingerprint, exact Symbol, raw Trade ID)
```

`canonicalTuple`은 각 UTF-8 component의 길이를 포함하는 충돌 없는 encoding이다. opaque Symbol을 delimiter로
단순 연결하거나 Unicode normalization한 문자열을 identity input으로 사용하지 않는다.

`stableIdentityKey`는 일반 application secret과 분리해 key version을 기록한다. 회전 시 기존 fingerprint를
다시 연결하는 명시적 migration 없이는 key를 바꾸지 않는다. 원본 `Uid`는 log, public API, AI context에
노출하지 않는다. 같은 key와 같은 canonical content는 중복으로 합치되 모든 source provenance를 보존한다. 같은 key의 내용이 다르면 충돌로 session을 거절한다.

`Uid`는 Trade History에만 있고 Position History에는 account identifier가 없다. 따라서 fingerprint는 Trade
identity를 만들 뿐 두 artifact가 실제로 같은 account/sub-account에서 왔음을 증명하지 않는다. 다음 evidence를
서로 다른 강도로 보존한다.

- 사용자가 두 파일이 같은 계정이라고 선언한 사실
- 두 artifact가 같은 `TradingImportSession`에 업로드된 사실
- 독립 coverage, symbol과 episode reconciliation이 호환된다는 계산 evidence
- 향후 authenticated venue connection처럼 실제 account identity를 증명하는 evidence

현재 CSV pair에는 마지막 evidence가 없다. reconciliation 성공도 account identity proof로 표현하지 않으며,
잘못된 계정 파일 조합을 일반적으로 탐지할 수 있다고 단정하지 않는다. raw UID는 암호화된 canonical identity
처리에 필요할 수 있어도 retained evidence나 일반 log에 평문으로 남기지 않는다.

Position History에는 확인된 stable venue row ID가 없다. source record identity는
`artifactId + sourceRowNumber`이며 값 조합으로 deduplicate하지 않는다. 동일 execution 집합이 둘 이상의
Position row에 배정되면 duplicate 또는 ambiguity로 처리한다. 최종 ReviewUnit ID는 배정된 execution key와
고정된 reconstruction policy로 결정론적으로 생성한다.

### Canonical TradingRecord

```text
TradingRecord
├── id
├── tradingBookId
├── instrumentId?
├── occurredAt?                # Trade-derived record의 UTC Instant
├── sourceProvenance
└── recordType

ExecutionRecord
FeeRecord
VenueReportedPnlRecord
VenuePositionSummaryRecord
FundingPaymentRecord           # 향후
LiquidationRecord              # 향후
TransferRecord                 # 향후
PositionSnapshotRecord         # 향후
```

Trade row 하나는 `ExecutionRecord`, `FeeRecord`, `VenueReportedPnlRecord`를 만든다. Position row는
`VenuePositionSummaryRecord`를 만든다. summary는 exact UTC `openedAt`/`closedAt`을 가진 종료 포지션
증거이며 시점별 잔고를 나타내는 `PositionSnapshotRecord`가 아니다.

`Fee`의 `0.00502299USDT` 같은 값은 amount와 asset으로 분리한다. V1에서 양수 source fee는 `COST`, 음수는
`REBATE`, 0은 `ZERO`이며 `balanceEffect = -sourceAmount`로 보존한다. 서로 다른 자산 fee를 환산할 고정된
valuation이 없으면 해당 episode의 net PnL capability만 누락시키며 source import 자체를 거절하지 않는다.

`sourceProvenance`는 최소 artifact ID, source row, source record key를 보존한다. canonical record는 accepted
session 이후 수정하지 않으며 보정은 새 Revision을 만든다. CSV로는 manual/API/bot, liquidation/ADL, order
type 같은 실행 기원을 구분할 수 없으므로 `EXECUTION_ORIGIN=UNKNOWN`을 기록한다. 제품 문구는 계정에서
관측된 거래 행동만 설명하며 사용자의 의도를 단정하지 않는다.

---

## 7. LedgerRevision과 데이터 Capability

```text
LedgerRevision
├── id
├── tradingBookId
├── revisionNumber
├── includedImportSessionIds
├── recordCount
├── coverage
├── contentHash
├── instrumentDescriptors
├── sourceDialectVersions
├── adapterVersion
├── normalizerVersion
├── canonicalSchemaVersion
├── instrumentTermsVersions
├── reconstructionPolicyVersion
├── reconciliationVersion
├── reviewUnitSchemaVersion
├── evidenceSchemaVersion
├── implementationDigests
├── dataPolicyVersion
├── reconciliationManifestHash
├── capabilities
├── qualitySummary
└── createdAt
```

Revision은 생성 후 수정하지 않는다. accepted Import가 장부에 반영될 때 새 Revision을 만든다. Import
compute job은 canonical record와 함께 versioned `ReconciliationManifest`를 만들고 Core는 manifest hash와
사용한 source/adapter/normalization/schema/terms/reconstruction/reconciliation/ReviewUnit/evidence version과
implementation digest를 Revision에 고정한다. Analysis는 같은 source를 독립적으로 다시
매칭하지 않고 pinned manifest의 allocation과 status로 ReviewUnit을 구성한다. manifest를 다시 생성하는 검증을
수행한다면 hash가 다를 때 run을 실패시킨다.

Revision의 content hash는 record 저장 순서와 실행별 ID/생성 시각에 영향받지 않는 canonical ordering과
serialization으로 계산한다. 기존 Revision을 재처리하거나 보정할 때도 결과를 덮어쓰지 않는다.

지원 가능한 분석은 데이터 capability로 판정한다.

```text
CLOSED_OUTCOME
INITIAL_EXPOSURE
MAX_EXPOSURE
POSITION_INCREASES
FEES
FUNDING_PAYMENTS
MARKET_PRICE_PATH
VENUE_REPORTED_PNL
POSITION_MODE_USER_DECLARED
POSITION_MODE_OBSERVED_COMPATIBLE
VENUE_POSITION_SUMMARY
EXECUTION_ORIGIN_KNOWN
STABLE_EPISODE_TIME
```

MVP CSV에는 실행 기원 정보가 없으므로 `EXECUTION_ORIGIN_KNOWN` capability를 부여하지 않는다. Metric은
필요한 capability가 없을 때 `0`을 만들지 않는다. `STABLE_EPISODE_TIME`은 pinned manifest의 exact
execution allocation에서 non-null episode 경계와 올바른 시간 순서를 확인할 수 있고 Position History의
summary 시각으로 execution 경계를 보정하지 않았을 때만 episode scope에 부여한다(ADR-060).

```text
MetricStatus
├── AVAILABLE
├── INSUFFICIENT_SAMPLE
├── NOT_APPLICABLE
└── MISSING_CAPABILITY
```

---

## 8. 상품별 Reconstruction과 ReviewUnit

모든 상품을 하나의 복원 알고리즘으로 처리하지 않는다.

```text
ReconstructionPolicy
├── BINANCE_USDS_LINEAR_PERPETUAL_ONE_WAY_V1   # 현재
├── LINEAR_PERPETUAL_HEDGE_V1                  # 향후
├── INVERSE_PERPETUAL_ONE_WAY_V1               # 향후
├── SPOT_INVENTORY_V1                          # 향후
└── OPTIONS_STRATEGY_V1                        # 향후
```

지원 정책이 없으면 추정하지 않고 실패한다.

### FuturesPositionEpisode와 PositionAllocation

아래는 도메인 형태의 요약이다. signed state transition, partial/reversal allocation, Decimal/zero,
fee/PnL 보존, reconciliation candidate graph와 tolerance의 규범 알고리즘은
[`TRADING_RECONSTRUCTION.md`](TRADING_RECONSTRUCTION.md)를 따른다.

One-way 선물에서는 exact source symbol을 pinned instrument에 매핑한 뒤 instrument별 Trade History를 canonical
`occurredAt`, 그다음 arbitrary-precision numeric `Trade ID` 순으로 정렬한다. canonical serialization은
instrument partition key를 앞에 두지만 서로 다른 instrument의 동시 execution 사이에 도메인 순서를 만들지
않는다. source row order와 `Order ID`는 tie-breaker가 아니다. 같은 `Order ID`의 여러 row는 각각의 fill이며
주문이나 execution 하나로 합치지 않는다. 순서 민감 metric도 instrument 안의 확정 순서만 사용한다.
signed quantity가 0에서 non-zero가 될 때 episode가 시작되고 다시 0이 될 때 종료한다.

```text
FuturesPositionEpisode
├── id
├── analysisRunId
├── instrumentId
├── direction: LONG | SHORT
├── openedAt?                    # LEFT_CENSORED shell에서는 null
├── closedAt?
├── initialQuantity?
├── maxQuantity?
├── initialNotional
├── maxNotional
├── averageEntryPrice
├── averageExitPrice
├── outcome
├── reconstructionStatus
├── reconciliationStatus
└── allocations

PositionAllocation
├── tradingRecordId
├── reviewUnitId
├── action: OPEN | INCREASE | REDUCE | CLOSE | REVERSAL_CLOSE | REVERSAL_OPEN
├── allocatedQuantity
├── allocatedFee
└── allocatedReportedPnl
```

하나의 execution이 기존 수량을 초과해 반전시키면 closing/opening allocation으로 나눈다. fee는 배분 수량
비율로 나누며 reversal은 일반 rapid re-entry와 분리한다. 각 execution의 수량과 연결된 cash flow의 통화별
합계는 allocation 전후에 보존되어야 한다.

```text
ReconstructionStatus
├── COMPLETE
├── LEFT_CENSORED
├── RIGHT_CENSORED
├── INCONSISTENT
└── UNSUPPORTED

ReconciliationStatus
├── EXACT
├── WITHIN_ROUNDING_TOLERANCE
├── AMBIGUOUS
├── INCOMPLETE_TRADES
├── SUMMARY_MISMATCH
└── UNSUPPORTED_OVERLAP
```

Position summary와 reconstructed episode는 단순 시간 join이 아니라 instrument/exact source symbol,
direction, exact opened/closed instant,
최대 수량, 청산 수량, realized PnL 제약으로 유일하게 매칭한다. 복수 후보가 남으면 가장 가까운 후보를
선택하지 않고 `AMBIGUOUS`로 제외한다. direction, max quantity, closed volume, realized PnL을 주 검증값으로
사용한다. 부분 청산과 추가 진입이 섞이면 평균 진입·청산가는 보조 증거로만 사용한다.

수량·가격·PnL 허용 오차는 고정 magic number가 아니라 source decimal scale과 LedgerRevision에 고정된
instrument terms에서 도출한다. Position History `Status`는 V1에서 정확히 `Closed`만 reconciliation 대상이다.
다른 값은 exclusion reason `UNSUPPORTED_POSITION_STATUS`로 보존하고 분석에서 제외하며 의미를
추측하지 않는다.

MVP 행동 분석에는 `COMPLETE`이면서 reconciliation이 `EXACT` 또는 `WITHIN_ROUNDING_TOLERANCE`인 episode만
포함한다. 나머지 ReviewUnit도 버리지 않고 상태와 제외 사유를 quality report에 보존한다.

artifact 경계가 다른 경우에도 기존 taxonomy를 사용한다. Trade 시작보다 먼저 열린 Position summary는
`LEFT_CENSORED + INCOMPLETE_TRADES`로 보존하고 Import 전체를 실패시키지 않는다. Position artifact의 latest
observed timestamp 뒤에 닫힌 complete Trade episode는 `COMPLETE + SUMMARY_MISMATCH`와
`POSITION_SUMMARY_MISSING`이며 정상 reconciliation 또는 0건으로 세지 않는다. 공통 관측 구간의 complete
episode는 unique one-to-one match와 numeric constraints를 통과할 때만 eligible이다. 실제 summary가
유일하게 조정되지 않으면 원인에 따라 `AMBIGUOUS`, `INCOMPLETE_TRADES`, `SUMMARY_MISMATCH` 또는
`UNSUPPORTED_OVERLAP`이며 reconciliation-required Metric에서 제외한다. quality report는 상태·포함/제외
건수·이유와 각 artifact coverage를 함께 노출할 수 있어야 한다.

### ReviewUnit

`PositionEpisode`는 모든 상품에 공통되지 않는다. Behavior Analytics는 상품별 결과를 공통 분석 경계인
`ReviewUnit`으로 받는다.

```text
ReviewUnit
├── id
├── type                         # FUTURES_POSITION_EPISODE 등
├── instrumentIds
├── openedAt
├── closedAt?
├── outcome
├── exposureSummary
├── dataQuality
└── sourceAllocations
```

향후 Spot은 `SPOT_INVENTORY_CYCLE`, Options는 `OPTIONS_STRATEGY_EPISODE`처럼 별도 구현한다.

---

## 9. Outcome 의미

```text
Outcome
├── grossTradingPnl: Money?       # Trade History Realized Profit 합계
├── venuePositionPnl: Money?      # Position History Closing PNL
├── tradingFees: Money?           # Trade History Fee 합계
├── fundingCost: Money?
├── otherCosts: Money?
├── netTradingPnl: Money?
├── allInPnl: Money?
├── normalizedMeasures
└── completeness
```

MVP 정의는 다음과 같다.

```text
grossTradingPnl = sum(Trade History.Realized Profit)
venuePositionPnl = Position History.Closing PNL
tradingFees = sum(Trade History.Fee sourceAmount)
netTradingPnl = grossTradingPnl - tradingFees    # 같은 settlement currency일 때
fundingTreatment = EXCLUDED
```

Reconciliation은 `grossTradingPnl`과 `venuePositionPnl`을 비교한다. Fee는 결과 계산에는 포함하지만 Position
History Closing PNL과의 gross PnL 조정값에는 섞지 않는다. CSV만으로 실제 증거금과 레버리지를 확정할 수
없으므로 ROE를 만들지 않는다. 필요한 경우 이름에 분모가 포함된 `PNL_ON_INITIAL_NOTIONAL`,
`PNL_ON_MAX_NOTIONAL`만 사용한다. 서로 다른 정산 자산을 환산할 때는 immutable `ValuationSnapshot`을
고정하기 전까지 합산하지 않는다.

초기 Behavior Metric의 공통 classification은 `netTradingPnl` exact Decimal의 부호에 따라
`LOSS | BREAKEVEN | WIN`, net outcome capability가 없으면 `UNAVAILABLE`이다. fee rebate는 signed source
amount로 위 식에 포함하고 gross PnL로 fallback하지 않는다. 상세 조건은
[`TRADING_ANALYTICS.md` §4](TRADING_ANALYTICS.md#4-공통-outcome-classification)를 따른다.

---

## 10. Behavior Analytics

### BehaviorObservation

심리나 의도를 추론하지 않고 ReviewUnit 사이에서 관찰한 사실을 표현한다.

```text
BehaviorObservation
├── id
├── analysisRunId
├── definitionId
├── definitionVersion
├── subjectReviewUnitIds
├── observedAt
├── attributes
└── evidenceRecordIds
```

초기 definition은 다음과 같다.

- `ENTRY_WITHIN_WINDOW_AFTER_LOSS`
- `INITIAL_EXPOSURE_CHANGE_AFTER_LOSS`
- `WIN_LOSS_HOLDING_DURATION_DIFFERENCE`

같은 execution에서 발생한 long/short 반전은 일반 재진입에 섞지 않고 `IMMEDIATE_REVERSAL` 차원으로
분리한다. same instrument, direction change, elapsed time도 attribute로 보존한다.

세 definition의 population, window, comparison group, minimum sample, formula, capability와 28개 합성
boundary expected result는 [`TRADING_ANALYTICS.md`](TRADING_ANALYTICS.md)가 정본이다. 요약하면 30분
re-entry는 `(0, PT30M]`이고 동일 earliest instant entry를 순서 없는 set으로 연결하며, exposure primary는
next-entry/loss initial-notional ratio의 중앙값, holding primary는 `loss median - win median`이다. exposure
baseline은 event 전 same-instrument/same-direction history만 사용해 미래 episode를 포함하지 않는다.

### BehaviorMetric

```text
BehaviorMetric
├── definitionId/version
├── status
├── period
├── populationSize
├── eligibleSampleSize
├── excludedSampleSize
├── excludedReasonCounts
├── subject/comparisonReviewUnitIds
├── scalarInputs
├── capabilitySnapshot
├── value
├── dimensions
├── comparisonDefinition
└── evidenceReference
```

### BehaviorFinding

모든 Metric을 첫 화면에 노출하지 않는다. Finding은 표본, effect size, outlier와 비교군 정책을 통과해
검토 가치가 있다고 선택된 결과다.

```text
BehaviorFinding
├── id
├── definitionId/version
├── observation
├── evidenceMetricIds
├── supportingReviewUnitIds
├── comparisonReviewUnitIds
├── sampleAssessment
└── rankReason
```

MVP의 `sampleAssessment`는 `INSUFFICIENT | LIMITED | DESCRIPTIVE`를 사용한다. 표본 수만으로
`HIGH CONFIDENCE`를 표시하거나 인과관계를 주장하지 않는다.

---

## 11. TradingAnalysisRun과 재현성

```text
TradingAnalysisRun
├── id
├── ownerId
├── tradingBookId
├── ledgerRevisionId
├── status: PENDING | RUNNING | COMPLETED | FAILED
├── sourceVersionSet
├── metricDefinitionVersions
├── findingRuleSetVersion
├── analysisConfigVersion
├── evidenceSchemaVersion
├── implementationDigest
├── analysisConfig
├── analysisConfigHash
├── result?
├── failure?
└── timestamps
```

변경된 Adapter/schema/reconstruction/Metric/Finding/config로 과거 입력을 다시 계산하는 작업은 기존 ImportSession이나
AnalysisRun을 다시 열지 않는다. Core 소유 `TradingReprocessingRun`이 source/target VersionSet, input
availability, idempotency와 lineage를 고정하고 영향 범위에 따라 새 `LedgerRevision` 또는
`TradingAnalysisRun`을 만든다. version taxonomy, availability, compatibility, publication과 failure는
[`TRADING_VERSIONING.md`](TRADING_VERSIONING.md)를 따른다.

```text
AnalysisConfig
├── reviewTimezone
├── analysisPeriod: [startInclusive, endExclusive)
├── reentryWindow: PT30M
├── outcomeBasis: NET_TRADING_PNL
├── fundingTreatment: EXCLUDED
├── eligibleReviewUnitPolicy: COMPLETE_AND_RECONCILED_ONLY
├── reversalTreatment: SEPARATE
├── baselinePolicy: PRIOR_PERIOD_SAME_INSTRUMENT_DIRECTION
├── metricMinimums
└── findingMinimums
```

정확한 config schema, V1 default와 status 우선순위는 `TRADING_ANALYTICS.md`를 따른다. minimum 변경은 새
AnalysisRun을 요구하고 계산 의미가 바뀌면 Metric Definition Version을 올린다.

결정론 계약은 다음과 같다.

```text
같은 LedgerRevision
+ 같은 ReconstructionPolicyVersion
+ 같은 ReconciliationManifestHash
+ 같은 Metric/Finding versions
+ 같은 AnalysisConfig
= 같은 TradingAnalysisResult
```

`COMPLETED`는 reconstruction quality report와 result가 함께 있을 때만 가능하다. `FAILED`에는 구조화된
failure가 필요하다. 결과에는 Finding뿐 아니라 전체 eligible/excluded counts와 제외 사유, 모든 Metric의
값 또는 계산 불가 상태, evidence 참조와 canonical result hash를 보존한다. Finding이 0개인 완료 결과는
허용한다. 재시도는 실패한 Run을 되돌리지 않고 같은 pinned input을 참조하는 새 Run을 만든다.

---

## 12. Review와 향후 행동 변화

MVP의 `TradingReview`는 Aggregate가 아니라 AnalysisResult, Finding, 근거 ReviewUnit/TradingRecord를
조합한 read model이다. 사용자 메모나 확인 상태처럼 독립 생명주기가 생길 때만 Aggregate로 승격한다.

기간 변화는 같은 metric definition/version, outcome basis, timezone과 capability를 사용해 과거 기간도
다시 계산한 경우에만 비교한다. 버전이 다른 월별 값을 그대로 이어 붙이지 않는다.
정확한 `TrendComparisonKey`와 `NOT_COMPARABLE_VERSION` 판정은
[`TRADING_VERSIONING.md` §13](TRADING_VERSIONING.md#13-trend-comparability)을 따른다.

향후 사용자가 바꾸고 싶은 행동을 선택하면 별도 Aggregate를 도입한다.

```text
BehaviorFocus
├── id
├── tradingBookId
├── ownerId
├── observationType
├── userDefinedRule
├── effectivePeriod
└── status: ACTIVE | COMPLETED | ARCHIVED
```

이는 투자 지시가 아니라 사용자가 정한 기준의 사후 준수 측정이다.

---

## 13. 확장 원칙

확장성을 위해 지금 만드는 것은 미래 상품 구현이 아니라 다음 seam이다.

1. `TradingAccount`와 `TradingBook` 분리
2. Backtest Asset과 별도의 `InstrumentDescriptor`
3. record type별 canonical `TradingRecord`
4. 상품별 `ReconstructionPolicy`
5. 분석 공통 경계 `ReviewUnit`
6. Metric별 required capability
7. immutable `LedgerRevision`
8. normalization/reconstruction/metric/finding 버전 분리
9. 모든 결과에서 source row까지 이어지는 provenance

새 상품을 추가할 때 기존 선물 정책에 조건문을 누적하지 않는다. 새 Terms, Adapter,
ReconstructionPolicy와 ReviewUnit을 추가하고, 각 Metric의 applicability를 명시한다.

---

## 14. 실제 익명 표본 검증과 남은 경계

### 14.1 확인된 관찰

민감값을 출력하지 않는 read-only 검사로 실제 익명 Binance USDⓈ-M Futures export pair를 검증했다. 이는
제품 불변식이나 모든 Binance export에 대한 보장이 아니라 ADR-058/059 입력 계약을 교정한 실증 근거다.

- Trade 5,196행과 Position 440행 모두 UTF-8 BOM, comma, CRLF였고 quote character는 없었다. timestamp는
  두 artifact 모두 second precision `yyyy-MM-dd HH:mm:ss`, Trade boolean은 lowercase `true|false`였다.
- Trade ID 중복은 없었고 한 Order ID에 여러 fill이 존재했으며 관찰 최대치는 59였다. Trade row는 전역
  시간순이 아니었고 Unicode source symbol이 존재했다.
- Position 표본은 모두 `Isolated`/`Closed`였고 Long과 Short가 모두 있었다. 두 artifact의 관측 기간은
  서로 달랐다.
- signed quantity state machine으로 471개 closed episode를 복원했다. Position 440개 중 공통 관측 구간의
  425개가 exact opened/closed timestamp로 유일하게 연결됐고 ambiguous boundary match는 0개였다.
- unique 425개는 max open interest, closed volume, entry/average close price와 Closing PNL constraint를 모두
  충족했다. 가격은 ADR-059의 source-scale derived tolerance 안이었고 수량과 Closing PNL은 exact equality였다.
- 나머지 Position 15개는 Trade earliest observed timestamp 전에 열려 `LEFT_CENSORED`였고, Position latest
  observed timestamp 뒤에는 Trade에서 46개 complete episode가 추가 복원돼 summary가 없었다.
- matched 425개의 Position `Closing PNL`은 fee가 존재해도 closing fill의 Trade `Realized Profit` 합계와
  exact equality였다. 따라서 Closing PNL은 fee 차감 후 net PnL이 아니며 fee는 outcome 계산에서 별도로
  차감한다.

이 검증으로 raw encoding/BOM/delimiter/line ending, timestamp precision, boolean source lexeme,
second-precision reconciliation 가능성과 기존 분 버킷 가정의 오류를 닫았다.

### 14.2 여전히 미확인

- `Cross` Margin 의미와 실제 reconciliation
- 서로 겹치는 두 export의 duplicate/conflict 판정
- 다른 fee asset과 rebate의 실제 export/reconciliation
- Hedge Mode
- 서로 다른 account/sub-account 파일을 잘못 조합한 경우의 일반 탐지 가능성
- Binance export version별 physical dialect 변화, BOM/line ending/quoting 변형

확인되지 않은 dialect나 계정 일치 evidence는 추측해 확대하지 않는다. 초기 세 Metric의 population은
ADR-060과 `TRADING_ANALYTICS.md`, 원본 보관·삭제는 ADR-061과 `TRADING_DATA_LIFECYCLE.md`, API/persistence는
ADR-063과 각 정본을 따른다.

### 14.3 Integration 후속 합성 fixture

이 Context 변경에는 실제 원본이나 합성 fixture를 포함하지 않는다. 후속 integration 작업은 민감값과 실제
identifier를 사용하지 않는 합성 fixture로 다음을 함께 검증해야 한다.

- UTF-8 BOM, comma + CRLF와 첫 header BOM 제거
- Trade/Position second-precision timestamp와 pinned source timezone canonicalization
- source order가 섞인 Trade와 deterministic instrument/time/numeric Trade ID ordering
- Unicode symbol의 lossless 보존과 pinned descriptor lookup
- 동일 Order ID의 다중 fill을 별도 execution으로 보존
- Trade 시작 전 Position의 `LEFT_CENSORED + INCOMPLETE_TRADES`
- Position coverage 뒤 complete episode의 `SUMMARY_MISMATCH + POSITION_SUMMARY_MISSING`
- 공통 구간 unique exact reconciliation 및 non-unique exact-timestamp ambiguity
- Position Closing PNL이 fee를 제외한 closing fill Realized Profit 합계라는 검증

fixture는 `TRADING_RECONSTRUCTION.md`의 expected result와
[`TRADING_REVIEW_ACCEPTANCE.md`](TRADING_REVIEW_ACCEPTANCE.md)의 stable Scenario ID를 연결해야 한다.
