# Owner Query 운영 인계와 후속 구현의 분리

기준일:2026-11-28. 대상은 ADR-086의 Account/Book metadata 단건 GET 두 개다.
일반 Main·snapshot 교체/fleet·[transport 검증](OWNER_QUERY_TRANSPORT_VERIFICATION.md)의 격리 수용과 실제 운영 적용을 구분한다.
현재 운영 판정은 **NO_GO**다. 아래 역할은 증거 제공 책임의 분류이며 실제 담당자/배포 target이 배정됐다는 뜻이 아니다.
이 문서는 운영 secret/환경 값·새 API 오류·제품 정책을 정하거나 서버를 활성화하지 않는다.

## 현재 범위

| 구분 | 현재 사실 | 다음 단위 |
|---|---|---|
| 두 metadata GET의 기술 검증 | synthetic key로 실제 위임 검증/authority/Guard·주요 deadline/slot/socket·정상/강제 종료·trust 구성 세대 검증 | 실제 배포 topology·입력/자원/SLO·운영 증거 |
| 실제 read-mode 배포 | target/권한·회원 초기화·전체 TR writer 중단·restore 인계 미수집 | 아래 OQA 항목에 실제 근거를 연결하고 운영 판정 |
| 후속 in-process 업무 | Go의 Account/Book·CreateImportSession/원 replay는 명시 범위에서 구현돼 있음 | 기존 Go 설계·현재 코드와 필요한 상태/Use Case를 대조해 다음 기능 범위 선택 |
| 생성/목록/Import HTTP·Worker | 두 GET의 read scope로 확대할 수 없음 | 기능별 위임/권한·Member/Guard·wire/오류/consumer 계약과 실제 수용을 먼저 한정 |

운영 target을 아직 정하지 못했다고 이미 허용된 격리 Domain/Store 작업까지 중단하지 않는다. 반대로 기술 CI·문서 완료를
새 업무 HTTP scope/route·Worker job·운영 승인으로 바꾸지 않는다. ADR-084 CreateImportSession·ADR-085 pin 원칙을 재승인받지
않되 미채택 profile·상태/consumer·실행 범위와 구분한다. 실제 다음 업무는 [Go 구현 현황](https://github.com/refinvest-dev/trading-review-api/blob/e4086b355df2cd8e29e5518c27b86645de3f491e/engineering/IMPLEMENTATION_STATUS.md)에서 출발한다.

## 실제 운영 자료가 필요한 항목

| ID / 증거 제공 역할 | 확보할 사실·검사 | 현재 판정 |
|---|---|---|
| OQA-01 / 배포 운영·Core/Go | 실제 build/ref·node inventory·ingress/authority origin·선택 flag·보호 mount/ACL·TLS 종료 위치 | 실제 배포 자료 미수집. fixture source/hash를 운영 node의 증거로 대체하지 않음 |
| OQA-02 / 보안·Core/Go | issuer/kid·키 보관/공급/폐기·분리된 양방향 자격·CA·회전/긴급 철회·미확인 node 제외 | 같은 build의 synthetic 순차 교체까지 검증. 실제 secret·KMS/ACL·fleet 전파/시계차/CA 교체는 별도 |
| OQA-03 / DB 운영·Core/Go | 실제 DB/schema/history/checksum·TR table/FK/owner/ID·접속 role/최소 권한·설치/upgrade/복구 계획 | 운영 catalog/history/권한을 조사하지 않음. fixture의 source DDL 적용은 실제 migration 인계가 아님 |
| OQA-04 / Core 인증·Member 운영 | signup 같은 Tx의 기본 차단 등록·허용 승인 근거·기존 Member backfill·탈퇴/차단·caller/kid/jti 제어/감사·복구 | candidate SQL·Store capability와 fixture SQL 수용. 실제 등록/허용/DDL·제어 소비자 미완료 |
| OQA-05 / Core TR·Compute/Storage 운영 | 아래 HTTP·GET 부수효과·예약/수동/시작 경로·잔여 Run/lease/ACK/grant·Object/key writer inventory와 중단/정리 | 여섯 worker의 fixture Bean 부재만 확인. 실제 전체 writer 중단·consumer 처리는 미확인 |
| OQA-06 / Core/Go·배포 운영 | 실제 routing 아래 callback 여유·연결/DB 획득·본문/전체 예산·취소/종료·미완료 관측·부하와 자원 한계 | 한정 fixture/관측은 완료. 운영 timeout·pool/admission·성능/heap/OS 전체 종료 수치를 선정하지 않음 |
| OQA-07 / Core/Web·관련 consumer | 두 GET의 선택/실패 처리·미이관 목록/쓰기/Import·artifact grant 소비자의 중단/유지/이관 범위 | 두 GET 좁은 계약만 채택. 남은 operation의 응답·scope를 이 문서에서 새로 정하지 않음 |
| OQA-08 / 복구 운영·Core/Go | read proxy 철회·route 차단/복원·Member/kid/jti 차단 보존·backup restore/quarantine·향후 Go 쓰기 후 복구 경계 | 실제 restore/rollback 리허설 미실행. flag를 끄는 것만으로 안전한 복구 완료로 보지 않음 |

두 GET은 신규 Aggregate/Snowflake를 발급하지 않는다. read-mode에 쓰기 allocator seed를 불필요한 선행조건으로 묶지 않는다.
실제 legacy ID/FK·TR 소유권/단일 writer·Member/키/복구 조건은 유지한다. 새 쓰기 기능은 해당 ID/Tx/멱등성 gate를 별도 충족한다.
운영 자료에는 승인된 artifact/ref·측정/복구 결과·담당 역할을 연결하고 원 credential·private key·token/subject/jti/hash를 넣지 않는다.

## Core TR source에서 확인한 쓰기 경로

아래는 현재 main source를 읽은 정적 시작 목록이다. 전체 운영 process·수동 SQL·배치/외부 consumer/스토리지 writer를
조사했다는 뜻이 아니다. 경로 수는 구현 완료율/자동 coverage gate가 아니며 실제 배포 source/ref와 다시 대조한다.

| 진입점 | source·동작 | 중단 검토 |
|---|---|---|
| Account | [Controller](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/account/TradingAccountController.kt): POST 생성·DELETE 삭제 | proxy flag는 두 GET 선택이며 이 command를 중단하지 않음 |
| Book | [AccountBook](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/book/TradingAccountBookController.kt) POST·[Book](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/book/TradingBookController.kt) DELETE | 등록/권한/route 차단·미이관 consumer 처리의 실제 증거 |
| Import·Artifact | [BookImport](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/importsession/TradingBookImportController.kt) 생성, [Session](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/importsession/TradingImportSessionController.kt) validation/retry/삭제/upload, [Artifact](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/artifact/TradingSourceArtifactController.kt) 삭제 | 잔여 실행·재시도·권한·Object/key 부수효과도 포함 |
| artifact grant GET | [Controller](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/artifactgrant/TradingArtifactGrantController.kt) → [redeem](../tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/artifactgrant/redeem/RedeemTradingArtifactGrantService.kt) → [consume Store](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcArtifactGrantRedemptionStore.kt)의 `UPDATE ... SET consumed_at` 후 storage open | GET이어도 TR DB writer다. 잔여 Compute/grant의 완료/중단·권한 회수와 함께 다룸 |
| dispatch/poll/result/ACK/staging | [Normalization scheduler](../tradingreview/adapter/compute/src/main/kotlin/com/refinvest/core/tradingreview/adapter/compute/TradingNormalizationDispatchScheduler.kt)의5개 Scheduled method | `refinvest.compute.enabled` 선택. 기존 Run/Dispatch/ACK 상태 보존·진행 I/O도 확인 |
| spool | [Sweeper](../tradingreview/adapter/compute/src/main/kotlin/com/refinvest/core/tradingreview/adapter/compute/NormalizationResultSpoolSweeper.kt) | `refinvest.trading-review.normalization.delivery.spool-sweep-enabled=false` 필요. 기본 활성 |
| deletion/expiry/retention | [Deletion](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingDeletionWorker.kt)·[Expiry](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingImportExpiryScheduler.kt)·[Raw retention](../tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/TradingRawRetentionWorker.kt) | `deletion-enabled`·`retention-enabled` 기본 활성. purge·정리 진행 상태 보존 |
| raw orphan | [Worker](../tradingreview/adapter/storage/src/main/kotlin/com/refinvest/core/tradingreview/adapter/storage/TradingRawOrphanWorker.kt) | root/keystore-path/keystore-password/key-alias 조건. 실제 storage 설정/생성 자원까지 확인 |
| 시작/수동/외부 경로 | [Core application 설정](../app/src/main/resources/application.yaml)·migrations·Bean 구성·관리/배치/Compute/Storage 소비자 | annotation 검색만으로 전부 닫지 않음. 실제 실행물·등록 경로·부여 권한/감사 필요 |

직접 POST/DELETE command는 source에서10개, 위 여섯 worker의 Scheduled method도10개를 확인했다. 부수효과가 있는 GET과
시작/수동/외부 경로는 이 숫자 밖이다. Core는 인증/Member authority·중계 역할을 계속 수행하고 **TR 업무 writer**를 중단한다.
Member authority의 Core 소유 제어와 Go 소유 TR 업무 DML을 같은 writer로 혼동하지 않는다. 이중 쓰기 운영 프로토콜을 추가하지 않는다.

## DB 시작과 rollback에서 유의할 현재 동작

[application.yaml](../app/src/main/resources/application.yaml)에는 기존 `spring.flyway.baseline-on-migrate=true`가 있다.
이 기본값을 실제 인계 DB의 history 승인으로 해석하지 않는다. target/schema/history·변경 계획을 확인하지 않은 채
누락 이력을 자동 baseline/repair하거나 기존 SQL을 Go에서 재실행하지 않는다. fixture는 별도 schema에서 Flyway를 끄고
source DDL을 적용했으며 운영 DB baseline을 검증하지 않았다. Member authority candidate SQL도 자동 등록/허용이 아니다.

두 GET Controller는 명시 proxy가 없을 때 legacy 조회를 선택한다. [Account 분기](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/account/TradingAccountController.kt)와
[Book 분기](../tradingreview/adapter/web/src/main/kotlin/com/refinvest/core/tradingreview/adapter/web/book/TradingBookController.kt)를 확인한다.
따라서 `runtime/proxy=false`만으로 새 Member/kid/jti 차단 효과가 같은 방식으로 보존된다고 간주하지 않는다.
선택된 proxy 실패의 legacy 성공 fallback은 금지다. 명시 rollback도 해당 route를 유지/차단/복원하는 전략과 차단 상태의
실제 거절 증거를 별도로 확보한다. 새 public 오류/status를 임의로 만들거나 과거 권한 상태를 복원하지 않는다.

## 다음 진행 단위

1. 실제 read-mode 배포를 진행할 때는 OQA-01~08의 target/담당·증거 위치를 채우고 미확인 항목을 닫는다. 자료 수집과 실행 승인을 구분한다.
2. 업무 개발을 이어갈 때는 Go 설계/구현 현황에서 다음 in-process Domain/Use Case/Store 범위를 명시한다. 이미 끝난 기반을 반복하지 않는다.
3. 새 HTTP/Worker 연결은 해당 기능의 미채택 profile/consumer·불변식/Tx/Guard/오류·수용 계약을 확정한 뒤 구현한다. 기존 ADR-086 read scope를 재사용하지 않는다.
