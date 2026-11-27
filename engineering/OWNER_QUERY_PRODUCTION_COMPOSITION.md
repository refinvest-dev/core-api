# Owner Query Core production 조립 계획

기준일: 2026-11-27. **상태: loader/명시 preparation·opt-in Boot 조립/lifecycle 구현·격리 검증.** [preparation](OWNER_QUERY_PREPARATION_VERIFICATION.md)·[Boot 검증/제약](OWNER_QUERY_BOOT_VERIFICATION.md)을 따른다. 일반 Main 서비스 재수용/운영은 별도다.
대상은 ADR-086의 Account·Book metadata 단건 GET 두 개다. 기존 제품 정책을 적용하며 목록·쓰기·Worker 권한을 추가하지 않는다.
[Context ADR-086](https://github.com/refinvest-dev/context/blob/8f00652381ef3c81b9fd11b7e4e452c8f98c451c/docs/TRADING_REVIEW_OWNER_QUERY_AUTH_POLICY.md)이 제품 정본이다.
이 문서는 Core의 조립 구현 순서와 검사 기준을 소유한다. DB migration·회원 허용·실제 secret 공급·route 운영 전환은 별도 판정이다.

## 현재 사실과 source

Core 기준은 [#151 병합 commit](https://github.com/refinvest-dev/core-api/commit/92e6aee4664b2a0f5c967c9027595143960dc07d),
Go는 [#127 병합 commit](https://github.com/refinvest-dev/trading-review-api/commit/9cc9d47b2abf7ec72217442be5e6321914704fd5),
integration은 [#64 병합 commit](https://github.com/refinvest-dev/integration/commit/8b689e6d52ba1458863f49d64d55e3b11232c9dc)이다.
최종 [서비스 증적](https://github.com/refinvest-dev/integration/blob/9e0519160d3f7066494547eaa2c79cd29e848ec3/evidence/owner-query-service/2026-11-27.json)은
Go `23c1216`·Core `e6f13be`·harness `5211525`의 clean source에서 실제 HTTP/TLS/DB 시나리오 11개 PASS를 기록한다.
그 뒤의 merge commit이나 main CI를 실행 당시 source/binary로 바꿔 기록하지 않는다.

| source | 현재 제공하는 것 | production 조립에서 필요한 것 |
|---|---|---|
| [Core Main](../app/src/main/kotlin/com/refinvest/RefinvestApplication.kt) | 일반 Spring Boot component scan·scheduling | 기능별 명시 준비·실패·수명 연결 |
| [ProxyConfiguration](../app/src/main/kotlin/com/refinvest/core/config/OwnerQueryProxyConfiguration.kt) | enabled 기본 false, issuer/reader·request timeout을 요구하는 proxy | 실제 issuer/reader 공급과 중복/부분 구성 거절 |
| [test launcher](../app/src/test/kotlin/com/refinvestfixture/ownerquery/OwnerQueryServiceFixture.kt) | 실제 컴포넌트의 synthetic 조립·fixture worker 배제 | production source로 복사하지 않고 좁은 조립 API로 대체 |
| [signing key](../auth/adapter/security/src/main/kotlin/com/refinvest/core/auth/adapter/security/delegation/OwnerQuerySigningKey.kt) | RSA key pair 복사·서명/검증 self-check | 보호된 입력·전체 snapshot 준비·발급 key 선택 |
| [authority verifier](../auth/adapter/security/src/main/kotlin/com/refinvest/core/auth/adapter/security/authority/NimbusAuthorityDelegationVerifier.kt) | 최대 32개 issuer/kid public key의 불변 복사 | 발급 key를 포함하는 whole-set 공급 |
| [authority reader](../auth/adapter/persistence/src/main/kotlin/com/refinvest/core/auth/adapter/out/persistence/JdbcMemberAuthorityReader.kt) | primary·한 statement 판정, 외부 Tx 거절·query timeout | 명시 primary datasource, 연결 획득/쿼리/요청 전체 budget 대조 |
| [authority security chain](../auth/adapter/web/src/main/kotlin/com/refinvest/core/auth/adapter/web/authority/MemberAuthoritySecurityConfiguration.kt)·[Controller](../auth/adapter/web/src/main/kotlin/com/refinvest/core/auth/adapter/web/authority/MemberAuthorityController.kt) | 두 inbound Bean 존재에 따른 조건부 등록 | 실제 Boot 등록 순서·단일 chain·CSRF 분리 검사 |
| [HTTP client](../tradingreview/adapter/reviewapi/src/main/kotlin/com/refinvest/core/tradingreview/adapter/reviewapi/OwnerMetadataHttpClient.kt) | async body 포함 deadline·cancel·유한 close | ingress drain 이후 close, 준비 실패 소유 자원 정리 |

fixture는 initializer가 Bean을 미리 등록한다. 이것의 성공으로 production component scan의 `@ConditionalOnBean` 평가 순서가
동일하다고 추정하지 않는다. default proxy 비활성화·Go health-only는 유지한다.

## 조립 경계와 파일 단위

`app`이 환경 선택·신뢰 입력·생성 자원의 소유자다. auth의 기존 Application/Adapter와 Trading Review의 기존 proxy를 생성해
각 feature의 inbound/outbound 계약으로 연결한다. auth adapter가 Trading Review application을 import하거나 Controller가
security/persistence 객체를 직접 생성하게 하지 않는다. 공통 DI registry나 모든 계층의 module 파일을 추가하지 않는다.

첫 구현은 `app/config/ownerquery/`에 설정 값·snapshot loader·명시 preparation을 응집한다. 실제 production 연결 시에만
Spring configuration/lifecycle을 추가한다. 파일은 설정/검증, 준비/정리, Spring 연결의 읽기 단위로 나누며 키/claim마다 패키지를 만들지 않는다.
Kotlin `data class`의 자동 `toString`에 private key/credential을 담지 않는다. 준비된 값은 private field·redacted 출력·복사 경계를 가진다.

```text
고정된 Core 설정 + 보호된 local 입력
  → strict decode / key·credential·CA·budget 검증
  → PreparedOwnerQuery (명시적 준비 결과; 요청 중 파일/env I/O 없음)
     ├→ auth IssueOwnerQueryDelegationUseCase
     ├→ auth CheckMemberAuthorityUseCase / AuthenticateAuthorityCallerUseCase
     └→ TR OwnerMetadataReader (생성한 client의 유일한 lifecycle owner 지정)
  → 기존 Proxy service / 두 GET Controller / authority 전용 security chain
```

loader/preparation의 실제 API·파일 형식은 [구현 기록](OWNER_QUERY_PREPARATION_VERIFICATION.md)을 따른다. 나머지 Boot root/lifecycle 이름은 구현 우선안이다. getter는 준비된 snapshot만 반환한다.
역직렬화가 완료되기 전에 Bean·client를 부분 등록하지 않는다. 별도 trust format의 Go/Core runtime 공용 library는 만들지 않는다.

## 설정과 신뢰 입력 우선안

기존 `trading-review.owner-query-proxy.enabled=false`와 명시 `request-timeout`을 유지한다. 첫 구현은 **호출자가 명시 호출하는
preparation**까지 제공하며 production Bean 자동 등록은 후속으로 나눈다. 파일의 존재가 기능 선택을 대신하지 않는다.
자동 연결 때 사용할 신뢰 파일 property 이름/설정 binding은 그 PR에서 정하며, 여기의 필드를 이미 지원하는 env/CLI로 안내하지 않는다.

초기 공급은 보호된 절대 local 경로의 단일 UTF-8 JSON object를 기술 우선안으로 삼는다. 최대 64 KiB, unknown/duplicate/case 차이·
null·잘못된 타입·추가 JSON·잘못된 Unicode를 거절하고 bounded read로 크기를 확인한다. 상한은 Core 구현 자원 한도이며 새 wire 정책이 아니다.
파일은 한번 열어 읽은 bytes에서 전체를 준비한다. 참조 파일을 이어 읽거나 URL/discovery·env 재조회·부분 reload를 하지 않는다.
JCA software RSA의 현재 지원 범위만 사용한다. KMS/HSM/자동 secret 생성은 추가하지 않는다.

| 의미 단위 | 준비 입력과 검증 |
|---|---|
| 발급 | issuer·현재 signing kid·PKCS#8 private key. PKIX public key는 아래 검증 set에서 정확히 하나 선택하고 `OwnerQuerySigningKey.prepare`로 pair proof |
| 검증 | issuer/kid/PKIX RSA public key 1~32개. 중복 identity·현재 signing key 누락/불일치 거절, RSA 2048~4096/E65537 유지 |
| Core→Go | 고정 HTTPS origin·현재 outbound credential 하나·명시 root CA PEM. credential getter는 준비된 고정 값 |
| Go→Core | authority caller의 credential 1~2개. 기존 opaque verifier의 형식·중복 검사를 사용하고 양 방향 자격 재사용 거절 |
| 예산 | proxy request·connect·client shutdown·authority JDBC query timeout·preparation 전체 budget을 명시. 서로 단위가 다른 값을 묵시 절삭하지 않음 |
| 처리 한계 | 기존 client의 maxInFlight 1~256. 논리 admission 한도이며 TCP socket/thread의 상한으로 보고하지 않음 |

private key는 Core에만 둔다. Go에는 검증 public key와 Go 자체 TLS server key만 공급한다. browser HS256/refresh·Compute 자격을
재사용하지 않는다. 형식 검사는 entropy 생성의 증거가 아니므로 운영 공급자가 안전한 생성/배포 근거를 별도 보유한다.
현재 Core TLS server certificate/keystore는 기존 Spring server 설정의 소유다. 새 조립 파일을 TLS termination 설정/배포 증거로 쓰지 않는다.
Go origin의 hostname 검증·명시 CA와 Core authority endpoint의 TLS 보호는 양쪽에서 별도로 확인한다.

절대 경로/regular file·크기 검사만으로 부모 ACL·symlink 교체 경합·플랫폼별 안전한 handle 접근이 증명되지는 않는다.
첫 loader 구현은 지원 OS와 link/reparse point 처리·open handle 기준 검사 및 남는 경계를 명시하고, 보호된 부모 mount/ACL을 활성화 조건으로 둔다.
구현할 수 없는 filesystem I/O 강제 선점을 timeout 성공으로 보고하지 않는다. 원 오류·경로·PEM·credential을 exception/cause에 담지 않는다.

## 시작과 준비 실패

1. 실제 소비자가 요청한 기능·입력을 고정한다. 미선택이면 key/client/authority Bean을 만들지 않는다. 자동 연결 단계에서는
   disabled+파일만 있는 부분 구성도 명시 거절할지 검토하고 context test로 기존 default 회귀를 확인한다.
2. 하나의 유한 preparation parent에서 decode→RSA/CA/자격 검증→기존 capability 생성을 수행한다. parent의 남은 예산으로
   단계별 상한을 제한한다. JVM thread/Future cancel로 임의 I/O/암호 연산의 즉시 종료를 보장한다고 주장하지 않는다.
3. DataSource는 빌려 쓰고 조립 객체가 close하지 않는다. test·실제 배포의 routing datasource가 primary를 가리킨다는 것을 명시한다.
   실제 request는 Reader가 primary·READ COMMITTED를 다시 검사하며 startup 성공/DB ready를 사용자 허용으로 캐시하지 않는다.
4. auth의 두 inbound와 issuer·reader가 모두 준비된 뒤 등록한다. primary schema readiness나 Member 허용을 얻기 위해 token을 만들거나
   사용자 authority 요청을 probe로 보내지 않는다. 준비 중 DDL/DML·회원 일괄 허용·자격/키 등록을 하지 않는다.
5. 실제 Boot root에서 listener/serving 공개 전 전체 연결을 검사한다. unknown/duplicate Bean·authority chain 누락·부분 준비는
   startup 실패다. 일반 `@DependsOn` 이름 나열만으로 security condition 평가/등록 순서가 해결됐다고 보지 않는다.
   Bean definition/import 순서와 실제 filter chain/controller 매핑을 검증하는 구체 방식을 연결 PR에서 구현한다.
6. 실패 시 그 시도에서 생성한 client/timer 등을 닫고 미공개 결과를 반환한다. 실패 원인은 stage/code로 제한한다.
   caller가 빌려준 datasource/Clock/외부 signer owner를 임의 종료하지 않는다.

preparation 성공과 production startup 성공, 실제 activation 승인은 별개다. authority schema/ready 미준비는 기존 요청에서 503이며,
access row 부재는 inactive다. 준비 함수가 이 둘을 자동 초기화하거나 default allow로 바꾸지 않는다.

## 요청 budget과 종료 수명

Core proxy는 현재 인증 thread에서 위임을 발급한 뒤 async read를 시작하며 DB Tx/lock을 잡고 Go를 기다리지 않는다.
authority는 primary 조회만 하고 Go/public proxy를 역호출하지 않는다. TTL60/leeway5·표준 오류/trace 정책은 유지한다.
운영 timeout 수치는 실제 callback의 연결·body·DB 획득/쿼리·취소 측정 후 정하며 fixture의 2/3초를 기본값으로 복사하지 않는다.
JDBC `setQueryTimeout(seconds)`는 connection acquisition timeout이나 HTTP 전체 취소가 아니다. 별도 datasource/pool 예산과 정리 지연도 검사한다.

종료의 목표는 **외부 신규 ingress 중단 → 이미 시작한 servlet async/proxy와 authority 요청 drain/join → 생성 client 종료 → 공유 datasource 종료**다.
독립 authority 서버를 먼저 내리면 이미 시작된 proxy→Go callback을 깨뜨릴 수 있으므로, ingress stop과 callback drain의 순서를 실제 topology로 검증한다.
단일 connector를 먼저 닫는 방식은 callback까지 거절할 수 있다. 배포/LB 분리 admission 또는 유한 실패 처리 전략을 shutdown PR에서 입증한다.

현재 `OwnerMetadataHttpClient.close()`는 active 요청을 실패 완료시키고 client/timer를 shutdown/await한다. ingress graceful drain을 대신하지 않는다.
또한 client await와 timer await에 각각 같은 shutdownBudget을 적용하므로 그것을 하나의 wall-clock cleanup 상한이라고 보고하지 않는다.
후속 owner는 전체 잔여 예산을 제한하고 idempotent 종료·부분 준비 실패를 검증해야 한다. Spring Boot 기본값·Bean 파괴 역순에 대한 추정으로
순서를 보장하지 않는다. drain 미완료 시 bounded cancel/오류 보고·최종 process 종료를 구분하며 성공으로 기록하지 않는다.

## 회원·DB·단일 writer 경계

| 항목 | 현재 사실 | 활성화 전에 필요한 증거 |
|---|---|---|
| 권위 DDL | [candidate SQL](../auth/adapter/persistence/src/main/resources/sql/member-authority-candidate.sql), 자동 Flyway 미등록 | 실제 target/history·Core DDL authority·backup/restore·권한·적용 검토 |
| Member 초기화 | register는 기본 차단/revision1; 기존 허용 덮어쓰기 없음 | signup 동일 Tx 등록·명시 승인, 기존 회원의 근거 있는 backfill·재실행/rollback·누락 거절 |
| caller/key 제어 | primary Store CAS·차단/철회 capability | 최소 권한·감사·준비된 제어 소비자, 원자 commit/복원·키 배포 확인 |
| Go 데이터 | Account/Book fixture 조회 수용 | 실제 TR relation/history·legacy ID/FK·owner/삭제 Guard와 read 권한 보존 |
| Core TR 중단 | fixture만 6개 worker 타입 부재 검사 | 운영의 HTTP command·scheduler·수동/재시도/시작 hook·외부 저장소 writer 전체 inventory와 중단 증거 |
| rollback | 두 GET은 durable admission을 만들지 않음 | 읽기 중계 철회와 향후 Go write 뒤 복구 구분, 권위 차단/철회 상태 보존 |

`refinvest.compute.enabled=false`만으로 Core TR 중단이 성립하지 않는다. 현재
[spool sweeper](../tradingreview/adapter/compute/src/main/kotlin/com/refinvest/core/tradingreview/adapter/compute/NormalizationResultSpoolSweeper.kt)는 조건 없이 등록되며,
[삭제 worker](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingDeletionWorker.kt)와
[expiry](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingImportExpiryScheduler.kt)·
[raw retention](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingRawRetentionWorker.kt)은 기본 활성 조건이다.
[raw orphan worker](../tradingreview/adapter/storage/src/main/kotlin/com/refinvest/core/tradingreview/adapter/storage/TradingRawOrphanWorker.kt)는 storage 설정에 의존한다.
fixture의 Bean 제거를 production에 그대로 옮기지 않는다. 기존 Controller의 나머지 write 경로 차단/미이관 route의 consumer 동작까지
관련 계약과 대조해야 한다. 새로운 public 오류/status를 이 기술 문서에서 정하지 않는다.

Core는 인증·Member authority·중계를 위해 계속 동작하며 기존 TR 업무 writer는 중단한다. 이중 쓰기 전환 프로토콜은 추가하지 않는다.
새 조회 기능은 Snowflake를 발급하지 않는다. 조회 활성화에 신규 Create의 allocator seed를 불필요하게 요구하지 않되,
실제 legacy ID 보존·FK·TR 단일 writer·백업/복원 조건을 생략하지 않는다.

## 교체·철회와 재시작

첫 조립은 불변 snapshot과 drain/restart를 사용한다. 신뢰 파일 변경 자체는 runtime 반영이 아니다.
새 kid 발급 전 Core authority verifier와 모든 serving Go verifier에 public key를 먼저 공급/확인한다. 확인되지 않은 node는 serving에서 제외한다.
구 key는 발급 중단 뒤 VERIFY_ONLY 창을 확보하고 TTL·양방향 skew·진행 요청 예산을 고려해 retire한다. 고정 75초를 운영값으로 정하지 않는다.
credential overlap은 같은 caller의 최대 두 자격이며 privilege 확대가 아니다. Core outbound 선택과 Go 수락 set,
Go authority outbound 선택과 Core 수락 set을 각 방향에서 확인한다.

긴급 철회는 Core authority store commit·추가 발급 중단·verifier/credential 전파를 조율한다. key 파일 교체만으로 즉시 철회를 완료 처리하지 않는다.
교체 실패는 기존 serving 프로세스의 검사된 snapshot과 신규 프로세스 시작 실패를 구분한다. 손상된 신규 입력을 이전 key/환경으로 자동 fallback하지 않는다.
fleet 확인/retire 시각·긴급 중단·복원은 운영 리허설 증거가 필요하다.

## 다음 구현 단위와 수용

| 순서 | 다음 PR 범위 | 필수 검사 | 해당 PR 밖의 일 |
|---|---|---|---|
| 1 | Core 전용 strict snapshot loader + 명시 preparation/소유 cleanup | PCP-01~04·PCP-09, 기존 issuer/authority/client 회귀 | production Bean/route 등록·live key·DDL·Member 허용 |
| 2 | 실제 Core root의 opt-in Bean·authority chain·lifecycle 연결 | PCP-05~08·PCP-10, 기존 browser/CSRF·disabled startup | 운영 enable·일괄 회원 허용·DB migration/인계 |
| 3 | 일반 Core Main/실제 Go Main의 격리 서비스 재수용·교체 장애 | PCP-11~12, contract sync 선행·정확 실행물/cleanup 증적 | 운영 권한/키/회원·DB/단일 writer·전체 부하 승인 |

아래 ID는 Core 구현 수용 계획의 로컬 ID다. Context의 TR-ACC Scenario를 새로 만들거나 기존 11개 fixture 결과를 확대하지 않는다.

| ID | 통과해야 하는 조건 |
|---|---|
| PCP-01 | strict JSON/UTF8·크기/개수/단위·key pair·현재 key 누락·중복·CA·양 방향 자격 재사용 거절, 민감 정보 출력 없음 |
| PCP-02 | 파일/env 변경·원 컬렉션 mutation 뒤 snapshot 불변; getter에 I/O 없음, 사라진 준비 파일 없이 요청/서명 가능 |
| PCP-03 | 단계별 실패/만료에서 부분 결과 미공개·생성 client 종료·빌린 datasource 보존; 지원 OS 파일 접근의 실제 보장/제약 기록 |
| PCP-04 | TTL/trace/오류 profile·서명 상호운용·현재 primary authority/owner Guard·client API 회귀, 형식 검사와 실제 entropy 공급 구분 |
| PCP-05 | disabled는 기존 browser/auth/CSRF·두 GET 회귀; enabled 부분/중복 Bean·잘못된 snapshot은 listener 공개 전 실패 |
| PCP-06 | 일반 Boot root에서 authority Controller/전용 chain을 정확히 하나 등록; 브라우저 CSRF 전역 제외·legacy 성공 fallback 없음 |
| PCP-07 | authority DB 연결 획득/쿼리·body 지연에서 전체 request budget·bounded cancel·slot/timer/connection 반환, Tx 밖 callback |
| PCP-08 | 진행 proxy→Go→Core callback이 있는 정상 drain·timeout·반복 close·준비 실패에서 자원 종료 순서와 전체 유한 시간 측정 |
| PCP-09 | 테스트는 synthetic secret·loopback integration DB의 소유 schema만 사용; env/키/keystore·실제 identity를 커밋/공개하지 않음 |
| PCP-10 | production 조립은 register/allow/ready setter·DDL·ID 발급·Compute 제출을 probe로 실행하지 않음; missing state/DB 장애 안전 거절 |
| PCP-11 | test initializer의 Bean 등록 없이 일반 Core Main과 Go cmd/api로 기존 11개 서비스 시나리오 재수용, schema/process 정리 |
| PCP-12 | 새/구 kid·두 방향 credential 순차 교체·잘못된 신규 입력·권위 차단 commit 전후·미확인 node 제외를 synthetic fleet에서 검증 |

위 1번의 loader/명시 preparation과 PCP-01~04/09 일부를 [구현·격리 검증](OWNER_QUERY_PREPARATION_VERIFICATION.md)했다. Windows TLS listener 실패·OS별 제외와 portable 파일 접근 한계를 구분한다. 새 production root·fleet/운영 조건은 미검증이며 기존 서비스 E2E 11개로 대체하지 않는다.
현재 운영 전환 판정은 **NO_GO**다. 위 2번은 [Boot 조립/security chain·lifecycle](OWNER_QUERY_BOOT_VERIFICATION.md)을 구현·격리 검증했다. 다음 기본 작업 단위는 위 3번의 일반 Core Main·Go 서비스 재수용이다. 실제 driver/pool·connector·운영 수용을 별도로 확인하며 제품 정책 재채택을 요구하지 않는다.
