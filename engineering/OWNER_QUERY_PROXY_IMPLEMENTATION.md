# Core Owner Query Proxy 설계

상태: ADR-086의 두 metadata GET을 위한 명시 client/service/Web 연결 준비(2026-11-24).
정본은 Context ADR-086과 연결된 두 내부 OpenAPI다. Go의 [HTTP 구현](https://github.com/refinvest-dev/trading-review-api/pull/126)을 소비한다.
기본 Core route는 유지하며 실제 키/회원 초기화·Go Main·DB 인계·부하/운영 활성화는 별도다.

## 경계와 조립

- `tradingreview:port`는 두 metadata resource·Query/Result·취소 가능한 Call과 작은 delegation/reader 계약을 소유한다.
  HTTP status/Servlet/JDK HttpClient/JSON 객체·타 feature Domain은 넣지 않는다.
- `tradingreview:application`의 명시 Proxy service는 현재 servlet thread에서 위임을 발급한 뒤 남은 budget으로 비동기 reader를 호출한다.
  DB/기존 Get use case를 호출하거나 실패 시 legacy 조회로 fallback하지 않는다.
- `tradingreview:adapter:reviewapi`는 고정 Go HTTPS 두 path, 별도 credential, 제한 응답/strict JSON과 transport 수명을 소유한다.
- `tradingreview:adapter:web`은 두 기존 Controller에 선택적인 inbound proxy capability를 받는다. 없으면 기존 DTO를 그대로 반환한다.
  있으면 DeferredResult로 servlet thread를 반납하고 명시 DTO/error를 반환한다. 전환기의 두 GET만 adapter 반환형 Any를 사용하며,
  실제 값은 기존 DTO 또는 typed DeferredResult로 제한한다. C/Q/Result/Domain에는 Any를 넣지 않는다.
- `app`은 auth의 IssueOwnerQueryDelegationUseCase를 Trading Review의 작은 issuer port에 연결한다.
  외부 owner/role/actor/JWT를 위임 근거로 받지 않고 기존 인증 principal에서만 발급한다.

`trading-review.owner-query-proxy.enabled`는 기본 false이며 request-timeout은 기본 숫자를 두지 않는다.
true이면 명시 issuer/reader bean과 유한 budget이 모두 필요하며 준비 실패는 startup 실패다.
새 network client/signing key/credential을 자동 생성하거나 기존 Compute key를 재사용하지 않는다.
선택된 proxy의 실패를 legacy DB 성공으로 바꾸지 않는다. enabled=false의 인증/CSRF·기존 GET 동작은 보존한다.

## 요청/응답

Client는 fixed origin HTTPS만 받고 path/query/userinfo/fragment·환경 proxy·redirect·cookie/authenticator를 사용하지 않는다.
신뢰 CA와 기본 hostname 검증·TLS1.2 이상을 유지한다. 외부 요청 header를 복사하지 않고 credential/새 위임/검증된 trace만 재구성한다.
기존 Core의 Long path binding은 유지한다. proxy branch의 body/query/transfer·content encoding을 거절하며 추가 업무/멱등성 row를 만들지 않는다.

JDK25 `BodySubscribers.limiting`으로 body를 16 KiB까지 받는다. 수신 header도 8 KiB·no-store/media/encoding/status를 검사한다.
JDK 초기 header parser 한계는 JVM/배포 설정의 별도 책임이며 post-receive 검사와 혼동하지 않는다.
JSON은 strict UTF8·duplicate/case/unknown/type/추가 JSON·unpaired surrogate를 거절한다.
현재 Go가 실제 출력하는 Account5/Book9 field를 지원하며 ID/parent·enum·UTC 시각을 검증한다.
현재 미출력 optional latest pointer는 추정하지 않으며 향후 지원 추가는 consumer 검토를 거친다.

Go의 401·CALLER_NOT_ALLOWED403과 unknown/malformed response는 public502 UPSTREAM_SERVICE_ERROR다.
Member403·400·404·410·두503·504는 ADR-086 registry를 따른다. 응답 message는 Core의 안전한 고정 문구이고 upstream raw message/details/challenge를 넘기지 않는다.
error trace는 현재 요청과 비교하며 no-store를 적용한다. 기존 브라우저 인증 실패401과 CSRF는 기존 security chain이 담당한다.

## 비동기 수명/Tx/trace

논리 in-flight admission은 즉시 거절 가능한 유한 semaphore로 제한한다. 물리 socket/thread 상한이나 부하 수용의 증거는 아니다.
전체 타이머는 headers 이후 body 완료도 포함한다. timeout·명시 cancel·close에서 network future를 취소하고 permit/timer를 정리한다.
독립 authority 자원을 잡고 Go를 기다리지 않으며 실제 Tx가 활성인 thread의 network 진입은 거절한다. Tx를 suspend해 숨기지 않는다.
owner는 ingress drain/join 후 client close/shutdown/await를 수행한다. 무한 close나 숨겨진 retry/background job은 도입하지 않는다.

DeferredResult timeout/error/completion은 취소 handle에 연결한다. Servlet container가 아직 관측하지 못한 browser disconnect까지
즉시 감지한다고 주장하지 않는다. 전체 deadline은 그 경우에도 유지하며 실제 Core→Go→Core 부하/abort 수용은 별도다.
cross-service absolute deadline header를 새로 만들지 않는다. 위임/credential 공급은 준비된 snapshot에서 동기·bounded하게 제공해야 한다.

W3C parent/state는 OpenTelemetry API의 parser로 검증한다. 잘못된/중복 parent는 새 random trace로 시작하고 state/baggage는 제거한다.
새 outbound span으로 연결하되 SDK/exporter·global provider는 등록하지 않는다. trace를 권한 증거로 사용하거나 사용자/거래/토큰 attribute를 추가하지 않는다.

## 검증 계획

Service의 발급/입력/budget·취소, strict decoder/오류 registry, 실제 TLS/redirect/proxy/size/body timeout/CA·취소/close,
기존 Controller 동기 회귀와 proxy async/error·NoStore, 실제 인증 principal 기반 서명/CSRF·준비 실패/disabled mode를 검증한다.
이 검사는 실제 Go service/Member DB E2E와 운영 인계·활성화 증거를 대체하지 않는다.
