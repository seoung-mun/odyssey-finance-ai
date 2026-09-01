# Odyssey MVP 공수·검증·성능 기준 계획

기준일: 2026-08-30
팀 구성: 개발자 2명, 두 명 모두 Codex 적극 활용
목표: 2026-09-07 심사 URL에서 핵심 수직 흐름이 실제로 동작하는 MVP

## 1. 일정 산정 원칙

이 문서의 공수는 Codex를 코드 초안, 테스트 골격, 반복 변환, 코드 탐색과 리뷰에 적극 사용하는
상태를 이미 포함한다. 따라서 다시 별도의 “AI 생산성 배수”를 곱하지 않는다. Codex가 줄이는 것은
작성·탐색 시간이고, 다음 작업은 여전히 사람의 확인 시간이 필요하다.

- DB/API 계약과 개인정보 경계 결정
- 정책 원문과 AI 추출 metadata의 대조·승인
- 실제 PostgreSQL migration과 pgvector query 확인
- 서비스 간 통합, 브라우저 흐름, 배포 장애 판단
- 심사 문구와 실제 기능의 일치 여부

공수는 낙관 단일값이 아니라 범위로 관리한다. API/DB/pgvector 결정이 하루 이상 늦어지거나 두
사람이 같은 파일을 반복 수정하면 아래 범위는 즉시 무효다.

## 2. P0 예상 공수

| 작업 | 예상 인일 | 주 담당 | 병렬 가능 |
|---|---:|---|---|
| 범위·정책 목록·API/DB 계약 고정 | 0.5~1.0 | 공동 | 낮음 |
| 정책 20~30개 수집·metadata 승인·KURE seed | 1.5~2.0 | A | 높음 |
| pgvector migration·적재·hybrid query | 1.0~1.5 | A | 중간 |
| Core 검색 API·자격 규칙·fallback | 1.5~2.0 | A | 중간 |
| Web 질문 선택·Top 3 카드·출처·오류 상태 | 1.0~1.5 | B | 높음 |
| compose·HTTPS·데모 seed·수직 통합 | 1.5~2.0 | B, 공동 | 낮음 |
| 심사 시나리오 수정·배포 리허설·버퍼 | 1.0~2.0 | 공동 | 낮음 |
| 합계 | **8.0~12.0 인일** |  |  |

두 명이 독립 파일을 병렬 작업하고 계약을 첫날 고정한다는 전제에서 예상 달력 기간은
**5~7일**이다. 이는 P0만의 추정이며 Housing/Market, 자유입력 KURE, 정책 효과 계산, 전체 API
회귀, 부하·장시간 안정성 시험은 포함하지 않는다.

### 역할 분담

- A — 데이터·백엔드: 정책 원천, metadata 승인, embedding artifact, PostgreSQL/pgvector, Core 검색
- B — Web·통합: API client/parser, Dashboard CTA Modal·`supportGoal`·정책 카드, 데모, compose/HTTPS, 심사 흐름
- 공동: 계약 확정, 개인정보 검토, 실제 DB smoke, 최종 배포와 rollback 판단

두 사람은 같은 계약 파일을 동시에 수정하지 않는다. Codex에는 파일 소유 범위와 합격 조건을
작업마다 명시하고, 생성 결과는 담당자가 diff와 실행 결과를 확인한 뒤 통합한다.

## 3. 권장 달력 순서

| 시점 | A | B | 공동 종료 조건 |
|---|---|---|---|
| Day 0 | 정책 목록·DB 초안 | API/UI 계약 초안 | P0와 endpoint/schema 고정 |
| Day 1~2 | 수집·승인·KURE seed·pgvector | fixture 기반 Web 카드·상태 | 같은 fixture ID와 DTO 사용 |
| Day 3 | Core hybrid search·자격 규칙 | 실제 API 연결·fallback UI | Top 3 수직 연결 |
| Day 4 | DB/query 수정 | 데모 seed·브라우저 흐름 | 핵심 smoke gate 통과 |
| Day 5 | compose/배포 지원 | HTTPS·심사 동선 | 배포 리허설 성공 |
| Day 6~7 | 결함 수정·범위 동결 | 결함 수정·발표 확인 | 변경 중지와 최종 URL 확인 |

일정이 밀리면 테스트를 무작정 삭제하지 않고 먼저 자유입력, 정책 수, UI 장식, sLLM 선택 기능을
줄인다. 핵심 수직 흐름이 안정된 다음에만 미뤄 둔 검증을 확장한다.

## 4. MVP 검증 등급

### 4-1. 제출 전 반드시 통과할 최소 gate

1. 변경 모듈 build/typecheck/lint와 핵심 단위 테스트
2. 실제 PostgreSQL에서 migration 적용, extension 확인, seed 적재, metadata+vector query 1회 이상
3. 같은 seed 재적재의 최소 멱등성 확인과 미승인 정책 미노출 확인
4. 기존 금액·계획·시뮬레이션 계산의 표적 회귀
5. 외부 LLM DTO에 개인 식별자·거래·목표금액·소득 필드가 없다는 경계 테스트
6. 정책 검색 장애 시 기존 계획·대시보드가 살아 있는 fallback smoke
7. 실제 HTTPS 브라우저에서 로그인/데모 → 계획 → 정책 질문 → Top 3 카드 → 공식 출처 한 흐름
8. 심사 환경 health와 재기동 후 같은 데모 흐름 확인

pgvector처럼 native SQL·extension을 쓰는 경로는 mock만으로 통과 처리하지 않는다. 다만 MVP에서는
모든 제약과 동시성 조합을 전수 검증하지 않고 위 실제 DB smoke로 범위를 제한한다.

### 4-2. MVP 이후로 미루는 검증

- 공개 API 전체 operation의 상태·소유권·경계값 전수 대조
- DB 제약조건 전체 조합, 동시 승인/적재 경쟁, 장애 주입, backup/restore 전체 시험
- 전체 브라우저·화면 크기·접근성 조합
- 대규모 정책 corpus와 자유입력 KURE
- peak/stress/soak 부하 시험과 장시간 메모리 안정성
- 전체 timeout/retry/네트워크 단절 조합

미룬 항목은 `PASS`가 아니라 `DEFERRED_MVP`로 기록하고 담당자·이유·재개 조건을 남긴다. 금융
숫자 변조, 개인정보 외부 전송, 인증 우회, ACTIVE 계획 자동 변경 가능성이 있는 결함은 일정상
미루는 대상이 아니다.

## 5. MVP 이후 성능 베이스라인

기능과 핵심 smoke가 녹색이 된 뒤 별도 staging에서 다음 순서로 측정한다. 심사 운영 기간인
2026-09-07 11:00~2026-09-11 23:59에는 심사 환경에서 부하 테스트나 인스턴스 변경을 하지 않는다.

### 측정 시나리오

1. 로그인·대시보드 조회
2. 계획 생성과 Analysis simulate
3. `supportGoal` 기반 정책 Top 3 검색
4. 정책 검색 fallback
5. 혼합 사용자 수직 흐름

### 필수 기록

- git commit, 측정 일시, 환경·인스턴스·container image digest
- 정책 snapshot·KURE model revision·DB row/chunk 수
- 요청 수, 동시 사용자, ramp-up, warm-up, 측정 시간
- endpoint별 p50/p95/p99, throughput, error/timeout 비율
- CPU, RSS, container restart, DB connection pool, query p95
- pgvector query plan과 cache hit 여부
- 실패 원인과 제외한 표본

첫 측정값을 `baseline-001`로 고정한다. 이후 최적화마다 가설, 변경 commit, 같은 조건의 전후
수치, 회귀 여부, 채택·원복 결정을 한 줄 ledger로 남긴다. 데이터셋·동시성·인스턴스가 달라진
측정은 같은 baseline의 개선으로 비교하지 않는다.

## 6. 완료 판정

- MVP 완료: P0 기능이 심사 URL에서 동작하고 4-1 최소 gate 증거가 있으며, 미실행 검증이
  `DEFERRED_MVP`로 분리돼 있다.
- 성능 baseline 완료: 동일 환경에서 재실행 가능한 명령과 `baseline-001` 결과가 저장돼 있다.
- 최적화 완료: baseline 대비 개선과 기능 회귀 없음이 동일 조건으로 재현된다.
