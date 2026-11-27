# Owner Query 명시 preparation 구현·검증

기준일: 2026-11-27. [production 조립 계획](OWNER_QUERY_PRODUCTION_COMPOSITION.md)의 1번 범위다.
Core base는 `c9bd52f577d2114898dc64d25c8ac9fb39d9e14f`, 구현 commit은 `9b5e68a0d2055cca2f313fee466ed1f5596dc4db`다.
제품 정책은 Context ADR-086을 유지한다. production Bean/route 등록·운영 key·Member/DDL 인계는 포함하지 않는다.

## 코드와 API

| 파일 | 책임 |
|---|---|
| [Preparation](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryPreparation.kt) | 명시 호출·기존 issuer/authority/client 조립·소유 client cleanup |
| [Snapshot](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQuerySnapshot.kt) | closed JSON·typed budget·RSA/PEM/CA/credential 전체 검증 |
| [File](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQuerySnapshotFile.kt) | default filesystem·절대 경로/NOFOLLOW·handle 크기와 bounded read·전후 metadata |
| [오류/budget](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryPreparationException.kt) | INPUT/SNAPSHOT/CAPABILITIES/DEADLINE/CLEANUP, monotonic 남은 예산·interrupt 확인 |
| [테스트](../app/src/test/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryPreparationTest.kt) | synthetic key·strict mutation·파일 수명·생성 실패/정리·실제 TLS·OS별 link |

```kotlin
val prepared = OwnerQueryPreparation.prepare(
    snapshotFile, primaryDataSource, currentMemberIdProvider, clock, preparationBudget,
)
// 다음 Boot 연결 단계에서 inbound capability와 reader를 주입한다.
// 실제 owner는 먼저 ingress/admission과 진행 요청을 정리한 뒤 호출한다.
prepared.close()
```

함수는 환경 파일/변수를 읽지 않고 caller의 명시 인자만 사용한다. singleton Bean·component·route/listener를 등록하지 않는다.
반환값은 issuer·authority check·authority caller·reader·requestTimeout을 제공하며 자체 출력은 redacted다.
Primary DataSource는 빌려 쓰고 준비 중 연결·DB query·DDL/DML·Member 허용·ID 발급·업무 token/Compute 제출을 수행하지 않는다.
RSA key pair의 로컬 서명 self-check는 기존 구현을 사용한다. 사용자 authority 허용/probe가 아니다.

## 로컬 snapshot 형식

64 KiB 이내 UTF-8 JSON object이며 아래 필드가 모두 필수다. 중복/unknown/case alias·null·type 변환·추가 JSON·잘못된 UTF-8/Unicode를 거절한다.
Go의 신뢰 파일과 다른 Core 로컬 형식이다. public API/위임 claim·hash profile은 바꾸지 않는다. 아직 env/property binding은 없다.

| 필드 | 계약 |
|---|---|
| issuer / signingKid / privateKey | 현재 발급 identity와 PKCS#8 RSA PRIVATE KEY PEM. private key는 Core에만 공급 |
| keys | 1~32개의 issuer/kid/publicKey object. PKIX RSA PUBLIC KEY PEM·identity 중복 금지·현재 발급 key 포함 |
| goOrigin / goCredential | 고정 HTTPS origin, path는 비어 있거나 `/`. query/fragment/userinfo 금지. URL-safe opaque credential 43~128자 |
| goRootCAs | 1~32개 CA certificate의 PEM chain. 중복·CA 아닌 certificate·추가 junk 거절, explicit trust store 구성 |
| authorityCredentials | Go→Core credential 1~2개, 중복 금지·goCredential 재사용 금지. entropy 생성은 공급자 증거가 필요 |
| requestTimeout / connectTimeout / shutdownTimeout | ISO-8601 Duration 문자열, 각 양수·최대60초. connect≤request. 운영 기본값을 새로 정한 것이 아님 |
| queryTimeoutSeconds | 양수 int, 초 단위. request budget보다 크면 거절. fraction/overflow를 절삭하지 않음 |
| maxInFlight | int 1~256, client의 논리 admission 한도 |

RSA 2048~4096/E65537·private/public pair proof는 기존 security adapter를 사용한다. PKCS#8/PKIX는 재인코딩과 비교해 trailing DER을 거절한다.
PEM 줄바꿈은 LF/CRLF와 앞뒤 빈 줄을 허용하며 base64 원문은 canonical padding을 검사한다. CA의 실제 server chain/hostname 검증은 TLS가 수행한다.
보호된 배포·CA 수명/회수·key/credential 등록과 fleet 배포 승인은 파일 decode 성공으로 대신하지 않는다.

입력을 한번 읽어 준비한 값만 요청에 사용한다. 파일/env 재조회·hot reload·discovery·자동 key 생성은 없다.
읽은 byte buffer/private DER 임시 배열 일부는 사용 뒤 지우지만 JVM String/crypto provider의 전체 heap 지움을 보장하지 않는다.

## 준비 실패와 소유권

하나의 preparation budget을 파일 접근→decode→key proof/CA·자격 검증→기존 capability→client 생성 후 확인까지 적용한다.
실제 I/O/암호 연산의 강제 선점은 보장하지 않는다. request/cleanup budget과 분리하며 단계마다 전체 preparation timeout을 다시 부여하지 않는다.
모든 snapshot 검증 뒤 마지막에 client를 만들고 만료/실패하면 생성 client만 닫는다. DataSource는 close하지 않는다.
`PreparedOwnerQuery.close()`는 한번만 client close를 호출하며 cleanup 실패는 안전한 CLEANUP stage로 보고한다.
원 오류 cause/suppressed·경로·PEM·credential을 전파하지 않는다. 준비/정리 중 InterruptedException은 안전한 stage로 변환하면서 thread interrupt 상태를 복구한다.

이 owner는 ingress drain·authority callback 수명을 관리하지 않는다. 기존 client의 종료 budget/await 동작을 그대로 사용하며
전체 process 종료의 단일 wall-clock budget을 구현한 것으로 해석하지 않는다. Boot root의 소유권/순서와 만료 cleanup은 다음 단계다.

## 파일 접근의 실제 보장

default filesystem의 정규화된 절대 경로와 regular file을 요구하고 UNC·관측된 symlink/reparse/other 경로를 거절한다.
file channel을 한번 열어 handle 크기를 검사하고 최대64 KiB만 읽는다. 전후 생성/수정 시각·크기·정규 경로와 제공되는 fileKey를 비교한다.
Windows JDK의 BasicFileAttributes는 정상 파일에도 fileKey=null을 반환하는 것을 새 synthetic 파일로 확인했다.
이 경우 metadata 검사를 객체 동일성 증명으로 승격하지 않는다. fileKey를 제공하는 플랫폼에서는 값의 변화도 거절한다.

이 portable 구현은 open handle의 파일 identity를 직접 검증하는 API나 경합 없는 ancestor containment를 제공하지 않는다.
보호된 부모/mount/ACL이 필요하며 symlink/regular-file의 ABA 교체·hardlink/원 소유권·network mount를 완전히 검출했다고 주장하지 않는다.
경로/metadata 확인과 실제 open 사이의 경합, blocking syscall·fleet 교체 검증은 운영 활성화 조건이다.

## 실제 실행과 미실행

| 검사 | 결과 |
|---|---|
| Kotlin test compile·module boundaries | 성공, 47개 모듈. 후속 대상 test 실행에서도 최종 코드가 컴파일됨 |
| Windows 대상 App test | 47개 중45 success·1 fail·1 skipped. 신규 suite45개는43 success·실제 TLS listen1 fail·Linux 전용 symlink1 skipped. 기존 proxy configuration2개 success |
| listener 실패 | 새 TLS server 준비에서 `java.net.SocketException: Bad address: listen`. source 파일 삭제 후 실제 HTTP 성공으로 계산하지 않음 |
| 기존 auth/application·security·reviewapi 회귀 명령 | BUILD SUCCESSFUL, 세 test task 모두 UP-TO-DATE. 새 실행 test 개수로 합산하지 않음 |
| 파일/정리/불변 snapshot | Windows junction 거절·파일 삭제 후 issuer/caller·byte mutation·만료 후 생성 client close·대여 DataSource 비접근·안전한 cleanup 오류 확인 |
| 초기 실패 | fixture TempDir 초기화, Windows fileKey null을 무조건 거절하던 판정을 수정. 이전 실패를 pass로 합산하지 않음 |

표의 Windows App 대상은 초기 구현 commit의 실행이다. 후속 interrupt 복구는 별도 집중 test 1개와 모듈 경계 검사를 통과했다. 전체 최종 source 실행은 PR head CI로 확인한다.

새 실제 TLS와 Linux symlink는 PR의 **정확 head Linux CI/해당 XML**로 별도 확인한다. 기존 11개 서비스 E2E는 이전 source/test launcher의 증거이며
이 preparation의 일반 Core Main 수용·키 교체/fleet/운영 수용으로 재사용하지 않는다. CI/PR merge도 운영 활성화 승인은 아니다.

## PCP 상태와 다음 단계

| 범위 | 현재 증거 | 계속 남은 조건 |
|---|---|---|
| PCP-01/02 | strict mutation·key pair·typed budget·byte/file snapshot·signer/caller 단위 | 실제 승인 input·env/property binding·production 배포 |
| PCP-03 | 만료/실패 소유 cleanup·대여 DataSource 보존·OS별 정적 link 검사 | filesystem 경합/ACL·전체 runtime 종료/강제 선점 보장 없음 |
| PCP-04 | 기존 signer/caller/client 조립, Kotlin 서명·실패/TTL·component TLS test 추가 | 실제 현재 authority→Go Guard의 일반 Main 경로·운영 entropy/배포 증거 |
| PCP-09 | generated key/CA·임시 파일·mock DB/loopback TLS, 사용자/환경 파일 보존 | 운영 사용자/secret·DB를 test fixture로 사용하지 않음 |
| PCP-05~08/10~12 | 이 변경에서 미실행 | 실제 Boot Bean/security chain·부분 구성·callback/drain·일반 Main·rotation/fleet 수용 |

다음 구현 단위는 production Boot의 **opt-in 조립·authority chain 등록·자원 lifecycle**이다.
기본 proxy 비활성화를 유지하며 회원 초기화/DDL·TR 단일 writer·키 운영 gate를 별도 확인한다. 운영 전환 판정은 NO_GO를 유지한다.
