# Reprocessing request/execution binding 논의안 (DRAFT)

현재 통합 권고는 [①~④ 결정표](TRADING_REVIEW_REPROCESSING_POLICY_DECISIONS_DRAFT.md)를 먼저 읽는다.
개별 문서의 순차 후속 안내보다 통합 표의 채택/수정/보류 권고·적용 조건을 우선 확인하되, 모두 미채택 상태다.


상태: **순차 논의 ②의 권장 후보·미채택**, 2026-11-14. [① identity/hash](TRADING_REVIEW_REPROCESSING_IDENTITY_POLICY_DRAFT.md)의
채택 권고 범위를 전제로 검토하되 ①의 명시 채택·byte 호환성·구현 승인을 대신하지 않는다.
ADR-079/WP01~04는 DRAFT다. 현재 wire·hash/vector·상태·Compute·DB/runtime을 변경하지 않는다.

## 고정 source와 비교 목적

| Source | 근거/남은 차이 |
|---|---|
| [기존 identity/child](https://github.com/refinvest-dev/context/blob/4b2ac85dbb24aeb783aab2b1abf9af0300dd7c8a/docs/TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md) | namespace/logical/full binding·실패 parent retry와 child 분리. full binding이라는 말의 비교 목적은 더 구체화해야 함 |
| [DTO 후보](https://github.com/refinvest-dev/context/blob/4b2ac85dbb24aeb783aab2b1abf9af0300dd7c8a/docs/TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md) | source Analysis omitted/null·publish omitted=true·명시 retry parent·완전 Config 조건 |
| [OpenAPI 후보](https://github.com/refinvest-dev/context/blob/4b2ac85dbb24aeb783aab2b1abf9af0300dd7c8a/docs/TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md) | public/internal 별도 operation·닫힌 request·필수 target/outputs. schema 성공은 의미 비교 증거가 아님 |
| [Versioning §10](https://github.com/refinvest-dev/context/blob/4b2ac85dbb24aeb783aab2b1abf9af0300dd7c8a/docs/TRADING_VERSIONING.md) | logical hash의 업무 필드·원 결과 보존. full request equality를 대신하지 않음 |
| [Go admission](https://github.com/refinvest-dev/trading-review-api/blob/09a39c87c8f9a5f562c2a4129c828b8e51ac069d/docs/design/workflow-persistence/queries-and-admission.md) | request fence/Guard 뒤 semantic claim·logical 관계·같은 실제 Tx에서 mapping/새 attempt |

아래 binding은 **비교할 불변 사실의 묶음**을 뜻하며 새 endpoint·hash·table·공통 DTO 이름이 아니다.
full binding은 이 비교 전반을 가리키는 상위 표현으로 남기고 모든 필드를 한 번에 같은 방식으로 비교하지 않는다.

## 네 가지 구분

| 구분 | 비교하는 것 | 사용 목적 |
|---|---|---|
| request namespace | 검증 owner + operationId + public Idempotency-Key | 최초 요청 mapping의 유일 범위. Book/profile을 추가해 충돌을 우회하지 않음 |
| request binding | 원 요청의 검증된 의미·exact source·target/config·outputs/publish·reason·명시 retry parent | **같은 public key**가 같은 요청인지 확인 |
| logical key | ①에서 다루는 owner/Book·source 내용·target/config/type의 deterministic 계산 family | 관련 active/success/실패 이력 탐색. 정확 요청/실행 동등성의 단독 증거가 아님 |
| execution binding | 같은 logical family에서 함께 실행·발행해도 되는 exact source/pin·outputs/publish·유효 실행 정책 | **다른 public key**의 active/success 수렴과 명시 retry의 부모/후속 관계 검증 |

reason·retry parent는 원 요청/audit에서 보존한다. execution equality에서 제외하더라도 권한·재시도 자격 검증을 생략하지 않는다.
Compute create/grant/ACK key/body/token은 각 child의 별도 고정 identity다. public binding을 그대로 Compute payload로 사용하지 않는다.

## 필드별 권장 비교

| 항목 | request binding | execution binding/별도 검증 |
|---|---|---|
| owner/Book | 검증 owner·exact Book 고정 | 동일 owner/Book 필수. 현재 권위/Account 소속/Guard는 별도 재검증 |
| source Revision·optional Analysis | exact ID와 원 검증 pin. Analysis omitted/null은 후보 profile에서 같은 '미지정' | exact ID·내용/result hash·version/provenance 관계 동일. 같은 hash의 다른 ID도 다른 binding |
| reprocessingType | exact enum | 동일. 비슷한 type/Compute surface로 대체 금지 |
| Target | 원 full tuple·component/version/hash를 lossless 보존 | 동일한 verified full target. child의 좁은 Target projection은 별도 계약 |
| Config | 적용되는 완전 Config·정본 hash. 요청 profile의 필수/생략 규칙 확인 | 검증된 값/hash 동일. Analysis 포함 후보는 완전 Config 필수; LEDGER_ONLY는 생략 |
| requestedOutputs | 명시된 selector | 동일. LEDGER_ONLY와 pair는 수렴시키지 않음 |
| publishAsLatest | omitted=true·명시 true를 같은 의미로 제안. false는 다름; null은 거절 | 동일 effective intent. 완료 뒤 latest pointer를 다시 이동시키지 않음 |
| reasonCode | exact 값. 같은 key에서 바꾸면 conflict | 비교에서는 제외하는 안. 각 요청의 허용 reason/권한을 독립 검증하고 원 Run reason은 덮지 않음 |
| retryOfReprocessingRunId | 생략과 exact non-null parent를 구분. parent 변경은 conflict | equality 필드에서는 제외. exact FAILED parent·같은 execution binding·단일 successor·이전 effect 해소를 별도 필수 검증 |
| request/contract profile | 원 규칙과 pin 해석을 식별·보존 | 다른 profile은 증명된 의미 동등성이 있을 때만 비교/alias. 새 namespace로 분리해 중복 실행 금지 |
| trace·deadline·service credential | identity에서 제외 | 현재 요청 권한/시간 budget은 검사. 기존 장기 실행 deadline을 replay로 연장하지 않음 |

비교할 정책은 **원 요청에 고정한 계산/발행 의미에 영향을 주는 정책 pin**이다. 모든 runtime 설정·현재 권한 상태를 binding에 넣지 않는다.
현재 Guard·version 지원/자원 가용성은 단계별 검증이며 원 pin을 최신 값으로 교체하는 근거가 아니다.
source Analysis가 명시됐으면 실제 계산에서 쓰지 않을 것 같다는 이유로 그 ID/hash를 비교에서 제거하지 않는다.

## 원 요청 정규화와 replay

request binding은 raw HTTP bytes와 구분한다. 허용된 omitted/default를 해당 **원 profile**에 따라 의미로 확정하고,
exact source/pin과 요청 이력을 함께 보존한다. 원 raw body 보관이나 새로운 binding hash를 이번에 요구하지 않는다.

- source Analysis omitted/null은 후보 schema가 같은 없음으로 정의한 범위에서만 동등하다.
- publish omitted와 true는 원 profile의 true default를 고정한 경우에만 동등하다. 현재 서버 default로 재해석하지 않는다.
- retry parent null·publish null·추가 key·중복 key·type coercion은 유효 요청으로 정규화하지 않는다.
- Analysis 포함 후보의 Config 생략은 default 주입으로 복구하지 않는다. legacy profile이 명시적으로 허용한 default는 최초 resolved 값과 근거를 보존해야 한다.
- component 생략/null·미사용 Target component·Decimal/배열 순서의 동등성을 범용 JSON 정리로 추정하지 않는다. 정본 VO 규칙을 따른다.

known key는 현재 접근 승인 뒤 원 request 의미·pin과 비교한다. immutable 원 pin/receipt를 replay하기 위해
새 Compute 실행을 위한 raw 가용성/registry 조건을 다시 적용해 원 outcome을 바꾸지 않는다.
현재 resource 접근 거절은 우선한다. 보존 pin을 확인할 수 없는 경우에는 최신 source/default로 재구성하지 않으며 ③/④ 후속 정책으로 남긴다.

## 다른 key의 수렴·retry

| 상황 | 권장안 |
|---|---|
| 같은 key·같은 request binding | 원 mapping/receipt/Run replay. 최신 상태·현재 trace와 원 admission 종류는 구분(③) |
| 같은 key·reason/parent/source/outputs/publish 차이 | conflict 후보. 동일 logical hash로 이를 덮지 않음 |
| 다른 key·같은 execution binding·active/success | 해당 Run으로 수렴. 새 요청의 reason/audit를 별도 mapping에 보존하고 원 Run/receipt/child·latest를 변경하지 않음 |
| 다른 key·동일 hash이나 다른 source ID/outputs/publish | binding conflict 후보. 다른 hash field·namespace를 추가해 새 실행 허용으로 돌리지 않음 |
| 다른 key·FAILED·retry parent 없음 | 기존 WP03처럼 실패 관계를 유지. key 변경만으로 새 attempt 생성 금지 |
| 다른 key·명시된 retryable FAILED parent | 현재 권위/eligibility·같은 execution binding·기존 remote/commit effect 해소·parent chain 확인 후 새 attempt 또는 이미 결속된 successor 수렴 |
| parent 변경/다른 계보·CANCELLED·UNKNOWN | retry 자격으로 자동 수용하지 않음. 원 effect/별도 정책으로 처리 |

서로 다른 reason으로 active/success에 수렴시키는 안은 audit/권한을 약화시키지 않는 조건부 권장이다.
새 요청 reason을 원 Run reason으로 덮어쓰지 않으며, 원 Run reason을 새 요청 값으로 바꾸지도 않는다.
실제 request audit 보존 범위/retention·원 admission receipt는 ③ 및 lifecycle 계약에서 확인해야 하므로 이 안도 미채택이다.

명시 retry parent를 execution equality에 그대로 넣으면 parent가 없던 최초 실행과 parent가 있는 retry를 비교할 수 없다.
따라서 계산/발행 동등성과 **retry 관계의 자격**을 나눠 검사한다. 이는 retry parent를 무시하자는 뜻이 아니다.
logical fence 아래 terminal parent와 이미 결속된 후속 chain을 확인하고 하나의 active successor만 허용한다.
다른 chain의 active/success를 보았다는 이유로 임의 parent의 retry를 승인하지 않는다.
Worker 재claim·grant 갱신·같은 create UNKNOWN 재전송은 이 새 업무 attempt와 별개다.

## Tx·모듈·오류 경계

request namespace fence와 semantic comparison을 구분한다. 현재 Guard·request binding·logical/execution 관계·retry 자격과
mapping/원 admission decision·필요한 새 Run/Dispatch/lineage는 기존 공통 lock graph와 같은 실제 PostgreSQL Tx에서 판정/저장한다.
별도 '비교 성공' Tx를 commit한 뒤 Run을 생성하거나 일반 Update/upsert로 claim을 우회하지 않는다.
전체 writer의 정확 lock 순서·Member/commit 선형화·물리 제약은 기존 WF/WR gate이며 이 문서로 검증하지 않았다.

Record가 원 public mapping·logical family·ReprocessingRun을 소유한다. Analysis는 같은 Tx의 공개 능력으로 source facts를 제공한다.
Record가 Analysis 내부 테이블/Domain을 참조하거나 Workflow가 자체 Aggregate/진행 테이블을 만들지 않는다.
현재 auth 거절·유효한 binding conflict·보존 데이터 손상·commit UNKNOWN을 구분하고 새로운 code/status를 이번에 만들지 않는다.
기술 손상/저장 오류를 사용자 conflict로 숨기거나 임의 FAILED Run을 commit하지 않는다. Compute/Storage I/O는 Tx 밖이다.

## 검토 사례와 잔여 결정

기존 WR-G02·WR-DRAFT-01~14·Go WUC-05/WFP-P03/P11/P12의 수용 계획에 다음을 연결한다. **실행한 테스트가 아니다.**

1. 같은 key에서 publish omitted/true 동등, false 변경 conflict. forbidden null/duplicate는 binding 비교 전 거절.
2. 같은 key의 reason/명시 retry parent 변경 conflict; source omitted/null은 원 profile에서 정의한 범위만 동등.
3. 다른 key·reason 차이·같은 execution binding은 원 active/success에 수렴하고 양쪽 request audit/원 Run reason 보존.
4. 같은 내용 hash·다른 source ID 또는 outputs/publish 차이는 수렴 금지.
5. 명시 retry는 원 FAILED parent/chain·effect 해소·동일 execution 조건과 단일 successor를 검증. UNKNOWN/Worker retry와 분리.
6. profile/default 변경 뒤에도 원 mapping/pin 보존. 다른 profile·원 pin 불명은 임의 equivalent/rehash하지 않음.
7. Guard 변경·동시 admission·DB 오류/UNKNOWN에서 원 mapping/Run/lineage 원자성·비노출·멱등 복구.

이번에 정리한 것은 비교 목적과 필드별 권장안이다. 새 field/schema·binding digest/vector·SQL/런타임·semantic 수용은 만들거나 실행하지 않았다.
reason 차이 수렴과 audit 보존, retry chain, profile 간 동등성은 명시 채택·실제 소비자/legacy 검증이 필요하다.
①/②의 정확 채택 범위를 확인한 뒤 ③ 원 admission receipt, 이어서 ④ source pin 불명 처리를 논의한다.
현재 410/422와 FAILED Run 연결을 No-Run receipt·새422로 바꾸지 않는다. 업무 구현 전환도 별도다.


## 순차 논의 ③ — request별 receipt

[원 admission receipt 후보](TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md)는 다른 key가 같은 Run에 수렴해도
각 request의 binding/reason/접수 시각과 원 admission decision을 구분하도록 제안한다. 원 Run.reason·부모 key mapping을 변경하지 않는다.
원202/현재FAILED·기록410/422·새 trace·현재 Guard·UNKNOWN/TTL 복구와 실패 Run 수렴의 원 종류 계승을 검토한다.
①~③은 미채택이며 source pin 불명·No-Run/새422 거절은 ④의 후속이다.
