# 계획·거래 트랜잭션 평가

## Baseline

- SQL 계약은 있으나 Spring 구현은 없다.
- 소비 하한, refresh session, 재계획 source transaction 소유권 제약은 없다.

## 합격 조건

- FastAPI 호출 동안 DB 트랜잭션·락을 유지하지 않고, 응답 뒤 goal lock과 snapshot 재검증을 거친다.
- 금융 입력 변경은 계산·계획 저장과 함께 성공하거나 전부 rollback한다. FastAPI 기술 실패는 503이다.
- `INFEASIBLE` 현실 입력과 버전은 저장하되 기존 ACTIVE를 유지한다.
- 거래 import는 계산 실패와 무관하게 저장되고, 동일 `(user_id, external_transaction_id)`는 멱등이다.
- 동일 source transaction의 event는 하나뿐이며 transaction·goal·event 사용자 소유권을 앱과 DB가 모두 막는다.
- p95×1.15, 누적 1.20, 7·14·21일, 최소 20건과 planned=0 별도 경고가 경계값에서 정확하다.

## 집중·적대 테스트

- 동시 금융 프로필 변경, 계산 중 snapshot 변경, malformed/timeout/5xx 응답, 저장 중 제약 실패.
- 교차 사용자 goal/transaction 연결, 동일 import·retry 반복, 이미 결정·proposal 연결 event retry.
- 표본 19/20, threshold 동일/1원 초과, planned 0, ratio 1.1999/1.20.

## 회귀

```bash
cd core-api
./gradlew spotlessCheck
./gradlew check
```

