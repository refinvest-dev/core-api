# Reprocessing receipt 수명·삭제 설계안 (DRAFT)

상태: **수치/보존 방식 선택 대기**, 2026-11-15. ADR-080의 원 decision 보존·삭제 우선 원칙은 이미 채택됐다.
이 문서의30일/Book 수명 조합은 사용자의 명시 선택 전 권장안이며 현재 정책/설정/migration에 적용하지 않는다.
[응답/비교 계약](TRADING_REVIEW_REPROCESSING_ADMISSION_CONTRACT_DRAFT.md)과 함께 구현 전에 확정한다.

## 기존 기준과 새 선택의 차이

| 고정 source | 기존 값/경계 |
|---|---|
| [Lifecycle §3](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_DATA_LIFECYCLE.md) | 실패 Import metadata30일·log30일·Audit180일·backup 최대30일. No-Run receipt의 수명을 자동 지정한 것은 아님 |
| [API §4.2](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_REVIEW_API.md) | transport mapping 최소24h·Aggregate가 남는 동안 logical mapping 보존. No-Run에는 Run이 없음 |
| [채택 정책](https://github.com/refinvest-dev/context/blob/21ac058fecd7ee358c231e829b901958a8e3211a/docs/TRADING_REVIEW_REPROCESSING_POLICY.md) | 같은 key 원 거절 불변·복구 뒤 새 key 정상 admission·현재 Guard/삭제·수명/재사용은 구현 전 문서 확정 |

## 권장안과 대안

| 선택 | 장점/비용 | 상태 |
|---|---|---|
| 상세 진단30일 + 최소 request/decision mapping은 Book 수명 | 기존 실패 metadata와 진단 기간 정렬·같은 key 자동 재실행 방지 / 최소 매핑의 정확 내용·삭제 참여 필요 | 권장·선택 대기 |
| receipt 전체를 Book 수명 | 진단 근거/구현이 단순 / 진단을 더 오래 보관하는 필요성·민감 범위/크기 검토 필요 | 대안·미선택 |
| 별도 수명 논의 | 수치/보존 범위 재검토 가능 / 관련 admission 활성화는 대기 | 선택 가능 |

질문에서 권장안을 미리 선택해 보여준 것은 동의가 아니다. 답변 부재를 승인으로 간주하지 않는다.

## 권장안을 선택한다면 보존할 두 범위

| 범위 | 필요 내용 | 만료/삭제 후보 |
|---|---|---|
| 상세 진단 근거 | 승인된 source/profile·정상 pin 부족 판단의 제한된 evidence/version·운영 진단. raw/request body·credential/locator는 제외 | 최초 decision 시각+P30D, replay로 연장하지 않음. source/Book/Member 명시 삭제가 우선 |
| 최소 request/decision mapping | 원 namespace/key 관계·원 request/contract profile·동일/상이 요청을 판정할 lossless descriptor·원 reason/명시 parent audit·거절 code/의미·원 시각·target/삭제 scope 결속 | Book 수명 후보. 관계 source/Book/Member 삭제의 control/closure에 포함. 일반 replay 접근은 항상 현재 Guard |

최소 매핑은 단순 key 존재 marker가 아니다. 원 요청 비교·audit·거절 replay를 보장할 충분한 의미가 필요하다.
필드/descriptor 크기·VO/projection·필요성·민감 분류를 저장 설계에서 확정한다. fingerprint 하나만 남겨 원 profile/의미를 복구할 수 있다고 추정하지 않는다.
추가 진단 evidence가 만료돼도 정상 replay/conflict에 필요한 descriptor·결정 근거 식별/감사 의미를 버리지 않는다.
필수 비교/결정 자료까지 소실됐다면 단순 marker로 새 admission을 허용하지 않고 원 불명/손상 복구 경계를 따른다.
이 안의 key 재사용은 같은 owner/operation/Book scope가 살아 있는 동안 허용하지 않는 방향이다. Book을 public namespace에 추가하는 결정은 아니다.
Book 삭제 뒤 기존 key가 다른 Book에서 재사용되는 경계·최소 suppression marker는 삭제/restore control과 함께 별도로 확정한다. 삭제된 key를 영원히 보관한다고 가정하지 않는다.

## 원칙적으로 유지할 삭제/보안 경계

- Book/Account/Member/source 삭제의 현재 Guard·접근 차단이 replay보다 우선한다. 원 거절 때문에 resource 존재/근거를 노출하지 않는다.
- Raw-only는 유지되는 Ledger/Analysis/Book·해당 요청 매핑을 일괄 purge/재실행 허가로 바꾸지 않는다. raw locator/진단 copy는 별도 삭제 대상이다.
- receipt를 Run count에 가산하지 않는다. 삭제 closure에는 No-Run mapping/진단/최소 매핑을 포함하고 실제 purge/backup 검증을 수행한다.
- 삭제와 admission은 같은 source/Book Guard·writer/Tx 규칙에 참여한다. 삭제 후 late decision 생성·상세 evidence 재유입/승격을 막는다.
- 최소 매핑은 일반 운영 log나 Audit Event가 아니다. 기존 log30일/Audit180일을 새 receipt TTL로 자동 적용하지 않는다.
- backup은 기존 최대30일·tombstone replay/restore 차단을 따른다. 현재 source를 복원하기 위해 삭제된 raw/backup을 되살리지 않는다.
- 로그/오류/metric에 full request/root·key·token·거래값을 남기지 않는다. hash/HMAC만 썼다고 비식별화나 삭제 예외가 되는 것은 아니다.

## 확정/검증할 사항

1. 보존 방식·clock·정확 최소 descriptor/감사/삭제 scope·key 재사용/삭제 후 suppression 경계를 명시 선택한다.
2. source/profile·현재 owner/Book 관계·정상/No-Run receipt에 필요한 보존 범위를 대조한다. 기존 Run/원 key를 소급 변경하지 않는다.
3. 상세 cleanup·최소 매핑 보존·원 decision/replay·동시 삭제/late create·backup restore와 key expiry/재사용을 기존 WR/WF 계획으로 검증한다.
4. 선택한 값은 정본 Lifecycle/receipt 계약·필요 schema/config·consumer/저장 모델에 일관되게 반영하고 실행 증적 뒤 활성화한다.

현재 수치는 후보이며 관련 runtime/cleanup·table/TTL·semantic 수용을 구현하거나 실행하지 않았다. 수명 선택이 완료되지 않으면 관련 활성화 gate는 열지 않는다.
