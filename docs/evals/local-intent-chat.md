# 로컬 intent 챗봇 검증 계약

## Intent와 응답 경계

허용 intent는 `SAVINGS_RECOMMENDATION`, `SAVINGS_WHAT_IF`, `PLAN_STATUS`,
`SPENDING_SUMMARY`, `REPLAN_GUIDE`, `POLICY_SEARCH`, `HELP`, `UNKNOWN`이다. 로컬 LLM은
이 enum 하나만 반환할 수 있고, 금액·비율·기간은 생성하지 않는다. 적금 추천 숫자는
`SavingsRecommendationService`의 결정론 결과만 응답에 포함한다.

`UNKNOWN` 문구는 다음으로 고정한다.

> 요청을 이해하지 못했습니다. 적금 추천, 계획 현황, 소비 요약처럼 원하는 작업을 말씀해 주세요.

## 저장·장애·동시성

- 입력은 1~500자, session ID는 UUID다.
- Redis에는 사용자 ID, 최근 intent 최대 6개, `productId`, `optionId`, `conditionIds` 최대
  20개만 저장한다. 자유입력 원문은 저장하지 않는다.
- 세션 JSON은 최대 4KiB, TTL은 45분이다. Redis AOF가 켜져 있어도 원문은 기록되지 않는다.
- 같은 session은 5초 Redis lock으로 직렬화하며 lock 경합은 409 `CHAT_SESSION_BUSY`다.
- Redis 연결 장애는 현재 메시지를 결정론적으로 분류하고 HTTP 200
  `sessionMode=STATELESS_FALLBACK`으로 반환한다.
- Ollama가 없거나 timeout·잘못된 enum을 반환하면 결정론적 키워드 분류기로 fallback한다.
- `local` 프로필과 `app.chat-ai-enabled=true`일 때만 Spring AI 구현체가 존재한다. 그 외
  프로필과 kill-switch off에서는 fallback 구현체 하나만 있고 `ChatModel`도 생성하지 않는다.

## 검증 명령

```bash
cd core-api
./gradlew test --tests 'com.dacon.core.chat.*' --no-daemon
./gradlew check --no-daemon
```
