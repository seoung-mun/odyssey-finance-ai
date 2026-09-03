# 온보딩 demo transaction 적용 평가

## 목표

직접입력 사용자의 프로필·재무·목표·예정지출과 non-DEMO 거래를 보존하면서 생년월일 기준
demo transaction만 멱등 적용하고, 기존 INITIAL 70/80/90 계획 생성까지 실제로 연결한다.

## 기준선

- preset 경로는 `GET /demo/testers` → `POST /me/demo-seed` → INITIAL 계획을 생성한다.
- direct 경로는 한 화면에서 프로필·재무·수동 3개월 거래·목표를 순차 저장한다.
- `seed_demo_user`는 사용자 전체 demo 상태를 만들기 때문에 direct 입력 보존에 사용할 수 없다.
- 계획 생성은 profile row, financial profile, active goal, 완료된 과거 거래 3개월 이상을 요구한다.

## 계약과 트랜잭션 경계

- 공개 API: `POST /api/v1/me/demo-transactions`, request body 없음, Bearer 사용자만 사용한다.
- 서버 KST 기준 만 나이로 `<=34 youth`, `35..54 middle`, `>=55 senior`를 결정한다.
- birthDate가 없으면 400 validation 오류를 반환한다.
- DB 함수 호출 한 transaction 안에서 해당 사용자의 기존 `source_id='DEMO'` 거래만 교체하고
  최신 template을 실제 과거월로 복사한다. non-DEMO 거래와 프로필·재무·목표·예정지출은 불변이다.
- 사용자 단위 DB lock으로 동시 요청을 직렬화한다. 외부 서비스 호출은 없다.
- 응답은 `testerId`, `scenarioVersion`, `inserted`, `completeMonths`를 반환한다.

## 합격 조건

1. 만 34/35/54/55세 및 생일 전후 경계가 결정론적으로 매핑된다.
2. birthDate 누락은 명시적 400 오류다.
3. 호출 전후 프로필·재무·목표·예정지출·non-DEMO 거래가 동일하다.
4. 같은 endpoint를 연속·동시에 호출해도 DEMO 거래가 배증하지 않는다.
5. `scheduled_expense_id`는 모두 null이고 완료된 과거월이 24개다.
6. 적용 후 기존 INITIAL 계획 API가 실제 Analysis와 PostgreSQL에서 70/80/90 option을 만든다.
7. preset demo-seed 경로는 회귀하지 않는다.
8. direct Web 흐름은 Step 3 저장 → 예정지출 CRUD → sample 소비 패턴 적용 → INITIAL 비교로 이어진다.
9. 실제 금융기관 연결을 주장하지 않으며 금액 scaling이나 analysis 계산 변경이 없다.

## 집중 테스트

- Core 단위: 나이 경계, 누락 birthDate, repository 결과·DB 오류 매핑, controller JWT 소유권.
- PostgreSQL: template 선택, 24개월, DEMO 교체, non-DEMO·사용자 입력 보존, 동시 호출.
- Web 단위: GSI 보존, preset/direct 분기, 8개 필드와 regionCode, 예정지출 CRUD·날짜 경계,
  sample 적용 실패/재시도, 성공 뒤 INITIAL 호출.
- REAL E2E: `/auth/e2e` direct 전체 흐름과 youth preset 회귀.

## 적대적 사례

- 생일 당일·하루 전후, 윤년 생일, 미래/누락 birthDate.
- endpoint 연타와 응답 유실 후 재시도.
- 기존 MANUAL 거래와 예정지출이 있는 사용자.
- template 누락·불완전 24개월·DB rollback.
- 목표일 이후 예정지출, API 중간 실패, 뒤로가기와 재시도.

## 전체 회귀

- Analysis 전체 검사(계산 코드는 변경하지 않음), Core `./gradlew check`, Web lint/test/build.
- OpenAPI 검증, compose config/build/up과 health, route interception 없는 REAL E2E.

## 완료 판정

실제 PostgreSQL native 함수, 실제 Core↔Analysis 계획 생성, 실제 브라우저 direct 및 preset 흐름이
검증되지 않으면 COMPLETE로 판정하지 않는다. 환경 문제는 실패를 숨기지 않고 미검증으로 보고한다.
