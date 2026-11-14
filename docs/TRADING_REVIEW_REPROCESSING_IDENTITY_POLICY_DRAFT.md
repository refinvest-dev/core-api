# Reprocessing identity/hash 정책 논의안 (DRAFT)

> **2026-11-15 채택 반영:** ①~④의 동작 원칙은 [채택 정책](TRADING_REVIEW_REPROCESSING_POLICY.md)·ADR-080을 따른다.
> 아래는 채택 전 검토 기록과 미확정 상세다. 과거 미채택/권고 문구가 이번 명시 채택을 취소하지 않는다.
> 정확 schema/code·receipt 수명/삭제·profile/legacy 적용은 관련 구현 전에 문서로 확정하며, 현재 wire/runtime은 유지한다.

## 채택 전 검토 기록과 남은 상세


기존 통합 결정표는 채택 정책으로 연결하는 현재 결정표로 갱신됐다.


상태: **순차 논의 ①의 권장 후보·미채택**, 2026-11-14. [consumer 리뷰](TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md) 뒤의
identity/hash 의미와 신규 profile 표현을 구체화한다. 일반적인 다음 작업 요청을 정책 동의로 간주하지 않는다.
ADR-079/WP01~04·WR gate는 계속 DRAFT다. 이 문서는 기존 hash bytes·namespace·OpenAPI·Compute·schema/vector·runtime을 변경하지 않는다.
구현/호환성 증거를 확보하거나 기존 key를 조사한 결과로 읽지 않는다.

## 논의 범위와 source

논의 순서는 ① identity/hash → ② request/execution binding → ③ 원 admission receipt → ④ source pin 불명 처리다.
이번은 ①뿐이며 뒤 세 주제의 제안·대안은 채택하지 않는다. 특히 Run 없는 거절 receipt·새 422 분기는 현재 wire에 반영하지 않는다.

| 고정 source | 현재 의미/공백 |
|---|---|
| [Versioning §10/16](https://github.com/refinvest-dev/context/blob/0c1bf45e2bb74dbb63d971bd494b493419587d54/docs/TRADING_VERSIONING.md) | ownerId를 포함한 logical 업무 필드·결정론적 직렬화와 result/input hash 구분 |
| [Persistence §5](https://github.com/refinvest-dev/context/blob/0c1bf45e2bb74dbb63d971bd494b493419587d54/docs/TRADING_REVIEW_PERSISTENCE.md) | 같은 logical 경계에 ownerScope 표기. 실제 byte projection이 같다고 증명되지 않음 |
| [WP03/04](https://github.com/refinvest-dev/context/blob/0c1bf45e2bb74dbb63d971bd494b493419587d54/docs/TRADING_REVIEW_REPROCESSING_IDENTITY_DRAFT.md) | namespace/logical/full binding·retry/child 구분과 legacy/vector gate |
| [Source 리뷰](https://github.com/refinvest-dev/context/blob/0c1bf45e2bb74dbb63d971bd494b493419587d54/docs/TRADING_REVIEW_REPROCESSING_CONSUMER_REVIEW_DRAFT.md) | 조사한 재처리 구현 부재 범위와 운영/legacy 데이터 미조사, parent/child Target 차이 |
| [Normalization identity](https://github.com/refinvest-dev/compute-api/blob/113b0e20b6d660c55ac4a7f3eb00ad10eb9ac963/src/refinvest/contracts/normalization/identity.py) | 기존 별도 RFC8785 input identity와 좁은 Normalization Target. 새 logical root의 byte oracle이 아님 |

ownerId/ownerScope 표기의 충돌을 이미 해결된 사실로 기록하지 않는다. 아래 선택은 신규 profile의 후보이며,
채택 시 정확한 정본 표기·profile recipe·호환성/이관을 함께 정리해야 한다.

## 소유자 의미의 권장안

| 항목 | 이번 후보 |
|---|---|
| 업무 소유자 | Record가 Account/Book 소속·현재 권위를 검증한 소유 Member ID |
| canonical root 필드명 | Versioning 표기에 맞춰 ownerId 선택 |
| ownerScope | 소유 범위를 설명하는 내부 개념명. 별도 serialized object/추가 hash field로 확장하지 않음 |
| Book 구분 | 기존 tradingBookId로 표현. Account ID·service caller·JWT를 ownerScope에 임의 추가하지 않음 |
| 현재 권한 | owner가 hash에 있어도 접수/조회/발행 시 현재 인증·scope/Guard 검증을 대신하지 못함 |

호출 actor와 resource owner를 구분한다. body ownerId·미검증 header·호출 서비스 ID로 root owner를 정하지 않는다.
현재 단일 Member owner 모델을 기준으로 제안하며, future tenant/조직 범위를 선제 도입하지 않는다.
Book의 Account 소속은 Record 불변식/Guard에서 검증하고 새 hash 범위를 만드는 근거로 쓰지 않는다.

## 신규 profile의 일곱 필드 후보

Versioning의 업무 필드 범위는 유지하면서 owner 표기·scalar/nullable 규칙을 신규 profile 후보로 명시한다.
같은 필드 범위를 사용한다는 말은 기존 byte/digest와 호환된다는 뜻이 아니다.

| 필드 | 후보 값/근거 |
|---|---|
| ownerId | 검증한 Member ID의 양의 signed-int64 10진 문자열, 선행0 없음 |
| tradingBookId | 검증한 Book의 기존 Long/신규 Snowflake ID를 같은 규칙의 10진 문자열로 표현 |
| sourceRevisionContentHash | 정본이 해당 source 내용 identity로 정한 검증된 lowercase SHA-256. raw artifact/inputSetHash로 대체하지 않음 |
| sourceAnalysisResultHash | 허용된 요청에 source Analysis가 명시됐으면 해당 검증 결과 hash, 지정되지 않았으면 JSON null |
| targetVersionSetHash | 부모 요청에 pin한 전체 Target의 정확 hash. Normalization/Analytics child hash를 복사하지 않음 |
| analysisConfigHash | Config가 적용되면 완전한 resolved Config의 정본 hash, 적용되지 않을 때만 JSON null. 허용된 default도 고정하며 생략을 없음으로 추정하지 않음 |
| reprocessingType | 현재 다섯 type 중 정확한 값. enum을 늘리거나 unsupported Compute를 허용하지 않음 |

두 optional hash key도 항상 존재하고 값으로 null을 사용한다는 안이다. 미확인 hash를 null/0/빈 문자열로 보정하는 규칙은 아니다.
예를 들어 sourceAnalysis가 지정됐는데 결과 pin을 검증하지 못했다면 '없음'으로 바꾸지 않는다.
이런 source의 admission/profile은 ④의 별도 논의다. logical input을 확정할 수 없으면 hash를 만들지 않는다.

Book/Member 숫자 ID를 검증한 뒤 이 profile의 표현을 만든다. API의 모든 opaque ID를 numeric Member parser에 넣지 않는다.
legacy Revision/Run UUID·원 ID/token은 그대로 보존하며 source pointer의 정확 비교는 ②에서 다룬다.
hash 함수 안에서 새 Snowflake/Run/attempt ID를 생성하지 않는다.

## canonical bytes 후보

1. 위 일곱 key만 갖는 object를 구성한다. optional hash의 두 key도 생략하지 않는다.
2. ownerId/tradingBookId는 위 canonical decimal string, hash는 정본의 lowercase 64hex, type은 정확 enum, 없음은 JSON null이다.
3. key를 Unicode code point 순으로 정렬하고 UTF-8/BOM 없음·불필요한 공백 없음으로 직렬화한다.
4. 그 bytes에 SHA-256을 적용하고 lowercase 64hex digest로 표현한다. 임의 prefix/salt/profile field를 root에 추가하지 않는다.

이 root의 허용 key와 값은 ASCII string/null이며 arbitrary text·Decimal·timestamp·array를 넣지 않는다.
정렬된 key 순서는 analysisConfigHash → ownerId → reprocessingType → sourceAnalysisResultHash →
sourceRevisionContentHash → targetVersionSetHash → tradingBookId다. 이는 recipe 설명이며 계산한 golden vector가 아니다.

Target/Config·source/result의 하위 hash 계산 규칙을 이 recipe로 재정의하지 않는다. 숫자/Decimal/시각을 포함하는
다른 hash의 serializer를 문자열 전용 root로 바꾸거나, Normalization의 기존 input identity payload를 여기에 그대로 넣지 않는다.
기존 profile이 숫자형 ID·optional 생략을 사용했다면 같은 의미의 값이라도 digest가 달라진다. 새 표현을 기존 v1 호환이라고 부르지 않는다.

요청 transport의 omitted publish/default·sourceAnalysis null 처리와 이 root의 필드 직렬화는 다른 단계다.
원 request profile의 의미 검증·pin을 완료한 typed facts만 hash 입력으로 사용한다. JSON 일반 map/최신 설정을 받아 즉석 추론하지 않는다.
표현 오류를 lowercasing/trim/숫자 coercion으로 조용히 고치지 않는다. 이 후보는 입력 허용 범위를 넓히지 않는다.

## 제외할 값과 뒤 논의의 경계

| root에 넣지 않는 값 | 별도 책임 |
|---|---|
| public Idempotency-Key·operation transport metadata | owner+operation+key namespace·원 request mapping |
| exact source Revision/Analysis ID·requestedOutputs·publish 의도 | ② request/execution binding의 정확 비교·충돌 판정 |
| retry parent·reason·trigger | ② 요청 의도/원 audit와 retry 승인. 같은 계산 비교에 무엇을 넣을지 별도 확정 |
| ReprocessingRun/attempt/worker ID·lease·시각·trace/JWT/locator | 실행/관측·현재 권위·보안. deterministic 계산 identity에 넣지 않음 |
| 현재 deletion generation | 현재 source/Scope Guard 및 실행/발행 검증. 이 root에 임의 추가하지 않음 |

같은 digest가 exact source pointer/산출물/발행 의도까지 같음을 증명하지 않는다.
같은 logical key와 다른 binding이면 현재 우선안의 conflict를 검토한다. 이 제약을 풀기 위해 outputs/publish를 root에 몰래 추가하지 않는다.
retry parent를 요청 비교와 실행 동일성 비교 중 어디에 둘지, reason 변경·재발행 의도를 어떻게 다룰지는 ②다.
기존 namespace에 profile/Book을 추가해 같은 public key의 충돌을 우회하지 않는다.

## profile과 legacy 보존

profile은 사용한 field/scalar/null/정렬 recipe를 식별할 metadata로 다룬다는 안이다.
정확 literal·API/DB 필드·테이블·code package는 정하지 않으며 root에 새 profileVersion field를 넣지 않는다.
알 수 없는 legacy 기록에 필드 모양만 보고 이 profile을 붙이지 않는다. 과거 receipt/ID/hash/createdAt·wire token은 rewrite하지 않는다.

숫자→문자열이나 생략→null을 바꾸면 같은 계산이 다른 digest를 가질 수 있다. 따라서 profile별 unique 공간을 나누고
새 계산을 무조건 시작하는 것은 허용하지 않는다. 기존 logical 성공/active·원 public mapping과의 관계를 확인해야 한다.
기존 재처리 key 이력이 실제로 있다면 검증된 byte/semantic 동등성에 한해서 명시 alias/이관 경계를 설계한다.
이력이 없음을 확인한 범위는 최초 규칙으로 시작할 수 있으며 불필요한 alias/이관 체계를 만들지 않는다.
이력/동등성을 확인할 수 없는 범위는 새 profile 활성화를 보류한다. 기존 Revision/Analysis/Compute hash 보존은 별도 필수다.
같은 digest라는 이유만으로 의미가 불명인 다른 profile을 동일시하지도 않는다.

조사한 source에서 Reprocessing 구현이 없었던 범위와 실제 DB/외부 consumer 부재는 다르다.
legacy inventory·원 key/profile/namespace·uniqueness/성공 receipt 이관과 supported source 범위를 확인한다.
현재 단일 TR writer·TR만 기존 DB 인계 원칙을 유지하며 이중 쓰기·자동 baseline/rehash를 추가하지 않는다.

## 대안과 아직 내리지 않은 판정

| 선택지 | 장점/비용 | 이번 상태 |
|---|---|---|
| ownerId Member scalar 대 ownerScope object | 현재 모델에 충분하고 충돌 범위가 단순함 / future 범위 변경 시 별도 version·호환성 검토 | Member scalar 우선 제안·미채택 |
| ID 문자열 대 JSON number | 큰 ID의 표현을 명시하기 쉬움 / 기존 number profile과 digest가 달라짐 | 신규 문자열 제안·미채택 |
| optional null 대 key 생략 | 항상 일곱 key로 재현 가능 / 기존 omitted profile과 digest가 달라짐 | 명시 null 제안·미채택 |
| profile을 root 밖에서 식별 | 기존 업무 field 범위를 불필요하게 늘리지 않음 / cross-profile alias·dedup 증명 필요 | metadata 제안·미채택 |

이번에 ①의 제안을 문서화했을 뿐 정책을 채택하지 않았다. 정확 legacy byte 호환성·parent Target hash 의미·
profile literal/보관·alias/이관과 구현/consumer 증거는 여전히 확인 대상이다.

후속은 ①의 채택 범위를 확인하고 ② request/execution binding을 순차 논의하는 단계다. ③ receipt와 ④ source pin 불명은 그 뒤다.
이름만 정한 새 hash 함수·package·schema/vector·golden digest를 만들지 않는다. recipe가 채택되고 legacy/child 경계가 정해진 뒤
필요한 positive/negative vector와 Go/다른 소비자 byte 일치·원 key 보존/충돌·현재 Guard 수용을 기존 WR-G02/WR-DRAFT에 연결한다.
WR-DRAFT-01~14·새 HTTP/SQL/동시성/UNKNOWN/Compute/운영 수용은 미실행이며 이번 문서 merge/CI로 완료 처리하지 않는다.


## ① 채택 권고 범위와 적용 조건

다시 검토한 권장안은 의미/신규 표현을 설계 정책으로 채택하고 실제 활성화는 증적 뒤 진행하는 것이다.
이 표는 **채택 제안의 범위**이며 일반적인 다음 작업 요청이나 문서 merge를 명시 동의로 바꾸지 않는다. 현재 상태는 계속 미채택이다.

| 구분 | 권고 범위 |
|---|---|
| 설계로 채택 제안 | 검증 Member ownerId·일곱 업무 필드·신규 ID 문자열/명시 null·canonical bytes·원 key/receipt 보존·중복 방지 |
| 표현의 한계 | hash root 표현을 정하는 안이며 전체 API/Go typed ID를 문자열로 바꾸는 결정이 아님 |
| Config 명확화 | 적용되는 Config는 허용된 default까지 고정한 hash. 요청 생략을 null로 바꾸지 않음. versioned Analysis 포함 후보는 완전 Config 필수 |
| 적용 전 확인 | 기존 재처리 logical key/원 bytes의 실제 이력·consumer 일치·golden vector·기존 active/success/namespace 관계 |
| 이력이 없는 범위 | 확인 후 최초 규칙 적용 가능. 기존 Revision/Compute의 hash가 있다는 사실만으로 재처리 key alias가 필요하다고 보지 않음 |
| 이번에 미결 | profile literal/보관·필요시 alias, ② binding·③ 원 receipt·④ source pin·첫 업무 구현 범위 |

후속 [② request/execution binding](TRADING_REVIEW_REPROCESSING_BINDING_POLICY_DRAFT.md)은 같은 public key의 요청 비교와
다른 key의 실행 수렴·retry 자격 비교를 나눈 미채택 후보다. 같은 logical hash만으로 모든 요청을 동일시하지 않는다.
