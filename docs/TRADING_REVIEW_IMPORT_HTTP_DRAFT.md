# Import 내부 HTTP 계약 (DRAFT)

상태: **ADR-076·IC-P01~P06의 미채택 OpenAPI 후보·runtime 미구현**.
[계약 후보](TRADING_REVIEW_IMPORT_CONTRACT_DRAFT.md)·[request/replay](TRADING_REVIEW_IMPORT_REPLAY_DRAFT.md)·
[response/error](TRADING_REVIEW_IMPORT_RESPONSE_DRAFT.md)와 [OpenAPI 초안](../openapi/drafts/trading-review-import.yaml)을 함께 읽는다.
active core-api.yaml/compute-api.yaml·계산/상태/hash/ACK·실제 DB/서비스를 바꾸지 않는다.
schema/CI 성공은 후보 채택·Go 업무 구현/운영 활성화가 아니다.

## operation과 표면

내부 prefix는 `/internal/trading-review/v1` 후보이며 아래 suffix를 붙인다. 외부 Web→Core path는 유지한다.
Core/Gateway는 인증/CSRF·중계, Go는 서비스/위임·현재 owner/Guard·업무 접수/조회 종단이다.

| suffix·method | 내부 operationId | public namespace / scope suffix | 성공 DTO |
|---|---|---|---|
| /books/{bookId}/imports POST | createOwnerTradingImportSession | createTradingImportSession / create | 201 Session |
| /books/{bookId}/imports GET | listOwnerTradingImports | 없음 / read | 200 SessionPage |
| /import-sessions/{sessionId} GET | getOwnerTradingImportSession | 없음 / read | 200 Session |
| /import-sessions/{sessionId} DELETE | deleteOwnerTradingImportSession | deleteTradingImportSession / delete | 202 SessionDeletion |
| /import-sessions/{sessionId}/artifacts POST | uploadOwnerTradingImportArtifact | uploadTradingImportArtifact / upload | 201 신규 / 200 기존 Artifact |
| /import-sessions/{sessionId}/validation POST | validateOwnerTradingImportSession | validateTradingImportSession / admit | 202 Session |
| /import-sessions/{sessionId}/retry POST | retryOwnerTradingImportSession | retryTradingImportSession / admit | 202 Session |
| /import-sessions/{sessionId}/quality-report GET | getOwnerTradingImportQualityReport | 없음 / read | 200 Quality, 미완료 409 |
| /source-artifacts/{artifactId} DELETE | deleteOwnerTradingSourceArtifact | deleteTradingSourceArtifact / delete | 202 RawDeletion |

ID는 canonical positive decimal string이며 regex만으로 int64 상한·소유권을 증명하지 않는다.
새 Aggregate Snowflake 선택과 legacy UUID/opaque Compute 참조를 혼동하지 않는다.
public operation 이름이 멱등 namespace다. 내부 operation 이름이나 route로 교체하지 않는다.
GET은 key/claim을 만들지 않으며 Retry/DELETE에는 새 body를 추가하지 않는다.
없는 CancelImport·직접 upload URL/finalize·동기 Compute API를 추가하지 않는다.

## 서비스 인증 AND 사용자 위임

TLS 위 caller별 opaque `Authorization: Bearer`와 별도 `X-RefInvest-User-Delegation` JWT를 **동시에** 요구한다.
OpenAPI security는 두 scheme을 한 object에 두고 operation별 override/anonymous/OR를 허용하지 않는다.
Go 대상 서비스 credential과 Compute 공용 REFINVEST_INTERNAL_API_KEY·브라우저 secret/쿠키·authority 호출 credential은 별개다.
이번에 실제 secret/환경변수·issuer/verifier를 만들거나 기존 key를 바꾸지 않는다.

| 검증 값 | 후보 규칙 |
|---|---|
| audience | 단일 string `refinvest:trading-review:import:v1`. owner-query/account-book 토큰을 Import에 수용하지 않음 |
| JWT header | RS256, typ=refinvest-user-delegation+jwt, 신뢰 registry의 kid. alg/issuer/key 혼동·token의 임의 key URL 금지 |
| subject / actor | canonical 내부 Member ID와 실제 service registry caller에 결속된 act.sub. provider UID/email/body owner 근거 금지 |
| scope | 정확한 `trading-review:import:read/create/upload/admit/delete` 중 해당 route 값. wildcard/admin 우회·read→write 승격 없음 |
| caller registry | 실제 service caller·method·route·audience·scope를 함께 allowlist. scope 문자열 하나로 모든 operation 허용하지 않음 |
| 시간/철회 | iat/nbf/exp/jti·크기·키/상태 snapshot·철회를 검증. TTL/skew 수치는 별도 리뷰; jti는 업무 idempotency key가 아님 |
| Member authority | 별도 위임/actor/check binding의 현재 권위 확인 뒤 resource lookup/claim. active=true는 owner/Guard의 대용이 아님 |

기존 [위임 초안](TRADING_REVIEW_OWNER_QUERY_CONTRACT_DRAFT.md)·[Member authority](TRADING_REVIEW_MEMBER_AUTHORITY_DRAFT.md)·
[키 운영](TRADING_REVIEW_DELEGATION_KEY_OPERATIONS_DRAFT.md)의 검증 책임을 참조하되 read 전용 audience/수치·
authority 구현 완료를 Import 쓰기로 확대하지 않는다. 새로운 arbitrary `op` claim을 요구하지 않고 실제 route registry에서 검증한다.
Gateway는 추후 caller 등록/위임 발급 연계를 완료해야 하며 직접 issuer라는 가정은 없다.
scope claim은 단일 exact string 후보이며 다중/wildcard 값으로 operation을 자동 확장하지 않는다.

**기존 authority wire의 지원 범위도 별도 gate다.** 현재 Member authority ActiveReply의 aud/scope는
owner-query:v1 / owner:read로 제한돼 Import audience를 수용하지 못한다. 이를 Import/쓰기 권위 증명으로
재사용하지 않는다. authority의 정확한 audience/scope-pair·checkId/hash/actor/subject/시간·policyRevision 결속을
후속 계약에서 확장/검증해야 한다. 미지원/불일치이면 AUTHORITY_UNAVAILABLE로 fail closed하고 lookup/claim을 진행하지 않는다.
이번에는 그 authority schema/fixture를 넓히지 않았다. x-authority-profile-support는 이 미해결 조건의 정적 표시다.

**Member 차단과 쓰기 commit의 선형화는 미해결**이다. authority snapshot과 Go 최종 commit 사이에 차단될 수 있다.
bounded admission의 승인 시점/후속 차단 fence·Member lifecycle 조율을 별도 확정해야 한다.
DB Tx 안의 authority RPC·단순 재조회 두 번·다른 서비스 Member table lock으로 해결됐다고 주장하지 않는다.
OpenAPI의 x-member-write-linearization은 이 미해결 gate 표시이며 실행 구현이 아니다.

### 삭제 status control 재사용

삭제 뒤 기존 public `GET /trading-deletion-requests/{deletionRequestId}` polling은
[Account/Book 공통 draft](../openapi/drafts/trading-review-account-book.yaml)의
`GET /internal/trading-review/v1/deletion-requests/{deletionRequestId}` / `getOwnerTradingDeletionRequest`를 재사용한다.
이것은 Import에 새 endpoint를 추가하는 것이 아니다. 해당 기존 후보는 account-book audience·owner:read scope의
**별도 위임**과 request owner/control 검증을 요구한다. Import 토큰/삭제 토큰을 자동 변환·확장하지 않는다.
Core는 opaque deletion request ID로 그 공통 route를 호출하며 Go가 실제 request owner를 검사한다.
Root ACTIVE를 강제하거나 Core가 parent row를 알아야만 경로를 선택하도록 만들지 않는다.
issuer/caller registry에 공통 status-read 권한이 준비되지 않으면 삭제 후 polling 연결 완료가 아니다.
두 profile은 모두 미채택이며 통합/교체가 필요하면 ADR-075와 함께 별도 계약 변경으로 리뷰한다.

## 요청·응답 DTO와 입력 신뢰

Create는 기존 public request schema를 참조한다. Upload는 IC-P01 후보의 optional nullable filename과
다섯 필수 metadata, required file/metadata part를 사용한다. Validate는 required JSON object이며 target absent/null은
원 Session pin, explicit 값은 같은 exact set의 확인값이다. exact set에는 normalizer/reconstruction이 필요하고
analytics optional/null·composite hash의 기존 의미를 유지한다. canonical equality/실행 가능성은 semantic 검증이다.

API req/res와 C/Q/Result·Domain·DB row를 구분한다. body/path/query/metadata가 owner를 선택하지 않는다.
strict duplicate/unknown field·Unicode/number/nullable, IANA catalog, role별 dialect·원 byte hash/size는 실제 decoder/Use Case에서 검증한다.
JSON Schema는 duplicate JSON key·multipart framing·stream hash·순서/시각·현재 DB Guard를 검증하지 않는다.

Session 후보는 다섯 status의 quality/failure/Revision/completedAt 조건과 known 필드를 닫힌 DTO로 표현한다.
optional role inventory/canValidate·TTL hint, verified coverage·quality unavailable·근거 없는 detection null은 IC-P04를 따른다.
응답에 원본 locator/key·Run/lease/Storage model을 자동 추가하지 않는다. raw-only/Session 삭제 응답은
각 target/scope를 제한하며 fresh IMPORT_SESSION_SUBTREE와 legacy IMPORT_SUBTREE를 구분한다.
어떤 schema status/0 count도 purge 완료의 증거가 아니다.

## stream·중계·멱등성·시간

- Core는 브라우저 Cookie/CSRF를 종료하고 외부 Authorization/delegation/identity 헤더를 제거한 뒤 service/사용자 위임을 재구성한다.
  Idempotency-Key의 값과 의미·허용 Content-Type/trace를 유지한다. hop-by-hop/WWW-Authenticate·redirect/login HTML을 blind relay하지 않는다.
- upload는 metadata-first/file-first 양 순서, text/csv 또는 application/vnd.ms-excel file, application/json metadata를 지원한다.
  실제 file bytes 상한은 52,428,800이다. multipart overhead/proxy/read deadline·metadata 크기는 별도 infrastructure 검증이다.
  예제의 file 문자열은 shape 설명용이며 실제 multipart bytes 검증이 아니다.
- inbound adapter가 stream Close를 소유하고 반환 전에 모든 읽기/작업을 join한다. request ctx를 Worker에 넘기지 않는다.
  실제 timeout/abort로 blocking Read가 해제되는지는 Core→Go transport에서 검증한다.
- encrypted Object 작업은 Tx 밖에서 끝내고 짧은 최종 Tx에서 owner/parent/Session Guard·generation·slot·key를 다시 검사한다.
  실패/UNKNOWN·정리 checkpoint와 adopted Object 보호는 schema로 검증되지 않는다.
- public fingerprint는 원 operation의 NUL/colon profile·최초 policy resolve와 canonical target 의미를 보존한다.
  same key/새 인증 재전송은 현재 권한 아래 기존 접수로 수렴하고 새 Run/Dispatch/Revision을 만들지 않는다.
- request/중계 timeout, Compute 한 요청, attempt/Run 고정 정책, worker lease, raw TTL을 분리한다.
  새 수치/ADR-071 hard deadline은 없다. Commit 이후 browser abort·조립 Query 실패·504는 작업 취소/미접수 확정이 아니다.
- valid traceparent/tracestate만 연결하고 잘못된 parent는 새 trace·그에 딸린 state는 폐기한다. baggage는 전달하지 않는다.
  오류 traceId/Worker execution trace 상관관계를 검증하며 token/key·filename/원문/전체 checksum을 기록하지 않는다.

## 오류 중계 후보와 접수 불확실성

내부 모든 응답은 no-store다. 일반 ErrorResponse와 Retry 410의 userAction/occurredAt 필수 DTO를 구분한다.
아래 새 code/status는 **내부/중계 후보**이며 현재 public OpenAPI에 자동 추가되지 않는다.

| 내부 의미 | Core/Gateway public 번역 후보 |
|---|---|
| service·서명/위임/actor·caller permission 오류의 401/403 | ADR-074/075의 502 UPSTREAM_SERVICE_ERROR 후보. 사용자 미인증/CSRF로 blind relay하지 않음 |
| Member authority의 접근 거절 | 403 MEMBER_ACCESS_DENIED 후보, 상세 차단 이유 비노출. caller 설정 오류와 분리 |
| authority unavailable·일시 DB/Storage/운영 gate 503 | safe 503. 권한/Guard/claim 진행으로 fallback하지 않음 |
| 입력/owner/삭제/상태/버전·파일 오류 | 기존 의미의 400/404/410/409/422/413/415. foreign 404와 owned 삭제 구분 |
| request budget 504·예상 밖 500 | 안전한 코드/traceId. public 504/code는 별도 채택하며 commit 실패/rollback을 단정하지 않음 |
| Retry 410 | SOURCE_ARTIFACT_EXPIRED→REUPLOAD_REQUIRED 또는 RESOURCE_DELETED→NONE, retryable=false·occurredAt 유지 |

접수 뒤 현재 Query의 terminal Session 응답도 원 201/202로 반환할 수 있다. 성공 body를 최소 receipt로 교체하지 않는다.
후속 Query의 현재 410/의존성 실패와 원 commit 사실을 분리한다. UNKNOWN은 같은 key/권위 DB fence로 해소하며
새 connection SELECT miss만으로 rollback을 확정하거나 final Object를 삭제하지 않는다.
retryable은 미commit 증거·전체 Use Case 자동 반복 허가가 아니다. err.Error()/SDK/SQL/stack·raw 값은 공개하지 않는다.

## 정적 검증과 남은 증거

`python -B scripts/verify-trading-review-import-draft.py`는 local refs·아홉 operation·AND/auth metadata·scope/namespace·
body/parameter/status/no-store·stream/control 선언과 request/response 예제·schema 수용/거부·위험한 변이를 검사한다.
vendor extension은 실행 verifier가 아니다. JWT 서명/권위·IANA·int64 상한·JSON duplicate key·multipart bytes·hash/TTL/Guard·
SQL 경합·UNKNOWN·Storage/Compute/E2E는 검사하지 않는다.
TR-PLAN-IMPORT-HTTP-001~024는 **미실행 semantic 계획**이며 기존 후보 계획/정본 TR-ACC와 별개다.

새 YAML은 integration의 정확한 파일명 분류·Core snapshot·실제 PS gate/checker 호출이 선행돼야 한다.
Context preview의 default integration main 분류를 우회하지 않는다. merge 뒤 snapshot PR/marker 적용·일치 검증은 별도다.
active public/Compute OpenAPI·golden fixture는 유지하며 새 schema/helper·문서만으로 업무 구현 전환/배포를 승인하지 않는다.
후속은 내부 profile/후보 정책·모듈/Guard·저장/전체 writer·Worker 실행/수용 명세의 리뷰다.
