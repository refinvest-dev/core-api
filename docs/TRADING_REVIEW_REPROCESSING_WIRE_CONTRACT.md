# Trading Review 재처리 wire·오류·중계 상세 계약

상태: **ADR-082 후속 설계 확정·runtime 비활성**, 2026-11-16. 세 안의 논의 후 사용자의 후속 작업 요청에 따라 endpoint 고정 profile·오류 registry·Core 원문 보존 중계를 구체화했다.
[ADR-082](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE.md)·[ADR-080 admission](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)·[ADR-081 수명](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION.md)을 따른다.
public/internal YAML은 여전히 DRAFT이고 실제 route/issuer·consumer 배포·업무 수용은 완료하지 않았다. ADR-079 전체·권위/child/private 정책의 채택으로 확대하지 않는다.

## Endpoint와 profile 고정

| method/역할 | public | internal | 요청 profile/namespace |
|---|---|---|---|
| POST 접수 | `/trading-review/v2/trading-books/{tradingBookId}/reprocessing-runs` | `/internal/trading-review/v1/trading-books/{tradingBookId}/reprocessing-runs` | `trading-reprocessing-admission-v1` / `requestTradingReprocessing` |
| GET 목록 | 위 Book collection | 위 내부 Book collection | 기존 query 계약 / 없음 |
| GET Run | `/trading-review/v2/trading-reprocessing-runs/{reprocessingRunId}` | `/internal/trading-review/v1/trading-reprocessing-runs/{reprocessingRunId}` | 기존 read 계약 / 없음 |
| POST 취소 | 위 Run 경로의 `/cancellation` | 위 내부 Run 경로의 `/cancellation` | body 금지·기존 cancel 계약 / `cancelTradingReprocessing` |

method+route가 접수 profile을 선택하며, 클라이언트가 profile header/body 필드로 바꾸지 않는다. 프로필 선택용 별도 입력은 없다.
public V2/internal V1은 각 API 계약의 버전이고 profile V1은 검증/동등성 규칙 식별자다. 원 decision에는 서버가 선택한 profile을 기록한다.
저장 codec/crypto epoch·서비스 인증/위임 profile·Compute protocol과 구분하며 이 이름을 logical SHA root/child hash에 추가하지 않는다.
같은 key의 namespace를 Book/API 버전/profile로 분할하지 않는다. 과거 key를 새 execution에 재결속하거나 지원 불명 profile을 최신 규칙으로 추정하지 않는다.
유효한 원 profile/descriptor로 현재 접근 승인 뒤 replay/conflict를 판정한다. 호환 매핑을 새로 가정하지 않으며 원장 손상/unknown codec을 단순 conflict·No-Run으로 위장하지 않는다.

기존 V2 후보의 실제 consumer/배포 inventory 확인이 활성화 조건이다. 이미 소비된 계약을 축소하는 변경이면 별도 versioned endpoint/profile로 분리한다.
배포한 profile의 요청 의미를 바꾸는 변경은 새 API 버전/명시 매핑으로 처리한다. 과거 decoder/원 decision을 지원하는 책임과 신규 admission 활성화를 구분한다.
신규 closed/byte/시각 schema는 접수 전용이다. 과거 Run 조회의 Target/version/legacy ID에 신규 입력 상한을 소급하지 않는다.

## 입력과 복합 오류 순서

1. 공통 서버는 제한된 연결/헤더/본문 수신을 수행한다. 공통 보호 한도 때문에 인증 전에 응답할 수 있으며 resource 존재를 노출하지 않는다.
2. 애플리케이션은 기존 브라우저 인증/CSRF 또는 service+서명 위임·현재 Member gate를 확인한다. 위임/Member 정책을 이번 계약으로 새로 정의하지 않는다.
3. operation의 path/header/query 형식을 검증한다. `Idempotency-Key`는 정확히 하나이며 기존 ASCII1~128 규칙이다. 중복/잘못된 key를 결합·자동 생성하지 않는다.
4. 접수의 Content-Type/Content-Encoding을 검사한다. `application/json`과 UTF-8을 사용하며 생략 charset 또는 `charset=utf-8`을 허용한다. 미지원 media/charset은415, encoding은 생략/단일 identity만 허용하고 나머지는415다.
5. 알려진 Content-Length가 상한보다 크면413이다. 그 밖에는1MiB 제한 수신/strict 파싱 중 먼저 확정한 오류에서 중단한다. 감지한 초과는413, 형식/필드/깊이/개수 오류는400이다. 다른 오류를 찾으려고 무제한 drain하지 않는다.
6. 현재 Book/source 소유권·Guard를 확인한 뒤 원 key/receipt 비교·새 admission을 수행한다. foreign/삭제 비노출·원 outcome/UNKNOWN·동일 실제 Tx 규칙을 유지한다.

동일 단계에서 미지원 media와 encoding이 모두 있으면 media 오류를 먼저 반환한다. depth/count/field byte 위반은400이며 전체 body byte와 혼동하지 않는다.
JSON은 UTF-8의 정확한 단일 document다. BOM·잘못된 Unicode scalar·중복 member·coercion/정밀도 손실을400으로 거절한다. JSON object 공백/key 순서와 의미 비교는 구분한다.
원문 보존 중계는 동일 key 비교를 byte 동등성으로 바꾸지 않는다. Go는 ADR-080의 profile별 descriptor/presence 의미로 비교한다.
GET에 접수용 Content-Type/body profile을 강제하거나 취소에 `{}` body를 자동 생성하지 않는다. 기존 query/취소 형식 규칙을 유지한다.

## 오류 registry와 응답 범위

| HTTP/code | 발생 지점/조건 | retryable |
|---|---|---|
|400 `INVALID_REQUEST` | 기존 형식 오류. 접수 strict/깊이/필드/개수 및 공통 key 형식 포함 |false |
|413 `REQUEST_BODY_TOO_LARGE` | 접수 본문1MiB 초과. Core/Go 어느 지점에서도 같은 의미 |false |
|415 `UNSUPPORTED_CONTENT_ENCODING` | 접수 미지원 압축/encoding chain |false |
|415 `UNSUPPORTED_MEDIA_TYPE` | 접수 미지원 Content-Type/charset |false |
|409 `REPROCESSING_CONFLICT` | 기존 key/request/execution 충돌 의미 유지 |false |
|503 `ADMISSION_OUTCOME_UNCONFIRMED` | Go 접수 commit/effect가 불명확. 기존 원 key/fence 복구 |true |
|503 `DEPENDENCY_UNAVAILABLE` | 기존 일시 기술 장애 |true |
|504 `UPSTREAM_TIMEOUT` | Core/향후 Gateway가 Go의 필요한 응답을 제때 받지 못함 |true |
|502 `UPSTREAM_RESPONSE_INVALID` | Core/향후 Gateway가 Go status/content/schema를 계약에 맞게 해석/중계할 수 없음 |false |

502/504는 **public relay 생성 오류**이며 Go의 internal 응답 registry에 추가하지 않는다. public4개 operation에 적용하고413/415는 body가 있는 접수에만 적용한다.
기존401/403/404/410/422/500·접수 RecordedFailure/No-Run branch와 code는 유지한다. business 실패를 일괄422로 바꾸지 않는다.
내부 `SERVICE_OR_DELEGATION_REJECTED`는 Core가 browser 로그인 만료401로 복사하지 않는다. Core 연동 실패로 public500 `INTERNAL_ERROR`/retryable false를 반환하고 원인은 안전한 trace로 조사한다.
정상적으로 검증된 현재 사용자/소유권·Guard 거절403/404/410은 해당 operation 의미대로 중계한다. 모든 internal403을 서비스 인증 오류로 재분류하지 않는다.

일반 오류는 `code/message/traceId/retryable` 필수·선택 `details`의 closed object다. code는 machine 분기, message는 안전한 한국어 안내이고 문구 변경을 허용한다.
details는 `field/reason`만 갖는 closed object의 배열이며 선택 정보다. 클라이언트가 reason/message 문구에 의존하지 않는다.
허용된 정적 schema 필드 경로만 제공하고 dynamic instrument key/raw 값·SQL/driver·credential/token·locator·내부 path를 넣지 않는다.
Run ID는 commit/현재 접근이 확인된 RecordedFailure branch에서만 허용한다. 일반400/413/415/502/503/504·No-Run에 Run ID를 합성하지 않는다.
원202 이후 Run이 FAILED여도 원 admission은202다. 원410/422·No-Run 종류는 유지하고 현재 View/trace/header를 과거 bytes로 고정하지 않는다.

## 재시도·trace·cache·timeout

retryable은 **동일 요청의 자동 재시도 권고**다. 접수 여부/rollback 확인 필드가 아니다. false인502/500도 앞선 Go effect가 없음을 보장하지 않는다.
true이면 제한된 backoff로 동일 endpoint/key/body를 재전송/확인한다. GET은 같은 조회 조건, 취소는 같은 cancel key를 사용한다. 새 key/실패 Run 재실행을 자동 허가하지 않는다.
Core는 접수/취소 POST를 숨겨진 transport retry로 반복하지 않는다. Web의 명시 요청/재시도와 durable Worker 재시도를 분리한다.
503/504의 선택 `Retry-After`는 nonnegative integer delta-seconds다. 제공하면 bounded client backoff에 반영하며 무한 retry/Run deadline 연장으로 해석하지 않는다.
traceparent/tracestate는 W3C 규칙으로 검증/제한하고 invalid trace는 새 context로 처리한다. client 값을 인증/멱등 key로 쓰지 않는다. 응답 traceId는 현재 요청의 안전한 correlation이다.
모든 민감 Run/read/admission/오류 응답은 `Cache-Control: no-store`다. 기존 public `Vary: Cookie`, internal `Vary: Authorization, X-RefInvest-User-Delegation`을 유지한다.
Core가 생성한 relay 오류에도 같은 public cache/trace 규칙을 적용한다. 중계가 성공한 Go 응답의 현재 trace 의미는 보존한다.
HTTP request timeout/브라우저 abort/JWT 만료는 durable 작업 취소가 아니다. 명시 cancel API만 사용하며504를 접수 미완료·새 key 발급 근거로 삼지 않는다.

## Core의 제한된 원문 보존 중계

| 대상 | 책임 |
|---|---|
| 인증/CSRF/라우팅 | Core가 정상 검증하고 고정 operation→Go 경로로 연결. 임의 사용자 지정 upstream URL 금지 |
| JSON body | 최대1MiB bounded bytes를 그대로 전달. JSON DTO/map 변환·재직렬화·default/trim·시각 보정/압축 해제 금지 |
| key/path/query | 유효한 단일 key와 exact resource 식별·query 의미 보존. resource ID를 number로 변환하지 않음 |
| 서비스/사용자 위임 | 브라우저 Cookie/Authorization/위임 주장·owner header를 전달하지 않고 Core가 검증한 사용자와 자기 caller로 생성 |
| Content-Type/Encoding | 지원 여부 확인. accepted body bytes를 바꾸지 않음. infrastructure의 선행 자동 압축 해제도 비활성화/검증 |
| HTTP 전송 header | Host/Content-Length/Transfer-Encoding·Connection 및 connection 지시 hop header는 대상 client/프로토콜에 맞게 구성. 중복 framing을 복사하지 않음 |
| trace | 검증한 trace context만 서비스 hop으로 연결. 전체 header/request dump 금지 |
| 응답 | status·JSON media·typed schema/code branch·safe header allowlist 확인 후 중계. byte 보존 의무를 오류 mapper에 일괄 적용하지 않음 |

초기 구현은 상한을 강제한 bounded buffer로 가능하다. 동시 요청 admission 수·메모리/취소/불완전 수신을 제한/검증해야 하며 무제한 read 후 size 검사하지 않는다.
Core/Go는 body를 request log·exception·APM에 저장하지 않는다. 원문 중계는 raw HTTP body 영구 보관이나 별도 원장 Aggregate를 요구하지 않는다.
응답 header allowlist는 Content-Type·Cache-Control·Vary·유효 Retry-After다. Location을 제공할 경우 승인된 public URL로 매핑하고 internal URL/credential/Set-Cookie를 복사하지 않는다.
전송 도중 실패하면 Core가 이미 보낸 status/body를 두 번째 JSON 오류로 덧붙이지 않는다. 연결 실패로 종료하고 client는 원 key 확인 절차를 따른다.

## 소비자·수용·적용 순서

[consumer 수용 계획](TRADING_REVIEW_REPROCESSING_CONSUMER_ACCEPTANCE.md)의 기존 WR-DRAFT-01~14/WF/WUC/TR-ACC assertion에 연결한다. 새 public Scenario ID를 만들지 않는다.
OpenAPI의 local Request* schema·response registry·vendor byte/depth 제약과 정적 checker는 설계 표현이다. OpenAPI3.0의 maxLength는 UTF-8 bytes가 아니며 duplicate key/body 예산도 schema만으로 입증되지 않는다.
schema/helper의 synthetic 경계 검사를 실제 HTTP/Core relay·메모리·hash·Compute·SQL/업무 수용 증거로 보지 않는다.
Context 계약→consumer snapshot 계획/전파→명시한 구현 범위→실제 수용→운영 활성화 순서다. E2E 전 integration 계약 sync, Web 포함 시 `-IncludeWeb`을 수행한다.
API 후보의 DRAFT 표시를 실제 활성화로 바꾸지 않는다. legacy/consumer inventory·권위/DB history·단일 writer/키/복구·child/private/삭제 조건은 관련 gate로 별도 확인한다.

## 근거

- [HTTP 상태·중계 의미](https://www.rfc-editor.org/rfc/rfc9110.html)·[hop header](https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.1)
- [JSON 이름 중복/UTF-8](https://www.rfc-editor.org/rfc/rfc8259.html)·[W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [기존 HTTP 후보](TRADING_REVIEW_WORKFLOW_HTTP_DRAFT.md)·[OpenAPI 후보](TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)·[consumer/legacy 조사](TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md)
