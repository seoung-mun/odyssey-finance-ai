# Odyssey 정책 RAG 아키텍처 설계

기준일: 2026-08-30
상태: DB/API 승인 전 목표 설계
범위: 2030 청년의 주거·내집마련 정책 탐색

## 1. 설계 결론

MVP는 **승인된 정책 메타데이터로 후보를 좁히고 KURE 임베딩으로 관련성을 순위화하는
hybrid retrieval**을 사용한다. 임베딩은 신청 자격이나 금액을 판정하지 않는다. 외부 LLM은
공개 정책 원문을 오프라인에서 구조화하는 데만 쓰며 사용자 요청 경로와 분리한다.

- 정책·`supportGoal` query profile embedding: 개발자 배치에서 생성
- 저장: PostgreSQL + pgvector, 1024차원
- 런타임: Spring이 `supportGoal`·고정 추가 질문·metadata filter·vector 검색을 조율
- 계산: 기존 FastAPI 결정론적 엔진과 분리
- 설명: 검증된 metadata와 원문 근거를 템플릿으로 조립
- KURE 서버: P0에서는 불필요. 자유입력과 런타임 모델은 P0 완료 뒤 P1에서 검토
- sLLM: 정책 검색 경로에 사용하지 않음

## 2. 신뢰 경계

```mermaid
flowchart LR
  subgraph PUBLIC[공개 데이터 경계]
    SRC[정부·공공기관 원문/API]
    CLM[외부 LLM 메타데이터 초안]
    BATCH[KURE 오프라인 임베딩]
  end

  subgraph PRIVATE[개인 데이터 경계]
    WEB[React]
    CORE[Spring Core]
    DB[(PostgreSQL + pgvector)]
    CALC[FastAPI 계산 엔진]
  end

  SRC --> CLM --> BATCH --> ARTIFACT[seed artifact + manifest]
  ARTIFACT --> CORE
  WEB --> CORE
  CORE --> DB
  CORE --> CALC
  CLM -. 금지 .-> WEB
  CLM -. 금지 .-> CORE
```

외부 LLM으로 전송 가능한 데이터는 정책 원문, 원문 URL, 공개 기관명, 정책 ID, 비민감
분류 체계뿐이다. 사용자 식별자, 개인 조건, 금융 데이터는 전송하지 않는다.

## 3. 오프라인 정책 수집 파이프라인

1. 공식 URL/API에서 원문을 가져온다.
2. HTML·첨부문서에서 본문과 표를 정규화하고 원문 hash를 계산한다.
3. 외부 LLM에 고정 JSON Schema로 metadata와 eligibility rule 후보를 요청한다.
4. enum·날짜·금액 단위·근거 구간을 기계 검증한다.
5. 관리자가 원문과 비교해 `APPROVED` 또는 `REJECTED`를 결정한다.
6. 승인 원문을 정책 단위와 의미 단위 청크로 나눈다.
7. 정책 청크와 `supportGoal` query profile을 같은 KURE 모델·버전으로 임베딩한다.
8. manifest, 원문 hash, 모델 버전, 차원, 생성 시각을 seed artifact에 기록한다.
9. Spring import가 artifact의 hash·model revision·dimension을 검증한 뒤 실제 PostgreSQL에
   적재하고, 검색 평가를 통과한 snapshot만 배포한다. 배치는 DB 자격증명이나 직접 연결을 갖지 않는다.

외부 LLM 출력은 다음과 같은 후보 JSON이다. `UNKNOWN` 문자열을 여러 타입에 섞지 않는다.
모든 추출 필드는 `{ value, evidence }` 형태로 받고, 근거가 없으면 `value: null`,
`evidence: []`, `reviewState: "UNKNOWN"`으로 고정한다. 값이 있는데 근거가 없거나 locator의
quote hash가 원문과 맞지 않으면 JSON Schema 통과 여부와 무관하게 후보 전체를 자동 반려한다.

```json
{
  "policyId": "source-stable-id",
  "title": {
    "value": "정책명",
    "evidence": [{ "quoteHash": "sha256", "locator": "title" }],
    "reviewState": "CANDIDATE"
  },
  "audiences": {
    "value": ["YOUTH", "NEWLYWED"],
    "evidence": [{ "quoteHash": "sha256", "locator": "section-3" }],
    "reviewState": "CANDIDATE"
  },
  "housingGoals": {
    "value": ["PURCHASE"],
    "evidence": [{ "quoteHash": "sha256", "locator": "section-4" }],
    "reviewState": "CANDIDATE"
  },
  "supportTypes": { "value": null, "evidence": [], "reviewState": "UNKNOWN" },
  "regions": {
    "value": ["NATIONWIDE"],
    "evidence": [{ "quoteHash": "sha256", "locator": "section-2" }],
    "reviewState": "CANDIDATE"
  },
  "applicationPeriod": {
    "value": { "kind": "CONTINUOUS", "from": null, "to": null },
    "evidence": [{ "quoteHash": "sha256", "locator": "section-5" }],
    "reviewState": "CANDIDATE"
  },
  "eligibilityCandidate": { "value": null, "evidence": [], "reviewState": "UNKNOWN" },
  "summaryCandidate": {
    "value": "공개 원문 기반 요약",
    "evidence": [{ "quoteHash": "sha256", "locator": "section-1" }],
    "reviewState": "CANDIDATE"
  }
}
```

고정 schema는 필드별 nullable 여부, enum, evidence 최소 개수와 `additionalProperties: false`를
강제한다. 다만 schema는 형식만 검증한다. 의미가 원문과 일치하는지는 관리자 승인 단계에서
확정하며, 승인 전 후보는 검색 index에 들어가지 않는다.

## 4. 논리 데이터 모델

실제 테이블명과 제약은 승인 후 SQL에서 확정한다.

| 엔터티 | 핵심 필드 | 역할 |
|---|---|---|
| `policy_sources` | sourceUrl, provider, sourceVersion, fetchedAt, sourceContentHash | 원문 provenance |
| `policies` | canonical ID, title, lifecycleStatus | 중복 소스를 합친 정책 정체성 |
| `policy_versions` | policyId, sourceId, version, validFrom/To, reviewStatus, approvedAt, lastVerifiedAt | 변경·만료·검증 이력 |
| `policy_metadata` | versionId, audience, goal, support, region, conditions, displayPriority | 버전별 hard filter·fallback 입력 |
| `policy_chunks` | versionId, text, locator, embedding | 근거 검색 단위 |
| `policy_rule_versions` | condition AST, status, reviewer | 승인된 자격 규칙 |
| `policy_query_profiles` | `supportGoal`, 고정 질문 프로그램 버전, query text, embedding | 목적별 검색 profile |
| `policy_index_snapshots` | snapshot ID, manifest hash, status, activatedAt | 원자적 index 배포 단위 |
| `policy_snapshot_versions` | snapshotId, versionId | snapshot에 포함된 승인 버전 집합 |
| `policy_retrieval_runs` | random run ID, intent/version, index version, result IDs | 비민감 검색 재현·평가 |

`policies.lifecycleStatus`는 정책 정체성의 `ACTIVE/RETIRED`만 나타낸다.
`policy_versions.reviewStatus`는 `CANDIDATE/APPROVED/REJECTED`이고 검색 승인 여부를 소유한다.
유효기간 만료는 저장 상태를 덮어쓰지 않고 `validFrom/validTo`와 조회 기준일로 계산한다.
`sourceVersion`은 공급자 값 유무와 관계없이 수집기가 원문 변경마다 부여하는 source 내 단조 증가
버전이고, `lastVerifiedAt`은 관리자가 해당 policy version을 마지막으로 원문 대조한 시각이다.
`displayPriority`는 승인 시 정하는 제한된 정수이며 동률 순서를 위한 것일 뿐 자격 점수가 아니다.
`sourceUrl`, `sourceContentHash`, `sourceVersion`, `lastVerifiedAt`은 version에서 source를 따라 반드시
복원할 수 있어야 하며 정책 카드 응답에서는 모두 required다. 여러 원천을 병합한 경우 카드에는
판정에 사용한 모든 source를 배열로 반환하고 임의의 대표 URL 하나로 축약하지 않는다.

개인 조건 원문이나 그 hash/HMAC은 retrieval run에 저장하지 않는다. 작은 후보 공간의 혼인·자녀·
무주택·소득 구간은 hash만으로도 추정될 수 있기 때문이다. 개인 조건은 요청 메모리에서만
eligibility 계산에 사용하고 응답 후 폐기한다. 장기 평가는 개인 조건 없는 고정 fixture와 집계
지표만 사용한다.

`plan_versions.policy_snapshot`은 계산 파라미터 snapshot이므로 정책 카탈로그 저장소로 재사용하지
않는다. 정책 적용 what-if를 만들 때만 선택한 `policyVersionId`와 승인 rule version을 별도
snapshot으로 연결한다.

## 5. 메타데이터와 자격 상태

### 5-1. 공통 입력

- 영구 저장: 생년월일 또는 기준일 나이, 거주지역
- 검색 요청 한정: 혼인 상태, 자녀 여부/수, 본인 주택보유, `supportGoal`과 목적별 추가 질문
- 정책 metadata에는 개인·부부·가구 소득·자산 기준을 보존하되 P0 Rule Engine은 판정하지 않는다.
  해당 기준이 있는 정책은 `NEEDS_CONFIRMATION`과 신청기관 최종 확인 문구를 반환한다.

정책이 요구하지 않는 조건은 수집하지 않는다. 사용자 입력이 없으면 추가 질문은 할 수 있지만
값을 추측하지 않는다. 검색 요청 조건은 응답 후 폐기하고 로그·hash·HMAC에 남기지 않는다.

`supportGoal`은 `PURCHASE`, `JEONSE`, `MONTHLY_RENT`, `PUBLIC_RENTAL`, `SUBSCRIPTION`,
`MOVING_COST`, `GUARANTEE`, `DORMITORY`다. 이는 정책의 `housingGoals`·`supportTypes`와 별도이며
one-to-many로 매핑한다. UI의 `잘 모르겠어요` 선택지는 P0에서 제공하지 않으며, 유효하지 않은 값은
400 입력 오류다. 고정 질문 프로그램의 문구·허용 답 enum은 승인 정책 목록과 함께 OpenAPI 계약 단계에서
확정한다. 모든 프로그램은 최대 3문항이며 후보 결과에 따라 동적으로 바뀌지 않는다. React는
Spring이 반환한 다음 질문만 표시하며, 정책 결과를 보고 질문을 추가하거나 교체하지 않는다.

### 5-2. 반환 상태

| 상태 | 의미 | UI 표현 |
|---|---|---|
| `RELATED` | 의미상 관련되지만 자격 미판정 | 관련 정책 |
| `POTENTIALLY_ELIGIBLE` | 확인된 조건은 통과 | 조건상 후보 |
| `NEEDS_CONFIRMATION` | 필수 조건 일부 미입력/모호 | 추가 확인 필요 |
| `INELIGIBLE` | 승인 규칙의 hard condition 불충족 | 기본 목록 제외, 이유 확인 가능 |
| `EXPIRED` | 신청 기간 종료/원문 만료 | 이력·기존 계획에서만 만료 표시 |

MVP는 `ELIGIBLE`이라는 확정 표현을 사용하지 않는다. 실제 기관 심사와 예외 조항이 있을 수
있기 때문이다.

상태 우선순위는 `EXPIRED > INELIGIBLE > NEEDS_CONFIRMATION > POTENTIALLY_ELIGIBLE > RELATED`다.
단, `POTENTIALLY_ELIGIBLE`은 정책이 정의한 필수 조건을 모두 입력했고 승인된 hard condition을
전부 통과했고 P0에서 판정하지 않는 공식 심사 조건이 없는 경우에만 쓴다. 조건이 하나라도 누락·모호하거나
소득·자산·원가구 등 공식 심사 조건이 남으면 다른 불충족 조건이 없는 한
`NEEDS_CONFIRMATION`이다. `INELIGIBLE`은 확인된 hard condition 위반이 하나라도 있으면 우선한다.
일반 검색은 만료 버전을 제외하므로 `EXPIRED`는 과거 결과 조회와 기존 PlanVersion에 연결된
정책 카드에서만 도달한다.

## 6. 런타임 검색 알고리즘

1. `APPROVED`이고 유효기간 안인 정책만 선택한다.
2. `supportGoal`을 받고, 목적별 고정 프로그램으로 추가 질문을 최대 3개 받는다.
3. `supportGoal`을 `housingGoals`·`supportTypes`에 one-to-many 매핑하고 명백한 hard mismatch를 제거한다.
4. 질문 profile의 KURE embedding으로 남은 청크를 cosine 순위화한다.
5. 정책별 최고 청크와 보조 청크를 묶고 중복 정책을 합친다.
6. 승인된 규칙으로 eligibility state와 이유를 계산한다.
7. Top 3 정책에 근거 locator와 공식 URL을 붙인다.

벡터 Top-K 후 자격 필터만 적용하면 관련하지만 신청할 수 없는 정책이 상단을 점유할 수 있다.
따라서 hard filter를 먼저 또는 vector query의 WHERE 조건으로 함께 적용한다.

개념 SQL은 다음과 같다. 한 정책에 유효한 승인 버전이 겹치면 `valid_from`, `approved_at`,
`version` 내림차순으로 정확히 하나만 선택한다. 실제 SQL에서는 동일 우선순위가 생기지 않도록
unique/ exclusion 제약을 함께 둔다.

```sql
WITH current_versions AS (
  SELECT DISTINCT ON (v.policy_id) v.id, v.policy_id
  FROM policy_versions v
  JOIN policies p ON p.id = v.policy_id
  JOIN policy_snapshot_versions sv ON sv.version_id = v.id
  WHERE p.lifecycle_status = 'ACTIVE'
    AND v.review_status = 'APPROVED'
    AND sv.snapshot_id = :active_snapshot_id
    AND v.valid_from <= :as_of_date
    AND (v.valid_to IS NULL OR v.valid_to >= :as_of_date)
  ORDER BY v.policy_id, v.valid_from DESC, v.approved_at DESC, v.version DESC
)
SELECT c.policy_version_id,
       1 - (c.embedding <=> :query_embedding) AS similarity
FROM current_versions cv
JOIN policy_metadata m ON m.version_id = cv.id
JOIN policy_chunks c ON c.policy_version_id = cv.id
WHERE (:region = ANY(m.region_codes) OR m.is_nationwide)
  AND m.housing_goals && :housing_goals
  AND c.index_snapshot_id = :active_snapshot_id
ORDER BY c.embedding <=> :query_embedding
LIMIT :candidate_limit;
```

정확한 index 유형과 operator class는 작은 코퍼스의 recall 측정 후 결정한다. 100~300개 청크에서
근거 없이 ANN index부터 추가하지 않는다.

## 7. 질문 캐시와 런타임 KURE 선택

### 7-1. P0 권장: `supportGoal` query profile

8개 `supportGoal`별 query text와 KURE embedding을 미리 저장한다. 사용자는 목적만 고르고,
목적별 고정 질문 프로그램을 최대 3개 순차적으로 답한다. React는 `supportGoal`과 현재 답 전체를
매 요청 보낸다. Spring은 영속 `PolicySearchSession`이나 DB 세션 없이 허용 답·다음 질문·최종 검색을
결정한다. 허용되지 않은 필드·enum은 거부한다. retrieval run에는 random run ID, `supportGoal`,
질문 프로그램 version, index version, 결과 policy ID만 남기고 개인 조건은 남기지 않는다.

이는 자유대화 AI가 아니다. UI와 문서에서 `주거정책 탐색`이라고 표시한다.

### 7-2. Modal과 정책 카드

대시보드 오른쪽 예정지출 카드 아래의 CTA가 큰 중앙 Modal을 연다. 별도 `/policies` route는 만들지
않고, 기존 Odyssey 기본정보를 다시 묻지 않는다. Modal의 흐름은 `supportGoal 선택 → 최대 3개 추가
질문 → Top 3 → 같은 Modal 상세`다.

- Top 3 기본 카드: 정책명, 한 줄 설명, 사전 작성한 `내 계획과의 연결`, `자세히 보기`, `공식 페이지`
- 결과 상단 공통 문구: `추천 결과는 최종 신청자격을 의미하지 않습니다.`
- 카드에서 제외: 자격상태 배지, `추가 확인 N개`, `왜 추천됐는지` 설명
- 같은 Modal 상세: 지원 내용, 확인된 조건, 추가 확인 조건, 신청기간, 기준일, 공식출처
- `GRANT`, `INTEREST_SUPPORT`, `MORTGAGE`, `GUARANTEE`의 계획 연결 문구는 승인 metadata의 고정
  텍스트이며, 정책 효과 금액을 계산하거나 PlanVersion을 바꾸지 않는다.
- loading: Modal 내부 skeleton. 실패: `정책 정보를 불러오지 못했습니다.`와 재시도. 0건:
  `현재 조건에서 이 유형의 추천 정책을 찾지 못했어요.`와 목적 선택 첫 화면으로 돌아가는
  `다른 주거지원 보기`. 어느 경우에도 기존 계획·대시보드는 유지한다.

React와 Spring 사이에는 Odyssey 정책검색 Public API를 추가한다. P0는 `supportGoal`과 answers를
요청에 담고, Spring이 다음 질문 또는 Top 3/0건 결과를 반환하는 stateless 계약이다. endpoint 경로,
HTTP 상태와 response enum 이름은 OpenAPI 계약 단계에서 확정한다. 온통청년 `getPlcy`는 이 런타임
API가 아니라 오프라인 수집 원천이며, React는 온통청년 원본 DTO를 받지 않는다.

### 7-3. 자유입력 질문

자유입력마다 새로운 semantic embedding을 얻으려면 런타임 모델 또는 외부 embedding API가 필요하다.
이는 P0 범위 밖이다. P0 완료 뒤 P1에서 런타임·개인정보·모델·레이턴시를 별도 결정한다.

## 8. 정책 카드 생성

정책 카드는 생성형 답변이 아니라 승인된 데이터 조립 결과다.

```text
[Top 3 기본 카드]
정책명 · 한 줄 설명 · 사전 작성한 내 계획과의 연결 · 자세히 보기 · 공식 페이지

[같은 Modal 상세]
지원 내용 · 확인된 조건 · 추가 확인 조건 · 신청기간 · 기준일 · 공식출처
```

원문의 금액·비율·기간을 요약에 표시할 때는 승인 metadata와 대조한다. LLM의 자유 생성 숫자를
표시하지 않는다. `GRANT`, `INTEREST_SUPPORT`, `MORTGAGE`, `GUARANTEE`처럼 비용 부담과 관련된
승인 지원 유형에만 `계획과의 관계`를 표시하고, `INFORMATION` 정책에는 표시하지 않는다. 이 템플릿은
정책 metadata만 사용하므로 개인 금융 데이터·검색 조건을 LLM에 보내지 않으며 PlanVersion이나
재계획 입력을 변경하지 않는다.

## 9. 실패와 fallback

| 실패 | 동작 |
|---|---|
| 정책 원천/API 장애 | 마지막 승인 snapshot 유지 |
| 외부 LLM 장애 | 수집 후보를 만들지 않고 기존 승인 데이터 유지 |
| schema 검증 실패 | 후보 반려, 사용자 미노출 |
| vector 검색 실패 | metadata-only 결과와 장애 배지 |
| 유효하지 않은 `supportGoal` | 400 입력 오류, 목적 선택 유지 |
| 필수 개인 조건 누락 | `NEEDS_CONFIRMATION`, 추가 질문 |
| 정책 만료 | 검색 기본 제외, 기존 plan snapshot은 보존 |

정책 실패는 계획 계산, PlanVersion 저장, dashboard 조회를 실패시키지 않는다.

P0의 metadata-only fallback은 hard filter 통과 정책을 `displayPriority DESC, lastVerifiedAt DESC,
policyId ASC`로 정렬하고 `RANKING_DEGRADED` 배지를 붙인다. 관련성 순위 저하와 자격 판정은
분리한다. 승인 규칙과 동일 입력으로 eligibility를 계산하므로 필수 조건을 모두 통과했다면
`POTENTIALLY_ELIGIBLE`도 유지한다. keyword fallback은 P0 합격 근거로 사용하지 않으며 실험
기능으로 표시한다. fallback에도 공식 출처 포함률 100%, false-positive eligibility 0건을
적용하고, Recall@3/MRR은 정상 hybrid 경로와 별도로 보고한다.

## 10. 보안·감사·운영

- 외부 LLM adapter는 public-policy DTO만 받도록 타입 경계를 둔다.
- 요청/응답 로그에는 원문 ID와 hash만 남기고 API key와 본문 전체 로그를 기본 금지한다.
- seed manifest에 KURE 모델 ID·revision·dimension·chunker version을 기록한다.
- 정책 카드 결과에 index snapshot version과 source version을 남긴다.
- 심사 운영 기간에는 정책 snapshot을 read-only로 고정한다.
- 정책 원문 라이선스와 재배포 가능 범위를 원천별로 확인한다.
- 수집기는 승인한 공식 host allowlist만 접근하고 redirect마다 DNS/IP를 다시 검증한다. loopback,
  link-local, RFC1918·metadata endpoint와 비허용 scheme/port를 차단한다.
- MIME allowlist, 다운로드·압축 해제·페이지·파싱 시간 상한을 두고 첨부 parser는 network 없는
  격리 프로세스에서 실행한다.
- 원문과 첨부의 명령문은 모두 비신뢰 데이터로 delimiter 안에 넣는다. 외부 LLM은 원문 지시를
  따르지 않고 고정 schema만 반환하며, tool·URL fetch 권한을 갖지 않는다.

승인과 index 배포는 두 단계로 분리한다. 수집 run ID와 `(sourceId, sourceContentHash)`,
`(policyId, version)`에는 unique 제약을 둔다. 승인된 version의 청크·embedding·manifest를 새
`BUILDING` snapshot과 `policy_snapshot_versions`에 멱등 upsert하고 전체 검증 후 한 DB
transaction에서 기존 `ACTIVE`를
`SUPERSEDED`, 새 snapshot을 `ACTIVE`로 전환한다. 실패한 `BUILDING` snapshot은 검색에서 보이지
않으며 재시도는 같은 run ID를 사용한다. 동시에 두 snapshot을 활성화하려는 요청은 조건부
update 또는 advisory lock으로 하나만 성공시킨다.

## 11. 평가 설계

- 정상 질문 20개
- 표현이 다른 동의 질문 10개
- 월세/전세/매매 near-miss 10개
- 나이·지역·혼인 조건 하나가 어긋나는 질문 10개
- 만료 정책과 중복 정책 사례
- 목적별 질문 3회 초과, 유효하지 않은 `supportGoal`, 이미 답한 필드 재질문 사례

`metadata only`, `KURE vector only`, `metadata + KURE`를 비교한다. 지표는 Recall@3, MRR,
false-positive eligibility, 근거 포함률, p95 latency다. 검색 점수가 좋아도 false eligibility가
1건이라도 있으면 자격 판정 합격으로 보지 않는다.

## 12. 구현 전 승인 게이트

1. 실제 정책 원천과 승인할 정책 목록
2. pgvector extension과 운영 PostgreSQL image
3. 외부 LLM provider·model·API key 보관·비용·보존 정책
4. 정책검색 Public API의 endpoint 경로·HTTP 상태·request/response enum

P0는 `supportGoal` 선택·고정 추가 질문 3개·Modal Top 3·일회성 개인 조건·정보 제공 카드로 확정한다.
런타임 자유입력, 영구 정책 프로필, calculable what-if는 P0 완료 뒤 P1에서 결정한다.
정책 snapshot과 PlanVersion 연결도 P1의 calculable what-if 검토 전에는 만들지 않는다.

이 항목은 `docs/미확정-설계.md`에서 결정한 뒤 구현한다.
