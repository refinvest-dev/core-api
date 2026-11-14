# Reprocessing request/receipt·No-Run 응답 계약 상세안

상태: **ADR-080 채택 원칙의 구현 전 계약 설계**, 2026-11-15. 원칙을 다시 승인받는 문서가 아니다.
wire는 별도 public/internal DRAFT profile이며 runtime은 비활성이다. 기존 active API·logical hash/Compute·Run 상태·DB는 변경하지 않는다.
수명 수치의 선택은 [receipt 수명안](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION_DRAFT.md)에 별도로 기록하며 미선택안을 채택으로 취급하지 않는다.

## 기준과 범위

| 고정 source | 근거 |
|---|---|
| [ADR-080 정책](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_REVIEW_REPROCESSING_POLICY.md) | 원 request intent/facts·원 admission·별도422/Run 없는 영속 거절 원칙 |
| [DTO](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md)·[OpenAPI 설명](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md) | strict request/type/outputs/config·source optional/null·publish default·기존 RecordedFailure |
| [public YAML](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/openapi/drafts/trading-review-reprocessing-public.yaml)·[internal YAML](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/openapi/drafts/trading-review-reprocessing.yaml) | 기존8개 operation/namespace·service/위임·응답·cache/trace; 새 route를 추가하지 않음 |
| [Lifecycle](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_DATA_LIFECYCLE.md)·[API §4.2](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_REVIEW_API.md) | raw/Book/Member 삭제·최소24h mapping·audit/log/backup 경계를 유지 |

## 원 request 비교 descriptor

아래는 저장/비교할 의미의 계약이다. public request에 별도 binding object/hash를 추가하지 않는다.

| 묶음 | 비교 규칙 |
|---|---|
| namespace/대상 | 검증 owner·기존 public operation class·key, exact Book/source Revision·optional source Analysis |
| 요청 의도 | exact type/outputs·effective publish·reason·명시 retry parent, 요청 full Target/Config·원 request profile |
| profile 정규화 | 이 versioned 후보에서는 source Analysis omitted/null을 없음으로, publish omitted/true를 true로 비교. false는 다름 |
| presence 보존 | retry parent null·publish null·중복/추가 key·coercion은 거절. 미사용 Target component의 omitted/null을 임의 합치지 않음 |
| Config/VO | Analysis 포함은 완전 Config 필수. 원 Decimal/VO 동등성 규칙을 따르고 배열을 임의 sort하거나 최신 default를 주입하지 않음 |
| 검증 facts | 실제 확인한 source/target/config pin·profile·원 decision·증거. 요청 body의 주장/일부 facts는 실행 가능한 full binding이 아님 |

JSON object key 순서는 의미 비교 대상이 아니지만 값/표현의 동등성은 정본 VO/profile이 허용한 범위만 인정한다.
same-key에서 reason/parent가 다르면 conflict다. cross-key의 reason 차이는 요청별 audit/권한 보존 조건으로 수렴하고 parent 자격은 별도 검증한다.
source ID가 다르면 같은 content hash라도 같은 execution으로 재라벨하지 않는다. 신규 root/hash·기존 Compute fingerprint를 descriptor hash로 재사용하지 않는다.
새 binding digest/serializer·원 body 전체 보관을 선제 요구하지 않는다. 보존 descriptor만으로 같은 요청/다른 요청을 손실 없이 판정해야 한다.

## admission 순서와 중복 실패 우선순위

1. service/위임·현재 Member/owner·strict 형식/필수·nullable와 현재 scope를 검증한다. foreign/현재 삭제된 resource는 기존 비노출/삭제 규칙이 우선이다.
2. known key는 원 profile/descriptor·decision을 복구해 현재 접근 승인 뒤 replay/conflict를 판정한다. 최신 eligibility·현재 default로 원 outcome을 바꾸지 않는다.
3. 새 key는 exact source/profile을 읽어 필요 pin의 정상 부족·손상·기술 장애를 분류한다. retry parent가 있으면 현재 접근/정확 parent/chain·원 effect를 검증하며 No-Run으로 우회하지 않는다.
4. 정상 legacy profile의 필요 pin 부족이 독립된 authoritative evidence로 확정되면 No-Run으로 거절한다. 이 결정은 target registry/Compute가 실행 가능하다는 주장이 아니므로 불필요한 실행 eligibility I/O를 하지 않는다.
5. source pin이 충분하면 full target/config/hash·지원/compatibility·logical/exact execution을 검증한다. 확정 business failure는 RecordedFailure, 일시 장애/손상은 해당 기술 error/복구 경로다.
6. 같은 실제 Tx에서 원 fence·현재 Guard·source/evidence incarnation·descriptor/decision/mapping과 필요한 Run/Dispatch/lineage를 결속하고 commit 확인 후 응답한다. 외부 I/O는 Tx 밖이다.

source를 읽지 못했거나 당시 profile의 정상성을 증명하지 못한 경우를 legacy 부족으로 확정하지 않는다.
복수 문제가 있을 때 위 단계에서 확정된 우선 원인을 사용한다. 기술 장애를 마스킹하거나 불명 facts로 다른 business 실패 Run을 완성하지 않는다.
정확 전체 writer/Member-commit 잠금·snapshot/CAS는 기존 WR/WF/물리 검증 대상이다. 이 순서만으로 경합을 검증한 것은 아니다.

## 세 admission outcome의 descriptor/decision

| 결과 | 필수 의미 | Run/효과 |
|---|---|---|
| 정상 접수/동일 실행 수렴 | 원 request descriptor·완전한 verified 실행 facts·접수 decision·정확 Run reference·요청별 audit/시각 | 새 접수라면 필요한 Run/Dispatch, 수렴이면 기존 Run·새 request mapping만 |
| RecordedFailure | 위 비교/실패에 필요한 full pin·원 오류 의미·정확 FAILED Run·commit된 decision | 새 Compute/Revision/Analysis dispatch 없음 |
| No-Run rejection | 원 request descriptor·확인 source/profile/부족 evidence·고정 거절 code·decision 시각·요청별 audit | Run/attempt/Dispatch·logical/execution binding 생성 없음 |

원202/현재FAILED는 원202로, 기록410/422는 원 오류 의미로 replay한다. 다른 key의 동일FAILED는 검증된 원 admission 종류를 계승하고 원 Run.reason을 바꾸지 않는다.
원 decision/profile/evidence가 불명하면 currentFAILED나 SQL NULL/ID0·최신 source로 원 receipt를 추정하지 않는다.

## No-Run422의 정확한 wire 초안

schema: `UnpinnedSourceRejectionV2Draft`, code: **REPROCESSING_SOURCE_PIN_UNAVAILABLE**.
허용 필드는 아래4개뿐이며 모두 required/non-null이다. 추가 필드·Run/receipt/source ID·details·locator/token은 허용하지 않는다.

| 필드 | 타입/의미 |
|---|---|
| code | string, 고정 literal REPROCESSING_SOURCE_PIN_UNAVAILABLE |
| message | 안전한 string. 내부 누락 field/거래값을 나열하지 않음 |
| traceId | 현재 요청의 correlation identifier. 원 receipt trace를 고정 복사하지 않음 |
| retryable | boolean, false. 같은 key를 재전송해 원 decision을 복구하는 것과 새 계산 retry는 구분 |

```json
{"code":"REPROCESSING_SOURCE_PIN_UNAVAILABLE","message":"이 거래 기록의 재처리 입력 근거를 확인할 수 없습니다.","traceId":"synthetic-current-request-trace","retryable":false}
```

위 예제는 transport synthetic이며 실제 source·DB/commit 증거가 아니다.
public/internal의 **admit422만** RecordedFailure branch와 이 No-Run branch를 oneOf로 허용한다. code 집합을 분리해 서로 겹치지 않게 한다.
기존 RecordedFailure의 code에서 Run ID를 생략/null/0으로 바꾸면 계속 거절한다. No-Run code에 Run ID를 붙여도 거절한다.
Query/cancel·일반422·다른 status에서는 No-Run code/variant를 허용하지 않는다. 원8개 operation·namespace·Vary/no-store·현재 authority 조건을 유지한다.
기존 active OpenAPI의 ErrorResponse나 모든422를 일괄 완화하지 않는다. 표준 decoder가 union·closed object를 보존하는지는 consumer gate다.
새 receipt ID/조회 endpoint·public Run 상태·reason enum은 추가하지 않는다.

## No-Run receipt와 current authority

영속화하는 것은 원 descriptor·거절 의미/evidence와 mapping이다. HTTP status/header를 Domain에 저장하라는 뜻이 아니며 mapper가 원 profile의422 의미를 구성한다.
최초 거절도 commit 확인 뒤에만 반환한다. UNKNOWN은 기존503/원 fence 복구이며 이 No-Run code로 바꾸지 않는다.
같은 key의 동일 descriptor는 현재 접근 승인 뒤 원 code/거절을 replay한다. source evidence가 나중에 생겨도 자동 실행으로 변경하지 않는다.
다른 descriptor는 conflict, 별도 승인 복구 후 실행은 새 key의 정상 admission이다. No-Run은 FAILED Run/명시 retry parent가 아니다.
raw-only는 retained Ledger/Analysis와 Book의 원 mapping을 일괄 무효화하지 않는다. Book/Member/source 접근 차단 시 현재 비노출/삭제 규칙이 우선한다.
브라우저 abort/응답 timeout·현재 trace는 원 decision을 취소/변경하지 않는다. 장기 deadline·새 attempt를 replay로 연장하지 않는다.

## 수명/consumer와 구현 전 조건

수명 수치는 [별도 수명안](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION_DRAFT.md)의 명시 선택을 기다린다. 30일/Book 수명 수치를 이번 schema가 승인하지 않는다.
원 descriptor/decision을 보존할 근거 없이 key miss를 미접수로 단정하지 않는다. No-Run을 Run 수명 규칙·Audit180일에 자동 편입하지 않는다.
Core relay는422 branch·code/현재 trace·retryable을 보존하고 No-Run ID를 추측하지 않는다. Web은 원 key replay와 새 요청 의도를 구분하고 abort를 durable cancel로 표시하지 않는다.
Go는 typed descriptor·확인 facts·decision을 분리하고, 불완전 No-Run evidence를 실행 함수/Domain Restore에 전달하지 않는다.
정확 descriptor 저장/retention·부족 evidence/source profile·legacy pin/receipt·Core/Web decoder·SQL/권위/UNKNOWN·삭제/운영 수용 전 runtime 활성화는 하지 않는다.

기존 WR-DRAFT-01~14·WR/WF/WUC/WFP를 재사용한다. 새 schema 예제/거절 변이는 정적 transport 검사이며 source 분류/commit·업무 semantic assertions·hash·HTTP/SQL/Compute·E2E는 미실행이다.
