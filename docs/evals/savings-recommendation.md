# 적금 추천·what-if 결정론 검증 계약

## 확정 입력 경계

- 대상은 로그인 사용자의 `ACTIVE financial_goal`에 속한 `ACTIVE plan_version`과 그 계획의
  `selected_at IS NOT NULL` 옵션이다. 하나라도 없으면 422 `ACTIVE_PLAN_REQUIRED`다.
- 월저축액은 계획 생성 당시 snapshot만 사용해
  `monthly_income_snapshot - monthly_fixed_cost_snapshot - recommended_monthly_spending`으로
  계산한다. 0 이하이거나 `long` 범위를 넘으면 422 `INVALID_MONTHLY_SAVINGS`다.
- 잔여 개월 `R`은 오늘이 아니라 `plan_version.as_of_date`의 달부터
  `target_date_snapshot`의 달까지 양 끝 달을 포함한다.
- 추천 목록은 자가체크 없이 기본금리로만 순위를 정한다. what-if는 클라이언트가 보낸
  `optionId`와 `conditionIds`로 서버의 현재 활성 데이터를 다시 읽는다.
- 계산과 자가체크에는 `source='RULE' AND review_needed=false`인 조건만 참여한다.
  `source='LLM'` 조건은 참고 데이터일 뿐 승인·승격 기능도 만들지 않는다.

## 계산과 정렬

- 단리 세전이자: `floor(월저축액 × T(T+1)/2 × 적용연이율 / 1200)`
- 앞당김 개월: `floor(세전이자 / 월저축액)`
- 적용연이율: `min(기본금리 + 선택 RULE 우대금리 합, 최고우대금리)`
- 상품 내부 옵션: 세전이자 내림차순 → 기본금리 내림차순 → 기간 오름차순 →
  `rsrv_type` → 옵션 ID.
- 상품: 앞당김 개월 내림차순 → 세전이자 내림차순 → `fin_co_no` → `fin_prdt_cd`.

## Golden cases

1. `as_of_date=2026-09-05`, `target_date_snapshot=2027-08-31`이면 `R=12`다.
2. 월소득 4,000,000원, 고정비 1,200,000원, 선택 권장지출 1,800,000원이면 월저축액은
   1,000,000원이다.
3. `T=12`, 기본금리 3.0%이면 세전이자는 195,000원이고 앞당김은 0개월이다.
4. 기본금리 3.0%, RULE 우대 합 0.7%p, 최고우대 3.5%이면 적용금리는 3.5%다.
5. `T=24`, `R=12`인 what-if는 `calculable=false`이며 기간·금리·월저축액·이자·앞당김
   숫자를 모두 `null`로 반환하고 안내 문구에도 숫자를 넣지 않는다.
6. `max_limit=null` 상품은 포함하고, 명시 한도보다 월저축액이 크면 제외한다.
7. 다른 상품·비활성·LLM condition ID와 중복 condition ID는 400이다.
8. 서로 다른 은행이 같은 상품코드를 사용한 완전 동점도 `fin_co_no`, `fin_prdt_cd` 순으로
   안정적으로 정렬한다.

## 검증 명령

```bash
cd core-api
./gradlew test --tests 'com.dacon.core.savings.*' --no-daemon
./gradlew check --no-daemon
```
