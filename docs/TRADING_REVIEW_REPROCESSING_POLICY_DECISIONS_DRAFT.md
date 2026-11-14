# Reprocessing 순차 정책 통합 결정표 (DRAFT)

상태: **①~④ 채택/수정/보류 권고·미채택**, 2026-11-14. 이 문서는 사람이 결정할 범위를 한곳에 모은다.
아래 '채택 권고'는 이미 채택됐다는 뜻이 아니다. ADR-079/WP01~04·①~④의 실제 상태는 계속 DRAFT다.
기존 wire·hash/vector·상태/namespace·Compute·SQL/runtime은 변경하지 않는다. 문서 merge/CI와 제품 결정·활성화·업무 구현을 구분한다.

## 기준과 읽는 순서

| 고정 source | 역할 |
|---|---|
| [① identity/hash](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_REPROCESSING_IDENTITY_POLICY_DRAFT.md) | Member owner·일곱 필드·신규 문자열/null·legacy 보존 후보 |
| [② request/execution binding](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md) | same-key 요청 비교·cross-key 실행 수렴·reason/parent 자격 |
| [③ 원 admission receipt](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_REPROCESSING_RECEIPT_POLICY_DRAFT.md) | 원 decision/현재 View·key별 audit·UNKNOWN/수명 |
| [④ 정상 legacy pin 부족](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_REPROCESSING_SOURCE_PIN_POLICY_DRAFT.md) | 원인 분류·지원 source 제한·versioned No-Run 거절 후보 |
| [OpenAPI 후보](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_REPROCESSING_OPENAPI_DRAFT.md)·[수용/전파](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_WORKFLOW_CONTRACT_ACCEPTANCE_DRAFT.md) | 현재 후보422는 FAILED Run 참조 필수. WR-G01~08·WR-DRAFT-01~14 미완료 |
| [정본 Versioning](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_VERSIONING.md)·[Persistence](https://github.com/refinvest-dev/context/blob/4686b2f1efc9ffdcf6cebaad8b7d357ca730df4a/docs/TRADING_REVIEW_PERSISTENCE.md) | 기존 hash 식·소유권/unique·Run/트랜잭션 의미. 이번에 수정하지 않음 |
| [Go admission](https://github.com/refinvest-dev/trading-review-api/blob/0c60348bfd4ebce6496fc368514b5dfff2aea682/docs/design/workflow-persistence/queries-and-admission.md) | 작은 Tx-bound 참여·원 fence/Guard·일반 Update와 원자 접수 구분 |

세부 대안·원 source 관측은 위 문서를 따른다. 이 표는 새 semantic test ID·업무 상태·Run 종류나 API 필드를 만드는 장부가 아니다.

## 설계로 채택을 권하는 범위

| 항목 | 권고 선택 | 남는 적용/검증 조건 |
|---|---|---|
| ① owner 의미 | 검증한 Member를 ownerId로 표현. ownerScope는 설명 개념, Book은 tradingBookId | 현재 Account/Book 소유권·Guard는 별도. 기존 ownerScope bytes와 같다고 보지 않음 |
| ① 업무 root | 일곱 필드 유지. 적용 Config는 허용된 default까지 pin한 hash, 미사용일 때만 null | parent full Target hash와 child projection·source/result hash를 구분. exact source/outputs/publish는②에서 비교 |
| ② 비교 목적 | 원 request namespace·same-key request 의미·logical family·cross-key execution/자격을 구분 | hash 하나나 구조체 전체 비교로 모든 동등성을 결정하지 않음 |
| ② reason/parent | reason·명시 retry parent는 원 request/audit에 보존. execution equality와 권한/parent 자격은 분리 | reason 차이 수렴은 audit 보존 전제. exact FAILED parent/chain·이전 effect·단일 successor 검증 필수 |
| ③ 원 admission | 원202 뒤 현재FAILED도 접수 종류202 유지; 기록410/422는 원 의미/Run 유지 | 현재 auth/owner/Guard가 우선. Run View·trace·safe message/header는 현재 profile에 따라 구성 |
| ③ 요청별 이력 | 다른 key가 같은 Run에 수렴해도 request binding/reason/시각·decision은 구분 | 원 Run.reason·부모 key mapping을 덮지 않음. 새 Dispatch/latest 부작용 없음 |
| ④ 원인 분류 | 정상 legacy pin 부족·기술 장애/손상·원 receipt/commit UNKNOWN 분리 | 당시 정상 profile·source/type별 필요 pin을 증명. 합법 optional absence는 pin 부족이 아님 |
| 교차 불변식 | 원 key/receipt·immutable ID/hash/lineage 보존, 같은 실제 Tx·현재 Guard·UNKNOWN 원 fence 복구 | 기존 전체 writer·Member/commit 선형화·복구/삭제/운영 수용은 실제 검증 필요 |

위 범위를 설계로 채택하더라도 새로운 bytes·wire/profile·consumer·운영 활성화까지 승인하는 것은 아니다.
같은 key의 원 decision과 request profile을 보존한다. 최신 source/default로 재해석하거나 profile/Book을 namespace에 추가해 충돌을 피하지 않는다.

## 조건부 채택 또는 보완을 권하는 범위

| 항목 | 권고 판정 | 함께 정해야 하는 조건 |
|---|---|---|
| ① 신규 hash 표현 | 신규 규칙으로 ID 문자열·optional 명시null·정렬/UTF-8·SHA256를 채택 권고; 기존 이력 적용은 보류 | profile 식별/원 bytes·legacy inventory·필요시 alias·golden vector·consumer bytes·active/success dedup |
| ② reason 차이 수렴 | 각 요청 권한/audit 보존을 전제로 조건부 권고 | 저장/수명·원 Run reason과 새 request reason의 조회/consumer 의미. audit를 못 남기면 무조건 수렴한다고 구현하지 않음 |
| ②/④ request 비교 근거 | **요청 의도와 검증된 resolved facts를 구분하도록 보완 권고** | No-Run은 일부 실행 pin이 없으므로 원 request/profile·확인 evidence로 동일 요청 비교. 정확 모델/비교 규칙 필요 |
| ③ 다른 key의 동일FAILED 수렴 | 원 admission 종류 계승은 조건부 검토 | 원 decision evidence·contract profile·현재 접근·새 request audit를 증명. 현재FAILED만으로202/410/422를 추정하지 않음 |
| ④ 지원 source 범위 | 검증 가능한 source/type/profile만 실행하도록 권고 | 제외 source의 schema-valid 요청에도 안전한 거절 계약 필요. UI 숨김·영구503·임의400/410으로 대신하지 않음 |
| ④ versioned No-Run 거절 | 정상 pin 부족에 대한 우선 후보, 활성화 보류 | 별도422 variant/code·원 request 비교·원자 decision/UNKNOWN·receipt 수명/삭제·Core/Web consumer·legacy/운영 수용 |

조건부는 해당 기능을 지금 활성화해도 된다는 뜻이 아니다. 조건이 미정인 지원 범위/경로는 별도로 비활성 유지하며,
다른 경로를 제한적으로 열더라도 제외 입력이 들어왔을 때의 versioned 거절·비노출 계약을 먼저 갖춰야 한다.

### 요청 의도와 resolved facts의 보완

②의 request binding은 새 wire DTO나 새 hash가 아닌 의미 비교 개념이다. 이를 다음 역할로 명확히 나눠 읽는 것을 권한다.

- **원 요청 의도:** exact source pointer·type/outputs/publish·reason/명시 parent·요청 Target/Config와 원 profile에서 허용한 omitted/default 의미.
- **검증된 facts:** 원 source/target/config의 실제 pin·검증 근거·execution 관계. 확인된 범위와 불명인 범위를 구분.

정상 실행/기록 FAILED Run에는 계약이 요구하는 완전 pin과 logical/execution 검증이 필요하다.
No-Run 후보에는 존재하지 않는 source pin/logical key를 요구하지 않는다. 대신 원 요청 비교와 정상 부족의 확정 근거를 원자 기록한다.
미확인 pin을null/0으로 보정하거나 request-only evidence를 실행 identity로 전달해서는 안 된다.
이 구분은 후속 계약 모델의 보완 권고이며 현재 C/Q/Result·schema·hash 함수에 필드를 추가하는 결정이 아니다.
원 request intent 자체가 손실됐거나 원 profile/default를 확인할 수 없으면 같은 요청이었다고 추정하지 않는다.

## 이번에는 보류하거나 제외할 것

| 항목 | 이유/경계 |
|---|---|
| 기존 hash/ID/token 일괄 변환·자동 backfill | 새 표현이 기존 bytes와 같다는 증거 없음. 실제 재처리 이력 없음 확인 시 불필요한 alias는 만들지 않음 |
| 새 binding hash·공통 BaseReceipt/Hasher·선제 table/Repository | 실제 소비자·검증 요구 전 추상화/물리 모델을 고정할 필요 없음 |
| partial pin FAILED Run·기존422의 RunID nil/0/생략 fallback | 현재 Run/RecordedFailure 불변식과 충돌. No-Run은 별도 versioned 계약 후보 |
| No-Run의 정확 수명/삭제 marker 수치 | Run이 없으므로 'Aggregate 수명 동안' 규칙으로 해결 불가. 최소24h를 자동 삭제/무기한 보관으로 해석하지 않음 |
| receipt ID/새 조회 endpoint·profile/version/code literal | 공개 필요성과 소비자/retention·기존 registry 호환성을 먼저 결정 |
| 자동 source repair·immutable overwrite·삭제 raw/backup 복원·TTL 연장 | lossless evidence 확인과 다른 일이며 별도 명시 이관/정책 필요. 기존 삭제 우선권 유지 |
| CANCELLED/UNKNOWN의 FAILED retry 취급·No-Run을 retry parent로 사용 | 원 effect/lineage·실행 identity가 다름. Worker 재개와 새 업무 attempt도 별개 |
| 공개 API·Store/Use Case/Worker·migration 구현 전환 | 실제 범위 승인·WR/WF gate·DB/ID/단일 writer/backup 인계·수용 증적이 별도 |

## 결정 후 전파할 순서

1. **정책 결정 기록:** 위 각 행의 채택/수정/보류 범위와 적용 대상 profile/source를 명시한다. 현재 권고 표를 actual adopted로 자동 치환하지 않는다.
2. **계약 보완:** owner 표기/recipe·request intent/facts·원 admission/수렴·No-Run variant/거절 code·receipt 수명/삭제를 소유 문서와 함께 정리한다.
3. **기계 계약/예제:** 활성화할 범위에 맞는 versioned OpenAPI와 positive/negative 사례·필요 vector를 정본에 작성한다. 기존 hash/wire를 유지할 범위와 변경할 범위를 명시한다.
4. **소비자/legacy:** Core relay·Go mapper/비교·Web trace/오류/abort·Compute child projection을 대조한다. 원 이력/profile·ID/hash·동등성/alias 필요 범위를 조사한다.
5. **snapshot 전파:** Context exact commit 정적 CI/impact preview → Context merge → 최신 Core snapshot refs/docs/marker 검토/반영 → 최신 sync. Go는 고정 source 연결이며 snapshot을 도입했다고 보지 않는다.
6. **구현·실행 수용:** 명시된 범위만 업무 구현하고 기존 WR/WF·TR-ACC/WUC/WFP 계획으로 실제 HTTP/권위/SQL·경합/UNKNOWN·child/삭제/수명·운영 인계를 검증한다.

계약 검토용 YAML/예제 작성과 실제 ingress/Worker 활성화는 별개다. 모든 경로를 한 번에 활성화할 의무는 없지만,
일부 source를 지원할 때에도 제외 입력/legacy key에 대한 완전한 오류·replay/비노출 정책이 필요하다.
integration 전체 sync는 E2E 전 선행하며 Web 포함이면 `-IncludeWeb`을 사용한다. CI 성공이나 문서/snapshot merge는 의미 수용 증거가 아니다.

## 수용과 운영에 남는 범위

기존 WR-G01~08·WR-DRAFT-01~14·WF-G01~08·WUC-05/WFP-P03/P11/P12를 연결하며 새 semantic test ID는 만들지 않는다.
형식 검사는 링크/정적 schema·예제 범위다. 실제 owner/Guard·Member/commit 선형화·legacy byte/Run 원 decision·동시 retry·
request/source incarnation·commit UNKNOWN·private R2/A2·ACK/수명·Analytics actual absence·DB/ID/단일 writer/backup·새 E2E는 별도 증적이다.
현재 네 후보의 작성/병합은 완료돼도 정책 채택·exact variant/retention·runtime 지원·운영 준비 완료는 아니다.

다음 검토의 우선 대상은 **조건부 표의 request intent/facts·No-Run variant와 receipt 수명/삭제·실패 수렴 의미**다.
이 세 묶음의 결정/명시 범위를 기록한 뒤 필요한 versioned 계약/consumer 작업으로 진행한다. 일반적인 다음 작업을 묵시적 채택이나 구현 승인으로 확대하지 않는다.
