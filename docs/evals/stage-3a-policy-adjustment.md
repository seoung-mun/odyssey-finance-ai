# Stage 3A policy adjustment 평가

## 범위

`policy_benefits`의 현재 목표 `CONFIRMED` 행을 Planning의 명시적
`FutureCashflowAdjustment`로 옮기고 계획 생성 당시 provenance를 기존
`plan_versions.policy_snapshot` JSONB에 보존한다.

## 불변식

- `currentSavedAmount`, `monthlyIncome`, `monthlyFixedCost`는 변경하지 않는다.
- Analysis 요청 필드와 계산식, Monte Carlo, Replan, 공개 API, DB schema는 변경하지 않는다.
- benefit이 없으면 immutable empty list이며 기존 Analysis 요청은 동일하다.
- `CANCELLED` 및 다른 goal 행은 Stage 2 confirmed 조회 경로에서 제외한다.
- 정렬은 `startYearMonth`, `policyBenefitId` 오름차순이다.
- 불가능한 type/기간/금액/status는 mapping 경계에서 fail closed한다.

## 합격 조건

- ONE_TIME과 MONTHLY가 원본 금액·기간·식별자·확정시각을 잃지 않고 각각 매핑된다.
- 여러 benefit은 합산하지 않고 개별 adjustment로 유지된다.
- 계획 저장본 `policySnapshot.futureCashflowAdjustments`가 이후 현재 benefit 상태와 독립적으로
  당시 값을 유지한다.
- non-empty adjustment도 Stage 3A에서는 Analysis 요청과 계산 결과를 바꾸지 않는다.

## Focused tests

- 빈 목록, ONE_TIME, MONTHLY, 결정적 정렬, 복수 benefit, 불가능 상태 거부
- PlanningQueryService에서 원본 금융 상태 불변 및 adjustment 연결
- PlanningService의 Analysis 요청 불변
- PlanningCommandService의 plan policy snapshot provenance 저장

Repository SQL과 snapshot persistence SQL을 변경하지 않으므로 별도 PostgreSQL 재실행은 하지
않는다. Stage 2의 confirmed/cancel/reconfirm/goal scope PostgreSQL 검증 결과를 재사용한다.
