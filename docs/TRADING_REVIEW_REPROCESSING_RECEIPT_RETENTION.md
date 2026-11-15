# Trading Review 재처리 receipt 수명·삭제 정책

상태: **확정**, 2026-11-15. 사용자의 명시 승인으로 [ADR-081](DECISIONS.md#adr-081--trading-review-reprocessing-receipt-retention)을 기록한다.
대상은 재처리 admission의 정상 접수·Run이 있는 기록 실패·No-Run 거절 receipt다. 다른 서비스/명령의 idempotency·Audit/log TTL을 일괄 변경하지 않는다.
[ADR-080 채택 정책](TRADING_REVIEW_REPROCESSING_POLICY.md)·[admission 상세 계약](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)을 함께 적용한다.
과거 선택 대기 문구와 달리 아래 기간/삭제/key 차단은 확정됐다. SQL/table/cleanup·실제 수용/운영 활성화 완료를 뜻하지 않는다.

## 세 데이터 범위와 clock

| 범위 | 필수 목적/내용 | 보관/종료 |
|---|---|---|
| 최소 request/decision 원장 | 원 namespace·request/contract profile·lossless 비교 descriptor·reason/명시 parent audit·원 접수/거절 의미·정확 Run 참조가 있다면 그 참조·최초 decision 시각·필수 판정 근거/식별 | **Book 수명**. 관련 source/Book/Account/Member 명시 삭제가 우선 |
| 상세 진단 | 운영 조사에 필요한 추가 source/profile/판정 evidence. 원 decision 검증/감사·정상 비교에 필수인 자료는 최소 원장으로 분류 | **최초 영속 decision 시각 + P30D**. replay/조회로 연장하지 않으며 명시 삭제가 우선 |
| 삭제 후 최소 차단 정보 | 같은 owner+operation의 기존 key 재사용 차단·삭제 scope/generation/삭제 control 결속·expiry/복구 suppression에 필요한 최소 정보 | **기존 삭제 control의 완료/복구 clock + P180D**. 삭제 미완료/FAILED는 완료/복구 전 만료시키지 않음 |

receipt는 HTTP 응답 전체 bytes/로그가 아닌 원 admission의 영속 의미다. 현재 Run View·trace·message 렌더링·headers를 불변 복사하지 않는다.
기간은 보존한 authoritative UTC decision/control 시각을 기준으로 계산하며 Book 표시 timezone·HTTP 재요청 시각으로 재계산하지 않는다.
P30D/P180D는 정책 값이다. cleanup batch/주기는 운영 설정으로 조절할 수 있지만 임의 환경 설정으로 보관 기간을 바꾸거나 원 pinned policy를 rewrite하지 않는다.
위 구분은 데이터 목적/수명이며 반드시 세 테이블을 만들라는 요구가 아니다.
삭제 요청 commit 즉시 현재 Guard로 접근/새 effect를 차단한다. 원장/진단의 실제 purge는 기존 삭제 SLA·checklist/실제 부재 검증을 따르며 commit 즉시 물리 삭제 완료라고 하지 않는다.
상세30일 만료도 cleanup 실행·부재 확인과 구분한다. TTL/시간 경과만으로 삭제 COMPLETED를 선언하지 않는다.

## 최소 원장과 진단의 경계

최소 원장은 단순 key 존재 marker가 아니다. 같은 요청/다른 요청 비교·원 code/decision 재현·요청별 감사에 필요한 의미를 lossless하게 보존한다.
No-Run에는 Run/attempt·완전 source pin/logical key를 만들지 않는다. 확인한 source/profile·정상 부족 근거 중 원 decision 검증에 필수인 부분은 원장에 남긴다.
추가 진단 evidence만30일 뒤 정리하며 필수 descriptor/profile·원 decision/감사 근거까지 버리지 않는다.
원장 손상/필수 자료 소실을 새 요청/기존 key 재사용 허가로 바꾸지 않는다. 원인 분류·권위 DB/원 fence 복구를 따른다.
Run이 있는 receipt는 원 Run/decision 참조를 활용할 수 있으나 현재 state만으로 과거 admission 종류를 추정하지 않는다.
다른 key가 같은 Run에 수렴해도 request별 reason/시각/descriptor·decision을 보존하고 원 Run.reason/부모 key mapping을 바꾸지 않는다.

raw CSV·전체 HTTP body·credential/locator/Compute token·거래 결과 전체·과거 응답 bytes는 최소 원장/진단의 기본 저장 대상이 아니다.
진단 보관 기간을 source/raw TTL 연장·삭제된 backup 복원 근거로 사용하지 않는다.

## replay·key 재사용

| 상황 | 확정 처리 |
|---|---|
| 같은 key·동일 request | 현재 접근 승인 뒤 원 decision replay. 최초202/기록410·422/No-Run 거절 종류는 유지 |
| 같은 key·다른 request | 원 namespace의 conflict. Book/profile을 namespace에 추가해 우회하지 않음 |
| 상세 진단30일 만료 | 최소 원장으로 비교/replay 유지. key를 새 실행에 재결속하지 않음 |
| No-Run 후 evidence 확보 | 기존 key는 원 거절 유지. 실행은 새 key의 정상 admission·실제 logical active/success 검증 |
| 관련 scope 삭제 후 원 요청 | 현재 삭제/비노출 규칙이 먼저이며 정상 receipt replay는 제공하지 않음 |
| 삭제된 key를 다른 Book에 재사용 | 같은 owner+operation의 차단 정보가 유효하면 conflict. 공개 namespace에는 Book을 추가하지 않음 |
| 차단 정보의 정당한 만료 뒤 | 그 과거 key의 replay/재사용 차단 보장 범위 밖. 클라이언트는 새 요청마다 새 key를 사용 |

최소 차단 정보는 정상 receipt가 아니다. 원 Run/거절·descriptor/Config·진단을 재현하거나 조회하는 데 사용하지 않는다.
현재 대상 resource 접근이 거절되면 기존404/410 등 operation 의미가 우선한다. 현재 대상은 허용되지만 retired key인 경우 기존 conflict 의미를 사용하고 과거 scope/receipt 존재를 노출하지 않는다.
차단 정보가 만료됐다는 사실만으로 live/UNKNOWN 작업의 key를 해제하지 않는다. 삭제 완료·권위 상태·기존 논리 중복 방지/원 fence를 함께 따른다.
오래된 key 조회 miss만으로 미접수/rollback을 단정하는 것은 계속 금지한다. 삭제된 ID를 새 Book/source에 재할당하거나 복구로 기존 scope를 재활성화하지 않는다.

## 삭제 전환에 예약 공백이 없어야 한다

삭제 요청 commit부터 원 receipt 접근·late admission/새 write를 Guard/tombstone으로 차단한다.
최소 원장을 purge하기 전에 해당 namespace/key의 재사용 차단 근거가 이어져야 한다. 원장 제거와 최소 차단 정보 확보 사이에 key가 잠시 풀리는 전환은 금지한다.
이를 위해 거대한 key 목록을 한 Tx/heap에 올리거나 별도 table을 강제하지 않는다. 기존 매핑의 control 결속/전환·bounded workset을 포함한 실제 방식은 물리 설계에서 검증한다.
request fence·source/Book Guard·삭제 control·필요 key 예약은 기존 전체 writer/Tx 규칙에 참여한다. Worker 전환/재시작/동시 admission에서도 보장이 유지돼야 한다.

차단 정보는 namespace를 식별/차단할 최소 key 참조·삭제 scope/control 결속·유효 clock/version에 한정한다.
원 request/Config·financial payload·상세 진단/원 Run 결과를180일 더 보관하지 않는다. 원장 purge와 차단 정보 보존은 별도 목적이다.
실제 key 참조의 저장 표현/암호화·lookup/rotation은 구현 전 보안/저장 설계로 구체화하되 hash/HMAC만으로 비식별화나 삭제 예외를 주장하지 않는다.
삭제된 Book/Member row의 생존을 강제하는 business FK나 source 내용 복구 없이도 control이 유지돼야 한다.

## 삭제 scope·backup·기존 수명과의 관계

- 관련 source/Book/Account/Member 삭제는 원장/진단의 Book/30일 보관보다 우선한다. 차단 정보는 승인된 삭제/restore control 목적으로만 제한 보존한다.
- Raw-only는 retained Ledger/Analysis/Book의 원장을 일괄 purge/무효화하거나 새 계산을 허용하지 않는다. 실제 raw/locator/진단 copy의 삭제 범위는 유지한다.
- No-Run receipt를 Run 수에 가산하지 않는다. 원장/진단/최소 차단 전환·cleanup은 삭제 closure/checklist/검증에 포함한다.
- backup 최대30일·quarantine·tombstone replay/부재 검증을 따른다. 복원으로 과거 receipt·만료 진단·해제된 key를 다시 활성화하지 않는다.
- 180일 clock은 기존 삭제 control의 완료/복구 기준이다. 단순 replay/일반 worker retry로 갱신하지 않으며 실제 복구 control의 clock/expiry 조정은 그 정책과 일관돼야 한다.
- 최소24h transport 보관은 하한이지24h 뒤 key 재사용 허가가 아니다. log30일/Audit180일과 receipt 데이터 목적은 다르다.
- 새 수명 정책의 적용 profile/clock을 명시하고 원 import/Run의 pinned data policy·원 key/ID/hash를 자동 rewrite하지 않는다. 기존 이력 이관은 별도 검증한다.

## 구현 전 남는 상세와 수용

기간·수명 구분·현재 삭제 우선·동일 namespace key 차단·보장 종료 의미는 확정됐으므로 이를 재승인받지 않는다.
정확 최소 descriptor/근거 분류·최소 key/control 표현·부모 row purge 뒤 lookup·전환/CAS/lock·bounded cleanup·backup restore를 저장/보안 설계에서 구체화한다.
기존 WR/WF/TR-ACC/WUC/WFP 계획에30일 경계/replay 비연장·필수 원장 유지·삭제 직후/전환 중/180일 경계의 key 재사용·FAILED deletion·late write/복원 사례를 연결한다.
현재 OpenAPI·hash/vector·SQL/TTL/cleanup·서버를 구현/활성화한 것은 아니다. 실제 부재·경합/UNKNOWN·권위/consumer·운영 수용과 명시 업무 구현 전환은 별도다.
과거 제안은 [DRAFT 경로](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION_DRAFT.md)/Git 이력에 보존하며 현재 정책은 이 문서가 우선한다.
