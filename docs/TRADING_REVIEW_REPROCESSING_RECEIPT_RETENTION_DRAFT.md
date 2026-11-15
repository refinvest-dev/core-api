# Reprocessing receipt 수명 정책 — 확정 안내

상태: **2026-11-15 명시 확정**. 현재 정본은 [receipt 수명·삭제 정책](TRADING_REVIEW_REPROCESSING_RECEIPT_RETENTION.md)·
[ADR-081](DECISIONS.md#adr-081--trading-review-reprocessing-receipt-retention)이다. 기존 링크 호환을 위해 DRAFT 경로를 유지한다.

| 구분 | 확정 수명 |
|---|---|
| 최소 request/decision 원장 | Book 수명. 관련 source/상위 명시 삭제가 우선 |
| 상세 진단 | 최초 영속 decision+P30D. replay로 연장하지 않음 |
| 삭제 후 최소 차단 정보 | 기존 삭제 control의 완료/복구 clock+P180D. 미완료/FAILED 삭제는 완료/복구 전 만료 금지 |

과거 30일+Book 수명 제안·선택 대기는 Git 이력에 보존한다. 현재 수명 선택은 완료됐으며 다시 승인을 요구하지 않는다.
차단 정보는 원 receipt/Config·진단의 추가180일 보관이 아니고 정상 replay에 쓰지 않는다. key namespace는 owner+operation으로 유지한다.
물리 model·최소 자료 표현/보안·삭제 전환·clock/CAS·실제 수용/운영 활성화는 정본의 별도 구현 전 조건을 따른다.
