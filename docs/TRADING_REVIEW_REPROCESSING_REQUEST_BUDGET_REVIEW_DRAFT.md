# ADR-082 요청 예산 조합·경계 검토

> **후속 채택(2026-11-16):** [ADR-082 확정 정책](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE.md)이 현재 기준이다.
> 아래 미채택·권고·수치 보류/int32 후보는 검토 당시 기록이다. 원 관측/고정 source는 보존하며 현재 정책을 덮어쓰지 않는다.
> 신규 profile 원칙·상한·압축 미지원/400·413·415는 채택됐고, 정확 wire/consumer·실제 검증/활성화는 별도다.

상태: **DRAFT·미채택 수치 검토**, 2026-11-16. [정책 후보](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE_DRAFT.md)·
[채택 범위 리뷰](TRADING_REVIEW_REPROCESSING_REQUEST_PROFILE_REVIEW_DRAFT.md)에 후속 구조 측정과 결정표를 연결한다.
이번 측정은 수치를 승인/활성화한 것이 아니다. ADR-080/081은 유지하고 ADR-082 채택 여부는 명시 결정 전까지 미채택으로 둔다.

## 이번 결정표

| 항목 | 현재 권고 | 수치/구현 전 조건 |
|---|---|---|
| 검증 구조·원칙 | 새 재처리 전용 closed nested·무손실/strict/presence·형식/자원 오류와 business receipt 분리 채택 권고 | 기존/shared/legacy를 서버만 축소하지 않음 |
| 시각 입력 | 새 profile의 UTC Z·0 fraction 생략/비영6자리 고정 입력 수정안 채택 권고 | hash/DTO 전체 왕복을 계약으로 고정. 자동 legacy 변환 없음 |
| int32 상한 | 저장 타입 이유의 별도 제품 상한을 도입하지 않는 방향 권고 | 기존 양수/JSON·hash 안전 범위는 유지. 무제한 integer 허용 아님 |
| body1MiB/depth16 | 초기 후보로 유지하되 아직 수치 미확정 | 아래 단위·조합·실제 caller·bounded decoder/consumer 검증 필요 |
| key/value·terms4096 | 필드·개수 한도와 전체 body 한도를 동시에 적용하는 방향 권고 |4096개에 모든 최대 길이를 넣을 수 있다는 보장은 하지 않음 |
| version 문자열 | 기존200자 제한과 새 UTF8 byte 후보의 교집합 |256byte 후보가 기존 maxLength200을 완화/대체하지 않음 |

이 표는 직전 논의의 실행 가능한 결정 범위다. 일반 다음 작업·응답 대기 시간을 채택 승인으로 해석하지 않는다.
수치 미확정은 업무 구현 때 임의로 정하거나 무제한 요청을 받자는 뜻이 아니다. profile/schema·오류/consumer 적용 전에 결정해야 한다.

## source와 측정 방식

[현재 public DRAFT](https://github.com/refinvest-dev/context/blob/bd2150941fda13940ad7f3d78188c2f48cf80ce0/openapi/drafts/trading-review-reprocessing-public.yaml)·
[shared schema](https://github.com/refinvest-dev/context/blob/bd2150941fda13940ad7f3d78188c2f48cf80ce0/openapi/core-api.yaml)·
[기존 checker](https://github.com/refinvest-dev/context/blob/bd2150941fda13940ad7f3d78188c2f48cf80ce0/scripts/verify-trading-review-reprocessing-draft.py)의 resolver/validator를 읽기 전용으로 사용했다.
실행 source는 이 고정 commit 기준이며 checker/YAML·원 hash/vector를 수정하지 않았다. 전체 checker의 모든 assertion을 재실행한 것은 아니다.
기존8개 type/output 예제와 두 synthetic 확장 payload의 현재 DRAFT 구조 검증을 수행했다. 이후 JSON 직렬화 byte와 container depth를 측정했다.
synthetic payload의 version/terms/대상은 실제 registry/lineage/hash 검증 자료가 아니다. schema 통과를 실제 실행 가능 요청/운영 inventory로 보지 않는다.

## baseline8개 관측

compact UTF8 JSON은 BOM/공백 없이 ensure_ascii=False로 생성했다. 일반 client의 실제 wire bytes가 항상 이 크기라는 뜻은 아니다.

| 예제 | compact bytes | container depth |
|---|---:|---:|
| REPARSE_SOURCE·LEDGER_ONLY |1648 |4 |
| REPARSE_SOURCE·LEDGER_AND_ANALYSIS |2321 |4 |
| REBUILD_LEDGER·LEDGER_ONLY |1648 |4 |
| REBUILD_LEDGER·LEDGER_AND_ANALYSIS |2321 |4 |
| RERUN_RECONSTRUCTION·LEDGER_ONLY |1654 |4 |
| RERUN_RECONSTRUCTION·LEDGER_AND_ANALYSIS |2327 |4 |
| RERUN_ANALYTICS·ANALYSIS_ONLY |2316 |4 |
| REEVALUATE_FINDINGS·ANALYSIS_ONLY |2363 |4 |

여기서 root object의 container depth는1이다. object/array를 통과할 때만1 증가하고 scalar는 증가시키지 않는다. 빈 object/array도 container 한 단계다.
예제 깊이4는 전체 current schema의 최대 깊이 증명이 아니다. 현재 open nested extension·향후 profile 구조도 별도로 확인해야 한다.

## terms 개수·길이·body 후보의 조합

REBUILD_LEDGER·LEDGER_ONLY 예제의 terms map만 확장했다. key는256 ASCII bytes,4096개의 서로 다른 synthetic key다.

| version value | decoded 길이 | compact UTF8 JSON | ASCII escape JSON |
|---|---|---:|---:|
| ASCII200자 |200자/200bytes |1,893,969bytes |1,893,969bytes |
| 한글85자+ASCII1자 |86자/256bytes |2,123,345bytes |3,167,825bytes |

두 payload는 현재 DRAFT의 구조 검증을 통과했고 후보 terms 개수·key256bytes·value256bytes에도 들어가지만 **둘 다 body1MiB를 초과**한다.
이는 상한 조합의 의미를 명시해야 한다는 증거다. 각 필드의 최대값이 모두 동시에 허용되는 보장과, 여러 독립 상한을 함께 적용하는 계약은 다르다.
같은 기본 예제의 compact 직렬화에서1MiB 안에 들어가는 최대 entry 수를 이 구조에 한해 탐색한 결과:

| version value | 최대 fit entries/bytes | 다음1개 추가 시 |
|---|---|---|
| ASCII200자 |2266개/1,048,509bytes |1,048,971bytes |
| UTF8 256bytes·86자 |2021개/1,048,495bytes |1,049,013bytes |

이 숫자를 새로운 제품 terms 상한으로 채택하지 않는다. 다른 field·문자열·escape/공백이면 달라지며 전체 허용 payload의 수학적 최대도 아니다.
권고는 **본문 byte·필드 byte/문자·entry 상한을 모두 만족해야 한다고 명시**하는 것이다.4096개를 긴 값으로 반드시 보내야 하는 실제 caller가 확인되면 body/개수/문자 제한을 함께 재검토한다.
body에 맞추려고 terms/Config/미사용 Target component를 자르거나 hash만 남기지 않는다. exact Target/원 request 비교 의미를 유지한다.

## VersionIdentifier200자와256byte는 다르다

현재 VersionIdentifier는 minLength1/maxLength200이다. ASCII256자는 현재 schema에서 거절됐고,86자인256UTF8 bytes 값은 구조상 허용됐다.
따라서 새 profile에256byte 후보를 두더라도 기존200자·각 필드의 원 형식과 함께 적용해야 한다. JSON Schema maxLength를 UTF8 byte 수로 오해하거나256으로 늘리지 않는다.
implementationDigest·instrument key·opaque source ID는 같은 VersionIdentifier scalar가 아니므로 원 schema별 조건을 구분한다. registry에 실제 존재하는 identifier인지의 업무 검증도 별개다.
확장 payload가 구조상 허용됐다는 사실로 non-ASCII version/임의 instrument가 실제 등록돼 있다고 주장하지 않는다.

## body·깊이 경계의 단위

같은 정상 예제 뒤에 JSON whitespace를 더해1,048,575 /1,048,576 /1,048,577 octets를 만들었다. 모두 같은 JSON document로 파싱되지만 후보1MiB 이하 여부는 앞 두 개만 해당한다.
이는 byte 측정/조건의 관측이며 서버가 실제로413을 반환했다는 HTTP 검증이 아니다. body 예산은 semantic object 크기/Content-Length만으로 판정할 수 없다.
ensure_ascii 출력 차이도 wire bytes에 영향을 준다. decoded field의 UTF8 byte 제한은 JSON escape 자체의 byte 수와 구분한다.
별도 nested container16/17을 구성해 계산 단위를 확인했다. 이 두 값은 정상 재처리 request shape가 아니며 depth16 parser 수용을 검증한 것도 아니다.
content encoding을 허용한다면 실제 수신 octet·해제 후 JSON 예산·해제 과정의 한도를 함께 정의한다. 이번에는 압축/HTTP/streaming decoder를 실행하지 않았다.

## 남은 확인과 다음 결정

| 남은 범위 | 필요한 확인 |
|---|---|
| 실제 caller/legacy | 최대 terms·원 문자열/형식·특수 escape·body 생성 방식. repository 예제만으로 운영 입력을 대표하지 않음 |
| ingress/decoder | profile별 byte/depth의 실제 위치·bounded 수신/파싱·메모리·중복/정밀도/취소·정확400/413 |
| producer/consumer | full Target/Config·원 hash·Core relay/Web status/code·같은 key와 원 receipt 의미 유지 |
| 적용 문서 | 수치·단위·교집합/초과 우선순위·현재 authority·profile/version·schema가 표현 못하는 byte/depth 검증 |

구조·시각·저장 타입 기반 int32 제한 배제의 채택 여부와 크기 수치 결정은 구분한다. 현재는 정책 결정 답변을 기다리며 수치 후보를 검토한 상태다.
새 API/schema/checker·runtime/SQL·메모리/HTTP/부하·실제 운영 inventory/E2E 수용은 없었다. 단순 측정 결과를 그 성공으로 확대하지 않는다.
