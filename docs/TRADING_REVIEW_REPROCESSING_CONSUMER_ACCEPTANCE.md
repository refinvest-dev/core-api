# 재처리 wire·consumer 수용 계획

상태: **계약 설계·실제 consumer 수용 미실행**, 2026-11-16. [wire 상세 계약](TRADING_REVIEW_REPROCESSING_WIRE_CONTRACT.md)·[ADR-082](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE.md)를 기준으로 한다.
기존 WR-DRAFT-01~14/WF-G01·07·08/WUC-05·09~12/TR-ACC를 확장한다. 아래 행은 새 public Scenario ID나 완료된 E2E 목록이 아니다.

## 소비자별 계약

| 소비자 | 고정 책임 | 실제 수용 전 확인 |
|---|---|---|
| Web | endpoint로 profile 선택·동일 의도 key/body 유지·typed 오류/RecordedFailure/No-Run union |401 refresh는 Core 사용자 인증만,503/504 제한 재시도·502 확인/운영 복구·abort와 cancel 분리 |
| Core/향후 Gateway | 정상 인증/CSRF·고정 route·원 JSON bytes/key·서비스 credential/서명 사용자 위임 | DTO/JSON filter/APM·자동 압축 해제·retry middleware의 변형/중복 제출 차단 |
| Go inbound/application | strict req→Command/Result·현재 Member/소유권/Guard·원 profile/receipt·같은 실제 Tx | header 중복·bounded read/decoder·현재 오류 우선순위·원 admission/UNKNOWN |
| Go Compute adapter | 승인된 child projection/Config/hash/inputIdentity/fingerprint 유지 | profile 이름/원 HTTP body를 child hash에 추가하지 않고 exact wire 지원 검증 |
| Go persistence | 원 request descriptor/profile/decision·필수 증거·수명/삭제/key 보존 | Core raw body를 영구 복제하지 않음. 새로운 Request* schema를 과거 receipt/read에 적용하지 않음 |

## 계약과 실행 증거를 구분하는 표

| 기존 수용 분류 | 검증 내용 | 이번 정적 범위 / 실제 수용에서 필요한 증거 |
|---|---|---|
| WR-G01/02·WF-G01 | public/internal 접수 route가 같은 고정 profile·namespace에 연결 | schema/metadata/negative mutation / 실제 route·원 profile 저장·과거 key 비교 |
| WR-DRAFT-01~14·WF-G01 | local closed nested·필수/nullable·정수/시각·UTF8/개수 | schema 사례·synthetic boundary / 실제 decoder의 중복 escaped key·Unicode·큰 integer·메모리 |
| WR-DRAFT-01~14·WF-G01 | 1MiB 앞/같음/초과·depth·encoding/media·키 중복 | bounded static JSON probe·response shape / chunked/부정확 길이·조기 실패·실제400/413/415 우선순위 |
| WR-G01/02/07·WF-G01 | Core가 body/key를 보존하고 Go가 descriptor 의미로 비교 | 문서 조건 / 양 hop bytes 비교·공백/escape/key 순서·동일 key 의미 replay·서로 다른 요청 conflict |
| WR-G07·WF-G07 | service+위임과 browser 인증·현재 Member/Guard | 기존 security metadata만 / 실제 JWT/CSRF·서명/권위·내부401의 public500 매핑·정상403/404/410 |
| WR-G01/07·WF-G01 | 오류 union/code/retryable·safe details·current trace/cache | 예제/거절 mutation / 실제 Core mapper·Web decoder·수신 중 실패·no secret/source 존재 노출 |
| WR-G01/02/07·WUC-05/09~12 | 원202+현재FAILED·원410/422·No-Run replay·timeout/UNKNOWN | 기존 schema/metadata·새 public502/504 shape / 실제 lost response·commit UNKNOWN·원 key/fence·새 Run 미생성 |
| WR-G01/07·WF-G01 | abort/JWT 만료와 durable cancel 분리 | 계약·취소 body 금지 / 실제 browser abort·same-key recovery·명시 cancel/publish 경합 |
| WR-G03~06·WF-G02~06 | Config·Target/child projection·private/최종 공개 | 새 Request*와 기존 View 분리만 / producer→DTO→hash→Compute 전체 왕복·기존 vector·실제 child 지원 |
| WR-G08·WF-G08 | snapshot/legacy inventory·운영 적용 | 고정 commit read-only 영향 계획 / consumer snapshot 검증·정상 인증 HTTP/DB/Compute E2E·단일 writer/DB/키/복구 |

WR-G는 [기존 acceptance 후보](TRADING_REVIEW_WORKFLOW_CONTRACT_ACCEPTANCE_DRAFT.md)의 gate 명칭이며 신규 Scenario ID가 아니다.
새 checker가 테스트하는 raw JSON은 synthetic 정적 입력이다. 운영 HTTP parser·Core relay·heap/부하·source/registry 적격성·Compute 계산/ACK·SQL 검증을 실행한 것이 아니다.
read schema는 과거 Target/ID를 보존하고, 신규 input profile을 response/과거 receipt의 재검증 정책으로 쓰지 않는다.

## 실제 적용 중단 조건

- 기존 V2 후보 또는 같은 path/profile을 이미 소비한 caller가 발견돼 변경이 호환되지 않으면 별도 version 계약을 확정할 때까지 중단한다.
- 필요한 정상 legacy ID/terms/Config·caller body가 새 제한을 만족하지 못하면 값을 잘라 접수하지 않고 계약/전환을 먼저 해결한다.
- Core/proxy가 JSON/숫자/시각·encoding을 바꾸거나 hidden POST retry를 수행하면 중계를 활성화하지 않는다.
- 계약 밖 응답을502로 처리하더라도 원 접수/원 key를 보존할 수 없거나 Web이 새 key로 재실행하면 활성화를 중단한다.
- source/권위/child/private/삭제·actual absence·DB/backup gate가 미충족인 기능을 문서/정적 성공만으로 활성화하지 않는다.

## 구현 시 검증 순서

1. 확정된 schema/registry로 Core·Go·Web DTO/mapper를 구현하되 exact ID·presence·정수/시각을 보존한다. Core의 접수 body는 별도 raw bounded 경로다.
2. 실제 Core→Go 요청 bytes·key·응답/trace·인증 오류 매핑과 bounded parser·취소/메모리를 검증한다.
3. 해당 업무 Store/Tx·원 receipt/UNKNOWN·Compute 전체 왕복과 기능별 TR-ACC를 검증한다.
4. integration sync 선행 후 정상 JWT/CSRF·DB/dispatch/result 경로의 실제 E2E를 수행한다. Web 포함 시 `-IncludeWeb`을 적용한다.
5. 실패/미실행·정확 revision·consumer 배포와 운영 인계 증거를 기록한 뒤 기능별 활성화한다.
