# Odyssey API 명세

외부 명세는 OpenAPI 3.0.3, 내부 명세는 숫자 경계를 정확히 표현하기 위해 3.1.0이다.
`openapi-spec-validator` 통과 확인. enum 값 8종이 DB CHECK 제약과 일치하는지 자동 대조 완료.

| 파일 | 대상 | 규모 |
|---|---|---|
| `openapi-public.yaml` | Spring 외부 API (React가 호출) | 공개 인증·CRUD·계획·재계획 |
| `openapi-internal.yaml` | FastAPI 내부 API (Spring만 호출) | 4 paths / 8 schemas |

HTML 문서로 보려면:

```bash
npx @redocly/cli build-docs openapi-public.yaml -o /tmp/openapi-public.html
open /tmp/openapi-public.html
```

---

## 확정된 결정

| 항목 | 결정 |
|---|---|
| 계획 생성 응답 | **하이브리드** — 숫자·fan chart 즉시 200, LLM 설명은 폴링 |
| 인증 | Google OIDC → 자체 JWT (access 15분 + refresh cookie 7일) |
| DB 쓰기 주체 | **Spring 전담.** FastAPI는 계산 결과만 반환 |
| prefix | `/api/v1` |
| 필드 네이밍 | camelCase |
| 에러 포맷 | RFC 7807 `application/problem+json` + `code` 필드 |
| 시각 | ISO 8601 + offset 필수 |
| 금액 / 비율 | 원 단위 정수 / 0~1 소수 |
| 페이지네이션 | 커서 방식 (거래 목록만) |
| 데모 데이터 | PostgreSQL 오프라인 템플릿 재생 (`GET /demo/testers`, `POST /me/demo-seed`) |
| 청년정책 매칭 | 명세에서 제외 (후순위) |

### JWT 세부

React가 Google 로그인으로 `id_token`을 받아 `POST /auth/google`로 넘기면, Spring이
OIDC 검증 후 자체 토큰을 발급한다. SPA + 분리 배포를 전제로 골랐다.

- access token은 15분이며 응답 본문으로 받아 React 메모리에만 둔다.
- refresh token은 7일이며 HttpOnly·Secure·SameSite=Strict cookie로만 전달한다.
- `POST /auth/refresh`는 refresh를 회전하며 이전 token을 폐기한다.
- access·refresh token을 localStorage/sessionStorage에 저장하지 않는다.

---

## 하이브리드 흐름 (가장 중요)

계산은 사실상 공짜고 LLM만 느리다는 사실을 이용한다.

```
React                     Spring                        FastAPI
  │                         │                              │
  ├─ POST /goals/1/plan-versions                           │
  │                         ├─ feasibility 검사 (A>=0)     │
  │                         ├─ POST /internal/simulate ───▶│  MC 10,000 경로
  │                         │◀──────────── 옵션 + bands ───┤  (~수백 ms)
  │                         ├─ [트랜잭션] plan_version +   │
  │                         │   simulation_run + options + │
  │                         │   bands 저장 → 커밋           │
  │◀── 200 explanation.status=PENDING                      │
  │   숫자·fan chart 렌더링  │                              │
  │                         ├─ Redis Stream enqueue         │
  │                         ├─ worker: POST /internal/explanations ▶│ LLM + 가드레일
  ├─ GET .../explanation    │                              │  (5~30초)
  │◀── PENDING              │◀──────────── text ───────────┤
  ├─ GET .../explanation    ├─ UPDATE explanation_text     │
  │◀── READY + text         │                              │
  └─ 설명 영역 채움          │                              │
```

폴링은 2초 간격, 30초 후 중단. Redis 장애·timeout은 설명만 FALLBACK으로 닫고 계획은 유지한다.

`status`는 `PENDING` · `PROCESSING` · `READY` · `FALLBACK` · `FAILED`다.
`FALLBACK`은 가드레일 상한 도달 시 숫자 없는 템플릿으로 대체된 정상 종료다.

### LLM 호출은 반드시 트랜잭션 밖에서

DB에 `lock_timeout = 5s`가 걸려 있다. 계획 저장 트랜잭션 안에서 수 초짜리 LLM을
기다리면 goal 행 락을 쥔 채로 다른 요청이 죽는다. `idle_in_transaction_session_timeout
= 30s`에도 걸린다.

---

## 재계획 흐름

트리거로 발생하는 재계획은 사용자가 화면을 안 보고 있을 때 백그라운드로 돌아간다.
사용자는 접속했을 때 `GET /dashboard`의 `pendingProposal`로 알게 된다.

```
트리거 감지 (shock / drift / 입력변경 / 월 정기)
  → replan_event 생성 (source = 현재 ACTIVE)
  → 기존 미결정 PROPOSED 가 있으면 STALE 처리   ← 순서 중요
  → 새 PROPOSED + simulation_run + options 생성
  → 사용자 접속 시 배너 노출
      [새 계획 적용] → POST /replan-events/{id}/decision (ACCEPT_NEW_PLAN)
                     → POST /plan-versions/{id}/select-option
                     → v1 SUPERSEDED, v2 ACTIVE
      [기존 유지]    → POST /replan-events/{id}/decision (KEEP_CURRENT_PLAN)
                     → v2 REJECTED, v1 유지, 해당 월 소비 기반 알림 억제
```

`KEEP_CURRENT_PLAN` 이후에도 소득·고정비·예정지출·목표 변경 트리거는 억제 없이
즉시 발생한다. 억제 대상은 shock·drift뿐이다.

---

## 표현 규칙 (React 팀 필수)

기획서 5-4의 용어 원칙이 API 필드명에 그대로 반영돼 있다.

- `simulationCoverage`는 **"시뮬레이션 충족률"** 또는 **"계획 안정성 수준"**으로 표기.
  "목표 달성 확률 90%" 같은 확정적 표현은 소비자 보호 리스크가 있어 **사용 금지**
- CUSTOM 옵션은 **"baseline 선택 → 충족률 계산"** 순서로 표현.
  "86% 계획이므로 85만원" 같은 역순 표현 금지
- `aggressiveWarning`은 차단이 아니라 재확인 유도. 사용자는 무시하고 진행 가능

---

## 409를 어떻게 다룰 것인가

DB의 부분 유니크 인덱스가 동시 요청을 막아주기 때문에, 409는 버그가 아니라 정상
흐름의 일부다. 실측으로 두 세션이 동시에 재계획하면 한쪽이 반드시 실패하고
최종 상태는 정상(PROPOSED 1개)임을 확인했다.

| code | 상황 | 클라이언트 대응 |
|---|---|---|
| `PROPOSED_PLAN_EXISTS` | 다른 요청이 이미 최신 제안 생성 | 해당 goal 재조회 |
| `OPTION_ALREADY_SELECTED` | 이미 다른 옵션 선택됨 | 계획 재조회 |
| `PLAN_NOT_SELECTABLE` | STALE/REJECTED 버전에 선택 시도 | 대시보드 재조회 |
| `DECISION_ALREADY_MADE` | 이미 결정된 재계획 이벤트 | 타임라인 재조회 |
| `ACTIVE_GOAL_EXISTS` | ACTIVE 목표 중복 생성 | 기존 목표 안내 |

Spring은 `DataIntegrityViolationException`을 제약 이름으로 분기해 위 코드로 매핑한다.
SQLSTATE만 보면 전부 `23505`라 구분이 안 된다.

로그 레벨은 WARN 이하로. ERROR로 찍으면 배치 중복 실행 때 매달 1일마다 가짜 알람이
울린다.

---

## 명세에 안 들어간 것

- **청년정책 매칭** (기획서 5-2) — 후순위. `user_profiles.region_code`는 스키마에
  이미 있으므로 나중에 엔드포인트만 추가하면 된다
- **관리자·운영 API** — 이번 공개 계약에는 포함하지 않음
- **거래 수동 등록 단건** — `POST /transactions/import`로 커버. 단건도 배열에 하나만
  담아 보내면 되고, `externalTransactionId`에 UUID를 발급해 넣는다

---

## 팀별 다음 단계

**Spring**
1. `openapi-public.yaml`로 controller 인터페이스 생성 (openapi-generator)
2. 재계획 트랜잭션 경계부터 구현 — 여기가 제일 까다롭다
3. `DataIntegrityViolationException` → 409 매핑을 먼저 넣어둘 것

**FastAPI**
1. `openapi-internal.yaml`로 Pydantic 모델 생성 (datamodel-code-generator)
2. `/internal/simulate` 핸들러는 `async def`가 아니라 `def`로
3. numpy 결과를 `int()`로 캐스팅하는 레이어를 공통 함수로

**React**
1. `openapi-public.yaml`로 타입 생성 (openapi-typescript)
2. 하이브리드 폴링 훅을 먼저 만들어둘 것 — 화면 여러 곳에서 쓴다
3. `GET /dashboard` 하나로 진입 화면이 다 그려지도록 설계돼 있다
