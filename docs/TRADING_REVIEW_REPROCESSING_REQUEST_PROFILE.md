# Trading Review 재처리 요청 검증 정책

상태: **채택**, 2026-11-16. 사용자가 권고안의 재검토 후 진행을 승인했고, 기존 identity/receipt·hash·예산 검토와 대조하여
[ADR-082](DECISIONS.md#adr-082--reprocessing-request-validation-profile)를 확정했다. 아래 원칙·표기·수치·압축/오류 구분은 신규 재처리 request profile의 정책이다.
**정책 채택과 현재 API 적용은 별개다.** 정확 wire 계약/consumer·실제 수용 조건을 충족하기 전 기존 API에 새 제한/status를 먼저 적용하지 않는다.
[ADR-080 정책](TRADING_REVIEW_REPROCESSING_POLICY.md)·[admission 상세](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)·
[ADR-081 수명](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION.md)은 유지한다. 이 결정은 ADR-079 전체나 다른 업무 후보의 채택이 아니다.

## 적용 범위와 버전 경계

- 신규 **재처리 전용 versioned request profile**에 적용한다. public 진입과 Core/Gateway→Go 중계는 같은 요청 의미/제한을 보존한다.
- shared Analysis/Compute schema·기존 profile·legacy·저장된 원 descriptor/receipt를 일괄 축소하거나 재작성하지 않는다.
- [wire 상세 계약](TRADING_REVIEW_REPROCESSING_WIRE_CONTRACT.md)에서 method+route→`trading-reprocessing-admission-v1` 매핑·request-local schema·오류 registry·Core 중계를 설계 확정했다. 현재 V2/internal V1 후보는 runtime 비활성이고 실제 consumer/배포 inventory·수용 전 활성화하지 않는다.
- request profile은 저장 codec·crypto epoch·서비스 인증/위임 profile과 다르다. profile/Book/중계 경로를 owner+operation+key namespace의 새 분할로 쓰지 않는다.
- 기존 ID·source pin·logical key·Config hash·Compute inputIdentity/fingerprint·golden vector는 보존한다. old profile을 새 규칙으로 decode/replay하거나 같은 instant만으로 execution을 자동 수렴시키지 않는다.

## 구조·무손실 입력·presence

| 대상 | 확정 규칙 |
|---|---|
| request·Target·Normalizer/Reconstruction/Analytics VersionSet | 해당 version의 명시 property만 허용하는 closed object |
| AnalysisConfig·AnalysisPeriod·MetricMinimums·FindingMinimums | 각각 closed object. 필요한 완전 Config를 받고 자동 default를 주입하지 않음 |
| sourceDialectVersionsByArtifactRole | 현재 계약의 TRADE_HISTORY·POSITION_HISTORY key만 허용 |
| metricDefinitionVersionsByMetricId | 현재 계약의 필수 세 Metric ID만 허용. 확장은 명시 profile 계약 변경 |
| instrumentTermsVersionByInstrument | 동적 instrument key를 허용하는 명시 map. key/value 형식·개수·길이·전체 예산을 함께 검증 |

중복 JSON member name은 같은 object 안에서 escape를 해석한 이름으로 검출한다. 조용한 last-value 선택을 허용하지 않는다.
잘못된 UTF-8/문자열·허용되지 않은 null/추가 필드·문자열 숫자/boolean coercion·정수 정밀도 손실을 거절한다.
trim/case-fold/Unicode 정규화·미사용 Target 삭제·원 값을 고치는 default/반올림으로 접수하지 않는다. JSON은 정확히 하나의 document여야 한다.

presence는 다음 기존 admission 의미를 유지하며, 모든 생략/null을 일괄 병합하지 않는다.

- sourceAnalysisRunId의 생략/null은 기존 계약의 None이다.
- publishAsLatest의 생략/true는 effective true, false는 구별하고 null은 거절한다.
- retryOfReprocessingRunId는 생략하거나 명시 non-null ID다. null은 거절한다.
- Analysis 산출물에는 완전 analysisConfig가 필요하고 Ledger-only에는 허용하지 않는다.
- Target의 미사용 구성요소도 생략/null/value를 임의 삭제·동등화하지 않는다. typed intent/facts·execution 비교는 ADR-080과 상세 계약을 따른다.

## Config 시각의 canonical 입력

신규 profile의 AnalysisPeriod 시각은 **UTC·대문자 T/Z·소수초 0이면 생략/비영이면 정확 6자리**로 받는다.

```text
2025-01-01T00:00:00Z
2025-01-01T00:00:00.100000Z
2025-01-01T00:00:00.000001Z
```

offset·짧은 fraction·`.000000Z`·소문자 t/z는 신규 profile에서 형식 오류다. 서버에서 허용 표기로 자동 변환하지 않는다.
유효 Gregorian 시각/year 0001~9999·마이크로초 정밀도를 검증하고 leap second/초과 정밀도를 잘라내거나 반올림하지 않는다.
이것은 protocol 규칙이며 특정 library 기본 출력에 의존하지 않는다. start < end 등 업무 의미 검증은 기존 admission 오류 분류와 구분한다.
유효 Config를 DTO→hash→Compute inputIdentity/fingerprint까지 같은 의미/표기로 전달해야 한다. source/result의 모든 timestamp에 이 입력 규칙을 일괄 적용하지 않는다.
기존 Config/legacy의 다른 표기를 같은 instant라는 이유로 rewrite하지 않으며, 새 표기의 Config가 기존 문자열 hash와 같다고 추정하지 않는다.

## 요청 예산과 정확 단위

아래 값은 **신규 profile의 초기 계약 상한**으로 확정했다. 성능 최적값·운영 실측 최대·현재 runtime 설정의 증거는 아니다.

| 대상 | 확정 상한 | 추가 조건 |
|---|---:|---|
| JSON body | 1,048,576 bytes (1 MiB) | HTTP transfer framing을 제외한 수신 JSON octets. 공백/BOM/escape도 크기에 포함. BOM 등 허용 여부는 JSON 계약대로 별도 검증 |
| JSON container depth | 16 | root object=1, object/array 진입마다 +1, scalar는 추가하지 않음. 빈 container도 포함 |
| resource ID | 256 UTF-8 bytes | 각 ID의 기존 grammar/typed 규칙도 유지. legacy opaque ID를 숫자로 강제 변환하지 않음 |
| version ID·implementationDigest·동적 instrument key | 각각 256 UTF-8 bytes | VersionIdentifier의 기존 maxLength 200자와 원 형식도 동시 적용 |
| reviewTimezone | 128 UTF-8 bytes | 지원 IANA/버전의 업무 적격성 검증과 구분 |
| instrumentTermsVersionByInstrument | 4,096 entries | 모든 key/value·전체 body 제한도 함께 적용 |
| MetricMinimums/FindingMinimums integer | 기존 양수·JSON/hash 안전 정수 범위 | 저장 타입만을 이유로 별도 int32 제품 상한을 도입하지 않음. 무제한 integer 허용이 아님 |

필드 byte 수는 JSON escape를 해석한 유효 문자열을 UTF-8로 표현한 길이다. wire에 `\u` escape를 쓰면 body byte는 별도로 커질 수 있다.
기존 transport key의 ASCII 1~128 및 SHA256의 정확 64hex 규칙은 유지한다. 일반 256byte 제한으로 이 규칙을 대체하지 않는다.
각 상한은 **교집합**이다. 4,096개에 모든 최대 길이를 채운 payload를 반드시 보낼 수 있다는 보장이 아니다. 값/terms/Target을 잘라 크기를 맞춰 접수하지 않는다.
Content-Length만 신뢰하지 않고 수신/파싱 중 예산을 강제한다. 전체를 무제한 heap에 적재한 뒤 검사하지 않는다.
한도는 profile 계약이며 배포별 임의 환경 설정으로 입력/replay 의미를 바꾸지 않는다. 향후 변경도 명시 계약/consumer 검토를 따른다.

## HTTP encoding·오류·receipt

신규 profile의 초기 요청은 **Content-Encoding 생략 또는 identity만 허용**한다. gzip/br 등 압축·encoding chain은 해제해 접수하지 않고 415로 거절한다.
HTTP transfer framing과 Content-Encoding을 혼동하지 않는다. 향후 압축 지원은 수신/해제 후 예산·해제 중 자원 제한을 포함한 별도 계약 변경이다.

| 상황 | 신규 profile 처리 | admission 효과 |
|---|---|---|
| 형식/타입·추가 필드·중복·금지 null·시각 표기·필드/깊이/개수 위반 | 400 | 이 거절로 새 Run/admission receipt를 만들지 않음 |
| 전체 JSON body 예산 초과 | 413 | 같은 원칙. body를 truncate/분할 접수하지 않음 |
| 미지원 Content-Encoding | 415 | 같은 원칙. 지원 압축으로 추정/자동 fallback하지 않음 |
| 구조상 유효한 요청의 업무 접수 실패 | 기존 operation의 business 오류/RecordedFailure/No-Run 계약 | 정책을 일괄 422로 재분류하지 않음. 원 410/422·Run/No-Run 의미 유지 |

기존 service/Member 인증·현재 authority/Guard·비노출 오류 우선권을 우회하지 않는다. 오류 body에 과거 key/scope/receipt 존재를 노출하지 않는다.
상한 감지 시 admission 전에 거절하고, 413을 확인하려고 이미 잘못된 본문을 무제한 drain하지 않는다.
복합 오류의 단계/조기 중단·media/encoding 우선순위와 현재 권위/receipt 순서는 [wire 계약](TRADING_REVIEW_REPROCESSING_WIRE_CONTRACT.md)으로 고정했다. 공통 transport 보호와 실제 runtime 수용은 구분한다.
기존 receipt/key가 있으면 그대로 보존한다. 이번 400/413/415나 네트워크 실패를 이전 시도의 rollback/미접수·key 해제 증거로 해석하지 않는다.
현재 승인된 replay는 원 profile/decision과 현재 authority/Guard를 사용한다. 새 입력 제한으로 원 receipt 종류/결속을 바꾸지 않는다.

## 실제 적용 전 필수 조건

| 경계 | 구현/활성화 전에 닫을 조건 |
|---|---|
| wire/API 계약 | [wire 상세](TRADING_REVIEW_REPROCESSING_WIRE_CONTRACT.md)의 profile/closed Request*·code·cache/trace·오류 순서·원문 중계 설계는 확정. DRAFT YAML/정적 경계를 넘어 실제 route/consumer·legacy 전환/수용 증거 필요 |
| caller/legacy | 실제 ID/version/terms·extension·Config 표기 inventory와 caller 생성 body 확인. 필요한 정상 요청이 제한을 넘으면 활성화를 중단하고 명시 계약 변경으로 해결 |
| consumer | Core/Gateway relay가 요청 의미·원 key·status/code/trace를 유지하고 Web이 새 오류를 해석. 압축과 mixed profile 전환/중단 경로 검증 |
| hash/Compute | producer/DTO/hash·원 Config/inputIdentity/fingerprint 왕복과 canonical 입력 반례. 기존 hash/vector를 재작성하지 않음 |
| bounded ingress | 실제 수신/파싱의 byte/depth/entry·UTF-8/중복/정수/정밀도·취소/메모리·HTTP 경계와 same-key/원 receipt/삭제/UNKNOWN 수용 |

최초 정책 채택에서 schema/runtime은 변경하지 않았다. 후속 wire 설계에서 DRAFT YAML의 접수 전용 schema/오류와 정적 checker를 갱신했으나 shared/active API·runtime·SQL/migration·Worker/업무 구현·consumer snapshot 적용은 없다.
실제 업무 수용/부하/E2E·운영 단일 writer/DB history·복구 인계 완료도 주장하지 않는다. 위 구현 전 조건은 정책 미채택을 의미하지 않으며 동일 원칙/수치의 재승인을 요구하지 않는다.

## 결정 근거와 과거 후보

[원 후보](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE_DRAFT.md)·[hash/DTO 리뷰](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE_REVIEW_DRAFT.md)·
[예산 조합/경계 관측](TRADING_REVIEW_REPROCESSING_REQUEST_BUDGET_REVIEW_DRAFT.md)은 검토 당시의 기록으로 보존한다. 과거 미채택/수치 보류·int32 후보는 이 정본을 덮어쓰지 않는다.
동일 instant의 시각 문자열 변경은 기존 hash를 바꿨고, DTO는 zero/짧은 소수초 표기를 바꿨다. 따라서 자동 동등화 대신 신규 입력 표기를 고정했다.
synthetic 8개 예제는 compact JSON 1,648~2,363bytes/depth4였다. 큰 terms 요청은 필드/개수 후보를 만족해도 1 MiB를 초과했다.
이는 구조/byte 관측이며 실제 등록 version/terms·HTTP/메모리·업무/운영 수용의 증거가 아니다. 단위와 교집합을 명시하고 실제 적용 전 검증을 분리한 근거다.
