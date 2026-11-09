# Trading Review Owner Query Contract (DRAFT)

상태: **ADR-074 검토 초안·미채택·미구현**. ADR-072, 현재 공개 Core/Compute OpenAPI와 Go의
미연결 owner query 구현을 변경하지 않는다. 이 문서와 [OpenAPI 초안](../openapi/drafts/trading-review-owner-query.yaml)은
채택 리뷰의 입력이며 production route 활성화나 G05/G06 완료의 근거가 아니다.

## 범위와 제안

Core/Gateway→Go의 Account/Book metadata 단건 조회 두 개만 제안한다. 초기 호출자는 Core,
Gateway는 향후 별도 caller 등록과 사용자 토큰 교환/발급 연계를 완료한 뒤 사용한다. Gateway가
사용자 위임 issuer가 되어야 하는 것은 아니다. 브라우저의 기존 path/schema는 유지한다.

| 내부 Go 경로 | operationId | 기존 공개 Core 경로 |
|---|---|---|
| `GET /internal/trading-review/v1/accounts/{accountId}` | `getOwnerTradingAccount` | `/trading-accounts/{accountId}` |
| `GET /internal/trading-review/v1/books/{bookId}` | `getOwnerTradingBook` | `/trading-books/{bookId}` |

응답은 기존 Core의 TradingAccount/TradingBook/ErrorResponse schema를 참조한다. Go의 AccountID를
Book wire의 `tradingAccountId`로 명시적으로 변환한다. Go 내부 Book projection의 displayName이나
owner·generation을 자동 JSON 직렬화해 추가하지 않는다. optional latest pointer를 추정·생성하지 않는다.
현재 path ID와 `sub`는 canonical positive decimal int64 문자열이다. 공개 ID의 opaque 의미를
변경하지 않으며 regex만으로 int64 upper bound까지 보장된다고 쓰지 않는다.

## 두 인증 책임

**추천 초안: TLS + caller별 opaque service Bearer credential + 별도 RS256 사용자 위임 JWT.**
서비스 credential은 누가 Go를 호출했는지, 사용자 JWT는 누구를 대신해 어떤 범위로 호출하는지
검증한다. 하나만으로 요청을 허용하지 않는다. OpenAPI security requirement는 한 object에 두 scheme을
함께 두어 AND를 표현한다. [OpenAPI Security Requirement](https://spec.openapis.org/oas/v3.0.3.html#security-requirement-object)

| 전달 | 초안 검증 |
|---|---|
| `Authorization: Bearer <opaque service credential>` | Go 대상의 caller별 registry·최소 권한·활성 상태·constant-time 검증 |
| `X-RefInvest-User-Delegation: <compact JWT>` | 서명·고정 alg/type·issuer/key·audience·시간·subject·actor·scope |
| raw owner/user header·query/body | principal 근거로 사용하지 않음 |

service credential은 최소 32 random bytes 수준으로 발급하고 secret manager에서 공급한다.
issuer 서명키, 브라우저 HS256 secret, Compute의 `REFINVEST_INTERNAL_API_KEY`를 재사용하지 않는다.
키 이름/환경 변수·발급/회수 도구는 별도 구현에서 정의한다. 이 변경은 secret을 만들거나 배포하지 않는다.
등록된 service identity는 credential 자체에서 registry로 해석하며 임의 caller header를 신뢰하지 않는다.
Core는 외부 Authorization/delegation/identity 헤더를 제거하고 두 credential을 재구성한다.
credential과 JWT는 로그·trace baggage·오류·checkpoint에 기록하지 않는다.

credential rotation은 같은 caller ID에 제한된 두 credential을 겹쳐 등록한 뒤 구 credential을 회수한다.
활성 caller에도 두 GET의 권한이 없으면 403이다. mTLS는 대안이나 현재 certificate 운영 체계가
확인되지 않아 필수안으로 선택하지 않는다. TLS termination 경계·secret 공급/회수 리허설은 채택 gate다.

## 위임 토큰 profile 제안

protected header는 `alg=RS256`, `typ=refinvest-user-delegation+jwt`, `kid`를 요구한다. RSA 공개키는
최소 2048-bit로 고정된 issuer/key registry에서 받는다([RFC 7518](https://www.rfc-editor.org/rfc/rfc7518.html#section-3.3)). JWT의 `jku`, `x5u`, embedded `jwk`,
알 수 없는 header/critical extension은 거절한다. `none`, HS256, 브라우저 access/refresh JWT를
허용하지 않는다. 현재 Core는 브라우저 HS256 issuer만 있으므로 별도 issuer 구현과 검증이 필요하다.
용도별 type/audience/key 검증을 분리하는 근거는 [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725.html#section-3.12)다.

| claim | 초안 규칙 |
|---|---|
| `iss` | 환경별 명시 issuer allowlist와 정확히 일치. 토큰이 지정한 URL로 discovery하지 않음 |
| `aud` | 단일 string `refinvest:trading-review:owner-query:v1` |
| `sub` | 검증된 내부 Member ID 문자열; provider subject/email 아님 |
| `act.sub` | registry에서 인증한 caller ID와 일치 |
| `scope` | 정확히 `trading-review:owner:read`; operator/admin owner 우회 없음 |
| `iat`, `nbf`, `exp` | integer NumericDate, `nbf=iat`, `0 < exp-iat <= 60` |
| `jti` | issuer가 생성한 비충돌 opaque ID; one-time admission key가 아님 |

60초 TTL, 5초 clock leeway와 4096-byte JWT 상한은 **리뷰용 제안 값**이다. 필수 key/claim/type을
검사하며 duplicate JSON member는 거절한다. `iat <= now+5`, `nbf <= now+5`, `now < exp+5`를
요구한다. 서명·신뢰 registry를 확인하기 전 claim을 권한으로 사용하지 않는다.
이 값은 RFC가 정한 TTL이 아니며 [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.4)의
시간 claim에 적용하는 서비스 profile 후보다. leeway를 적용하면 nominal TTL보다 수락 창이 길어진다.

`act`는 actor 식별 의미만 사용하며 OAuth token exchange endpoint 전체를 구현했다는 뜻이 아니다.
[RFC 8693 actor claim](https://www.rfc-editor.org/rfc/rfc8693.html#section-4.1)
GET retry는 유효 기간 내 동일 위임을 재사용할 수 있다. jti를 일회용으로 소비하지 않는다.
이는 proof-of-possession/완전한 replay 방지 계약이 아니다. TLS·별도 caller credential·짧은 TTL로
노출 범위를 줄이며 resource ID는 토큰 scope 아래 실제 소유권으로 다시 검증한다.

초기 검증키는 신뢰 설정으로 공급한다. JWT에서 임의 JWKS URL을 따라가지 않는다. 새 공개키를
검증자에 먼저 배포한 뒤 issuer kid를 전환한다. 구 키 유지 창은 최대 TTL·양방향 clock skew·진행 중
요청 budget을 포함해 정의하고, 회수와 reload 실패를 실험한다. key cache/reload 구현이나 즉시 키
철회가 이미 구축됐다고 가정하지 않는다.

## Member 상태와 권한 확인 순서

1. TLS/크기·중복 header 제한 → service credential 검증.
2. JWT 서명/profile → `act.sub`와 실제 caller binding → caller/scope 권한.
3. **권위 Member 접근 상태 검사** → 입력 ID 검증 → Go owner/parent/현재 삭제 Guard 조회.
4. 성공 projection을 whitelist 변환하고 cache 금지 응답.

추천은 조회마다 권위 Member 상태를 확인하고 상태 확인 실패를 503으로 거절하는 방식이다.
Go가 Member table을 직접 읽어 인증 authority를 대체하지 않는다. 정확한 authority endpoint·서비스
인증·상태/철회 linearization·응답 pin은 **별도 미확정 gate**다. 현재 Core에는 이 Go용 protocol이 없다.
이 gate가 구현되기 전 위임 서명/TTL만으로 production 조회를 활성화하지 않는다. TTL만으로 회원
정지/탈퇴나 기존 session/logout의 즉시 철회를 보장하지 않는다. 이미 시작된 요청의 철회 범위도 별도다.

이 초안은 origin session family를 현재 브라우저 access token에서 얻을 수 있다고 가정하지 않는다.
Member 상태 연계와 session revocation은 서로 다른 확인 사항이며 새 boolean claim으로 대체하지 않는다.
Core/Gateway가 principal을 확인했더라도 Go의 resource owner/parent/삭제 Guard 검사를 생략하지 않는다.

## Archive metadata 조회 제안

추천은 **삭제되지 않은 ARCHIVED Account/Book의 metadata GET은 허용**하는 것이다. archive는
삭제/물리 purge로 해석하지 않는다. owner와 상위 Account의 삭제 Guard는 계속 우선한다.
Book 또는 parent가 삭제 중이면 owner에게 410, 타 사용자에게는 404다. Raw-only generation과
Book 전체 삭제 generation도 구분한다. archive command·write/admission/진행 job의 효과는 이번
두 GET 계약에 추가하지 않는다.

현재 Core의 Book get은 non-ACTIVE를 RESOURCE_DELETED로 처리하고, Go는 ARCHIVED Book/parent를
ErrUnavailable로 보류한다. 이 초안을 채택하려면 두 구현과 공개 오류 호환성·기존 archive 데이터를
검토한 뒤 바꿔야 한다. 이 문서 작성만으로 어느 구현도 archive 조회를 허용하지 않는다.

## 오류와 중계 제안

모든 오류는 기존 ErrorResponse의 code/message/traceId/retryable 형태를 사용한다. SQL·토큰·원문
header·리소스 metadata를 details에 넣지 않는다. 다음 새 code와 중계 mapping은 초안이다.

| Go 내부 응답 | Core/Gateway 처리 제안 |
|---|---|
| 401 SERVICE_AUTHENTICATION_FAILED / USER_DELEGATION_INVALID | upstream 설정/위임 오류로 취급, public 502 UPSTREAM_SERVICE_ERROR; 무조건 public 401로 전달하지 않음 |
| 403 CALLER_NOT_ALLOWED | public 502 UPSTREAM_SERVICE_ERROR, 사용자 재로그인 유도하지 않음 |
| 403 MEMBER_ACCESS_DENIED | 현재 사용자 접근 거절, public 403; session/logout 원인 mapping은 authority 계약에서 확정 |
| 400 INVALID_REQUEST | public 400, unsafe details 제거 |
| 404 RESOURCE_NOT_FOUND | public 404, foreign/absent 동일 |
| 410 RESOURCE_DELETED | public 410, owned 삭제 Guard만 |
| 503 AUTHORITY_UNAVAILABLE / OWNER_QUERY_UNAVAILABLE | public 503, retryable=true; 권한 검사를 우회하지 않음 |
| 504 REQUEST_DEADLINE_EXCEEDED | public 504, retryable=true |

모든 응답은 `Cache-Control: no-store`다. WWW-Authenticate의 내부 challenge를 브라우저에 그대로
전파하지 않는다. redirect/login HTML을 반환하지 않는다. 현재 공개 ErrorResponse를 Problem Details로
바꾸거나 Core route를 새 경로로 교체하지 않는다.

## Timeout·trace·브라우저 취소

Go active request budget 5초, Core connect 1초/전체 12초를 제안한다. Go budget은 Member 확인·DB
조회에 적용하고 upstream Context와 더 짧은 값을 사용한다. rollback cleanup은 기존 Runner의
독립 유한 budget을 사용한다. cleanup/전송까지 항상 5초 안에 끝난다는 SLO를 주장하지 않는다.
수치는 부하/장애 측정 후 확정한다. future durable Run deadline·lease·Compute timeout과는 별개다.

검증된 traceparent/tracestate만 전파하고 malformed traceparent는 새 trace로 시작한다. tracestate는
유효 parent가 없으면 버리고, baggage는 전달하지 않는다. tracing 실패로 인증 근거를 대체하거나
거래/사용자 식별자·토큰을 전파하지 않는다. [W3C Trace Context](https://www.w3.org/TR/trace-context/)
오류 traceId는 안전한 correlation 값이며 현재 Core UUID 형식도 허용한다. 새 response header는 요구하지 않는다.

GET은 멱등성 row/Run을 생성하지 않는다. retry는 유한 policy 아래 새 outbound span으로 연결하고
유효 기간이 끝났으면 같은 사용자/caller 의미로 위임을 갱신한다. 브라우저 취소는 이 read Context만
취소한다. 이미 접수한 durable job의 취소가 아니며 앞으로의 명시 cancel command를 대신하지 않는다.

## 채택·적용 gate

- service credential/TLS·RS256 issuer/key rotation·Member authority/철회 protocol과 예산 선택 리뷰.
- archive metadata 의미 및 기존 public mapping의 Core/Go 동시 호환성 검토.
- OpenAPI 초안 승격, 승인된 schema snapshot/vector 동기화, Go/Core HTTP adapter 구현.
- 실제 서명/변조·wrong issuer/aud/type/kid/actor·Member 거절/장애·unknown ID/삭제·취소/trace 검증.
- 실제 DB catalog/history/baseline 인계와 Core Trading Review 기존 쓰기 비활성화 증거.

현재 checker는 초안 상태/OpenAPI 참조·security AND·예제 shape의 정적 검사다. 서명·JWT
validator·authority·HTTP·E2E 검증이 아니다. 초안 token 숫자/이름·secret/배포 설정을 runtime에 넣지 않는다.

## Authority/키 운영 보완 초안

[Member authority protocol](TRADING_REVIEW_MEMBER_AUTHORITY_DRAFT.md)과
[키 운영 절차](TRADING_REVIEW_DELEGATION_KEY_OPERATIONS_DRAFT.md),
[authority OpenAPI](../openapi/drafts/trading-review-member-authority.yaml)에서 요청/응답 binding·권위 판정
snapshot·재확인·운영 교체/철회를 구체화했다. 앞 절의 authority 미확정 gate는 이제 이 보완안의
계약 채택·실제 Core 상태/issuer/endpoint·운영 구현 검증으로 이어진다. 기존 미채택 상태는 유지한다.
