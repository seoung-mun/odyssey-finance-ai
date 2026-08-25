# PostgreSQL 제약 경합 오류 평가

## Baseline

- `GlobalExceptionHandler`는 모든 `DataIntegrityViolationException`을 409
  `STATE_CONFLICT`로 반환한다.
- `GoalServiceImpl`과 `PlanningServiceImpl`은 실제 제약 이름을 확인하지 않고 모든 무결성
  오류를 각각 `ACTIVE_GOAL_EXISTS`, `OPTION_ALREADY_SELECTED`로 바꾼다.
- 예상하지 못한 CHECK·FK·NOT NULL 위반도 정상 경합처럼 숨겨질 수 있다.

## 확정 매핑

| PostgreSQL 제약 | 공개 오류 code | HTTP |
|---|---|---|
| `uq_goal_active_per_user` | `ACTIVE_GOAL_EXISTS` | 409 |
| `uq_plan_version_proposed_per_goal` | `PROPOSED_PLAN_EXISTS` | 409 |
| `uq_plan_option_selected_per_version` | `OPTION_ALREADY_SELECTED` | 409 |
| `uq_plan_version_active_per_goal` | `OPTION_ALREADY_SELECTED` | 409 |

`DECISION_ALREADY_MADE`는 현재 대응 endpoint의 구현과 전용 DB 제약이 없어
`docs/미확정-설계.md` D-010이 확정될 때까지 구현하지 않는다.

## 합격 조건

- PostgreSQL SQLSTATE `23505`이면서 위 제약 이름과 일치할 때만 해당 409 code를 반환한다.
- 위 목록에 없는 unique 위반과 CHECK·FK·NOT NULL 위반은 일반 409로 낮추지 않고 500
  `INTERNAL_ERROR`로 처리한다.
- 정상 경합 409는 ERROR stack trace로 기록하지 않는다. 예상 밖 무결성 오류는 request ID와
  함께 ERROR로 기록한다.
- 오류 응답은 기존 RFC 7807의 `type`, `instance`, `code`, `requestId` 계약을 유지한다.
- 서비스별 광범위한 catch가 제약 이름 판정을 우회하지 않는다.

## 집중·적대 테스트

- 실제 PostgreSQL에서 위 네 unique 제약을 각각 위반해 SQLSTATE와 제약 이름을 확인한다.
- 각 제약이 정확한 409 code로 매핑되고 다른 제약 이름으로 바꾸면 500이 되는지 검증한다.
- CHECK·FK·NOT NULL 위반, cause chain에 제약 정보가 없는 예외, PostgreSQL 이외 예외를 500으로
  처리하는지 검증한다.
- 동시 목표 생성, 동시 계획 생성, 동시 옵션 선택에서 한 요청만 성공하고 나머지는 계약된
  409를 받으며 반쪽 데이터가 남지 않는지 확인한다.

## 회귀

```bash
cd core-api
./gradlew check --rerun-tasks
```

Repository mock만으로 제약 매핑을 통과 처리하지 않는다. 실제 PostgreSQL 검증을 실행하지
못하면 이 작업은 미완료다.
