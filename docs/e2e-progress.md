# 백엔드 REAL E2E 진행 체크포인트

갱신: 2026-09-06 KST

## 목표 / 완료 조건

- 목표: Phase 1 설명 이전, Phase 2 적금 추천·what-if, Phase 3 로컬 챗봇을 프론트 제외 REAL Docker Compose 경계에서 검증한다.
- 완료: `docs/e2e-goal-prompt.md`의 완료 조건을 충족하고 기능 단위 커밋 후 `develop` 대상 PR을 연다.
- 브랜치: `feature/core-ai-savings-chat`

## 상태

- PASS — 격리 Docker Compose 기본 백엔드 REAL 시나리오 170단계를 최종 코드로 연속 2회 통과했다.
- PASS — 로컬 `llm` profile REAL 시나리오 174단계를 통과했다. 실제 Ollama
  `qwen3:0.6b-q4_K_M` 호출과 Ollama 중단 시 서비스 생존·fallback을 확인했다.
- PASS — OpenAPI 공개 41개 중 Google 실자격증명이 필요한 1개를 제외한 40개 operation이
  실제 HTTPS 성공 응답을 반환했다. Google 교환은 잘못된 token 401 폐쇄 경계를 확인했다.
- PASS — 적금 추천·what-if의 RULE 조건 제한, 기간 초과, 한도, 소유권, ACTIVE 계획 입력과
  계획 테이블 row-count 불변을 실제 API/DB로 확인했다.
- PASS — 챗 원문 Redis 미저장, TTL, 동시 lock 409, Redis 장애 stateless fallback과 Ollama
  장애 결정론 fallback을 확인했다.
- PASS — 운영 Analysis 이미지에는 PyTorch와 uv가 없고 크기는 76,055,439 bytes다. 운영 Compose는
  PostgreSQL·Redis·Analysis·Core·Caddy만 포함하며 Ollama·Node/Web을 포함하지 않는다.
- PASS — 운영 Compose를 격리 프로젝트로 실제 빌드·기동해 5개 서비스 health와 Caddy HTTPS를
  확인한 뒤 컨테이너·볼륨을 정리했다.
- PASS — Core `spotlessCheck check`, Analysis `uv sync --frozen --no-dev`·Ruff·90개 unittest,
  QA 러너 6개 unittest·정적 검사·self-check가 최종 통과했다.
- TODO — 독립 검증, 기능 단위 커밋, push/PR.

## 발견 / 수정한 버그

- 수정 — PostgreSQL nullable `NUMERIC max_limit`를 JDBC `Long.class`로 직접 읽어 추천 API가 409가 되던 문제. `BigDecimal.longValueExact()` 경계로 변경.
- 수정 — CORS 403처럼 JSON이 아닌 오류 응답도 REAL 러너가 증거로 보존하도록 응답 파서를 보강했다.
- 미해결 — 없음.

## 최신 실행

```text
python3 scripts/real_scenario_qa.py --backend-only
REAL 시나리오 QA 통과: 170개 단계 (연속 2회)

python3 scripts/real_scenario_qa.py --backend-only --llm
REAL 시나리오 QA 통과: 174개 단계

docker compose -f docker-compose.prod.yml -f <local-smoke-override> up --build --wait
PostgreSQL·Redis·Analysis·Core healthy, Caddy HTTPS /actuator/health=UP
```

## 다음 시작점

1. 읽기 전용 독립 검증에서 blocker/high를 확인하고 필요한 수정만 반영한다.
2. 기능 단위로 커밋하고 `develop` 대상 PR을 연다.

## 미커밋 변경 요약

- Phase 1 Core Spring AI 설명 이전 및 Analysis 설명 라우트 제거.
- Phase 2 Finlife 카탈로그/적금 추천·what-if DB/API/계산.
- Phase 3 로컬 intent 챗/Redis 구조화 세션.
- OpenAPI, Compose, REAL QA 러너, 결정·평가 문서.
- `web/` 코드는 수정하지 않았고 기존 `web/AGENTS.md` 변경은 사용자/원격 변경으로 보존한다.
