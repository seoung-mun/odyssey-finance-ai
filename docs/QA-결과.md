# QA 결과

## 2026-09-06 테스트 실효성 보강 후속 상태

### 적용·검증한 항목

- Analysis의 비퇴화 24개월 fixture가 요청 seed를 실제로 사용함을 고정 결과와 서로 다른 seed
  결과로 검증했다. `np.random.default_rng(payload["random_seed"])`를 상수 `12345`로 바꾼
  변이는 해당 테스트를 실패시킨 뒤 원복했다.
- Core의 `REAL_POSTGRES_URL` 조건으로 조용히 skip되던 PostgreSQL 테스트 12개는 Testcontainers
  PostgreSQL 16.4 공통 베이스로 옮겼다. 컨테이너에 `sql/01_schema.sql`과 무결성 SQL을 적용하고
  Flyway V4+를 실행한다. `GoalCreationHttpPostgresTest`와
  `PolicyBenefitHttpPostgresTest`는 실제 컨테이너에서 통과했다.
- QA runner는 OpenAPI에 없는 공개 호출을 실패 처리하고, 성능의 absolute check 결과를 실제
  `passed` 판정에 연결했다. helper 테스트는 UTC→KST, artifact 경로, operation 전수, timeout 및
  unexpected 5xx를 각각 검증한다.

### 남은 작업·판정

- Core PostgreSQL 집합 실행에서 `PolicyInformationalExposureHttpPostgresTest`는 artifact의 실제
  `ELIGIBILITY_ONLY`와 테스트가 기대한 `INFORMATIONAL`이 달라 실패한다. 제품 계약과 fixture 중
  어느 쪽이 기준인지 확정 전에는 테스트 기대값을 바꾸지 않는다.
- `LayerArchitectureTest`는 프로덕션 소스의 문자열 규칙 위반으로 전체 `./gradlew check`를 막는다.
  이번 테스트 게이트 변경과 독립된 기존 실패이며, 문자열 lint 성격의 테스트 정리는 별도 단위다.
- compose QA 실행형 전환, policy artifact의 non-zero 종료·`python -O` 보장, 정책 혜택/coverage
  경계 변이 보강은 미수행이다. Web P0 하드코딩 feasibility 및 P3 스위트 갱신은 Web 담당자에게
  인계한다.

## 2026-09-06 AI 기능 확장 백엔드 REAL 검증

### 결론

- 프론트를 제외한 격리 Compose에서 실제 PostgreSQL 16.4·Redis 7.4·Uvicorn·Spring·Caddy를
  연결한 기본 시나리오 170단계를 최종 코드로 연속 2회 통과했다.
- 로컬 테스트 전용 Ollama `qwen3:0.6b-q4_K_M`을 포함한 시나리오 174단계를 통과했다. 실제
  `/api/chat` 호출 뒤 Ollama를 중단해도 챗은 결정론 fallback, 설명은 deadline 내 FALLBACK,
  기존 계획·대시보드·재계획은 정상 응답함을 확인했다.
- 공개 OpenAPI 41개 중 Google 실 ID token이 필요한 `exchangeGoogleToken`만 성공 응답을
  만들지 못했다. 이 operation은 잘못된 token의 401 폐쇄 경계를 확인했고, 나머지 40개는 실제
  HTTPS 성공 응답을 기록했다. Internal operation은 4개로 계약과 일치한다.

### 기능·경계 증거

- 설명 생성은 Analysis에서 제거되고 Core의 타입화된 포트로 이동했다. 기본/e2e/kill-switch는
  ChatModel 없이 기동하며 즉시 FALLBACK하고, 로컬 profile만 Ollama를 사용한다.
- 적금 추천·what-if는 ACTIVE 목표·계획 snapshot과 선택 옵션으로 월저축액을 계산했다. RULE
  조건만 계산에 참여하고, LLM/비활성/타상품 조건, 기간 초과, 명시 한도, nullable 한도,
  상품·옵션 total order를 API와 DB 원장으로 대조했다. 기존 계획 5개 테이블 row-count delta는 0이다.
- 챗은 입력 원문을 Redis에 저장하지 않으며 intent·구조화 ID만 TTL 45분으로 저장했다. 원문
  부재, TTL 감소, 동시 요청 lock 409, Redis 장애 시 HTTP 200 stateless fallback을 직접 확인했다.
- 허용 origin의 credentialed preflight는 200, 다른 origin은 403이었다. Analysis 중단 시 계획
  관련 부분 저장이 없고, Redis/Ollama 중단은 기존 결정론 서비스까지 전파되지 않았다.
- Analysis 운영 이미지는 76,055,439 bytes이며 최종 레이어에 `torch`와 `uv`가 없다. 운영
  Compose 이미지는 PostgreSQL·Redis·Analysis·Core·Caddy뿐이고 Ollama·Node/Web이 없다.
- 운영 Compose를 격리 프로젝트와 localhost Caddy override로 실제 빌드·기동해 5개 서비스의
  health 및 Caddy HTTPS `/actuator/health=UP`을 확인하고 소유 컨테이너·볼륨을 정리했다.

### 미검증 경계

- 실제 Google 테스트 계정과 client ID를 이용한 성공 교환, 실제 Vercel custom domain→EC2
  Caddy TLS·쿠키 브라우저 흐름은 자격증명/배포가 없어 미검증이다.
- FINLIFE 실 API key 기반 원격 수집은 미검증이며, key 없음·외부 장애 fail-open과 로컬 fixture
  기반 수집/멱등성은 검증했다.
- 프론트/Playwright는 요청 범위에서 제외했다. 운영에는 Ollama를 배포하지 않으므로 로컬 모델의
  품질 경쟁이나 운영 하드웨어 성능은 완료 조건으로 사용하지 않았다.

---

## 2026-09-01 정책 검색·가상 재계획 REAL 검증

### 완료한 범위

- 캐시 없는 격리 Compose에서 실제 PostgreSQL 16.4·Redis 7.4·Uvicorn·Spring·Caddy·
  Chromium을 연결한 `python3 scripts/real_scenario_qa.py` 127단계가 1회 통과했다.
- route interception 없이 Playwright 9개가 통과했고, Google exchange만 허용된 E2E 인증
  우회로 분리한 상태에서 Public 제품 operation 33개와 Internal operation 5개 성공을 확인했다.
- Analysis 장애 중 생성된 proposal 없는 재계획 event를 Analysis 복구 뒤 실제 계획 화면에서
  `재계획 다시 시도`로 처리했다. 최초 실패 원인은 API client가 이미 변환한 event를
  `PlansPage`가 다시 strict parse하던 이중 parsing이었다.
- 공식 후보와 분리된 `UNAPPROVED_DATA_QA_FIXTURE`·`SYNTHETIC_QA_ONLY`만 정책 검색·가상 비교
  성공 경로에 사용했다. 이는 공식 정책 승인 또는 운영 적재 증거가 아니다.

### PENDING

- **공식 정책: PENDING.** 공식 HTTPS 원문에서 만든 후보 24건은 모두 사람 승인 전이며 실제
  application DB에 적재하지 않았다. runner는 이 artifact의 import를 거부하고 write 0을
  확인한다.
- **외부 AI: PENDING.** 이번 REAL 실행의 설명은 안전한 `FALLBACK`으로 수렴했다. 실제 외부
  모델의 READY 응답 품질·수치 대조·지연은 완료 판정하지 않는다.
- **성능: PENDING.** warm p95/p99, c4/c8, 30분 soak는 이번 종료 범위에서 실행하지 않았다.
  특히 c4의 `baseline 대비 70%`에 필요한 미적용 baseline workload 계약이 확정되지 않아
  runner도 성능을 합격 처리하지 않는다.

---

## 2026-08-28 외부 Odyssey UI 이식 재검증

범위: `origin/feature/odyssey-ui-demo@8afe49b`의 시각 언어를 현재 실제 Login·Onboarding·
Dashboard 흐름에 이식한 Web 변경 9개 파일.

### 결론

- prototype의 `DemoNav`, mock 금융 숫자, 소비 하한, React 19·Tailwind·Recharts는 가져오지
  않고 로그인 바다·범선·등대, SVG 온보딩 항로, 계획 파도 카드, 대시보드 8:4 항로 구성을
  현재 API DTO와 실제 선택 흐름 위에 복원했다. 새 런타임 의존성은 추가하지 않았다.
- Web lint·build, Vitest 75개, MOCK Playwright 5개가 통과했다.
- 캐시 없는 격리 Compose에서 실제 PostgreSQL·Redis·Analysis·Core·Caddy·Chromium을 연결해
  API·DB 112단계와 route interception 없는 REAL Playwright 2개가 다시 통과했다.
- 실제 Chrome에서 375px은 1열, 840px은 대시보드 `462px 270px`, 1440px은
  `752px 376px` 2열이며 각 viewport에서 문서 가로 overflow가 없음을 확인했다. 로그인도
  721px부터 흰 패널·바다 분할 구성을 사용한다.
- 차트 P10/P90 경계·P50·목적지와 월별 상세값, 빈 band 데이터 없음 상태를 실제 데이터로
  렌더링한다. 파도·범선·등대·경로·카드 전환 애니메이션은 `prefers-reduced-motion`에서 멈춘다.
- 두 차례 읽기 전용 충실도 QA의 지적 사항을 수정했고 최종 변경범위·E2E QA에서
  blocker·high·medium은 없었다.
- 실제 Google 계정 OAuth 완료와 Safari 실기기 safe-area는 이번 자동 QA 범위 밖이다.

---

## 2026-08-28 데모 수직 흐름 검증

기준: `docs/QA-가이드.md`, `docs/evals/demo-seed.md`
방법: `scripts/real_scenario_qa.py`가 캐시 없이 Compose를 빌드하고 실제 PostgreSQL 16.4·
Redis·Analysis·Core·Caddy·Vite·Chromium을 한 실행에서 검증했다.

### 결론

- API·DB·장애·동시성 시나리오 112단계와 route interception 없는 Playwright 2개가 통과했다.
- 세 데모 테스터 모두 계획 옵션 3개를 생성했고 절감률은 0.05~0.30 범위에서 서로 달랐다.
- 브라우저가 로그인 → 테스터 선택 → seed → 계획 생성 → 80% 옵션 선택 → 대시보드 →
  새로고침을 실제 API로 완료했다. refresh·재로그인·교차 사용자 404도 별도 스펙에서 통과했다.
- 같은 사용자의 동시 seed 두 요청은 모두 200과 같은 응답을 반환했고, seed state·profile·
  financial profile·goal은 각각 1개, 중복 거래 ID는 0개였다.
- 세션 timezone이 UTC일 때 senior 템플릿을 25개월로 세던 V6 월 상한을 Asia/Seoul 기준으로
  고쳤고, 실제 PostgreSQL에서 UTC·KST 모두 seed/reseed 검증을 통과했다.
- QA 출력의 refresh JWT 노출을 제거했고, 무인증 tester 조회 401을 REAL 시나리오에 추가했다.

### 회귀

- Analysis Ruff, unittest 98개, categories·synth_mock·vae self-check — 통과.
- Core `./gradlew check --rerun-tasks --no-daemon` — 통과.
- Web lint·build, Vitest 75개, MOCK Playwright 5개 — 통과.
- 최종 실행 뒤 Dacon/Odyssey 컨테이너·이미지·볼륨 잔여 없음.

### 남은 사양 문제

- 존재하지 않는 tester의 응답은 평가 문서 409, Core 구현 404, OpenAPI 미기재로 충돌한다.
  공용 계약 승인 전에는 구현을 바꾸지 않고 `docs/미확정-설계.md`에 기록했다.

---

## 2026-08-27 백엔드 재검증

기준: `docs/QA-가이드.md`, `docs/evals/real-mvp-integration.md`
범위: Core·Analysis 백엔드만. Web/브라우저는 프론트 재작업 예정으로 이번 웨이브 범위 밖.
방법: `scripts/real_scenario_qa.py` — 격리 compose project(랜덤 project명·loopback 포트·
일회용 secret)에서 실제 PostgreSQL 16.4·Redis 7.4·Uvicorn·Spring·Caddy를 기동해 사용자
시나리오(목표 입력 → 선반영 → 재계획)와 적대적 케이스를 실제 HTTPS API로 수행했다.

### 결론

2026-08-26 판정의 두 blocker(Core→Analysis HTTP 전송, 무필터 거래 목록 500)는 코드 레벨에서
이미 고쳐져 있었으나 실제 재검증이 없었다. 이번에 실제 스택으로 재검증해 **REAL로 확정**했다.
그 과정에서 이전에 알려지지 않았던 P0급 결함 2건을 실제 요청으로 새로 발견해 고쳤다:

- **[blocker→해결] 현실적이지 않은 거래 금액이 월별 집계를 깨뜨림.** `amount=Long.MAX_VALUE`인
  거래를 적재하면 그 자체는 성공하지만, `monthly_spending_summary` VIEW의
  `SUM(...)::bigint`가 그 사용자의 다른 거래와 합산할 때 BIGINT 범위를 넘겨 `SQLSTATE 22003`으로
  깨진다. 이후 그 사용자의 **모든** 계획 생성 요청이 500 대신 알 수 없는 `DataIntegrityViolationException`
  기반 409로 영구히 막힌다. `TransactionInput`/`RefundAllocationInput`에 현실적 상한(10^15원,
  `TransactionDtos.MAX_AMOUNT`)을 추가해 재발을 막았다(`API/openapi-public.yaml` 동기화,
  사용자 승인 하에 계약 변경).
- **[high→해결] 재계획 저장의 Hibernate flush 순서 latent bug.** `PlanningCommandService.save()`가
  기존 PROPOSED 계획을 STALE로 바꾸는 UPDATE와 새 계획 INSERT 사이에 flush가 없어, Hibernate가
  update보다 insert를 먼저 내보내면 `uq_plan_version_proposed_per_goal`(goal당 PROPOSED 1개)을
  순간적으로 위반할 수 있었다(01_schema.sql이 이미 이 순서 위험을 주석으로 경고하고 있었다).
  `select()`의 supersede 처리와 동일하게 `plans.flush()`를 추가했다.

### 이번에 REAL로 확정한 것

- 실제 Uvicorn에 대한 계획 생성: option 3개·band 57개·simulation이 한 transaction으로 저장.
- 거래 무필터·카테고리 필터·기간+카테고리 복합필터·커서 페이지네이션 — 전부 200.
- 환불 linked·pending·unmatched, 늦은 PAYMENT resolve, 중복 import 멱등성(회귀 유지).
- 선반영: 예정지출 생성·수정이 즉시 재계획을 선계산하고(`triggeredReplanEventId`), 결정
  (`ACCEPT_NEW_PLAN`/`KEEP_CURRENT_PLAN`)이 계획 상태와 소비 기반 재계획 억제를 정확히 반영.
- 자동 재계획: 큰 거래 shock가 `replan_events`에 정확히 1건만 생성(중복 없음).
- 수동 infeasible 재계획 422 `PLAN_INFEASIBLE`, 부분 행 없이 append-only.
- Analysis 연결 거부 → 503, 계획 관련 행 0건(부분 저장 없음).
- Redis 다운 → 계획 생성은 성공하고 설명은 즉시 FALLBACK으로 마감(무기한 PENDING 아님, 설계
  의도대로 동작). 재기동 뒤 새 설명은 정상 수렴.
- 예정지출 동시 수정 2건 → 500 없이 낙관적 잠금으로 정리(200/200 또는 200/409), 최종 행 일관.
- 사용자 B가 A의 목표·계획·재계획 이벤트·예정지출을 조회·수정·결정 시도 → 전부 404.
- 컨테이너 로그에 secret·token 미노출.

### 이번 웨이브에서 다루지 않음(범위 밖 또는 미검증)

- Web/브라우저 전체 흐름, Playwright, Vercel→Caddy 실제 도메인 HTTPS — 프론트 재작업 예정.
- Redis consumer가 메시지를 pending으로 들고 있는 도중 재기동되는 pending reclaim·중복 delivery
  — black-box compose 조작으로 안전하게 재현할 방법을 못 찾아 미검증으로 남긴다.
- 공개 API 31개 전체 대조표(이번엔 핵심 경로들을 실제로 태웠지만 전수 대조는 아님).
- 실제 Ollama 설명 품질·지연, AWS/secret store, 부하 테스트.

### 회귀

- Core `./gradlew check` — 통과(격리 폴더의 사전 diff에 있던 spotless 위반은 `spotlessApply`로
  정리).
- Analysis Ruff — 위반 0. Analysis 전체 unittest 103개 — 전부 통과.
- `sql/03_verify.sql` 77개, `sql/06_verify_refund_allocations.sql` — 새 일회용 PostgreSQL
  16.4 컨테이너(01→02→05→V4 적용, 검증 뒤 폐기)에서 전부 통과. `03_verify.sql`은 빈 DB를
  전제하므로 24시간째 떠 있는 `dacon` 개발 스택에는 실행하지 않았다.

---

# 2026-08-26 판정 (구 기록)

## 결론

현재 상태는 **모듈 단위 녹색, 실제 MVP 수직 흐름 실패**다. 공개 API 31개 mapping은 코드에
존재하지만 실제 브라우저 흐름과 계획 생성이 blocker에서 중단되므로 구현 완료로 인정하지
않는다. 이번 판정에는 메인이 배정하지 않은 목업을 합격 근거로 사용하지 않았다.

실제 검증에서 환불 배분·중복 거래·사용자 소유권·입력 경계와 부분 저장 방지는 버텼다.
반면 브라우저 인증 연결, Core→Analysis HTTP 전송, 기본 거래 목록 쿼리가 실제 환경에서
실패했다. Redis 내구성, 재계획, 실제 Ollama는 선행 blocker 때문에 미검증이다.

## 증거 분류

| 범위 | 직접 실행 결과 | 분류 | 판정 한계 |
|---|---:|---|---|
| Analysis Ruff·unittest | 103개 통과 | 혼합 | 엔진·fixture 직접 호출은 `REAL` 단독 컴포넌트, TestClient·patch·가짜 HTTP는 `MOCK` |
| Core Gradle | 90개 통과 | `MOCK`/단위 | Mockito·직접 객체·고정 `HttpServer`; 실제 Spring/JPA/Redis/Analysis 합격 근거 아님 |
| Web Vitest | 47개 통과 | `MOCK`/단위 | jsdom·주입 fetcher; 실제 브라우저↔Core 근거 아님 |
| 기존 Playwright | 5개 통과 | `MOCK` | `page.route().fulfill()`로 핵심 API 전부 대체 |
| 실제 Playwright | 1개 실패, 소유권 1개 skip | `REAL`/미검증 | 인증 직후 최초 화면 진입 실패, 이후 시나리오 전체 미검증 |
| PostgreSQL SQL | `03_verify.sql`, `06_verify_refund_allocations.sql` 통과 | `REAL` | DB 함수·제약 레인; Spring 사용자 흐름과는 별도 |
| 실제 서비스 수직 실행 | PG·Redis·Uvicorn·Spring·Vite·Chromium 기동 | `REAL` | 아래 최초 실패 지점까지 검증 |

기존 `AnalysisClientTest`의 JDK 고정 응답 서버, FastAPI `TestClient`, patched Ollama,
Repository mock, jsdom fetcher, route fulfillment는 모두 보조 단위 증거다. 대응 `REAL`이 실패하거나
없으므로 해당 서비스 경계를 통과로 판정하지 않는다.

## 실제 수직 시나리오 결과

### 통과

- 실제 PostgreSQL 16.4와 Redis 7.4를 loopback에 기동하고 migration·health를 확인했다.
- 사용자 샘플 적재 전후 `onboardingComplete=false → true`, 같은 요청 재시도 `loaded=false`.
- 사용자 B가 사용자 A의 목표를 조회하면 `404 RESOURCE_NOT_FOUND`.
- 같은 거래 import 재시도는 `inserted=4 → 0`, `skipped=0 → 4`로 중복 원장을 만들지 않았다.
- linked·pending·unmatched 환불 금액과 상태가 보존됐다.
- 늦게 도착한 PAYMENT가 pending 환불 allocation을 실제 DB에서 resolved로 바꿨다.
- PAYMENT 소비 표본과 환불 allocation 반영 월 집계를 실제 DB/API에서 대조했다.
- 음수 금액, 잘못된 날짜, int64 범위 초과 입력은 모두 400으로 거부됐다.
- 실제 Uvicorn에 올바른 계산 JSON을 직접 보내면 200, option 3개, band 57개를 반환했다.
- 계획 생성 실패 뒤 `plan_versions`, `simulation_runs`, `plan_options`가 모두 0이라 부분 저장은
  남지 않았다.

### [blocker] 실제 브라우저 인증 뒤 온보딩에 진입하지 못한다

- evidence: route interception 없는 Chromium이 `/onboarding`에서 90초 동안
  `샘플로 둘러보기` 버튼을 찾지 못했다.
- cause: 실제 E2E 스펙은 인증 API에서 access token을 받지만 앱 메모리 상태에 전달하지 않는다.
  refresh cookie는 `Secure`인데 E2E origin은 평문 HTTP라 앱의 refresh 부팅도 성립하지 않았다.
- impact: 온보딩, 계획 선택, 대시보드, 재계획, 새로고침·재로그인 전체가 미검증이다.
- owner: Web/Core 인증 통합 빌더.
- security: 실패 trace는 token·cookie 노출 가능성 때문에 열지 않고 삭제했다.

### [blocker] Core→Analysis 실제 계획 생성이 422로 실패한다

- evidence: `POST /api/v1/goals/{goalId}/plan-versions`는 공개 400, 실제 Uvicorn은
  `/internal/simulate` 422와 body 전체 누락을 보고했다. 동일 JSON을 직접 HTTP/1.1로 보내면
  200이다.
- cause: 공유 JDK `HttpClient` 기본 버전이 cleartext HTTP/2 upgrade를 시도하고 Uvicorn이 이를
  거부하면서 요청 body가 전달되지 않는다. 강제 HTTP/1.1에서는 body와 필드별 검증이 정상이다.
- impact: 최초 계획, 옵션 선택, 대시보드 계획, 모든 재계획이 막힌다.
- owner: Core HTTP/통합 빌더.
- integrity: 관련 계획 행은 0으로 rollback되어 부분 저장은 없다.

### [high] 필터 없는 거래 목록이 실제 PostgreSQL에서 500이다

- evidence: `GET /api/v1/transactions?limit=50` → `500 INTERNAL_ERROR`, PostgreSQL은
  `could not determine data type of parameter $2`를 반환했다.
- cause: nullable 필터를 한 JPQL/native query에서 처리하면서 null 파라미터 타입을 확정하지
  못한다.
- impact: 기본 거래내역 화면을 열 수 없다.
- owner: Core transaction 빌더.

## 아직 합격하지 않은 정적·단위 QA 항목

- drift 상세 JSON의 `recommendedMonthlySpending × day`가 int64 overflow를 낼 수 있다.
- 수동 infeasible 재계획이 OpenAPI의 422 대신 200을 반환한다.
- 예정지출 동시 수정에서 계산 snapshot과 최종 행이 달라질 수 있다.
- Web 날짜 검사는 input type을 우회하면 존재하지 않는 달력 날짜를 API로 보낼 수 있다.
- Web은 재계획 403·404·422를 일반 전체화면 오류로 축약한다.
- 멀티유저 실제 Playwright는 foreign plan ID가 없으면 skip한다.

위 항목은 단위·정적 재현이므로 최신 `REAL` 시나리오 통과 항목과 섞지 않는다. 수정 후 실제
HTTP·DB·브라우저에서 다시 검증한다.

## 보안·암호화 QA

### 현재 보호되는 것

- 실제 secret 파일은 Git ignore 대상이고 tracked `.env.example`은 placeholder만 포함한다.
- JWT secret은 32 byte 미만을 거부하고 Analysis 내부 token은 공백을 거부한다.
- access JWT는 Web 메모리에만 두며 local/session storage에 저장하지 않는다.
- refresh JWT 원문은 DB에 저장하지 않고 SHA-256 digest만 저장한다.
- refresh cookie는 `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/api/v1/auth`이며 logout도 같은
  path로 삭제한다.
- Redis stream에는 planVersionId·inputHash·promptVersion만 있고 금융 원문·token은 없다.
- 기본 compose에서 PostgreSQL·Redis·Core·Analysis·Ollama는 host port를 publish하지 않는다.

### 남은 위험

- [high] Caddy 기본 주소와 예제값이 `:80`이라 기본 compose는 HTTP다. 실제 운영 domain을
  Caddy site address로 넣고 인증서와 HTTP→HTTPS redirect를 실측하기 전에는 OAuth·JWT·금융
  데이터 전송을 합격시킬 수 없다.
- [medium] 실제 Playwright가 `trace: retain-on-failure`이고 E2E/Bearer token을 header에 보내므로
  CI artifact에 token이 남을 수 있다.
- [medium/운영] 이메일, 소득·고정비, 목표, 거래처·금액, 계획 snapshot·simulation 결과는 DB에
  애플리케이션 암호화 없이 저장된다. EBS·snapshot·backup 암호화와 최소권한 DB role 증거도
  아직 없다.
- [판정 불가/운영] 비밀값은 compose 환경변수로 주입되어 Docker daemon 권한자에게 보인다.
  운영 secret store 연결과 회전 절차가 없다.
- [low/로컬] ignored `.env`와 `web/.env.local` 권한이 0644다.

Core→Analysis→Ollama의 평문 HTTP는 현재 단일 compose private network 안에서 host port가
없으므로 로컬 예외로 분류한다. 운영에서도 동일하게 격리된 단일 host private network인지
증명하지 못하거나 multi-host로 분리하면 TLS 또는 mTLS가 필요하다.

QA 중 최초 PG·Redis·Spring이 전체 인터페이스에 bind된 사실을 발견했다. 즉시 중지하고
`127.0.0.1` 전용으로 재기동한 뒤 금융 요청을 수행했으며, 종료 시 소유 프로세스와 컨테이너를
전부 정리했다.

## 미검사

- 옵션 선택·대시보드·예정지출·재계획 목록/결정/재시도 전체 흐름
- Redis queue consumer, 중단·재기동, pending reclaim, 중복 delivery
- 동시 계획·목표·예정지출·재계획 요청과 응답 유실 재시도
- 실제 Ollama 설명, 숫자 대조, timeout·RSS
- 실제 Google 테스트 계정 로그인 1회
- 실제 Vercel→Caddy HTTPS, 인증서 chain, redirect, HSTS/CSP
- AWS SG, Secrets Manager, EBS·snapshot·backup 암호화와 DB 최소권한
