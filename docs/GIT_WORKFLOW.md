# GIT_WORKFLOW.md — 에이전트를 포함한 모든 기여자의 git 규칙

이 문서는 `core-api`, `compute-api`, `web` 세 레포에 공통으로 적용된다. 코딩 에이전트(Codex 등)와
사람 기여자 모두 이 규칙을 따른다.

## 1. 왜 이 문서가 필요한가

에이전트가 지시를 항상 완벽하게 따른다고 가정하지 않는다. 그래서 이 문서는 두 층으로 나뉜다:

- **정책** (이 문서에 적힌 규칙) — 에이전트에게 방향을 알려주는 용도
- **강제** (GitHub 저장소 설정) — 에이전트가 정책을 어기더라도 실제로 못 하게 막는 용도

**둘 중 강제가 진짜다.** §5의 브랜치 보호 설정이 꺼져 있다면, 이 문서의 나머지는 참고용일 뿐 안전장치가
아니다.

## 2. 에이전트가 자율적으로 해도 되는 것 / 안 되는 것

| 행위 | 에이전트 자율 수행 | 비고 |
|---|---|---|
| 로컬 커밋 | ✅ | 자주, 작은 단위로 커밋한다. 되돌리기 쉬운 지점을 많이 남기는 게 목적 |
| 브랜치 생성/전환 | ✅ | `main`/`develop`은 제외 |
| feature 브랜치로 push | ✅ | 자기가 만든 브랜치에 한함 |
| PR 생성 | ✅ | 생성은 제안일 뿐 반영이 아니므로 허용. §4 형식을 따른다 |
| `main`에 직접 push | ❌ | 항상 사람이 PR을 통해서만 |
| PR 머지 | ❌ | 항상 사람이 리뷰 후 직접 수행 |
| force-push | ❌ | 자기 혼자 쓰는 브랜치가 아니면 절대 금지. 자기 브랜치라도 먼저 확인 |
| 브랜치 삭제 | ❌ | 자기가 만든 feature 브랜치의 머지 후 정리 정도만, 그 외엔 사람이 |
| 원격 저장소 설정 변경(보호 규칙, 시크릿 등) | ❌ | 항상 사람이 |

## 3. 브랜치/커밋 컨벤션

**브랜치명**: `<type>/<repo-scope>-<짧은-설명>` — 예: `feature/strategy-crud`, `fix/backtest-lag-offby-one`

- `type`: `feature`, `fix`, `chore`, `refactor`, `docs` 중 하나

**커밋 메시지**: [Conventional Commits](https://www.conventionalcommits.org/) 형식을 따른다.

```text
<type>(<scope>): <설명>

<본문 — 무엇을, 왜. 관련 있으면 ADR/Use Case 번호를 남긴다>
```

예:

```text
feat(strategy): StrategyVersion 생성/조회 API 구현

docs/USECASES.md의 DefineStrategyVersion, GetStrategyVersion 구현.
docs/DOMAIN.md §1.2 불변식(executionAsset은 VIX 제외) 검증 포함.
```

## 4. PR 규칙

- 제목: 커밋 컨벤션과 동일한 형식
- 본문에 반드시 포함:
  - 이 PR이 구현하는 Use Case(`docs/USECASES.md`) 또는 ADR(`docs/DECISIONS.md`) 번호
  - **Core↔Compute API 계약이 바뀌는 PR이라면**, 상대 레포의 PR 링크를 반드시 같이 남긴다
    (`docs/AI_AGENT.md` §4와 동일 규칙 — 한쪽 레포만 보고 응답 필드를 추측해 구현하지 않는다)
- 머지 전 CI가 통과해야 한다: 빌드, 테스트, (core-api의 경우) ArchUnit 모듈 경계 테스트
- 리뷰어 승인 없이 머지 버튼이 아예 안 보이는 게 정상이다 — §5 확인

## 5. 실제 강제 설정 (GitHub 저장소별, 최초 1회)

`core-api`, `compute-api`, `web` 각 레포의 `main` 브랜치에 다음을 켠다
(Settings → Branches → Branch protection rules):

- [ ] **Require a pull request before merging** — 직접 push 차단
- [ ] **Require approvals** (최소 1) — 리뷰 없이 머지 불가
- [ ] **Require status checks to pass before merging** — CI(빌드/테스트/ArchUnit) 통과 필수
- [ ] **Do not allow force pushes** — `main`에 force-push 차단
- [ ] **Do not allow deletions** — `main` 삭제 차단

에이전트를 push 가능한 계정/토큰으로 쓰고 있다면, 그 계정도 위 규칙의 예외(bypass)에 넣지 않는다 —
예외를 넣는 순간 이 문서의 강제력이 사라진다.

## 6. 에이전트 실행 환경 설정 권장값

- Sandbox: `workspace-write` (파일시스템은 레포 안에서만 쓰기 허용)
- Approval: 완전 자동(`never`)보다는 `on-request`/`untrusted` 등 위험한 명령 전에 확인받는 옵션을 권장
  — 정확한 플래그명은 설치된 Codex CLI 버전의 `codex --help`로 확인한다(이 부분은 버전마다 자주 바뀐다)
- 가능하면 에이전트 세션에는 `main` push 권한이 없는 자격 증명을 쓴다 — §5가 뚫려도 이중 방어가 되도록
