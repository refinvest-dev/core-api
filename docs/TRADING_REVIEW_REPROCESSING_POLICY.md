# Trading Review 재처리 채택 정책

상태: **설계 정책 채택**, 2026-11-15. 사용자의 명시적인 '권고안 채택' 결정에 따라 [ADR-080](DECISIONS.md#adr-080--trading-review-reprocessing-policy-adoption)을 기록한다.
채택 대상은 아래 ①~④의 동작 원칙이다. 기존 wire/bytes 적용·runtime 활성화·업무 구현·수용/운영 인계 완료를 의미하지 않는다.
ADR-079 전체·child/수명·다른 ADR의 미결 사항을 일괄 채택하지 않는다. 과거 후보의 미채택 표기와 충돌하면 이 문서의 **채택 범위**가 우선한다.

## ① identity/hash

- 업무 소유자는 Record가 Account/Book 소속·현재 권위를 검증한 Member ID이며 canonical field는 ownerId다. ownerScope는 설명 개념으로 사용한다.
- 일곱 field는 ownerId, tradingBookId, sourceRevisionContentHash, sourceAnalysisResultHash, targetVersionSetHash, analysisConfigHash, reprocessingType이다.
- 신규 root에서 owner/Book은 양의 signed-int64를 선행0 없는10진 문자열로 표현한다. optional hash key도 유지하며 실제 미사용은 JSON null이다.
- 적용 Config는 원 profile이 허용한 default까지 확정한 정본 hash를 사용한다. versioned Analysis 포함 후보가 요구한 완전 Config를 요청 생략 시 자동 보정하는 규칙은 아니다.
- root의 ASCII key/string·null을 key 정렬·UTF-8/BOM 없음·불필요한 공백 없음으로 직렬화하고 SHA-256 lowercase64hex로 표현한다. prefix/salt/profile field를 임의 추가하지 않는다.
- parent의 full Target hash와 child projection·source/input/result hash는 구분한다. 하위 hash 규칙을 이 root 규칙으로 재정의하지 않는다.
- 정확 source pointer·outputs/publish·reason/parent·Run/attempt·시각/trace·현재 generation은 이 root에 편의상 추가하지 않는다. 별도 binding/현재 Guard 검증을 따른다.

신규 표현의 채택은 기존 hash를 다시 계산하라는 뜻이 아니다. 원 key/profile·ID/token/hash·createdAt/lineage를 보존한다.
기존 재처리 이력/bytes·consumer 동등성·alias 필요 범위를 조사하고 golden vector로 검증한 뒤 적용한다. 이력 없음이 확인된 범위에는 불필요한 alias를 만들지 않는다.
hash 입력의 ID 문자열 규칙을 모든 API/Go typed ID의 문자열 전환으로 확대하지 않는다.

## ② 요청 비교·실행 수렴·retry

| 목적 | 채택 규칙 |
|---|---|
| public namespace | 기존 owner+operation+key 유지. Book/profile을 추가해 충돌을 회피하지 않음 |
| 같은 key | 원 profile의 동일 요청 의미여야 함. exact source/outputs/publish·reason·명시 retry parent 변경은 conflict |
| 다른 key 수렴 | 같은 logical family이며 exact source/type·full target/config·outputs/publish·실행 의미가 같아야 함. 같은 내용 hash의 다른 source ID로 재라벨 금지 |
| reason 차이 | 각 요청의 허용 reason/권한·audit를 보존하면 기존 실행 수렴 허용. 원 Run.reason을 덮지 않음 |
| explicit retry | execution equality와 별도로 exact retryable FAILED parent/chain·원 effect 해소·현재 접근·단일 successor를 검증. key 변경만으로 새 attempt 생성 금지 |

**원 요청 의도와 검증된 facts를 분리한다.** request의 exact pointer/의도·원 profile/default 의미를 보존하고, source/target/config pin은 실제 검증한 facts로 취급한다.
No-Run 거절에는 불명 실행 pin/logical key를 만들지 않는다. 반대로 request-only/부분 facts를 정상 실행 입력으로 전달하지 않는다.
합법적인 source Analysis omitted/null·publish omitted/true 동등성은 원 profile의 규칙에 한정하며 금지된 null·duplicate·coercion을 보정하지 않는다.
명시 retry parent를 execution equality에서 제외하는 것은 parent 자격을 생략하는 뜻이 아니다. UNKNOWN/CANCELLED·Worker 재claim/같은 create 재전송은 새 FAILED retry와 구분한다.

## ③ 원 admission receipt

- 최초202 뒤 Run이 FAILED/CANCELLED/COMPLETED여도 같은 key의 원 admission 종류는202로 유지한다. 현재 상태는 승인된 Run View로 제공한다.
- 접수 당시 기록된410/422는 원 오류 의미·정확 Run 참조를 유지한다. pin 확정 business rejection을 기록하는 경계와 기술 오류/rollback을 구분한다.
- 현재 auth/Member·owner/Scope Guard가 우선이며 원 receipt가 접근권한을 연장하지 않는다. 현재 trace/안전한 렌더링·headers를 원 응답 bytes로 고정하지 않는다.
- 다른 key가 같은 Run에 수렴해도 각 request의 binding/reason/접수 시각·decision을 보존한다. 원 Run.reason·부모 key mapping/receipt를 바꾸거나 새 Dispatch/latest 부작용을 만들지 않는다.
- 다른 key의 동일FAILED·명시 retry 없음은 검증된 원 admission 종류를 계승한다. 원202 뒤 실행실패면202/현재FAILED, 기록410/422면 원 오류 의미다. currentFAILED만으로 원 종류를 추정하지 않는다.
- mapping/필요 Run/Dispatch/원 decision은 같은 실제 PostgreSQL Tx에서 결속하고 commit 확인 후 응답한다. callback 임시 outcome·응답 유실·HTTP 취소를 commit 증거로 쓰지 않는다.
- commit UNKNOWN은 원 key/binding·권위 DB·동일 fence로 복구한다. SELECT miss/nullable ID·replica·새 key로 미접수/새 실행을 단정하지 않는다.

수렴에 필요한 원 decision/profile·권한·audit를 증명할 수 없는 범위는 활성화하지 않는다. 원 receipt 불명을 임의202/410/422로 backfill하지 않는다.

## ④ 정상 legacy source pin 부족

- 정상 legacy 형식의 필요 pin 표현 한계·기술 장애/저장 손상·원 receipt/commit UNKNOWN을 구분한다. 합법 optional absence를 부족으로 취급하지 않는다.
- 검증 가능한 source/type/profile만 실행한다. 제외 입력에 대한 안전한 거절 계약도 구현 전에 설계하며 UI 숨김·임의400/410·영구503으로 우회하지 않는다.
- 정상 pin 부족이 확정되면 FAILED Run·가짜 pin/logical key 없이 **실행 전 영속 거절**을 기록한다. 응답은 **별도 versioned422 variant·오류 code·Run ID 없음**으로 설계한다.
- 현재422의 RecordedFailure에서 Run ID만 생략/null/0으로 바꾸는 변경은 금지한다. pin 확정 business failure의 WP02 경로를 전부 No-Run으로 바꾸지 않는다.
- 원 request/profile·확인된 evidence/부족 근거·거절 decision·현재 Guard를 원자 결속한다. same-key는 원 거절을 replay하며 기존202/기록실패/UNKNOWN을 새 거절에 재결속하지 않는다.
- 별도 승인된 복구 후 실행은 새 key의 정상 admission이다. No-Run 거절은 FAILED parent가 아니며 retryOf로 사용할 수 없다. 실제 logical active/success 관계는 정상 admission에서 검증한다.
- 복구는 원 authoritative evidence의 lossless 확인에 한정한다. 최신 default·원 source overwrite·가상 Session·삭제 raw/backup 복원·TTL 연장으로 pin을 채우지 않는다.

## 미확정 세부사항은 언제 확정하는가

| 시점 | 확정할 것 | 완료 판단 |
|---|---|---|
| **관련 업무 구현 전 문서 설계** | 정확 No-Run variant/code·required/nullable·request intent/facts 비교/원 decision 재현·reason audit·receipt 보관/삭제/최소 marker/key 재사용 | 소유 계약·필요 versioned OpenAPI/예제·수용 assertion/consumer 의미가 일치 |
| **hash 적용·이관 전 문서/증적** | profile 식별/원 recipe·지원 source/legacy 범위·원 bytes·alias 필요 여부·vector·호환성/복구 계획 | 실제 이력과 cross-consumer 검증. 기존 키 rewrite 금지 |
| **구현 착수 전 상세 설계, 이후 구현과 검증으로 구체화** | table/FK/unique/index·source/claim lock/CAS/Tx 경계·Port 능력·오류/복구 경로·예산 | 의미/원자성 경계는 선행 확정. 실제 SQL/EXPLAIN/경합·부하 결과로 물리 설계 조정 |
| **구현 중 결정 가능** | 구체 struct/함수명·파일 분리·내부 helper·adapter 세부 | 채택 계약/의존성·수명/오류 규칙을 유지. 외부 의미 변경이면 정본 설계에 먼저 반영 |
| **활성화/운영 인계 전** | 실제 권위/Member-commit 선형화·전체 writer/UNKNOWN·consumer·삭제/수명/backup·DB/ID/단일 writer·sync/E2E | WR/WF/TR-ACC/WUC/WFP의 실제 실행 증적 |

즉 **외부 계약·정합성·보관 정책을 구현하면서 즉흥 결정하지 않는다.** 모든 물리 튜닝까지 문서로 끝낸 뒤 코딩해야 하는 것은 아니며,
확정된 의미를 구현/검증하면서 조정 가능한 내부 상세는 함께 발전시킨다. 필요한 범위만 선행 설계하고 업무 구현 전환은 별도로 확정한다.
receipt 보관 최소24h를 자동 삭제 시각/무기한 보관으로 해석하지 않는다. Run 없는 receipt에는 'Run 수명 동안' 규칙을 적용할 수 없으므로 별도 수명/삭제를 먼저 정한다.

## 채택의 한계와 후속

ADR-079의 child 실행/profile·private 수명, ADR-073·AP/LP 등 별도 미결 정책·정확 새schema/code/TTL·이관/운영·업무 구현 전체는 채택하지 않았다.
현재 active/draft OpenAPI·기존 canonical hash 식/vector·Compute·DB/schema/runtime은 이번에 변경하지 않는다. 관련 범위 적용 전에 소유 계약의 불일치를 먼저 정리한다.
과거 논의는 [통합 결정표](TRADING_REVIEW_REPROCESSING_POLICY_DECISIONS_DRAFT.md)와 네 DRAFT에 보존한다. 파일명 DRAFT나 과거 미채택 문구로 이번 명시 채택을 취소하지 않는다.
기존 WR/WF gate·WR-DRAFT-01~14·TR-ACC/WUC/WFP는 별도로 남으며 새 semantic ID/실행 결과를 만들지 않는다.
다음 작업은 **request intent/facts·원 receipt/수렴·No-Run variant/code/수명/삭제를 문서 설계에서 확정**한 뒤 필요한 schema/vector·consumer/legacy·전파를 준비하는 것이다.
Context exact commit CI/impact→merge→최신 snapshot/marker→sync를 따르고 Web 포함 E2E 전에는 integration `-IncludeWeb` 검사가 선행한다.


## 구현 전 상세 계약 후속

[Request/receipt·No-Run 상세안](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)은 원 descriptor/검증 facts·admission 순서와
versioned422의 REPROCESSING_SOURCE_PIN_UNAVAILABLE/UnpinnedSourceRejectionV2Draft를 구체화한다. active wire/runtime은 유지한다.
[수명안](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION_DRAFT.md)은 상세30일+최소 매핑 Book 수명을 권장하되 명시 선택 대기다.
채택된 원칙을 다시 승인받지 않으며 수명 수치/보존 방식은 별도 선택 전 확정으로 표시하지 않는다. 관련 구현/활성화 전에 남은 계약/consumer·수명·실제 수용을 완료한다.
