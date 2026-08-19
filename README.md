# 성문

로컬(자체호스팅) LLM 기반 개인 맞춤형 금융 계획 추천 서비스.
2026 금융 AI Challenge (금융보안원) 출품작.

소비/저축 패턴을 분석해 목표 금액까지의 계획을 강도별(빡센/표준/느슨한)로 제시하고,
실제 소비를 반영해 계속 재계산하는 **동적 재무 플래너**. 개인 금융 데이터가 외부 LLM
API로 나가지 않도록 sLLM을 자체 호스팅한다.

기획 배경과 설계 근거는 [docs/기획서.md](docs/기획서.md) 참고.

## 구조

| 서비스 | 스택 | 역할 | 포트 |
|---|---|---|---|
| [core-api](core-api/) | Spring Boot 3.3 / Java 21 | 인증, 유저·거래·계획 CRUD, 게이트웨이 | 8080 |
| [analysis-api](analysis-api/) | FastAPI / Python 3.11 | 계산 엔진, 몬테카를로, LLM + 가드레일 | 8000 |
| [web](web/) | React 18 + Vite + TS | 대시보드, fan chart, 강도별 계획 비교 | 3000 |

저장소는 PostgreSQL(주 스토어), Redis(job status·LLM 캐시), Ollama(sLLM 서빙).

**설계의 핵심**: 숫자는 결정론적 계산 엔진과 몬테카를로에서만 나오고, LLM은 확정된
숫자를 자연어로 풀어 쓰기만 한다. 환각이 금액에 닿지 않도록 컴포넌트를 물리적으로
분리한 구조 ([기획서 5-0](docs/기획서.md)).

## 시작하기

**1. 최초 1회 — git 훅 활성화** (커밋 메시지·브랜치 이름 규칙을 강제한다)

```bash
git config core.hooksPath .githooks
```

**2. 환경변수**

```bash
cp .env.example .env
```

실제 값은 팀 채널에서 받을 것. `.env`는 커밋되지 않는다.

**3. 전체 스택 실행**

```bash
docker compose up -d
```

### 서비스별 개별 실행

```bash
cd analysis-api && uv run uvicorn app.main:app --reload
```

```bash
cd core-api && ./gradlew bootRun
```

```bash
cd web && npm install && npm run dev
```

## 검증

```bash
cd analysis-api && uv run ruff check . && uv run python -m engine.montecarlo
```

`engine/` 모듈은 각각 파일 하단에 assert 셀프체크를 갖고 있어 `python -m` 으로 바로
돌릴 수 있다. 리소스 벤치마크는 `uv run python -m bench.run`.

## 기여 규칙

- 브랜치·커밋·PR 규칙: [.claude/skills/git-flow/](.claude/skills/git-flow/SKILL.md)
- AI 작업 규칙: [AGENTS.md](AGENTS.md)
- `main`/`develop` 직접 푸시 금지 — `pre-push` 훅이 막는다

## 참고

- `analysis-api/data/`는 용량 문제로 gitignore됨. 원본 데이터셋은 별도 공유.
- 본선 심사 URL 접근 기간(9/7 11:00 ~ 9/11 23:59)에는 배포·부하테스트 금지.
