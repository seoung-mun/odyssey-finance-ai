# Policy implementation progress

## Current stage

- Stage: `Stage 1A-1 — policy runtime metadata wiring`
- Status: `COMPLETED`

## Git state

- Branch: `checkpoint/pre-policy-20260903`
- HEAD: `9ba9870452448d2672ce9fe1d199022e108bd293`
- Working tree: dirty
- Stage 1A-1 files: explicitly staged after this checkpoint update
- Unstaged unrelated path: `.gradle/` (local Gradle cache, not part of the change)
- Commit/merge/rebase/push: not performed

## Completed

- Artifact version DTO에 application status와 region/age eligibility metadata를 추가했다.
- 새 metadata가 있는 artifact는 enum, 기준일, 검증 근거, 지역 코드, 연령 범위를 strict validation한다.
- metadata가 없는 기존 artifact는 기존과 동일하게 허용한다.
- importer가 policy version별 application status, eligibility, region code를 한 transaction에서 동기화한다.
- search repository/service에는 새 metadata filter를 연결하지 않아 기존 검색 순서를 유지했다.
- 51건 reference를 exact `policyKey`로 결합한 canonical handoff를 생성했다.
- PENDING review 상태와 non-calculable 상태를 유지했고 human approval 정보는 생성하지 않았다.

## Files changed

- `core-api/src/main/java/com/dacon/core/policy/PolicyDtos.java`
  - artifact 전용 application status와 eligibility DTO 추가
- `core-api/src/main/java/com/dacon/core/policy/PolicyArtifactImportService.java`
  - 새 metadata validation과 DB persistence 추가, legacy null 경로 유지
- `sql/migrations/V11__policy_runtime_metadata.sql`
  - policy version 종속 application status, eligibility, region tables/indexes/constraints 추가
- `scripts/policy_artifact/wire_runtime_metadata.js`
  - 세 reference를 exact `policyKey`/identity로 검증하고 canonical handoff를 재현
- `data/policy/odyssey_policy_51_runtime_metadata_handoff_v1.json`
  - 51개 runtime metadata handoff; review gate는 PENDING/non-importable
- `core-api/src/test/java/com/dacon/core/policy/PolicyRuntimeMetadataTest.java`
  - 51건 mapping/count/canonical manifest, invalid metadata, legacy compatibility 검증
- `core-api/src/test/java/com/dacon/core/policy/PolicyTestArtifacts.java`
  - importer test fixture에 승인된 runtime metadata 예시 추가
- `core-api/src/test/java/com/dacon/core/policy/PolicyArtifactImportPostgresTest.java`
  - 실제 PostgreSQL metadata persistence 검증 추가
- `docs/policy-implementation-progress.md`
  - 현재 stage, 검증 결과, 다음 작업 checkpoint 기록

## DB / migration state

- 새 forward migration: `V11__policy_runtime_metadata.sql`
- 추가 table:
  - `policy_version_application_status`
  - `policy_version_eligibility`
  - `policy_version_regions`
- 추가 index:
  - `ix_policy_application_status_decision`
  - `ix_policy_version_regions_code`
- 기존 row는 새 child row가 없어도 유효하며, metadata 누락과 명시적 unrestricted eligibility를 구분한다.
- 기존 migration V4~V10은 수정하지 않았다.
- 임시 PostgreSQL 16에서 01 schema와 V4~V11 순차 적용을 확인했다.

## Policy data mapping state

- total: 51
- mapped: 51
- unmapped: 0
- duplicate `policyKey`: 0
- title/supportGoal mismatch: 0
- application status: ALLOW 23 / EXCLUDE 11 / RECHECK 17
- region scope: NATIONAL 3 / LOCAL 48
- age: bounded 41 / unrestricted 10
- review status: PENDING 51
- active calculation rule: 0
- artifact bytes: 818,601
- canonical manifest: PASS (`637d04ad83a6bbdad51bf588dcb37afdcd2573a0b4b896827bf1421f1933fc41`)

## Tests actually run

- `node scripts/policy_artifact/wire_runtime_metadata.js`
  - PASS: 51 policies, ALLOW 23, EXCLUDE 11, RECHECK 17, canonical output 재현
- `./gradlew.bat --gradle-user-home ../.gradle --no-daemon cleanTest test --tests com.dacon.core.policy.PolicyRuntimeMetadataTest --tests com.dacon.core.policy.PolicySearchServiceTest`
  - PASS: metadata 3 tests, existing search 6 tests
- `./gradlew.bat --gradle-user-home ../.gradle --no-daemon cleanTest test --tests com.dacon.core.policy.PolicyArtifactImportPostgresTest`
  - PASS: PostgreSQL importer integration 7 tests
- 01 schema와 V4~V11을 임시 PostgreSQL 16에 순차 적용
  - PASS
- metadata/search/command 세 테스트를 함께 실행한 최초 시도
  - `PolicyRuntimeMetadataTest`: PASS 3
  - `PolicySearchServiceTest`: PASS 6
  - `PolicyArtifactImportCommandTest`: ENVIRONMENT FAILURE — Windows symlink privilege
- 최초 sandbox Gradle 시도
  - ENVIRONMENT FAILURE — Gradle cache JAR access denied
- 최초 PostgreSQL integration 시도
  - ENVIRONMENT FAILURE — 필수 `ANALYSIS_INTERNAL_TOKEN` 미설정; 테스트용 token 설정 후 위 7 tests PASS

## Known issues

- 51건 handoff는 사람 승인 전이므로 의도적으로 import 불가능한 PENDING candidate다.
- application status/region/age hard filter는 Stage 1A-2 범위이며 아직 구현하지 않았다.
- Windows symlink 권한이 없는 환경에서는 기존 `PolicyArtifactImportCommandTest`의 symlink case가 실행되지 않는다.

## Exact next action

`Stage 1A-2 search hard filter 구현을 시작할 준비가 됨`

적용 순서: application status ALLOW → region → age → supportGoal → APPROVED/effective → cosine → Top 0~3.
