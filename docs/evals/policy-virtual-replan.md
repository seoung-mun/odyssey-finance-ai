# 정책 검색·가상 재계획 평가

기준일: 2026-08-31

## Baseline

- Public OpenAPI는 32개, Internal OpenAPI는 4개 operation이다.
- Web에는 거래·예정지출·전체 계획·재계획 결정을 끝까지 수행하는 사용자 route가 없다.
- 정책 catalog, 검색 snapshot, 계산 rule, 저장 없는 정책 시나리오 계약과 DB schema가 없다.
- 기존 REAL QA는 실제 PostgreSQL·Redis·Uvicorn·Spring·Caddy·Chromium을 기동하지만 Public operation 전수와 사용자 클릭 전수는 아직 대조하지 않는다.

## Acceptance

1. Public 34개와 Internal 5개 operation이 명세·실제 HTTP에서 일치한다. Google exchange 한 건만 승인된 `e2e` profile 우회이며 나머지는 실제 제품 경계를 사용한다.
2. 중앙정부·서울·경기·인천 청년 주거정책 20~30개가 승인 artifact로 멱등 적재되고 공식 provenance가 100% 존재한다.
3. 사람 검증이 끝나지 않은 정책은 `INFORMATIONAL`/`ELIGIBILITY_ONLY`이며 계산 CTA가 0건이다.
4. `CALCULABLE` 정책은 사용자가 기관에서 확인한 금액·기간만 ONE_TIME_FUNDING 또는 MONTHLY_EXPENSE_REDUCTION 하나로 변환한다.
5. 현재/가정 계획은 동일 seed·sampled paths를 쓰며 기존 권장 월 저축액·절감률·목표일 내 충족률만 비교한다.
6. 성공·오류·Analysis 종료·동시 요청 전후 PlanVersion, ACTIVE 계획, 거래, 예정지출, 재계획 이벤트, Redis fingerprint 변화가 0건이다.
7. `/transactions`, `/plans`, Dashboard의 프로필·재무·목표·정책 dialog를 route interception 없는 실제 Chromium 클릭으로 완주한다.

## Focused tests

- OpenAPI lint와 operationId 자동 대조: Public 34, Internal 5.
- PostgreSQL 16.4에서 schema/migration 재실행, ACTIVE snapshot 유일성, 1024차원 JSONB, FK·상태·멱등성 검증.
- Spring 실제 HTTP에서 정책 Top 3, 소유권, 미승인 계산 거부, 날짜·금액·기간 경계, Analysis 503 매핑.
- FastAPI 실제 Uvicorn에서 adjustment 월별 cashflow와 current/assumed golden 원 단위 대조, 결정성 10/10.
- 실제 Caddy HTTPS/Chromium에서 인증→온보딩→거래/환불→목표→계획→예정지출→재계획→정책 비교→새로고침/재로그인.

## 승인 artifact import 계약

- artifact identity는 `(artifactVersion, manifestSha256, embeddingModel, embeddingDimension)`이다.
- 같은 identity 재실행은 기존 snapshot ID를 반환하고 정책·버전·청크·membership 행 수와 ACTIVE snapshot을 바꾸지 않는 성공이다.
- 같은 `artifactVersion`에 다른 manifest/model/dimension이면 전체 import를 중단하고 기존 snapshot을 유지한다.
- source는 `sourceKey`, policy는 `policyKey`, version은 `(policyId, sourceVersion)`, chunk는 `(policyVersionId, chunkIndex)`로 멱등 upsert한다.
- sources→policies→versions/sources/chunks/query profiles/calculation rules→BUILDING snapshot/membership 순으로 한 transaction에서 적재한다.
- 모든 version은 `APPROVED`여야 snapshot membership에 들어가며, 계산 mode는 승인 rule의 adjustment type·source version·locator/hash와 일치해야 한다.
- 전체 hash·1024차원·관계·golden 검증 뒤에만 기존 ACTIVE를 RETIRED, 새 snapshot을 ACTIVE로 같은 transaction에서 전환한다.
- 중간 오류·프로세스 종료·동시 import는 새 ACTIVE나 부분 membership을 남기지 않는다. 동일 artifact 재시도가 복구 경로다.

## Adversarial cases

- `null`, 0, 음수, `10^15` 초과, int64 overflow, 역전 기간, horizon 비중첩, 월말·윤년·KST 경계.
- 비소유 plan/policyVersion, 만료·미승인 정책, 재계획 미수락 proposal 직접 선택.
- 동일 요청·동시 요청, 실제 Analysis kill/SIGSTOP, Redis 중단·재기동, 인증 만료와 연타.
- 정책 답변·기관 확정 금액·기간·token·cookie가 DB, hash, 애플리케이션/분석 로그, Playwright trace에 남는지 검사.

## 검색 품질

- 사람 확정 golden query 30개에서 Recall@3 `>= 0.90`, MRR `>= 0.80`.
- false eligibility 0건, 공식 provenance 100%.
- metadata-only baseline보다 Recall@3와 MRR가 모두 낮지 않고 하나 이상 `>= 0.05` 향상.

### 구현 중 발견한 판정 제약

- 현재 Public 요청은 자유 검색어를 받지 않고 `supportGoal`과 고정 질문 답만 받으며,
  `policy_query_profiles`도 supportGoal당 벡터 하나다. 따라서 같은 supportGoal의 모든 golden query는
  실제 Core에서 동일한 Top 3를 받는다.
- 2026-09-01 후보를 실제 Core와 같은 고정 profile로 재평가한 값은 Recall@3 `0.5667`, MRR
  `0.4901`이다. query별 문장을 새로 embedding한 `1.0`/`0.9167`은 현재 제품 경로의 합격 근거가
  아니다.
- 자유 검색어 또는 답변별 profile 계약을 추가할지, 고정 supportGoal Top 3에 맞는 별도 품질 지표로
  바꿀지 승인되기 전에는 이 검색 품질 항목을 통과 처리하지 않는다.

## 성능·안정성

- warm 정책 검색·시나리오 p95 `<= 1s`, p99 `<= 2s`.
- 동시성 4, 100요청에서 오류·timeout 0, throughput `>= baseline의 70%`.
- 동시성 8 공격에서 예상 밖 5xx·timeout 0.
- 30분 soak에서 OOM·restart 0, 마지막 10분 RSS p95 `<= warm RSS의 120%`.
- clean stack 기능 회귀 3/3, 성능 3회 중앙값.

## Full regression

- Analysis 전체 검사.
- Core `./gradlew check --rerun-tasks`.
- Web lint·test·build와 route interception 없는 REAL Chromium.
- 실제 PostgreSQL schema/verify, 실제 Redis, 실제 Core→Analysis HTTP.
- `scripts/real_scenario_qa.py`의 Public/Internal operation 대조와 전체 상태 fingerprint.

## 불합격 조건

- 신규 `MOCK`/`CONTRACT_STUB`, H2·SQLite, FastAPI TestClient, Spring MockMvc, 브라우저 route interception을 통합/E2E 합격 근거로 사용.
- 공식 원문 locator·hash·golden 사람 대조 전 `CALCULABLE` 활성화.
- 시나리오 요청이 사용자 상태를 저장·변경하거나 사용자/정책 식별정보를 Analysis로 전달.
- 필요한 실제 경계를 실행하지 못한 항목을 통과 처리.
- judge 운영 기간(2026-09-07 11:00~2026-09-11 23:59)에 심사용 환경 배포·부하·인스턴스 변경.
