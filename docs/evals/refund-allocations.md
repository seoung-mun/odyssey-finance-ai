# 환불 배분·소비 집계 평가

## Baseline

- 기존 월 집계는 REFUND를 발생 월 PAYMENT에서 차감해 음수 예측 표본을 만들 수 있다.
- `refund_allocations`와 결제 원월 배분 무결성은 없다.

## Acceptance

- REFUND 한 건을 여러 PAYMENT에, PAYMENT 한 건을 여러 REFUND에 배분한다.
- 같은 사용자 REFUND→PAYMENT만 허용하고 각 거래 금액을 초과해 배분하지 않는다.
- 연결 환불은 PAYMENT 발생 월 소비를 줄이고, 미연결 환불은 REFUND 발생 월 현금 유입에만 표시한다.
- 예정지출 PAYMENT는 연결 환불을 반영한 뒤 예측 표본에서 제외한다.
- 월 예측 표본은 항상 0 이상이고 소비가 소득보다 큰 상태는 그대로 허용한다.

## Focused tests

- `sql/06_verify_refund_allocations.sql`
- 신규 DB: 기존 01→02→05 적용 후 V4 migration과 검증 실행
- 기존 DB: Flyway baseline 3 이후 V4를 정확히 한 번 적용한다. version migration SQL을 직접
  재실행하는 것은 지원하지 않으며 Flyway history/checksum으로 중복 적용을 차단한다.

## Adversarial cases

- 환불액·결제액 초과 배분, 교차 사용자, REFUND→REFUND, PAYMENT→PAYMENT
- 부분·분할·다중 환불, 결제보다 먼저 수신한 pending 배분
- 같은 환불/결제에 대한 동시 INSERT
- KST 월 경계를 사이에 둔 PAYMENT와 REFUND

## Full regression

- `sql/03_verify.sql` 기존 제약 회귀
- Core 실제 PostgreSQL native query와 FastAPI 실제 HTTP 입력 검증
