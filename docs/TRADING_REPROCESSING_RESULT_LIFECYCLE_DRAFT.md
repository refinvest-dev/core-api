# 복합 재처리 결과 수명·ACK 정책 초안

상태: **검토 초안, 미채택·미구현**. ADR-073의 대안 검토다. ADR-063/069의 ACK·상태·보관 계약과
OpenAPI를 변경하지 않는다. 신규 deadline/오류/상태를 production에서 활성화할 근거가 아니다.
승인 시 관련 정본/API/Acceptance를 함께 갱신한다. ADR-071의 Compute hard deadline은 별개 초안이다.

## 1. 문제와 현재 제약

복합 재처리는 R2→A2를 비공개로 준비하고 한 DB Tx에서 함께 발행한다. 그러나 Compute 결과는
terminal 이후 최대 PT24H 보존이고 PERSISTED는 실제 발행 commit 이후에만 전송할 수 있다.
R2를 Go가 받았어도 A2 대기·재시작·발행 지연으로 R2의 payload/ACK receipt가 먼저 만료될 수 있다.

서로 다른 사건을 구분해야 한다.

| 사건 | 보장하는 것 | 보장하지 않는 것 |
|---|---|---|
| Compute COMPLETED | 계산 결과 생성 | Go 검증·공개 완료 |
| Go private prepare 완료 | identity/hash에 결속된 검증 결과의 제한된 비공개 보존 | PERSISTED ACK 권한 |
| 최종 DB publication commit | 요청한 child·lineage·pointers·Run 완료의 원자적 공개 | ACK 전달 성공 |
| ACK 204/같은 receipt replay | Compute가 ACK를 확인 | 별도 삭제 checklist의 실제 부재 확인 |
| payload TTL/404 | 조회/전달 불가 또는 만료 가능성 | ACK 성공·Compute 모든 사본 purge 완료 |

근거: [Persistence §7](TRADING_REVIEW_PERSISTENCE.md),
[Normalization delivery](TRADING_NORMALIZATION_RESULT_DELIVERY.md),
[Analytics protocol](TRADING_ANALYTICS_JOB_PROTOCOL.md),
[Runtime deletion](TRADING_RUNTIME_DELETION.md), [Versioning](TRADING_VERSIONING.md).

## 2. 대안과 추천 초안

| 대안 | 개요 | 장점 | 비용/제약 |
|---|---|---|---|
| A. 제한된 발행 판단 창 | 기존 result/receipt TTL 안에서 복합 준비·발행을 시도, 실패하면 R1/A1 보존 | 현행 wire/disposition·retention 유지 | 긴 A2/장애로 작업이 실패할 수 있고 ACK 전달까지 보장하지 못함 |
| B. 비공개 custody 확인 프로토콜 | 공개 전 검증된 durable 보관을 별도 protocol로 확인 | Compute 결과 보존과 공개 수명을 분리 | 새 wire/version·receipt·privacy/retention·GC·호환 배포 설계 필요 |
| C. 현행 PERSISTED를 private prepare에도 사용 | 기존 enum 의미 확대 | 구현 표면이 작아 보임 | publication 이후 ACK 정본과 충돌하므로 채택하지 않음 |
| D. Go에 받아두고 Compute 만료 후 무기한 발행 | 외부 TTL과 독립적으로 대기 | 장기 복구 가능 | orphan/staging 보관 정책과 ACK 완료 의미를 먼저 바꿔야 하므로 현행 계약으로 허용 불가 |

**추천 초안은 A**다. 현재 계약을 보존하는 시작점이며 충분한 실행 성공률이 나오는지는 측정해야 한다.
대표 입력에서 창 안에 복합 처리가 어려우면 B를 별도 버전 계약으로 검토한다. C/D를 편의상 구현하지 않는다.
이는 문서 제안이며 사용자가 리뷰·채택하기 전 확정 방향으로 표시하지 않는다.

## 3. 대안 A의 정책 pin과 시간 경계

접수 시 재처리 policy version과 전체 작업 예산·최종 Tx/ACK 재시도용 reserve를 pin하는 방안을 제안한다.
수치는 아직 미정이다. Backtest 값이나 ADR-071 초안 수치를 복사하지 않는다.

- `workflowDeadline`: 대기/재시작/재시도를 포함한 작업 한계 후보. 시작 때 고정하고 연장하지 않음.
- `resourceExpiresAt`: 각 descriptor의 광고 만료, Go private artifact의 기존 보관 한계.
- `publishDecisionCutoff`: `min(workflowDeadline, 필요한 resourceExpiresAt들) - reserve` 후보.
- 새 A2 descriptor가 도착하면 필요한 만료 집합을 재평가하며 cutoff는 뒤로 연장하지 않음.
- reserve가 없거나 양수 창이 확보되지 않으면 해당 단계/발행을 시작하지 않음.

이 이름들은 제안용 개념이며 새 JSON 필드·DB column·enum을 확정하지 않는다. DB clock·clock skew,
lock/statement timeout·정책 수치의 정확한 계산은 Go/Compute 검증 후 확정한다.

cutoff는 **잠금 뒤 발행을 승인하는 판단 시점**의 한계다. check 한 번으로 commit 완료나 네트워크
ACK가 실제 만료 전에 끝난다고 보장하지 않는다. lock 획득 후 현재 시각·Guard·attempt·hash·
resource 유효성을 다시 확인하고 짧은 final Tx에서 CAS한다. commit 또는 전달 중 창을 넘기면
아래의 commit 결과/ACK 복구 규칙으로 처리한다. reserve는 실행 여유이며 전달 보장이 아니다.

Compute attempt 실행 중단과 Go의 신규 준비/발행 중단은 다르다. cutoff 도달만으로 Python 자식
프로세스가 종료됐다고 표시하지 않는다. 취소는 기존 endpoint의 best effort, 실제 삭제는 별도 부재 검증이다.

## 4. 준비·최종 발행·ACK 순서

1. 요청 유형·source/target pin·owner/Book scope·정책을 고정한다. R2+A2가 필요한 요청에만 이 흐름을 적용한다.
2. R2를 bounded 수신하고 transport/section/hash/reference/version을 검증한다. 보호된 staging과
   checkpoint를 저장하고 일반 query/Book pointer/기존 ACCEPTED Session은 변경하지 않는다.
3. 창과 Guard를 재확인해 R2 고정 A2를 실행·검증한다. R2·A2·원본 stream을 하나의 장기 DB Tx로 묶지 않는다.
4. 최종 Tx에서 scope/attempt/pin/hash·완전성·판단 cutoff·공개 경합 조건을 재검증한다.
5. 두 child·lineage·선택 pointer·ReprocessingRun 완료와 모듈별 PERSISTED outbox를 함께 commit한다.
6. commit 이후 동일 ACK를 전송한다. ACK 실패가 공개 결과 재발행이나 기존 결과로 rollback을 유발하지 않는다.

child Run과 기존 Session의 identity/state binding, latestAnalysis pointer 선택은 별도 G03 gate다.
이 초안은 이를 해결했다고 간주하지 않으며 기존 Session을 VALIDATING으로 되돌리지 않는다.

## 5. 실패·취소·삭제와 정리

- **commit 전 준비 실패/cutoff 초과:** 최종 공개 금지, 기존 R1/A1 유지, Go staging cleanup을 영속 기록한다.
- **일반 사용자 취소:** 공개 CAS와 경쟁시켜 먼저 확정한 결정을 따른다. 아직 공개되지 않은 current
  completed 결과에 취소를 이유로 DISCARDED_STALE을 보내지 않는다. 새로운 attempt/generation을 위조하지 않는다.
- **실제 삭제/stale attempt:** 정본 조건을 충족하고 payload가 유효할 때만 기존 DISCARDED_STALE을 사용한다.
  Normalization의 deletion-stale 조건과 Analytics의 stale-attempt 조건을 서로 혼용하지 않는다.
- **ACK할 수 없는 미공개 결과:** 기존 Compute TTL cleanup을 따르고 Go 사본을 정리한다. wire 변경 없이
  취소 전용 ACK를 만들지 않는다. 명시 삭제라면 별도 runtime deletion 절차와 실제 부재 증거가 필요하다.
- **중간/최종 Tx 오류:** 상태·checklist·outbox 원자성을 유지한다. 외부 object는 별도 idempotent cleanup으로 복구한다.

발행 판단 창 소진의 공개 failure taxonomy는 미확정이다. 실제 payload 만료 전 reserve 소진을
RESULT_PAYLOAD_EXPIRED로 위장하지 않는다. 채택 시 기존 실패 표와 맞추거나 명시적으로 새 code를 정의한다.
정확한 Run 상태/오류는 승인 전 enum으로 추가하지 않는다.

## 6. commit 이후 ACK 전송 종료

일반 발행과 복합 발행 모두 ACK transport 상태를 업무 Run 완료와 분리한다.

- 204 또는 같은 ACK의 검증된 receipt replay만 acknowledged로 기록한다.
- timeout/일시 장애는 원래 key·body·persistedAt/discardedAt·generation·disposition을 유지해 재전송한다.
- 409 identity/hash/disposition 충돌은 자동으로 새 ACK를 생성하지 않고 일치 오류로 조사한다.
- 404/만료는 ACK 성공이 아니다. 정책상 retry window가 끝나면 미전달 terminal outcome을 기록·관측하되
  이미 commit된 결과·COMPLETED·pointer를 되돌리거나 계산을 다시 제출하지 않는다.
- Compute payload/receipt가 사라진 뒤의 확인 가능성에는 한계가 있다. 전달 종료가 부재 증명이나
  ACK 수신 증명은 아니며 삭제 checklist의 성공 근거로 사용하지 않는다.

이 방안은 현행 제한 보존에 맞춘 전송 종료 정책의 **제안**이다. 영구 retry·exactly-once·영구 receipt를
약속하지 않는다. ACK 전송 budget·오류 분류·운영 경보·최소 감사 보관은 채택 시 정본에 구체화한다.

## 7. crash 복구와 보관

| 재시작 지점 | 확인과 처리 |
|---|---|
| 일부 bytes/section 수신 | 검증 완료로 간주하지 않고 보호된 spool/stage를 정리하거나 유효 기간 안에 재수신 |
| R2 준비 후 A2 전 | owner/Guard/pin·동일 R2·만료를 재검증, 같은 실행 identity로 재개하거나 실패/정리 |
| R2/A2 준비 완료 | cutoff·정합성을 다시 확인하고 final Tx 경쟁. 창 종료 시 기존 공개 상태 유지 |
| commit 응답 유실 | 멱등 key/영속 child·pointer·완료·outbox를 재조회. 성공/실패를 추측해 재발행하지 않음 |
| commit 후 ACK 전 | durable outbox만 재개, 결과 검증/계산/발행을 반복하지 않음 |
| ACK 성공 응답 유실 | 동일 body/key replay, receipt 만료 뒤 전달 불명확성은 성공으로 치환하지 않음 |

orphan spool/staging의 기존 최대 PT24H와 즉시 삭제·privacy 규칙을 완화하지 않는다.
유효 prepared 결과와 abandoned orphan 구분, 정책 pin·lease·cleanup 상태를 durable하게 추적한다.
기존 보관 상한을 넘어 복구하려면 B와 함께 lifecycle/backup/key 정책을 별도로 변경해야 한다.
Raw-only 삭제와 Book 삭제의 Guard 의미는 유지하며 raw가 지워졌다는 이유만으로 기존 Ledger/Analysis를 삭제하지 않는다.

## 8. 검증 시나리오 (초안 ID)

아래 RACK-DRAFT ID는 검토용이며 확정 Acceptance Scenario ID가 아니다. 승인 후 정본 Acceptance에 매핑한다.

| ID | 조건 | 기대 결과 |
|---|---|---|
| RACK-DRAFT-01 | R2/A2 준비 성공·창 유효 | 단일 Tx 공개, 이후 두 ACK outbox 전송 |
| RACK-DRAFT-02 | R2 staging만 완료 | 일반 query 비노출, PERSISTED 없음 |
| RACK-DRAFT-03 | R2 대기 중 cutoff 종료 | 신규 A2/발행 금지, 기존 R1/A1 유지·cleanup |
| RACK-DRAFT-04 | final Tx DB 실패 | 두 child/pointers/완료/outbox 모두 rollback |
| RACK-DRAFT-05 | 발행 commit 직후 ACK timeout | 결과 유지, 같은 key/body/timestamp로 ACK만 재시도 |
| RACK-DRAFT-06 | ACK 미전달 상태에서 receipt 만료 | acknowledged로 위장하지 않음, 업무 완료 유지·전송 실패 관측 |
| RACK-DRAFT-07 | commit 응답 유실 후 재시작 | 영속 상태로 성공 여부 복구, 중복 공개/계산 없음 |
| RACK-DRAFT-08 | 사용자 취소와 final CAS 경합 | 승자 상태 유지, 취소만으로 stale generation 위조 없음 |
| RACK-DRAFT-09 | 실제 Book 삭제가 준비 중 commit | 이후 공개 차단, 권한 있는 purge/실제 부재 검증 |
| RACK-DRAFT-10 | 같은 ACK와 persistedAt이 바뀐 재전송 | 동일 재전송 아님을 검출, receipt 충돌을 성공 처리하지 않음 |
| RACK-DRAFT-11 | final Tx 승인 이후 ACK 전에 만료 | 공개 결과 유지, ACK 전송 종료를 별도 기록 |
| RACK-DRAFT-12 | 프로세스 중단으로 orphan 상한 초과 | 사본을 retained result로 승격해 발행하지 않고 정리 |

## 9. 채택 gate

1. A의 사용자 실패/취소·공개 code/state·전체 시간 예산을 리뷰하고 관련 정본에 반영한다.
2. 실제 clock/DB lock·commit 경합, representative input의 준비/분석 시간과 reserve를 검증한다.
3. G03 child Run/Session·pointer 계약을 확정하고 final Tx/lease/Guard의 실제 원자성을 검증한다.
4. Compute의 유효 receipt replay·만료/정리 경계를 두 runtime에서 대조한다. 현재 동작 차이를 새로운 보장으로 쓰지 않는다.
5. privacy/backup/orphan과 durable prepare의 수명·key 삭제·정리 증거를 검증한다.
6. context/OpenAPI/fixture snapshot을 전파하고 실제 HTTP/DB E2E를 수행한다.

이 gate 이전에는 G04 완료·production 준비 완료로 표시하지 않는다. Metric/계산/version/hash의
Determinism과 Point-in-Time 의미에는 제안된 변경이 없다.
