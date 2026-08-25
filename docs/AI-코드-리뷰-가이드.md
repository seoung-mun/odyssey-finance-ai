# 오디세이 AI 코드 리뷰 가이드

기준일: 2026-08-25

대상 브랜치: `feature/core-develop`

대상: AI 에이전트가 전체 코드와 서비스 경계를 검수할 때 적용하는 실행 가이드

## 1. 리뷰 목적

이 프로젝트의 상당 부분은 AI가 생성했으므로 "빌드와 테스트가 통과한다"를 리뷰 완료로
보지 않는다. 다음 네 가지를 에이전트가 코드와 실행 결과로 확인하는 것이 목적이다.

1. 기획·OpenAPI·DB 계약과 실제 구현이 같은가.
2. 금융 숫자, 인증, 소유권과 트랜잭션 경계가 우회되지 않는가.
3. mock 기반 테스트가 실제 서비스 경계의 실패를 숨기지 않는가.
4. 불필요한 추상화와 복제 코드 없이 다른 개발자가 안전하게 변경할 수 있는가.

전체 리뷰를 맡은 에이전트는 특정 서비스만 보고 끝내지 않고 아래 순서대로 흐름을 검토한다.
주석과 문서는 탐색을 돕는 설명일 뿐이며, 코드와 테스트가 실제 근거다.

## 2. 리뷰 시작 전 기준선

### 2.1 먼저 읽을 문서

1. 루트와 대상 서비스의 `AGENTS.md`
2. `docs/기획서.md`
3. `docs/통합-서비스-설계.md`
4. `API/openapi-public.yaml`, `API/openapi-internal.yaml`
5. `sql/01_schema.sql`, `sql/02_integrity.sql`, 후속 migration
6. `docs/evals/*.md`
7. `docs/개발-로드맵.md`
8. 실제 코드, 테스트와 `git diff`

`TODO.md`는 FastAPI 파생 작업 중심이고 실제 구현보다 오래된 항목이 있으므로 단독 기준으로
사용하지 않는다.

### 2.2 기준선 명령

```bash
git status --short --branch
git log --graph --decorate --oneline -30
git diff --check

cd analysis-api
uv run ruff check .
LOG_LEVEL=CRITICAL uv run python -m unittest discover -s tests -v

cd ../core-api
./gradlew --no-daemon spotlessCheck check javadoc

cd ../web
npx tsc --noEmit
npm run lint
npm test -- --run
npm run build
npm run test:e2e
```

기존 실패가 있으면 먼저 명령, 환경, 오류와 마지막 정상 커밋을 기록한다. 리뷰 중 발견한
문제와 무관한 실패를 함께 고치지 않는다.

## 3. 전체 리뷰 순서

리뷰는 아래 순서를 유지한다. 뒤 단계는 앞 단계의 계약을 소비하므로 화면부터 보면 잘못된
숫자나 권한 경계를 놓치기 쉽다.

### 3.1 계약과 데이터 모델

먼저 OpenAPI operation, DTO, Entity, SQL column을 한 줄로 연결한다.

```text
OpenAPI field
  → React runtime validator
  → Spring request/response DTO
  → Service command/query
  → Entity·Repository
  → SQL type·CHECK·FK·UNIQUE
  → FastAPI Pydantic model
```

확인할 것:

- camelCase JSON과 Java/Python 필드가 누락 없이 대응하는가.
- 금액은 원 단위 정수이고 DB에서 필요한 정밀도를 보존하는가.
- `required_reduction_rate NUMERIC(23,4)`를 임의로 더 좁히지 않았는가.
- nullable, enum, 기본값과 최대·최소 범위가 세 서비스에서 같은가.
- request ID, `maxRetry`, 설명 상태 enum이 문서와 구현에서 같은가.
- 공개 API operation 중 Spring Controller가 없는 항목이 있는가.
- migration이 새 DB와 기존 DB 모두에서 적용 가능한가.

중단 조건: DB/API 계약이 실제 코드와 다르지만 어느 쪽이 기준인지 확정할 수 없으면 구현을
고치지 말고 쟁점을 보고한다.

### 3.2 FastAPI 금융 계산

시작 파일:

- `analysis-api/app/models.py`
- `analysis-api/app/main.py`
- `analysis-api/engine/planning.py`
- `analysis-api/tests/test_planning.py`
- `analysis-api/tests/test_internal_api.py`

확인할 것:

- `app/`이 요청 검증과 조합만 하고 계산은 `engine/`의 순수 함수에 남아 있는가.
- bool, float와 숫자 문자열이 정수 필드로 묵시 변환되지 않는가.
- 모든 금액 계산이 정수·Decimal 경계를 지키고 float 반올림에 의존하지 않는가.
- seed가 필수이고 동일 계획의 옵션이 동일 난수 경로를 공유하는가.
- 10,000경로와 horizon 경계가 요청으로 우회되지 않는가.
- 소득 0, 지출>소득, 1개월, 부족한 이력과 정수 경계가 명시적으로 종료되는가.
- 옵션, coverage와 percentile band가 같은 확정 추천 금액에서 파생되는가.
- Spring이나 Web에서 같은 금융 숫자를 다시 계산하지 않는가.

AI 생성 코드에서 특히 볼 것:

- 테스트 fixture에 맞춘 상수나 분기
- 여러 함수에 복제된 반올림·범위 검증
- `except Exception`으로 계산 오류를 정상값처럼 반환하는 코드
- 이름만 다른 동일 계산 함수와 사용되지 않는 확장용 추상화

### 3.3 Spring 인증·보안

시작 파일:

- `core-api/src/main/java/com/dacon/core/config/SecurityConfig.java`
- `core-api/src/main/java/com/dacon/core/auth/`
- `core-api/src/main/java/com/dacon/core/error/`

확인할 것:

- Google token의 signature, issuer, audience, expiry와 `email_verified`를 모두 검증하는가.
- 동일 계정은 이메일이 아니라 Google `sub`로 식별하는가.
- access token은 응답, refresh token은 제한된 HttpOnly cookie로 전달되는가.
- refresh 원문을 DB에 저장하지 않고 회전·폐기·동시 사용을 막는가.
- JWT key 길이와 알고리즘, 로그의 token·개인정보 노출을 차단하는가.
- Controller가 JWT subject를 신뢰 가능한 사용자 ID로 변환하는 경로가 하나인가.
- 401, 403, 404를 숨김 정책과 공개 오류 계약에 맞게 구분하는가.
- request ID가 입력 검증, 로그와 오류 응답에 같은 값으로 이어지는가.

실제 Google key가 없으면 cryptographic verifier 단위 테스트와 잘못된 issuer/audience/expiry
fixture까지 검토하고, 운영 로그인을 검증했다고 표시하지 않는다.

### 3.4 Spring 서비스·Repository·트랜잭션

리뷰 순서:

```text
Controller → DTO → Service interface → Service implementation
  → Repository → Entity state transition → DB constraint
```

확인할 것:

- Controller가 Repository, `EntityManager`나 JDBC를 직접 호출하지 않는가.
- 통신 경계가 DTO로 구조화되고 Entity를 API에 그대로 노출하지 않는가.
- 한 구현만 있는 인터페이스는 외부 adapter port 등 분명한 경계 이유가 있는가.
- Repository는 `JpaRepository`를 확장하고 쿼리 메서드 이름이 실제 조건을 정확히 표현하는가.
- 복잡한 동적 조회가 없는데 QueryDSL이나 범용 query abstraction을 추가하지 않았는가.
- 조회와 변경 메서드의 `@Transactional(readOnly = true)` 및 잠금 위치가 적절한가.
- 외부 FastAPI·Redis 호출이 DB 트랜잭션을 불필요하게 오래 잡지 않는가.
- 소유권 검증이 조회 후 메모리 비교가 아니라 쿼리·서비스 경계에서 일관되게 적용되는가.
- unique 충돌, 동시 선택, 중복 import와 seed 재실행이 멱등하게 종료되는가.
- 오류를 로그만 남기고 성공 응답으로 삼키는 경로가 없는가.

도메인별 추가 확인:

- `user`: 빈 계정에서만 sample seed가 한 번 생성되는가.
- `goal`: 다른 사용자의 목표·예정지출을 읽거나 변경할 수 없는가.
- `transaction`: `source_transaction_id`와 외부 ID의 중복 방지가 DB까지 이어지는가.
- `plan`: 새 계획 저장이 기존 `ACTIVE`를 자동으로 폐기하지 않는가.
- `plan`: 사용자가 선택할 때만 기존 계획이 `SUPERSEDED`로 전이되는가.

### 3.5 Redis 비동기 설명과 FastAPI LLM

시작 파일:

- `core-api/src/main/java/com/dacon/core/explanation/`
- `analysis-api/app/explanation.py`
- `analysis-api/tests/test_explanation.py`
- `docs/evals/redis-explanations.md`

확인할 것:

- Redis payload가 식별자만 담고 확정 데이터는 Spring이 사용자 소유권과 함께 다시 읽는가.
- publish 실패가 계획 계산·저장을 rollback하지 않고 설명만 `FALLBACK`으로 바꾸는가.
- consumer group 생성, ACK, pending reclaim과 중복 delivery가 멱등한가.
- 상태가 `PENDING → PROCESSING → READY | FALLBACK | FAILED`로만 전이되는가.
- FastAPI는 Redis와 DB에 연결하지 않는가.
- LLM이 계산 API나 엔진을 호출해 새 숫자를 만들 수 없는가.
- 출력의 ASCII, Unicode와 한국어 수사 숫자를 원본과 대조하는가.
- 최대 두 번 교정하고 전체 15초 안에 종료하는가.
- header/body trickle, malformed JSON, 과대 응답과 모델 단절이 숫자 없는 fallback인가.
- 활성 추론이 하나이고 lock 대기 요청도 전체 deadline 안에 끝나는가.

mock 테스트만 보지 말고 실제 Redis와 raw HTTP/Ollama 실패를 최소 한 번 재현한다.

### 3.6 React 경계와 사용자 실패 상태

시작 파일:

- `web/src/api.ts`
- `web/src/types.ts`
- `web/src/App.tsx`
- 각 Page와 대응 테스트
- `web/e2e/core-flow.spec.ts`

확인할 것:

- `strict`를 우회하는 `any`, `var`, 무근거 `as`와 `!`가 없는가.
- API 응답을 trust boundary에서 runtime validation한 뒤 사용하고 있는가.
- API 계약 타입을 여러 화면에서 손으로 중복 정의하지 않았는가.
- 파생 값을 state에 복사하거나 effect로 내부 상태를 맞추지 않는가.
- effect가 네트워크·브라우저 API 같은 외부 시스템 동기화에만 쓰이는가.
- props, state와 API 응답 객체를 직접 변경하지 않는가.
- hook lint 경고를 disable 주석으로 숨기지 않는가.
- 저장 중 중복 제출과 늦게 도착한 이전 응답 덮어쓰기를 막는가.
- 401은 로그인, 403·404는 각각의 화면, 409는 재조회, 500은 request ID와 재시도인가.
- 네트워크·서버 validation 실패 후 입력값과 focus가 유지되는가.
- keyboard, label, focus-visible, reduced motion과 모바일 동작이 있는가.
- 숫자와 달성 가능성을 Web이 다시 계산하지 않고 API 값을 표시만 하는가.

성능 최적화는 profiler나 Web Vitals 근거가 있을 때만 승인한다. 측정 없이 추가된 memo,
callback cache와 전역 상태 라이브러리는 제거 후보로 본다.

### 3.7 Docker·배포 경계

확인할 것:

- `docker compose config`가 필수 비밀값을 넣었을 때 통과하는가.
- PostgreSQL migration 순서와 기존 volume 적용 절차가 분리되어 있는가.
- PostgreSQL, Redis, FastAPI와 Ollama 포트가 외부에 공개되지 않는가.
- Redis password와 Spring 환경 변수명이 실제 설정 키와 일치하는가.
- Ollama 모델 pull·health가 실제 모델 상주·준비 완료와 혼동되지 않는가.
- Caddy와 nginx 역할이 중복되지 않는가.
- Vercel HTTPS → Caddy HTTPS → Spring 흐름에서 mixed content와 CORS 오류가 없는가.
- 이미지에 `.env`, key, build cache와 벤치마크용 대형 의존성이 들어가지 않는가.
- Docker Desktop 수치를 `t4g.large` 성능 증명으로 표현하지 않는가.

## 4. AI 생성 코드 집중 점검표

다음 항목은 검색 결과만으로 판정하지 말고 모든 호출자를 추적한다.

| 냄새 | 확인 방법 |
|---|---|
| 존재하지 않는 계약·필드 | OpenAPI·SQL과 생성자/serializer를 양방향 대조 |
| 테스트만 통과하는 mock | 실제 adapter 경계를 patch하는지, 최소 한 번 실제 실패 재현 |
| 과도한 추상화 | 구현 하나뿐인 interface/factory와 미래용 generic의 현재 소비자 확인 |
| 계층 우회 | Controller의 Repository/JDBC 접근, Web의 금융 계산 검색 |
| 오류 삼킴 | broad catch, 빈 catch, 로그 후 성공 반환과 기본값 반환 검색 |
| 트랜잭션 오용 | `@Transactional` 안의 HTTP·Redis·긴 loop와 lock 확인 |
| 타입 위장 | Java raw type/`var`, TypeScript `any`/단언, Python coercion 확인 |
| 동시성 착각 | check-then-act와 애플리케이션 lock만 있고 DB unique/lock이 없는 경로 재현 |
| 보안 기본값 | 기본 비밀번호·JWT key, permissive CORS와 운영 mock 로그인 검색 |
| 죽은 코드 | 호출자 0건인 endpoint adapter, Store, util, DTO와 config 확인 |
| 복제 구현 | 같은 validation·mapping·rounding이 서비스별로 갈라졌는지 확인 |
| 주석 불일치 | 코드와 다른 트랜잭션·예외·상태 설명을 high로 보고 |

추천 검색 시작점:

```bash
rg -n '\bvar\b|catch \(Exception|catch \(RuntimeException|TODO|FIXME' core-api/src
rg -n '\bany\b|\bas\s+[A-Z]|eslint-disable|ts-ignore' web/src
rg -n 'except Exception|float\(|requests\.|redis|sqlalchemy' analysis-api/app analysis-api/engine
rg -n 'JdbcTemplate|EntityManager|Repository' core-api/src/main/java/com/dacon/core/*/*Controller.java
```

## 5. 시그니처와 Javadoc 리뷰 기준

Javadoc은 이름을 한국어로 반복하는 장식이 아니라 호출 계약이어야 한다.

- 타입 주석은 책임과 다른 계층과의 경계를 설명한다.
- Controller는 인증 주체, 입력과 HTTP 결과를 설명한다.
- Service는 소유권 검증, 상태 변경, 트랜잭션과 외부 호출 효과를 설명한다.
- Repository는 검색 조건, 잠금과 없을 때의 반환을 설명한다.
- Entity mutation은 허용 상태와 전이 후 불변조건을 설명한다.
- DTO와 record는 어느 API 경계에서 어떤 단위로 사용되는지 설명한다.
- `@param`, `@return`, `@throws`는 실제 의미가 있을 때 구체적으로 작성한다.
- 단순 getter도 단위, nullable과 snapshot 여부처럼 이름만으로 모르는 내용을 기록한다.
- 구현과 다른 설명, "처리한다", "반환한다"뿐인 주석과 추측한 예외는 수정 대상이다.

주석 변경 후 반드시 다음을 실행한다.

```bash
cd core-api
./gradlew --no-daemon spotlessCheck check javadoc
```

## 6. 발견 사항 보고 형식

각 문제는 아래 형식으로 한 건씩 기록한다.

```text
severity: blocker | high | medium | low
evidence: 파일:줄과 실제 계약·코드
reproduce: 실패를 재현하는 최소 명령 또는 요청
impact: 사용자·데이터·운영에 미치는 영향
owner: core | analysis | web | infra | docs
minimum_fix: 계약을 바꾸지 않는 가장 작은 수정
```

판정 기준:

- `blocker`: 금액·데이터 손상, 인증 우회, secret 노출, 배포 불가
- `high`: 핵심 흐름 실패, 트랜잭션·멱등성 위반, timeout 무시, 계약 불일치
- `medium`: 특정 경계 입력 실패, 운영 진단 곤란, 유지보수 위험
- `low`: 동작은 맞지만 명명·주석·중복 등 품질 개선 필요

"코드가 이상해 보인다"는 발견이 아니다. 파일과 줄, 재현 방법과 영향을 함께 제시한다.

## 7. 수정·재검수·승인 순서

1. baseline 결과와 전체 diff를 보존한다.
2. 계약·데이터, 적대적 서비스, E2E 관점으로 리뷰한다.
3. blocker/high를 같은 서비스 빌더에 최소 수정으로 재배정한다.
4. 같은 완료 조건의 수정은 빌더·QA 합계 최대 두 번 수행한다.
5. 독립 QA가 원래 재현 절차와 전체 회귀를 다시 실행한다.
6. blocker/high가 없고 서비스별 필수 명령이 모두 통과해야 승인한다.
7. 교차 서비스 계약, PR, release와 배포 전에는 전체 스택과 전체 diff를 다시 검토한다.

리뷰 승인과 배포 승인은 별개다. 로컬 Docker 통합 통과는 AWS Graviton 성능과 운영 Google
로그인 성공을 증명하지 않는다.
