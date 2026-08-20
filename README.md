# 동적 재무 플래너

목표 금액까지의 재무 계획을 소비 패턴 기반으로 강도별(빡센/표준/느슨한)로 제시하고,
실제 소비를 반영해 계속 재계산하는 서비스. 2026 금융 AI Challenge(금융보안원) 출품작.

기획 배경과 설계 근거는 [docs/기획서.md](docs/기획서.md) 참고.

## 설계 원칙

숫자(목표 저축액, 절감률, 도달 시점, 달성확률)는 전부 결정론적 계산 엔진과 몬테카를로
시뮬레이션에서만 나온다. LLM은 이미 확정된 숫자를 자연어로 풀어 설명할 뿐, 숫자를 직접
생성하지 않는다 — 환각이 금액에 닿지 않도록 컴포넌트를 물리적으로 분리한 구조
([기획서 5-0](docs/기획서.md)).

## 컴포넌트

### [core-api](core-api/) — Spring Boot 3.3 / Java 21 (포트 8080)

인증, 유저·거래·계획 CRUD, API 게이트웨이. 유저 프로필(월 소득, 고정비, 절감률
하한선 적용 여부), 거래 로그, 예정 지출, 계획 버전 이력을 PostgreSQL에 저장한다.

### [analysis-api](analysis-api/) — FastAPI / Python 3.11 (포트 8000)

계산 엔진 + 몬테카를로 시뮬레이션 + LLM 서빙을 맡는 별도 마이크로서비스.

- **계산 엔진** (`engine/`): 규칙 기반으로 목표 저축액, 필요 절감률, 강도별(90/50/30%
  신뢰수준) 절감률을 폐형식(closed-form) 분위수로 산출한다. ML/LLM을 쓰지 않는다 —
  사칙연산 수준 계산에 생성형 AI를 쓰면 환각 리스크만 추가된다.
- **몬테카를로 시뮬레이션**: 유동지출 분포를 블록 부트스트랩으로 리샘플링해 미래
  경로를 대량 생성한다(`[n_paths, horizon]` 벡터 연산). fan chart용 10/50/90
  percentile band를 만들고, 계산 엔진은 그 분포 위에서 강도별 절감률을 재시뮬레이션
  없이 읽어낸다.
- **동적 재계획(rolling re-plan)**: 월간 자동 / 사용자 수동 / 예산 이탈 임계치, 세
  가지 트리거로 계산 엔진·몬테카를로를 재호출해 계획을 갱신하고 변경 사유까지 함께
  설명한다.
- **LLM 설명 생성**: 자체호스팅 sLLM(Ollama, GGUF 양자화)이 계산 결과 JSON을 자연어로
  풀어 쓴다. 출력에 등장하는 숫자는 원본과 대조 검증하고, 불일치 시 재시도(최대
  2~3회) 후 최종 실패하면 템플릿 fallback으로 전환하는 가드레일(LangGraph)을 거친다.
- **청년 정책 매칭** (보조 기능): 사용자 프로필(나이·지역) 기준으로 청년정책 오픈
  API를 조회해 참고 정보로만 안내한다. 계산 엔진과 수치적으로 연동하지 않는다.

### [web](web/) — React 18 + Vite + TS (포트 3000)

대시보드, fan chart 시각화, 강도별 계획 비교 UI, 재계획 이력(시점별 fan chart 비교).

## 인프라

PostgreSQL(주 스토어), Redis(비동기 job 상태 관리, LLM 응답 캐싱), Ollama(sLLM 서빙).

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

## 포맷터

| 서비스 | 도구 | 명령어 |
|---|---|---|
| analysis-api | ruff format | `uv run ruff format .` |
| core-api | Spotless + google-java-format | `./gradlew spotlessApply` |
| web | Prettier + ESLint | `npm run format && npm run lint` |

설정은 `.editorconfig`(공통 들여쓰기·줄바꿈), `analysis-api/pyproject.toml`,
`core-api/build.gradle.kts`, `web/.prettierrc` + `web/eslint.config.js`에 있다.

### Java(core-api) 포맷터 동작 방식

`build.gradle.kts`에 [Spotless](https://github.com/diffplug/spotless) 플러그인이
`googleJavaFormat()` 스타일로 붙어 있다.

```bash
./gradlew spotlessCheck   # 위반만 확인 (수정 안 함) — ./gradlew check 실행 시 자동 포함됨
./gradlew spotlessApply   # 위반을 실제로 고쳐씀
```

`spotlessCheck`는 `check` 태스크의 의존성으로 자동 등록되어 있어, 포맷이 깨진 채로는
`./gradlew build`/`check`가 실패한다. 즉 CI/로컬 빌드 시점에 강제된다.

에디터(VS Code) 저장 시 자동 포맷은 Java만 꺼져 있다(`.vscode/settings.json`).
VS Code 내장 Java 포매터가 google-java-format과 결과가 완전히 같지 않아, 켜두면
Spotless 기준과 다시 어긋나는 diff가 생기기 때문이다. 대신 커밋 전에
`./gradlew spotlessApply` 한 번 돌리는 흐름을 쓴다.

## 기여 규칙

- 브랜치·커밋·PR 규칙: [.claude/skills/git-flow/](.claude/skills/git-flow/SKILL.md)
- AI 작업 규칙: [AGENTS.md](AGENTS.md)
- `main`/`develop` 직접 푸시 금지 — `pre-push` 훅이 막는다

## 참고

- `analysis-api/data/`는 용량 문제로 gitignore됨. 원본 데이터셋은 별도 공유.
- 본선 심사 URL 접근 기간(9/7 11:00 ~ 9/11 23:59)에는 배포·부하테스트 금지.
