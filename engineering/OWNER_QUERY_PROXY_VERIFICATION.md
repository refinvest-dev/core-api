# Core Owner Query Proxy 구현·검증 기록

기준일: 2026-11-24. 범위는 [상세 설계](OWNER_QUERY_PROXY_IMPLEMENTATION.md)의 Account/Book metadata GET 두 개다.
[Context ADR-086 정책](https://github.com/refinvest-dev/context/blob/8f00652381ef3c81b9fd11b7e4e452c8f98c451c/docs/TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)과
[Go #126](https://github.com/refinvest-dev/trading-review-api/pull/126)의 HTTP 구현을 소비한다.
Core base는 `410dcbb9ffb3ede8ec8b9e7ca1ed3f7d3e52f8c6`, 확인한 Go main은
`0029b1bf986ae295cb14de7be8d3a436d823230f`다. 이 문서는 Core 소유 구현 기록이며 제품 계약의 대체물이 아니다.

## 구현 경계

| 위치 | 책임 |
|---|---|
| `tradingreview:port` | Query, 취소 가능한 Call, typed metadata/실패, 작은 delegation issuer/reader |
| `tradingreview:application` | 입력 확인, 현재 인증 thread에서 위임 발급, 남은 요청·위임 budget 전달 |
| `tradingreview:adapter:reviewapi` | 고정 HTTPS 두 경로, 별도 credential, strict 응답 검증, 비동기 전송 수명 |
| `tradingreview:adapter:web` | 기존 두 Controller의 선택적 중계, DeferredResult, 안전한 오류·no-store |
| `app` | Auth 위임 발급기와 Trading Review issuer 연결, 조건부 조립 |
| `shared:infrastructure` | W3C trace 검증·새 span 생성; SDK/exporter 등록 없음 |

Inbound/outbound는 서로 import하지 않는다. 양쪽이 실제 사용하는 Call·Resource·Result·Failure 네 타입만
`port/common/ownerquery`에 두며 경계 테스트에 정확한 파일 목록을 등록했다. Domain/JPA/HTTP 모델을 공유하지 않는다.
새 outbound reader는 기존 Get service나 persistence로 fallback하지 않는다.

`trading-review.owner-query-proxy.enabled`는 기본 false다. 활성화 조건은 명시 issuer/reader bean과
유한 `request-timeout`이며 실제 key/credential/CA 공급과 client lifecycle owner는 별도 composition에서 준비해야 한다.
애플리케이션이 준비되지 않은 상태에서 enabled=true만 설정하면 startup 실패다.
Go Main 연결, Core TR writer 중단, 데이터 인계와 운영 활성화는 이 변경에 포함하지 않았다.

## 실제 실행 결과

| 검사 | 결과와 해석 |
|---|---|
| `:app:compileKotlin :tradingreview:adapter:reviewapi:compileKotlin verifyAggregateModuleBoundaries` | 성공, Gradle module 47개 경계 검사 |
| 최종 기본 JVM의 shared infrastructure / reviewapi / web 검사 | 각각 6 / 8 / 18 tests 통과; 같은 묶음의 Application worker 오류로 전체 명령은 실패 |
| 미완료된 `:tradingreview:application:test` 개별 재실행 | 55 tests, 실패/오류/skip 0 |
| 최종 `:app:test`의 Proxy·Trading/Auth/Core 경계 관련 여섯 class 선택 실행 및 `:app:bootJar` | 23 tests, 실패/오류/skip 0; 실행 JAR 패키징 성공 |
| 2026-11-25 Book·chunked 경계 추가 후 `reviewapi:test web:test` | 각각 10 / 19 tests, 실패/오류/skip 0; 기본 JVM에서 한 명령 성공 |

모듈 검사는 해당 `cleanTest` 뒤 실행했으며 과거 XML을 합산하지 않았다. 실행은 Java25·Gradle wrapper를 사용했다.
App 선택 pattern은 `*OwnerQueryProxy*`, `*TradingPortBoundaryTest`, `*TradingReviewModuleBoundaryTest`,
`*CoreModuleBoundaryTest`, `*TradingApplicationStructureTest`, `*AuthModuleBoundaryTest`다.

최초 검증에서 TLS fixture 초기화 순서, 인증 fixture의 잘못된 Cookie 이름, 완료 통지와 admission permit 해제 사이의 경합을 발견했다.
fixture는 `@BeforeAll` 인자의 임시 directory와 실제 AuthCookieWriter 상수를 사용하도록 수정했다.
전송 구현은 하나의 terminal CAS를 거쳐 timer/network·permit 소유권을 정리한 뒤 호출자에게 완료를 통지하도록 수정했다.

수정 후 기본 Windows 실행에서도 native `java.net.SocketException: Bad address: listen`으로 TLS fixture의
loopback listener 생성이 실패했다. 해당 실행은 실패로 기록하며 성공으로 합산하지 않는다.
자식 검증 프로세스에만 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true`를 추가한 별도 실행에서 한 차례 87개가 통과했지만
후속 재실행에서는 같은 listener 오류가 재발했다. App 재실행도 Gradle daemon의 listen 단계에서 시작하지 못한 경우가 있었다.
이 실행들은 실패이며 기존 XML을 새 성공으로 해석하지 않는다. IPv4 옵션으로 문제가 해결됐다고 판단하지 않는다.
운영 설정·Gradle·테스트 코드는 이 옵션을 강제하지 않고 재시도/skip으로 오류를 숨기지 않는다.
TLS fixture는 class당 하나의 server를 공유하고 각 테스트의 요청 handler·client를 분리하도록 정리했다.
이후 기본 JVM 실행에서는 실제 TLS 5개와 decoder 3개가 모두 통과했다. 같은 실행의 Application worker에서
listen 오류가 발생했으나 해당 모듈 개별 재실행 55개가 통과했다. App 최종 23개와 bootJar도 통과했다.
따라서 표의 최종 검사는 여러 실행의 결과이며 한 번의 전체 명령이 성공한 것으로 표현하지 않는다.
테스트 옵션을 bootJar에 적용한 검증 helper의 명령 오류도 한 차례 있었으며 옵션 위치를 고쳐 재실행했다.
Linux 전체 CI 결과는 정확 PR head를 기준으로 별도로 확인한다.

최종 리뷰에서 Book의 성공 경로와 Content-Length가 없는 초과 응답 검증을 보강했다.
실제 TLS에서 Book 고정 path·큰 parent ID·ARCHIVED metadata를 확인했고, decoder에서 숫자/비정규 parent와
미지원 product profile을 거절하는지 검사했다. Web에서는 parent·product/timezone을 public Book DTO로 반환하는지 검증했다.
chunked 초과 body는 선언된 Content-Length의 사전 거절과 구분하여 실제 subscriber의 수신 상한을 검증했다.
생산 코드는 변경하지 않았고 관련 두 adapter 모듈을 다시 실행했다.

### Linux CI의 소스 경계 검사 후속

`d326c158385ca3cb53963702c3dfb53e827e5dab`의 [Linux CI](https://github.com/refinvest-dev/core-api/actions/runs/45422110739/job/138544134494)는
628 tests 중 `TradingAccountBookBoundaryTest.tradingReviewSourcesRespectFeatureLayers` 한 건이 실패했다.
오류/skip은 0이었지만 전체 CI는 실패이고 PostgreSQL 후속 gate도 실행되지 않았다.
Gradle 의존 지도에는 반영한 공통 trace 기술 의존성을 해당 소스 경계 검사에 반영하지 않은 것이 원인이었다.

검사 대상에 `adapter/reviewapi`를 추가하고 Web/reviewapi에만 `shared.infrastructure.http` 참조를 허용했다.
다른 feature·shared 기술 전체나 Domain/Application 의존성을 허용하지 않는다.
실패 class의 로컬 9개 테스트를 다시 실행하여 실패/오류/skip 0을 확인했다. 수정 후 정확 head의 전체 CI는 별도로 확인한다.

## 검증한 경로

- 임시 keytool identity·실제 HTTPS server를 사용하는 client 검사는 고정 path·재구성한 credential/위임,
  Cookie/baggage 비전달, 큰 문자열 ID, 신뢰되지 않은 CA·redirect·encoding·cache·중복 field·크기/UTF8/ID 오류를 확인했다.
- Header 뒤 느린 body의 total timeout, 논리 in-flight 초과, 취소·close 후 차단과 활성 DB Tx 진입 거절을 확인했다.
  취소 검사는 terminal 완료 뒤 permit을 재사용할 수 있는지 포함한다. 물리 socket/thread 수나 운영 부하 상한을 측정한 것은 아니다.
- Decoder 검사는 알려진 status/code/retryable·현재 trace 일치와 strict JSON의 구조/type/문자열 경계를 확인했다.
  upstream 원문 message를 public 응답으로 사용하지 않는다.
- Web MockMvc는 typed async 결과·중계 실패·잘못된 path/query·HEAD를 검사했다.
  App의 실제 SecurityConfiguration·HS256 Cookie JWT decoder와 RS256 issuer를 연결해 현재 principal의 큰 Member ID,
  고정 actor, 위조 owner/위임 header 무시, 미인증401·no-store와 POST CSRF403을 확인했다.
- 기본 비활성화와 준비 없는 활성화 실패를 확인했으며 기존 Controller·Application 회귀도 대상 모듈 실행에 포함했다.

HTTPS fixture와 App 인증 fixture는 각각 실제 경로를 검증하지만 하나의 Core→Go→Core 서비스 E2E가 아니다.
환경 proxy 무시 정책은 builder의 명시 NO_PROXY 설정이며 외부 proxy 환경을 주입한 별도 실행은 하지 않았다.

## 수용·운영 후속

Go Main의 두 GET, 실제 Core authority callback, 전용 CA/credential·서명 key 공급/교체, 회원 상태 초기화,
기존 DB/ID와 TR writer 인계, 실제 browser abort·Core→Go→Core 동시 부하/종료 수용은 미실행이다.
Servlet이 아직 관측하지 못한 연결 종료를 즉시 감지한다고 주장하지 않으며 독립 total timer가 수명을 제한한다.
현재 Go가 출력하지 않는 optional latest pointer도 지원한다고 간주하지 않는다.

실제 서비스 E2E 전에 integration의 계약 sync를 실행하고 분리된 integration DB와 synthetic fixture를 사용한다.
문서·fixture 성공이나 PR 병합을 운영 활성화 승인으로 해석하지 않는다. 로컬 환경/사용자 파일 20개와 다른 저장소의 변경을 보존했다.
