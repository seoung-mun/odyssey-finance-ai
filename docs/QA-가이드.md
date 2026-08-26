# QA 가이드

이 문서는 Odyssey Finance의 QA 실행 기준이다. QA 에이전트는 코드를 보기 전에 이 문서와
`.agents/skills/adversarial-qa/SKILL.md`를 전부 읽고 가설 대장을 먼저 만든다.

## 1. 합격의 의미

테스트가 초록색이라는 사실만으로 기능을 합격시키지 않는다. 합격은 사용자가 실제로 거치는
브라우저와 HTTP, 서비스 간 네트워크, PostgreSQL·Redis, 계산 엔진이 같은 실행에서 연결되고
계약·상태·금액이 끝까지 보존됐다는 증거가 있을 때만 가능하다.

- 문서나 빌더 보고가 아니라 QA가 직접 실행한 출력으로 판정한다.
- 정상 경로뿐 아니라 소유권, 중복·동시성, 경계값, timeout·부분 실패를 공격한다.
- 금융 숫자는 API 응답만 보지 않고 DB 원장과 계산 엔진 입력·출력을 대조한다.
- 실행할 수 없는 경계는 통과가 아니라 `미검증`이다.
- 명세가 없거나 서로 충돌하면 결함으로 추측하지 않고 `판정 불가`로 메인에 올린다.

## 2. 테스트 증거 분류

모든 테스트와 QA 결과는 아래 셋 중 하나로 표시한다.

| 분류 | 정의 | 합격 근거 |
|---|---|---|
| `REAL` | 실제 제품 프로세스와 제품 의존성을 기동해 실제 프로토콜로 통신 | 해당 경계의 통합·E2E 근거 가능 |
| `CONTRACT_STUB` | 특정 오류·희귀 응답을 재현하려고 메인이 명시 배정한 프로토콜 대역 | 요청·응답 처리의 보조 근거만 가능 |
| `MOCK` | 함수·Repository·HTTP·브라우저 응답 등 대상 경계를 메모리 안에서 대체 | 단위 로직의 보조 근거만 가능 |

다음은 이름이 통합 테스트여도 기본적으로 `MOCK` 또는 `CONTRACT_STUB`이다.

- Mockito, `mock`, `patch`, `monkeypatch`, fake/in-memory Repository
- H2·SQLite 등 실제 운영 PostgreSQL을 대체한 DB
- FastAPI `TestClient`로 네트워크와 실제 Uvicorn을 건너뛴 테스트
- Spring `MockMvc`만 사용해 실제 포트와 클라이언트 전송을 건너뛴 테스트
- 고정 응답 `HttpServer`, WireMock, 가짜 Redis/Ollama
- Playwright의 `page.route().fulfill()` 또는 API 응답 가로채기

대상 경계를 대체한 테스트가 통과해도 그 경계는 `미검증`이다. 단위 테스트를 삭제할 필요는
없지만 실제 통합 결과와 섞어 보고하면 안 된다.

## 3. 목업 승인 경계

`CONTRACT_STUB`과 `MOCK`을 새로 작성하거나 QA 합격 증거로 인용하는 작업은 메인만 별도 작업
패킷으로 배정한다. QA와 빌더는 실행하기 편하다는 이유로 실제 경계를 임의 대체하지 않는다.

메인이 목업 레인을 배정할 때는 작업 패킷에 다음을 적는다.

- 대체하는 실제 경계
- 실제 경계로 재현하기 어려운 이유
- 목업이 증명할 수 있는 동작과 증명하지 못하는 동작
- 별도로 반드시 실행할 대응 `REAL` 시나리오

목업 결과 보고에는 아래 표를 반드시 붙인다.

| 분류 | 대체 경계 | 사용 이유 | 증명 가능 | 증명 불가 | 대응 REAL 결과 |
|---|---|---|---|---|---|

## 4. REAL 실행 조건

교차 서비스 QA는 서로 격리된 포트와 컨테이너 이름을 사용해 다음 구성요소를 실제로 기동한다.

- PostgreSQL: 운영과 같은 major version, 실제 migration과 native SQL 적용
- Redis: 실제 서버와 제품 serialization·consumer·retry 경로 사용
- Analysis: Uvicorn으로 FastAPI를 실제 포트에 기동
- Core: Spring Boot를 실제 포트에 기동하고 실제 `HttpClient` 사용
- Web: 실제 dev/preview 서버와 route interception 없는 브라우저 사용

보고에는 각 프로세스의 URL, 포트, PID 또는 컨테이너 이름, health 결과를 남긴다. 일반 사용자
상태는 공개 API로 만든다. DB 함수·제약·격리 수준 자체를 검증할 때만 SQL을 직접 실행하고,
그 경우 사용자 시나리오와 DB 직접 검증을 별도 레인으로 표시한다.

## 5. 필수 수직 시나리오

최소 한 번은 다음 흐름이 한 실행에서 끊김 없이 이어져야 한다.

1. 사용자 A 인증과 온보딩
2. 실제 거래 입력과 조회
3. 목표 생성·수정
4. Core가 실제 Analysis에 계산을 요청하고 옵션·band를 수신
5. 계획과 계산 결과의 트랜잭션 저장
6. 옵션 선택과 대시보드 반영
7. 예정 지출 생성·수정에 따른 재계획
8. 재계획 목록·요청·결정·재시도
9. 사용자 B의 사용자 A 자원 접근 거부
10. 브라우저 새로고침·재로그인 후 동일 상태 확인

환불은 원장과 소비 표본을 따로 대조한다.

- PAYMENT는 소비 표본에 포함한다.
- 연결된 REFUND는 원장 유입과 allocation을 보존하되 소비 표본에서 제외한다.
- 아직 연결할 수 없는 환불은 pending/unmatched 상태와 금액을 잃지 않는다.
- 다대다 allocation 합이 결제·환불 금액을 넘는 요청을 실제 PostgreSQL에서 거부한다.

## 6. 적대적 필수 공격

QA 레인은 배정 범위에 맞는 항목을 실제로 재현한다.

- 0, 음수, `null`, 빈 배열, 원 단위 최대값과 int64 overflow
- 월말·윤년·KST 자정·잘못된 달력 날짜
- 동일 idempotency key 재전송과 응답 유실 뒤 재시도
- 같은 목표·예정 지출·재계획에 대한 동시 수정
- Analysis 연결 거부, 실제 프로세스 종료에 의한 timeout, 401·422·5xx
- Redis 중단·재기동, pending reclaim, 중복 consumer
- 저장 도중 실패했을 때 부분 행·고아 행·이벤트 유실 여부
- 빈 options/bands, 잘못된 숫자, LLM 숫자 변조와 fallback
- 다른 사용자의 ID를 추측한 조회·수정·결정
- 프론트 입력 속성 변경, 직접 fetch, 연타, 뒤로가기, 새로고침

희귀한 잘못된 HTTP 본문을 만들기 위한 서버 대역은 메인이 명시한 `CONTRACT_STUB` 레인에서만
사용한다. 실제 프로세스 종료나 실제 잘못된 입력으로 만들 수 있는 오류는 대역하지 않는다.

## 7. 보안 QA

보안 검사는 로컬 개발 예외와 운영 합격 기준을 구분한다.

### 전송 구간

- 브라우저가 접근하는 운영 URL은 HTTPS만 허용하고 HTTP는 HTTPS로 전환한다.
- OAuth redirect, access/refresh token, 내부 인증 토큰, 금융 거래·계획 데이터가 신뢰 경계를
  넘을 때 평문 HTTP를 허용하지 않는다.
- 로컬 loopback 또는 외부 접근이 차단된 단일 Docker private network의 서비스 간 HTTP는
  개발 예외로 기록할 수 있다. 운영에서도 평문을 유지하려면 private network 격리와 외부
  비노출을 배포 설정으로 증명해야 하며, 그렇지 않으면 TLS 또는 mTLS가 필요하다.

### 저장·주입·로그

- OAuth client secret, JWT secret, DB password, 내부 토큰은 저장소에 커밋하지 않고 환경변수나
  secret store로 주입한다. 예제 값은 실제 값과 명확히 달라야 한다.
- 비밀번호를 직접 저장한다면 강한 password hash를 사용한다. OAuth-only 서비스는 Google
  access/refresh token을 불필요하게 저장하지 않는다.
- 은행 거래 원문과 사용자 재무 데이터의 DB 평문 저장 범위, 백업·접근 통제를 보고한다.
  MVP에서 애플리케이션 레벨 암호화를 유예하면 반드시 잔여 위험과 적용 시점을 기록한다.
- 요청·응답 로그, exception, browser trace, test report에 token·cookie·OAuth code·금융 원문이
  남지 않는지 실제 실패 로그로 확인한다.
- 쿠키는 운영에서 `Secure`, `HttpOnly`, 적절한 `SameSite`, 최소 `Path`를 사용하고 logout과
  rotation에서 폐기된다.

## 8. QA 보고 형식

보고서는 다음 순서를 지킨다.

1. 코드 보기 전에 작성한 가설 대장과 `확인/기각/판정 불가`
2. 기동한 실제 구성요소와 health 증거
3. 실행한 시나리오, 명령, HTTP·DB 결과
4. 결함: `severity/evidence/reproduce/impact/owner`
5. 사용한 모든 `CONTRACT_STUB`·`MOCK` 별도 표
6. 미검사 영역과 이유

실행 출력이 없는 정적 추정은 한 단계 낮춰 표시한다. 결함이 없더라도 시도한 가설과 미검사
영역은 생략하지 않는다. `REAL` 경로가 최초 hop에서 실패하면 이후 단계는 통과가 아니라
각각 `미검증`으로 남긴다.

## 9. 완료와 수정 루프

- blocker/high는 원래 서비스 빌더에게 재배정한다.
- 빌더 수정 뒤 QA는 같은 재현 절차와 전체 회귀를 직접 다시 실행한다.
- 같은 완료 조건의 빌더·QA 수정 루프는 합계 두 번까지만 수행하고 이후에는 메인에 올린다.
- 실제 PostgreSQL, Uvicorn HTTP, Spring HTTP, Redis 또는 브라우저 중 필요한 경계를 실행하지
  못했다면 기능 완료로 커밋하지 않는다.
- 최종 결과는 `docs/QA-결과.md`, 남은 작업은 `TODO.md`, 순서는 `docs/개발-로드맵.md`에 메인만
  반영한다.
