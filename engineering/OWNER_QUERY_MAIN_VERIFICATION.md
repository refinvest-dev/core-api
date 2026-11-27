# Owner Query 일반 Main 서비스 수용

기준일: 2026-11-27. ADR-086의 두 metadata GET에 대해 일반 production bootJar와 Go cmd/api의 격리 수용을 완료했다.
Core source는 `090e8e608228a5aba54974a53403480dfa9eb6e0`, Go source는 `67e5e0df6885a2619b5c484a1f2ee5d58e7cd6cf`다.
[payload-free 실제 실행 증적](https://github.com/refinvest-dev/integration/blob/1557a610b6984fc9504cbb733cbad7b2e6bb6c92/evidence/owner-query-service/2026-11-27-main.json)은 code commit·binary/source SHA·16개 시나리오·cleanup을 기록한다.
운영 전환은 계속 **NO_GO**다. 기존 test-classpath launcher11개와 이번16개를 합산하지 않는다.

## 일반 실행과 worker 선택

`:app:bootJar`의 manifest는 일반 `com.refinvest.RefinvestApplicationKt`이며 test fixture class를 포함하지 않는다.
initializer·수동 Bean 선등록 없이 [opt-in Runtime](OWNER_QUERY_BOOT_VERIFICATION.md)과 strict snapshot을 사용한다.
Core/Go의 실제 security·issuer·authority·primary DB·owner/parent Guard를 연결했고 mock verdict는 사용하지 않았다.
loopback TLS 관측 중계기는 authority 원 요청과 실제 응답을 전달하고 callback 하나의 도착 시점만 제어한다.
운영 LB·routing 수용이나 직접 연결 topology의 증명으로 확대하지 않는다.

[spool sweeper](../tradingreview/adapter/compute/src/main/kotlin/com/refinvest/core/tradingreview/adapter/compute/NormalizationResultSpoolSweeper.kt)에
`refinvest.trading-review.normalization.delivery.spool-sweep-enabled` 조건을 추가했다. 미설정/true는 기존처럼 생성하고
false일 때만 중단한다. 자동으로 Runtime flag와 묶지 않는다. 기본 활성 동작은 변경하지 않았다.
fixture process는 해당 설정과 기존 Compute/삭제/보존 설정·raw key 미공급을 사용하며 여섯 legacy worker의 Bean 부재를 확인했다.
이것은 전체 Core TR writer 중단/업무 인계 증거가 아니다. 실제 write endpoint·외부 writer inventory는 별도다.

`beans,shutdown` Actuator 노출은 integration이 생성한 loopback process 설정에만 둔다. Core의 기본 health-only 노출과
browser JWT·CSRF 검증을 유지한다. 환경 파일·운영 secret은 수정하지 않았다.

## 실제 수용 결과

| 범위 | 실제 결과 |
|---|---|
| 일반 Main 조립 | Runtime 유일성·test fixture 부재·여섯 legacy worker 부재 |
| 기존11개 시나리오 | bigint metadata DTO·위조 identity 배제·소유권·Member/key 차단·오류·동시 callback·trace·no fallback 모두 PASS |
| Go SQL/브라우저 disconnect | 소유 record lock으로504와 요청 deadline 내 SQL 해제; 즉시 취소/durable job 취소로 해석하지 않음 |
| Core 실제 primary pool | Hikari2개 점유 중 추가 요청503, 기존 요청/lock 해제 뒤 정상 조회 |
| Core JDBC SQL | query timeout 뒤503, 대기 statement 부재·connection 재사용 |
| 정상 Spring 종료 | 새 read503 후 보류했던 실제 authority callback 처리·기존 read200, Runtime 완료→Boot servlet graceful→Hikari 종료 |
| 짧은 drain budget | 진행 read503·고정 incomplete ERROR, exit0을 성공 join으로 해석하지 않음 |
| 정리 | 두 Core 종료 뒤 식별 가능한 primary session0, 중계기/process 종료 후 소유 schema 제거 |

Core fixture request/connect/client shutdown/query budget과 primary pool은 의도적으로 작게 지정했다.
운영 timeout·pool 한도나 처리량/SLO의 권장 기본값이 아니다. Go 마지막 process cleanup은 여전히 강제 종료이며
Core 정상 drain 증적으로 섞지 않는다. 기존 Go 자원 수명 unit/integration 검사는 별도다.

정상 Runtime close에는 고정 INFO `Owner query shutdown complete`를 추가했다. 기존 false는 고정 ERROR
`Owner query shutdown incomplete`다. 로그는 key·Member·JWT·payload를 포함하지 않는다.
강제 종료에서 callback/driver 자원을 임의 선점하거나 process exit code를 바꾸는 기능은 추가하지 않았다.

## 검사와 남은 범위

로컬 `:app:bootJar`, Boot5·Runtime8·worker 선택1의14개 test, Gradle 모듈 경계 검사가 통과했다.
integration의4개 안전 검사와 `verify-contract-sync.ps1 -IncludeWeb`도 통과했다. IncludeWeb은 snapshot 검증이며 UI E2E가 아니다.
최초 개발 실행16개 PASS 뒤 source를 commit하고 최종 clean source에서 다시16개 PASS했다.
전체 Core/PostgreSQL 회귀는 PR의 정확 head CI로 확인하며 로컬14개에 합산하지 않는다.
최종 짧은 drain용 Core 재기동은 Windows10014 listen 실패1회 뒤 두 번째 startup에서 READY가 됐다.
실패 시도를 증적에 보존하며 Windows 원인이 해결됐다는 의미는 아니다.

PCP-05/07/08/11의 일반 Main·한정 primary pool/SQL·callback 종료를 이번 증적으로 보완했다.
PCP-09의 synthetic secret·격리 DB·비밀정보 비공개는 유지했다. PCP-12의 키/자격 교체·fleet/version skew·운영 장애/route 수용은 미실행이다. DB history·회원 backfill/allow·신뢰 ACL·
단일 TR writer 인계·backup/restore·운영 부하도 별도 gate다. Read metadata만의 수용을 쓰기/장기 작업 구현 완료로 확대하지 않는다.
다음은 해당 rotation/fleet/운영 전환 조건을 실제 소비자·배포 단위로 분리해 검토하는 단계다.
