# Stage 2 policy benefit persistence / API 평가

## 범위

기관에서 실제 지원이 확정됐다고 사용자가 명시한 calculable policy benefit을 Core와
PostgreSQL에 저장·조회·취소한다. Planning, Replan, Analysis, Web에는 연결하지 않는다.

## 확정 계약

- 식별 단위: `user_id + goal_id + policy_version_id`
- 활성 상태: `CONFIRMED`; 복구 이력 상태: `CANCELLED`
- 활성 중복: PostgreSQL partial unique index와 서비스의 goal 쓰기 잠금으로 방지한다.
- 동일 금액·시작월·종료월 재요청: 기존 `CONFIRMED` 행을 반환한다.
- 의미가 다른 재요청: 기존 행을 덮어쓰지 않고 `409 POLICY_BENEFIT_CONFLICT`로 거부한다.
- 취소: hard delete 없이 `CANCELLED`; 반복 취소는 같은 행을 반환한다.
- 취소 후 재확정: 새 `CONFIRMED` 행을 만들고 이전 이력은 보존한다.
- API 월 형식: `YYYY-MM`; DB 저장은 해당 월 1일인 `DATE`다.
- 목록: 인증 사용자가 소유한 ACTIVE goal의 `CONFIRMED` benefit만 반환한다.
- 계산 rule source of truth: ACTIVE snapshot에 속한 APPROVED/ALLOW/effective policy version과
  PostgreSQL `policy_calculation_rules`다.
- eligibility: 기존 policy region/age evaluator를 검색과 확인 API가 함께 사용한다. cosine
  ranking은 확인 조건이 아니다.
- goal horizon 추가 제약은 만들지 않고 Stage 3A 통합 시 결정한다.

## Baseline

- informational ALLOW 23, calculable 11, ONE_TIME 9, MONTHLY 2가 runtime activation 가능하다.
- `policy_benefits` 테이블과 공개 confirm/list/cancel API는 없다.
- 정책 benefit은 Planning/Replan/Analysis 입력에 사용되지 않는다.

## Acceptance

1. V12 additive migration이 PostgreSQL 16의 현행 schema와 V4~V11 다음에 적용된다.
2. ONE_TIME과 MONTHLY 실제 승인 정책을 생성하고 DB 필드가 정확히 저장된다.
3. `institutionConfirmed=true`, 소유 ACTIVE goal, active/effective APPROVED+ALLOW calculable
   policy, 현재 profile region/age, rule cap과 기간 제약을 모두 검증한다.
4. 동일 요청 두 번은 행 하나, 금액/기간 변경은 409다.
5. cancel은 soft cancel이고 반복 가능하며 cancel 뒤 새 확인이 가능하다.
6. informational-only, 비활성/비승인/비ALLOW, 타인 goal, 금액/기간 오류를 거부한다.
7. 천원 복비 cap은 300,000원이며 1,000원 별도 adjustment를 만들지 않는다.
8. 기존 policy search/import와 goal 소유권 경로가 회귀하지 않는다.
9. Planning, Replan, Analysis, Web 및 policy approval artifact는 변경하지 않는다.

## Focused tests

- DTO/month helper validation: false confirmation, zero amount, 필수 월, 역전·초과 기간
- Service/API: exact retry, changed amount/period conflict, ownership, eligibility, informational 거부
- PostgreSQL HTTP: 실제 approved ONE_TIME/MONTHLY 정책, DB 저장, list, cancel, reconfirm,
  partial unique/index 동작
- 기존 policy importer/search 관련 regression subset

## Adversarial cases

- 같은 goal/policy에 대한 동시 confirm
- CANCELLED 이력과 새 CONFIRMED 행 공존
- client가 rule cap/mode를 위조하거나 기간 끝을 생략
- active snapshot 밖의 과거 policy version 직접 지정
- 다른 사용자의 goal/benefit ID 사용
- 월말·윤년과 무관하게 월 개수 inclusive 계산

## Full regression

- Core `spotlessCheck` 및 `check`
- 실제 PostgreSQL을 요구하는 focused test는 별도 격리 PostgreSQL 16에서 실행한다.
- Stage 2와 무관한 full browser/Docker E2E는 실행하지 않는다.
