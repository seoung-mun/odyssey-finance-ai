# 정책 반영 가상 재계획 구현 계획

> **작업자 필수:** 각 작업은 `superpowers:subagent-driven-development`와 `superpowers:test-driven-development`를 적용하고, 완료 주장 전 `superpowers:verification-before-completion`을 적용한다.

**목표:** 승인된 공식 주거정책을 검색하고, 사용자가 기관에서 확정받은 지원 금액·기간만 일회성으로 입력해 현재 ACTIVE 계획과 저장되지 않는 가상 재계획을 비교한다. 동시에 현재 Public API에 있으나 Web에서 빠진 사용자 흐름을 연결한다.

**아키텍처:** React는 `/api/v1/**`만 호출한다. Spring Core가 정책 카탈로그·소유권·`CALCULABLE` gate·조정값 변환·PostgreSQL을 담당하고, FastAPI는 정책·사용자 식별정보 없이 기존 계획 snapshot과 숫자 adjustment만 받아 동일 seed로 현재/가정 계획을 계산한다. 정책 수집·KURE embedding은 오프라인 artifact 생성 단계에만 존재한다.

**기술:** React/Vite, Spring Boot/JPA, PostgreSQL 16.4, FastAPI/Uvicorn, Redis 7.4, Caddy HTTPS, Playwright/Chromium, Python `sentence-transformers` 오프라인 선택 의존성.

**설계 원본:** `docs/superpowers/specs/2026-08-31-policy-virtual-replan-design.md`

## 1. 고정 결정과 제외 범위

- 정책 자격을 새 공식으로 추정하지 않는다. 기관 확인이 끝난 지원 금액·기간만 입력받는다.
- 확정 금액·기간·추가 답변은 요청 메모리에만 두고 DB, 일반 hash, HMAC, 애플리케이션 로그, 분석 로그에 저장하지 않는다.
- 비교 기준은 선택한 ACTIVE 계획의 옵션 하나뿐이다. 선택하지 않은 PRESET들을 재계산하지 않는다.
- 비교 지표는 기존 권장 월 저축액, 절감률, 목표일 내 충족률만 사용한다. 새로운 목표 도달일 계산은 추가하지 않는다.
- 시나리오는 `PlanVersion`, ACTIVE 계획, 거래, 예정지출, 재계획 이벤트, Redis 상태를 변경하지 않는다.
- 브라우저에는 `/api/v1/**`만 노출한다. `/internal/**`, PostgreSQL, Redis는 private network/loopback에 유지한다.
- 새 전역 상태관리, UI 라이브러리, API wrapper, pgvector, 런타임 외부 AI/KURE 호출을 추가하지 않는다.
- 기존 MOCK 테스트는 유지할 수 있지만 신규 합격 근거와 전체 완료 판정에는 사용하지 않는다.

## 2. 계약과 데이터

### Public API

기존 32개 operation에 아래 2개를 추가해 총 34개를 실제 HTTP로 검증한다.

1. `POST /policies/search`
   - 요청: `supportGoal`, 최대 3개의 `{questionId, value}` 답변
   - 응답: `QUESTION | RESULTS` 판별 응답
   - 결과: Top 3의 전체 상세, 계산 가능 상태, 공식 출처와 provenance
2. `POST /policy-versions/{policyVersionId}/scenario`
   - 요청: `currentPlanVersionId`, 검색 입력, `confirmedAward {institutionConfirmed: true, amountWon, startYearMonth, endYearMonth?}`
   - 응답: 현재/가정 계획 요약, 적용 adjustment, 고정 가정 문구, 공식 출처
   - 오류: 잘못된 입력 `400`, 미존재·비소유 `404`, 상태 충돌 `409`, 경계값 `422`, Analysis 불가 `503`

### Internal API

기존 4개 operation에 `POST /internal/policy-scenarios`를 추가해 총 5개를 검증한다.

- 입력은 기존 계획 input snapshot, 선택 옵션, `FutureCashflowAdjustment {type, amountWon, startMonthIndex, endMonthIndex?, sourceVersion}`뿐이다.
- 사용자 ID, 정책 ID, 검색 답변, 공식 원문은 Analysis로 보내지 않는다.
- Public 응답에는 기존 요약만 반환한다. 내부 응답의 bands는 golden 대조에만 쓴다.

### PostgreSQL

최소 테이블은 다음으로 제한한다.

- `policy_sources`, `policies`, `policy_versions`, `policy_version_sources`
- `policy_chunks`, `policy_query_profiles`
- `policy_index_snapshots`, `policy_snapshot_versions`
- `policy_calculation_rules`, `policy_retrieval_runs`

시나리오·확정 지원금·사용자 답변 테이블은 만들지 않는다. embedding은 길이 1024 JSONB 숫자 배열이며 Spring에서 정확 cosine을 계산한다. ACTIVE index snapshot은 하나만 허용하고, 승인 artifact import는 동일 입력에 멱등이어야 한다.

## 3. 정책 corpus와 승인 gate

- corpus: 중앙정부와 서울·경기·인천의 청년 주거정책 20~30개.
- 계산 후보:
  - 복지로 청년월세 한시 특별지원: 월 최대 20만원, 최대 24개월.
  - 서울 청년월세지원: 월 최대 20만원, 최대 12개월.
  - 서울 청년 부동산 중개보수 및 이사비 지원: 실제 지출, 최대 40만원 일회성.
- 상한액을 자동으로 적용하지 않는다. 사용자가 기관에서 확정받은 실제 금액·기간을 넣는다.
- 각 정책의 공식 URL, version, 원문 locator, hash, 금액 단위, 기간, 적용 월, golden을 사람이 대조하기 전에는 `CALCULABLE`로 적재하지 않는다.
- 일반 정책은 `INFORMATIONAL` 또는 `ELIGIBILITY_ONLY`로 검색·출처만 제공하며 계산 CTA가 없어야 한다.
- KURE-v1은 오프라인 artifact 생성에만 사용하고 1024차원을 검증한다. 모델·artifact·source hash·생성 시각을 manifest에 남긴다.

공식 검증 시작점:

- 복지로: <https://m.bokjiro.go.kr/ssis-tem/ssis-tem/twataa/wlfareInfo/moveTWAT52011M.do?wlfareInfoId=WLF00004661>
- 서울 청년월세지원: <https://www.seoul.go.kr/news/news_notice.do?nttNo=457234&tr_code=snews>
- 서울 중개보수·이사비: <https://www.seoul.go.kr/news/news_report.do?nttNo=464088&srchCtgry=465>
- KURE-v1: <https://huggingface.co/nlpai-lab/KURE-v1>

## 4. 계산 규칙

- `ONE_TIME_FUNDING`: 적용 월에 한 번만 목표 잔액을 줄이는 가정으로 적용한다.
- `MONTHLY_EXPENSE_REDUCTION`: 시작·종료 YearMonth와 계획 horizon이 겹치는 각 월에 전액 적용하며 일할 계산하지 않는다.
- 금액이 `null`, 0 이하, `10^15` 초과이거나 합산 overflow면 거부한다.
- 종료가 시작보다 빠르거나 계획 horizon과 겹치지 않으면 거부한다.
- Core가 계획 생성 시점의 KST 월 origin을 기준으로 YearMonth를 month index로 변환한다.
- 현재 계획과 가정 계획은 동일 seed와 동일 sampled paths를 한 번 생성해 사용한다.
- 결과는 화면 메모리에만 두고 Modal 종료·새로고침·로그아웃 후 폐기한다.

## 5. 기존 계약 결함 선행 수정

- `GoalInput.targetDate`, `ScheduledExpenseInput.scheduledDate`에 서버 검증을 맞춰 누락 입력이 500이 되지 않게 한다.
- `triggeredReplanEventId`의 Java/OpenAPI 응답 drift를 정리한다.
- 실제 발생하는 `404/409/503`, 문자열 길이와 재시도 `200 INFEASIBLE`을 OpenAPI에 기록한다.
- 재계획으로 생성된 proposal 선택은 서버에서 해당 이벤트의 `ACCEPT_NEW_PLAN` 결정 이후만 허용한다.
- 최초 계획 생성에서 직접 만든 proposal은 기존처럼 선택할 수 있다.

## 6. Web 범위

### `/transactions`

- 거래 목록·filter·cursor 조회
- PAYMENT, REFUND와 allocation 입력
- 월별·카테고리별 요약
- 예정지출 생성·수정·취소
- 거래 수정·삭제는 Public API가 없으므로 만들지 않는다.

### `/plans`

- 계획 버전 목록·상세·설명
- CUSTOM 옵션 입력과 proposal 선택
- 재계획 이력·기존 계획 유지·새 계획 수락·재시도·복구 상태

### Dashboard dialogs

- 프로필, 재무 입력, 목표를 각각 저장하는 native dialog
- 예정지출 요약과 주거정책 CTA
- 정책 검색 질문, Top 3, 상세, 확정 금액·기간 입력, 현재/가정 비교 dialog
- 공통 navigation과 logout

기존 `api.ts`, parser, `busyRef`/request sequence 패턴을 재사용한다. 정책 금액 계산은 Web에 두지 않는다. 접근성 기본인 label, keyboard focus, dialog 닫기, 오류 텍스트를 포함한다.

## 7. 작업 순서와 소유권

### Task 1 — 계약·평가·DB 기준 고정 (메인)

**수정:** `API/openapi-public.yaml`, `API/openapi-internal.yaml`, `sql/*.sql`, `docs/evals/*`, 관련 기획·미확정 문서

- [ ] 현재 operationId와 실제 응답 drift baseline 기록
- [ ] 실패하는 계약 검증/실제 PostgreSQL 검사부터 작성
- [ ] Public 2개, Internal 1개 계약 추가
- [ ] 정책 schema와 멱등 artifact import 계약 추가
- [ ] 계산 후보 사람 승인표와 미승인 시 informational fallback 기록
- [ ] OpenAPI lint와 PostgreSQL 16.4 실제 schema 적용

### Task 2 — 기존 Core 계약·선택 gate (Core 리드)

**수정:** `core-api/src/**`, `core-api` 테스트

- [ ] 누락 날짜·응답 drift·오류 상태를 재현하는 실패 테스트
- [ ] 공통 입력 경계에서 최소 수정
- [ ] 재계획 proposal 선택 전 `ACCEPT_NEW_PLAN` gate 추가
- [ ] 직접 생성 proposal 회귀와 소유권·동시 요청 REAL 검증

### Task 3 — 정책 catalog·검색 (Core 리드)

**수정:** `core-api/src/**`, 승인 artifact import 경로

- [ ] 실제 PostgreSQL import/search 실패 테스트
- [ ] 1024차원·유한수 validation과 exact cosine
- [ ] 고정 질문 최대 3개와 Top 3 결과
- [ ] `CALCULABLE`/일반 정책 CTA contract와 provenance
- [ ] 입력 답변·확정 금액이 로그/DB에 남지 않는지 검사

### Task 4 — 가상 재계획 엔진 (Analysis 리드)

**수정:** `analysis-api/**`

- [ ] adjustment 경계와 동일 paths 사용을 재현하는 실패 테스트
- [ ] 기존 deterministic planner/Monte Carlo 호출 경로를 재사용
- [ ] 일회성·월별 adjustment 최소 구현
- [ ] 현재/가정 golden, 결정성 10회, invalid/overflow 검사
- [ ] 정책·사용자 식별정보 수신 금지 검사

### Task 5 — 시나리오 orchestration (Core 리드)

**수정:** `core-api/src/**`

- [ ] 정책 상태·계획 소유권·기관 확인 gate 실패 테스트
- [ ] YearMonth→index와 internal DTO 변환
- [ ] Analysis 실제 HTTP 호출과 오류 매핑
- [ ] 성공·오류·동시 요청 전후 DB/Redis fingerprint 불변 검증

### Task 6 — 전체 Public UI (Web 리드)

**수정:** `web/src/**`, 기존 Playwright 시나리오

- [ ] `frontend-design` 적용 후 현재 route/스타일을 재사용한 화면 구성
- [ ] `/transactions`, `/plans`, Dashboard dialogs 구현
- [ ] 정책 검색·상세·확정 입력·가상 비교 구현
- [ ] route interception 없는 실제 브라우저 클릭 흐름 작성
- [ ] 직접 fetch가 아닌 화면 조작으로 새로고침·재로그인·오류 복구 확인

### Task 7 — 통합 REAL QA와 수정 루프 (메인 + 읽기 전용 QA 리드)

**수정:** `scripts/real_scenario_qa.py`, `docs/QA-결과.md`, `TODO.md`

- [ ] 기존 격리 Compose/HTTPS/E2E auth bypass를 확장
- [ ] 계약·데이터, 적대적 서비스, E2E·변경범위 3개 QA lane
- [ ] blocker/high는 원 빌더에 최대 2회 수정 재배정
- [ ] 전체 회귀·성능·soak 결과와 미검사 영역 저장

## 8. REAL E2E 합격 기준

- 신규 합격 근거의 `MOCK`/`CONTRACT_STUB`: 0건.
- Google exchange 성공만 `e2e` profile과 one-time token으로 우회한다. refresh/logout/cookie는 실제 제품 경로다.
- Public 34개, Internal 5개 operation을 실제 PostgreSQL·Redis·Uvicorn·Spring HTTP·Caddy HTTPS로 검증한다. 34개 중 Google login 1개만 `APPROVED_BYPASS`로 별도 표시한다.
- Playwright는 `page.route().fulfill()`과 클릭 증거용 `page.evaluate(fetch)`를 사용하지 않는다.
- 사용자 A 인증→온보딩→거래/환불→목표→계획 생성/선택→예정지출→재계획 결정/재시도→정책 검색/비교→새로고침/재로그인 흐름이 한 stack에서 이어진다.
- 사용자 B가 사용자 A의 계획·정책 시나리오 요청에 접근한 성공 사례는 0건이다.
- 정상/오류/Analysis kill/동시 요청 전후 계획·ACTIVE·거래·예정지출·재계획·Redis fingerprint 변화는 0건이다.
- 계산 CTA 오노출: `INFORMATIONAL`/`ELIGIBILITY_ONLY` 전체 0건.
- 신규 UI의 주요 상태는 pairwise/equivalence class로 덮고, 인증만료+요청중, 재계획미수락+직접선택, 정책만료+확정입력, Analysis중단+재시도 4개 triple을 강제한다.

## 9. 검색·계산 품질 합격 기준

- 사람이 확정한 golden query 30개.
- Recall@3 `>= 0.90`, MRR `>= 0.80`, false eligibility 0건, 공식 provenance 100%.
- metadata-only baseline보다 두 지표 모두 낮지 않고 Recall@3 또는 MRR 중 하나는 `>= 0.05` 향상.
- 승인한 2~3개 계산 정책의 모든 적용 월 cashflow가 golden과 원 단위로 동일하다.
- 같은 입력의 current/assumed summary가 10/10 동일하다.
- `null`, 0, 음수, `10^15` 초과, overflow, 역전 기간, horizon 비중첩을 모두 거부한다.

## 10. 성능·안정성 완료 기준

- 비교 대상: 같은 REAL stack의 정책 시나리오 미적용 baseline과 적용 후.
- warm 정책 검색/시나리오: p95 `<= 1s`, p99 `<= 2s`.
- 동시성 4, 총 100요청: 오류·timeout 0, 적용 후 throughput `>= baseline의 70%`.
- 동시성 8 공격: 예상 밖 5xx·timeout 0.
- 30분 soak: OOM·프로세스/컨테이너 restart 0, 마지막 10분 RSS p95 `<= warm RSS의 120%`.
- clean stack 전체 기능 3/3 통과, 성능 수치는 3회 중앙값으로 판정.
- judge 운영 기간(2026-09-07 11:00~2026-09-11 23:59)에는 심사용 환경 배포·부하·인스턴스 변경을 하지 않는다.

## 11. 완료 판정

- `analysis-api` 전체 검사, Core `./gradlew check`, Web lint/build가 통과한다.
- 실제 PostgreSQL schema/migration과 실제 서비스간 HTTP를 포함한 REAL 수직 흐름이 통과한다.
- 검색·계산·성능 기준을 모두 만족하고 읽기 전용 QA의 blocker/high가 0건이다.
- 전체 diff에서 의도 밖 변경, secret, 개인정보·확정 지원금 로그, 외부 노출된 internal route가 0건이다.
- 결과와 미검사 영역을 `docs/QA-결과.md`, 남은 작업을 `TODO.md`에 반영한다.
- 메인이 검증 후 Conventional Commits로 분리 커밋한다. push·PR·merge·release·deploy는 별도 사용자 승인 전 수행하지 않는다.
