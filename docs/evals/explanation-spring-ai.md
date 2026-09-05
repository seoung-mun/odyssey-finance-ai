# 설명 생성 Spring AI 이전 검증

## 범위

Phase 1은 `analysis-api`의 `/internal/explanations`를 제거하고 Core의
`ExplanationGeneratorPort`가 로컬 Ollama만 호출하도록 옮긴다. OpenRouter와 다른 외부 LLM은
사용하지 않는다.

## 빈 계약

| 실행 조건 | ExplanationGeneratorPort | ChatModel |
| --- | --- | --- |
| `local` + `app.explanation-ai-enabled=true` | Spring AI/Ollama | 1개 |
| `local` + false 또는 미설정 | 즉시 `FALLBACK` | 없음 |
| local 이외의 프로필 | 즉시 `FALLBACK` | 없음 |

기본 설정은 `spring.ai.model.chat=none`이며, local 프로필에서만 Ollama 모델을 활성화한다.
설명 포트는 하나만 등록되어야 한다.

## 장애 계약

- 전체 요청 deadline은 `app.explanation-timeout`이며 기본값은 15초다.
- 최초 생성 뒤 숫자 검증 실패 때만 최대 두 번 재교정한다. 세 호출은 하나의 deadline을 공유한다.
- Spring AI retry는 1회로 제한하고, Ollama auto-pull은 `never`로 둔다.
- timeout, 전송 실패, 빈 응답, 허용되지 않은 숫자는 즉시 숫자 없는 `FALLBACK`으로 마감한다.

## 검증 명령

```bash
cd core-api
./gradlew spotlessCheck
./gradlew check --no-daemon
```

추가 E2E에서는 kill-switch 상태의 Ollama 네트워크 호출 0건, local Ollama `READY`,
analysis-api의 `/internal/explanations` 호출 0건, 15초 초과 시 `FALLBACK`을 확인한다.
