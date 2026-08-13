# AI_AGENT.md — 에이전트 작업 워크플로우

`AGENTS.md`를 먼저 읽었다는 전제로 작성한다. 이 문서는 "무엇을" 만들지가 아니라 "어떻게" 작업을 진행할지를 다룬다.

---

## 1. 작업 시작 전 체크리스트

새 작업(이슈/티켓)을 받으면 다음을 순서대로 확인한다.

1. **어느 레포의 작업인가?** — `core-api`(도메인 로직, API), `compute-api`(백테스트 계산, 데이터 적재), `web`(UI), `context`(문서/계약)인지 먼저 구분한다. 하나의 기능이 여러 레포에 걸치는 경우(예: 새 Condition Type 추가)가 흔하므로, 영향받는 레포를 전부 나열한 뒤 시작한다.
2. **`docs/DECISIONS.md`에 관련 ADR이 있는가?** — 있다면 그 결정을 따른다. 결정과 다르게 구현해야 할 이유가 있다면, 코드를 먼저 바꾸지 말고 ADR을 수정하는 PR을 먼저 제안한다.
3. **`docs/DOMAIN.md`의 관련 Aggregate 불변식을 확인한다.** — 특히 Strategy/BacktestRun의 상태 전이 규칙을 위반하지 않는지 확인한다.
4. **Core↔Compute 경계를 넘는 변경인가?** — API 계약(`context` 레포의 OpenAPI 스펙)이 바뀌어야 한다면, 계약을 먼저 수정하고 두 레포에 각각 반영한다. 한쪽 레포만 보고 임의로 응답 필드를 추측해 구현하지 않는다.

## 2. Core (Kotlin + Spring Boot) 작업 규칙

- 패키지 구조는 `docs/ARCHITECTURE.md` §2의 모듈 경계를 따른다. `strategy`, `backtest`(오케스트레이션), `asset`, `user`, `subscription` 모듈 간 직접 참조 대신 각 모듈이 노출하는 Application Service를 통해서만 상호작용한다. 실제 Gradle 모듈/패키지 구조와 네이밍 컨벤션은 `core-api/AGENTS.md` §4를 따른다(`docs/DECISIONS.md` ADR-018).
- Compute를 호출하는 코드는 **오직 `backtest`와 `asset` 두 모듈**에만 존재한다. `backtest`는 백테스트 실행을, `asset`은 Asset/Calendar 참조 데이터 조회(`ListAssets`, `GetAssetAvailability`, `GetSeries`)를 각각 전담하며, 각 모듈은 자신의 기술 어댑터 계층(`core-api/AGENTS.md` §4 컨벤션)에 Compute 클라이언트를 격리한다. 다른 모듈(`strategy`, `user` 등)은 Compute를 직접 호출하지 않고 반드시 `backtest`/`asset` 모듈의 Use Case를 거친다. Compute의 URL, 응답 스키마를 이 어댑터 밖으로 노출하지 않는다.
- Core의 백테스트 실행 엔드포인트(Web이 호출하는 public API, `openapi/core-api.yaml`의 `POST /strategy-versions/{versionId}/backtests`)는 **비동기**다. 요청 즉시 `BacktestRun(status=PENDING)`을 반환하고, 실제 계산은 `backtest` 모듈이 Compute의 내부 API(`docs/ARCHITECTURE.md` §6, `POST /backtests`)에 위임한 뒤 상태를 폴링해 갱신한다. 절대 이 엔드포인트를 동기로 만들지 않는다 (Compute 응답을 기다리며 커넥션을 오래 잡고 있지 않는다).
- DSL 관련 필드(Condition, Signal Asset 등)는 `docs/GLOSSARY.md`의 네이밍을 그대로 코드 식별자로 사용한다. 임의로 축약하거나 다른 용어로 바꾸지 않는다.
- 테스트: Aggregate 불변식(예: "Primary Signal Asset은 정확히 1개") 위반 시나리오를 반드시 실패 테스트로 커버한다.

## 3. Compute (Python + FastAPI) 작업 규칙

- **Backtest Engine**과 **Data Ingestion**은 같은 레포 안이라도 명확히 분리된 모듈/패키지로 유지한다 (`engine/`, `ingestion/`). Ingestion 실패가 Engine에 영향을 주면 안 된다.
- 시계열 계산(Return, Change, Relative, Lag)은 `docs/DECISIONS.md` **ADR-003(4대 Temporal Rule)**을 그대로 구현한다 — 이 규칙은 이 서비스의 핵심 계약이므로 임의로 최적화하며 규칙을 변형하지 않는다. 특히 Cross-Calendar Metric Anchor Rule(**ADR-004**)을 건드리는 변경은 반드시 해당 ADR을 함께 갱신한다.
- Dataset은 항상 **Immutable Snapshot**을 참조해서 계산한다. Ingestion이 만든 최신 스냅샷을 실행 중인 백테스트가 참조하다가 중간에 갱신되는 일이 없도록 스냅샷 ID를 파라미터로 명시적으로 받는다.
- 벡터화 연산(pandas/polars)을 사용하되, Point-in-Time Correctness를 해치는 방식(예: 전체 시계열을 미리 로드해두고 미래 인덱스에 실수로 접근하는 패턴)을 피하기 위해 계산 함수는 항상 "이 시점까지 확정된 데이터"만 인자로 받도록 설계한다.
- compute-api는 외부에 인증 없이 노출하지 않는다. core-api만 호출하는 내부망 서비스로 취급한다. 구체적인 인증 방식(API Key + 네트워크 격리)은 `docs/DECISIONS.md` ADR-017 참고.

## 4. Web (Next.js) 작업 규칙

- Core API만 호출한다(`openapi/core-api.yaml`). Compute의 존재를 프론트가 알 필요가 없다.
- 백테스트 결과 대기는 **폴링**(2~3초 간격)으로 구현한다. WebSocket 등 실시간 채널은 지금 도입하지 않는다.
- Strategy Builder는 Progressive Disclosure(Level 1 → 2 → 3) 구조를 그대로 UI 상태로 반영한다. Level 3(Relative/Lag/Cross-Market)를 처음부터 노출하지 않는다.
- 차트는 `docs/ARCHITECTURE.md` §5에 명시된 라이브러리 선택을 따른다.

## 5. PR 작성 시 확인할 것

> 브랜치/커밋 컨벤션, 어떤 git 작업까지 에이전트가 자율로 해도 되는지는 `docs/GIT_WORKFLOW.md` 참고.

- 변경이 `docs/DECISIONS.md`의 기존 ADR과 충돌하지 않는지 명시한다.
- Core↔Compute API 계약을 바꿨다면 `context` 레포의 OpenAPI 스펙도 같은 PR 세트에 포함한다(레포가 다르더라도 PR 설명에 서로 링크).
- Determinism에 영향을 주는 변경(§ AGENTS.md 4항)은 PR 설명에 "Determinism 영향 없음" 또는 "영향 있음 + 마이그레이션 계획"을 명시한다.

## 6. 모호할 때

스펙에 없는 엣지 케이스를 만나면, 영향 범위에 따라 다르게 대응한다.

- **계산 엔진 핵심 로직**(Temporal Rule, Signal/Lag/Execution 판정, Determinism, Missing Session 처리 등 `docs/DECISIONS.md` ADR-002~010이 다루는 영역)에 영향을 준다면 — 임의로 구현을 정하지 않는다. 우선순위 기준(아래)으로 가장 타당한 안을 스스로 도출하되, **코드를 병합하기 전에** `docs/DECISIONS.md`에 그 판단과 근거를 담은 ADR 초안을 추가하고 사람의 리뷰를 요청한다. 여기서는 잘못된 기본값이 조용히 틀린 백테스트 결과로 이어질 수 있기 때문에 신중함이 우선한다.
- **그 외 영역**(API 필드 명명 세부사항, 에러 메시지 문구, 내부 함수 분리 방식 등 정정 비용이 낮은 결정)은 아래 우선순위 기준으로 스스로 판단해 진행하고, PR 설명에 어떤 근거로 그렇게 정했는지만 남긴다. 매번 멈추고 확인을 구하지 않는다.

이 서비스의 우선순위는 **Data Correctness > Temporal Correctness > Execution Correctness > Statistical Interpretation > Research UX > Feature Count** 순이다. 트레이드오프가 생기면 이 순서를 기준으로 판단한다.
