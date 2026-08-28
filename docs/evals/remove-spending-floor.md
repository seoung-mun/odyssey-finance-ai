# 소비 하한 계산 계약 삭제 평가

## Baseline

- Public/Internal OpenAPI, DB, Analysis, Core, Web 약 30개 파일에 소비 하한 계약이 퍼져 있다.
- Analysis는 하한을 해석해 추천액과 최대 절감률을 보정하고 Core가 파생값을 저장·노출한다.

## Acceptance

- `SpendingFloorInput`, `ResolvedSpendingFloor`, `floorApplied`,
  `effectiveMaxReductionRate`와 프로필 하한 입력을 전 계층에서 삭제한다.
- `/me/sample-data`, 하드코딩 샘플 적재와 `sampleDataLoadedAt`도 함께 삭제한다.
- 하한 분기 외 IID 부트스트랩, 몬테카를로 경로와 분위수 절감률 수식은 바꾸지 않는다.

## Focused tests

- 기존 입력에서 하한 필드 없이 Analysis 요청·응답이 검증되고 옵션 3개가 계산된다.
- Core 직렬화·저장 스냅샷과 Web 파서·화면에 삭제 필드가 남지 않는다.
- 실제 PostgreSQL에 V5를 적용하고 JPA `validate`가 통과한다.

## Adversarial cases

- 구 클라이언트가 삭제 필드를 보내도 계산 계약에 다시 유입되지 않는다.
- 기존 하한값이 있는 DB에 V5를 적용해도 다른 금융 프로필·계획 결과는 보존한다.
- 제거 후에도 지출 0, 지출>소득, 잔여 1개월과 목표 불가능 입력이 기존 방식으로 처리된다.

## Full regression

- Analysis 전체 테스트, Core `./gradlew check`, Web lint/build
- 하한 제거 전후 엔진 diff에서 MC·부트스트랩 수식 변경이 없음을 확인한다.
