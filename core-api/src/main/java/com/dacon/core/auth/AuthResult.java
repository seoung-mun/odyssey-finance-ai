package com.dacon.core.auth;

/**
 * 인증 서비스가 HTTP 경계에 전달하는 발급 결과다.
 *
 * @param accessToken 응답 본문으로 전달할 access JWT
 * @param refreshToken HttpOnly 쿠키로만 전달할 refresh JWT 원문
 * @param isNewUser 로그인 중 내부 계정을 새로 만들었으면 {@code true}
 */
record AuthResult(String accessToken, String refreshToken, boolean isNewUser) {}
