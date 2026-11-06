# Analytics 실행 제한과 프로세스 격리 — ADR-071 검토 초안

상태: **DRAFT / 미승인 / production 연결 금지**. 작성일: 2026-10-30.
이 문서는 ADR-069를 대체하거나 현재 API·실행 동작을 변경하지 않는다. 아래 결정안과 새 오류
이름은 제안이며, 승인·정본 계약 반영·구현·운영 수치 검증을 각각 별도 gate로 통과해야 한다.

## 1. 목적과 현재 기준

Analytics 계산이 멈춰도 lease를 계속 갱신해 실행 slot을 영구 점유하는 경우를 제한하고,
취소·lease 상실 뒤 늦은 결과가 발행되지 않게 한다. Metric 정의·표본·Evidence·정규화 결과를
바꾸거나 미완료 계산을 빈 성공으로 바꾸는 작업이 아니다.

- [Job Protocol V1](TRADING_ANALYTICS_JOB_PROTOCOL.md): lease 60초, heartbeat 최대 20초 간격,
  최초를 포함한 최대 3 attempts, 생성 후 24시간 cutoff, Core만 명시적으로 retry한다.
- [Result Delivery V1](TRADING_ANALYTICS_RESULT_DELIVERY.md): 전체 entity의 협상된 바이트 상한,
  검증 후 발행·ACK, terminal 이후 최대 24시간 retention을 유지한다.
- Compute `379eba5`의 Analytics worker는 계산과 heartbeat thread가 같은 부모 프로세스에 있다.
  다운로드의 20초 socket timeout은 전체 실행 deadline이 아니며, 계산 중단을 보장하지 않는다.
- 입력 상한은 artifact마다 기본 512MiB, 출력 운영 상한도 기본 512MiB다. 두 입력의 JSON tree,
  계산 객체, 결과 tree, 직렬화 buffer·bytes, AES-GCM ciphertext가 메모리에서 겹칠 수 있다.
  이 값들은 허용된 운영 configuration이며 전체 RSS 보장이나 권장 메모리 수치가 아니다.
- 현재 `TradingReviewComputeFailureCode`에는 Analytics 실행 timeout 코드가 없다. Backtest의
  별도 `FatalErrorCode.EXECUTION_TIMEOUT`과 ADR-042의 600초·30초를 자동 적용하지 않는다.

적용 불변식은 [DOMAIN](DOMAIN.md)의 TR-I10~12, TR-I15~16, TR-I18, TR-I25~27,
TR-I29, TR-I33~36이다. 실패가 기존 정상 결과·latest pointer를 변경해서는 안 된다.

## 2. 결정안: 실행 경계와 시간 예산

### 2.1 부모와 자식

한 claim은 별도의 자식 프로세스 하나에서 실행한다. 부모만 lease·heartbeat·취소 확인,
현재 claim의 조건부 DB 변경·terminal 발행을 소유한다. 자식은 검증된 계약 입력으로
다운로드·파싱·계산·결과 구성·직렬화를 수행하고 제한된 outcome을 전달한다.

자식 인자에는 DB connection, payload 암호화 key, 내부 API key를 넣지 않는다. 이것만으로
OS 환경이나 공격 코드가 격리되는 것은 아니다. 실행 격리는 자원·수명 경계이며 별도의 보안
sandbox를 구현했다고 주장하지 않는다. 자식과 그 하위 프로세스의 정리도 감독 대상이다.

### 2.2 같은 attempt의 deadline은 재시작해도 유지

새 attempt의 **첫 유효 claim 시각**을 DB clock으로 기록하고 실행 profile의 T를 사용해
`attemptDeadlineAt = attemptStartedAt + T`를 한 번 고정한다. 이는 제안된 runtime metadata이며
현재 schema의 필드가 아니다. Job의 `started_at`이나 attempt의 생성 시각으로 대신하지 않는다.
Queue 대기와 이미 실패를 commit한 뒤 Core retry를 기다리는 시간은 T에 포함하지 않는다.

동일 attempt의 lease reclaim은 기존 deadline의 남은 시간만 사용한다. 자식 재시작·worker
재배포·grant 교체로 T를 다시 주지 않는다. Core가 기존 규칙에 따라 **새 attempt**를 만들었을
때만 새 시작 시각과 deadline을 만든다. Retry budget·cutoff는 그대로 유지한다.

부모는 DB가 반환한 남은 예산을 프로세스 로컬 monotonic deadline으로 보수적으로 환산한다.
DB 응답 지연으로 예산이 늘지 않게 요청 전 monotonic 시각을 사용하고, 실행 중 wall clock
조정으로 타이머를 연장하지 않는다. DB clock·lease·재시작 간 시각 차이는 시험·운영 gate다.
DB clock의 비정상 역행을 무시하고 무제한 재실행할 수 있다는 보장은 하지 않는다.

### 2.3 T에 포함하는 구간

첫 claim 이후 시작 준비·spawn, grant fetch와 만료 확인, byte/hash/schema/identity 검증,
입력 변환, Engine, projection, section/result digest, 전체 UTF-8 entity 직렬화, 부모의
제한된 staging으로 결과 수신·길이/SHA/필수 identity 검증 및 자식 종료 확인까지 포함한다.

부모가 완전하고 유효한 결과와 정상 자식 종료를 **deadline보다 먼저** 확인한 경우만 READY다.
정확히 deadline에 도착하거나, bytes를 보냈어도 자식이 종료하지 않으면 timeout이다.
작업 사이의 `should_stop`이나 socket timeout만으로 이 hard deadline을 구현하지 않는다.

이미 결과가 READY인 뒤의 암호화·DB publication은 별도의 유한한 부모 예산 P로 제한한다.
DB lock/statement timeout도 그 안에 있어야 한다. T와 P를 합쳐 Core Run 전체 응답 SLA라고
주장하지 않는다. T 이전의 READY 확인이 없는 결과를 느린 DB 작업을 핑계로 발행하지 않는다.
READY 뒤 deadline이 지나도 P 안의 유효한 transaction이 commit할 수 있다는 경계를 명시한다.

프로세스 정리 유예 G는 deadline 연장이 아니다. Timeout 판정 이후 결과는 버리고 terminate,
G 이내 종료 확인, 필요 시 kill·join으로 정리한다. 강제 종료·tree 정리가 확인되지 않은 실행을
성공으로 발행하거나 새 claim을 계속 받아 실행 누수를 누적하지 않는다.

## 3. 결정안: 우선순위와 실패

아래는 **제안된** mapping이다. 새 코드 두 개는 아직 OpenAPI enum에 없으며 사용하면 안 된다.

| 상황 | 현재 claim이 유효할 때 제안하는 동작 | 재시도·발행 |
|---|---|---|
| 실행 deadline 도달 | `FAILED / ANALYTICS_EXECUTION_TIMEOUT` | retryable=false, payload·ACK 없음 |
| 감독자가 확인한 child 자원 hard limit 초과 | `FAILED / ANALYTICS_RESOURCE_LIMIT_EXCEEDED` | retryable=false, 잘라서 성공 금지 |
| 원인이 확인되지 않은 child crash·EOF·IPC 오류 | 기존 `TEMPORARY_DEPENDENCY_FAILURE` | 기존 budget 안에서 Core만 새 attempt 시작 |
| D 이전 grant 만료·stream 중단 | 기존 입력 오류 taxonomy | 기존 retry 규칙 유지 |
| 결과 entity가 협상 cap 초과 | 기존 `RESULT_PAYLOAD_TOO_LARGE` | retryable=false, 부분 결과 없음 |
| lease 상실·heartbeat 실패·stale token/generation | child 중단·지역 결과 폐기 | 이전 부모는 성공·실패 모두 DB 변경 금지 |
| 취소가 먼저 DB에 commit됨 | 취소 상태 유지·child 중단 | timeout 실패로 덮어쓰지 않음 |
| 정상 READY와 publication CAS 성공 | 기존 atomic `COMPLETED` | Core 검증·commit 후에만 PERSISTED ACK |
| worker 종료 요청 | 신규 claim 중단·child 정리 | 인위적 실패 commit 없이 유효 lease 처리/만료 규칙 적용 |

Timeout을 일시 의존성 실패로 합치면 같은 고비용 계산을 자동 반복할 수 있어 nonretryable을
권장한다. 값이 더 큰 profile로 재실행하려면 권한·version·삭제 상태를 다시 확인한 새 Run을
사용한다. 기존 terminal Job을 열거나 같은 key의 replay로 제한값을 바꾸지 않는다.

우선순위는 전역의 임의 시각 비교가 아니라 **현재 소유권과 DB에서 commit된 상태**로 정한다.
부모는 지역 종료 사유를 정한 뒤 현재 상태·attempt/token·generation·lease owner/generation·
미만료 lease·취소 여부를 비교하는 기존 atomic 전이로 한 번만 반영한다. CAS 실패를 덮어쓰지
않는다. 취소·정상 발행·timeout이 경합하면 먼저 유효하게 commit한 terminal 상태를 보존한다.

동일 로컬 관측에서 D도 지났다면 입력 만료나 crash를 임의로 retryable로 바꾸지 않고 timeout을
선택한다. 단, 이미 완료/취소됐거나 lease를 잃었다면 새 failure를 쓰지 않는다.

Compute는 Core의 실시간 tombstone을 직접 조회하지 않는다. Core 삭제가 commit됐어도 Compute
취소 요청이 아직 오지 않았다면 Compute COMPLETED 자체를 완전히 막는다고 주장할 수 없다.
Core가 현재 Run·attempt·generation으로 publication을 차단하고 기존 DISCARDED_STALE을
처리한다. 실패/취소 Job에는 payload나 stale ACK를 새로 만들지 않는다.

MemoryError·signal·exit code 하나만으로 OS hard limit 초과를 단정하지 않는다. 격리 범위의
신뢰할 수 있는 한도·종료 원인 증거가 있을 때만 resource-limit 코드를 사용한다.

## 4. 결정안: IPC·메모리·spool

부모가 큰 `(payload dict, body bytes)`를 통째로 pickle 수신하는 방식은 기본안으로 채택하지
않는다. 바이트 상한이 있어도 중복 tree·pickle·암호화 메모리와 부분 IPC 수신 정지를 막지 못한다.

권장 검토안은 부모가 만든 비공개 attempt별 bounded spool에 child가 단일 entity를 쓰고,
작고 고정 상한이 있는 제어 outcome으로 완료를 알리는 방식이다. 자식이 제공한 임의 path를
신뢰하지 않는다. 부모가 소유한 handle/opaque slot만 사용하고 길이·hash·claim identity를
독립 확인한다. 제어 frame의 초과·부분 frame·송신 중단도 hard deadline을 막아서는 안 된다.

제어 outcome에는 전체 records/sections, locator, credential, 원시 예외를 싣지 않는다.
형식·필수 identity·종류·최대 길이를 정적으로 제한한다. 부모는 DB용 작은 metadata를 받되
그 metadata와 실제 entity의 필수 identity·hash가 일치하는지 확인한다. 정상 integrity 검증을
생략하는 근거로 이 초안을 사용하지 않는다. 세부 구현은 AR-09에서 비교 검증한다.

Spool은 삭제 가능한 보호된 임시 데이터다. 필요 시 attempt 전용 임시 key를 사용하며 DB payload
암호화 key와 구분한다. Key/handle 전달·폐기도 AR-09 검증 대상이다. 기존 lifecycle의 암호화·접근·삭제 규칙을 적용하고,
disk quota·중복 write·성공/실패/취소/lease 상실·부모 crash 후 orphan 정리를 검증한다.
새 다운로드 API나 영구 결과 저장소로 사용하지 않는다. 정상 종료 시 즉시 정리하고 orphan도
기존 최대 보관 한도를 넘기지 않게 한다. Path·locator·거래값은 로그에 남기지 않는다.

부모가 bounded body를 읽고 기존 AES-GCM 암호문을 만드는 구간도 **부모 메모리 예산**에
포함한다. 현재 whole-body AES-GCM·DB bytea 구현이 streaming이 된다고 가정하지 않는다.
기존 nonce prefix·AAD·암호문 형식을 바꾸려면 별도 호환성 설계가 필요하다. Parent publication
port와 metadata 검증의 필요한 변경은 AR-09 준비 PR에서 검토하고 AR-10 전에는 연결하지 않는다.

한 worker의 전체 peak와 동시에 실행 가능한 worker 수를 함께 검증한다. Child만 제한하고
parent·암호화·IPC buffer를 무제한으로 남기거나, 공유 cgroup OOM으로 heartbeat 부모까지
죽이는 구성을 안전한 격리라고 부르지 않는다. Active-job admission pool과 실행 동시성은
다르므로 shared capacity 값으로 동시 RSS를 추정하지 않는다.

첫 production 검토 대상은 기존 Linux/systemd 배포다. Child별 OS 한도와 부모 생존·kill-on-parent-
exit/tree 정리를 실제 환경에서 증명해야 한다. Windows spawn·timeout 개발 검증이 Windows의
메모리 hard limit을 보장하지는 않는다. 대응 OS 감독 기능이 없으면 bounded profile을 활성화하지
않고 시작 검증에서 실패한다. 다른 OS의 보장을 묵시적으로 대체하지 않는다.

## 5. 운영 profile과 미확정 수치

권장안은 Compute가 새 Job admission에서 execution policy version·profile revision과 값을
한 번 저장하고 모든 attempts에 유지하는 것이다. 같은 key의 create replay는 원래 Job과 pin을
반환한다. Attempt 시작/deadline만 첫 claim·새 attempt 규칙에 따라 기록한다. 이는 새 내부
metadata 제안이며 현재 wire나 DB에 존재한다고 전제하지 않는다.

| 항목 | 상태 | 확정에 필요한 증거 |
|---|---|---|
| 실행 wall time T | 미확정 | 지원 최대 입력·느린 storage·CPU/파싱/projection/직렬화의 tail |
| 종료 유예 G·중단 관측 간격 S | 미확정 | 실제 OS의 막힌 I/O·죽은 child/tree 정리 시간 |
| 부모 publication 예산 P·DB lock/statement 한도 | 미확정 | 대형 AES-GCM·bytea와 lock 경합, heartbeat 여유 |
| Child memory·부모 reserve·worker 동시성 | 미확정 | 정상 peak·OS limit·OOM·압박 시 부모 생존 |
| 제어 frame·spool disk quota | 미확정 | 최대 정상 metadata·entity, 동시 작업·orphan |

대표 입력·상한 근처·긴 Evidence·높은 관계 수·구조는 유효하지만 메모리 증폭이 큰 JSON을
합성/허용 fixture로 측정한다. 작은 fixed vector 하나의 시간·RSS로 운영값을 선택하지 않는다.
운영 bytes 상한을 조용히 낮추거나 자산/행을 생략하지 않는다. 더 작은 지원 상한이 필요하면
Core admission·광고된 계약·오류를 별도 검토하고 변경 전후 검증한다.

Profile 수치·안전 여유·실행 동시성과 지원 OS를 승인된 release manifest에 명시해야 한다.
재시작이나 환경 변수 수정으로 기존 Job의 profile이 바뀌지 않아야 한다. Profile 정보는 계산
입력이 아니며 input/config/result hash와 AnalysisConfig에 섞지 않는 안을 제안한다.
이 server-side pin·관측 노출 방식도 정책 리뷰 대상이다.

## 6. 계약과 배포 영향

승인 시 다음 변경을 별도 계약 PR에서 함께 검토한다. 현재 문서는 아무 enum도 추가하지 않는다.

| 경계 | 필요한 후속 검토 |
|---|---|
| Compute OpenAPI | shared failure enum에 제안된 두 code, nonretryable 의미·status 적합성 |
| Core OpenAPI | `TradingAnalysisRunFailed.failure` → `StructuredFailure` → `TradingReviewFailureCode`와 안전한 공개 mapping |
| Job protocol·acceptance | deadline/reclaim·경합·자원 실패·Core terminal/ACK 시나리오 추가 |
| Input/result identity·vector | 실행 profile은 runtime metadata; 기존 projection/기대값 유지 여부 확인 |
| Compute persistence | additive 내부 policy pin·attempt 시작/deadline·조건부 전이, 실제 DB 경합 검증 |
| Worker·deployment | supervisor, bounded IPC/spool·메모리/시간 설정, 종료·cleanup·관측·runbook |

계산식·Metric/Finding/config/result schema를 바꾸는 제안이 아니다. 같은 **전체 고정 VersionSet**과
입력에서 성공한 semantic 결과는 보존해야 한다. 하지만 새 artifact의 implementationDigest는
실제 새 값을 써야 하며 VersionSet이 hash 입력에 포함되므로 배포 전후 full hash가 무조건 같다고
주장하지 않는다. 이미 발행된 결과·fixture·기존 digest를 새 구현에 맞춰 고치지 않는다.

기존 worker의 claim query는 implementationDigest/profile별 routing을 하지 않는다. 구·신 worker를
같은 queue에 섞으면 다른 exact pin을 잡고 VERSION_INCOMPATIBLE로 실패시킬 수 있다.
기본 전환안은 신규 Analytics admission/dispatch 중지 → 미전송·응답 유실 Job 수렴 → 기존 Job
drain 또는 Core의 명시적 취소와 소유 worker 정리 → active legacy Job 0 확인 → 검증된
계약·additive migration·새 Core/Compute registry/profile 배포 → isolated DB 실제 E2E → 재개다.
조회·삭제·취소·기존 completed payload/ACK는 유지한다. 무한 실행 legacy Job을 새 worker가
암묵적으로 더 짧은 profile로 재해석하지 않는다.

기존 terminal 이력은 legacy 실행임을 명시적으로 보존할 수 있으나, 과거 실행에 finite deadline을
소급 부여하지 않는다. 자동 backfill로 active Job의 실행 의미를 바꾸지 않는다.

Rollback도 admission을 중지한 뒤 수행한다. Bounded policy로 접수한 작업을 unbounded worker로
되돌리거나 지원하지 않는 exact digest를 옛 worker가 claim하게 하지 않는다. 새 작업은 같은 pin을
지원하는 검증 버전으로 drain·취소한 뒤 전환한다. 기존 completed 결과와 ACK·retention은 유지한다.

## 7. 검증 시나리오 제안

다음 `AR08-*`는 검토 항목이며 확정된 TR-ACC ID나 완료 증적이 아니다. 승인 후 acceptance의
다음 빈 번호에 매핑하고 fixture 상태를 따로 관리한다. 기존 ANALYSIS-016~047은 유지한다.

| 항목 | 검증할 Given / When / Then |
|---|---|
| AR08-01 | 정상 세 Metric → 기존 fixed vector·schema·counts·hash, Core publication/ACK 보존 |
| AR08-02 | fetch/parse/Engine/projection/hash/serialization 각각 정지 → T 내 중단, partial success 없음 |
| AR08-03 | 결과 전송 후 child 미종료 / 부분 control frame / spool write 정지 → 부모 타이머 유지 |
| AR08-04 | 부모 crash·reclaim → 같은 attempt·기존 D의 남은 예산만, 지난 D면 재계산 금지 |
| AR08-05 | 새 Core retry와 create replay → 새 attempt만 새 시작, profile/semantic pin 유지 |
| AR08-06 | READY 직전/정확히 D/직후 → strict 시간 경계, D 이후 결과 발행 금지 |
| AR08-07 | 취소·timeout·완료 동시 경합 → terminal 하나, 실패/취소 payload·ACK 0 |
| AR08-08 | lease 상실·heartbeat 예외·다른 attempt → 이전 부모의 성공/실패 write 0 |
| AR08-09 | Core 삭제가 먼저 commit, Compute 결과는 늦게 도착 → Core 결과/latest 변경 0, 기존 stale 처리 |
| AR08-10 | 확인된 child OS 한도 초과 / 원인 불명 crash → 서로 다른 실패 taxonomy·Core retry 규칙 |
| AR08-11 | 최대 entity·부모 암호화·worker 동시 압박 → 부모 생존·정확한 cap·OS/Disk 예산 증거 |
| AR08-12 | DB lock·statement 지연 / 마지막 write 실패 → P 안의 abort·rollback, payload/job/attempt 원자성 |
| AR08-13 | SIGTERM/SIGKILL·orphan descendant → 소유 process tree·spool 정리, 예산 reset 없음 |
| AR08-14 | 신규/legacy pin·새 digest·rollback → 잘못된 worker claim·과거 결과 재해석 없음 |
| AR08-15 | payload/로그/metrics 검사 → locator·원시 값·거래 식별자·spool path 유출 없음 |

## 8. 승인·활성화 gate와 제외 범위

- [ ] DRAFT 결정안의 deadline 범위·경합·실패/재시도·server-side pin·Core 공개 mapping 리뷰.
- [ ] 대표 입력의 T/G/S/P·메모리·disk·동시성 수치와 지원 OS 검증·승인.
- [ ] Core/Compute OpenAPI·정본 protocol·acceptance를 별도 계약 PR로 반영·동기화.
- [ ] AR-09의 additive metadata·격리 executor·IPC/spool 준비를 production 미연결 상태로 검증.
- [ ] AR-10 부모 worker 연결, 실제 DB 경합·Core E2E·deployment/rollback과 cleanup gate 통과.

이 gate 전에는 AR-08이 승인 완료됐다고 표시하지 않는다. AR-09의 검토용 준비가 가능해도
production 경로·설정 기본값을 활성화하지 않는다. Backtest/Normalization deadline·retry,
Metric 계산·표본 threshold, 새 queue·범용 Job framework, Web 기능은 이 초안 범위 밖이다.

이번 문서 변경의 Determinism impact: none. Point-in-Time impact: none.
실제 deadline 도입은 성공/실패 가능성을 바꾸므로 별도 정책·배포·운영 검증을 요구한다.
