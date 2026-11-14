# Reprocessing 불명 source pin 처리 논의안 (DRAFT)

현재 통합 권고는 [①~④ 결정표](TRADING_REVIEW_REPROCESSING_POLICY_DECISIONS_DRAFT.md)를 먼저 읽는다.
개별 문서의 순차 후속 안내보다 통합 표의 채택/수정/보류 권고·적용 조건을 우선 확인하되, 모두 미채택 상태다.


상태: **순차 논의 ④ 권장 후보·미채택**, 2026-11-14. [① identity/hash](TRADING_REVIEW_REPROCESSING_IDENTITY_POLICY_DRAFT.md)·
[② binding](TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md)·[③ receipt](TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md)를 잇는다.
①~④·ADR-079/WP01~04는 계속 미채택이다. 기존 wire/422·hash/vector·Compute·DB/runtime은 변경하지 않는다.
이번은 분류·대안·활성화 조건이며 정상 source를 실제 조사하거나 새 No-Run 응답을 구현한 결과가 아니다.

## 고정 source와 계약 공백

| Source | 현재 의미/공백 |
|---|---|
| [Consumer WCR-R03](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md) | 정상 legacy 형식의 pin 부족·일시 장애·손상을 구분. fake pin/미기록422 fallback 금지 |
| [WP02](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_REVIEW_WORKFLOW_CONTRACT_DRAFT.md) | 접근 가능한 source/target identity를 확정한 business failure만 FAILED Run·receipt 원자 기록 |
| [OpenAPI 후보](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)·[DTO](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_REVIEW_WORKFLOW_DTO_DRAFT.md) | 접수422는 recorded FAILED Run 참조 필수. source/version/lineage를 모르는 Run은 완성할 수 없음 |
| [③ receipt](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md) | 원 decision·현재 View/Guard·UNKNOWN 복구. 원 receipt 불명과 정상 source pin 부족은 별도 |
| [Versioning](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_VERSIONING.md)·[Lifecycle](https://github.com/refinvest-dev/context/blob/3a8a7dd5c5f95f2a9d088c8f303d4a2744e29450/docs/TRADING_DATA_LIFECYCLE.md) | immutable source/lineage·exact compatibility·삭제 우선. 새로운 조회/재처리가 raw TTL·삭제를 되돌리지 않음 |
| [Go admission](https://github.com/refinvest-dev/trading-review-api/blob/5146bded06b0be2314d278a8e70a60f020ef8289/docs/design/workflow-reprocessing-storage/admission-and-identities.md) | 원 receipt/Run·same actual Tx·pin 불명 시 임의 hash/default 금지 |

정상 legacy source는 **당시 계약에서는 유효하게 보존됐지만 요청한 재처리에 필요한 pin을 표현하지 못하는 자료**다.
그 source의 정상 조회/보존이 가능하다는 사실과 특정 재처리 type/profile 지원은 다르다.
nullable가 '없음'을 의미하는 합법적인 optional 값까지 pin 부족으로 분류하지 않는다. exact source/result/version/hash/lineage 등 필요한 사실은 type/지원 profile별로 확인한다.

## 먼저 구분할 원인

| 확인된 사실 | 처리 경계 |
|---|---|
| malformed request·미검증 caller·foreign/현재 접근 거절 | 기존 입력/인증/존재 숨김. pin 진단을 노출하지 않음; 새 Run 없음 |
| DB/registry/remote 일시 장애로 확인하지 못함 | 기술 오류/유한 재시도·복구. legacy 불가로 확정하지 않음 |
| 저장 decoding/무결성 불일치·원 필수 pin 손상 | 저장 이상/운영 복구. 정상 legacy 부족·사용자422로 숨기지 않음 |
| 정상 legacy 형식이 필요한 pin을 표현하지 못함 | 지원 source/profile 문제. 가짜 pin/무한503/Commit UNKNOWN으로 분류하지 않음 |
| 원 authoritative evidence로 lossless pin 확인 가능 | 지원 profile/근거·현재 Guard 아래 확정 가능 여부 검증. 최신 값 추론과 구분 |
| full pin은 확정됐고 raw 부재·확정 compatibility 실패 | 기존 WP02의 기록 FAILED Run·원410/422 후보를 검토. pin 부족의 No-Run 경로와 구분 |
| known key의 commit/원 receipt가 불명 | ③의 원 namespace/fence/effect 복구. 새 pin 부족 rejection으로 mapping을 덮지 않음 |

현재 source version이 오래됐다는 이유만으로 손상이라고 하지 않는다. 반대로 hash 검증 실패/불일치 증거가 있는데 legacy 정상성으로 덮지 않는다.
행/객체가 없다는 조회 결과 하나는 정상 legacy 형식·실제 삭제 완료·접수 부재의 충분한 증거가 아니다.

## 권장안과 대안

| 선택 | 장점/비용 | 이번 권고 |
|---|---|---|
| 검증 가능한 source/type/profile만 활성화 | 불변식과 기존 Run 모델 유지 / 제외 source에 대한 안전한 거절 계약도 필요 | **지원 범위의 기본 전제**로 권고. endpoint에서 schema-valid 제외 source가 들어오는 경우를 무시하지 않음 |
| versioned No-Run 영속 거절 | 불명 pin을 만들지 않고 같은 key의 원 거절을 재현 / receipt·응답·consumer·retention 계약 보강 필요 | **정상 pin 부족의 우선 후보**. 기존422에 몰래 적용하지 않음 |
| 원 evidence에 근거한 별도 복구/이관 | 일부 source를 지원 가능 / provenance·불변 source·검증·운영 비용 | 검증 가능한 경우만 조건부. request/GET에서 자동 repair하지 않음 |
| partial/unknown pin의 FAILED Run 허용 | 기존 Run 조회 경로를 사용 가능 / Run·logical key·lineage·모든 consumer 불변식 변경 | 우선 채택하지 않음. unknown을0/null로 채우는 우회 금지 |

권고는 지원 source 제한과 별도 거절 계약을 함께 설계하는 것이다. UI에서 source를 감췄다는 이유로 서버의 거절 경계가 해결되지는 않는다.
새 계약/consumer가 준비되지 않은 source/profile은 admission을 활성화하지 않는다. 현재 후보의422를400/404/410으로 임의 치환하거나503으로 영구 반복하지 않는다.
이 문서는 기존 endpoint에 사용할 임시 fallback status/code를 정하지 않는다. 활성화 전에 정상 pin 부족의 명시적인 거절 계약을 채택해야 한다.

## versioned No-Run 거절의 후보 범위

현재 access가 승인됐고 schema-valid 요청이며 정상 legacy 형식의 한계를 확정한 경우에 한한다.
영속화할 수 있는 것은 **원 request의 의미와 실행 전 거절 decision**이며, 존재하지 않는 Run/실행 pin을 만든 기록이 아니다.

| 후보에 필요한 사실 | 보존/제한 |
|---|---|
| 원 public namespace·원 request/contract profile | 기존 owner+operation+key 유지. profile/Book을 namespace에 추가해 기존 key 충돌을 회피하지 않음 |
| source pointer·요청 type/outputs/publish·reason·명시 입력 의미 | 검증한 request 의미만 lossless 보존. body 전체/credential/raw row를 저장하라는 뜻이 아님 |
| 확인된 source/profile evidence·필요 pin을 확정할 수 없다는 근거 | 확인한 사실과 미확인 부분을 구분. fake source hash/version/lineage나 logical key를 만들지 않음 |
| 안전한 원 거절 의미·decision 시각·profile | 같은 key의 의미 비교·원 decision 복구. 현재 authority/trace/허용 detail은 별도 |
| Run/Dispatch·Compute/child | 생성하지 않음. Run ID0/null을 기존 RecordedFailure의 유효 참조로 보내지 않음 |

논리 family를 확정할 수 없으므로 **완전한 execution binding/logical key가 존재한다고 요구하지 않는다**.
same-key 비교는 원 profile의 request binding과 확인된 사실/거절 근거로 수행하고, 이것을 실행 가능한 계산 identity로 승격하지 않는다.
request 대상 Target/Config도 실제 검증한 범위를 보존한다. registry 장애로 검증하지 못한 것을 'legacy pin 부족'으로 확정하는 경로는 없다.
필요 사실을 왜 확정할 수 없는지조차 알 수 없으면 이 영속 거절을 임의 기록하지 않고 원인별 복구/정책 판정을 따른다.

응답은 safe business 거절로 표현하며 **422 계열의 versioned 별도 variant**를 우선 검토한다.
정확 code literal·schema/required/nullable·공개 receipt ID 여부·profile 이름은 아직 정하지 않았다. Run 연결은 제공하지 않는 안이다.
이 variant는 현재422의 recorded Run 필수 조건을 의도적으로 변경하는 계약 후보다. 기존 YAML의 Run ID 필드를 생략하는 것으로 구현할 수 없다.
기존 pin 확정 business 실패는 WP02 RecordedFailure 경로를 유지하며 모든422를 No-Run으로 바꾸지 않는다.
일시 장애/손상/권한 오류를 이 거절로 바꾸거나 safe detail에 내부 locator·미확인 source 내용을 넣지 않는다.

## key·replay·재접수

- 기존 key의 원 receipt/commit을 먼저 복구한다. 원202/기록410/422를 현재 pin 부족을 이유로 새 거절에 재결속하지 않는다.
- 신규 No-Run decision을 기록한다면 request fence·현재 Member/owner/Guard·원 의미 비교와 decision/mapping은 같은 짧은 실제 Tx에서 확정한다.
- callback 임시 결과만으로 거절을 응답하지 않는다. commit UNKNOWN이면 원 key/fence로 복구하며 새 key/Run으로 덮지 않는다.
- 같은 key·같은 요청은 현재 접근 승인 뒤 원 거절 의미를 replay한다. source evidence가 나중에 확보돼도 자동 실행으로 바꾸지 않는다.
- 같은 key·다른 source/의도/profile 의미는 ②의 conflict 검토를 따른다. profile 변경은 namespace 분리·새 의미 덮어쓰기를 허용하지 않는다.
- 별도로 승인된 복구 후 실행을 요청하려면 새 key의 정상 admission을 검증한다. No-Run decision은 FAILED Run이 아니므로 retry parent로 쓰지 않는다.
- 새 key가 실행 가능해져 logical key를 확정했다면 기존 logical active/success·exact execution 관계도 검증한다. 과거 No-Run의 무효 hash 공간을 만들지 않는다.

같은 legacy source에 새 key를 반복해도 부족한 pin이 생기거나 Compute 호출·retry attempt가 만들어지지는 않는다.
동일 Run 없는 요청들을 content hash로 합쳐 가상의 logical family를 만들 필요도 없다. 요청별 decision 보존과 자원/abuse budget은 별도 검토한다.
replay는 현재 Guard가 우선이다. raw-only·Book 삭제/Member 차단과 거절 receipt의 조회 권한/보존을 혼합하지 않는다.
③의 최소24h/삭제/backup·legacy 이력 규칙은 참고하되 **Run이 없는 receipt의 정확 수명/최소 marker는 별도 채택 조건**이다.
기존 'Aggregate가 남는 동안 logical mapping 보존' 규칙을 존재하지 않는 Run의 무기한 보존 근거로 적용하지 않는다.

## lossless 확인·복구 경계

당시 profile과 immutable descriptor·manifest·source evidence·원 receipt가 필요한 사실을 lossless하게 증명할 때만 pin 확인을 검토한다.
현재 registry의 최신 version/default·sourceAnalysis의 최신 결과·0/빈 lineage로 채우거나 하위 hash를 부모 hash로 바꾸지 않는다.
source/profile마다 복구 가능한 field와 근거를 검증한다. 일부 hash 하나가 맞는다는 이유로 전체 provenance/tuple을 확정하지 않는다.
원 source를 수정해야 하는 이관은 별도의 명시 정책/검증 대상이다. immutable Revision을 제자리 수정하거나 synthetic Session/Run을 만들지 않는다.
삭제된 raw/backup 복원·TTL 연장·권한 재발급으로 pin을 채우는 우회는 없다. 새 upload/새 source는 별도 lineage다.
request Tx 안의 Compute/Storage I/O·자동 repair·status GET의 mutation은 금지한다. 외부 준비/검증과 짧은 원자 DB decision을 구분한다.
worker의 장기 준비·발행 시 pin/Generation/현재 권위를 다시 검증하는 기존 책임을 이 admission 분류로 대체하지 않는다.

## 채택/활성화 전 조건과 검토 사례

이 후보는 기존 WR-G01/02/07/08·WR-DRAFT-01~14·WUC-05/WFP-P03/P11/P12에 연결한다. 새 수용 ID/실행 결과는 추가하지 않는다.

1. source/type/profile별 필수 pin과 정상 legacy 한계·손상·일시 장애·원 receipt 불명을 실제 이력/consumer와 대조한다.
2. 같은 schema-valid 정상 pin 부족에서 가짜 Run/Compute 없이 안전한 versioned 거절을 commit/replay하고, 기존422 RecordedFailure와 구분한다.
3. 현재 Guard·다른 owner·raw-only/Book 삭제에서 존재/diagnostic 비노출과 정상 retained resource 보존을 검증한다.
4. 같은 key의 원202/기록실패/No-Run 거절 불변·different binding conflict·복구 후 새 key/정상 logical 수렴을 검증한다.
5. commit 실패/UNKNOWN·동시 요청·key 보관 경과/삭제/backup에서 decision 원자성·원 fence 복구·미확인 miss 비추정을 검증한다.
6. 손실 없는 evidence 확인은 현재 default/자동 repair와 구분하며 immutable 원 ID/hash/createdAt·삭제/TTL·lineage를 보존한다.

정확 No-Run variant/거절 code·원 request comparison evidence·receipt 수명/삭제·legacy mapping·Core/Web 소비자·설정/운영 수용이 남아 있다.
이번은 **계약 후보 문서화**다. active/draft OpenAPI·정적 예제/검사기·hash/vector·SQL·Go/Python/HTTP/Worker는 수정하지 않았고 semantic 사례도 미실행이다.
후속은 ①~④의 채택/보류/수정 범위를 한 번에 정리한 결정표와 필요한 versioned schema/consumer 전파 계획이다.
일반적인 다음 작업이나 문서 merge/CI를 정책 동의·gate 완료·업무 구현 전환으로 확대하지 않는다.
