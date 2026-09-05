# AI 기능 확장 권장 설계안

상태: **Phase 2·3 사용자 승인·계약 승격 및 구현 완료**
작성일: 2026-09-06
주의: 사용자가 권장 방향을 승인했다. Phase 2 계약은 `API/openapi-public.yaml`,
`sql/migrations/V13__savings_products.sql`, `docs/evals/savings-recommendation.md`로 승격했다.
Phase 3 계약도 `API/openapi-public.yaml`과 `docs/evals/local-intent-chat.md`에 반영했다.

## Phase 2 — 적금 추천·what-if

### ACTIVE 계획 입력

- 대상은 로그인 사용자의 `ACTIVE financial_goal`, 그 목표의 최신 `ACTIVE plan_version`, 그
  계획에서 `selected_at IS NOT NULL`인 옵션이다. 하나라도 없으면 422를 권장한다.
- 월저축액 후보 산식은 다음과 같다.

  `plan_version.monthly_income_snapshot`
  `- plan_version.monthly_fixed_cost_snapshot`
  `- selected plan_option.recommended_monthly_spending`

  현재 프로필 값을 다시 읽지 않고 선택 당시 계획 snapshot만 사용한다. 결과가 0 이하이거나
  `long` 범위를 넘으면 계산을 거부한다.
- 잔여 개월 `R`은 오늘이 아니라 `plan_version.as_of_date`의 달부터
  `plan_version.target_date_snapshot`의 달까지 양 끝 달을 포함한다.

### API 경계

- `GET /api/v1/savings/recommendations`: 자가체크 없이 기본금리로 상품별 최적 옵션을 계산하고
  최대 3개를 반환한다.
- `POST /api/v1/savings/products/{productId}/what-if`: `optionId`와 `conditionId[]`만 받아
  서버가 현재 저장된 조건과 금리를 다시 읽는다.
- 조건 원문, 라벨, 금리를 클라이언트 입력으로 받지 않는다.
- `active=true AND source='RULE' AND review_needed=false` 조건만 자가체크와 계산에 참여한다.
  `source='LLM'` 조건은 계산과 자가체크에서 영구 제외한다.
- `T > R`이면 계산 숫자는 반환하지 않고
  `선택한 적금 기간이 현재 계획의 남은 기간보다 깁니다.`를 반환한다.

### 계산과 total order

- 단리 세전이자:
  `floor(월저축액 × T(T+1)/2 × 적용연이율 / 1200)`
- 앞당김 개월: `floor(세전이자 / 월저축액)`
- 적용연이율: `min(기본금리 + 선택 RULE 우대금리 합, 최고우대금리)`
- 상품 내부 옵션: 세전이자 내림차순 → 기본금리 내림차순 → 기간 오름차순 →
  `rsrv_type` → 옵션 ID.
- 상품: 앞당김 개월 내림차순 → 세전이자 내림차순 → `fin_co_no` → `fin_prdt_cd`.

### golden case 후보

1. `as_of_date=2026-09-05`, `target_date_snapshot=2027-08-31`이면 `R=12`.
2. 월소득 4,000,000원, 고정비 1,500,000원, 선택 권장지출 1,500,000원이면 월저축액
   1,000,000원.
3. `T=12`, 기본금리 3.0%이면 세전이자 195,000원, 앞당김 0개월.
4. RULE 우대 1.0%p, 최고우대 3.8%이면 적용금리 3.8%, 이자 247,000원.
5. `T=13`, `R=12`이면 숫자 없는 기간 초과 fallback.
6. `max_limit=null`은 포함하고, 명시 한도보다 월저축액이 크면 제외.
7. 다른 상품·비활성·LLM condition ID는 400.

## Phase 3 — 로컬 intent 챗봇

### intent와 서비스 매핑

- `SAVINGS_RECOMMENDATION`: Phase 2 추천 조회
- `SAVINGS_WHAT_IF`: 특정 적금 what-if 화면/필수 선택 안내
- `PLAN_STATUS`: 기존 dashboard의 ACTIVE 계획 조회
- `SPENDING_SUMMARY`: 기존 월 소비 요약 조회
- `REPLAN_GUIDE`: 기존 재계획 기능 안내
- `POLICY_SEARCH`: 기존 정책 검색 안내
- `HELP`: 지원 기능 목록
- `UNKNOWN`: 고정 안내

LLM 출력은 위 enum 하나만 허용한다. 모든 금액과 계산 결과는 기존 Core 서비스에서만 나온다.

### 장애·동시성·저장 경계

- Ollama가 없거나 느리거나 kill-switch가 꺼져 있으면 결정론적 키워드 분류기로 fallback한다.
- Redis 장애 시 현재 메시지만 stateless fallback으로 처리하고 HTTP 200을 유지한다. 응답에
  `sessionMode=STATELESS_FALLBACK`을 명시하고 기존 문맥을 사용했다고 가장하지 않는다.
- 같은 session의 동시 요청은 짧은 Redis lock으로 직렬화한다. lock 획득 실패는 409
  `CHAT_SESSION_BUSY`; Redis 연결 장애는 위 stateless fallback이 우선한다.
- 입력 원문은 1~500자. Redis에 원문은 절대 저장하지 않는다.
- Redis에는 UUID session ID, 최근 intent 최대 6개, 최신 구조화 선택값만 저장한다.
- 구조화 선택값은 `productId`, `optionId`, `conditionId` 최대 20개이며 자유 문자열은 없다.
- 직렬화된 session 최대 4KiB, TTL 45분. 크기 초과 시 구조화 선택값을 버리고 intent만 유지한다.
- UNKNOWN 고정 문구:
  `요청을 이해하지 못했습니다. 적금 추천, 계획 현황, 소비 요약처럼 원하는 작업을 말씀해 주세요.`

## Ollama 모델·운영 경계

- 권장 태그: `qwen3:0.6b-q4_K_M`.
- 이유: Ollama 공식 태그에 존재하는 약 523MB Q4_K_M 모델이며, 이번 P0의 성공 기준은 품질
  경쟁보다 로컬 LLM 연결·enum/설명 생성·장애 격리 여부다.
- Ollama auto-pull은 Core에서 금지한다. 모델 준비는 compose의 별도 `ollama-model` 서비스만
  수행한다.
- 모델 없음, pull 실패, timeout, 잘못된 enum/숫자 출력은 기존 계획·추천 서비스를 실패시키지
  않고 fallback으로 끝낸다.
- `OPEN_ROUTER_API_KEY`는 외부 AI API 자격증명 변수지만 이번 P0의 Phase 1·3 요청에는 주입하거나
  전달하지 않는다.

## 원격 통합 확인 결과

- 최신 Plan/Goal 필드와 선택 옵션을 확인해 위 월저축액 산식을 확정했다.
- 공개 API는 위 두 endpoint로 분리했으며 migration은 원격 최신 번호 다음인 V13을 사용한다.
- 기존 Redis key와 겹치지 않는 `chat:session:`/`chat:lock:` prefix를 Phase 3에 사용한다.
- 프론트는 이번 백엔드 작업과 E2E 범위에서 제외한다.
