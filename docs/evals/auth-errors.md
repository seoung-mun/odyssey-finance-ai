# 인증·오류 경계 평가

## 합격 조건

- Google issuer·audience·signature·exp·email_verified와 자체 JWT를 Spring Security/JOSE가 검증한다.
- access 15분, refresh 7일이며 refresh 원문은 저장하지 않고 회전·폐기한다.
- refresh cookie는 `HttpOnly; Secure; SameSite=Strict`와 refresh path를 사용한다.
- 모든 오류는 RFC 7807, `code`, `requestId`를 포함하고 모든 응답에 `X-Request-ID`가 있다.
- 프론트는 401 로그인 이동, 분리된 403/404, 409 재조회, 500 request ID·재시도, 네트워크 입력 유지, Error Boundary를 제공한다.

## 적대 테스트

- 잘못된 issuer/audience/서명, 만료 token, email 미검증, refresh 재사용과 동시 회전.
- 다른 사용자의 자원 ID, 필드 검증, unique 경합, FastAPI timeout, 예상 밖 예외.
- 연속 제출, 늦은 응답, 오프라인, 깨진 JSON, 키보드와 모바일 폭.

## 회귀

```bash
cd core-api && ./gradlew check
cd ../web && npm run lint && npm test -- --run && npm run build
```

