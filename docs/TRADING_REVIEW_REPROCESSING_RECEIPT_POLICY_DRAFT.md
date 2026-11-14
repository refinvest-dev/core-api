# Reprocessing 원 admission receipt 논의안 (DRAFT)

현재 통합 권고는 [①~④ 결정표](TRADING_REVIEW_REPROCESSING_POLICY_DECISIONS_DRAFT.md)를 먼저 읽는다.
개별 문서의 순차 후속 안내보다 통합 표의 채택/수정/보류 권고·적용 조건을 우선 확인하되, 모두 미채택 상태다.


상태: **순차 논의 ③ 권장 후보·미채택**, 2026-11-14. [① identity/hash](TRADING_REVIEW_REPROCESSING_IDENTITY_POLICY_DRAFT.md)·
[② binding](TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md)을 이어 원 접수 decision과 현재 Run View를 구분한다.
①~③·ADR-079/WP01~04는 미채택이다. 현재 wire·hash/vector·Compute·DB/runtime을 변경하지 않는다.
④ 불명 source pin의 거절/기록 모델·No-Run receipt/새422·업무 구현 전환은 이번에 선택하지 않는다.

## 고정 source와 차이

| Source | 현재 근거/공백 |
|---|---|
| [WP02 접수](https://github.com/refinvest-dev/context/blob/52ed51f3750a40a944ff0712cb5763560b65293c/docs/TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md) | 동기 410/422와 FAILED Run·원 receipt의 같은 Tx 후보. 실제 source pin이 확정돼야 함 |
| [② binding](https://github.com/refinvest-dev/context/blob/52ed51f3750a40a944ff0712cb5763560b65293c/docs/TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md) | same-key 원 요청 비교·cross-key 실행 수렴. reason/audit·parent chain과 receipt 구분 필요 |
| [DTO](https://github.com/refinvest-dev/context/blob/52ed51f3750a40a944ff0712cb5763560b65293c/docs/TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md)·[OpenAPI](https://github.com/refinvest-dev/context/blob/52ed51f3750a40a944ff0712cb5763560b65293c/docs/TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md) | 최초202/기록410/422·현재 trace·현재 접근 우선, 503 UNKNOWN. static schema가 commit을 증명하지 않음 |
| [API §4.2/8](https://github.com/refinvest-dev/context/blob/52ed51f3750a40a944ff0712cb5763560b65293c/docs/TRADING_REVIEW_API.md)·[Persistence §5](https://github.com/refinvest-dev/context/blob/52ed51f3750a40a944ff0712cb5763560b65293c/docs/TRADING_REVIEW_PERSISTENCE.md) | transport mapping 최소24h·Aggregate가 남는 동안 logical mapping 보존. operation별 원 응답 의미 필요 |
| [Core idempotency Store](https://github.com/refinvest-dev/core-api/blob/ab7402577a32758b8d600e3dbfd5cd54b6fcce3c/tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence/JdbcTradingIdempotencyStoreAdapter.kt)·[V1 DDL](https://github.com/refinvest-dev/core-api/blob/ab7402577a32758b8d600e3dbfd5cd54b6fcce3c/tradingreview/adapter/persistence/src/main/resources/db/migration/V1__trading_account_book.sql) | fingerprint·nullable resource_id만으로 재처리 원 outcome/profile 전체를 복구할 수 없음. 기존 다른 command의 source이며 실제 불완전 row 관측이 아님 |
| [Go ADR-0001](https://github.com/refinvest-dev/trading-review-api/blob/9297db848507b1b45e9e0b5bdddfaf7fc66b0028/docs/adr/0001-transaction-bound-capabilities.md) | commit UNKNOWN은 권위 DB/원 fence로 복구. 단일 SELECT miss는 rollback 증거가 아님 |

이 문서의 receipt는 **영속적으로 확정된 최초 접수 의미와 참조**다. HTTP body 원 bytes·로그·Compute ACK receipt와 구분한다.
아래 표현은 conceptual facts이며 새 Aggregate/table·공개 상태/enum·schema field·receipt 조회 endpoint를 정의하지 않는다.

## 세 수명과 필요한 사실

| 구분 | 보존/조회할 사실 | 변경 경계 |
|---|---|---|
| request mapping/receipt | 원 owner+operation+key namespace·request/contract profile·② request binding/원 resolved pin·reason/명시 parent audit·decision 종류/안전한 오류 의미·정확 Run 참조·접수 확정 시각 | 같은 key를 retry successor/최신 요청에 다시 결속하지 않음 |
| Run/실행 | logical/execution binding·원 Run/업무 attempt/lineage·현재 상태/progress/failure·최종 child decision | 전용 업무 전이/발행으로 변경. 최초 admission 의미를 덮지 않음 |
| HTTP projection | 원 admission 종류에 맞는 status/code/허용된 Run 연결, 현재 승인된 Run View·trace·safe message·cache/전송 header | 원 profile/현재 권위에 따라 렌더링. 전체 응답 bytes를 과거 값으로 고정하지 않음 |

Record가 request mapping·ReprocessingRun과 원 admission 의미를 소유한다. 각 요청의 reason과 Run 최초 reason은 별개로 보존한다.
exact bytes 저장·항상 새 receipt ID 발급·별도 event engine을 요구하지 않는다. 손실 없는 의미/reference와 profile을 증명할 수 있어야 한다.
원 request/default/pin을 복구할 수 없으면 현재 source/registry/config로 재구성하지 않는다. ④ 및 legacy gate가 열린다.

## 접수 종류와 현재 상태

| 원 admission decision | HTTP 후보 | 이후 Run 상태와 replay |
|---|---|---|
| 새 Run 정상 접수 | 202 + 승인된 Run View | 이후 FAILED/CANCELLED/COMPLETED여도 원 접수 종류는202. 현재 상태는 View에서 표시 |
| 다른 key의 exact active/success 수렴 | 이 후보의 접수202 + 해당 Run View | 새 request mapping/receipt·audit를 commit. 원 Run/child/dispatch/latest는 변경하지 않음 |
| WP02 확정 business rejection | 원 410 또는422 + 기록된 FAILED Run 연결 | 같은 key replay는 원 code/종류/Run 유지. 현재 registry 회복을 이유로 자동 실행/202 변환하지 않음 |
| 다른 key·exact FAILED·명시 retry 없음 | WP03에 따라 원 Run에 결속하고 **그 Run의 검증된 원 admission 종류를 계승**하는 권장안 | 원202 뒤 실행 실패면202/현재FAILED, 원 기록410/422면 해당 오류 의미. 새 요청 audit/확정 시각을 원 요청 것으로 위조하지 않음 |
| 명시 retry 자격 충족 | 새 Run을 접수하거나 같은 parent chain의 이미 확정한 successor로 수렴한 원 decision | 새 public mapping/receipt. 이전 실패 key의 mapping/receipt는 불변 |
| malformed/인증/foreign/현재 접근 거절 | 기존 operation 오류·존재 숨김 | 새 Run·확정 admission receipt를 생성하지 않음. 보안 audit와 별개 |
| 일시 DB/registry 오류·commit UNKNOWN | 기존 기술 오류/복구, UNKNOWN은 기존 후보503 의미 | 확정 rejection receipt·추측 Run ID를 만들지 않음 |

다른 key가 같은 Run에 연결돼도 원 key의 request binding/reason/시각을 복사해 새 요청이 그 요청이었다고 기록하지 않는다.
공유 가능한 것은 검증된 Run/decision 근거이며 각 key의 요청 의미·audit와 접수 결정은 구분한다.
수렴한 FAILED의 원 admission 근거가 불명하면 현재 FAILED 상태만으로 202/410/422를 추정하지 않는다.
위 실패 수렴/종류 계승은 미채택 권장안이며 소비자·원 receipt 보존/retention과 함께 확인해야 한다.

②의 reason 차이 수렴은 각 요청의 reason/권한 검증과 audit 보존이 전제다. original Run.reason을 변경하지 않는다.
현재 Run.failure의 retryable 표기와 **원 admission 오류의 retry 의미**를 혼합하지 않는다. 원 recorded rejection의 의미를 보존하고,
same-key 재전송·명시 새 FAILED retry·Worker 재시도의 허용 여부를 원 retryable boolean 하나로 결정하지 않는다.

## 같은 key replay 순서

1. 현재 service/위임·Member·owner/Book/source 접근과 Scope Guard를 확인한다. 원 receipt가 접근권한을 연장하지 않는다.
2. 원 namespace/권위 DB에서 request binding·profile/pin·commit된 decision/Run 참조를 확인한다. 기존 전체 lock/Tx 규칙을 따른다.
3. ②의 same-key 의미가 다르면 승인된 conflict. 현재 권한 거절은 원 mapping 존재/Run을 노출하지 않는다.
4. 동일하면 원 admission 종류·safe code/허용된 참조를 유지하고, 현재 승인된 Run View를 같은 제한된 read snapshot에서 구성한다.
5. 현재 trace·안전한 message 렌더링·문서화된 header/cache 정책으로 반환한다. replay 자체는 dispatch/cancel/repair·최신 pointer 변경을 하지 않는다.

원 admission을 읽을 권한과 새 계산을 실행할 eligibility는 구분한다. Raw-only 삭제로 raw가 없어졌다는 이유만으로
접근 가능한 retained Run의 원202를 새410으로 바꾸지 않는다. Book/source 접근 자체가 차단됐다면 현재 삭제/비노출 규칙이 우선한다.
status GET도 repair/Compute poll을 실행하지 않는다. 삭제 뒤 일반 replay와 deletion status의 별도 control 권한을 합치지 않는다.

## 고정할 값과 매번 새로 읽을 값

| 고정 대상 | 현재 projection 대상 |
|---|---|
| 원 profile·semantic binding/해당 resolved pin·허용된 audit | 현재 인증/authority·owner/Guard·노출 허용 여부 |
| 원 admission 종류/status 의미·safe code·오류 의미/원 Run mapping | Run의 현재 상태/progress/failure/공개된 child만, 조회 snapshot |
| 원 접수/decision 시각·원 ID/lineage | 현재 요청 trace/span·안전한 message 렌더링·문서화된 transport header |

HTTP status/header를 Domain/App 모델에 직접 넣어 저장하라는 뜻은 아니다. business decision과 contract profile을 보존하고 mapper가 응답 의미를 재현한다.
Core/Gateway는 내부 인증 실패를 브라우저 logout으로 그대로 번역하거나 오류를 모두202로 바꾸지 않는다.
원 receipt의 trace를 현재 응답 trace로 재사용하지 않는다. 기존 요청과의 관측 연결이 필요하면 별도 제한된 참조로 관리한다.
raw root/request body·credential·locator·Compute token을 receipt/log/error에 보관하지 않는다. safe detail은 기존 allowlist와 lifecycle을 따른다.

## commit·응답 유실·UNKNOWN

정상 신규는 Run/Dispatch/필요 intent·mapping/receipt, 기록 rejection은 FAILED Run/decision·mapping/receipt를 같은 실제 Tx에서 저장한다.
durable rejection은 commit할 업무 decision이다. 저장 오류로 반환해 rollback시키지 않으며 실제 DB/Guard 오류를 삼켜 commit하지도 않는다.
commit 확인 전 확정 receipt/Run 연결을 응답하지 않는다. callback 안에서 만든 결과만으로 commit 성공을 추정하지 않는다.

| 장애 시점 | 후보 처리 |
|---|---|
| known-abort·접수 전 오류 | rollback 근거와 원인 보존. 현재 권위 아래 같은 key의 안전한 재접수 가능 여부를 판정 |
| commit 성공·HTTP 응답 유실/브라우저 abort | 영속 receipt 유지. 같은 key replay; durable 작업은 별도 cancel 없이 중단하지 않음 |
| commit 결과 불명 | 원 key/binding·권위 DB·같은 fence로 이전 effect를 조정. nullable mapping/SELECT miss·replica 결과로 rollback 단정 금지 |
| 복구 중 현재 접근 거절 | 자원/Run 비노출. 내부 effect 조정/정리 책임이 사라진 것은 아님 |
| 원 receipt/Run 참조 손상·보존 profile 불명 | 원인 구분/복구. 임의 원 code·pin·ID0/마지막 Run·새 attempt로 보정하지 않음 |

UNKNOWN은 새 public Run 상태·영구 receipt 종류가 아니다. 원 commit을 확인하면 원 decision으로 복귀하고,
미확정이면 기존 후보503/같은 key 복구 의미를 유지한다. 다른 public key나 새 Compute create로 불확실성을 덮지 않는다.
복구는 취소된 HTTP Context를 재사용하지 않는 별도 유한 작업이다. background 무기한 실행/새 lease 없이 병렬 복구를 허용하는 뜻은 아니다.
정확 budget·worker claim·lock graph·known-abort SQLSTATE·Member/commit 선형화는 기존 계약/Go ADR과 실제 수용에서 검증한다.

## 보관·삭제·legacy

현재 API의 transport mapping **최소24시간**은 자동 삭제 시각이 아니다. Aggregate가 남는 동안 logical mapping을 보존하는 규칙도 유지한다.
transport body/record가 정리돼도 replay를 약속한 범위의 최소 decision/profile/request 관계를 복구할 근거가 필요하다는 권장이다.
보존 근거 없이 key 조회가 miss라는 이유로 미접수/새 실행을 선언하지 않는다. 반대로 이 원칙을 무기한 전체 body/key 보관 근거로 쓰지 않는다.
receipt·audit의 구체 보관 범위/삭제 후 최소 marker·멤버/Book purge·backup 복구와 재사용 key 경계는 lifecycle/운영 gate에서 확정해야 한다.
이번에 새 TTL/보관 예외·삭제 후 정상 Run 조회·receipt 전용 tombstone table을 채택하지 않는다.

기존 Core의 resource_id만으로 원 status/code/profile/binding을 알 수 없다. SQL NULL을 ID0으로 읽어 원 Run으로 쓰지 않는다.
실제 이력이 있다면 원 ID/hash/receipt evidence를 lossless 보존하고, 불명 receipt를 새 profile의202로 자동 backfill하지 않는다.
기존 재처리 receipt 이력이 없음을 확인한 범위는 불필요한 migration/alias를 만들지 않는다. source에서 못 찾았다는 사실만으로 운영 이력 부재를 단정하지 않는다.

## 기존 수용 계획에 연결할 사례

기존 WR-G01/02/07/08·WR-DRAFT-01~14·Go WUC-05/WFP-P03/P11/P12를 사용한다. 다음은 **미실행 검토 사례**다.

1. 원202 직후 Worker FAILED·동일 key replay: 원202·같은 Run/현재FAILED·새 trace, 새 Dispatch 없음.
2. 기록410/422 replay: 원 오류 의미·같은 FAILED Run, 현재 Guard 우선. registry 회복이 자동 실행을 만들지 않음.
3. 다른 key의 exact active/success/FAILED 수렴: key별 binding/reason/시각·원 Run decision 근거 분리, 원 mapping/Run.reason 불변.
4. 새 명시 retry와 부모 key replay: 새 successor key와 원 실패 receipt가 서로 덮이지 않음.
5. commit 직전 오류·성공 후 응답 유실·commit UNKNOWN·동시 재접수: mapping/Run/decision 원자성·원 fence 복구·단일 effect.
6. raw-only와 Book 삭제·권한 변경·삭제 후 replay: 정상 조회/Run 노출·control 권한을 혼동하지 않음.
7. mapping TTL 경과·원 profile/receipt 불명·nullable resource_id: miss/ID0/최신 설정으로 원 admission을 추정하지 않음.

새 schema·error literal·receipt digest/vector·SQL/HTTP/Worker 구현이나 semantic 실행 증거는 추가하지 않았다.
①~③의 정확 채택 범위를 확인하고 다음은 ④ 정상 legacy source의 pin 불명 처리와 안전한 거절/기록 계약을 논의한다.


## 후속 ④ 정상 legacy source pin 부족

[④ 후보](TRADING_REVIEW_REPROCESSING_SOURCE_PIN_POLICY_DRAFT.md)는 정상 source의 필요한 pin 부족과 기술 장애/손상·원 receipt 불명을 분리한다.
지원 source 제한과 versioned No-Run 영속 거절을 우선 검토하되 현재422·Run 모델을 바꾸지 않는다.
이 경우의 request binding/거절 evidence는 실행 가능한 logical key/pin이 아니며, Run 없는 receipt의 수명/삭제는 별도 조건이다.
원202/기록실패·UNKNOWN을 새 No-Run 거절에 재결속하지 않는다. ①~④는 미채택이며 정확 variant/code·consumer·retention은 후속이다.
