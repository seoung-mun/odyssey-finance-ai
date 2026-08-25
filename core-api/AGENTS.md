# core-api — Spring Boot 규칙

루트 `AGENTS.md`를 상속한다. 공개 API는 `../API/openapi-public.yaml`, FastAPI 계약은
`../API/openapi-internal.yaml`, DB는 `../sql/*.sql`이 기준이다. 스택은 Java 21,
Spring Boot 3.3, JPA, PostgreSQL이다.

## 구현 경계

- Controller: HTTP·인증 변환
- Service: 유스케이스와 트랜잭션
- Repository: 영속성
- Client: FastAPI 호출과 오류 변환

CRUD와 확정 계약을 먼저 구현한다. 한 구현뿐인 인터페이스·factory·범용 추상화는 만들지
않고 요청 DTO와 Entity를 분리한다.

OpenAPI 상태 코드·필드와 SQL 제약을 임의로 바꾸지 않는다. 계획 변경은 기존 행 덮어쓰기
대신 버전과 입력 snapshot으로 재현 가능해야 한다.

## 결정 게이트

다음이 문서에 없으면 구현 전 선택지·권장안·영향을 메인에게 보고한다.

- 트랜잭션 시작·종료와 FastAPI 호출/DB commit 순서
- 멱등성, 중복 요청, 동시 수정과 락
- timeout, 부분 실패, 원격 성공 뒤 DB 실패

LLM 설명 실패를 계산·계획 저장 실패로 확대하거나 숫자를 Spring에서 다시 계산하지 않는다.

## 소유권과 검증

Core 에이전트는 `core-api/`만 수정한다. `API/`, `sql/`, `docs/`, `TODO.md`와 다른 서비스
변경은 메인에게 반환한다.

```bash
./gradlew spotlessCheck
./gradlew check
```

적대적 QA는 중복·동시 요청, rollback, FastAPI timeout·5xx·잘못된 JSON, 인증 누락,
계획 버전 불일치와 orphan 데이터를 확인한다.
