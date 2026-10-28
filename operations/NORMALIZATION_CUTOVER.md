# Normalization joint cutover

이 문서는 ADR-063/065/066/070, Context `TRADING_VERSIONING.md`의 공동 전환,
`TR-ACC-IMPORT-ORCH-028`을 실행하는 Core 운영 절차다. 공개 관리 API는 추가하지 않는다.
결과 버전 선택은 기존 V1/V1이며 V2 선택 활성화는 별도 작업이다.

## Control and authority

상태는 Core PostgreSQL schema의 `trading_normalization_transition` 한 행에 저장한다.
모든 participating Core 인스턴스는 같은 DB/schema를 사용해야 한다. 메모리 flag나 환경 변수의
변경·재기동으로 상태를 바꾸지 않는다. `refinvest.compute.enabled`는 계속 true로 둔다.

V10 migration owner 또는 승인된 DBA 운영 role만 아래 함수를 실행한다. 함수는
`SECURITY INVOKER`이고 PUBLIC 실행 권한을 제거했다. 배포 시 애플리케이션 role은
control/audit 테이블 SELECT만, 운영 role은 control UPDATE, audit INSERT와 sequence USAGE,
두 함수 EXECUTE를 갖도록 DBA가 부여한다. 운영 role에 reconcile을 허용하면 대상
Run/attempt/dispatch SELECT·UPDATE 권한도 필요하다. runtime role을 migration owner로
사용하지 않는다. 환경별 role 이름과 권한 부여는 인프라 소유 설정이다.

성공한 PAUSE/VERIFY/RESUME/RECONCILE_CREATE는 DB `session_user`, epoch, 안전한 evidence
reference, DB 시각, 선택적 dispatch UUID를 감사 테이블에 저장한다. 공유 계정 대신 개인 운영
identity를 사용하고 승인 ticket/배포 인벤토리/HTTP 검증 결과를 접근 통제된 변경 기록에 연결한다.
audit 테이블을 runtime에서 수정·삭제할 권한은 주지 않는다. 거절된 SQL 명령은 rollback되므로
DB 접근 감사에도 남긴다. evidence에는 credential, signed URI, CSV/거래값을 넣지 않는다.

## Fence boundary

schema에 대해 `pg_advisory_*_lock(hashtext(current_schema()),28028)`을 사용한다.
admission은 public idempotency claim 전에 shared transaction lock을 얻고 Run/Session/dispatch/
idempotency commit까지 유지한다. PAUSE의 exclusive transaction lock은 먼저 들어온 admission의
commit을 기다린다. PAUSE가 commit된 뒤 시작한 정상 validation/retry는 기존 공개 오류
`503 TEMPORARY_DEPENDENCY_FAILURE`, `retryable=true`이며 public claim과 상태 변경은 없다.

create는 shared session lock을 durable claim부터 HTTP 응답·receipt 저장까지 유지한다.
이미 permit을 가진 send는 PAUSE 명령과 겹칠 수 있고 PAUSE의 완료 전에 끝난다. 성공적인
PAUSE 함수 호출 **transaction commit**이 중지 완료 경계다. 명령을 제출한 시각을 완료로
해석하지 않는다. 잠금 대기 timeout/connection loss는 중지 성공이 아니며 별도 연결로 상태를
확인한다. Core가 DB와 연결을 잃은 시점의 네트워크 요청은 여전히 불명확 create로 대조한다.

한 인스턴스의 동시 send permit은 1개다. fence용 connection과 claim/receipt transaction용
connection이 필요하므로 runtime pool은 최소 2개, 정상 HTTP/poll 부하를 고려해 그 이상으로
설정한다(현재 기본 pool 10개). session lock 해제 실패 시 연결을 abort하여 pool에 잠금을
돌려주지 않는다. claim 자체에도 DB pause 검사를 둔다. scheduler on/off만으로 보호하지 않는다.

PAUSE 동안 status/terminal 조회, grant 복구, source failure 처리, 삭제·취소·stale discard와
이미 commit된 publication의 ACK 재시도를 유지한다. terminal 성공은 RESULT_READY/staging으로
보존하고 새 publication/PERSISTED outbox 생성을 재개까지 보류한다. 이는 ORCH-028의
`중지 중 신규 PERSISTED 없음`을 적용한다. 삭제 generation 검사가 항상 우선한다.

## Stop and inspect

1. 변경 ticket에 Context/Core/Compute/Web/Integration의 정확한 commit, 배포 instance ID,
   image digest, schema/DB identity, 실행자와 승인 범위를 기록한다. 아래 SQL의 search_path를
   실제 Core schema로 고정하고 DB identity를 확인한다. psql 접속 credential은 기존 secret
   provider로 전달하고 command line/history/log에 넣지 않는다.

   ```sql
   SELECT current_database(),current_schema(),session_user;
   SELECT * FROM trading_normalization_transition;
   SELECT epoch,action,actor,evidence_ref,dispatch_id,occurred_at
     FROM trading_normalization_transition_audit ORDER BY id DESC LIMIT 30;
   ```

2. 현재 epoch를 사용해 아래 명령을 autocommit 또는 명시적인 transaction commit으로 실행한다.
   이미 paused여도 새 PAUSE는 epoch를 증가시키고 과거 검증을 무효화한다. stale epoch는 거절된다.

   ```sql
   SELECT trading_normalization_transition_control('PAUSE',0,'CHANGE-1234');
   -- 별도 연결에서 paused=true와 반환된 epoch=1을 확인한다.
   ```

3. **처음 이 기능을 설치할 때 또는 이전 Core가 혼재하면** 위 DB gate만으로 해당 이전
   instance를 중지했다고 주장하지 않는다. 배포 인벤토리에서 gate 미지원 process를 제거하고,
   제거 완료까지 기존 ingress/egress 운영 제어로 validation/retry와 Normalization create를
   차단한다. 그 사이 새 identity로 우회 전송하지 않는다. 이전 process/worker 0, old outbound
   connection/queue 0, 계약 버전 필드 없는 미전송 producer 0의 근거를 변경 기록에 첨부한다.
   지원되는 인스턴스만 다시 기동하고 같은 DB의 paused 상태를 읽는지 확인한다.

4. 미전송·불명확 작업과 보존 pin을 조사한다. 두 pin이 없는 legacy 배포에서는 그 배포의
   schema/provenance를 먼저 조사하고 V9/Core·0017/Compute backfill을 순서대로 수행한다.

   ```sql
   SELECT d.id,d.run_id,d.compute_key,d.status,d.attempt_count,d.create_started_at,
          d.lease_expires_at,n.compute_job_id,n.result_schema_version,
          n.result_hash_specification_version,n.attempt_token,n.input_fingerprint
     FROM trading_normalization_dispatches d JOIN trading_normalization_runs n ON n.id=d.run_id
     WHERE d.status IN ('PENDING','SUBMITTED','RESULT_READY') ORDER BY d.created_at;
   SELECT count(*) FROM trading_normalization_dispatches
     WHERE result_schema_version IS NULL OR result_hash_specification_version IS NULL;
   ```

## Drain, backfill and ambiguous creates

이미 접수된 V1 Job은 status/terminal을 조회해 drain한다. 보존된 결과 metadata 또는 당시 V1-only
producer 계약을 근거로 Core V9와 Compute 0017을 적용한다. Run/Revision/result hash와 발급된
dispatch key는 보존한다. Job request snapshot과 idempotency fingerprint/record를 함께 검증한다.
근거 없는 행에 V1을 채우거나 새 요청의 필드 누락을 default로 해석하지 않는다.

durable claim은 `create_started_at`을 HTTP 전에 저장한다. 이후 timeout/5xx/429(필수 Retry-After가 있는 명시적
503 TRADING_REVIEW_CAPACITY_EXCEEDED만 미접수로 확인)·응답 유실·Core crash이면 PENDING identity를 보류한다. lease 만료만으로
재전송하지 않는다. 이전 PENDING attempt도 V10에서 보수적으로 이 상태로 전환한다.

1. 기존 dispatch key의 SHA-256으로 **Compute 소유 idempotency record를 read-only** 대조한다.
   해당 Job의 실제 내부 HTTP GET status에서 Job ID, logical dispatch ID, Core Run ID,
   attempt token, generation, input hash, VersionSet, V1/V1 pin을 Core와 비교한다. 기존 key로
   create를 보내서 존재 여부를 탐색하지 않는다. unknown 결과로 새 key/Run/Job을 만들지 않는다.
2. 일치하는 Job을 확인했으면 paused epoch에서 다음을 실행한다. Job bind는 기존 Run/attempt를
   SUBMITTED로 연결하고 status polling을 계속한다. 새 identity를 만들지 않는다.

   ```sql
   SELECT trading_normalization_reconcile_create(
     '00000000-0000-0000-0000-000000000001'::uuid,
     'verified-existing-compute-job-id',1,'CHANGE-1234-reconciled');
   ```

3. Compute의 durable idempotency 부재와 미접수를 모두 확인했을 때만 job 인자를 NULL로 하여
   같은 dispatch key의 전송을 재개 가능하게 한다. 이 동작도 PAUSE를 해제하지 않으며 검증
   attest를 무효화한다. 확인할 수 없으면 보류 유지 후 incident 복구한다. 삭제되었거나 current가
   아닌 identity는 reconcile 함수가 거절한다. Job 내용 대조는 운영자의 권한 있는 read-only
   검증 책임이며 Core DB 함수가 외부 HTTP 사실을 대신 증명하지 않는다.

## Verify and resume

1. 원래 fleet을 paused로 유지한다. 전용 비운영 DB와 실제 HTTP에서 배포할 exact Core/Compute
   commit 조합을 실행한다. Integration의 result-version-pin runner는 별도 canary Core/Compute
   schema에서 정상 JWT·CSRF, 합성 CSV 업로드, public validation과 실제 dispatcher로 V1/V1
   create를 생성한다. accepted/status/terminal/payload와 같은 key replay, 독립 V1 hash 검증,
   단일 Revision/commit 후 ACK를 확인한다. 숨겨진 default나 unsigned/contract 우회 요청을 쓰지 않는다.
2. 실패하면 VERIFY/RESUME을 실행하지 않는다. paused 상태를 별도 connection으로 확인하고,
   실패 로그·DB identity를 보존한다. 수정된 조합을 격리 환경에서 다시 검증한다. 혼합 버전 fleet에
   신규 create를 보내지 않는다. 이전 binary rollback도 paused DB를 유지한 채 동일 pin·migration
   호환성을 확인한 뒤 수행한다. DB migration을 되돌리거나 Revision/hash를 덮어쓰지 않는다.
3. 성공 증적(정확한 commit과 instance/egress inventory 포함)을 접근 통제된 immutable 기록에
   저장하고 그 SHA-256 reference를 다음 두 명령에 동일하게 사용한다. 아래는 형식 예시다.

   ```sql
   SELECT trading_normalization_transition_control('VERIFY',1,'sha256/verified-evidence-digest');
   SELECT trading_normalization_transition_control('RESUME',1,'sha256/verified-evidence-digest');
   SELECT paused,epoch,verified_evidence FROM trading_normalization_transition;
   ```

   함수는 unresolved PENDING create가 있거나 epoch/reference가 다르면 재개를 거절한다.
   VERIFY는 DBA의 검증 완료 attest이며 arbitrary 문자열 자체가 HTTP 성공을 증명하지 않는다.
   재개 후 중지 중 거절했던 동일 public key로 validation/retry하고 단일 Run/Job/Revision/ACK를
   확인한다. 실패 시 새 PAUSE로 epoch를 올리고 다시 조사한다. 대기 Run을 성공으로 처리하거나
   미전송 dispatch를 삭제하지 않는다.

## Migration runner limitation

Compute 0017은 기존 terminal Job이 있는 schema에서 전체 SQL을 atomic transaction으로 실행하면
deferred Job→terminal FK pending event와 ALTER TABLE이 충돌하는 알려진 SQLSTATE 55006이 있다.
Core gate/V10은 이 migration 오류를 고치지 않는다. Integration의 ORCH-027은 기존 psql statement별
runner의 범위이며 atomic runner 배포의 통과 증거가 아니다. 해당 배포는 Compute 소유 SQL/runner의
별도 해결·검증을 완료할 때까지 중지를 유지한다. 빈 canary schema에서 atomic migration이 성공한
것도 기존 terminal Job의 atomic backfill 성공으로 해석하지 않는다.
