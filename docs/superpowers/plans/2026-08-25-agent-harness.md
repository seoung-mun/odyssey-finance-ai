# Project Agent Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 루트 메인 에이전트가 Spring Boot·FastAPI·React 빌더와 독립 QA 팀을 구조화된 문맥으로 호출하고, 검증·수정·자동 커밋까지 수행하도록 저장소의 `AGENTS.md` 계층을 개편한다.

**Architecture:** 루트 `AGENTS.md`는 작업 그래프, 구조화 패킷, Git과 QA 게이트만 소유한다. 각 서비스 폴더의 `AGENTS.md`는 그 서비스의 구현·검증 규칙만 소유하며, 빌더 웨이브와 읽기 전용 QA 웨이브를 분리한다.

**Tech Stack:** Markdown instructions, Codex native subagents, Git Flow, Spring Boot 3.3/Java 21, FastAPI/Python 3.11, React 18/Vite/TypeScript

**Spec:** `docs/superpowers/specs/2026-08-25-agent-harness-design.md`

## Global Constraints

- 별도 하네스 프레임워크와 Ralph Loop 구현체를 설치하지 않는다.
- 메인은 최대 3개 서브에이전트만 병렬 호출하고 서브에이전트의 재귀 위임을 금지한다.
- 공통 문맥은 `AGENTS.md`, 실제 근거는 저장소 파일, 작업별 차이는 구조화 패킷으로 전달한다.
- 서브에이전트는 커밋하지 않고 메인만 통합 검증 후 커밋한다.
- 브랜치는 `feature/<서비스>-<내용>` 형식이며 에이전트 이름을 넣지 않는다.
- push, PR, merge, release, 배포는 사용자 승인 전 수행하지 않는다.
- 같은 완료 조건의 빌드·QA 수정 루프는 합쳐서 최대 2회다.
- QA 에이전트는 읽기 전용이며 증거와 최소 재현 절차를 포함한 발견 사항만 보고한다.
- 미확정 설계는 `docs/미확정-설계.md`에 일괄 기록하고 구현하지 않으며, 질문은 빌더 웨이브
  전후에 묶어서 보고한다.
- 확정 작업 우선순위는 확정 DB/API, CRUD, 확정 계산, 프론트 수직 흐름, LLM 운영 연결,
  실측 기반 인프라 순서이며 의존관계가 우선한다.
- 모든 빌더 패킷에는 `evaluation`의 `baseline`, `acceptance`, `focused_tests`,
  `adversarial_cases`, `full_regression`을 포함하고 평가가 없으면 구현하지 않는다.
- 단순 CRUD 평가는 패킷에만 두고 금융 계산·트랜잭션·LLM·교차 서비스 평가는
  `docs/evals/<task>.md`를 코드보다 먼저 커밋한다. 읽기 전용 평가 에이전트가 초안을 만들고
  메인이 계약과 테스트 가능성을 확인한다.
- 기존 baseline 실패는 기록하고 무관한 수정은 하지 않으며 blocker/high는 반드시 해결한다.
- medium/low는 근거와 영향도를 TODO에 기록할 수 있다.
- 숫자는 결정론적 엔진과 몬테카를로에서만 나오고 LLM은 확정된 JSON을 설명만 한다.

---

### Task 1: 루트 메인 오케스트레이터 규칙 개편

**Files:**
- Modify: `AGENTS.md`
- Reference: `TODO.md`
- Reference: `docs/기획서.md`
- Reference: `.agents/skills/git-flow/SKILL.md`

**Interfaces:**
- Consumes: 설계 문서의 메인 루프, 구조화 작업 패킷, Git 경계, QA 웨이브
- Produces: 모든 서비스 에이전트가 상속할 공통 규칙과 메인 에이전트 실행 프로토콜

- [ ] **Step 1: 현재 규칙에서 오래된 결정을 식별한다**

Run:

```bash
rg -n "bench|모델 선정|미확정|Redis|재시도|브랜치|에이전트|QA" AGENTS.md TODO.md docs/기획서.md analysis-api/bench/qwen35-2b-evaluation.md
```

Expected: 루트의 과거 벤치마크 문구와 미확정 목록이 현재 `TODO.md` 및 Qwen 평가 문서와 어긋나는 지점을 확인한다.

- [ ] **Step 2: 루트 `AGENTS.md`를 메인 하네스 중심으로 다시 작성한다**

다음 절을 이 순서로 둔다.

```markdown
# 성문 — 메인 에이전트 하네스
## 목적과 불변조건
## 진실의 원천과 읽는 순서
## 메인 에이전트 실행 루프
## 병렬화와 파일 소유권
## 구조화 작업 패킷
## 빌더 결과 보고 형식
## QA 웨이브
## 전체 회귀 게이트
## Git 자동화
## 중단하고 사용자에게 물을 조건
## 마감 기간 금지 작업
```

반드시 다음 규칙을 명시한다.

- 완료 여부는 `TODO.md` 체크가 아니라 실제 코드와 검증 결과로 판단한다.
- 메인이 계약과 의존관계를 확정한 뒤 독립 작업만 최대 3개로 나눈다.
- 빌더는 서로 겹치지 않는 `owns` 경로만 수정한다.
- 루트 문서, 공용 API 명세, `TODO.md`와 커밋은 메인만 담당한다.
- 작업 패킷 필드는 `task`, `goal`, `read`, `owns`, `contract`, `done`, `verify`, `stop`, `report`로 고정한다.
- 사용자 결정이 저장소에 없을 때만 관련 최근 대화를 최소한으로 전달한다.
- QA는 `계약·데이터`, `적대적 서비스`, `E2E·변경범위` 세 역할로 빌더 뒤에 실행한다.
- QA 발견 형식은 `severity`, `evidence`, `reproduce`, `impact`, `owner`로 고정한다.
- `feature/*` 브랜치와 Conventional Commits를 사용하며 `codex`, `claude`를 이름에 넣지 않는다.
- DB/API 계약, 트랜잭션·비동기 경계, 새 인프라·의존성은 사용자 승인 전 확정하지 않는다.
- 미확정 설계는 `docs/미확정-설계.md`에 ID, 쟁점, 근거, 선택지, 권장안, 영향 경로를
  기록하고 구현하지 않는다. 질문은 빌더 웨이브 전후에 묶어서 보고한다.
- 확정 작업은 확정 DB/API, CRUD, 확정 계산, 프론트 수직 흐름, LLM 운영 연결, 실측 기반
  인프라 순으로 우선한다. 의존관계가 우선한다.
- 모든 빌더 패킷에 `evaluation` 하위 필드(`baseline`, `acceptance`, `focused_tests`,
  `adversarial_cases`, `full_regression`)를 포함하고 평가가 없으면 구현하지 않는다.
- 금융 계산·트랜잭션·LLM·교차 서비스 평가는 `docs/evals/<task>.md`를 코드보다 먼저
  커밋하며, 읽기 전용 평가 에이전트가 초안을 만들고 메인이 계약·테스트 가능성을 확인한다.
- 기존 baseline 실패는 기록하고 무관한 수정은 하지 않으며 blocker/high는 반드시 해결한다.
  medium/low는 근거와 영향도를 TODO에 기록할 수 있다.

- [ ] **Step 3: 구조와 금지 규칙을 검사한다**

Run:

```bash
rg -n "구조화 작업 패킷|QA 웨이브|전체 회귀|feature/<서비스>-<내용>|최대 2회|push|재귀 위임" AGENTS.md
rg -n "codex/|claude/|revfactory/harness 설치|Ralph Loop 설치" AGENTS.md
git diff --check -- AGENTS.md
```

Expected: 첫 명령은 모든 필수 규칙을 찾고, 두 번째 명령은 결과가 없으며, diff check가 성공한다.

- [ ] **Step 4: 루트 규칙을 커밋한다**

```bash
git add AGENTS.md
git commit -m "docs(docs): 메인 에이전트 하네스 규칙 추가" -m "공통 문맥과 작업별 패킷을 분리해 병렬 작업의 충돌과 반복 토큰을 줄인다."
```

Expected: 훅을 우회하지 않고 문서 커밋 1개가 생성된다.

---

### Task 2: Analysis 빌더·QA 규칙 최신화

**Files:**
- Modify: `analysis-api/AGENTS.md`
- Reference: `analysis-api/bench/qwen35-2b-evaluation.md`
- Reference: `analysis-api/app/main.py`
- Reference: `analysis-api/tests/`

**Interfaces:**
- Consumes: 루트의 구조화 패킷과 숫자·LLM 분리 원칙
- Produces: Analysis 소유 경로, 운영 LLM 정책, Python 검증 명령과 적대적 QA 기준

- [ ] **Step 1: 실제 Analysis 구조와 명령을 다시 확인한다**

Run:

```bash
find analysis-api/app analysis-api/engine analysis-api/tests -maxdepth 2 -type f | sort
sed -n '1,180p' analysis-api/bench/qwen35-2b-evaluation.md
sed -n '1,220p' analysis-api/pyproject.toml
```

Expected: 엔진·FastAPI·테스트 경계와 Qwen 운영 정책을 실제 파일에서 확인한다.

- [ ] **Step 2: `analysis-api/AGENTS.md`를 서비스 실행 규칙으로 갱신한다**

기존 엔진 순수성, 정수 금액, seed, 벡터 연산, 셀프체크 규칙은 유지한다. 다음 내용을 추가한다.

```markdown
## 에이전트 소유권
- Analysis 빌더는 analysis-api/ 안에서만 수정한다.
- API/·TODO.md·docs/와 다른 서비스 변경이 필요하면 중단하고 메인에게 보고한다.

## FastAPI 경계
- app/은 요청 검증과 엔진·설명 모듈 조합을 담당한다.
- 공개/내부 계약은 API/openapi-internal.yaml을 기준으로 한다.

## 운영 LLM 정책
- qwen3.5:2b-q4_K_M, context 2K, 활성 추론 1개
- 확정 JSON만 입력하고 숫자 표기를 정규화해 원본과 대조
- 최대 2회 교정, 10~15초 timeout, 최종 200 FALLBACK
- 현재 평가 결과와 운영 연결 완료 여부를 구분

## 검증
- uv run ruff check .
- uv run python -m unittest discover -s tests -v
- 변경한 engine 모듈의 python -m 셀프체크

## 적대적 QA
- 숫자 변조·단위 변경·외국어 혼입·timeout·연결 실패·재시도 상한
```

LangGraph를 이미 사용 중인 것으로 단정하지 않는다. 모델 평가가 완료됐다는 사실과 운영 `/internal/explanations` 연결이 완료됐다는 사실도 혼동하지 않는다.

- [ ] **Step 3: Analysis 규칙과 명령을 검증한다**

Run:

```bash
rg -n "qwen3.5:2b-q4_K_M|context 2K|활성 추론 1개|최대 2회|FALLBACK|unittest discover" analysis-api/AGENTS.md
cd analysis-api && uv run ruff check . && uv run python -m unittest discover -s tests -v
```

Expected: 필수 운영 규칙이 모두 검색되고 Ruff와 전체 unittest가 성공한다.

- [ ] **Step 4: Analysis 규칙을 커밋한다**

```bash
git add analysis-api/AGENTS.md
git commit -m "docs(analysis): 빌더와 LLM QA 규칙 최신화" -m "모델 평가와 운영 구현을 구분하고 숫자 가드레일의 검증 기준을 고정한다."
```

Expected: Analysis 규칙만 포함한 문서 커밋이 생성된다.

---

### Task 3: Core 빌더·트랜잭션 QA 규칙 추가

**Files:**
- Create: `core-api/AGENTS.md`
- Reference: `API/openapi-public.yaml`
- Reference: `API/openapi-internal.yaml`
- Reference: `sql/01_schema.sql`
- Reference: `sql/02_integrity.sql`
- Reference: `core-api/build.gradle.kts`

**Interfaces:**
- Consumes: 루트의 계약 승인 게이트와 Git·QA 규칙
- Produces: Spring 빌더의 계층·트랜잭션 중단 조건과 Core 검증 명령

- [ ] **Step 1: Core의 현재 구현 범위와 계약을 확인한다**

Run:

```bash
find core-api/src -type f | sort
rg -n "operationId:|x-internal-token|plan|transaction|profile" API/openapi-public.yaml API/openapi-internal.yaml sql/*.sql
```

Expected: Core는 부트스트랩 수준이며 구현 전에 따라야 할 공개·내부 API와 SQL 계약이 별도 파일에 있음을 확인한다.

- [ ] **Step 2: `core-api/AGENTS.md`를 작성한다**

다음 절을 포함한다.

```markdown
# core-api — Spring Boot 세부 규칙
## 역할과 진실의 원천
## 패키지와 계층 책임
## API·DB 계약
## 트랜잭션·동시성 결정 게이트
## FastAPI 호출 경계
## 에이전트 소유권
## 검증
## 적대적 QA
```

반드시 다음을 명시한다.

- Java 21, Spring Boot 3.3, JPA, PostgreSQL을 현재 스택으로 사용한다.
- 공개 API는 `API/openapi-public.yaml`, 내부 호출은 `API/openapi-internal.yaml`, DB는 `sql/*.sql`이 기준이다.
- Controller는 HTTP 변환, Service는 유스케이스와 트랜잭션, Repository는 영속성만 담당한다.
- 트랜잭션 경계, 멱등성 키, 동시 수정, 외부 FastAPI 호출과 DB commit 순서가 문서에 없으면 구현 전에 메인에게 선택지와 권장안을 보고한다.
- 원격 호출을 포함한 실패 경로와 계획 버전 불일치를 테스트 없이 완료 처리하지 않는다.
- Core 빌더는 `core-api/`만 수정하며 공용 API·SQL 변경은 메인에게 반환한다.
- 검증 명령은 `./gradlew spotlessCheck`와 `./gradlew check`다.
- 적대적 QA는 중복 요청, 부분 실패, timeout, 인증 누락, 동시 수정, rollback을 확인한다.

- [ ] **Step 3: Core 규칙과 기존 프로젝트 검증을 실행한다**

Run:

```bash
rg -n "openapi-public.yaml|openapi-internal.yaml|sql/\*\.sql|트랜잭션|멱등성|동시 수정|spotlessCheck|gradlew check" core-api/AGENTS.md
cd core-api && ./gradlew spotlessCheck && ./gradlew check
```

Expected: 모든 계약·검증 규칙이 검색되고 Gradle 검사가 성공한다.

- [ ] **Step 4: Core 규칙을 커밋한다**

```bash
git add core-api/AGENTS.md
git commit -m "docs(core): Spring 빌더와 트랜잭션 QA 규칙 추가" -m "미확정 트랜잭션 경계를 자동 구현하지 않고 계약 승인 뒤 검증하도록 제한한다."
```

Expected: 새 Core 규칙만 포함한 문서 커밋이 생성된다.

---

### Task 4: Web 빌더·브라우저 QA 규칙 추가

**Files:**
- Create: `web/AGENTS.md`
- Reference: `web/package.json`
- Reference: `web/src/App.tsx`
- Reference: `API/openapi-public.yaml`
- Reference: `docs/기획서.md`

**Interfaces:**
- Consumes: 공개 API 계약과 루트의 QA·소유권 규칙
- Produces: 프론트 자율 설계 범위, 핵심 흐름 우선순위와 브라우저 검증 기준

- [ ] **Step 1: Web의 현재 골격과 사용 가능한 명령을 확인한다**

Run:

```bash
find web/src -maxdepth 3 -type f | sort
sed -n '1,220p' web/package.json
sed -n '1,160p' web/src/App.tsx
```

Expected: React/Vite 기본 골격과 lint·build 명령만 존재함을 확인한다.

- [ ] **Step 2: `web/AGENTS.md`를 작성한다**

다음 절을 포함한다.

```markdown
# web — React 세부 규칙
## 역할과 진실의 원천
## 구현 우선순위
## API와 상태 처리
## UI 자율 설계 경계
## 에이전트 소유권
## 검증
## 적대적 브라우저 QA
```

반드시 다음을 명시한다.

- 기획서와 `API/openapi-public.yaml`을 제품·데이터 계약으로 사용한다.
- 로그인/프로필 입력, 목표 설정, 계획 비교, fan chart, 재계획 이력의 심사 핵심 흐름을 장식보다 먼저 완성한다.
- 모든 API 화면은 loading, empty, error, LLM fallback 상태를 갖는다.
- 금액·퍼센트·날짜 표시를 API 원본 의미와 다르게 변환하지 않는다.
- 별도 시안이 없으면 정보 구조와 시각 방향은 Web 빌더가 정하되 새 디자인 시스템, 상태 관리, 차트 의존성은 실제 화면 요구가 생길 때만 추가한다.
- Web 빌더는 `web/`만 수정하고 API 변경 필요 시 메인에게 반환한다.
- 검증 명령은 `npm run lint`, `npm run build`이며 핵심 흐름은 실제 브라우저로 확인한다.
- 적대적 QA는 느린 응답, 빈 데이터, 깨진 JSON, 연속 클릭, 모바일 폭, 키보드 조작을 확인한다.

- [ ] **Step 3: Web 규칙과 기존 골격 검증을 실행한다**

Run:

```bash
rg -n "openapi-public.yaml|loading|empty|error|fallback|npm run lint|npm run build|브라우저|키보드" web/AGENTS.md
cd web && npm run lint && npm run build
```

Expected: 핵심 UI·QA 규칙이 검색되고 lint와 production build가 성공한다.

- [ ] **Step 4: Web 규칙을 커밋한다**

```bash
git add web/AGENTS.md
git commit -m "docs(web): 프론트 빌더와 브라우저 QA 규칙 추가" -m "심사 핵심 흐름과 실패 상태를 우선하고 불필요한 프론트 의존성 도입을 막는다."
```

Expected: 새 Web 규칙만 포함한 문서 커밋이 생성된다.

---

### Task 5: 독립 QA와 전체 회귀로 하네스 검증

**Files:**
- Review: `AGENTS.md`
- Review: `analysis-api/AGENTS.md`
- Review: `core-api/AGENTS.md`
- Review: `web/AGENTS.md`
- Modify only if findings exist: the AGENTS file that owns the finding

**Interfaces:**
- Consumes: 네 AGENTS 파일과 Task 1~4의 커밋
- Produces: 중복·모순이 제거되고 실제 명령으로 검증된 에이전트 하네스

- [ ] **Step 1: 세 개의 읽기 전용 QA 패킷을 병렬 실행한다**

빌더 웨이브 전후에는 읽기 전용 평가 에이전트가 각 작업의 평가 초안을 만들고, 메인이
계약과 테스트 가능성을 확인한다. 평가가 없거나 확인되지 않은 작업은 구현하지 않는다.

QA 1 `contract-data`:

```yaml
goal: 루트와 서비스 규칙의 계약·숫자·트랜잭션 경계 모순 탐지
read: [AGENTS.md, analysis-api/AGENTS.md, core-api/AGENTS.md, API/, sql/]
owns: []
verify: [API 기준 경로, 숫자 생성 금지, 승인 게이트, retry 상한]
```

QA 2 `adversarial-rules`:

```yaml
goal: 규칙을 우회하거나 무한 반복·과도한 권한을 만들 수 있는 허점 탐지
read: [AGENTS.md, analysis-api/AGENTS.md, core-api/AGENTS.md, web/AGENTS.md]
owns: []
verify: [수정 루프 상한, 재귀 위임 금지, 외부 작업 승인, QA 읽기 전용]
```

QA 3 `scope-e2e`:

```yaml
goal: 서비스별 소유권 누락과 실제 검증 명령 불일치 탐지
read: [AGENTS.md, analysis-api/AGENTS.md, core-api/AGENTS.md, web/AGENTS.md, README.md]
owns: []
verify: [파일 소유권, Analysis/Core/Web 명령, 브라우저 QA, 전체 회귀 조건]
```

Expected: 각 QA가 `severity`, `evidence`, `reproduce`, `impact`, `owner` 형식으로 보고하며 직접 파일을 수정하지 않는다.

- [ ] **Step 2: blocker와 high 발견 사항을 메인이 수정한다**

각 발견을 실제 파일과 명령으로 재현한 뒤 해당 AGENTS 파일 한 곳에서 최소 수정한다. 증거가 없거나 재현되지 않는 지적은 수정하지 않고 QA 결과에 기각 이유를 기록한다.
기존 baseline 실패는 별도로 기록하고 무관한 수정을 하지 않는다. medium/low는 근거와
영향도를 TODO에 기록할 수 있다. blocker/high가 남아 있으면 마일스톤을 완료로 선언하지
않는다.

- [ ] **Step 3: 교차 파일 일관성을 검사한다**

Run:

```bash
rg -n "최대 3개|최대 2회|재귀 위임|읽기 전용|feature/<서비스>-<내용>" AGENTS.md
rg -n "qwen3.5:2b-q4_K_M|최대 2회|FALLBACK" analysis-api/AGENTS.md
rg -n "트랜잭션|멱등성|동시 수정" core-api/AGENTS.md
rg -n "loading|empty|error|fallback|브라우저" web/AGENTS.md
git diff --check
```

Expected: 모든 필수 규칙이 검색되고 whitespace 오류가 없다.

- [ ] **Step 4: 전체 저장소 회귀 명령을 실행한다**

Run:

```bash
cd analysis-api && uv run ruff check . && uv run python -m unittest discover -s tests -v
cd core-api && ./gradlew check
cd web && npm run lint && npm run build
```

각 명령은 저장소 루트에서 별도로 실행한다. Expected: 세 서비스 검증이 모두 exit code 0으로 끝난다.

- [ ] **Step 5: QA 수정이 있었으면 별도 커밋한다**

```bash
git add AGENTS.md analysis-api/AGENTS.md core-api/AGENTS.md web/AGENTS.md
git commit -m "docs(docs): 에이전트 하네스 QA 지적 반영" -m "독립 검증에서 재현된 규칙 충돌과 권한 경계 누락을 수정한다."
```

Expected: 수정이 있을 때만 커밋하고, 발견 사항이 없으면 빈 커밋을 만들지 않는다.

- [ ] **Step 6: 최종 상태를 보고한다**

Run:

```bash
git status --short
git log --oneline -7
```

Expected: 작업 트리가 깨끗하고 설계·계획·네 AGENTS 변경 커밋이 기능별로 구분된다. stacked branch라는 사실과 push·PR·merge·배포를 수행하지 않았음을 보고한다.
