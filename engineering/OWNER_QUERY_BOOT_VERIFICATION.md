# Owner Query Boot 조립·종료 검증

> 후속 [일반 Main 서비스 수용](OWNER_QUERY_MAIN_VERIFICATION.md)에서 실제 bootJar/Go Main의16개 시나리오를 검증했다. 아래 계획/격리 검증의 이력과 구분하며 운영 전환 NO_GO는 유지한다.

기준일: 2026-11-27. [production 계획](OWNER_QUERY_PRODUCTION_COMPOSITION.md)의 2번 구현이다.
Core base는 `b7e7991bd41061b07810804cb2162a7d1a1000f7`(#153 병합), 코드 commit은 `cf285f5199b12594c58609cf57c164c086bd3894`다.
정본 ADR-086의 두 metadata GET만 대상으로 한다. 기본 비활성화·실제 운영 인계 NO_GO는 유지한다.
일반 RefinvestApplication↔실제 Go Main의 후속 E2E는 상단 기록을 따르며 fleet/rotation·운영 수용은 미완료다.

## 조립과 현재 source

| source | 역할 |
|---|---|
| [설정 선택](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryBootSettings.kt) | opt-in·부분 구성·direct TLS·유한 예산 검사, redacted typed 설정 |
| [Boot root](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryRuntimeConfiguration.kt) | 기존 preparation 호출, capability Bean·개수/연결 검사·lifecycle owner |
| [Runtime](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryRuntime.kt) | read/authority 사용자 추적·callback drain·완료/강제 종료 구분 |
| [Proxy](../app/src/main/kotlin/com/refinvest/core/config/OwnerQueryProxyConfiguration.kt) | 명시 snapshot의 request budget 사용, 중복 설정 불일치 거절 |
| [authority 등록 조건](../auth/adapter/web/src/main/kotlin/com/refinvest/core/auth/adapter/web/authority/MemberAuthorityAvailableCondition.kt) | 기존 명시 capability 또는 opt-in 선택으로 Controller/chain 등록 순서 의존 제거 |
| [client 종료](../tradingreview/adapter/reviewapi/src/main/kotlin/com/refinvest/core/tradingreview/adapter/reviewapi/OwnerMetadataHttpClient.kt) | 주어진 남은 예산 하나로 client/timer await, zero budget은 추가 대기 없는 강제 정리 시도 |

`RefinvestApplication`의 기존 component scan이 Boot root를 발견한다. application/domain에 Spring 조립 코드를 넣지 않는다.
생성된 `PreparedOwnerQuery`는 별도 Bean으로 노출하지 않고 Runtime 하나가 소유한다. issuer·authority check·authority caller·reader만
기존 feature 계약으로 주입하며 DataSource/Clock/current Member provider는 빌려 쓴다. app은 사용하는 SecurityFilterChain API를 기존 version catalog로 명시 의존한다.

선택된 runtime의 Bean 정의가 나중에 발견돼도 authority adapter의 조건은 명시 mode로 일치한다.
이후 SmartInitializingSingleton에서 expected capability·Controller의 유일성, 실제 issuer/reader 연결·authority chain 존재를 검사한다.
잘못된/중복/누락 구성은 context refresh 실패이며 생성 Runtime은 destroy 경로에서 준비 client를 닫는다.
기존 test launcher의 선등록 capability 조건도 유지한다. 다른 security chain을 전역 제거하거나 browser CSRF를 해제하지 않는다.

## 명시 선택과 환경 설정

[application.yaml](../app/src/main/resources/application.yaml)에 다음 process env/기존 Core .env properties import용 placeholder를 추가했다.
실제 .env·example이나 secret 파일은 수정하지 않았다. 아래는 지원 설정이며 실행/배포 승인 목록이 아니다.

| 환경 이름 | property / 규칙 |
|---|---|
| TRADING_REVIEW_OWNER_QUERY_RUNTIME_ENABLED | trading-review.owner-query-runtime.enabled, 기본 false. true/false만 허용 |
| TRADING_REVIEW_OWNER_QUERY_PROXY_ENABLED | trading-review.owner-query-proxy.enabled, 기본 false. runtime 선택 시 함께 true 필요 |
| TRADING_REVIEW_OWNER_QUERY_RUNTIME_SNAPSHOT_FILE | trading-review.owner-query-runtime.snapshot-file, 준비할 절대 local 경로 |
| TRADING_REVIEW_OWNER_QUERY_RUNTIME_PREPARATION_TIMEOUT | trading-review.owner-query-runtime.preparation-timeout, 필수 ISO-8601 Duration·양수·최대60초 |
| TRADING_REVIEW_OWNER_QUERY_RUNTIME_DRAIN_TIMEOUT | trading-review.owner-query-runtime.drain-timeout, 필수 ISO-8601 Duration·양수·최대60초 |

runtime 미선택에 경로/예산 값만 있는 부분 설정은 거절한다. YAML 기본 empty optional 값은 미설정으로 취급하고 선택 상태에서 empty는 실패다.
선택된 mode의 typed 설정과 Runtime Bean 개수가 일치하는지도 검사한다. 파일 존재만으로 기능을 켜거나 실패 시 legacy DB 성공으로 fallback하지 않는다.
기존 proxy=true·runtime=false의 명시 fixture 조립은 기존 issuer/reader와 request-timeout을 요구하는 경로로 유지한다.

runtime=true는 **server.ssl.enabled=true의 direct Boot TLS 모드만 지원**한다. Core server certificate/keystore/password는 기존 Boot TLS 설정으로
별도 공급해야 한다. flag는 인증서/실제 배포 증거가 아니며 미완성 TLS 설정은 Boot server 준비에서도 실패한다. 외부 TLS termination 전용 mode는 추가하지 않았다.
Core .env를 properties로 가져오는 경우 Windows 경로는 `C:/protected/...` 또는 properties escaping을 사용한다. 실제 private 경로/자격을 예제로 커밋하지 않는다.

snapshot의 requestTimeout이 runtime 요청 예산의 정본이다. 기존 `trading-review.owner-query-proxy.request-timeout`도 지정하면 같은 Duration이어야 한다.
운영 timeout 수치는 측정해 별도 확정하며 fixture의 값을 기본값으로 복사하지 않는다. [파일 형식/접근 한계](OWNER_QUERY_PREPARATION_VERIFICATION.md)를 유지한다.
설정/신뢰 입력은 준비 후 요청에서 다시 읽지 않는다. Env 변경·원 파일 삭제 후에도 준비된 issuer/caller는 고정된 값을 사용한다.
준비는 DB 연결·등록/allow/ready setter·migration·ID/Compute 작업을 실행하지 않는다. 기존 Boot JPA/Flyway의 역할을 이 기능의 probe로 바꾸지 않는다.

## 시작과 authority 보안 경계

```text
mode/부분 설정 검증
  → single preparation budget·기존 snapshot/키/자격/CA 준비
  → Runtime owner + 4개 capability Bean + 기존 Proxy
  → singleton topology/selection 검사
  → Spring lifecycle start에서 read/authority admission OPEN
```

PREPARED/종료 상태의 read는 기존 OWNER_QUERY_UNAVAILABLE, 닫힌 authority는 기존 AUTHORITY_UNAVAILABLE 경로를 사용한다.
성공한 startup을 Member/owner 허용으로 캐시하지 않는다. 실제 authority 판정은 기존 Core primary Reader, resource 소유권/현재 Guard는 Go가 수행한다.
runtime flag는 Core의 기존 TR writer/worker를 중단하지 않는다. 실제 운영 전환의 전체 writer inventory·중단은 별도 gate다.

로컬 Boot 검사는 실제 Spring web context에서 authority adapter를 runtime 정의보다 먼저 발견하도록 구성해 유일한 chain/Controller와 현재 Browser401·CSRF403을 검사했다.
물리 listener의 실패/공개 시점·실제 TLS server·일반 Main의 network/DB 결합을 이 mock servlet context 검사로 완료 처리하지 않는다.

## 종료 상태와 예산

```text
OPEN
 → DRAINING_PROXY: 새 metadata read 거절, 진행 proxy의 authority callback은 허용
 → AUTHORITY_CLOSED: proxy 결과 수명 종료 후 새 authority 접수 거절, 진행 authority 사용자 대기
 → 남은 drain budget으로 client/timer shutdown/await
 → CLOSED: shutdownComplete=true 또는 false
```

read는 기존 async call 완료까지, authority는 실제 execute 반환/예외와 Reader 자원 정리까지 추적한다. lock은 카운터 전환에만 사용하고
network/DB I/O·completion callback을 잡은 채 유지하지 않는다. client close는 새 전체 예산을 시작하지 않으며 두 await에도 같은 남은 clock을 사용한다.
Prepared owner는 잘못된 close budget을 소유 상태 전환 전에 거절하므로 이후 정상 close가 가능하다. 기존 인자 없는 close API도 유지한다.

현재 Spring Boot4.1.0 라이브러리의 실제 phase를 로컬 class API/bytecode에서 대조했다.

| 대상 | phase |
|---|---|
| OwnerQuery Runtime | 2147482624, Boot graceful constant+1 |
| WebServerGracefulShutdownLifecycle | 2147482623 |
| servlet WebServerStartStopLifecycle | 2147481599 |

Spring은 큰 phase부터 멈춘다. 따라서 정상 경로는 resource 사용자 drain/client close → Boot connector stop → 공유 Bean destroy 순서다.
Spring lifecycle test에서는 실제 phase를 사용하는 connector marker와 공유 resource marker로 순서를 확인했다. HTTP 응답 직렬화는 별도 Boot servlet drain이 담당한다.
Bean destroy가 다시 close해도 idempotent다. constructor/context 실패 시에도 생성 client만 닫고 빌린 datasource를 직접 닫지 않는다.

예산 만료/interrupt/cleanup 실패는 shutdownComplete=false와 고정 ERROR `Owner query shutdown incomplete`로 기록한다.
stop callback은 완료해 Spring의 후속 종료를 진행하며 새 read/authority는 계속 거절한다. 이것을 성공한 join으로 보고하지 않는다.
중단에 협조하지 않는 JDBC/임의 callback을 강제 선점하거나 영원히 기다리지 않는다. 이 경우 Spring이 뒤이어 server/공유 datasource를 정리할 수 있으며,
진행 중 read의 정상 응답/물리 자원 join이나 process 전체 종료 상한을 보장하지 않는다. 자동 halt·exit code 변경은 추가하지 않았다.
운영자는 incomplete 로그/상태를 종료 실패로 다뤄야 하며 driver/pool/전체 종료와 복구 증거를 확보해야 한다.

실제 LB/readiness가 내부 callback 경로를 먼저 제외하면 JVM의 phase만으로 callback 도달을 보장할 수 없다.
고정 Core authority origin의 routing·배포 drain을 실제 topology에서 검증한다. 다른 Core 기능·readiness를 임의로 같이 차단하지 않는다.

## 수행한 검증

| 실제 검사 | 결과 |
|---|---|
| Windows 대상 App 검사 |60 pass/0 fail/0 skipped. Boot5·Runtime8·기존 preparation45·proxy2 |
| Boot context | 기본 비활성화·부분/잘못된 flag·TLS 미선택·예산 충돌·중복 capability·authority 누락 실패 cleanup·snapshot/env 고정 |
| 보안 등록 | runtime 늦은 발견 순서, authority401/400/no-store·browser401·POST CSRF403, startup DB 비접근 |
| Lifecycle | read 거절/기존 callback 허용·authority 자원 대기·정상 순서·repeat close·timeout zero remaining·미협조 authority·interrupt 실패 분리 |
| 경계 | 47개 Gradle 모듈 검사 성공 |

초기 classpath(SecurityFilterChain)·테스트 supplier/constructor 선택 오류를 수정한 뒤 위 대상을 다시 실행했다.
이전 Windows native listener 관측과 달리 이번 대상 실행의 기존 component TLS도 성공했지만 원인이 제거됐다고 일반화하지 않는다.
전체 PostgreSQL/legacy security 회귀·bootJar는 PR의 정확 head CI 결과로 별도 확인한다. 테스트 report gate·실제 필수 실행 조건은 유지한다.

## 남은 수용과 다음 범위

PCP-05/06은 선택·context/등록·보안 회귀, PCP-08은 정상/실패 수명 모델을 격리 검사했다.
PCP-07의 실제 primary pool 획득/SQL timeout·반환과 PCP-05/08의 listener/connector/callback 종료는 후속 일반 Main 서비스 수용에서 보완했다. 전체 body 취소·운영 topology로 확대하지 않는다.
PCP-10은 이번 조립의 DB 비접근/상태 변경 부재를 확인했으며 운영 Member/DDL 준비를 완료한 것은 아니다.
기존 test launcher의 11개 PASS를 이번 Root/운영 수용으로 바꾸지 않는다.

후속 [일반 Main 서비스 수용](OWNER_QUERY_MAIN_VERIFICATION.md)에서 PCP-11 및 관련 deadline/drain/trace/error를 실제 경로로 재검증했다.
E2E 전에 contract sync가 필요하다. rotation/fleet·실제 key/회원 초기화·DB history/ID·단일 writer·backup/restore·운영 활성화는 별도이며 NO_GO를 유지한다.
