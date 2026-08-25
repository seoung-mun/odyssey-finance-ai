# Redis 설명 queue 평가

## 합격 조건

- 계산 API는 동기이고 Redis Stream은 설명 생성에만 사용한다. 캐시는 없다.
- job은 `planVersionId`, `inputHash`, `promptVersion`만 포함하며 금융 원문을 넣지 않는다.
- enqueue 장애·timeout은 계산과 계획을 유지하고 설명만 `FALLBACK`으로 만든다.
- worker reclaim과 멱등 상태 전이로 유실·중복 완료·무한 대기가 없다.
- 전체 15초, 최초 1회+교정 2회 이내이며 허용되지 않은 숫자가 최종 text에 남지 않는다.

## 적대 테스트

- Redis 단절, enqueue timeout, worker 종료 후 reclaim, 중복 delivery.
- Ollama timeout·malformed 응답·한국어 외 혼입·숫자 환각·3번째 실패.
- fallback 성공률 100%, 숫자 불일치 잔존율 0%, 계산 JSON 불변.

