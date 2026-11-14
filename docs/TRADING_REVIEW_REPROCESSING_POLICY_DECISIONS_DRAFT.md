# Reprocessing 정책 채택 결정표

상태: **①~④ 동작 원칙 채택**, 2026-11-15. 사용자의 명시 승인으로 [채택 정책](TRADING_REVIEW_REPROCESSING_POLICY.md)·
[ADR-080](DECISIONS.md#adr-080--trading-review-reprocessing-policy-adoption)을 기록했다. 이 파일의 DRAFT 경로는 기존 링크 호환을 위해 유지한다.
기존 권고/미채택 표는 채택 전 Git 이력에 남는다. 현재 규칙과 적용 한계는 채택 정책이 정본이다.

## 채택한 범위

| 범위 | 채택 결정 |
|---|---|
| ① | 검증 Member ownerId·일곱 field·신규 ID 문자열/명시 null·정렬/UTF-8/SHA256. 원 legacy bytes/key/ID/hash 보존 |
| ② | 원 request intent/facts·same-key와 cross-key 실행 비교 구분. reason 감사/권한·exact retry parent/chain/effect/single successor |
| ③ | 원202/현재FAILED·기록410/422 보존. key별 audit/decision·현재 Guard/trace. 동일FAILED 수렴은 검증된 원 admission 종류 계승 |
| ④ | 정상 legacy 부족·기술 오류·원 UNKNOWN 분리. 지원 source 제한·별도 versioned422/Run 없는 영속 거절. 복구 후 새 key, No-Run은 retry parent 아님 |

채택에는 원 decision/현재 권위·같은 실제 Tx/원 fence 복구·immutable source/삭제 우선·기존 key 충돌 우회 금지가 포함된다.
현재 wire/hash 식/vector·OpenAPI/SQL/runtime을 이번에 수정하거나 배포/업무 구현을 승인한 것은 아니다. ADR-079 전체·다른 미결 정책도 일괄 채택하지 않는다.

## 관련 구현 전에 문서 설계에서 확정

| 대상 | 이유 |
|---|---|
| exact variant/code·required/nullable·request/receipt 비교·응답 의미 | Core/Go/Web이 같은 계약을 구현해야 함. 현재422에서 Run ID 생략 fallback 금지 |
| receipt 수명/삭제 marker/key 재사용·No-Run 수명 | Run 없는 기록을 'Run 수명 동안'으로 보관할 수 없음. 최소24h가 자동 삭제/무기한 보관은 아님 |
| profile/recipe·지원 source/legacy·bytes/alias/vector·이관/복구 | 신규 표현을 원 key에 적용하기 전 실제 호환성/중복 방지 근거 필요 |
| 업무 state·Tx/claim/Guard·오류/UNKNOWN·Port 능력/잠금 의미 | 정합성·복구/공개 경계를 구현 편의로 바꾸지 않기 위함 |

## 구현 설계/검증과 함께 구체화

테이블 분할·FK/unique/index·실제 SQL/EXPLAIN·경합·부하를 검증하며 물리 설계를 조정한다.
구체 struct/함수명·파일 분리·내부 helper는 구현 중 정할 수 있다. 계약/권한/수명/오류 의미가 바뀌면 소유 설계를 먼저 갱신한다.
설계 정책 채택과 신규 업무 구현/운영 활성화는 별도이며 WR/WF/TR-ACC/WUC/WFP의 실제 수용 증적이 필요하다.

## 다음 작업

채택 여부를 다시 묻지 않고 request intent/facts·원 receipt/수렴·versioned No-Run schema/code·수명/삭제를 문서 설계에서 확정한다.
그 뒤 필요한 OpenAPI/예제·vector·consumer/legacy를 맞추고 Context exact commit CI/impact→merge→최신 snapshot/marker→sync 순서로 전파한다.
구현은 명시된 범위에서 진행한다. E2E 전 integration sync, Web 포함 시 `-IncludeWeb`을 따른다.
