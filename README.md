# Odyssey — 동적 재무 플래너

소비 이력과 목표를 바탕으로 월별 지출 계획을 제안하고, 실제 소비 변화가 생기면 계획을 다시
계산하는 2026 금융 AI Challenge 출품작이다.

금액·절감률·시뮬레이션 충족률은 결정론적 계산과 몬테카를로에서만 만든다. LLM은 확정된
결과를 설명할 뿐 숫자를 생성하거나 바꾸지 않는다. 자세한 기획 근거는
[기획서](docs/기획서.md)를 따른다.

## 구성

| 구성요소 | 역할 |
|---|---|
| `web/` | React 18·Vite 기반 로그인, 온보딩, 계획 비교와 대시보드 |
| `core-api/` | Spring Boot 3.3·Java 21 기반 인증, 거래·목표·계획 CRUD와 재계획 |
| `analysis-api/` | FastAPI·NumPy 기반 IID bootstrap 10,000경로 계산과 LLM 가드레일 |
| PostgreSQL | 프로필, 거래, 목표, 계획 버전과 계산 입력 snapshot 저장 |
| Redis Stream | LLM 설명 생성 작업만 비동기 처리 |
| Caddy | React 정적 파일 제공 및 `/api/v1/*` Core API 프록시 |

공개 HTTP 계약은 [API/openapi-public.yaml](API/openapi-public.yaml), Core–Analysis 내부 계약은
[API/openapi-internal.yaml](API/openapi-internal.yaml), DB 계약은 [sql/](sql/)에 있다.
[코드 가이드](docs/가이드.md)는 서비스별 읽기 순서를 제공한다.

## 로컬 실행

필수 도구는 Docker Compose, Java 21, Python 3.11과 Node.js다. Compose는 PostgreSQL·Redis·
Core·Analysis·Caddy를 함께 기동하고, `llm` profile은 Ollama 모델을 내려받는다.

```bash
git config core.hooksPath .githooks
cp .env.example .env
# .env의 비밀번호·토큰·JWT_SECRET·GOOGLE_CLIENT_ID를 실제 값으로 교체
docker compose --profile llm up --build
```

기본 공개 바인딩은 loopback이며 Caddy가 HTTPS를 제공한다. 실행 후 상태 확인은 다음과 같다.

```bash
curl --insecure https://localhost/actuator/health
docker compose ps
```

기존 PostgreSQL volume에는 스키마 변경을 임의로 재적용하지 않는다. Core의 Flyway migration과
현재 DB history를 확인한 뒤 해당 migration만 적용한다.

## 서비스별 개발·검증

```bash
# Analysis
cd analysis-api
uv run ruff check .
uv run python -m unittest discover -s tests -v

# Core
cd ../core-api
./gradlew check

# Web
cd ../web
npm ci
npm run lint
npm test -- --run
npm run build
```

실제 네트워크·TLS·컨테이너 경계는 별도 Compose QA로 확인한다. 이 스크립트는 격리된 포트,
볼륨, 환경변수를 만들고 종료 시 자신이 만든 자원만 정리한다.

```bash
./scripts/test_real_compose_qa.sh
./scripts/real-compose-qa.sh
```

현재 QA 상태와 남은 P0 항목은 [QA 결과](docs/QA-결과.md), 실행 기준은
[QA 가이드](docs/QA-가이드.md), 우선순위는 [TODO](TODO.md)에서 확인한다. 모듈 테스트가
통과해도 실제 수직 흐름이 자동으로 합격하는 것은 아니다.

## 개발 원칙

- 모든 금액은 원 단위 정수, 비율은 0~1 소수로 처리한다.
- 비교·시뮬레이션은 다른 사용자나 평균이 아니라 해당 사용자의 과거 거래만 사용한다.
- `simulationCoverage`는 **시뮬레이션 충족률**이며 목표 달성 확률로 표현하지 않는다.
- LLM timeout·검증 실패는 계획 저장을 되돌리지 않고 숫자 없는 fallback 설명으로 끝낸다.
- 공개 API·DB·계산·트랜잭션 경계 변경은 구현 전에 승인한다.

## 기여

- 작업 규칙: [AGENTS.md](AGENTS.md)
- 브랜치·커밋 규칙: [.agents/skills/git-flow/SKILL.md](.agents/skills/git-flow/SKILL.md)
- `main`·`develop` 직접 push, 사용자 승인 없는 push·PR·배포는 금지한다.
- 심사 URL 운영 기간(2026-09-07 11:00~2026-09-11 23:59)에는 심사용 환경을 배포·부하
  테스트·인스턴스 변경하지 않는다.
