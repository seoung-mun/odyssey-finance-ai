# 데모 seed 평가

## Baseline

- `/me/sample-data`는 한 가지 하드코딩 샘플만 적재하며 장기 분포·재현성·격리를 검증하지 않는다.

## Acceptance

- 세 테스터는 오프라인 생성된 24개 완전월과 현재 월 템플릿을 PostgreSQL에서 재생한다.
- 동일 테스터·버전·KST 날짜의 거래 배열 SHA-256이 동일하다.
- 전체 월합계 CV는 0.12~0.35이고 각 시나리오 목표 구간과 목표 예산 비율을 만족한다.
- 첫 계획의 옵션 3개는 절감률 0.05~0.30 안에서 서로 다르며 경고 경계도 시연한다.
- seed는 계획을 만들지 않고 reseed는 해당 데모 사용자 데이터만 초기화한다.

## Focused tests

- 실제 PostgreSQL에서 migration, 최초 seed, 동일일 reseed와 사용자별 동시 seed를 실행한다.
- Analysis 입력은 24개 비음수 완전월이고 예정지출 연결 거래는 표본에서 제외한다.
- 카테고리별 p50·p95는 오프라인 통계의 ±10% 이내다.

## Adversarial cases

- 데모 상태가 없는 기존 데이터 사용자, 알 수 없는 테스터와 교차 사용자 reset은 409다.
- 같은 사용자의 동시 seed는 advisory lock으로 직렬화되고 중복 행을 만들지 않는다.
- 건당 금액과 월 합계는 각각 `10^15` 미만이며 초과 템플릿은 실패한다.
- 삭제된 계획을 가리키는 Redis 설명 메시지는 worker가 종료·ack하고 재시도 루프를 만들지 않는다.

## Full regression

- 다른 사용자의 관련 행 해시와 `users`, 인증·세션 테이블이 seed 전후 동일하다.
- Analysis 전체 테스트, Core `./gradlew check`, Web lint/build, Docker Compose REAL 브라우저 흐름
