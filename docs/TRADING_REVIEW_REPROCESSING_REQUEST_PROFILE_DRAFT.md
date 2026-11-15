# Reprocessing request 검증·동등성·크기 profile 후보

상태: **ADR-082 DRAFT·미채택**, 2026-11-15. ADR-080/081의 확정 namespace·원 receipt·No-Run·보존 기간은 유지한다.
이 문서는 남은 nested extension·시각 동등성·정확 상한에 대한 **권고 후보**다. 숫자/거절·정규화는 아직 현재 API/YAML·Go runtime에 적용하지 않는다.
[admission 상세 계약](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)·[수명 정본](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION.md)과 함께 읽는다.

## 기존 계약과 변경 위치

현재 AnalysisConfig 최상위는 closed지만 AnalysisPeriod·MetricMinimums·FindingMinimums·AnalyticsVersionSet 등은 nested 추가 필드가 전부 닫혀 있지 않다.
참고: [고정 shared schema](https://github.com/refinvest-dev/context/blob/0f089d1a317466e8df635e6a48eb5b0ff7d593d6/openapi/core-api.yaml)·
[고정 public DRAFT](https://github.com/refinvest-dev/context/blob/0f089d1a317466e8df635e6a48eb5b0ff7d593d6/openapi/drafts/trading-review-reprocessing-public.yaml).
Go가 현재 허용 nested 값을 버리거나 shared schema만을 근거로 새400/413을 도입할 수 없다.
채택한다면 **재처리 전용 versioned request profile**에 닫힌 nested schema·상한·오류를 함께 적용한다. shared Analysis/Compute schema를 일괄 축소하지 않는다.
정확 wire profile 식별/협상·이전 V2 DRAFT와의 구분은 YAML/consumer 변경 전에 확정한다. 내부 codec/crypto epoch나 service/위임 profile을 API 버전 대신 쓰지 않는다.

## 권고1 — 알려진 nested 구조는 closed

| 대상 | 후보 의미 |
|---|---|
| request/Target·Normalizer/Reconstruction/Analytics VersionSet | 해당 version의 명시 property만 허용. object 추가 key 거절 |
| AnalysisConfig·AnalysisPeriod·MetricMinimums·FindingMinimums | 각각 알려진 property만 허용. 완전 Config를 요구하고 자동 default 주입 금지 |
| sourceDialectVersionsByArtifactRole | 현재 TRADE_HISTORY·POSITION_HISTORY 두 key만 허용 |
| metricDefinitionVersionsByMetricId | 현재 필수 세 Metric ID만 허용. 새 Metric은 명시 profile 변경 |
| instrumentTermsVersionByInstrument | instrument ID가 동적인 map. 승인된 key/value 형식·아래 카디널리티 제한 적용 |

현재 허용 extension에 의미를 부여하지 않은 채 hash에 넣거나 실행에 전달하지 않는다. 새 profile에서 추가 key는 형식 오류400 후보이며 business RecordedFailure/No-Run으로 저장하지 않는다.
legacy/기존 profile의 원 descriptor는 lossless하게 보존한다. 새 closed profile을 과거 원장 decoding·replay에 소급 적용하거나 값을 삭제하지 않는다.
schema 구현 시 closed 하위 object를 해당 profile에서 정의한다. 불완전한 allOf/additionalProperties 조합으로 기존 필수 속성까지 거절하지 않도록 resolved-schema 사례를 검증한다.

## 수정 권고2 — 신규 profile의 canonical 시각 입력

**2026-11-16 검토 수정:** [채택 범위 검토](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE_REVIEW_DRAFT.md)의 실제 hash/DTO 관측이 종전의 동일 시점 표기 동등화 권고보다 우선한다.
현재 Config hash는 시각 문자열을 보존하고 DTO는 소수초 표기를 바꿀 수 있다. offset/소수초 표기를 바꾼 동일 instant를 곧바로 같은 Config hash/execution으로 판정하지 않는다.

신규 versioned profile의 수정 권고는 **UTC Z, 소수초0이면 생략·비영이면 정확6자리**라는 protocol 입력 표기를 고정하는 것이다.
`.000000Z`·짧은 fraction·offset 등을 서버가 자동 보정하지 않고, 이 profile을 채택할 때만 형식 거절400으로 설계한다. 기존 profile/legacy에는 소급 적용하지 않는다.
기존 fixture/hash/Compute identity는 재작성하지 않는다. 규칙은 library 기본 출력이 아니라 소유 계약으로 명시하고 producer/DTO/hash·inputIdentity/fingerprint 왕복을 검증한다.
시각 유효성/정밀도·start < end·업무 Config 오류와 원 receipt 종류도 분리한다. 정확 반례·표기/범위 조건은 검토 문서에 있다.
**아직 미채택이며 새 schema/runtime에 적용하지 않았다.**

## 권고3 — bounded request의 수치 후보

아래는 inventory/consumer·메모리 검증 전 **권고 수치**다. 현재 상한·실측 최대·성능 증거가 아니다. 값마다 새 profile에서의 적용과 오류를 함께 검토한다.

| 대상 | 후보 상한/단위 | 처리 후보 |
|---|---|---|
| JSON request body |1,048,576 bytes, 수신 JSON octet 기준 | 초과413, business receipt/Run 생성 없음 |
| JSON nesting | root object를 depth1로 셌을 때16 | 초과400 |
| resource ID 문자열 |256 UTF-8 bytes | 초과400. opaque legacy ID를 숫자로 강제 변환하지 않음 |
| version ID·implementationDigest·동적 instrument key | 각256 UTF-8 bytes | 초과400. exact 값 trim/case-fold 금지 |
| reviewTimezone |128 UTF-8 bytes | 초과400. 유효 IANA/버전 검증은 별도 |
| instrumentTermsVersionByInstrument | 최대4096 entries | 초과400. body 상한도 동시에 적용 |
| MetricMinimums/FindingMinimums의 integer |1~2,147,483,647 | 범위 밖400. 실제 정책상 허용 최소/Config 의미 검증 별도 |

기존 transport key는 ASCII1~128·SHA256 hash는 기존 정확64hex 규칙을 유지한다. 그 필드에 위 일반256 상한을 대신 적용하지 않는다.
문자열 숫자/boolean coercion·duplicate key·허용되지 않은 null/추가 field는 그대로 거절한다. 빈 동적 map의 업무 적격성은 실행 계약에 따르며 새 default/구성요소를 자동 생성하지 않는다.
같은 instrument map이4096 이하라도 전체 body가1MiB를 넘으면413이다. 문자열/entry 한도는 전체 byte 예산을 대체하지 않는다.
body는 정확 octet 수를 검증하고 Content-Length만 신뢰하지 않는다. 허용 content encoding과 압축을 지원할 경우 해제 후 예산/decoder depth는 ingress 계약에서 함께 확정한다.
본문·depth·카디널리티 검증도 bounded하게 수행하며 모든 입력을 heap에 적재한 뒤 크기만 재지 않는다.

## 오류·receipt·현재 authority

형식/상한 거절은 새 admission decision이 아니며 source pin 부족 No-Run422나 기록실패 Run으로 변환하지 않는다.
413을 채택한다면 public/internal response registry와 Core/Gateway relay·Web decoder에 같이 반영해야 한다. 현재 YAML에 없는 status/code를 서버만 먼저 반환하지 않는다.
기존 안전 오류 code의 재사용/새 code 필요 여부·header/trace·cache·413과400 우선순위는 실제 registry 설계에서 확정한다. 새로운 literal을 이 문서에서 활성화하지 않는다.
현재 service/Member/owner·scope 비노출/삭제 규칙과 원 receipt commit/UNKNOWN 처리는 유지한다. 같은 key라도 malformed/상한 위반을 보정해 다른 의미로 접수하지 않는다.
현재 승인된 정상 replay는 원 profile/원 decision을 사용한다. 새 profile로 old key를 다른 요청에 결속하거나 원202/기록410/422/No-Run 종류를 바꾸지 않는다.

## 채택/적용 전 필요한 확인

| 범위 | 필요한 자료/작업 |
|---|---|
| inventory/consumer | 실제 legacy 문자열/terms 카디널리티·caller 생성 body·기존 extension 유무·수치 초과 시 처리 |
| product/hash | 시각/Config 동등성·지원 Metric/terms key·full Target presence·canonical hash vectors와 일치 |
| versioned schema | profile 식별·재처리 local closed object·byte/depth(표준 schema만으로 표현 불가 포함)·400/413 public/internal registry |
| consumer | Core/Gateway relay status/code·Web 오류/원 key 재전송·혼합 profile 비교/전환 중단 |
| 실제 수용 | bounded decode/메모리·문자열 byte/중복/정수/시각 경계·같은 key/원 receipt/삭제/UNKNOWN |

기존 WR/WF/WUC/WFP/TR-ACC를 재사용한다. 새 public Scenario ID/Run 상태를 만들지 않는다.
현재는 후보 문서만 작성했다. OpenAPI/hash/vector/DB/runtime 변경·새 codec/SQL·실제 inventory/semantic/HTTP/부하/E2E 실행은 없다.
권고 수치/closed nested/시각 비교는 미채택이다. ADR-080/081의 확정 사항과 혼동하거나 후보 merge/CI를 정책 채택으로 해석하지 않는다.
