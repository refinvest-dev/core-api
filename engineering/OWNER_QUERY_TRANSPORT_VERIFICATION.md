# Owner Query 실제 Main transport·취소 수용

기준일:2026-11-28. ADR-086의 기존 두 metadata GET·PCP-07/08을 일반 Core/Go Main으로 보완 검증했다.
[고정 source 증적](https://github.com/refinvest-dev/integration/blob/51298d741d9a3c28cd822b333d9b19f5f4eaa0fe/evidence/owner-query-service/2026-11-28-transport.json)은9개 시나리오 PASS·실행 binary/source SHA·7개 one-shot fault와 정리를 기록한다.
Core source `97248c5087a45ec7f72c997d373f379a5f28ac64`, Go `e4086b355df2cd8e29e5518c27b86645de3f491e`, harness `7da419fe62c2ba4044f9a216073b11c5b67599fc`이며 tracked source는 clean이었다.
이전 일반 Main16개·fleet13개와 합산하지 않는다. 운영 전환은 [별도 인계 조건](OWNER_QUERY_ACTIVATION_READINESS.md)을 따른다.

## 발견한 문제와 변경

첫 고정 실행은 부분 body deadline/취소·truncation까지4개 checkpoint를 통과한 뒤 header 선언 길이 초과에서 실패했다.
기존 [client](../tradingreview/adapter/reviewapi/src/main/kotlin/com/refinvest/core/tradingreview/adapter/reviewapi/OwnerMetadataHttpClient.kt)는
BodyHandler 안에서 header 검증 예외를 던졌다. public502와 logical slot 반환 뒤에도 해당 HTTP/1 TLS peer 연결이 남는 것을 관측했다.
결과 Future 완료를 실제 body exchange 종료로 해석하면 안 됐다.

거절 시에는 `RejectedBody` subscriber를 반환하고 `onSubscribe`에서 해당 subscription을 명시 취소한 뒤 고정된 실패로 완료한다.
새 demand·body 적재를 시작하지 않고 잘못된 header/token/credential·raw 예외를 보관하거나 원인에 감싸지 않는다.
다른 요청의 client 전체를 닫지 않는다. 기존 public 오류/16KiB cap·whole-response timer·admission 의미는 유지한다.
[실제 TLS 회귀](../tradingreview/adapter/reviewapi/src/test/kotlin/com/refinvest/core/tradingreview/adapter/reviewapi/OwnerMetadataHeaderCancellationTest.kt)는
서버가 body EOF를 보내기 전에 client peer가 닫히고 같은 maxInFlight1 client의 다음 요청이 성공하는지 확인한다.

[Runtime](../app/src/main/kotlin/com/refinvest/core/config/ownerquery/OwnerQueryRuntime.kt)에 고정 INFO `Owner query draining`을 추가했다.
DRAINING_PROXY 전환과 budget 시작 뒤, lock 밖에서 기록한다. 기존 complete/incomplete와 구분하고 identity/자격·payload를 넣지 않는다.
정상 body drain은 이 marker 뒤에도 첫 요청이 pending임을 확인한 후 본문을 완료시켜200과 종료 순서를 검증했다.

## 실제 수용

| 경로 | 실제 확인 |
|---|---|
| 준비/정상 | 일반 Runtime 유일성·여섯 legacy worker 부재·실제 Core/Go/authority와 Account/Book 응답 |
| partial body | header/prefix 후 whole Core deadline504·상대 소켓 EOF/reset·slot 반환. 대기 중 추가 요청503은 Go에 도달하지 않음 |
| truncation | 원 선언 길이보다 짧은 body는502, fallback 없이 다음 정상 요청 성공 |
| 선언 길이 초과 | Content-Length16385를 header에서502로 거절·상대 소켓 취소·slot 반환 |
| stream 초과 | Content-Length 없는 chunked JSON+whitespace16385 byte가16KiB cap에서502·소켓 취소·slot 반환 |
| browser disconnect | 실제 browser TLS close 후 request budget 안의 body 대기/전송 정리와 slot 반환. 즉시 취소/job 취소로 확대하지 않음 |
| 정상 종료 | drain 시작 때 body pending, release 뒤200. complete→Boot servlet graceful→Hikari 종료, process exit/primary session0 |
| 예산 부족 종료 | 진행 read503·body peer 취소·incomplete ERROR. exit0을 성공 join으로 해석하지 않음 |
| 정리/부수효과 | idempotency/Import row 미생성, 소유 fault/relay/process 종료 뒤 새 schema 제거 |

transport fault는 실제 Go 응답을 받은 뒤 metadata hop에만 적용했다. authority는 실제 Core 판정이며 인증을 mock하지 않았다.
peer EOF/reset은 fixture release 전의 실제 Core→relay TLS 관측이다. fixture guard15초에 의한 자체 종료를 성공 증거로 쓰지 않는다.
Core request3초·Go request2초·authority1초·maxInFlight1은 이 fixture 조건이며 운영값/SLO가 아니다.
한정 payload/socket 관측을 JVM 전체 thread/heap·모든 네트워크 hop/OS 자원 join의 증명으로 확대하지 않는다.

## 검증과 다음 범위

Core 대상25개(Runtime8·Boot5·worker1·새 TLS 회귀1·기존 client6·decoder4), bootJar/모듈 경계와 integration 안전 검사13개가 통과했다.
계약 sync(-IncludeWeb)와 실제9개 시나리오를 고정 source에서 통과했다. 최초 실패·startup 재시도와 수정 후 실행은 증적에서 구분한다.
전체 Core/PostgreSQL 회귀는 정확 PR head CI로 별도 확인한다. Go 마지막 cleanup의 강제 종료를 graceful 증거로 사용하지 않는다.

PCP-07/08의 이 body/deadline/거절/취소 범위를 보완했으며 운영 전체 수용이나 새 업무 HTTP 권한을 승인한 것은 아니다.
남은 [운영 인계 표](OWNER_QUERY_ACTIVATION_READINESS.md)와 후속 in-process 업무 범위를 분리한다. 운영 자료 미수집을 이유로
허용된 격리 Domain/Store까지 중단하거나, 반대로 기존 read scope를 생성/목록/Import/Worker에 확대하지 않는다.
